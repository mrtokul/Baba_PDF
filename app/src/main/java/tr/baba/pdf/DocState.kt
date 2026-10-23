package tr.baba.pdf

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Açık belgenin durumu. Çalışma kopyaları uygulama önbelleğinde tutulur; özgün dosyaya dokunulmaz. */
class DocState(private val app: Context) {
    var file by mutableStateOf<File?>(null)
        private set
    var name by mutableStateOf("")
        private set
    var version by mutableIntStateOf(0)
        private set
    var pageCount by mutableIntStateOf(0)
        private set
    var undoCount by mutableIntStateOf(0)
        private set
    var redoCount by mutableIntStateOf(0)
        private set
    var busy by mutableStateOf(false)
        private set
    var needPassword by mutableStateOf(false)
        private set
    var isBook by mutableStateOf(false)
        private set
    var recentVersion by mutableIntStateOf(0)
        private set
    var message by mutableStateOf<String?>(null)
    var query by mutableStateOf("")

    private var origUri: Uri? = null
    private val history = ArrayDeque<File>()
    private val redoStack = ArrayDeque<File>()
    private var pendingSrc: File? = null
    private var pendingName = ""
    private var pendingUri: Uri? = null

    private fun newFile(ext: String = "pdf") = File(app.cacheDir, "w${System.nanoTime()}.$ext")

    private fun bookCfg() = PdfOps.BookCfg(420f, 680f, Recent.bookFontSize(app))

    private suspend fun guarded(block: suspend () -> Unit) {
        busy = true
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "Hata: ${e.message ?: e.javaClass.simpleName}"
        } catch (e: OutOfMemoryError) {
            message = "Bellek yetmedi; daha küçük bir dosyayla deneyin"
        } catch (e: UnsatisfiedLinkError) {
            message = "PDF motoru yüklenemedi: ${e.message}"
        } finally {
            busy = false
        }
    }

    private fun clearHistory() {
        history.forEach { it.delete() }
        history.clear()
        redoStack.forEach { it.delete() }
        redoStack.clear()
        undoCount = 0
        redoCount = 0
    }

    private fun setDoc(f: File, nm: String, book: Boolean, uri: Uri?) {
        clearHistory()
        file?.takeIf { it != f }?.delete()
        file = f
        name = nm
        isBook = book
        origUri = uri
        query = ""
        pageCount = PdfOps.pageCount(f)
        version++
    }

    suspend fun open(uri: Uri) = guarded {
        val nm = withContext(Dispatchers.IO) { PdfOps.displayName(app, uri) }
        val ext = nm.substringAfterLast('.', "pdf").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "pdf" }
        val tmp = newFile(ext)
        withContext(Dispatchers.IO) { PdfOps.copyUri(app, uri, tmp) }
        tryOpen(tmp, null, nm, uri, ext != "pdf")
    }

    private suspend fun tryOpen(src: File, pw: String?, nm: String, uri: Uri?, book: Boolean) {
        val out = newFile()
        try {
            withContext(Dispatchers.IO) { PdfOps.openNormalized(src, pw, out, bookCfg()) }
        } catch (e: PasswordNeeded) {
            out.delete()
            if (pw != null) message = "Şifre yanlış"
            pendingSrc = src
            pendingName = nm
            pendingUri = uri
            needPassword = true
            return
        } catch (e: Exception) {
            src.delete()
            out.delete()
            throw e
        }
        src.delete()
        pendingSrc = null
        needPassword = false
        withContext(Dispatchers.IO) { setDoc(out, nm, book, uri) }
        if (uri != null) {
            Recent.add(app, nm, uri)
            recentVersion++
        }
    }

    suspend fun submitPassword(pw: String) = guarded {
        val src = pendingSrc ?: return@guarded
        val nm = pendingName
        tryOpen(src, pw, nm, pendingUri, !src.name.endsWith(".pdf"))
    }

    fun cancelPassword() {
        pendingSrc?.delete()
        pendingSrc = null
        needPassword = false
    }

    fun clearRecent() {
        Recent.clear(app)
        recentVersion++
    }

    private fun push(f: File) {
        history.addLast(f)
        if (history.size > 8) history.removeFirst().delete()
        redoStack.forEach { it.delete() }
        redoStack.clear()
        undoCount = history.size
        redoCount = 0
    }

    private suspend fun swapIn(out: File, okMessage: String?) {
        val src = file ?: return
        val count = withContext(Dispatchers.IO) { PdfOps.pageCount(out) }
        push(src)
        file = out
        pageCount = count
        version++
        if (okMessage != null) message = okMessage
    }

    /** Çalışma kopyasını değiştiren işlem; geri alınabilir. */
    suspend fun transform(okMessage: String, block: (src: File, out: File) -> Unit) = guarded {
        val src = file ?: return@guarded
        val out = newFile()
        try {
            withContext(Dispatchers.IO) { block(src, out) }
            swapIn(out, okMessage)
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    /** Küçültme: sonuç büyükse uygulanmaz. */
    suspend fun compress(raster: Boolean, dpi: Int = 110, quality: Float = 0.65f) = guarded {
        val src = file ?: return@guarded
        val out = newFile()
        try {
            withContext(Dispatchers.IO) {
                if (raster) PdfOps.compressRaster(src, out, dpi, quality) else PdfOps.compressStructure(src, out)
            }
            val before = src.length()
            val after = out.length()
            if (after >= before) {
                out.delete()
                message = "Küçülmedi (${kb(before)} → ${kb(after)}); dosya değişmedi"
                return@guarded
            }
            swapIn(out, "Küçültüldü: ${kb(before)} → ${kb(after)}")
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    private fun kb(b: Long): String =
        if (b >= 1024 * 1024) "%.1f MB".format(b / 1048576.0) else "${b / 1024} KB"

    suspend fun merge(uris: List<Uri>) = guarded {
        val parts = mutableListOf<File>()
        val temps = mutableListOf<File>()
        val out = newFile()
        try {
            withContext(Dispatchers.IO) {
                file?.let { parts.add(it) }
                for (u in uris) {
                    val nm = PdfOps.displayName(app, u)
                    val ext = nm.substringAfterLast('.', "pdf").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "pdf" }
                    val t = newFile(ext)
                    val d = newFile()
                    temps.add(t)
                    temps.add(d)
                    PdfOps.copyUri(app, u, t)
                    try {
                        PdfOps.openNormalized(t, null, d, bookCfg())
                    } catch (e: PasswordNeeded) {
                        throw IOException("Şifreli dosya birleştirilemez: $nm")
                    }
                    parts.add(d)
                }
                if (parts.size < 2) throw IOException("Birleştirmek için en az iki belge gerekir")
                PdfOps.merge(parts, out)
            }
            if (file == null) {
                withContext(Dispatchers.IO) { setDoc(out, "birlestirilmis.pdf", false, null) }
                message = "Birleştirildi"
            } else {
                swapIn(out, "Birleştirildi")
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            temps.forEach { it.delete() }
        }
    }

    fun undo() {
        if (history.isEmpty()) return
        val cur = file ?: return
        val prev = history.removeLast()
        redoStack.addLast(cur)
        file = prev
        undoCount = history.size
        redoCount = redoStack.size
        pageCount = PdfOps.pageCount(prev)
        version++
        message = "Geri alındı"
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val cur = file ?: return
        val next = redoStack.removeLast()
        history.addLast(cur)
        file = next
        undoCount = history.size
        redoCount = redoStack.size
        pageCount = PdfOps.pageCount(next)
        version++
        message = "Yinelendi"
    }

    private fun write(uri: Uri, action: (OutputStream) -> Unit) {
        val os = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Dosya yazılamadı")
        os.use(action)
    }

    val canOverwrite: Boolean get() = origUri != null && !isBook

    /** Özgün dosyanın üzerine yazar (e-kitaplarda kullanılmaz). */
    suspend fun saveOriginal() = guarded {
        val src = file ?: return@guarded
        val u = origUri ?: throw IOException("Özgün dosya yok; Farklı Kaydet'i kullanın")
        try {
            withContext(Dispatchers.IO) { write(u) { o -> src.inputStream().use { it.copyTo(o) } } }
        } catch (e: Exception) {
            throw IOException("Özgün dosyaya yazılamadı; Farklı Kaydet'i kullanın")
        }
        message = "Özgün dosya güncellendi"
    }

    suspend fun exportPdf(uri: Uri) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) { write(uri) { o -> src.inputStream().use { it.copyTo(o) } } }
        message = "Kaydedildi"
    }

    suspend fun exportEncrypted(uri: Uri, password: String) = guarded {
        val src = file ?: return@guarded
        val tmp = newFile()
        try {
            withContext(Dispatchers.IO) {
                PdfOps.encrypt(src, tmp, password)
                write(uri) { o -> tmp.inputStream().use { it.copyTo(o) } }
            }
            message = "Şifreli kopya kaydedildi (açık belge şifresiz kaldı)"
        } finally {
            tmp.delete()
        }
    }

    suspend fun exportText(uri: Uri, pages: Set<Int>?) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) {
            val text = PdfOps.extractText(src, pages)
            write(uri) { o -> o.write(text.toByteArray(Charsets.UTF_8)) }
        }
        message = "Metin kaydedildi"
    }

    suspend fun writeText(uri: Uri, text: String) = guarded {
        withContext(Dispatchers.IO) { write(uri) { o -> o.write(text.toByteArray(Charsets.UTF_8)) } }
        message = "Kaydedildi"
    }

    suspend fun exportPng(uri: Uri, pageIndex: Int) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) {
            val bmp = PdfOps.renderPng(src, pageIndex)
            write(uri) { o -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, o) }
            bmp.recycle()
        }
        message = "Sayfa resim olarak kaydedildi"
    }

    suspend fun exportPngZip(uri: Uri, pages: Set<Int>) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) { write(uri) { o -> PdfOps.exportPngZip(src, pages, o) } }
        message = "Resimler ZIP olarak kaydedildi"
    }

    suspend fun exportSplitZip(uri: Uri, each: Int) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) { write(uri) { o -> PdfOps.splitZip(src, each, app.cacheDir, o) } }
        message = "Parçalar ZIP olarak kaydedildi"
    }

    suspend fun search(q: String): List<PdfOps.Hit> {
        val src = file ?: return emptyList()
        busy = true
        return try {
            withContext(Dispatchers.IO) { PdfOps.search(src, q) }
        } catch (e: Exception) {
            message = "Hata: ${e.message ?: e.javaClass.simpleName}"
            emptyList()
        } finally {
            busy = false
        }
    }

    suspend fun info(): Map<String, String> {
        val src = file ?: return emptyMap()
        return withContext(Dispatchers.IO) {
            val m = LinkedHashMap(PdfOps.metadata(src))
            m["Dosya boyutu"] = kb(src.length())
            m
        }
    }

    suspend fun bookmarks(): List<PdfOps.Mark> {
        val src = file ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                PdfOps.bookmarks(src)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Sonuç değeri olan, geri alınabilir işlem. */
    suspend fun <R> transformWith(okMessage: (R) -> String, block: (src: File, out: File) -> R) = guarded {
        val src = file ?: return@guarded
        val out = newFile()
        try {
            val r = withContext(Dispatchers.IO) { block(src, out) }
            swapIn(out, okMessage(r))
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    suspend fun readBytes(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("Dosya açılamadı")
        input.use { it.readBytes() }
    }

    suspend fun signWith(p12: Uri, pw: String, page: Int, visible: Boolean, reason: String, loc: String) {
        val tmp = newFile("p12")
        try {
            withContext(Dispatchers.IO) { PdfOps.copyUri(app, p12, tmp) }
            transform("Belge imzalandı (artımlı kayıt)") { s, o ->
                Signing.sign(s, o, tmp, pw, page, visible, reason, loc)
            }
        } finally {
            tmp.delete()
        }
    }

    suspend fun ocrSearchable(pages: Set<Int>) =
        transformWith<Int>({ n -> if (n == 0) "Sayfalarda metin bulunamadı" else "$n sayfaya aranabilir metin katmanı eklendi" }) { s, o ->
            Ocr.makeSearchable(app, s, o, pages)
        }

    suspend fun ocrText(uri: Uri, pages: Set<Int>) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) {
            val t = Ocr.toText(app, src, pages)
            write(uri) { o -> o.write(t.toByteArray(Charsets.UTF_8)) }
        }
        message = "OCR metni kaydedildi"
    }

    suspend fun signReport(): String {
        val src = file ?: return ""
        busy = true
        return try {
            withContext(Dispatchers.IO) { Signing.report(src, name) }
        } catch (e: Exception) {
            "Hata: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    /** Seçilen belgeyle açık belgeyi karşılaştırır; rapor metnini döndürür. */
    suspend fun compareWith(uri: Uri): String {
        val a = file ?: return ""
        busy = true
        val nm = withContext(Dispatchers.IO) { PdfOps.displayName(app, uri) }
        val ext = nm.substringAfterLast('.', "pdf").lowercase().filter { it.isLetterOrDigit() }.ifEmpty { "pdf" }
        val t = newFile(ext)
        val d = newFile()
        return try {
            withContext(Dispatchers.IO) {
                PdfOps.copyUri(app, uri, t)
                try {
                    PdfOps.openNormalized(t, null, d, bookCfg())
                } catch (e: PasswordNeeded) {
                    throw IOException("Şifreli belge karşılaştırılamaz")
                }
                "Seçilen: $nm\n" + PdfOps2.compare(a, d)
            }
        } catch (e: Exception) {
            "Hata: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            t.delete()
            d.delete()
            busy = false
        }
    }

    suspend fun check(): String {
        val src = file ?: return ""
        busy = true
        return try {
            withContext(Dispatchers.IO) { PdfOps2.check(src) }
        } catch (e: Exception) {
            "Hata: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            busy = false
        }
    }

    suspend fun attachments(): List<PdfOps2.Att> {
        val src = file ?: return emptyList()
        return withContext(Dispatchers.IO) {
            try {
                PdfOps2.attachments(src)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    suspend fun exportAttachment(uri: Uri, index: Int) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) { write(uri) { o -> PdfOps2.exportAttachment(src, index, o) } }
        message = "Ek kaydedildi"
    }
}
