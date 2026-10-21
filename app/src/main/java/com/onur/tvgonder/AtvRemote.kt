// v0.31 – 2026-10-21: Google TV / Android TV'nin kendi Wi‑Fi kumanda hizmeti (Google TV uygulamasıyla aynı yol): eşleşme kodu, tuşlar, link açma
package com.onur.tvgonder

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Calendar
import java.util.concurrent.Executors
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager
import javax.security.auth.x500.X500Principal

/**
 * TV'nin içindeki "Android TV Remote Service" ile konuşur (6466: kumanda, 6467: eşleşme).
 * TV Gönder'in TV'de kurulu olması gerekmez; tuşlar TV'nin kendi kumandası kadar hızlı gider.
 */
object AtvRemote {
    enum class State { Idle, Connecting, Connected, NeedPairing, Pairing, Error }

    val state = MutableStateFlow(State.Idle)
    val message = MutableStateFlow("")

    private const val ALIAS = "tvgonder_atv"
    private const val FEATURES = 1 or 2 or 32 or 64 or 512   // ping, tuş, güç, ses, link
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

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun ensureKey() {
        val ks = keyStore()
        if (ks.containsAlias(ALIAS)) return
        val start = Calendar.getInstance(); val end = Calendar.getInstance().apply { add(Calendar.YEAR, 20) }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA1, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
            .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1, KeyProperties.SIGNATURE_PADDING_RSA_PSS)
            .setCertificateSubject(X500Principal("CN=atvremote"))
            .setCertificateSerialNumber(BigInteger.valueOf(1000))
            .setCertificateNotBefore(start.time).setCertificateNotAfter(end.time)
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply { initialize(spec); generateKeyPair() }
    }

    private fun clientCert(): X509Certificate = keyStore().getCertificate(ALIAS) as X509Certificate

    private fun ssl(): SSLContext {
        ensureKey()
        val ks = keyStore()
        val km = object : X509ExtendedKeyManager() {
            override fun getClientAliases(t: String?, i: Array<out Principal>?) = arrayOf(ALIAS)
            override fun chooseClientAlias(t: Array<out String>?, i: Array<out Principal>?, s: Socket?) = ALIAS
            override fun getServerAliases(t: String?, i: Array<out Principal>?): Array<String>? = null
            override fun chooseServerAlias(t: String?, i: Array<out Principal>?, s: Socket?): String? = null
            override fun getCertificateChain(a: String?) = arrayOf(ks.getCertificate(ALIAS) as X509Certificate)
            override fun getPrivateKey(a: String?) = ks.getKey(ALIAS, null) as PrivateKey
        }
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(arrayOf(km), arrayOf(trustAll), null) }
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
                message.value = "Eşleşme başlatılamadı: ${e.message}. TV açık ve aynı Wi‑Fi'da mı?"
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
                message.value = "Eşleşme olmadı: ${e.message}"
            }
        }
    }

    fun isPaired(c: Context, h: String) = c.getSharedPreferences("atv", Context.MODE_PRIVATE).getBoolean("paired_$h", false)

    // ---------------------------------------------------------------- kumanda bağlantısı

    fun connect(c: Context, h: String) {
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
                message.value = if (state.value == State.NeedPairing) "Bu telefon TV ile bir kez eşleşmeli" else "TV'ye ulaşılamadı: ${e.message}"
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
