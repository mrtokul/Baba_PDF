package tr.baba.pdf

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.FitzInputStream
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.PDFWidget
import com.artifex.mupdf.fitz.PKCS7DistinguishedName
import com.artifex.mupdf.fitz.PKCS7Signer
import com.artifex.mupdf.fitz.PKCS7Verifier
import com.artifex.mupdf.fitz.Rect
import org.bouncycastle.asn1.x500.RDN
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaCertStore
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.io.ByteArrayInputStream
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** FitzInputStream sonda -1 yerine 0 döndürebildiğinden elle okunur. */
internal fun readAll(s: FitzInputStream): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16384)
    while (true) {
        val n = s.read(buf, 0, buf.size)
        if (n <= 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

/** PKCS#12 (.p12/.pfx) dosyasından imzalayan MuPDF imzalayıcısı. */
class P12Signer(p12: File, password: String) : PKCS7Signer() {
    private val bc = BouncyCastleProvider()
    private val key: PrivateKey
    private val chain: List<X509Certificate>

    init {
        val ks = KeyStore.getInstance("PKCS12", bc)
        try {
            p12.inputStream().use { ks.load(it, password.toCharArray()) }
        } catch (e: IOException) {
            throw IOException("Sertifika açılamadı: parola yanlış veya dosya geçersiz")
        }
        val alias = ks.aliases().toList().firstOrNull { ks.isKeyEntry(it) }
            ?: throw IOException("Dosyada özel anahtar yok")
        key = ks.getKey(alias, password.toCharArray()) as PrivateKey
        chain = ks.getCertificateChain(alias).map { it as X509Certificate }
    }

    private fun rdn(n: X500Name, oid: org.bouncycastle.asn1.ASN1ObjectIdentifier): String? {
        val r: Array<RDN> = n.getRDNs(oid)
        return r.firstOrNull()?.first?.value?.toString()
    }

    override fun name(): PKCS7DistinguishedName {
        val n = X500Name.getInstance(chain[0].subjectX500Principal.encoded)
        val d = PKCS7DistinguishedName()
        d.cn = rdn(n, BCStyle.CN) ?: ""
        d.o = rdn(n, BCStyle.O) ?: ""
        d.ou = rdn(n, BCStyle.OU) ?: ""
        d.email = rdn(n, BCStyle.EmailAddress) ?: ""
        d.c = rdn(n, BCStyle.C) ?: ""
        return d
    }

    override fun sign(stm: FitzInputStream): ByteArray {
        val data = readAll(stm)
        val alg = if (key.algorithm.equals("EC", true) || key.algorithm.equals("ECDSA", true)) "SHA256withECDSA" else "SHA256withRSA"
        val cs = JcaContentSignerBuilder(alg).setProvider(bc).build(key)
        val dp = JcaDigestCalculatorProviderBuilder().setProvider(bc).build()
        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(JcaSignerInfoGeneratorBuilder(dp).build(cs, chain[0]))
        gen.addCertificates(JcaCertStore(chain))
        return gen.generate(CMSProcessableByteArray(data), false).encoded
    }

    override fun maxDigest(): Int = 4096 + chain.sumOf { it.encoded.size } * 2
}

@Suppress("UNCHECKED_CAST")
private fun sel(s: Any): org.bouncycastle.util.Selector<org.bouncycastle.cert.X509CertificateHolder> =
    s as org.bouncycastle.util.Selector<org.bouncycastle.cert.X509CertificateHolder>

/** İmza doğrulayıcı: özet BouncyCastle ile, güven zinciri Android sistem sertifikalarıyla denetlenir. */
class BcVerifier : PKCS7Verifier() {
    private val bc = BouncyCastleProvider()

    override fun checkDigest(stream: FitzInputStream, signature: ByteArray): Int {
        return try {
            val data = readAll(stream)
            val sd = CMSSignedData(CMSProcessableByteArray(data), signature)
            val certs = sd.certificates
            var any = false
            for (si in sd.signerInfos.signers) {
                any = true
                val holder = certs.getMatches(sel(si.sid)).firstOrNull() ?: return PKCS7Verifier.PKCS7VerifierNoCertificate
                val v = JcaSimpleSignerInfoVerifierBuilder().setProvider(bc).build(holder as org.bouncycastle.cert.X509CertificateHolder)
                if (!si.verify(v)) return PKCS7Verifier.PKCS7VerifierDigestFailure
            }
            if (any) PKCS7Verifier.PKCS7VerifierOK else PKCS7Verifier.PKCS7VerifierNoSignature
        } catch (e: Exception) {
            PKCS7Verifier.PKCS7VerifierDigestFailure
        }
    }

    override fun checkCertificate(signature: ByteArray): Int {
        return try {
            val sd = CMSSignedData(signature)
            val cf = CertificateFactory.getInstance("X.509")
            val certs = sd.certificates.getMatches(null).map {
                cf.generateCertificate(ByteArrayInputStream((it as org.bouncycastle.cert.X509CertificateHolder).encoded)) as X509Certificate
            }
            if (certs.isEmpty()) return PKCS7Verifier.PKCS7VerifierNoCertificate
            val signerId = sd.signerInfos.signers.firstOrNull()?.sid
            val leaf = certs.firstOrNull { c ->
                signerId != null && sd.certificates.getMatches(sel(signerId)).any {
                    (it as org.bouncycastle.cert.X509CertificateHolder).encoded.contentEquals(c.encoded)
                }
            } ?: certs[0]
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(null as KeyStore?)
            val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
            val ordered = listOf(leaf) + certs.filter { it != leaf }
            try {
                tm.checkClientTrusted(ordered.toTypedArray(), "RSA")
                PKCS7Verifier.PKCS7VerifierOK
            } catch (e: Exception) {
                if (leaf.subjectX500Principal == leaf.issuerX500Principal) PKCS7Verifier.PKCS7VerifierSelfSigned
                else if (certs.any { it.subjectX500Principal == it.issuerX500Principal }) PKCS7Verifier.PKCS7VerifierSelfSignedInChain
                else PKCS7Verifier.PKCS7VerifierNotTrusted
            }
        } catch (e: Exception) {
            PKCS7Verifier.PKCS7VerifierUnknown
        }
    }
}

object Signing {
    data class Sig(val page: Int, val field: String, val digest: String, val cert: String)

    private fun digestText(c: Int) = when (c) {
        PDFWidget.SIGNATURE_ERROR_OKAY -> "Bütünlük sağlam (imzadan sonra değişmemiş)"
        PDFWidget.SIGNATURE_ERROR_NO_SIGNATURES, PDFWidget.SIGNATURE_ERROR_NOT_SIGNED -> "İmzasız alan"
        PDFWidget.SIGNATURE_ERROR_DIGEST_FAILURE -> "BOZUK: belge imzadan sonra değiştirilmiş"
        PDFWidget.SIGNATURE_ERROR_NO_CERTIFICATE -> "İmzada sertifika yok"
        else -> "Doğrulanamadı ($c)"
    }

    private fun certText(c: Int) = when (c) {
        PDFWidget.SIGNATURE_ERROR_OKAY -> "Sertifika güvenilir bir kök tarafından onaylı"
        PDFWidget.SIGNATURE_ERROR_SELF_SIGNED -> "Kendinden imzalı sertifika (güven zinciri yok)"
        PDFWidget.SIGNATURE_ERROR_SELF_SIGNED_IN_CHAIN -> "Zincirde kendinden imzalı sertifika var"
        PDFWidget.SIGNATURE_ERROR_NOT_TRUSTED -> "Sertifika güvenilir değil"
        PDFWidget.SIGNATURE_ERROR_NO_CERTIFICATE -> "Sertifika bulunamadı"
        PDFWidget.SIGNATURE_ERROR_NOT_SIGNED, PDFWidget.SIGNATURE_ERROR_NO_SIGNATURES -> "-"
        else -> "Denetlenemedi ($c)"
    }

    /** Belgeyi P12 ile imzalar (artımlı kayıt). [visible] ise sayfanın sağ altında görünür kutu eklenir. */
    fun sign(src: File, out: File, p12: File, password: String, page: Int, visible: Boolean, reason: String, location: String) {
        src.copyTo(out, overwrite = true)
        val signer = P12Signer(p12, password)
        val d = Document.openDocument(out.path)
        try {
            val pdf = d.asPDF()
            if (page < 0 || page >= pdf.countPages()) throw IllegalArgumentException("Sayfa numarası geçersiz")
            val pg = pdf.loadPage(page) as PDFPage
            val w = pg.createSignature()
            if (visible) {
                val b = pg.bounds
                val bw = b.x1 - b.x0
                val bh = b.y1 - b.y0
                // MuPDF sayfa uzayında y aşağı artar
                w.setRect(Rect(b.x0 + bw - 230f, b.y0 + bh - 100f, b.x0 + bw - 30f, b.y0 + bh - 30f))
            } else {
                w.setRect(Rect(0f, 0f, 0f, 0f))
            }
            val ok = w.sign(signer, PDFWidget.SIGNATURE_DEFAULT_APPEARANCE, null, reason.ifBlank { null }, location.ifBlank { null })
            if (!ok) throw IOException("İmzalama başarısız")
            pdf.save(out.path, "incremental")
            pg.destroy()
        } finally {
            d.destroy()
            signer.destroy()
        }
    }

    fun verify(src: File): List<Sig> {
        val res = ArrayList<Sig>()
        val v = BcVerifier()
        val d = Document.openDocument(src.path)
        try {
            val pdf = d.asPDF()
            for (i in 0 until pdf.countPages()) {
                val pg = pdf.loadPage(i) as PDFPage
                for (w in pg.widgets ?: emptyArray()) {
                    if (w.fieldType != PDFWidget.TYPE_SIGNATURE) continue
                    val dg = w.checkDigest(v)
                    val ct = if (dg == PDFWidget.SIGNATURE_ERROR_OKAY) w.checkCertificate(v) else PDFWidget.SIGNATURE_ERROR_NOT_SIGNED
                    res.add(Sig(i + 1, w.name ?: "", digestText(dg), certText(ct)))
                }
                pg.destroy()
            }
        } finally {
            d.destroy()
            v.destroy()
        }
        return res
    }

    fun report(src: File, name: String): String {
        val s = verify(src)
        val sb = StringBuilder()
        sb.append("İmza raporu: ").append(name).append('\n')
        sb.append("Bulunan imza alanı: ").append(s.size).append("\n\n")
        for ((k, x) in s.withIndex()) {
            sb.append("${k + 1}. Sayfa ${x.page}, alan '${x.field}'\n")
            sb.append("   Bütünlük : ${x.digest}\n")
            sb.append("   Sertifika: ${x.cert}\n\n")
        }
        if (s.isEmpty()) sb.append("Bu belgede dijital imza yok.\n")
        return sb.toString()
    }
}
