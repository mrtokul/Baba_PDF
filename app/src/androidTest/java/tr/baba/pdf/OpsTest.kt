package tr.baba.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.platform.app.InstrumentationRegistry
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.Rect
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OpsTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File
    private val cfg = PdfOps.BookCfg(420f, 680f, 12f)

    @Before fun setUp() {
        dir = File(ctx.cacheDir, "t${System.nanoTime()}").also { it.mkdirs() }
        Progress.reset()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun f(n: String) = File(dir, n)

    private fun makePdf(out: File, n: Int, text: (Int) -> String = { "Sayfa ${it + 1} Hello Baba" }) {
        val pdf = PDFDocument()
        val font = pdf.addSimpleFont(Font("Helvetica"), 0)
        val res = pdf.newDictionary()
        val fd = pdf.newDictionary()
        fd.put("F1", font)
        res.put("Font", fd)
        for (i in 0 until n) {
            val pg = pdf.addPage(Rect(0f, 0f, 595f, 842f), 0, res, "BT /F1 24 Tf 72 700 Td (${text(i)}) Tj ET")
            pdf.insertPage(-1, pg)
        }
        pdf.save(out.path, "")
        pdf.destroy()
    }

    private fun bounds(file: File, i: Int = 0): Pair<Float, Float> {
        val d = Document.openDocument(file.path)
        try {
            val p = d.loadPage(i)
            val b = p.bounds
            p.destroy()
            return Pair(b.x1 - b.x0, b.y1 - b.y0)
        } finally {
            d.destroy()
        }
    }

    @Test fun pageOps() {
        val a = f("a.pdf"); makePdf(a, 4)
        assertEquals(4, PdfOps.pageCount(a))
        val b = f("b.pdf"); PdfOps.delete(a, b, setOf(1))
        assertEquals(3, PdfOps.pageCount(b))
        val c = f("c.pdf"); PdfOps.keep(a, c, setOf(0, 2))
        assertEquals(2, PdfOps.pageCount(c))
        val m = f("m.pdf"); PdfOps.merge(listOf(a, c), m)
        assertEquals(6, PdfOps.pageCount(m))
        val mv = f("mv.pdf"); PdfOps.movePage(a, mv, 0, 3)
        val t = PdfOps.extractText(mv, setOf(3))
        assertTrue("taşınan sayfa sonda olmalı: $t", t.contains("Sayfa 1"))
        val r = f("r.pdf"); PdfOps.rotate(a, r, setOf(0), 90)
        val (w, h) = bounds(r)
        assertTrue("döndürülmüş sayfa yatay olmalı", w > h)
        val bl = f("bl.pdf"); PdfOps.blankPage(a, bl, 2)
        assertEquals(5, PdfOps.pageCount(bl))
    }

    @Test fun searchAndText() {
        val a = f("a.pdf"); makePdf(a, 3)
        val hits = PdfOps.search(a, "Hello")
        assertEquals(3, hits.size)
        assertTrue(PdfOps.extractText(a, null).contains("Sayfa 2"))
    }

    @Test fun encryptNeedsPassword() {
        val a = f("a.pdf"); makePdf(a, 2)
        val e = f("e.pdf"); PdfOps.encrypt(a, e, "sifre1")
        try {
            PdfOps.openNormalized(e, null, f("x.pdf"), cfg)
            fail("şifre istenmeliydi")
        } catch (_: PasswordNeeded) {
        }
        val o = f("o.pdf")
        PdfOps.openNormalized(e, "sifre1", o, cfg)
        assertEquals(2, PdfOps.pageCount(o))
    }

    @Test fun redactionRemovesText() {
        val a = f("a.pdf"); makePdf(a, 1)
        val r = f("r.pdf")
        val n = PdfOps2.redact(a, r, listOf("Hello"))
        assertTrue("en az bir eşleşme", n > 0)
        assertFalse(PdfOps.extractText(r, null).contains("Hello"))
    }

    @Test fun stampAndCompress() {
        val a = f("a.pdf"); makePdf(a, 2)
        val s = f("s.pdf")
        PdfOps2.stampText(a, s, setOf(0, 1), "GİZLİ {n}/{N}", 40f, Color.RED, 5, 30f, 0.5f)
        assertEquals(2, PdfOps.pageCount(s))
        val z = f("z.pdf"); PdfOps.compressRaster(a, z, 72, 0.5f)
        assertEquals(2, PdfOps.pageCount(z))
        val cs = f("cs.pdf"); PdfOps.compressStructure(a, cs)
        assertEquals(2, PdfOps.pageCount(cs))
    }

    @Test fun cropAndSize() {
        val a = f("a.pdf"); makePdf(a, 1)
        val c = f("c.pdf"); PdfOps2.crop(a, c, setOf(0), 50f, 50f, 50f, 50f)
        val (w, h) = bounds(c)
        assertEquals(495f, w, 1f); assertEquals(742f, h, 1f)
        val s = f("s.pdf"); PdfOps3.pageSize(a, s, setOf(0), 420f, 595f)
        assertEquals(420f, bounds(s).first, 1f)
        assertTrue(PdfOps.extractText(s, null).contains("Hello"))
    }

    @Test fun bookmarks() {
        val a = f("a.pdf"); makePdf(a, 3)
        val b1 = f("b1.pdf"); PdfOps.addBookmark(a, b1, "Bir", 1)
        val b2 = f("b2.pdf"); PdfOps.addBookmark(b1, b2, "Iki", 2)
        assertEquals(2, PdfOps.bookmarks(b2).size)
        val b3 = f("b3.pdf"); PdfOps3.editBookmark(b2, b3, 1, "Ikinci", 3)
        val e = PdfOps.bookmarks(b3)
        assertEquals("Ikinci", e[1].title)
        assertEquals(2, e[1].page)
        val b4 = f("b4.pdf"); PdfOps3.deleteBookmark(b3, b4, 0)
        val l = PdfOps.bookmarks(b4)
        assertEquals(1, l.size)
        assertEquals("Ikinci", l[0].title)
    }

    private fun textImage(file: File, text: String) {
        val bmp = Bitmap.createBitmap(1600, 500, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 170f; typeface = Typeface.SANS_SERIF
        }
        c.drawText(text, 60f, 300f, p)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bmp.recycle()
    }

    @Test fun imagesOcrAndExtract() {
        val img = f("i.jpg"); textImage(img, "HELLO WORLD")
        val pdf = f("i.pdf"); PdfOps4.imagesToPdf(listOf(img), pdf)
        assertEquals(1, PdfOps.pageCount(pdf))
        val zip = ByteArrayOutputStream()
        assertEquals(1, PdfOps3.extractImagesZip(pdf, setOf(0), zip))
        val txt = Ocr.toText(ctx, pdf, setOf(0))
        assertTrue("OCR metni: $txt", txt.uppercase().contains("HELLO"))
        val out = f("ocr.pdf")
        assertEquals(1, Ocr.makeSearchable(ctx, pdf, out, setOf(0)))
        val hits = PdfOps.search(out, "hello")
        assertTrue("aranabilir katman bulunamadı", hits.isNotEmpty())
        assertTrue(PdfOps.extractText(out, null).uppercase().contains("WORLD"))
    }

    @Test fun signAndVerify() {
        val kpg = KeyPairGenerator.getInstance("RSA"); kpg.initialize(2048)
        val kp = kpg.generateKeyPair()
        val bc = BouncyCastleProvider()
        val dn = X500Name("CN=Test Kullanici, O=Baba, C=TR")
        val cert = JcaX509CertificateConverter().setProvider(bc).getCertificate(
            JcaX509v3CertificateBuilder(
                dn, BigInteger.ONE, Date(System.currentTimeMillis() - 86400000L),
                Date(System.currentTimeMillis() + 86400000L * 365), dn, kp.public
            ).build(JcaContentSignerBuilder("SHA256withRSA").setProvider(bc).build(kp.private))
        )
        val ks = KeyStore.getInstance("PKCS12", bc)
        ks.load(null, null)
        ks.setKeyEntry("k", kp.private, "pw".toCharArray(), arrayOf(cert))
        val p12 = f("t.p12")
        p12.outputStream().use { ks.store(it, "pw".toCharArray()) }

        val a = f("a.pdf"); makePdf(a, 2)
        val s = f("s.pdf")
        Signing.sign(a, s, p12, "pw", 0, true, "deneme", "Ankara")
        val v = Signing.verify(s)
        assertEquals(1, v.size)
        assertTrue("özet: ${v[0].digest}", v[0].digest.contains("sağlam"))
        assertTrue(Signing.report(s, "s.pdf").contains("Sayfa 1"))

        val bytes = s.readBytes()
        bytes[7] = '6'.code.toByte() // imzalı aralıkta tek bayt değişikliği
        val bad = f("bad.pdf"); bad.writeBytes(bytes)
        val v2 = Signing.verify(bad)
        assertTrue("bozulma algılanmalı: ${v2.map { it.digest }}", v2.isNotEmpty() && !v2[0].digest.contains("sağlam"))

        try {
            Signing.sign(a, f("x.pdf"), p12, "yanlis", 0, false, "", "")
            fail("yanlış parola reddedilmeliydi")
        } catch (_: Exception) {
        }
    }

    @Test fun converters() {
        val rtf = f("a.rtf")
        rtf.writeText("{\\rtf1\\ansi\\ansicpg1254 Merhaba \\'fe\\'f0 \\u351? \\b kalin\\b0\\par Ikinci}", Charsets.ISO_8859_1)
        val h = f("a.html"); Converters.toHtml(rtf, "rtf", h)
        val t = h.readText()
        assertTrue(t, t.contains("Merhaba") && t.contains("ş") && t.contains("ğ") && t.contains("<b>"))
        assertEquals(2, Regex("<p>").findAll(t).count())

        val iba = f("a.iba")
        ZipOutputStream(iba.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("index.xml"))
            z.write("<doc><p style=\"title\">Merhaba Dünya</p><p>Metin</p></doc>".toByteArray())
            z.closeEntry()
        }
        val ih = f("i.html"); Converters.toHtml(iba, "iba", ih)
        assertTrue(ih.readText().contains("<h1>Merhaba Dünya</h1>"))

        val body = "Merhaba MOBI".toByteArray()
        val hdr = ByteArray(78)
        "TEXtREAd".toByteArray().copyInto(hdr, 60)
        hdr[77] = 2
        val recs = ByteArray(16)
        val base = 78 + 16 + 2
        fun put32(a: ByteArray, o: Int, v: Int) { a[o] = (v shr 24).toByte(); a[o + 1] = (v shr 16).toByte(); a[o + 2] = (v shr 8).toByte(); a[o + 3] = v.toByte() }
        put32(recs, 0, base); put32(recs, 8, base + 16)
        val r0 = ByteArray(16)
        r0[1] = 1; put32(r0, 4, body.size); r0[9] = 1; r0[10] = 0x10
        val mobi = f("a.mobi"); mobi.writeBytes(hdr + recs + ByteArray(2) + r0 + body)
        val mh = f("m.html"); Converters.toHtml(mobi, "mobi", mh)
        assertTrue(mh.readText().contains("Merhaba MOBI"))
    }

    @Test fun htmlBecomesPdf() {
        val h = f("b.html"); h.writeText("<html><body><h1>Baslik</h1><p>Metin</p></body></html>")
        val o = f("b.pdf"); PdfOps.openNormalized(h, null, o, cfg)
        assertTrue(PdfOps.pageCount(o) >= 1)
        assertTrue(PdfOps.extractText(o, null).contains("Baslik"))
    }
}
