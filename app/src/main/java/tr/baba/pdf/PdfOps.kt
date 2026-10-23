package tr.baba.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.DocumentWriter
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PasswordNeeded : Exception()

object PdfOps {

    data class Hit(val page: Int, val snippet: String)
    data class Mark(val title: String, val page: Int, val level: Int)
    data class BookCfg(val w: Float, val h: Float, val em: Float)

    private const val SAVE = "garbage=compact,compress=yes"

    fun copyUri(ctx: Context, uri: Uri, dest: File) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IOException("Dosya açılamadı")
        input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
    }

    fun displayName(ctx: Context, uri: Uri): String {
        val n = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return n ?: (uri.lastPathSegment ?: "belge.pdf")
    }

    /**
     * Çalışma kopyası üretir: şifre çözülür, e-kitaplar sayfalı PDF'e çevrilir.
     * Şifre gerekiyorsa [PasswordNeeded] fırlatır. [src] uzantısı biçimi belirler.
     */
    fun openNormalized(src: File, password: String?, out: File, cfg: BookCfg) {
        val d = Document.openDocument(src.path)
        try {
            if (d.needsPassword()) {
                if (password == null || !d.authenticatePassword(password)) throw PasswordNeeded()
            }
            if (d.isPDF()) {
                if (d.isUnencryptedPDF()) {
                    src.copyTo(out, overwrite = true)
                } else {
                    d.asPDF().save(out.path, "decrypt=yes")
                }
            } else {
                d.layout(cfg.w, cfg.h, cfg.em)
                val writer = DocumentWriter(out.path, "pdf", "")
                try {
                    val n = d.countPages()
                    for (i in 0 until n) {
                        val p = d.loadPage(i)
                        try {
                            val dev = writer.beginPage(p.getBounds())
                            p.run(dev, Matrix.Identity(), null)
                            dev.close()
                            writer.endPage()
                        } finally {
                            p.destroy()
                        }
                    }
                    writer.close()
                } finally {
                    writer.destroy()
                }
            }
        } finally {
            d.destroy()
        }
    }

    fun pageCount(file: File): Int {
        val d = Document.openDocument(file.path)
        try {
            return d.countPages()
        } finally {
            d.destroy()
        }
    }

    private inline fun <T> withPdf(src: File, block: (PDFDocument) -> T): T {
        val d = Document.openDocument(src.path)
        try {
            return block(d.asPDF())
        } finally {
            d.destroy()
        }
    }

    private fun toPage(s: String, part: String): Int =
        s.trim().toIntOrNull() ?: throw IllegalArgumentException("Geçersiz sayı: $part")

    /** "1-3,5,8-" biçimini 0 tabanlı sayfa kümesine çevirir. */
    fun parseRange(text: String, total: Int): Set<Int> {
        val result = sortedSetOf<Int>()
        for (part in text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val m = part.split('-')
            val a: Int
            val b: Int
            when (m.size) {
                1 -> { a = toPage(m[0], part); b = a }
                2 -> {
                    a = if (m[0].isBlank()) 1 else toPage(m[0], part)
                    b = if (m[1].isBlank()) total else toPage(m[1], part)
                }
                else -> throw IllegalArgumentException("Geçersiz aralık: $part")
            }
            if (a < 1 || b > total || a > b) {
                throw IllegalArgumentException("Sayfa aralığı 1-$total içinde olmalı: $part")
            }
            for (i in a..b) result.add(i - 1)
        }
        if (result.isEmpty()) throw IllegalArgumentException("Sayfa seçilmedi")
        return result
    }

    /** Yalnızca [keep] içindeki sayfaları tutar. */
    fun keep(src: File, out: File, keep: Set<Int>) {
        withPdf(src) { pdf ->
            for (i in pdf.countPages() - 1 downTo 0) if (i !in keep) pdf.deletePage(i)
            pdf.save(out.path, SAVE)
        }
    }

    fun delete(src: File, out: File, remove: Set<Int>) {
        withPdf(src) { pdf ->
            if (remove.size >= pdf.countPages()) throw IllegalArgumentException("Tüm sayfalar silinemez")
            for (i in pdf.countPages() - 1 downTo 0) if (i in remove) pdf.deletePage(i)
            pdf.save(out.path, SAVE)
        }
    }

    fun rotate(src: File, out: File, pages: Set<Int>, degrees: Int) {
        withPdf(src) { pdf ->
            for (i in pages) {
                val po = pdf.findPage(i)
                var cur = 0
                var o = po
                var guard = 0
                while (o != null && !o.isNull() && guard < 32) {
                    val r = o.get("Rotate")
                    if (r != null && r.isNumber()) {
                        cur = r.asInteger()
                        break
                    }
                    o = o.get("Parent")
                    guard++
                }
                po.put("Rotate", ((cur + degrees) % 360 + 360) % 360)
            }
            pdf.save(out.path, SAVE)
        }
    }

    /** [at] konumuna (0 tabanlı) boş sayfa ekler; boyut komşu sayfadan alınır. */
    fun blankPage(src: File, out: File, at: Int) {
        withPdf(src) { pdf ->
            val n = pdf.countPages()
            if (at < 0 || at > n) throw IllegalArgumentException("Konum 0-$n içinde olmalı")
            val ref = pdf.loadPage(if (at > 0) at - 1 else 0)
            val box = Rect(ref.getBounds())
            ref.destroy()
            val page = pdf.addPage(box, 0, pdf.newDictionary(), "")
            pdf.insertPage(at, page)
            pdf.save(out.path, SAVE)
        }
    }

    /** [from] sayfasını [to] konumuna taşır (ikisi de 0 tabanlı). */
    fun movePage(src: File, out: File, from: Int, to: Int) {
        withPdf(src) { pdf ->
            val n = pdf.countPages()
            if (from < 0 || from >= n || to < 0 || to >= n) throw IllegalArgumentException("Sayfa numarası 1-$n içinde olmalı")
            val order = (0 until n).toMutableList()
            val x = order.removeAt(from)
            order.add(to, x)
            pdf.rearrangePages(order.toIntArray())
            pdf.save(out.path, SAVE)
        }
    }

    fun merge(files: List<File>, out: File) {
        val dst = Document.openDocument(files[0].path)
        try {
            val pdf = dst.asPDF()
            for (k in 1 until files.size) {
                val s = Document.openDocument(files[k].path)
                try {
                    val sp = s.asPDF()
                    val cnt = s.countPages()
                    for (i in 0 until cnt) pdf.graftPage(pdf.countPages(), sp, i)
                } finally {
                    s.destroy()
                }
            }
            pdf.save(out.path, SAVE)
        } finally {
            dst.destroy()
        }
    }

    /** Yapıyı sıkıştırır; metin ve vektörler korunur. */
    fun compressStructure(src: File, out: File) {
        withPdf(src) { pdf ->
            pdf.save(out.path, "garbage=4,compress=yes,compress-images=yes,compress-fonts=yes")
        }
    }

    /** Sayfaları JPEG görüntüye çevirerek küçültür (metin seçilebilirliği kaybolur). */
    fun compressRaster(src: File, out: File, dpi: Int, quality: Float) {
        val d = Document.openDocument(src.path)
        try {
            val dst = PDFDocument()
            val n = d.countPages()
            for (i in 0 until n) {
                Progress.step(i, n)
                val p = d.loadPage(i)
                val b = Rect(p.getBounds())
                val w = b.x1 - b.x0
                val h = b.y1 - b.y0
                val bmp = AndroidDrawDevice.drawPage(p, dpi.toFloat())
                p.destroy()
                val bos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, (quality * 100).toInt().coerceIn(10, 95), bos)
                bmp.recycle()
                val img = dst.addImage(com.artifex.mupdf.fitz.Image(bos.toByteArray()))
                val res = dst.newDictionary()
                val xo = dst.newDictionary()
                xo.put("Im0", img)
                res.put("XObject", xo)
                val ws = String.format(Locale.US, "%.2f", w)
                val hs = String.format(Locale.US, "%.2f", h)
                val pg = dst.addPage(Rect(0f, 0f, w, h), 0, res, "q $ws 0 0 $hs 0 0 cm /Im0 Do Q")
                dst.insertPage(-1, pg)
            }
            dst.save(out.path, SAVE)
            dst.destroy()
        } finally {
            d.destroy()
        }
    }

    fun encrypt(src: File, out: File, userPassword: String) {
        if (userPassword.contains(',') || userPassword.contains('=')) {
            throw IllegalArgumentException("Şifre ',' veya '=' içeremez")
        }
        val owner = java.util.UUID.randomUUID().toString()
        withPdf(src) { pdf ->
            pdf.save(out.path, "garbage=compact,compress=yes,encrypt=aes-256,user-password=$userPassword,owner-password=$owner")
        }
    }

    fun metadata(src: File): Map<String, String> {
        val d = Document.openDocument(src.path)
        try {
            val keys = linkedMapOf(
                "Başlık" to Document.META_INFO_TITLE,
                "Yazar" to Document.META_INFO_AUTHOR,
                "Konu" to Document.META_INFO_SUBJECT,
                "Anahtar sözcükler" to Document.META_INFO_KEYWORDS,
                "Oluşturan program" to Document.META_INFO_CREATOR,
                "PDF üretici" to Document.META_INFO_PRODUCER,
                "Oluşturma tarihi" to Document.META_INFO_CREATIONDATE,
                "Değiştirme tarihi" to Document.META_INFO_MODIFICATIONDATE,
                "Biçim" to Document.META_FORMAT,
                "Şifreleme" to Document.META_ENCRYPTION
            )
            val result = linkedMapOf<String, String>()
            for ((label, key) in keys) result[label] = d.getMetaData(key) ?: ""
            result["Sayfa sayısı"] = d.countPages().toString()
            return result
        } finally {
            d.destroy()
        }
    }

    fun setMetadata(src: File, out: File, values: Map<String, String>) {
        withPdf(src) { pdf ->
            for ((k, v) in values) pdf.setMetaData(k, v)
            pdf.save(out.path, SAVE)
        }
    }

    fun bookmarks(f: File): List<Mark> {
        val d = Document.openDocument(f.path)
        try {
            val top = d.loadOutline() ?: return emptyList()
            val res = mutableListOf<Mark>()
            fun walk(arr: Array<Outline>, level: Int) {
                for (x in arr) {
                    val pg = try {
                        d.pageNumberFromLocation(d.resolveLink(x))
                    } catch (e: Exception) {
                        -1
                    }
                    res.add(Mark(x.title ?: "", pg, level))
                    val kids = x.down
                    if (kids != null) walk(kids, level + 1)
                }
            }
            walk(top, 0)
            return res
        } finally {
            d.destroy()
        }
    }

    /** [page] (1 tabanlı) sayfasına yeni üst düzey yer imi ekler. */
    fun addBookmark(src: File, out: File, title: String, page: Int) {
        withPdf(src) { pdf ->
            val oi = pdf.outlineIterator()
            try {
                while (oi.next() == 0) {
                    // son üst düzey öğeye ilerle
                }
                oi.insert(title, "#page=$page", false)
            } finally {
                oi.destroy()
            }
            pdf.save(out.path, SAVE)
        }
    }

    fun extractText(src: File, pages: Set<Int>?): String {
        val d = Document.openDocument(src.path)
        try {
            val sb = StringBuilder()
            val n = d.countPages()
            for (i in 0 until n) {
                if (pages != null && i !in pages) continue
                val p = d.loadPage(i)
                try {
                    val st = p.toStructuredText()
                    sb.append(st.asText()).append("\n\n")
                    st.destroy()
                } finally {
                    p.destroy()
                }
            }
            return sb.toString()
        } finally {
            d.destroy()
        }
    }

    fun search(src: File, query: String, limit: Int = 200): List<Hit> {
        val tr = Locale.forLanguageTag("tr")
        val q = query.trim().lowercase(tr)
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        val d = Document.openDocument(src.path)
        try {
            val n = d.countPages()
            for (i in 0 until n) {
                if (hits.size >= limit) break
                val p = d.loadPage(i)
                try {
                    val st = p.toStructuredText()
                    val text = st.asText().replace('\n', ' ').replace('\r', ' ')
                    st.destroy()
                    val low = text.lowercase(tr)
                    var from = 0
                    var perPage = 0
                    while (perPage < 3 && hits.size < limit) {
                        val idx = low.indexOf(q, from)
                        if (idx < 0) break
                        val s = (idx - 30).coerceAtLeast(0)
                        val e = (idx + q.length + 50).coerceAtMost(text.length)
                        hits.add(Hit(i + 1, text.substring(s, e).trim()))
                        perPage++
                        from = idx + q.length
                    }
                } finally {
                    p.destroy()
                }
            }
        } finally {
            d.destroy()
        }
        return hits
    }

    fun renderPng(src: File, index: Int, dpi: Float = 150f): Bitmap {
        val d = Document.openDocument(src.path)
        try {
            val p = d.loadPage(index)
            try {
                return AndroidDrawDevice.drawPage(p, dpi)
            } finally {
                p.destroy()
            }
        } finally {
            d.destroy()
        }
    }

    fun exportPngZip(src: File, pages: Set<Int>, os: OutputStream) {
        val z = ZipOutputStream(os)
        val d = Document.openDocument(src.path)
        try {
            var kk = 0
            for (i in pages) {
                Progress.step(kk++, pages.size)
                val p = d.loadPage(i)
                try {
                    val bmp = AndroidDrawDevice.drawPage(p, 150f)
                    z.putNextEntry(ZipEntry("sayfa_%03d.png".format(i + 1)))
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, z)
                    z.closeEntry()
                    bmp.recycle()
                } finally {
                    p.destroy()
                }
            }
        } finally {
            d.destroy()
        }
        z.finish()
        z.flush()
    }

    /** Belgeyi her [each] sayfada bir parça olacak şekilde bölüp ZIP içine yazar. */
    fun splitZip(src: File, each: Int, tmpDir: File, os: OutputStream) {
        val total = pageCount(src)
        val z = ZipOutputStream(os)
        var start = 0
        var part = 1
        while (start < total) {
            val end = minOf(start + each, total)
            val tmp = File(tmpDir, "part${System.nanoTime()}.pdf")
            try {
                keep(src, tmp, (start until end).toSet())
                z.putNextEntry(ZipEntry("parca_%02d_sayfa_%d-%d.pdf".format(part, start + 1, end)))
                tmp.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            } finally {
                tmp.delete()
            }
            start = end
            part++
        }
        z.finish()
        z.flush()
    }
}

/** Ekranda sayfa çizer. MuPDF belgesi tek iş parçacığından kullanıldığı için işlemler kilitlidir. */
class PageRenderer(file: File) {
    private val doc: Document = Document.openDocument(file.path)
    private val lock = Mutex()
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int = doc.countPages()

    suspend fun render(index: Int, widthPx: Int, query: String): Bitmap? {
        val key = "$index:$widthPx:$query"
        cache.get(key)?.let { return it }
        return lock.withLock {
            if (closed) return@withLock null
            withContext(Dispatchers.Default) {
                val page = doc.loadPage(index)
                try {
                    val ctm = AndroidDrawDevice.fitPageWidth(page, widthPx)
                    val bmp = AndroidDrawDevice.drawPage(page, ctm)
                    if (query.isNotEmpty()) {
                        val hits = page.search(query)
                        if (hits != null) {
                            val origin = Rect(page.getBounds()).transform(ctm)
                            val ox = Math.floor(origin.x0.toDouble()).toFloat()
                            val oy = Math.floor(origin.y0.toDouble()).toFloat()
                            val canvas = Canvas(bmp)
                            val paint = Paint()
                            paint.color = Color.argb(100, 255, 200, 0)
                            for (hit in hits) {
                                for (q in hit) {
                                    val r = Rect(q).transform(ctm)
                                    canvas.drawRect(r.x0 - ox, r.y0 - oy, r.x1 - ox, r.y1 - oy, paint)
                                }
                            }
                        }
                    }
                    cache.put(key, bmp)
                    bmp
                } finally {
                    page.destroy()
                }
            }
        }
    }

    fun close() {
        scope.launch {
            lock.withLock {
                if (!closed) {
                    closed = true
                    try {
                        doc.destroy()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
