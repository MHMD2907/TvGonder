// v0.10 – 2026-10-13: tvInfo (bağlantı + sürüm + kayıt yeri durumu tek istekte)
package com.onur.tvgonder

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Telefonun TV ile konuşan tarafı. */
class TvClient(private val store: Store) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(0, TimeUnit.SECONDS)
        .build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun base() = "http://${store.tvHost}:${store.tvPort}"
    private fun builder(url: String) = Request.Builder().url(url).header("X-Token", store.tvToken ?: "")

    private fun check(resp: okhttp3.Response): JSONObject {
        val o = runCatching { JSONObject(resp.body?.string() ?: "{}") }.getOrDefault(JSONObject())
        if (!resp.isSuccessful) error(o.optString("error", "TV hata verdi (${resp.code})"))
        return o
    }

    fun pair(host: String, port: Int, code: String, name: String) {
        val body = JSONObject().put("code", code).put("name", name).toString().toRequestBody(jsonType)
        val req = Request.Builder().url("http://$host:$port/pair").post(body).build()
        http.newBuilder().readTimeout(70, TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
            val o = check(resp)
            store.tvHost = host
            store.tvPort = port
            store.tvToken = o.getString("token")
            store.tvName = o.optString("tvName", "TV")
        }
    }

    /** TV'ye ulaşılabiliyor mu, sürümü ve kayıt yeri seçili mi (USB'den bağımsız). */
    fun tvInfo(): JSONObject {
        http.newCall(Request.Builder().url("${base()}/ping").build()).execute().use { return check(it) }
    }

    /** TV'deki uygulamanın sürüm kodu; eski sürümlerde 0 döner. */
    fun tvVersion(): Long {
        http.newCall(Request.Builder().url("${base()}/ping").build()).execute().use { resp ->
            return check(resp).optLong("version", 0L)
        }
    }

    /** Telefonda kurulu APK'yı TV'ye gönderir (telefon ve TV aynı APK'yı kullanır). */
    fun sendUpdate(ctx: Context, onProgress: (Long, Long) -> Unit) {
        val apk = java.io.File(ctx.applicationInfo.sourceDir)
        val size = apk.length()
        val body = object : RequestBody() {
            override fun contentType() = "application/vnd.android.package-archive".toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                apk.inputStream().use {
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent, size)
                    }
                }
            }
        }
        http.newCall(builder("${base()}/update").post(body).build()).execute().use { check(it) }
    }

    fun folders(path: String): List<String> {
        val url = "${base()}/folders".toHttpUrl().newBuilder().addQueryParameter("path", path).build()
        http.newCall(builder(url.toString()).build()).execute().use { resp ->
            val arr = check(resp).getJSONArray("folders")
            return List(arr.length()) { arr.getString(it) }
        }
    }

    fun mkdir(path: String, name: String) {
        val body = JSONObject().put("path", path).put("name", name).toString().toRequestBody(jsonType)
        http.newCall(builder("${base()}/mkdir").post(body).build()).execute().use { check(it) }
    }

    fun link(url: String) {
        val body = JSONObject().put("url", url).toString().toRequestBody(jsonType)
        http.newCall(builder("${base()}/link").post(body).build()).execute().use { check(it) }
    }

    /** onProgress(gönderilen, toplam) arka plan iş parçacığında çağrılır. */
    fun upload(ctx: Context, uri: Uri, folder: String, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        var name = "dosya"
        var size = -1L
        ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val si = c.getColumnIndex(OpenableColumns.SIZE)
                if (ni >= 0) name = c.getString(ni) ?: name
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
            }
        }
        if (size < 0) size = ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size < 0) error("$name boyutu okunamadı")
        val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"

        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = ctx.contentResolver.openInputStream(uri) ?: error("$name açılamadı")
                input.use {
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = it.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        sent += n
                        onProgress(sent, size)
                    }
                }
            }
        }
        val req = builder("${base()}/upload")
            .header("X-Folder", URLEncoder.encode(folder, "UTF-8"))
            .header("X-Name", URLEncoder.encode(name, "UTF-8"))
            .post(body).build()
        http.newCall(req).execute().use { check(it) }
    }
}
