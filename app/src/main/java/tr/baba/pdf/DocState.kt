package tr.baba.pdf

import android.content.Context
import android.graphics.Bitmap
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
    var busy by mutableStateOf(false)
        private set
    var needPassword by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)

    private val history = ArrayDeque<File>()
    private var pendingSrc: File? = null
    private var pendingName = ""

    private fun newFile() = File(app.cacheDir, "w${System.nanoTime()}.pdf")

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
        } finally {
            busy = false
        }
    }

    private fun setDoc(f: File, nm: String) {
        history.forEach { it.delete() }
        history.clear()
        undoCount = 0
        file?.takeIf { it != f }?.delete()
        file = f
        name = nm
        pageCount = PdfOps.pageCount(f)
        version++
    }

    suspend fun open(uri: Uri) = guarded {
        val tmp = newFile()
        val nm = withContext(Dispatchers.IO) {
            PdfOps.copyUri(app, uri, tmp)
            PdfOps.displayName(app, uri)
        }
        tryOpen(tmp, null, nm)
    }

    private suspend fun tryOpen(src: File, pw: String?, nm: String) {
        val out = newFile()
        try {
            withContext(Dispatchers.IO) { PdfOps.openDecrypted(src, pw, out) }
        } catch (e: PasswordNeeded) {
            out.delete()
            if (pw != null) message = "Şifre yanlış"
            pendingSrc = src
            pendingName = nm
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
        withContext(Dispatchers.IO) { setDoc(out, nm) }
    }

    suspend fun submitPassword(pw: String) = guarded {
        val src = pendingSrc ?: return@guarded
        tryOpen(src, pw, pendingName)
    }

    fun cancelPassword() {
        pendingSrc?.delete()
        pendingSrc = null
        needPassword = false
    }

    private fun push(f: File) {
        history.addLast(f)
        if (history.size > 5) history.removeFirst().delete()
        undoCount = history.size
    }

    /** Çalışma kopyasını değiştiren işlem; geri alınabilir. */
    suspend fun transform(okMessage: String, block: (src: File, out: File) -> Unit) = guarded {
        val src = file ?: return@guarded
        val out = newFile()
        try {
            withContext(Dispatchers.IO) { block(src, out) }
            val count = withContext(Dispatchers.IO) { PdfOps.pageCount(out) }
            push(src)
            file = out
            pageCount = count
            version++
            message = okMessage
        } catch (e: Exception) {
            out.delete()
            throw e
        }
    }

    /** Küçültme: sonuç büyükse uygulanmaz. */
    suspend fun compress(dpi: Int, quality: Float) = guarded {
        val src = file ?: return@guarded
        val out = newFile()
        try {
            withContext(Dispatchers.IO) { PdfOps.compress(src, out, dpi, quality) }
            val before = src.length()
            val after = out.length()
            if (after >= before) {
                out.delete()
                message = "Küçülmedi (${kb(before)} → ${kb(after)}); dosya değişmedi"
                return@guarded
            }
            val count = withContext(Dispatchers.IO) { PdfOps.pageCount(out) }
            push(src)
            file = out
            pageCount = count
            version++
            message = "Küçültüldü: ${kb(before)} → ${kb(after)}"
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
                    val t = newFile()
                    val d = newFile()
                    temps.add(t)
                    temps.add(d)
                    PdfOps.copyUri(app, u, t)
                    try {
                        PdfOps.openDecrypted(t, null, d)
                    } catch (e: PasswordNeeded) {
                        throw IOException("Şifreli dosya birleştirilemez: ${PdfOps.displayName(app, u)}")
                    }
                    parts.add(d)
                }
                if (parts.size < 2) throw IOException("Birleştirmek için en az iki PDF gerekir")
                PdfOps.merge(parts, out)
            }
            val old = file
            if (old == null) {
                withContext(Dispatchers.IO) { setDoc(out, "birlestirilmis.pdf") }
            } else {
                val count = withContext(Dispatchers.IO) { PdfOps.pageCount(out) }
                push(old)
                file = out
                pageCount = count
                version++
            }
            message = "Birleştirildi"
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            temps.forEach { it.delete() }
        }
    }

    fun undo() {
        if (history.isEmpty()) return
        val prev = history.removeLast()
        file?.delete()
        file = prev
        undoCount = history.size
        pageCount = PdfOps.pageCount(prev)
        version++
        message = "Geri alındı"
    }

    private fun write(uri: Uri, action: (java.io.OutputStream) -> Unit) {
        val os = app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Dosya yazılamadı")
        os.use(action)
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
            message = "Şifreli kopya kaydedildi (özgün belge şifresiz kaldı)"
        } finally {
            tmp.delete()
        }
    }

    suspend fun exportText(uri: Uri) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) {
            val text = PdfOps.extractText(src)
            write(uri) { o -> o.write(text.toByteArray(Charsets.UTF_8)) }
        }
        message = "Metin kaydedildi"
    }

    suspend fun exportPng(uri: Uri, pageIndex: Int) = guarded {
        val src = file ?: return@guarded
        withContext(Dispatchers.IO) {
            val bmp = PdfOps.renderPagePng(src, pageIndex)
            write(uri) { o -> bmp.compress(Bitmap.CompressFormat.PNG, 100, o) }
            bmp.recycle()
        }
        message = "Sayfa resim olarak kaydedildi"
    }

    suspend fun search(query: String): List<PdfOps.Hit> {
        val src = file ?: return emptyList()
        busy = true
        return try {
            withContext(Dispatchers.IO) { PdfOps.search(src, query) }
        } catch (e: Exception) {
            message = "Hata: ${e.message ?: e.javaClass.simpleName}"
            emptyList()
        } finally {
            busy = false
        }
    }
}
