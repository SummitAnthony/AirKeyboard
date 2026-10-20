package com.airkeyboard.ui.bookmarks

import android.content.Context
import android.net.Uri
import com.airkeyboard.network.ChunkBuilder
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val name: String, val url: String)

/** Bookmarks as a JSON array in SharedPreferences, seeded with a few sites on first use */
object BookmarkStore {
    private const val PREFS = "bookmarks"
    private const val KEY_LIST = "list"

    private val DEFAULTS = listOf(
        Bookmark("YouTube", "https://www.youtube.com"),
        Bookmark("Netflix", "https://www.netflix.com"),
        Bookmark("Google", "https://www.google.com"),
        Bookmark("Spotify", "https://open.spotify.com"),
    )

    fun load(context: Context): List<Bookmark> {
        val json = prefs(context).getString(KEY_LIST, null) ?: return DEFAULTS
        return try {
            val array = JSONArray(json)
            List(array.length()) { i ->
                array.getJSONObject(i).let { Bookmark(it.getString("name"), it.getString("url")) }
            }
        } catch (e: Exception) {
            DEFAULTS
        }
    }

    fun save(context: Context, bookmarks: List<Bookmark>) {
        val array = JSONArray()
        bookmarks.forEach { array.put(JSONObject().put("name", it.name).put("url", it.url)) }
        prefs(context).edit().putString(KEY_LIST, array.toString()).apply()
    }

    /** [input] as an http(s) URL, with https:// added when it has no scheme; null when it isn't one */
    fun normalizeUrl(input: String): String? {
        val text = input.trim()
        if (text.isEmpty() || text.any { it.isWhitespace() }) return null
        val url = if ("://" in text) text else "https://$text"
        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrEmpty()) return null
        return url
    }

    /** True when opening [url] on the PC ("url <url>") would not fit in one system command frame */
    fun isUrlTooLong(url: String): Boolean = !ChunkBuilder.fitsSystem("url $url")

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
