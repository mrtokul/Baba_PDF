package tr.baba.pdf

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.OutlineIterator
import com.artifex.mupdf.fitz.PDFDocument
import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object PdfOps3 {
    private const val SAVE = "garbage=compact,compress=yes"

    private inline fun <T> withPdf(src: File, block: (PDFDocument) -> T): T {
        val d = Document.openDocument(src.path)
        try {
            return block(d.asPDF())
        } finally {
            d.destroy()
        }
    }

    /** Ön sıralı (görünen sıra) [index]. öğeye iterator'ü götürür. */
    private fun seek(oi: OutlineIterator, index: Int): Boolean {
        if (oi.item() == null) return false
        var k = 0
        while (k < index) {
            if (oi.down() == 0 && oi.item() != null) {
                k++
                continue
            }
            var moved = false
            while (true) {
                if (oi.next() == 0) { moved = true; break }
                if (oi.up() != 0) break
            }
            if (!moved) return false
            k++
        }
        return true
    }

    fun deleteBookmark(src: File, out: File, index: Int) {
        withPdf(src) { pdf ->
            val oi = pdf.outlineIterator()
            try {
                if (!seek(oi, index)) throw IllegalArgumentException("Yer imi bulunamadı")
                oi.delete()
            } finally {
                oi.destroy()
            }
            pdf.save(out.path, SAVE)
        }
    }

    /** Başlığı ve (verilmişse, 1 tabanlı) hedef sayfayı değiştirir. */
    fun editBookmark(src: File, out: File, index: Int, title: String, page: Int?) {
        withPdf(src) { pdf ->
            val oi = pdf.outlineIterator()
            try {
                if (!seek(oi, index)) throw IllegalArgumentException("Yer imi bulunamadı")
                val it = oi.item()
                val uri = if (page != null) "#page=$page" else it.uri
                oi.update(OutlineIterator.OutlineItem(title, uri, it.is_open, it.r, it.g, it.b, it.flags))
            } finally {
                oi.destroy()
            }
            pdf.save(out.path, SAVE)
        }
    }

    /** Seçili sayfalardaki gömülü görselleri PNG olarak ZIP'e yazar; kaç görsel yazıldığını döndürür. */
    fun extractImagesZip(src: File, pages: Set<Int>, os: OutputStream): Int {
        var count = 0
        val z = ZipOutputStream(os)
        val seen = HashSet<Int>()
        withPdf(src) { pdf ->
            var kk = 0
            for (i in pages.sorted()) {
                Progress.step(kk++, pages.size)
                val po = pdf.findPage(i)
                val res = PdfOps2.inherited(po, "Resources") ?: continue
                val xo = res.get("XObject") ?: continue
                if (xo.isNull() || !xo.isDictionary()) continue
                var k = 0
                for (key in xo) {
                    val obj = xo.get(key) ?: continue
                    val st = obj.get("Subtype")
                    if (st == null || st.isNull() || st.asName() != "Image") continue
                    if (obj.isIndirect() && !seen.add(obj.asIndirect())) continue
                    try {
                        val img = pdf.loadImage(obj)
                        val pm = img.toPixmap()
                        val buf = pm.asPNG()
                        z.putNextEntry(ZipEntry(String.format(Locale.US, "sayfa%03d_gorsel%02d.png", i + 1, ++k)))
                        z.write(buf.asByteArray())
                        z.closeEntry()
                        count++
                    } catch (e: Exception) {
                        // çözülemeyen görsel atlanır
                    }
                }
            }
        }
        z.finish()
        z.flush()
        return count
    }

    /** Sayfaları [w]x[h] pt boyutuna sığdırır (oran korunur, ortalanır). */
    fun pageSize(src: File, out: File, pages: Set<Int>, w: Float, h: Float) {
        withPdf(src) { pdf ->
            for (i in pages) {
                val po = pdf.findPage(i)
                val mb = PdfOps2.inherited(po, "MediaBox") ?: continue
                if (!mb.isArray() || mb.size() < 4) continue
                val a = mb.get(0).asFloat(); val b = mb.get(1).asFloat()
                val c = mb.get(2).asFloat(); val d = mb.get(3).asFloat()
                val x0 = minOf(a, c); val y0 = minOf(b, d)
                val pw = Math.abs(c - a); val ph = Math.abs(d - b)
                if (pw < 1f || ph < 1f) continue
                val rot = (PdfOps2.inherited(po, "Rotate")?.asInteger() ?: 0) / 90 % 2
                val tw = if (rot == 1) h else w
                val th = if (rot == 1) w else h
                val s = minOf(tw / pw, th / ph)
                val tx = (tw - pw * s) / 2f - x0 * s
                val ty = (th - ph * s) / 2f - y0 * s
                val old = po.get("Contents")
                val arr = pdf.newArray()
                arr.push(pdf.addStream(String.format(Locale.US, "q %.5f 0 0 %.5f %.3f %.3f cm", s, s, tx, ty)))
                if (old != null && !old.isNull()) {
                    if (old.isArray()) for (k in 0 until old.size()) arr.push(old.get(k)) else arr.push(old)
                }
                arr.push(pdf.addStream("Q"))
                po.put("Contents", arr)
                val box = pdf.newArray()
                box.push(0); box.push(0); box.push(tw); box.push(th)
                po.put("MediaBox", box)
                val box2 = pdf.newArray()
                box2.push(0); box2.push(0); box2.push(tw); box2.push(th)
                po.put("CropBox", box2)
            }
            pdf.save(out.path, SAVE)
        }
    }

    /** Metin yönüne bakarak yan/ters duran sayfaları düzeltir; döndürülen sayfa sayısını verir. */
    fun autoRotate(src: File, out: File): Int {
        var changed = 0
        val d = Document.openDocument(src.path)
        try {
            val pdf = d.asPDF()
            for (i in 0 until pdf.countPages()) {
                val p = d.loadPage(i)
                val cnt = IntArray(4) // 0:→  1:↓  2:←  3:↑
                try {
                    val st = p.toStructuredText()
                    for (blk in st.blocks) for (ln in blk.lines ?: emptyArray()) {
                        val dir = ln.dir ?: continue
                        val n = ln.chars?.size ?: 0
                        val idx = if (Math.abs(dir.x) >= Math.abs(dir.y)) (if (dir.x >= 0) 0 else 2) else (if (dir.y >= 0) 1 else 3)
                        cnt[idx] += n
                    }
                    st.destroy()
                } finally {
                    p.destroy()
                }
                val total = cnt.sum()
                if (total < 20) continue
                val best = cnt.indices.maxByOrNull { cnt[it] } ?: 0
                if (best == 0 || cnt[best] * 10 < total * 6) continue
                val delta = when (best) { 1 -> 270; 2 -> 180; else -> 90 }
                val po = pdf.findPage(i)
                val cur = PdfOps2.inherited(po, "Rotate")?.asInteger() ?: 0
                po.put("Rotate", ((cur + delta) % 360 + 360) % 360)
                changed++
            }
            if (changed > 0) pdf.save(out.path, SAVE) else File(src.path).copyTo(out, overwrite = true)
        } finally {
            d.destroy()
        }
        return changed
    }
}
