package com.frpmovie.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// "Vistos recientemente" por pestaña/servidor, guardado en el teléfono para
// que aparezca como primera fila del inicio (como "Continuar viendo").
object RecentStore {
    private const val PREFS = "frp_recent"
    private const val MAX = 20

    fun get(context: Context, key: String): List<Channel> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(key, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Channel(
                    streamId = o.optInt("id"),
                    name = o.optString("name"),
                    logo = o.optString("logo"),
                    category = o.optString("category"),
                    directUrl = if (o.has("url")) o.optString("url") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun add(context: Context, key: String, item: Channel) {
        val list = get(context, key).filterNot { sameItem(it, item) }.toMutableList()
        list.add(0, item)
        val arr = JSONArray()
        for (c in list.take(MAX)) {
            val o = JSONObject()
            o.put("id", c.streamId)
            o.put("name", c.name)
            o.put("logo", c.logo)
            o.put("category", c.category)
            c.directUrl?.let { o.put("url", it) }
            arr.put(o)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun sameItem(a: Channel, b: Channel): Boolean =
        if (a.directUrl != null || b.directUrl != null) a.directUrl == b.directUrl else a.streamId == b.streamId
}
