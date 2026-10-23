package tr.baba.pdf

import android.content.Context
import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Buffer
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFObject
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import java.util.Locale

/** Tesseract (tur+eng) ile OCR: aranabilir PDF (görünmez metin katmanı) ve düz metin. */
object Ocr {
    private const val LANG = "tur+eng"
    private const val DPI = 200f

    private fun prepare(ctx: Context): String {
        val root = File(ctx.filesDir, "ocr")
        val dir = File(root, "tessdata")
        dir.mkdirs()
        for (n in listOf("tur", "eng")) {
            val f = File(dir, "$n.traineddata")
            if (!f.exists() || f.length() < 1000) {
                ctx.assets.open("tessdata/$n.traineddata").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
            }
        }
        return root.absolutePath
    }

    private fun newApi(ctx: Context): TessBaseAPI {
        val api = TessBaseAPI()
        if (!api.init(prepare(ctx), LANG)) {
            api.recycle()
            throw java.io.IOException("OCR dil verisi yüklenemedi")
        }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        return api
    }

    private fun renderPage(d: Document, i: Int): Pair<Bitmap, Float> {
        val p = d.loadPage(i)
        try {
            var s = DPI / 72f
            val b = p.bounds
            val longSide = maxOf(b.x1 - b.x0, b.y1 - b.y0) * s
            if (longSide > 4200f) s *= 4200f / longSide
            return Pair(AndroidDrawDevice.drawPage(p, s * 72f), s)
        } finally {
            p.destroy()
        }
    }

    /** Seçilen sayfaların metni. */
    fun toText(ctx: Context, src: File, pages: Set<Int>): String {
        val api = newApi(ctx)
        val d = Document.openDocument(src.path)
        try {
            val sb = StringBuilder()
            var k = 0
            for (i in pages.sorted()) {
                Progress.step(k++, pages.size)
                val (bmp, _) = renderPage(d, i)
                api.setImage(bmp)
                val t = api.getUTF8Text() ?: ""
                bmp.recycle()
                sb.append("--- Sayfa ").append(i + 1).append(" ---\n").append(t.trim()).append("\n\n")
            }
            return sb.toString()
        } finally {
            d.destroy()
            api.recycle()
        }
    }

    private class Word(val text: String, val l: Int, val t: Int, val r: Int, val b: Int)

    private fun words(api: TessBaseAPI): List<Word> {
        api.getUTF8Text() // tanımayı tetikler
        val out = ArrayList<Word>()
        val it = api.resultIterator ?: return out
        try {
            it.begin()
            do {
                val t = it.getUTF8Text(TessBaseAPI.PageIteratorLevel.RIL_WORD)?.trim()
                if (!t.isNullOrEmpty()) {
                    val r = it.getBoundingRect(TessBaseAPI.PageIteratorLevel.RIL_WORD)
                    out.add(Word(t, r.left, r.top, r.right, r.bottom))
                }
            } while (it.next(TessBaseAPI.PageIteratorLevel.RIL_WORD))
        } finally {
            it.delete()
        }
        return out
    }

    private fun hex4(v: Int) = String.format("%04X", v)

    /** Her sayfaya görünmez (Tr 3) bir metin katmanı ekler; taranmış PDF aranabilir/seçilebilir olur. */
    fun makeSearchable(ctx: Context, src: File, out: File, pages: Set<Int>): Int {
        val api = newApi(ctx)
        val d = Document.openDocument(src.path)
        var done = 0
        try {
            val pdf = d.asPDF()
            val cids = LinkedHashMap<Int, Int>() // unicode -> cid
            val fontRef = pdf.newDictionary()
            // sayfa içerikleri önce toplanır, yazı tipi (ToUnicode) en sonda tamamlanır
            val fontObj = pdf.addObject(fontRef)
            var k0 = 0
            for (i in pages.sorted()) {
                Progress.step(k0++, pages.size)
                val (bmp, s) = renderPage(d, i)
                api.setImage(bmp)
                val ws = words(api)
                bmp.recycle()
                if (ws.isEmpty()) continue
                val po = pdf.findPage(i)
                val mb = PdfOps2.inherited(po, "MediaBox")
                if (mb == null || !mb.isArray() || mb.size() < 4) continue
                val ax = mb.get(0).asFloat(); val ay = mb.get(1).asFloat()
                val bx = mb.get(2).asFloat(); val by = mb.get(3).asFloat()
                val mx0 = minOf(ax, bx)
                val my1 = maxOf(ay, by)
                val rot = (PdfOps2.inherited(po, "Rotate")?.asInteger() ?: 0)
                if (rot % 360 != 0) continue
                val cs = StringBuilder("q BT 3 Tr\n")
                for (w in ws) {
                    val hex = StringBuilder()
                    var n = 0
                    var k = 0
                    while (k < w.text.length) {
                        val cp = w.text.codePointAt(k)
                        k += Character.charCount(cp)
                        val cid = cids.getOrPut(cp) { cids.size + 1 }
                        if (cid > 65000) continue
                        hex.append(hex4(cid))
                        n++
                    }
                    if (n == 0) continue
                    val wPt = (w.r - w.l) / s
                    val hPt = (w.b - w.t) / s
                    if (wPt <= 0f || hPt <= 0f) continue
                    val size = hPt.coerceIn(2f, 200f)
                    val tz = (wPt * 100f / (n * 0.5f * size)).coerceIn(5f, 2000f)
                    val x = mx0 + w.l / s
                    val y = my1 - w.b / s
                    cs.append(String.format(Locale.US, "/BabaOcr %.2f Tf %.1f Tz 1 0 0 1 %.2f %.2f Tm <%s> Tj\n", size, tz, x, y, hex))
                }
                cs.append("ET Q\n")

                var res = PdfOps2.inherited(po, "Resources")
                if (res == null) {
                    res = pdf.newDictionary()
                    po.put("Resources", res)
                }
                var fd = res.get("Font")
                if (fd == null || fd.isNull() || !fd.isDictionary()) {
                    fd = pdf.newDictionary()
                    res.put("Font", fd)
                }
                fd.put("BabaOcr", fontObj)
                val old = po.get("Contents")
                val arr = pdf.newArray()
                arr.push(pdf.addStream("q"))
                if (old != null && !old.isNull()) {
                    if (old.isArray()) for (k in 0 until old.size()) arr.push(old.get(k)) else arr.push(old)
                }
                arr.push(pdf.addStream("Q"))
                arr.push(pdf.addStream(cs.toString()))
                po.put("Contents", arr)
                done++
            }
            if (done > 0) finishFont(pdf, fontRef, cids)
            pdf.save(out.path, "garbage=compact,compress=yes")
        } finally {
            d.destroy()
            api.recycle()
        }
        return done
    }

    private fun finishFont(pdf: PDFDocument, f: PDFObject, cids: Map<Int, Int>) {
        val sb = StringBuilder()
        sb.append("/CIDInit /ProcSet findresource begin 12 dict begin begincmap\n")
        sb.append("/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n")
        sb.append("/CMapName /Adobe-Identity-UCS def /CMapType 2 def\n")
        sb.append("1 begincodespacerange <0000> <FFFF> endcodespacerange\n")
        val items = cids.entries.toList()
        var i = 0
        while (i < items.size) {
            val chunk = items.subList(i, minOf(i + 100, items.size))
            sb.append(chunk.size).append(" beginbfchar\n")
            for ((cp, cid) in chunk) {
                val u = String(Character.toChars(cp))
                val h = u.map { String.format("%04X", it.code) }.joinToString("")
                sb.append('<').append(hex4(cid)).append("> <").append(h).append(">\n")
            }
            sb.append("endbfchar\n")
            i += 100
        }
        sb.append("endcmap CMapName currentdict /CMap defineresource pop end end\n")
        val tu = pdf.addStream(sb.toString())

        val fdesc = pdf.newDictionary()
        fdesc.put("Type", pdf.newName("FontDescriptor"))
        fdesc.put("FontName", pdf.newName("BabaGlyphless"))
        fdesc.put("Flags", 5)
        val bbox = pdf.newArray()
        bbox.push(0); bbox.push(0); bbox.push(500); bbox.push(1000)
        fdesc.put("FontBBox", bbox)
        fdesc.put("ItalicAngle", 0)
        fdesc.put("Ascent", 1000)
        fdesc.put("Descent", 0)
        fdesc.put("CapHeight", 1000)
        fdesc.put("StemV", 80)

        val cidf = pdf.newDictionary()
        cidf.put("Type", pdf.newName("Font"))
        cidf.put("Subtype", pdf.newName("CIDFontType2"))
        cidf.put("BaseFont", pdf.newName("BabaGlyphless"))
        val si = pdf.newDictionary()
        si.put("Registry", "Adobe")
        si.put("Ordering", "Identity")
        si.put("Supplement", 0)
        cidf.put("CIDSystemInfo", si)
        cidf.put("FontDescriptor", pdf.addObject(fdesc))
        cidf.put("DW", 500)
        cidf.put("CIDToGIDMap", pdf.newName("Identity"))
        val desc = pdf.newArray()
        desc.push(pdf.addObject(cidf))

        f.put("Type", pdf.newName("Font"))
        f.put("Subtype", pdf.newName("Type0"))
        f.put("BaseFont", pdf.newName("BabaGlyphless"))
        f.put("Encoding", pdf.newName("Identity-H"))
        f.put("DescendantFonts", desc)
        f.put("ToUnicode", tu)
    }
}
