// v0.23 – 2026-10-19: TV/tablet (alıcı): telefondan gelen ekran yansıtmasını 8768'de bekler, ekranı öne getirir
package com.onur.tvgonder.mirror

import android.content.Context
import android.content.Intent
import com.onur.tvgonder.Hub
import com.onur.tvgonder.MainActivity
import com.onur.tvgonder.Store
import java.net.InetSocketAddress
import java.net.ServerSocket

class MirrorServer(private val ctx: Context, private val store: Store) : Thread("tvgonder-mirror") {
    @Volatile private var server: ServerSocket? = null

    override fun run() {
        val ss = runCatching {
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.PORT)) }
        }.getOrNull() ?: return
        server = ss
        while (!ss.isClosed) {
            val sock = runCatching { ss.accept() }.getOrNull() ?: break
            Thread {
                val s = MirrorSession.accept(sock) { store.devices().containsKey(it) } ?: return@Thread
                Hub.mirrorIn.value?.close()
                Hub.mirrorIn.value = s
                if (!Hub.foreground) runCatching {
                    ctx.startActivity(
                        Intent(ctx, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }.apply { isDaemon = true }.start()
        }
    }

    fun close() { runCatching { server?.close() } }
}
