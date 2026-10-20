// v0.24 – 2026-10-19: kayıt klasörü her cihaz için ayrı; çoklu hedef, geçmiş, video konumu, döndürme
package com.onur.tvgonder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Telefonun eşleştiği bir alıcı (TV veya tablet). */
data class Target(val name: String, val host: String, val port: Int, val token: String, val id: String = "")

/** Telefonda gönderim geçmişi satırı. kind: "link" ya da "file". */
data class Sent(val kind: String, val name: String, val uri: String, val target: String, val at: Long)

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

    fun removeReceived(uri: String) {
        val list = received().filter { it.uri != uri }
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("u", it.uri).put("m", it.mime).put("t", it.at)) }
        p.edit().putString("received", a.toString()).apply()
    }

    /** Gönderilen dosyanın adının başına gönderim tarihi eklensin mi (telefon). */
    var datePrefix: Boolean
        get() = p.getBoolean("datePrefix", true)
        set(v) { p.edit().putBoolean("datePrefix", v).apply() }

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
    /** Kayıt klasörü her cihaz (TV/tablet) için ayrı hatırlanır. */
    var lastFolder: String
        get() = p.getString("lastFolder_$tvId", null) ?: p.getString("lastFolder", "") ?: ""
        set(v) { p.edit().putString("lastFolder_$tvId", v).apply() }

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
            Target(o.optString("n", "TV"), o.getString("h"), o.optInt("p", ReceiverServer.PORT), o.getString("t"), o.optString("i"))
        }
        // Eski sürümden kalan tek eşleşmeyi listeye taşı
        val tok = tvToken; val host = tvHost
        if (list.isEmpty() && tok != null && host != null) return listOf(Target(tvName, host, tvPort, tok, tvId)).also { saveTargets(it) }
        return list
    }

    private fun saveTargets(list: List<Target>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("n", it.name).put("h", it.host).put("p", it.port).put("t", it.token).put("i", it.id)) }
        p.edit().putString("targets", a.toString()).apply()
    }

    fun addTarget(t: Target) = saveTargets(targets().filter { it.token != t.token && it.host != t.host } + t)

    fun selectTarget(t: Target) { tvHost = t.host; tvPort = t.port; tvToken = t.token; tvName = t.name; tvId = t.id }

    /** Seçili alıcının kalıcı kimliği (IP değişse de aynı kalır). */
    var tvId: String
        get() = p.getString("tvId", "") ?: ""
        set(v) { p.edit().putString("tvId", v).apply() }

    /** Bu cihaz alıcıyken kullandığı kalıcı kimlik. */
    val receiverId: String
        get() = p.getString("receiverId", null) ?: java.util.UUID.randomUUID().toString().also { p.edit().putString("receiverId", it).apply() }

    fun history(): List<Sent> = runCatching {
        val a = JSONArray(p.getString("history", "[]") ?: "[]")
        List(a.length()) { i -> a.getJSONObject(i).let { Sent(it.getString("k"), it.getString("n"), it.getString("u"), it.optString("t"), it.optLong("a")) } }
    }.getOrDefault(emptyList())

    fun addHistory(s: Sent) {
        val list = (listOf(s) + history()).take(150)
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("k", it.kind).put("n", it.name).put("u", it.uri).put("t", it.target).put("a", it.at)) }
        p.edit().putString("history", a.toString()).apply()
    }

    /** Gönderim geçmişini ve "Son linkler" listesini birlikte siler. */
    fun clearHistory() { p.edit().remove("history").remove("recentLinks").apply() }

    fun clearRecentLinks() { p.edit().remove("recentLinks").apply() }

    fun removeRecentLink(url: String) {
        p.edit().putString("recentLinks", JSONArray(recentLinks().filter { it != url }).toString()).apply()
    }

    /** Resimler ekranı doldursun mu (oran korunur, kenar kırpılır). */
    var imageFill: Boolean
        get() = p.getBoolean("imageFill", true)
        set(v) { p.edit().putBoolean("imageFill", v).apply() }

    /** Video ekranı doldursun mu (oran korunur, kenar kırpılır). */
    var videoFill: Boolean
        get() = p.getBoolean("videoFill2", false)
        set(v) { p.edit().putBoolean("videoFill2", v).apply() }

    /** Resmin elle verilen dönüş açısı (0/90/180/270). */
    fun rotation(key: String): Int = p.getInt("rot_" + key.hashCode(), 0)
    fun setRotation(key: String, deg: Int) {
        p.edit().apply { if (deg % 360 == 0) remove("rot_" + key.hashCode()) else putInt("rot_" + key.hashCode(), ((deg % 360) + 360) % 360) }.apply()
    }

    /** Video kaldığı yer (ms). */
    fun position(key: String): Long = p.getLong("pos_" + key.hashCode(), 0L)
    fun setPosition(key: String, ms: Long) {
        p.edit().apply { if (ms <= 0) remove("pos_" + key.hashCode()) else putLong("pos_" + key.hashCode(), ms) }.apply()
    }

    /** Seçili alıcıyı listeden çıkarır; başka varsa ona geçer. */
    fun removeCurrentTarget() {
        val rest = targets().filter { it.token != tvToken }
        saveTargets(rest)
        clearTv()
        rest.firstOrNull()?.let { selectTarget(it) }
    }

    fun clearTv() {
        p.edit().remove("tvHost").remove("tvToken").remove("tvName").remove("tvId").apply()
    }
}
