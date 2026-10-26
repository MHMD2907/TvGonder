// v0.37 – 2026-10-26: Google TV kumanda hizmeti: telefondan yazı yazma (TV'nin yazı alanına canlı), eşleşme, tuşlar, link açma
package com.onur.tvgonder

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * TV'nin içindeki "Android TV Remote Service" ile konuşur (6466: kumanda, 6467: eşleşme).
 * TV Gönder'in TV'de kurulu olması gerekmez; tuşlar TV'nin kendi kumandası kadar hızlı gider.
 */
object AtvRemote {
    enum class State { Idle, Connecting, Connected, NeedPairing, Pairing, Error }

    val state = MutableStateFlow(State.Idle)
    val message = MutableStateFlow("")

    private const val ALIAS = "tvgonder_atv"
    private const val FEATURES = 1 or 2 or 4 or 32 or 64 or 512   // ping, tuş, yazı (IME), güç, ses, link

    /** TV'de bir yazı alanı açıldığında true (telefonda klavye kendiliğinden açılır). */
    val imeActive = MutableStateFlow(false)
    /** TV'deki yazı alanının içeriği (TV bildirdikçe). */
    val imeText = MutableStateFlow("")
    @Volatile private var imeCounter = 0L
    @Volatile private var fieldCounter = 0L
    private val io = Executors.newSingleThreadExecutor()
    private val sendQ = Executors.newSingleThreadExecutor()

    @Volatile private var sock: SSLSocket? = null
    @Volatile private var host: String? = null
    @Volatile private var pairSock: SSLSocket? = null

    // ---------------------------------------------------------------- küçük protobuf yardımcıları

    private class PB {
        val o = ByteArrayOutputStream()
        fun vi(v: Long) { var x = v; while (true) { if (x and 0x7FL.inv() == 0L) { o.write(x.toInt()); return }; o.write(((x and 0x7F) or 0x80).toInt()); x = x ushr 7 } }
        fun int(f: Int, v: Long) = apply { vi((f.toLong() shl 3)); vi(v) }
        fun bytes(f: Int, b: ByteArray) = apply { vi((f.toLong() shl 3) or 2); vi(b.size.toLong()); o.write(b) }
        fun str(f: Int, s: String) = bytes(f, s.toByteArray())
        fun msg(f: Int, m: PB) = bytes(f, m.o.toByteArray())
        fun out(): ByteArray = o.toByteArray()
    }

    /** Alan numarası → değerler (sayı ya da bayt dizisi). */
    private fun parse(b: ByteArray): Map<Int, List<Any>> {
        val m = HashMap<Int, MutableList<Any>>()
        var i = 0
        fun vi(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl s); if (c and 0x80 == 0) return r; s += 7 } }
        while (i < b.size) {
            val tag = vi(); val f = (tag ushr 3).toInt()
            when ((tag and 7).toInt()) {
                0 -> m.getOrPut(f) { mutableListOf() }.add(vi())
                2 -> { val n = vi().toInt(); m.getOrPut(f) { mutableListOf() }.add(b.copyOfRange(i, i + n)); i += n }
                5 -> i += 4
                1 -> i += 8
                else -> return m
            }
        }
        return m
    }

    private fun write(o: OutputStream, body: ByteArray) {
        val p = PB(); p.vi(body.size.toLong())
        synchronized(o) { o.write(p.out() + body); o.flush() }
    }

    private fun read(inp: InputStream): ByteArray {
        var len = 0L; var s = 0
        while (true) {
            val c = inp.read(); if (c < 0) throw java.io.EOFException()
            len = len or ((c and 0x7F).toLong() shl s); if (c and 0x80 == 0) break; s += 7
        }
        val b = ByteArray(len.toInt()); var r = 0
        while (r < b.size) { val n = inp.read(b, r, b.size - r); if (n < 0) throw java.io.EOFException(); r += n }
        return b
    }

    // ---------------------------------------------------------------- istemci sertifikası (telefonun kimliği)

    // Not: Telefonun güvenli anahtar kasası (AndroidKeyStore) bazı telefonlarda TLS imzasında
    // "RSA internal error" veriyor. Bu yüzden anahtar uygulamanın kendi içinde (yazılımla) üretilip saklanır.
    @Volatile private var filesDir: java.io.File? = null
    @Volatile private var identity: Pair<PrivateKey, X509Certificate>? = null

    private fun der(tag: Int, body: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); o.write(tag)
        val n = body.size
        when {
            n < 0x80 -> o.write(n)
            n < 0x100 -> { o.write(0x81); o.write(n) }
            n < 0x10000 -> { o.write(0x82); o.write(n shr 8); o.write(n and 0xFF) }
            else -> { o.write(0x83); o.write(n shr 16); o.write((n shr 8) and 0xFF); o.write(n and 0xFF) }
        }
        o.write(body); return o.toByteArray()
    }
    private fun seq(vararg p: ByteArray) = der(0x30, p.fold(ByteArray(0)) { a, b -> a + b })
    private fun oid(vararg parts: Int): ByteArray {
        val o = ByteArrayOutputStream(); o.write(parts[0] * 40 + parts[1])
        for (v in parts.drop(2)) {
            val stack = ArrayList<Int>(); var x = v; stack.add(x and 0x7F); x = x ushr 7
            while (x > 0) { stack.add((x and 0x7F) or 0x80); x = x ushr 7 }
            stack.reversed().forEach { o.write(it) }
        }
        return der(0x06, o.toByteArray())
    }

    /** Kendinden imzalı X.509 (v3) sertifika: CN=atvremote, SHA256withRSA. */
    private fun selfSigned(kp: java.security.KeyPair): X509Certificate {
        val sha256rsa = seq(oid(1, 2, 840, 113549, 1, 1, 11), byteArrayOf(0x05, 0x00))
        val name = seq(der(0x31, seq(oid(2, 5, 4, 3), der(0x0C, "atvremote".toByteArray()))))
        val fmt = java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val now = System.currentTimeMillis()
        val validity = seq(der(0x17, fmt.format(java.util.Date(now - 86_400_000L)).toByteArray()), der(0x17, "491231235959Z".toByteArray()))
        val tbs = seq(
            der(0xA0, der(0x02, byteArrayOf(2))),          // sürüm v3
            der(0x02, BigInteger.valueOf(now).toByteArray()),
            sha256rsa, name, validity, name,
            kp.public.encoded,                               // SubjectPublicKeyInfo
        )
        val sig = java.security.Signature.getInstance("SHA256withRSA").apply { initSign(kp.private); update(tbs) }.sign()
        val cert = seq(tbs, sha256rsa, der(0x03, byteArrayOf(0) + sig))
        return java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(cert.inputStream()) as X509Certificate
    }

    private fun ensureKey(): Pair<PrivateKey, X509Certificate> {
        identity?.let { return it }
        val dir = java.io.File(filesDir ?: error("hazır değil"), "atv").apply { mkdirs() }
        val kf = java.io.File(dir, "key.pk8"); val cf = java.io.File(dir, "cert.der")
        if (kf.exists() && cf.exists()) runCatching {
            val key = java.security.KeyFactory.getInstance("RSA").generatePrivate(java.security.spec.PKCS8EncodedKeySpec(kf.readBytes()))
            val cert = java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(cf.inputStream()) as X509Certificate
            return (key to cert).also { identity = it }
        }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4)) }.generateKeyPair()
        val cert = selfSigned(kp)
        kf.writeBytes(kp.private.encoded); cf.writeBytes(cert.encoded)
        return (kp.private to cert).also { identity = it }
    }

    private fun clientCert(): X509Certificate = ensureKey().second

    private fun ssl(): SSLContext {
        val (key, cert) = ensureKey()
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = ALIAS
            override fun getServerAliases(t: String?, i: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?): String? = null
            override fun getCertificateChain(a: String?) = arrayOf(cert)
            override fun getPrivateKey(a: String?) = key
        }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf(trustAll), null) }
    }

    /** Teknik hata metinlerini anlaşılır hale getirir. */
    private fun friendly(e: Exception): String = when {
        e is java.net.ConnectException || e is java.net.NoRouteToHostException -> "TV'ye ulaşılamadı. TV açık ve telefonla aynı Wi‑Fi'da mı?"
        e is java.net.SocketTimeoutException -> "TV cevap vermedi. TV açık mı?"
        e is javax.net.ssl.SSLException -> "TV ile güvenli bağlantı kurulamadı. TV'yi yeniden başlatıp tekrar dene."
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun open(h: String, port: Int): SSLSocket {
        val raw = Socket().apply { connect(InetSocketAddress(h, port), 4000); tcpNoDelay = true }
        val s = ssl().socketFactory.createSocket(raw, h, port, true) as SSLSocket
        s.soTimeout = 15_000
        s.startHandshake()
        return s
    }

    // ---------------------------------------------------------------- eşleşme

    private fun outer() = PB().int(1, 2).int(2, 200)

    /** TV'de 6 haneli kodu gösterir. */
    fun startPairing(c: Context, h: String) {
        filesDir = c.filesDir
        host = h
        state.value = State.Pairing
        message.value = "TV'ye bağlanılıyor…"
        io.execute {
            try {
                runCatching { pairSock?.close() }
                val s = open(h, 6467); pairSock = s
                val o = s.outputStream; val inp = s.inputStream
                write(o, outer().msg(10, PB().str(1, "atvremote").str(2, "TV Gönder (${Build.MODEL})")).out())
                while (true) {
                    val m = parse(read(inp))
                    if ((m[2]?.firstOrNull() as? Long) != 200L) error("TV eşleşmeyi reddetti")
                    when {
                        m.containsKey(11) -> write(o, outer().msg(20, PB().msg(1, PB().int(1, 3).int(2, 6)).int(3, 1)).out())
                        m.containsKey(20) -> write(o, outer().msg(30, PB().msg(1, PB().int(1, 3).int(2, 6)).int(2, 1)).out())
                        m.containsKey(31) -> { message.value = "TV ekranındaki 6 haneli kodu yaz"; return@execute }
                    }
                }
            } catch (e: Exception) {
                state.value = State.Error
                message.value = "Eşleşme başlatılamadı: ${friendly(e)}"
            }
        }
    }

    private fun hexBytes(hex: String): ByteArray = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Python'daki bytes.fromhex(f"{n:X}") ile aynı: baştaki sıfırlar olmadan. */
    private fun big(n: BigInteger, prefix0: Boolean): ByteArray {
        var h = n.toString(16).uppercase()
        if (prefix0) h = "0$h"
        if (h.length % 2 == 1) h = "0$h"
        return hexBytes(h)
    }

    fun finishPairing(c: Context, code: String) {
        val s = pairSock ?: run { message.value = "Önce \"Eşleşmeyi başlat\"a bas"; return }
        val pin = code.trim().uppercase()
        if (pin.length != 6 || pin.any { it !in "0123456789ABCDEF" }) { message.value = "Kod 6 karakter olmalı (0-9, A-F)"; return }
        io.execute {
            try {
                val cc = clientCert().publicKey as RSAPublicKey
                val sc = (s.session.peerCertificates[0] as X509Certificate).publicKey as RSAPublicKey
                val md = MessageDigest.getInstance("SHA-256")
                md.update(big(cc.modulus, false)); md.update(big(cc.publicExponent, true))
                md.update(big(sc.modulus, false)); md.update(big(sc.publicExponent, true))
                md.update(hexBytes(pin.substring(2)))
                val hash = md.digest()
                if ((hash[0].toInt() and 0xFF) != pin.substring(0, 2).toInt(16)) {
                    message.value = "Kod yanlış, TV'deki kodu tekrar kontrol et"
                    return@execute
                }
                write(s.outputStream, outer().msg(40, PB().bytes(1, hash)).out())
                val m = parse(read(s.inputStream))
                if ((m[2]?.firstOrNull() as? Long) != 200L || !m.containsKey(41)) error("TV kodu kabul etmedi")
                runCatching { s.close() }; pairSock = null
                c.getSharedPreferences("atv", Context.MODE_PRIVATE).edit().putBoolean("paired_${host}", true).putString("host", host).apply()
                message.value = "Eşleşti ✓"
                connect(c, host!!)
            } catch (e: Exception) {
                state.value = State.Error
                message.value = "Eşleşme olmadı: ${friendly(e)}"
            }
        }
    }

    fun isPaired(c: Context, h: String) = c.getSharedPreferences("atv", Context.MODE_PRIVATE).getBoolean("paired_$h", false)

    // ---------------------------------------------------------------- kumanda bağlantısı

    fun connect(c: Context, h: String) {
        filesDir = c.filesDir
        if (state.value == State.Connected && host == h && sock?.isClosed == false) return
        host = h
        state.value = State.Connecting
        message.value = "Bağlanıyor…"
        io.execute {
            runCatching { sock?.close() }
            val s = try { open(h, 6466) } catch (e: Exception) {
                // Eşleşmemiş telefonu TV bağlantı sırasında reddeder
                val pairedBefore = isPaired(c, h)
                state.value = if (e is javax.net.ssl.SSLException || !pairedBefore) State.NeedPairing else State.Error
                message.value = if (state.value == State.NeedPairing) "Bu telefon TV ile bir kez eşleşmeli" else friendly(e)
                if (state.value == State.NeedPairing) c.getSharedPreferences("atv", Context.MODE_PRIVATE).edit().remove("paired_$h").apply()
                return@execute
            }
            sock = s
            s.soTimeout = 0
            Thread({ loop(c, s) }, "atv-remote").apply { isDaemon = true; start() }
        }
    }

    private fun loop(c: Context, s: SSLSocket) {
        val o = s.outputStream
        try {
            while (!s.isClosed) {
                val m = parse(read(s.inputStream))
                when {
                    m.containsKey(1) -> write(o, PB().msg(1, PB().int(1, FEATURES.toLong()).msg(2,
                        PB().str(1, Build.MODEL).str(2, Build.MANUFACTURER).int(3, 1).str(4, "1").str(5, "atvremote").str(6, "1.0.0"))).out())
                    m.containsKey(2) -> write(o, PB().msg(2, PB().int(1, FEATURES.toLong())).out())
                    m.containsKey(8) -> {
                        val p = parse(m[8]!!.first() as ByteArray)
                        write(o, PB().msg(9, PB().int(1, (p[1]?.firstOrNull() as? Long) ?: 0L)).out())
                    }
                    // TV'de yazı alanı odaklandı / içeriği değişti
                    m.containsKey(21) -> runCatching {
                        val be = parse(m[21]!!.first() as ByteArray)
                        imeCounter = (be[1]?.firstOrNull() as? Long) ?: imeCounter
                        fieldCounter = (be[2]?.firstOrNull() as? Long) ?: fieldCounter
                        (be[3]?.firstOrNull() as? ByteArray)?.let { ei ->
                            (parse(ei)[2]?.firstOrNull() as? ByteArray)?.let { obj ->
                                (parse(obj)[3]?.firstOrNull() as? ByteArray)?.let { imeText.value = String(it) }
                            }
                        }
                        imeActive.value = true
                    }
                    m.containsKey(22) -> runCatching {
                        val req = parse(m[22]!!.first() as ByteArray)
                        (req[2]?.firstOrNull() as? ByteArray)?.let { st ->
                            val f = parse(st)
                            (f[1]?.firstOrNull() as? Long)?.let { fieldCounter = it }
                            (f[2]?.firstOrNull() as? ByteArray)?.let { imeText.value = String(it) }
                        }
                        imeActive.value = true
                    }
                    m.containsKey(40) -> {
                        c.getSharedPreferences("atv", Context.MODE_PRIVATE).edit().putBoolean("paired_${host}", true).putString("host", host).apply()
                        state.value = State.Connected; message.value = "Bağlı ✓"
                    }
                }
                if (state.value == State.Connecting && (m.containsKey(2) || m.containsKey(8))) { state.value = State.Connected; message.value = "Bağlı ✓" }
            }
        } catch (_: Exception) { }
        if (sock === s) {
            sock = null
            if (state.value == State.Connected || state.value == State.Connecting) { state.value = State.Error; message.value = "Bağlantı koptu – dokununca yeniden bağlanır" }
        }
    }

    private fun send(body: ByteArray) {
        val s = sock ?: return
        sendQ.execute { runCatching { write(s.outputStream, body) }.onFailure { runCatching { s.close() } } }
    }

    /** Android tuş kodu (KeyEvent.KEYCODE_*). dir: 3 kısa, 1 basılı tutma başı, 2 basılı tutma sonu. */
    fun key(code: Int, dir: Int = 3) = send(PB().msg(10, PB().int(1, code.toLong()).int(2, dir.toLong())).out())

    /**
     * TV'deki odaklı yazı alanının içeriğini [t] yapar (YouTube araması, tarayıcı adres çubuğu, giriş ekranları…).
     * Telefonda yazdıkça TV'de canlı görünür.
     */
    fun text(t: String) {
        if (t.isEmpty()) return
        val n = (t.length - 1).toLong()
        send(PB().msg(21, PB().int(1, imeCounter).int(2, fieldCounter)
            .msg(3, PB().int(1, 1).msg(2, PB().int(1, n).int(2, n).str(3, t)))).out())
    }

    /** TV'de link açar (YouTube linki YouTube uygulamasında, açıkken bile). */
    fun launch(url: String) = send(PB().msg(90, PB().str(1, url)).out())

    /** Bağlı değilse bağlanıp linki açar; arka planda çağrılır. true: gönderildi. */
    fun launchBlocking(c: Context, h: String, url: String): Boolean {
        if (!isPaired(c, h)) return false
        if (!(state.value == State.Connected && host == h && sock?.isClosed == false)) {
            connect(c, h)
            val until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until && state.value != State.Connected) Thread.sleep(100)
            if (state.value != State.Connected) return false
        }
        val s = sock ?: return false
        return runCatching { write(s.outputStream, PB().msg(90, PB().str(1, url)).out()) }.isSuccess
    }

    fun disconnect() { runCatching { sock?.close() }; sock = null; state.value = State.Idle }
}
