// v0.33 – 2026-10-21: Fare eklentisi uygulamanın içinden gönderilir; uzaktan uygulamalar, TV ekranını isteme, fare komutları, indirme, eşleşme
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

/** TV'deki bir dosya/klasör (telefondan gezmek için). */
data class RemoteFile(val name: String, val dir: Boolean, val size: Long, val mime: String, val mod: Long)

/** Fare/kumanda komutlarını TV'ye UDP ile yollar (düşük gecikme). */
class Remote(private val host: String, private val token: String) {
    private val sock = runCatching { java.net.DatagramSocket() }.getOrNull()
    private val exec = java.util.concurrent.Executors.newSingleThreadExecutor()
    @Volatile private var addr: java.net.InetAddress? = null

    private val mouseCmds = setOf("m", "c", "lc", "s", "back", "home", "t", "lp", "sw")

    /** Fare komutlarının gideceği yer: 8767 = Fare eklentisi (her yerde), 8766 = TV Gönder'in kendi faresi. */
    @Volatile var mousePort = 8767

    fun send(cmd: String) {
        if (exec.isShutdown) return
        exec.execute {
            runCatching {
                val a = addr ?: java.net.InetAddress.getByName(host).also { addr = it }
                val b = "$token $cmd".toByteArray()
                // İmleç komutları "TV Gönder Fare" eklentisine, medya/ses ana uygulamaya
                val port = if (cmd.substringBefore(' ') in mouseCmds) mousePort else RemoteListener.PORT
                sock?.send(java.net.DatagramPacket(b, b.size, a, port))
            }
        }
    }

    /** TV'de Fare eklentisi kurulu ve açık mı (ping → pong). Arka planda çağır. */
    fun probeMouse(): Boolean = probe(8767)

    /** TV Gönder (ana uygulama) cevap veriyor mu. */
    fun probeApp(): Boolean = probe(RemoteListener.PORT)

    private fun probe(port: Int): Boolean = runCatching {
        java.net.DatagramSocket().use { s ->
            s.soTimeout = 1200
            val b = "$token ping".toByteArray()
            s.send(java.net.DatagramPacket(b, b.size, java.net.InetAddress.getByName(host), port))
            val buf = ByteArray(16)
            val p = java.net.DatagramPacket(buf, buf.size)
            s.receive(p)
            String(buf, 0, p.length) == "pong"
        }
    }.getOrDefault(false)

    fun close() { exec.shutdown(); runCatching { sock?.close() } }
}

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
            store.tvId = o.optString("id")
            store.addTarget(Target(store.tvName, host, port, store.tvToken!!, store.tvId))
        }
    }

    /** TV'ye ulaşılabiliyor mu, sürümü ve kayıt yeri seçili mi (USB'den bağımsız). */
    fun tvInfo(): JSONObject {
        http.newCall(Request.Builder().url("${base()}/ping").build()).execute().use { return check(it) }
    }

    /**
     * Seçili alıcıya ulaşılamıyorsa (modem IP'yi değiştirmiş olabilir) aynı ağda arar.
     * Bulursa yeni adresi kaydeder ve true döner. ~3-5 saniye sürer.
     */
    fun rediscover(): Boolean {
        val ip = Net.localIp() ?: return false
        val token = store.tvToken ?: return false
        val prefix = ip.substringBeforeLast('.')
        val port = store.tvPort
        val wantId = store.tvId
        val wantName = store.tvName
        val quick = http.newBuilder()
            .connectTimeout(400, TimeUnit.MILLISECONDS)
            .readTimeout(800, TimeUnit.MILLISECONDS)
            .build()
        val found = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(32)
        try {
            val jobs = (1..254).map { i ->
                pool.submit(Runnable {
                    if (found.get() != null) return@Runnable
                    val h = "$prefix.$i"
                    runCatching {
                        quick.newCall(Request.Builder().url("http://$h:$port/ping").build()).execute().use { r ->
                            if (r.isSuccessful) {
                                val o = JSONObject(r.body?.string() ?: "{}")
                                val match = if (wantId.isNotBlank()) o.optString("id") == wantId
                                else o.optString("app") == "tvgonder" && o.optString("name") == wantName
                                if (match) found.compareAndSet(null, h)
                            }
                        }
                    }
                })
            }
            jobs.forEach { runCatching { it.get() } }
        } finally {
            pool.shutdownNow()
        }
        val h = found.get() ?: return false
        store.tvHost = h
        store.addTarget(Target(store.tvName, h, port, token, wantId))
        return true
    }

    /** TV'deki uygulamanın sürüm kodu; eski sürümlerde 0 döner. */
    fun tvVersion(): Long {
        http.newCall(Request.Builder().url("${base()}/ping").build()).execute().use { resp ->
            return check(resp).optLong("version", 0L)
        }
    }

    /** Telefonda kurulu APK'yı TV'ye gönderir (telefon ve TV aynı APK'yı kullanır). */
    /** Fare eklentisinin en son sürümünü GitHub'dan indirir (telefonun internete ihtiyacı var). */
    /** Fare eklentisi: uygulamanın içinden (internet gerekmez). */
    fun fetchFareApk(ctx: Context): java.io.File = Apps.fareApk(ctx)

    /** Telefondaki APK'yı (varsayılan: bu uygulamanın kendisi) TV'ye kurulum için gönderir. */
    fun sendUpdate(ctx: Context, file: java.io.File? = null, onProgress: (Long, Long) -> Unit) {
        val apk = file ?: java.io.File(ctx.applicationInfo.sourceDir)
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

    fun files(path: String): List<RemoteFile> {
        val url = "${base()}/files".toHttpUrl().newBuilder().addQueryParameter("path", path).build()
        http.newCall(builder(url.toString()).build()).execute().use { resp ->
            val arr = check(resp).getJSONArray("items")
            return List(arr.length()) { i ->
                arr.getJSONObject(i).let { RemoteFile(it.optString("name"), it.optBoolean("dir"), it.optLong("size"), it.optString("mime"), it.optLong("mod")) }
            }
        }
    }

    /** TV'deki dosyayı out'a yazar. */
    fun download(path: String, name: String, out: java.io.OutputStream, onProgress: (Long, Long) -> Unit) {
        val url = "${base()}/download".toHttpUrl().newBuilder().addQueryParameter("path", path).addQueryParameter("name", name).build()
        http.newCall(builder(url.toString()).build()).execute().use { resp ->
            if (!resp.isSuccessful) check(resp)
            val body = resp.body ?: error("Boş yanıt")
            val total = body.contentLength()
            body.byteStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var got = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    onProgress(got, total)
                }
            }
        }
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

    data class RemoteApp(val pkg: String, val label: String, val system: Boolean, val size: Long)

    fun apps(system: Boolean): List<RemoteApp> {
        val url = "${base()}/apps".toHttpUrl().newBuilder().addQueryParameter("system", if (system) "1" else "0").build()
        http.newCall(builder(url.toString()).build()).execute().use { resp ->
            val arr = check(resp).getJSONArray("apps")
            return List(arr.length()) { i -> arr.getJSONObject(i).let { RemoteApp(it.optString("pkg"), it.optString("label"), it.optBoolean("system"), it.optLong("size")) } }
        }
    }

    fun appIcon(pkg: String): ByteArray? = runCatching {
        val url = "${base()}/appicon".toHttpUrl().newBuilder().addQueryParameter("pkg", pkg).build()
        http.newCall(builder(url.toString()).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
    }.getOrNull()

    fun appOpen(pkg: String) {
        val body = JSONObject().put("pkg", pkg).toString().toRequestBody(jsonType)
        http.newCall(builder("${base()}/appopen").post(body).build()).execute().use { check(it) }
    }

    fun appRemove(pkg: String) {
        val body = JSONObject().put("pkg", pkg).toString().toRequestBody(jsonType)
        http.newCall(builder("${base()}/appremove").post(body).build()).execute().use { check(it) }
    }

    /** TV'den ekranını bu cihaza (port) yansıtmasını ister. */
    fun mirrorRequest(port: Int, key: String, name: String) {
        val body = JSONObject().put("port", port).put("key", key).put("name", name).toString().toRequestBody(jsonType)
        http.newCall(builder("${base()}/mirror/request").post(body).build()).execute().use { check(it) }
    }

    val host: String? get() = store.tvHost
    val token: String? get() = store.tvToken

    fun link(url: String) {
        val body = JSONObject().put("url", url).toString().toRequestBody(jsonType)
        http.newCall(builder("${base()}/link").post(body).build()).execute().use { check(it) }
    }

    /** onProgress(gönderilen, toplam) arka plan iş parçacığında çağrılır. */
    /**
     * Dosyayı TV'ye gönderir. withDate: ayarlarda açıksa adın başına "2026-10-14 15.30 - " eklenir.
     * Boyutunu söylemeyen uygulamalardan (bazı müzik uygulamaları) gelen dosya önce geçici kopyalanır.
     */
    fun upload(ctx: Context, uri: Uri, folder: String, withDate: Boolean = true, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        var name = "dosya"
        var size = -1L
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        }
        if (name == "dosya") uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { name = it }
        if (size <= 0) size = runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
        var tmp: java.io.File? = null
        if (size <= 0) {
            tmp = java.io.File(ctx.cacheDir, "up_${System.nanoTime()}")
            (ctx.contentResolver.openInputStream(uri) ?: error("$name açılamadı")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            size = tmp.length()
            if (size <= 0) { tmp.delete(); error("$name okunamadı") }
        }
        val mime = ctx.contentResolver.getType(uri)
            ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        val finalName = if (withDate && store.datePrefix)
            java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale("tr")).format(java.util.Date()) + " - " + name
        else name

        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaTypeOrNull()
            override fun contentLength() = size
            override fun writeTo(sink: BufferedSink) {
                val input = tmp?.inputStream() ?: ctx.contentResolver.openInputStream(uri) ?: error("$name açılamadı")
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
            .header("X-Name", URLEncoder.encode(finalName, "UTF-8"))
            .post(body).build()
        try {
            http.newCall(req).execute().use { check(it) }
        } finally {
            tmp?.delete()
        }
    }
}
