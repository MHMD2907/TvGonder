// v0.2 – 2026-10-12: son linkler + pano hafızası eklendi
package com.onur.tvgonder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class Store(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("tvgonder", Context.MODE_PRIVATE)

    // ---- TV tarafı ----
    var treeUri: String?
        get() = p.getString("tree", null)
        set(v) { p.edit().putString("tree", v).apply() }

    fun devices(): Map<String, String> {
        val o = JSONObject(p.getString("devices", "{}") ?: "{}")
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    fun addDevice(token: String, name: String) = saveDevices(devices() + (token to name))
    fun removeDevice(token: String) = saveDevices(devices() - token)
    private fun saveDevices(m: Map<String, String>) {
        p.edit().putString("devices", JSONObject(m as Map<*, *>).toString()).apply()
    }

    // ---- Telefon tarafı ----
    var tvHost: String?
        get() = p.getString("tvHost", null)
        set(v) { p.edit().putString("tvHost", v).apply() }
    var tvPort: Int
        get() = p.getInt("tvPort", ReceiverServer.PORT)
        set(v) { p.edit().putInt("tvPort", v).apply() }
    var tvToken: String?
        get() = p.getString("tvToken", null)
        set(v) { p.edit().putString("tvToken", v).apply() }
    var tvName: String
        get() = p.getString("tvName", "TV") ?: "TV"
        set(v) { p.edit().putString("tvName", v).apply() }
    var lastFolder: String
        get() = p.getString("lastFolder", "") ?: ""
        set(v) { p.edit().putString("lastFolder", v).apply() }

    /** Panodan en son önerilen/gönderilen link; aynı link tekrar tekrar önerilmesin diye. */
    var lastClip: String
        get() = p.getString("lastClip", "") ?: ""
        set(v) { p.edit().putString("lastClip", v).apply() }

    fun recentLinks(): List<String> {
        val a = runCatching { JSONArray(p.getString("recentLinks", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return List(a.length()) { a.getString(it) }
    }

    fun addRecentLink(url: String) {
        val list = (listOf(url) + recentLinks().filter { it != url }).take(8)
        p.edit().putString("recentLinks", JSONArray(list).toString()).apply()
    }

    fun clearTv() {
        p.edit().remove("tvHost").remove("tvToken").remove("tvName").apply()
    }
}
