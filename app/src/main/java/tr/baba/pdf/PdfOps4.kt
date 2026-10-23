package tr.baba.pdf

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix as GMatrix
import android.media.ExifInterface
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Image
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.Rect
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

object PdfOps4 {
    private const val SAVE = "garbage=compact,compress=yes"

    /** Resim dosyalarından, her biri bir sayfa olan PDF yapar (EXIF yönü uygulanır, en uzun kenar ≤ 2400 px). */
    fun imagesToPdf(files: List<File>, out: File) {
        val dst = PDFDocument()
        try {
            for ((k, f) in files.withIndex()) {
                Progress.step(k, files.size)
                var bmp = decode(f) ?: throw IllegalArgumentException("Resim okunamadı: ${f.name}")
                val bos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos)
                val w = 595f
                val h = w * bmp.height / bmp.width
                bmp.recycle()
                val img = dst.addImage(Image(bos.toByteArray()))
                val res = dst.newDictionary()
                val xo = dst.newDictionary()
                xo.put("Im0", img)
                res.put("XObject", xo)
                val hs = String.format(Locale.US, "%.2f", h)
                val pg = dst.addPage(Rect(0f, 0f, w, h), 0, res, "q 595 0 0 $hs 0 0 cm /Im0 Do Q")
                dst.insertPage(-1, pg)
            }
            dst.save(out.path, SAVE)
        } finally {
            dst.destroy()
        }
    }

    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / sample > 3600) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        var b = BitmapFactory.decodeFile(f.path, o2) ?: return null
        val deg = try {
            when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } catch (e: Exception) { 0f }
        val longSide = maxOf(b.width, b.height)
        val scale = if (longSide > 2400) 2400f / longSide else 1f
        if (deg != 0f || scale != 1f) {
            val m = GMatrix()
            m.postRotate(deg)
            m.postScale(scale, scale)
            val nb = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
            if (nb != b) b.recycle()
            b = nb
        }
        return b
    }

    data class Field(
        val page: Int, val index: Int, val name: String, val type: Int,
        val value: String, val options: List<String>, val readOnly: Boolean
    )

    fun formFields(src: File): List<Field> {
        val res = ArrayList<Field>()
        val d = Document.openDocument(src.path)
        try {
            val pdf = d.asPDF()
            for (i in 0 until pdf.countPages()) {
                val pg = pdf.loadPage(i) as PDFPage
                val ws = pg.widgets ?: emptyArray()
                for ((k, w) in ws.withIndex()) {
                    val t = w.fieldType
                    if (t == PDFWidget.TYPE_SIGNATURE || t == PDFWidget.TYPE_BUTTON && w.isPushButton) continue
                    if (t == PDFWidget.TYPE_UNKNOWN) continue
                    val opts = if (w.isChoice) (w.options?.toList() ?: emptyList()) else emptyList()
                    res.add(Field(i, k, w.name ?: "", t, w.value ?: "", opts, w.isReadOnly))
                }
                pg.destroy()
            }
        } finally {
            d.destroy()
        }
        return res
    }

    /** [changes]: (sayfa, sıra) → yeni değer. Onay kutusu/radyo için "açık" ise "1", değilse "0". */
    fun fillForm(src: File, out: File, changes: Map<Pair<Int, Int>, String>) {
        val d = Document.openDocument(src.path)
        try {
            val pdf = d.asPDF()
            for ((key, v) in changes) {
                val pg = pdf.loadPage(key.first) as PDFPage
                val w = (pg.widgets ?: emptyArray()).getOrNull(key.second)
                if (w != null && !w.isReadOnly) {
                    when {
                        w.isCheckbox || w.isRadioButton -> {
                            val on = v == "1"
                            val cur = w.value.orEmpty().let { it.isNotEmpty() && it != "Off" }
                            if (on != cur) w.toggle()
                        }
                        w.isChoice -> w.setChoiceValue(v)
                        else -> w.setTextValue(v)
                    }
                }
                pg.destroy()
            }
            pdf.save(out.path, SAVE)
        } finally {
            d.destroy()
        }
    }
}
