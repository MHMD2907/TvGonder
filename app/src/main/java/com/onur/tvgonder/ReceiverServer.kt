// v0.10 – 2026-10-13: kayıt yeri doğrudan klasör (USB/TV hafızası) olabilir; /ping kayıt yeri durumunu bildirir
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
            200, JSONObject().put("app", "tvgonder").put("name", Build.MODEL).put("version", appVersionCode(ctx)).put("storage", store.treeUri != null),
        )
        if (path == "/pair" && s.method == Method.POST) return pair(s)

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
            else -> json(404, JSONObject().put("error", "Bulunamadı"))
        }
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
        return json(200, JSONObject().put("token", token).put("tvName", Build.MODEL))
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
