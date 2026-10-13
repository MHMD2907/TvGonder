// v0.13 – 2026-10-13: birden fazla alıcı (TV/tablet), alıcı modu; son gelenler, boşta slayt
package com.onur.tvgonder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Telefonun eşleştiği bir alıcı (TV veya tablet). */
data class Target(val name: String, val host: String, val port: Int, val token: String)

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

    /** TV boşta kalınca (5 dk) slayt gösterisi başlasın mı. */
    var idleSlides: Boolean
        get() = p.getBoolean("idleSlides", true)
        set(v) { p.edit().putBoolean("idleSlides", v).apply() }

    fun received(): List<Received> = runCatching {
        val a = JSONArray(p.getString("received", "[]") ?: "[]")
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Received(o.getString("n"), o.getString("u"), o.optString("m"), o.optLong("t"))
        }
    }.getOrDefault(emptyList())

    fun addReceived(r: Received) {
        val list = (listOf(r) + received().filter { it.uri != r.uri }).take(12)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("u", it.uri).put("m", it.mime).put("t", it.at)) }
        p.edit().putString("received", a.toString()).apply()
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

    // ---- Ayarlar ----
    var autoClip: Boolean
        get() = p.getBoolean("autoClip", true)
        set(v) { p.edit().putBoolean("autoClip", v).apply() }
    var defA5: Boolean
        get() = p.getBoolean("defA5", false)
        set(v) { p.edit().putBoolean("defA5", v).apply() }
    var defColor: Boolean
        get() = p.getBoolean("defColor", true)
        set(v) { p.edit().putBoolean("defColor", v).apply() }

    fun recentLinks(): List<String> {
        val a = runCatching { JSONArray(p.getString("recentLinks", "[]") ?: "[]") }.getOrDefault(JSONArray())
        return List(a.length()) { a.getString(it) }
    }

    fun addRecentLink(url: String) {
        val list = (listOf(url) + recentLinks().filter { it != url }).take(8)
        p.edit().putString("recentLinks", JSONArray(list).toString()).apply()
    }

    /** Bu cihaz TV gibi alıcı olarak mı çalışsın (tablet için). */
    var receiverMode: Boolean
        get() = p.getBoolean("receiverMode", false)
        set(v) { p.edit().putBoolean("receiverMode", v).apply() }

    fun targets(): List<Target> {
        val a = runCatching { JSONArray(p.getString("targets", "[]") ?: "[]") }.getOrDefault(JSONArray())
        val list = List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Target(o.optString("n", "TV"), o.getString("h"), o.optInt("p", ReceiverServer.PORT), o.getString("t"))
        }
        // Eski sürümden kalan tek eşleşmeyi listeye taşı
        val tok = tvToken; val host = tvHost
        if (list.isEmpty() && tok != null && host != null) return listOf(Target(tvName, host, tvPort, tok)).also { saveTargets(it) }
        return list
    }

    private fun saveTargets(list: List<Target>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("h", it.host).put("p", it.port).put("t", it.token)) }
        p.edit().putString("targets", a.toString()).apply()
    }

    fun addTarget(t: Target) = saveTargets(targets().filter { it.token != t.token && it.host != t.host } + t)

    fun selectTarget(t: Target) { tvHost = t.host; tvPort = t.port; tvToken = t.token; tvName = t.name }

    /** Seçili alıcıyı listeden çıkarır; başka varsa ona geçer. */
    fun removeCurrentTarget() {
        val rest = targets().filter { it.token != tvToken }
        saveTargets(rest)
        clearTv()
        rest.firstOrNull()?.let { selectTarget(it) }
    }

    fun clearTv() {
        p.edit().remove("tvHost").remove("tvToken").remove("tvName").apply()
    }
}
