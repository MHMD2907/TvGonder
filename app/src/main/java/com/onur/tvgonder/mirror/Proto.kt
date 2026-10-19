// v0.23 – 2026-10-19: ekran yansıtma protokolü: tanışma (anahtar + ad), paketler (boyut, kodek ayarı, kare, ses), geri kanal
package com.onur.tvgonder.mirror

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer

/**
 * Bağlantı: gönderen önce writeUTF(anahtar), writeUTF(ad) yollar; alıcı 1 baytlık "1" ile onaylar.
 * Paket: [tür:1][uzunluk:4][zaman µs:8][veri]. Geri kanal (alıcı → gönderen): 1 = anahtar kare iste.
 */
object Proto {
    const val PORT = 8768
    const val SIZE = 1
    const val CONFIG = 2
    const val FRAME = 3
    const val KEY = 4
    const val AUDIO = 5
    const val REQ_KEY = 1

    const val RATE = 48_000

    fun sizeBytes(w: Int, h: Int): ByteArray = ByteBuffer.allocate(8).putInt(w).putInt(h).array()
}

class PacketWriter(out: OutputStream) {
    private val o = DataOutputStream(BufferedOutputStream(out, 1 shl 16))

    @Synchronized
    fun send(type: Int, pts: Long, data: ByteArray, len: Int = data.size) {
        o.writeByte(type)
        o.writeInt(len)
        o.writeLong(pts)
        o.write(data, 0, len)
        o.flush()
    }
}

/** Kabul edilmiş, yansıtılan görüntüyü taşıyan bağlantı (alıcı tarafı). */
class MirrorSession(val sock: Socket, val name: String) {
    val input = DataInputStream(sock.getInputStream().buffered(1 shl 16))
    @Volatile var closed = false
        private set

    fun requestKeyFrame() {
        runCatching { sock.getOutputStream().apply { write(Proto.REQ_KEY); flush() } }
    }

    fun close() {
        closed = true
        runCatching { sock.close() }
    }

    companion object {
        /** Gelen bağlantıda tanışmayı yapar; anahtar uygunsa oturum döner. */
        fun accept(sock: Socket, valid: (String) -> Boolean): MirrorSession? = runCatching {
            sock.tcpNoDelay = true
            sock.soTimeout = 8000
            val din = DataInputStream(sock.getInputStream())
            val key = din.readUTF()
            val name = din.readUTF()
            if (!valid(key)) { sock.close(); return null }
            sock.getOutputStream().apply { write(1); flush() }
            sock.soTimeout = 0
            MirrorSession(sock, name)
        }.getOrElse { runCatching { sock.close() }; null }
    }
}
