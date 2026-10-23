package tr.baba.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

class PasswordNeeded : Exception()

object PdfOps {

    data class Hit(val page: Int, val snippet: String)

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

    /** Şifresiz çalışma kopyası üretir. Şifre gerekiyorsa [PasswordNeeded] fırlatır. */
    fun openDecrypted(src: File, password: String?, out: File) {
        val doc = try {
            PDDocument.load(src, password ?: "", MemoryUsageSetting.setupTempFileOnly())
        } catch (e: InvalidPasswordException) {
            throw PasswordNeeded()
        }
        doc.use {
            if (it.isEncrypted) {
                it.isAllSecurityToBeRemoved = true
                it.save(out)
            } else {
                src.copyTo(out, overwrite = true)
            }
        }
    }

    fun pageCount(file: File): Int {
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(pfd).use { it.pageCount }.also { pfd.close() }
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
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (i in doc.numberOfPages - 1 downTo 0) if (i !in keep) doc.removePage(i)
            doc.save(out)
        }
    }

    fun delete(src: File, out: File, remove: Set<Int>) {
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            if (remove.size >= doc.numberOfPages) throw IllegalArgumentException("Tüm sayfalar silinemez")
            for (i in doc.numberOfPages - 1 downTo 0) if (i in remove) doc.removePage(i)
            doc.save(out)
        }
    }

    fun rotate(src: File, out: File, pages: Set<Int>, degrees: Int) {
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            for (i in pages) {
                val p = doc.getPage(i)
                p.rotation = ((p.rotation + degrees) % 360 + 360) % 360
            }
            doc.save(out)
        }
    }

    fun merge(files: List<File>, out: File) {
        val m = PDFMergerUtility()
        m.destinationFileName = out.path
        files.forEach { m.addSource(it) }
        m.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
    }

    /** Sayfaları JPEG görüntüye çevirerek küçültür (metin seçilebilirliği kaybolur). */
    fun compress(src: File, out: File, dpi: Int, quality: Float) {
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            PdfRenderer(pfd).use { r ->
                PDDocument().use { doc ->
                    val scale = dpi / 72f
                    for (i in 0 until r.pageCount) {
                        r.openPage(i).use { p ->
                            val w = p.width
                            val h = p.height
                            val bmp = Bitmap.createBitmap(
                                (w * scale).toInt().coerceAtLeast(1),
                                (h * scale).toInt().coerceAtLeast(1),
                                Bitmap.Config.ARGB_8888
                            )
                            bmp.eraseColor(Color.WHITE)
                            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            val page = PDPage(PDRectangle(w.toFloat(), h.toFloat()))
                            doc.addPage(page)
                            val img = JPEGFactory.createFromImage(doc, bmp, quality)
                            PDPageContentStream(doc, page).use { cs ->
                                cs.drawImage(img, 0f, 0f, w.toFloat(), h.toFloat())
                            }
                            bmp.recycle()
                        }
                    }
                    doc.save(out)
                }
            }
        } finally {
            pfd.close()
        }
    }

    fun encrypt(src: File, out: File, userPassword: String) {
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val policy = StandardProtectionPolicy(UUID.randomUUID().toString(), userPassword, AccessPermission())
            policy.encryptionKeyLength = 128
            doc.protect(policy)
            doc.save(out)
        }
    }

    fun extractText(src: File): String =
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { PDFTextStripper().getText(it) }

    fun search(src: File, query: String, limit: Int = 200): List<Hit> {
        val tr = Locale.forLanguageTag("tr")
        val q = query.trim().lowercase(tr)
        if (q.isEmpty()) return emptyList()
        val hits = mutableListOf<Hit>()
        PDDocument.load(src, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
            val st = PDFTextStripper()
            for (i in 1..doc.numberOfPages) {
                if (hits.size >= limit) break
                st.startPage = i
                st.endPage = i
                val text = st.getText(doc).replace('\n', ' ').replace('\r', ' ')
                val low = text.lowercase(tr)
                var from = 0
                var perPage = 0
                while (perPage < 3 && hits.size < limit) {
                    val idx = low.indexOf(q, from)
                    if (idx < 0) break
                    val s = (idx - 30).coerceAtLeast(0)
                    val e = (idx + q.length + 50).coerceAtMost(text.length)
                    hits.add(Hit(i, text.substring(s, e).trim()))
                    perPage++
                    from = idx + q.length
                }
            }
        }
        return hits
    }

    fun renderPagePng(src: File, index: Int, dpi: Int = 150): Bitmap {
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            return PdfRenderer(pfd).use { r ->
                r.openPage(index).use { p ->
                    val scale = dpi / 72f
                    val bmp = Bitmap.createBitmap(
                        (p.width * scale).toInt().coerceAtLeast(1),
                        (p.height * scale).toInt().coerceAtLeast(1),
                        Bitmap.Config.ARGB_8888
                    )
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bmp
                }
            }
        } finally {
            pfd.close()
        }
    }
}

/** Ekranda sayfa çizer. PdfRenderer aynı anda tek sayfa açabildiği için işlemler kilitlidir. */
class PageRenderer(file: File) {
    private val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(pfd)
    private val lock = Mutex()
    private var closed = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    val pageCount: Int = renderer.pageCount

    suspend fun render(index: Int, widthPx: Int): Bitmap? {
        val key = "$index:$widthPx"
        cache.get(key)?.let { return it }
        return lock.withLock {
            if (closed) return@withLock null
            withContext(Dispatchers.Default) {
                renderer.openPage(index).use { p ->
                    val h = (widthPx.toFloat() * p.height / p.width).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    cache.put(key, bmp)
                    bmp
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
                        renderer.close()
                        pfd.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }
}
