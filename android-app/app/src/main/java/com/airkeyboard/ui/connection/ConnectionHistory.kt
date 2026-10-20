package com.airkeyboard.ui.connection

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A PC this tablet connected to; [name] is the PC name from the handshake */
data class RecentPc(val name: String, val host: String, val port: Int, val lastUsed: Long)

/**
 * The last [MAX] PCs connected to successfully, newest first, one entry per host:port.
 * Stored as a JSON array in the "connection" prefs, next to last_host.
 */
object ConnectionHistory {
    const val MAX = 10
    private const val PREFS = "connection"
    private const val KEY_HISTORY = "history"

    fun load(context: Context): List<RecentPc> {
        val json = prefs(context).getString(KEY_HISTORY, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                RecentPc(o.getString("name"), o.getString("host"), o.getInt("port"), o.getLong("lastUsed"))
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }

    fun record(context: Context, pc: RecentPc) = save(context, added(load(context), pc))

    fun remove(context: Context, pc: RecentPc) = save(context, removed(load(context), pc))

    /** [pc] first, any older entry for its host:port dropped, at most [MAX] kept */
    fun added(list: List<RecentPc>, pc: RecentPc): List<RecentPc> =
        (listOf(pc) + removed(list, pc)).take(MAX)

    fun removed(list: List<RecentPc>, pc: RecentPc): List<RecentPc> =
        list.filterNot { it.host == pc.host && it.port == pc.port }

    private fun save(context: Context, list: List<RecentPc>) {
        val array = JSONArray()
        list.forEach {
            array.put(JSONObject().put("name", it.name).put("host", it.host).put("port", it.port).put("lastUsed", it.lastUsed))
        }
        prefs(context).edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
