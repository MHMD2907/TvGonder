// v0.23 – 2026-10-19: ekran yansıtma alıcısını (8768) da başlatır; fare/kumanda (UDP); tablet alıcı modunda açılışta başlar
package com.onur.tvgonder

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.iki.elonen.NanoHTTPD

class ReceiverService : Service() {
    private var server: ReceiverServer? = null
    private var remote: RemoteListener? = null
    private var mirror: com.onur.tvgonder.mirror.MirrorServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        runCatching { ServiceCompat.startForeground(this, 1, notification(), type) }

        val store = Store(this)
        Hub.devices.value = store.devices()
        server = runCatching {
            ReceiverServer(applicationContext, store).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }
        }.getOrNull()
        Hub.running.value = server != null
        remote = RemoteListener(applicationContext, store).also { it.isDaemon = true; it.start() }
        mirror = com.onur.tvgonder.mirror.MirrorServer(applicationContext, store).also { it.isDaemon = true; it.start() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        server?.stop()
        remote?.close()
        mirror?.close()
        Hub.running.value = false
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel("recv", "Alıcı", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= 26) setChannelId("recv")
            setSmallIcon(android.R.drawable.stat_sys_download)
            setContentTitle("TV Gönder hazır")
        }.build()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (isTv(c) || Store(c).receiverMode) ContextCompat.startForegroundService(c, Intent(c, ReceiverService::class.java))
    }
}
