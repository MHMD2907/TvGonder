// v0.28 – 2026-10-19: kızılötesi cihaz arşivi (Flipper-IRDB, 800+ dosya): kategori/marka/model listesi, indirme, kayıtlı cihazlar
package com.onur.tvgonder.ir

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object IrDb {
    data class Model(val path: String, val category: String, val brand: String, val model: String)
    data class Saved(val name: String, val path: String)

    /** Arşiv klasörü → ekranda görünen kategori. */
    val categories = linkedMapOf(
        "TVs" to "📺 Televizyon",
        "Cable_Boxes" to "📡 Uydu / kablo alıcısı",
        "DVB-T" to "📡 Uydu / kablo alıcısı",
        "TV_Tuner" to "📡 Uydu / kablo alıcısı",
        "Streaming_Devices" to "📦 TV kutusu / medya oynatıcı",
        "Audio_and_Video_Receivers" to "🔊 Ses sistemi / soundbar",
        "SoundBars" to "🔊 Ses sistemi / soundbar",
        "Projectors" to "📽️ Projeksiyon",
        "Universal_TV_Remotes" to "🌐 Evrensel TV kodları",
    )

    private var cache: Pair<String, List<Model>>? = null

    /** (sürüm, liste) – uygulamanın içindeki dizinden. */
    fun index(c: Context): Pair<String, List<Model>> {
        cache?.let { return it }
        val lines = c.assets.open("irdb.txt").bufferedReader().readLines()
        val rev = lines.firstOrNull()?.removePrefix("#") ?: "main"
        val list = lines.drop(1).filter { it.endsWith(".ir") }.mapNotNull { path ->
            val parts = path.split('/')
            if (parts.size < 2) return@mapNotNull null
            val cat = categories[parts[0]] ?: return@mapNotNull null
            val brand = if (parts.size >= 3) parts[1].replace('_', ' ') else "Genel"
            val model = parts.last().removeSuffix(".ir").replace('_', ' ')
            Model(path, cat, brand, model)
        }
        return (rev to list).also { cache = it }
    }

    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()

    private fun localFile(c: Context, path: String) =
        File(c.filesDir, "ir/" + path.replace('/', '_').replace(' ', '_')).apply { parentFile?.mkdirs() }

    /** Dosyayı (önbellekte yoksa internetten) getirir ve tuşları çözer. */
    fun load(c: Context, path: String): List<IrSignal> {
        val f = localFile(c, path)
        if (!f.exists() || f.length() < 20) {
            val rev = index(c).first
            val enc = path.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            val url = "https://raw.githubusercontent.com/Lucaslhm/Flipper-IRDB/$rev/$enc"
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) error("Kod dosyası indirilemedi (${r.code}). İnternet bağlantısını kontrol et.")
                f.writeText(r.body?.string() ?: error("Boş dosya"))
            }
        }
        return IrCodec.parse(f.readText())
    }

    // ---------------------------------------------------------------- kayıtlı cihazlar

    private fun prefs(c: Context) = c.getSharedPreferences("ir", Context.MODE_PRIVATE)

    fun saved(c: Context): List<Saved> = runCatching {
        val a = JSONArray(prefs(c).getString("devices", "[]"))
        List(a.length()) { i -> a.getJSONObject(i).let { Saved(it.getString("name"), it.getString("path")) } }
    }.getOrDefault(emptyList())

    private fun write(c: Context, list: List<Saved>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.name).put("path", it.path)) }
        prefs(c).edit().putString("devices", a.toString()).apply()
    }

    fun add(c: Context, s: Saved) { write(c, saved(c).filter { it.name != s.name } + s); current(c, s.name) }
    fun remove(c: Context, name: String) { write(c, saved(c).filter { it.name != name }) }

    fun current(c: Context): String? = prefs(c).getString("current", null)
    fun current(c: Context, name: String) { prefs(c).edit().putString("current", name).apply() }
}
