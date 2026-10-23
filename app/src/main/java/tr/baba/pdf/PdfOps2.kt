package tr.baba.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.Locale

/** Aşama 2: açıklamalar, damga/filigran, karartma, temizlik, izinler, karşılaştırma, ekler. */
object PdfOps2 {

    private const val SAVE = "garbage=compact,compress=yes"

    data class Att(val name: String, val size: Int)

    private inline fun <T> withPdf(src: File, block: (PDFDocument) -> T): T {
        val d = Document.openDocument(src.path)
        try {
            return block(d.asPDF())
        } finally {
            d.destroy()
        }
    }

    internal fun inherited(po: PDFObject, key: String): PDFObject? {
        var o: PDFObject? = po
        var guard = 0
        while (o != null && !o.isNull() && guard < 32) {
            val v = o.get(key)
            if (v != null && !v.isNull()) return v
            o = o.get("Parent")
            guard++
        }
        return null
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.3f", v)

    // ---------------------------------------------------------------- damga / filigran

    fun parseColor(s: String): Int = when (s.trim().lowercase(Locale.forLanguageTag("tr"))) {
        "siyah" -> Color.BLACK
        "gri" -> Color.rgb(110, 110, 110)
        "mavi" -> Color.rgb(30, 80, 200)
        "yeşil", "yesil" -> Color.rgb(20, 130, 50)
        "turuncu" -> Color.rgb(230, 120, 20)
        else -> Color.rgb(200, 30, 30) // kırmızı
    }

    private fun textBitmap(text: String, sizePt: Float, color: Int, angle: Float): Bitmap {
        val k = 4f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = sizePt * k
        paint.color = color
        paint.typeface = Typeface.DEFAULT_BOLD
        val lines = text.split("\n")
        val fm = paint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.1f
        var w = 1f
        for (l in lines) w = maxOf(w, paint.measureText(l))
        val pad = 8f
        val bw = (w + pad * 2).toInt().coerceAtLeast(2)
        val bh = (lineH * lines.size + pad * 2).toInt().coerceAtLeast(2)
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        var y = pad - fm.ascent
        for (l in lines) {
            c.drawText(l, pad, y, paint)
            y += lineH
        }
        if (angle == 0f) return bmp
        val m = android.graphics.Matrix()
        m.postRotate(-angle)
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        bmp.recycle()
        return rotated
    }

    private fun png(b: Bitmap): ByteArray {
        val o = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.PNG, 100, o)
        return o.toByteArray()
    }

    /** Sayfaya görüntü yerleştirir. [anchor] 1..9 (1 sol üst, 5 orta, 9 sağ alt). Ölçü: puan (pt). */
    private fun placeImage(
        pdf: PDFDocument, page: Int, img: PDFObject, wPt: Float, hPt: Float,
        anchor: Int, margin: Float, opacity: Float, tag: String
    ) {
        val po = pdf.findPage(page)
        var x0 = 0f
        var y0 = 0f
        var pw = 595f
        var ph = 842f
        val mb = inherited(po, "MediaBox")
        if (mb != null && mb.isArray() && mb.size() >= 4) {
            val a = mb.get(0).asFloat()
            val b = mb.get(1).asFloat()
            val c = mb.get(2).asFloat()
            val d = mb.get(3).asFloat()
            x0 = minOf(a, c)
            y0 = minOf(b, d)
            pw = Math.abs(c - a)
            ph = Math.abs(d - b)
        }
        val a0 = (anchor - 1).coerceIn(0, 8)
        val col = a0 % 3
        val row = a0 / 3
        val x = x0 + when (col) { 0 -> margin; 1 -> (pw - wPt) / 2; else -> pw - wPt - margin }
        val y = y0 + when (row) { 0 -> ph - hPt - margin; 1 -> (ph - hPt) / 2; else -> margin }

        var res = inherited(po, "Resources")
        if (res == null) {
            res = pdf.newDictionary()
            po.put("Resources", res)
        }
        var xo = res.get("XObject")
        if (xo == null || xo.isNull() || !xo.isDictionary()) {
            xo = pdf.newDictionary()
            res.put("XObject", xo)
        }
        val imName = "BabaIm$tag"
        xo.put(imName, img)
        var gs = res.get("ExtGState")
        if (gs == null || gs.isNull() || !gs.isDictionary()) {
            gs = pdf.newDictionary()
            res.put("ExtGState", gs)
        }
        val gsName = "BabaGs$tag"
        val gd = pdf.newDictionary()
        gd.put("ca", opacity)
        gd.put("CA", opacity)
        gs.put(gsName, gd)

        val old = po.get("Contents")
        val arr = pdf.newArray()
        arr.push(pdf.addStream("q"))
        if (old != null && !old.isNull()) {
            if (old.isArray()) {
                for (k in 0 until old.size()) arr.push(old.get(k))
            } else {
                arr.push(old)
            }
        }
        arr.push(pdf.addStream("Q"))
        arr.push(pdf.addStream("q /$gsName gs ${f(wPt)} 0 0 ${f(hPt)} ${f(x)} ${f(y)} cm /$imName Do Q"))
        po.put("Contents", arr)
    }

    /** Metin damgası/filigran/üst-alt bilgi. {n} sayfa no, {N} toplam, {tarih} bugün. */
    fun stampText(
        src: File, out: File, pages: Set<Int>, text: String, sizePt: Float, color: Int,
        anchor: Int, angle: Float, opacity: Float
    ) {
        val date = java.text.SimpleDateFormat("dd.MM.yyyy", Locale("tr")).format(java.util.Date())
        withPdf(src) { pdf ->
            val total = pdf.countPages()
            val cache = HashMap<String, PDFObject>()
            val dims = HashMap<String, Pair<Float, Float>>()
            for (i in pages) {
                val t = text.replace("{n}", (i + 1).toString()).replace("{N}", total.toString()).replace("{tarih}", date)
                var img = cache[t]
                if (img == null) {
                    val bmp = textBitmap(t, sizePt, color, angle)
                    img = pdf.addImage(Image(png(bmp)))
                    cache[t] = img
                    dims[t] = Pair(bmp.width / 4f, bmp.height / 4f)
                    bmp.recycle()
                }
                val dm = dims[t]!!
                placeImage(pdf, i, img, dm.first, dm.second, anchor, 24f, opacity, "T$i")
            }
            pdf.save(out.path, SAVE)
        }
    }

    /** Görsel/imza resmi yerleştirir. [widthPct] sayfa genişliğinin yüzdesi. */
    fun placePicture(src: File, out: File, page: Int, bytes: ByteArray, anchor: Int, widthPct: Float, opacity: Float) {
        withPdf(src) { pdf ->
            val image = Image(bytes)
            val iw = image.getWidth().toFloat().coerceAtLeast(1f)
            val ih = image.getHeight().toFloat().coerceAtLeast(1f)
            val obj = pdf.addImage(image)
            val po = pdf.findPage(page)
            var pw = 595f
            val mb = inherited(po, "MediaBox")
            if (mb != null && mb.isArray() && mb.size() >= 4) {
                pw = Math.abs(mb.get(2).asFloat() - mb.get(0).asFloat())
            }
            val wPt = pw * widthPct / 100f
            val hPt = wPt * ih / iw
            placeImage(pdf, page, obj, wPt, hPt, anchor, 24f, opacity, "P$page")
            pdf.save(out.path, SAVE)
        }
    }

    // ---------------------------------------------------------------- açıklamalar

    private fun colorOf(name: String): FloatArray = when (name.trim().lowercase(Locale.forLanguageTag("tr"))) {
        "yeşil", "yesil" -> floatArrayOf(0.4f, 0.9f, 0.4f)
        "pembe" -> floatArrayOf(1f, 0.5f, 0.75f)
        "mavi" -> floatArrayOf(0.4f, 0.7f, 1f)
        "kırmızı", "kirmizi" -> floatArrayOf(1f, 0.2f, 0.2f)
        else -> floatArrayOf(1f, 0.95f, 0.2f) // sarı
    }

    /** Aranan metni vurgular / altını çizer / üstünü çizer. Bulunan eşleşme sayısını döndürür. */
    fun markText(src: File, out: File, pages: Set<Int>, query: String, kind: Int, colorName: String): Int {
        val type = when (kind) {
            2 -> PDFAnnotation.TYPE_UNDERLINE
            3 -> PDFAnnotation.TYPE_STRIKE_OUT
            else -> PDFAnnotation.TYPE_HIGHLIGHT
        }
        val color = colorOf(colorName)
        var count = 0
        withPdf(src) { pdf ->
            for (i in pages) {
                val pg = pdf.loadPage(i) as PDFPage
                try {
                    val hits = pg.search(query)
                    if (hits != null) {
                        for (hit in hits) {
                            val an = pg.createAnnotation(type)
                            an.setColor(color)
                            an.setQuadPoints(hit)
                            an.update()
                            count++
                        }
                    }
                    pg.update()
                } finally {
                    pg.destroy()
                }
            }
            pdf.save(out.path, SAVE)
        }
        return count
    }

    fun addNote(src: File, out: File, page: Int, text: String) {
        withPdf(src) { pdf ->
            val pg = pdf.loadPage(page) as PDFPage
            try {
                val an = pg.createAnnotation(PDFAnnotation.TYPE_TEXT)
                an.setRect(Rect(36f, 36f, 60f, 60f))
                an.setContents(text)
                an.setColor(floatArrayOf(1f, 0.85f, 0.1f))
                an.update()
                pg.update()
            } finally {
                pg.destroy()
            }
            pdf.save(out.path, SAVE)
        }
    }

    /** Bağlantı ve form alanları dışındaki tüm açıklamaları siler. */
    fun deleteAnnotations(src: File, out: File): Int {
        var count = 0
        withPdf(src) { pdf ->
            val n = pdf.countPages()
            for (i in 0 until n) {
                val pg = pdf.loadPage(i) as PDFPage
                try {
                    val list = pg.getAnnotations()
                    if (list != null) {
                        for (an in list) {
                            val t = an.getType()
                            if (t != PDFAnnotation.TYPE_WIDGET && t != PDFAnnotation.TYPE_LINK) {
                                pg.deleteAnnotation(an)
                                count++
                            }
                        }
                    }
                    pg.update()
                } finally {
                    pg.destroy()
                }
            }
            pdf.save(out.path, SAVE)
        }
        return count
    }

    /** Açıklamaları ve form alanlarını sayfaya işler (düzleştirir). */
    fun flatten(src: File, out: File) {
        withPdf(src) { pdf ->
            pdf.bake(true, true)
            pdf.save(out.path, SAVE)
        }
    }

    // ---------------------------------------------------------------- karartma ve temizlik

    /** Verilen metinleri (her satır bir ifade) kalıcı olarak karartır. Karartılan eşleşme sayısını döndürür. */
    fun redact(src: File, out: File, terms: List<String>): Int {
        var count = 0
        withPdf(src) { pdf ->
            val n = pdf.countPages()
            for (i in 0 until n) {
                val pg = pdf.loadPage(i) as PDFPage
                try {
                    var any = false
                    for (term in terms) {
                        val hits = pg.search(term) ?: continue
                        for (hit in hits) {
                            var x0 = Float.MAX_VALUE
                            var y0 = Float.MAX_VALUE
                            var x1 = -Float.MAX_VALUE
                            var y1 = -Float.MAX_VALUE
                            for (q in hit) {
                                x0 = minOf(x0, q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                                y0 = minOf(y0, q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                                x1 = maxOf(x1, q.ul_x, q.ur_x, q.ll_x, q.lr_x)
                                y1 = maxOf(y1, q.ul_y, q.ur_y, q.ll_y, q.lr_y)
                            }
                            val an = pg.createAnnotation(PDFAnnotation.TYPE_REDACT)
                            an.setRect(Rect(x0, y0, x1, y1))
                            an.update()
                            any = true
                            count++
                        }
                    }
                    if (any) pg.applyRedactions(true, PDFPage.REDACT_IMAGE_PIXELS)
                    pg.update()
                } finally {
                    pg.destroy()
                }
            }
            pdf.save(out.path, SAVE)
        }
        return count
    }

    /** Belge bilgilerini, XMP üst verisini, betikleri ve gömülü ekleri temizler. */
    fun sanitize(src: File, out: File) {
        withPdf(src) { pdf ->
            val keys = listOf(
                "info:Title", "info:Author", "info:Subject", "info:Keywords",
                "info:Creator", "info:Producer"
            )
            for (k in keys) {
                try {
                    pdf.setMetaData(k, "")
                } catch (_: Exception) {
                }
            }
            val root = pdf.getTrailer().get("Root")
            if (root != null && !root.isNull()) {
                root.delete("Metadata")
                root.delete("OpenAction")
                root.delete("AA")
                val names = root.get("Names")
                if (names != null && !names.isNull() && names.isDictionary()) {
                    names.delete("JavaScript")
                    names.delete("EmbeddedFiles")
                }
            }
            pdf.save(out.path, "garbage=4,compress=yes,sanitize=yes")
        }
    }

    /** Sahip şifresiyle kısıtlı izinli kopya yazar (açmak için şifre gerekmez). */
    fun restrictPermissions(
        src: File, out: File, ownerPassword: String,
        print: Boolean, copy: Boolean, modify: Boolean, annotate: Boolean
    ) {
        if (ownerPassword.isBlank()) throw IllegalArgumentException("Sahip şifresi boş olamaz")
        if (ownerPassword.contains(',') || ownerPassword.contains('=')) {
            throw IllegalArgumentException("Şifre ',' veya '=' içeremez")
        }
        var p = -3904 // 0xFFFFF0C0
        if (print) p = p or 4
        if (modify) p = p or 8
        if (copy) p = p or 16
        if (annotate) p = p or 32
        withPdf(src) { pdf ->
            pdf.save(out.path, "garbage=compact,compress=yes,encrypt=aes-256,owner-password=$ownerPassword,permissions=$p")
        }
    }

    // ---------------------------------------------------------------- araçlar

    /** Boş sayfaları siler; silinen sayfa sayısını döndürür. */
    fun removeBlankPages(src: File, out: File): Int {
        val blank = mutableListOf<Int>()
        val d = Document.openDocument(src.path)
        try {
            val n = d.countPages()
            for (i in 0 until n) {
                val p = d.loadPage(i)
                try {
                    val st = p.toStructuredText()
                    val hasText = st.asText().isNotBlank()
                    st.destroy()
                    if (hasText) continue
                    val bmp = AndroidDrawDevice.drawPage(p, 20f)
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    var dark = 0
                    for (c in px) {
                        val lum = (Color.red(c) * 3 + Color.green(c) * 6 + Color.blue(c)) / 10
                        if (lum < 240) dark++
                    }
                    bmp.recycle()
                    if (dark.toDouble() / px.size < 0.002) blank.add(i)
                } finally {
                    p.destroy()
                }
            }
            if (blank.size >= n) throw IllegalArgumentException("Tüm sayfalar boş görünüyor; silinmedi")
        } finally {
            d.destroy()
        }
        if (blank.isEmpty()) {
            src.copyTo(out, overwrite = true)
            return 0
        }
        PdfOps.delete(src, out, blank.toSet())
        return blank.size
    }

    /** Dosyayı yeniden yazarak onarır. */
    fun repair(src: File, out: File) {
        withPdf(src) { pdf -> pdf.save(out.path, "garbage=4,compress=yes") }
    }

    fun check(src: File): String {
        val sb = StringBuilder()
        val d = Document.openDocument(src.path)
        try {
            val n = d.countPages()
            var bad = 0
            for (i in 0 until n) {
                try {
                    val p = d.loadPage(i)
                    try {
                        val bmp = AndroidDrawDevice.drawPage(p, 16f)
                        bmp.recycle()
                    } finally {
                        p.destroy()
                    }
                } catch (e: Exception) {
                    bad++
                }
            }
            sb.append("Sayfa sayısı: ").append(n).append('\n')
            sb.append("Çizilemeyen sayfa: ").append(bad).append('\n')
            if (d.isPDF()) {
                val pdf = d.asPDF()
                sb.append("PDF sürümü: ").append(pdf.getVersion()).append('\n')
                sb.append("İmza alanı: ").append(pdf.countSignatures()).append('\n')
                val form = try { pdf.hasAcroForm() } catch (e: Exception) { false }
                sb.append("Form (AcroForm): ").append(if (form) "var" else "yok").append('\n')
                sb.append("Hızlı web görünümü: ").append(if (pdf.wasLinearized()) "evet" else "hayır").append('\n')
            }
            sb.append('\n').append(if (bad == 0) "Sorun bulunmadı." else "Bazı sayfalar çizilemedi; Onar'ı deneyin.")
        } finally {
            d.destroy()
        }
        return sb.toString()
    }

    /** İki belgeyi sayfa sayfa metin olarak karşılaştırır. */
    fun compare(a: File, b: File): String {
        fun pageTexts(f: File): List<String> {
            val d = Document.openDocument(f.path)
            try {
                val res = mutableListOf<String>()
                val n = d.countPages()
                for (i in 0 until n) {
                    val p = d.loadPage(i)
                    try {
                        val st = p.toStructuredText()
                        res.add(st.asText().replace(Regex("\\s+"), " ").trim())
                        st.destroy()
                    } finally {
                        p.destroy()
                    }
                }
                return res
            } finally {
                d.destroy()
            }
        }
        val ta = pageTexts(a)
        val tb = pageTexts(b)
        val sb = StringBuilder()
        sb.append("Açık belge: ").append(ta.size).append(" sayfa\n")
        sb.append("Seçilen belge: ").append(tb.size).append(" sayfa\n\n")
        val common = minOf(ta.size, tb.size)
        val diff = mutableListOf<Int>()
        for (i in 0 until common) if (ta[i] != tb[i]) diff.add(i + 1)
        if (ta.size != tb.size) sb.append("Sayfa sayıları farklı.\n")
        if (diff.isEmpty() && ta.size == tb.size) {
            sb.append("Tüm sayfaların metni aynı.")
        } else {
            sb.append("Metni farklı sayfalar (").append(diff.size).append("): ")
            sb.append(diff.take(60).joinToString(", "))
            if (diff.size > 60) sb.append(" …")
            if (diff.isNotEmpty()) {
                val i = diff[0] - 1
                val x = ta[i]
                val y = tb[i]
                var k = 0
                while (k < x.length && k < y.length && x[k] == y[k]) k++
                sb.append("\n\nİlk fark, sayfa ").append(diff[0]).append(":\n")
                sb.append("Açık: …").append(x.substring(maxOf(0, k - 20), minOf(x.length, k + 60))).append('\n')
                sb.append("Seçilen: …").append(y.substring(maxOf(0, k - 20), minOf(y.length, k + 60)))
            }
        }
        return sb.toString()
    }

    // ---------------------------------------------------------------- gömülü ekler

    private fun collectNames(node: PDFObject?, out: MutableList<Pair<String, PDFObject>>, depth: Int) {
        if (node == null || node.isNull() || depth > 8) return
        val names = node.get("Names")
        if (names != null && names.isArray()) {
            var i = 0
            while (i + 1 < names.size()) {
                val key = names.get(i)
                val spec = names.get(i + 1)
                out.add(Pair(if (key.isString()) key.asString() else "ek", spec))
                i += 2
            }
        }
        val kids = node.get("Kids")
        if (kids != null && kids.isArray()) {
            for (k in 0 until kids.size()) collectNames(kids.get(k), out, depth + 1)
        }
    }

    private fun embedded(pdf: PDFDocument): List<Pair<String, PDFObject>> {
        val res = mutableListOf<Pair<String, PDFObject>>()
        val root = pdf.getTrailer().get("Root")
        val ef = root?.get("Names")?.get("EmbeddedFiles")
        collectNames(ef, res, 0)
        return res
    }

    fun attachments(src: File): List<Att> {
        val d = Document.openDocument(src.path)
        try {
            val pdf = d.asPDF()
            return embedded(pdf).map { (name, spec) ->
                val size = try {
                    pdf.loadEmbeddedFileContents(spec).getLength()
                } catch (e: Exception) {
                    -1
                }
                val uf = spec.get("UF")
                val fn = spec.get("F")
                val shown = when {
                    uf != null && uf.isString() -> uf.asString()
                    fn != null && fn.isString() -> fn.asString()
                    else -> name
                }
                Att(shown, size)
            }
        } finally {
            d.destroy()
        }
    }

    fun exportAttachment(src: File, index: Int, os: OutputStream) {
        val d = Document.openDocument(src.path)
        try {
            val pdf = d.asPDF()
            val list = embedded(pdf)
            if (index !in list.indices) throw IllegalArgumentException("Ek bulunamadı")
            val buf = pdf.loadEmbeddedFileContents(list[index].second)
            os.write(buf.asByteArray())
        } finally {
            d.destroy()
        }
    }
}
