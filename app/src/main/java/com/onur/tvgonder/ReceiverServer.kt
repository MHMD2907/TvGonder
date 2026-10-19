// v0.23 – 2026-10-19: /apps (uygulama listesi, simge, aç, kaldır), /mirror/request (TV ekranını tablete yansıtma); /auth, indirme, kayıt yeri
package com.onur.tvgonder

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.documentfile.provider.DocumentFile
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * TV'deki yerel sunucu. Eşleşmemiş cihazların tüm istekleri reddedilir.
 * Uç noktalar: /ping, /pair, /folders, /mkdir, /upload, /link, /update
 */
class ReceiverServer(
    private val ctx: Context,
    private val store: Store,
) : NanoHTTPD(PORT) {

    companion object { const val PORT = 8765 }

    override fun serve(s: IHTTPSession): Response = try {
        route(s)
    } catch (e: Exception) {
        json(500, JSONObject().put("error", e.message ?: "Beklenmeyen hata"))
    }

    private fun route(s: IHTTPSession): Response {
        val path = s.uri
        if (path == "/ping") return json(
            200, JSONObject().put("app", "tvgonder").put("name", Build.MODEL).put("version", appVersionCode(ctx)).put("storage", store.treeUri != null).put("id", store.receiverId),
        )
        if (path == "/pair" && s.method == Method.POST) return pair(s)
        if (path == "/auth") {
            // Sadece TV'nin kendisinden (Fare eklentisi) sorulabilir
            val local = s.remoteIpAddress == "127.0.0.1" || s.remoteIpAddress == "::1"
            val ok = local && store.devices().containsKey(s.parms["token"] ?: "")
            return json(if (ok) 200 else 401, JSONObject().put("ok", ok))
        }

        val token = s.headers["x-token"]
        if (token == null || !store.devices().containsKey(token)) {
            return json(401, JSONObject().put("error", "Bu telefon eşleşmemiş"))
        }
        return when {
            path == "/folders" && s.method == Method.GET -> folders(s)
            path == "/mkdir" && s.method == Method.POST -> mkdir(s)
            path == "/upload" && s.method == Method.POST -> upload(s)
            path == "/link" && s.method == Method.POST -> link(s)
            path == "/update" && s.method == Method.POST -> update(s)
            path == "/files" && s.method == Method.GET -> files(s)
            path == "/download" && s.method == Method.GET -> download(s)
            path == "/apps" && s.method == Method.GET -> apps(s)
            path == "/appicon" && s.method == Method.GET -> {
                val png = Apps.iconPng(ctx, s.parms["pkg"] ?: "") ?: return json(404, JSONObject().put("error", "Simge yok"))
                newFixedLengthResponse(Response.Status.OK, "image/png", java.io.ByteArrayInputStream(png), png.size.toLong())
            }
            path == "/appopen" && s.method == Method.POST -> {
                val pkg = JSONObject(readBody(s)).optString("pkg")
                bringFront()
                val ok = Apps.open(ctx, pkg)
                json(if (ok) 200 else 404, JSONObject().put("ok", ok).put("error", "Uygulama açılamadı"))
            }
            path == "/appremove" && s.method == Method.POST -> {
                val pkg = JSONObject(readBody(s)).optString("pkg")
                val ok = Apps.remove(ctx, pkg)
                json(if (ok) 200 else 400, JSONObject().put("ok", ok).put("error", "Kaldırma penceresi açılamadı"))
            }
            path == "/mirror/request" && s.method == Method.POST -> mirrorRequest(s)
            else -> json(404, JSONObject().put("error", "Bulunamadı"))
        }
    }

    private fun apps(s: IHTTPSession): Response {
        val arr = JSONArray()
        Apps.list(ctx, s.parms["system"] == "1").forEach {
            arr.put(JSONObject().put("pkg", it.pkg).put("label", it.label).put("system", it.system).put("size", it.size))
        }
        return json(200, JSONObject().put("apps", arr))
    }

    /** Arka plandan açılış engellenmesin diye önce TV Gönder öne getirilir (izinliyse). */
    private fun bringFront() {
        if (Hub.foreground) return
        runCatching {
            ctx.startActivity(android.content.Intent(ctx, MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    /** Tablet "TV ekranını göster" dedi: TV'de izin penceresi açılır, izin verilince TV ekranı tablete akar. */
    private fun mirrorRequest(s: IHTTPSession): Response {
        val b = JSONObject(readBody(s))
        val port = b.optInt("port")
        val key = b.optString("key")
        if (port <= 0 || key.isBlank()) return json(400, JSONObject().put("error", "Eksik bilgi"))
        val host = s.remoteIpAddress
        runCatching {
            ctx.startActivity(
                android.content.Intent(ctx, com.onur.tvgonder.mirror.MirrorConsentActivity::class.java)
                    .putExtra("host", host).putExtra("port", port).putExtra("key", key)
                    .putExtra("name", b.optString("name", "Tablet"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
        return json(200, JSONObject().put("ok", true))
    }

    private fun pair(s: IHTTPSession): Response {
        val body = JSONObject(readBody(s))
        val code = body.optString("code")
        val name = body.optString("name", "Telefon").ifBlank { "Telefon" }
        val current = Hub.pairingCode.value
        if (current == null || code != current) {
            return json(403, JSONObject().put("error", "Kod geçersiz veya süresi dolmuş"))
        }
        val req = PairRequest(name, CompletableFuture())
        Hub.pending.value = req
        val ok = try { req.decision.get(60, TimeUnit.SECONDS) } catch (_: Exception) { false }
        Hub.pending.value = null
        if (!ok) return json(403, JSONObject().put("error", "TV bu eşleşmeyi onaylamadı"))

        val token = UUID.randomUUID().toString()
        store.addDevice(token, name)
        Hub.devices.value = store.devices()
        Hub.pairingCode.value = null
        return json(200, JSONObject().put("token", token).put("tvName", Build.MODEL).put("id", store.receiverId))
    }

    private val noStorage get() = json(409, JSONObject().put("error", "TV'de kayıt yeri seçilmemiş. TV'de \"USB belleği seç\"e bas."))

    private fun folders(s: IHTTPSession): Response {
        if (store.treeUri == null) return noStorage
        val dir = resolve(s.parms["path"] ?: "")
        if (dir == null || !dir.isDirectory) return json(404, JSONObject().put("error", "Klasör yok"))
        val arr = JSONArray()
        dir.listFiles().filter { it.isDirectory }
            .sortedBy { it.name?.lowercase() }
            .forEach { arr.put(it.name) }
        return json(200, JSONObject().put("folders", arr))
    }

    private fun mkdir(s: IHTTPSession): Response {
        val b = JSONObject(readBody(s))
        val dir = resolve(b.optString("path")) ?: return json(404, JSONObject().put("error", "Klasör yok"))
        val name = clean(b.optString("name"))
        if (name.isBlank()) return json(400, JSONObject().put("error", "Klasör adı boş"))
        if (dir.findFile(name) == null) dir.createDirectory(name)
        return json(200, JSONObject().put("ok", true))
    }

    private fun upload(s: IHTTPSession): Response {
        val folder = URLDecoder.decode(s.headers["x-folder"] ?: "", "UTF-8")
        val name = clean(URLDecoder.decode(s.headers["x-name"] ?: "dosya", "UTF-8")).ifBlank { "dosya" }
        val len = s.headers["content-length"]?.toLongOrNull()
            ?: return json(411, JSONObject().put("error", "Boyut bilinmiyor"))
        if (store.treeUri == null) return noStorage
        val dir = resolve(folder) ?: return json(404, JSONObject().put("error", "Klasör yok. USB bellek takılı mı?"))
        val mime = s.headers["content-type"] ?: "application/octet-stream"
        // Düz klasörde (USB/TV hafızası) doğrudan dosya; Android seçicisiyle seçilmişse DocumentFile
        val raw = dir.uri.scheme == "file"
        val target: java.io.File? = if (raw) uniqueFile(java.io.File(dir.uri.path!!), name) else null
        val file = if (raw) DocumentFile.fromFile(target!!) else dir.createFile(mime, name)
            ?: return json(500, JSONObject().put("error", "Dosya oluşturulamadı. USB bağlı mı?"))

        var left = len
        val buf = ByteArray(64 * 1024)
        (if (raw) target!!.outputStream() else ctx.contentResolver.openOutputStream(file.uri))?.use { out ->
            while (left > 0) {
                val n = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        } ?: return json(500, JSONObject().put("error", "Yazma açılamadı"))

        if (left > 0) {
            file.delete()
            return json(400, JSONObject().put("error", "Gönderim yarım kaldı"))
        }
        val rec = Received(file.name ?: name, file.uri.toString(), mime, System.currentTimeMillis())
        store.addReceived(rec)
        Hub.received.value = rec
        return json(200, JSONObject().put("saved", file.name ?: name))
    }

    private fun link(s: IHTTPSession): Response {
        val url = JSONObject(readBody(s)).optString("url").trim()
        val u = Uri.parse(url)
        if (u.scheme != "http" && u.scheme != "https") {
            return json(400, JSONObject().put("error", "Sadece http/https linkleri açılır"))
        }
        LinkOpener.open(ctx, url)
        return json(200, JSONObject().put("ok", true))
    }

    private fun update(s: IHTTPSession): Response {
        val len = s.headers["content-length"]?.toLongOrNull()
            ?: return json(411, JSONObject().put("error", "Boyut bilinmiyor"))
        if (len > 300L * 1024 * 1024) return json(400, JSONObject().put("error", "Dosya çok büyük"))
        val dir = java.io.File(ctx.cacheDir, "update").apply { mkdirs() }
        val f = java.io.File(dir, "tvgonder.apk")
        var left = len
        val buf = ByteArray(64 * 1024)
        f.outputStream().use { out ->
            while (left > 0) {
                val n = s.inputStream.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
        if (left > 0) {
            f.delete()
            return json(400, JSONObject().put("error", "Gönderim yarım kaldı"))
        }
        Hub.updateReady.value = f
        return json(200, JSONObject().put("ok", true))
    }

    // ---- yardımcılar ----
    /** Klasördeki dosya ve klasörler (telefondan gezip indirmek için). */
    private fun files(s: IHTTPSession): Response {
        if (store.treeUri == null) return noStorage
        val dir = resolve(s.parms["path"] ?: "")
        if (dir == null || !dir.isDirectory) return json(404, JSONObject().put("error", "Klasör yok"))
        val arr = JSONArray()
        dir.listFiles().filter { !(it.name ?: ".").startsWith(".") }
            .sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenByDescending { it.lastModified() })
            .forEach { f ->
                arr.put(
                    JSONObject().put("name", f.name).put("dir", f.isDirectory).put("size", f.length())
                        .put("mime", f.type ?: "").put("mod", f.lastModified())
                )
            }
        return json(200, JSONObject().put("items", arr))
    }

    private fun download(s: IHTTPSession): Response {
        if (store.treeUri == null) return noStorage
        val dir = resolve(s.parms["path"] ?: "") ?: return json(404, JSONObject().put("error", "Klasör yok"))
        val f = dir.findFile(s.parms["name"] ?: "") ?: return json(404, JSONObject().put("error", "Dosya yok"))
        if (f.isDirectory) return json(400, JSONObject().put("error", "Klasör indirilemez"))
        val input = (if (f.uri.scheme == "file") java.io.File(f.uri.path!!).inputStream() else ctx.contentResolver.openInputStream(f.uri))
            ?: return json(500, JSONObject().put("error", "Dosya açılamadı"))
        return newFixedLengthResponse(Response.Status.OK, f.type ?: "application/octet-stream", input, f.length())
    }

    /** Aynı adda dosya varsa "ad (2).uzantı" yapar. */
    private fun uniqueFile(dir: java.io.File, name: String): java.io.File {
        dir.mkdirs()
        var f = java.io.File(dir, name)
        val base = name.substringBeforeLast('.', name)
        val ext = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var n = 2
        while (f.exists()) f = java.io.File(dir, "$base ($n)$ext").also { n++ }
        return f
    }

    private fun root(): DocumentFile? = rootDoc()

    private fun rootDoc(): DocumentFile? {
        val t = store.treeUri ?: return null
        val u = Uri.parse(t)
        return if (u.scheme == "file") java.io.File(u.path!!).apply { mkdirs() }.let { DocumentFile.fromFile(it) }
        else DocumentFile.fromTreeUri(ctx, u)
    }


    private fun resolve(rel: String): DocumentFile? {
        var cur = root() ?: return null
        for (part in rel.split("/").filter { it.isNotBlank() && it != "." && it != ".." }) {
            cur = cur.findFile(part) ?: return null
        }
        return cur
    }

    private fun clean(n: String) = n.replace('/', '_').replace('\\', '_').trim()

    private fun readBody(s: IHTTPSession): String {
        val len = s.headers["content-length"]?.toIntOrNull() ?: 0
        if (len <= 0 || len > 65536) return "{}"
        val buf = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = s.inputStream.read(buf, read, len - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun status(c: Int) = when (c) {
        200 -> Response.Status.OK
        400 -> Response.Status.BAD_REQUEST
        401 -> Response.Status.UNAUTHORIZED
        403 -> Response.Status.FORBIDDEN
        404 -> Response.Status.NOT_FOUND
        411 -> Response.Status.LENGTH_REQUIRED
        else -> Response.Status.INTERNAL_ERROR
    }

    private fun json(code: Int, o: JSONObject): Response =
        newFixedLengthResponse(status(code), "application/json; charset=utf-8", o.toString())
}
