package tr.baba.pdf

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Son açılan belgeler ve küçük ayarlar (SharedPreferences). */
object Recent {
    private const val SEP = "\u0001"
    private fun sp(ctx: Context) = ctx.getSharedPreferences("baba", Context.MODE_PRIVATE)

    fun list(ctx: Context): List<Pair<String, String>> {
        val raw = sp(ctx).getString("recent", "") ?: ""
        return raw.split("\n").filter { it.contains(SEP) }.map {
            val i = it.indexOf(SEP)
            it.substring(0, i) to it.substring(i + 1)
        }
    }

    fun add(ctx: Context, name: String, uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: Exception) {
            try {
                ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
            }
        }
        val items = list(ctx).filter { it.second != uri.toString() }.toMutableList()
        items.add(0, name.replace("\n", " ").replace(SEP, " ") to uri.toString())
        val text = items.take(10).joinToString("\n") { it.first + SEP + it.second }
        sp(ctx).edit().putString("recent", text).apply()
    }

    fun clear(ctx: Context) {
        sp(ctx).edit().remove("recent").apply()
    }

    fun bookFontSize(ctx: Context): Float = sp(ctx).getFloat("book_em", 12f)

    fun setBookFontSize(ctx: Context, em: Float) {
        sp(ctx).edit().putFloat("book_em", em).apply()
    }
}
