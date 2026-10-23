package tr.baba.pdf

import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/** RTF, MOBI/AZW ve IBA dosyalarını okunabilir tek parça HTML'e çevirir (MuPDF HTML'i sayfalar). */
object Converters {
    val EXTS = setOf("rtf", "mobi", "azw", "azw3", "prc", "iba")
    private const val MAX = 80L * 1024 * 1024

    fun toHtml(src: File, ext: String, out: File) {
        if (src.length() > MAX) throw IllegalArgumentException("Dosya çok büyük")
        val body = when (ext.lowercase()) {
            "rtf" -> rtf(src.readBytes())
            "iba" -> iba(src)
            else -> mobi(src.readBytes())
        }
        out.writeText(
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title></title>" +
                "<style>body{font-family:serif;line-height:1.35} p{margin:0 0 0.6em 0} img{max-width:100%}</style>" +
                "</head><body>$body</body></html>", Charsets.UTF_8
        )
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------------------------------------------------------------- RTF
    private val SKIP = setOf(
        "fonttbl", "colortbl", "stylesheet", "info", "header", "footer", "headerl", "headerr", "footerl", "footerr",
        "pict", "object", "footnote", "themedata", "colorschememapping", "latentstyles", "datastore", "listtable",
        "listoverridetable", "rsidtbl", "generator", "xmlnstbl", "fldinst", "shpinst", "private"
    )

    private class RtfState(var skip: Boolean, var b: Boolean, var i: Boolean, var u: Boolean, var uc: Int)

    fun rtf(data: ByteArray): String {
        if (!String(data, 0, minOf(data.size, 64), Charsets.ISO_8859_1).trimStart().startsWith("{\\rtf"))
            throw IllegalArgumentException("Bu dosya geçerli bir RTF belgesi gibi görünmüyor")
        val s = String(data, Charsets.ISO_8859_1)
        var cs: Charset = Charset.forName("windows-1252")
        val html = StringBuilder()
        var para = StringBuilder()
        var st = RtfState(false, false, false, false, 1)
        val stack = ArrayList<RtfState>()
        var pendingStar = false
        var first = true
        var skipChars = 0
        var open = Triple(false, false, false) // açık b,i,u etiketleri

        fun closeTags() {
            if (open.third) para.append("</u>")
            if (open.second) para.append("</i>")
            if (open.first) para.append("</b>")
            open = Triple(false, false, false)
        }
        fun sync() {
            if (open.first != st.b || open.second != st.i || open.third != st.u) {
                closeTags()
                if (st.b) para.append("<b>")
                if (st.i) para.append("<i>")
                if (st.u) para.append("<u>")
                open = Triple(st.b, st.i, st.u)
            }
        }
        fun text(t: String) {
            if (st.skip || t.isEmpty()) return
            sync()
            para.append(esc(t))
        }
        fun endPara() {
            closeTags()
            val p = para.toString()
            html.append(if (p.isBlank()) "<p>&nbsp;</p>\n" else "<p>$p</p>\n")
            para = StringBuilder()
        }

        var i = 0
        val n = s.length
        val pend = java.io.ByteArrayOutputStream()
        fun flushBytes() {
            if (pend.size() > 0) {
                text(String(pend.toByteArray(), cs))
                pend.reset()
            }
        }
        while (i < n) {
            val c = s[i]
            when (c) {
                '{' -> {
                    flushBytes()
                    stack.add(RtfState(st.skip, st.b, st.i, st.u, st.uc))
                    pendingStar = false
                    i++
                }
                '}' -> {
                    flushBytes()
                    if (stack.isNotEmpty()) st = stack.removeAt(stack.size - 1)
                    i++
                }
                '\\' -> {
                    i++
                    if (i >= n) break
                    val d = s[i]
                    if (d.isLetter()) {
                        var j = i
                        while (j < n && s[j].isLetter()) j++
                        val word = s.substring(i, j)
                        var k = j
                        if (k < n && (s[k] == '-' || s[k].isDigit())) {
                            k++
                            while (k < n && s[k].isDigit()) k++
                        }
                        val param = s.substring(j, k).toIntOrNull()
                        if (k < n && s[k] == ' ') k++
                        i = k
                        if (pendingStar) {
                            st.skip = true
                            pendingStar = false
                        }
                        if (word in SKIP) st.skip = true
                        when (word) {
                            "ansicpg" -> param?.let { try { cs = Charset.forName("windows-$it") } catch (_: Exception) {} }
                            "par", "sect" -> { flushBytes(); if (!st.skip) endPara(); first = false }
                            "line" -> { flushBytes(); if (!st.skip) para.append("<br/>") }
                            "tab" -> { flushBytes(); text("    ") }
                            "emdash" -> text("—")
                            "endash" -> text("–")
                            "bullet" -> text("•")
                            "lquote" -> text("‘")
                            "rquote" -> text("’")
                            "ldblquote" -> text("“")
                            "rdblquote" -> text("”")
                            "b" -> { flushBytes(); st.b = param != 0 }
                            "i" -> { flushBytes(); st.i = param != 0 }
                            "ul" -> { flushBytes(); st.u = param != 0 }
                            "ulnone" -> { flushBytes(); st.u = false }
                            "pard", "plain" -> { flushBytes(); if (word == "plain") { st.b = false; st.i = false; st.u = false } }
                            "uc" -> st.uc = param ?: 1
                            "u" -> {
                                flushBytes()
                                var v = param ?: 0
                                if (v < 0) v += 65536
                                text(String(Character.toChars(v)))
                                skipChars = st.uc
                            }
                        }
                    } else {
                        when (d) {
                            '\'' -> {
                                val h = if (i + 1 < n) s.substring(i + 1, minOf(i + 3, n)) else ""
                                i += 3
                                if (skipChars > 0) { skipChars--; continue }
                                h.toIntOrNull(16)?.let { if (!st.skip) pend.write(it) }
                            }
                            '*' -> { pendingStar = true; i++ }
                            '~' -> { flushBytes(); text(" "); i++ }
                            '_' -> { flushBytes(); text("‑"); i++ }
                            '-' -> i++
                            '\\', '{', '}' -> { flushBytes(); text(d.toString()); i++ }
                            else -> i++
                        }
                    }
                }
                '\r', '\n' -> i++
                else -> {
                    if (skipChars > 0) { skipChars--; i++; continue }
                    // ham metin
                    if (c.code < 128) { flushBytes(); text(c.toString()) } else pend.write(c.code)
                    i++
                }
            }
        }
        flushBytes()
        if (para.isNotEmpty()) endPara()
        if (first && html.isEmpty()) throw IllegalArgumentException("RTF içinde okunabilir metin yok")
        return html.toString()
    }

    // ---------------------------------------------------------------- IBA
    private val IMG_EXT = listOf(".png", ".jpg", ".jpeg", ".gif", ".bmp", ".tif", ".tiff")

    private fun local(n: Node) = (n.nodeName ?: "").substringAfter(':').lowercase()

    private fun ibaText(e: Element): String {
        val sb = StringBuilder()
        var c = e.firstChild
        while (c != null) {
            when (c.nodeType) {
                Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> sb.append(c.nodeValue)
                Node.ELEMENT_NODE -> when (val nm = local(c)) {
                    "br", "lnbr", "crbr", "pgbr", "intr" -> sb.append('\n')
                    "tab" -> sb.append('\t')
                    "ghost-text", "attachment", "ghost-p" -> {}
                    else -> sb.append(ibaText(c as Element)).also { nm.length }
                }
            }
            c = c.nextSibling
        }
        return sb.toString()
    }

    fun iba(f: File): String {
        val zip = try { ZipFile(f) } catch (e: Exception) {
            throw IllegalArgumentException("Bu dosya geçerli bir IBA (iBooks Author) belgesi gibi görünmüyor")
        }
        zip.use { z ->
            val names = z.entries().toList().map { it.name }
            val lookup = HashMap<String, String>()
            for (nm in names) {
                lookup[nm.lowercase()] = nm
                if (nm.contains('/')) lookup.getOrPut(nm.substringAfter('/').lowercase()) { nm }
            }
            val idx = names.filter { it.lowercase().substringAfterLast('/') in setOf("index.xml.gz", "index.xml") }
                .minByOrNull { it.count { ch -> ch == '/' } * 2 + (if (it.lowercase().endsWith(".gz")) 0 else 1) }
                ?: throw IllegalArgumentException("IBA içinde index.xml bulunamadı")
            var raw = z.getInputStream(z.getEntry(idx)).use { it.readBytes() }
            if (idx.lowercase().endsWith(".gz") || (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()))
                raw = GZIPInputStream(ByteArrayInputStream(raw)).use { it.readBytes() }
            val dbf = DocumentBuilderFactory.newInstance()
            dbf.isNamespaceAware = false
            try { dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
            val root = dbf.newDocumentBuilder().parse(ByteArrayInputStream(raw)).documentElement
            val out = StringBuilder()
            val seen = HashSet<String>()

            fun imgOf(e: Element) {
                val attrs = e.attributes
                for (a in 0 until attrs.length) {
                    val at = attrs.item(a)
                    if (at.nodeName.substringAfter(':').lowercase() != "path") continue
                    val v = at.nodeValue ?: continue
                    if (IMG_EXT.none { v.lowercase().endsWith(it) }) continue
                    val member = lookup[v.lowercase()] ?: lookup[java.net.URLDecoder.decode(v, "UTF-8").lowercase()] ?: continue
                    if (!seen.add(member)) continue
                    val en = z.getEntry(member) ?: continue
                    if (en.size > 50L * 1024 * 1024) continue
                    val b = z.getInputStream(en).use { it.readBytes() }
                    val mime = when {
                        member.lowercase().endsWith(".png") -> "image/png"
                        member.lowercase().endsWith(".gif") -> "image/gif"
                        member.lowercase().endsWith(".bmp") -> "image/bmp"
                        member.lowercase().endsWith(".tif") || member.lowercase().endsWith(".tiff") -> "image/tiff"
                        else -> "image/jpeg"
                    }
                    out.append("<p style=\"text-align:center\"><img src=\"data:$mime;base64,")
                        .append(Base64.encodeToString(b, Base64.NO_WRAP)).append("\"/></p>\n")
                }
            }

            fun walk(e: Element) {
                if (local(e) == "p") {
                    val t = ibaText(e).trim()
                    if (t.isNotEmpty()) {
                        val style = (0 until e.attributes.length).map { e.attributes.item(it) }
                            .filter { it.nodeName.substringAfter(':').lowercase() == "style" }
                            .joinToString(" ") { it.nodeValue.orEmpty() }.lowercase()
                        var tag = "p"
                        if ("title" in style) tag = "h1"
                        else if ("heading" in style || "chapter" in style || "head" in style) {
                            val dg = style.firstOrNull { it.isDigit() }?.digitToInt()
                            tag = if (dg != null) "h${minOf(dg + 1, 6)}" else "h2"
                        }
                        out.append('<').append(tag).append('>')
                            .append(esc(t).replace("\n", "<br/>").replace("\t", "&#160;&#160;&#160;&#160;"))
                            .append("</").append(tag).append(">\n")
                    }
                    imgOf(e)
                    // alt öğelerdeki resimler
                    val all = e.getElementsByTagName("*")
                    for (k in 0 until all.length) imgOf(all.item(k) as Element)
                    return
                }
                imgOf(e)
                var c = e.firstChild
                while (c != null) {
                    if (c.nodeType == Node.ELEMENT_NODE) walk(c as Element)
                    c = c.nextSibling
                }
            }
            walk(root)
            if (out.isEmpty()) throw IllegalArgumentException("IBA dosyasında okunabilir metin veya resim bulunamadı")
            return out.toString()
        }
    }

    // ---------------------------------------------------------------- MOBI / AZW
    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 255) shl 8) or (b[o + 1].toInt() and 255)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o) shl 16) or u16(b, o + 2)

    private fun palmDecompress(d: ByteArray): ByteArray {
        val o = java.io.ByteArrayOutputStream(d.size * 3)
        val buf = ArrayList<Byte>()
        var i = 0
        val out = ByteArray(d.size * 8 + 64)
        var p = 0
        fun put(b: Int) {
            if (p >= out.size) return
            out[p++] = b.toByte()
        }
        while (i < d.size) {
            val c = d[i++].toInt() and 255
            when {
                c == 0 -> put(0)
                c in 1..8 -> { var k = 0; while (k < c && i < d.size) { put(d[i++].toInt()); k++ } }
                c < 0x80 -> put(c)
                c >= 0xC0 -> { put(' '.code); put(c xor 0x80) }
                else -> {
                    if (i >= d.size) break
                    val v = ((c shl 8) or (d[i++].toInt() and 255)) and 0x3FFF
                    val len = (v and 7) + 3
                    val dist = v shr 3
                    for (k in 0 until len) {
                        if (dist <= 0 || p - dist < 0) break
                        put(out[p - dist].toInt())
                    }
                }
            }
        }
        o.write(out, 0, p)
        buf.clear()
        return o.toByteArray()
    }

    /** Sondaki ek veriyi (extra data flags) kayıt sonundan çıkarır. */
    private fun trimTrailing(rec: ByteArray, flags: Int): ByteArray {
        var end = rec.size
        for (bit in 15 downTo 1) {
            if (flags and (1 shl bit) != 0 && end > 0) {
                // değişken uzunluklu sayı: sondan okunur
                var size = 0
                var shift = 0
                var pos = end - 1
                while (pos >= 0) {
                    val b = rec[pos].toInt() and 255
                    size = size or ((b and 0x7F) shl shift)
                    shift += 7
                    pos--
                    if (b and 0x80 != 0 || shift > 28) break
                }
                end -= size
            }
        }
        if (flags and 1 != 0 && end > 0) end -= ((rec[end - 1].toInt() and 3) + 1)
        return rec.copyOf(end.coerceIn(0, rec.size))
    }

    fun mobi(b: ByteArray): String {
        if (b.size < 80) throw IllegalArgumentException("Dosya çok küçük")
        val type = String(b, 60, 8, Charsets.ISO_8859_1)
        if (!type.startsWith("BOOKMOBI") && !type.startsWith("TEXtREAd"))
            throw IllegalArgumentException("Bu dosya geçerli bir MOBI/AZW belgesi gibi görünmüyor")
        val nrec = u16(b, 76)
        val offs = IntArray(nrec + 1)
        for (i in 0 until nrec) offs[i] = u32(b, 78 + i * 8)
        offs[nrec] = b.size
        fun rec(i: Int): ByteArray = b.copyOfRange(offs[i], minOf(offs[i + 1], b.size).coerceAtLeast(offs[i]))
        val r0 = rec(0)
        val comp = u16(r0, 0)
        val textRecs = u16(r0, 8)
        var enc = 1252
        var flags = 0
        var firstImg = -1
        if (r0.size > 0xB0 && String(r0, 16, 4, Charsets.ISO_8859_1) == "MOBI") {
            val hl = u32(r0, 20)
            enc = u32(r0, 28)
            firstImg = u32(r0, 108)
            if (hl >= 0xE4 && r0.size >= 0xF4) flags = u16(r0, 0xF2)
        }
        if (u16(r0, 0x0C) == 2) throw IllegalArgumentException("Şifreli (DRM) Kindle dosyası açılamaz")
        if (comp != 1 && comp != 2) throw IllegalArgumentException("Bu sıkıştırma türü (HUFF/CDIC) desteklenmiyor")
        val text = java.io.ByteArrayOutputStream()
        for (i in 1..minOf(textRecs, nrec - 1)) {
            var r = rec(i)
            r = trimTrailing(r, flags)
            text.write(if (comp == 2) palmDecompress(r) else r)
        }
        val cs = if (enc == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        var html = String(text.toByteArray(), cs)
        if (type.startsWith("TEXtREAd")) html = "<pre>" + esc(html) + "</pre>"
        html = html.replace(Regex("<mbp:pagebreak[^>]*>", RegexOption.IGNORE_CASE), "<hr/>")
            .replace(Regex("</?(mbp:[a-z]+|body|html|head)[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("<(a|link|\\?xml)[^>]*(filepos|kindle:)[^>]*>", RegexOption.IGNORE_CASE), "")
        // resimler: recindex="00001" → kayıt firstImg + n - 1
        if (firstImg in 1 until nrec) {
            html = Regex("<img([^>]*?)recindex=\"?(\\d+)\"?([^>]*)>", RegexOption.IGNORE_CASE).replace(html) { m ->
                val k = m.groupValues[2].toInt()
                val ri = firstImg + k - 1
                if (ri in 1 until nrec) {
                    val d = rec(ri)
                    val mime = when {
                        d.size > 3 && d[0] == 0x89.toByte() -> "image/png"
                        d.size > 3 && d[0] == 'G'.code.toByte() -> "image/gif"
                        else -> "image/jpeg"
                    }
                    "<img src=\"data:$mime;base64,${Base64.encodeToString(d, Base64.NO_WRAP)}\"/>"
                } else ""
            }
        }
        if (html.isBlank()) throw IllegalArgumentException("Kitapta okunabilir metin bulunamadı")
        return html
    }
}
