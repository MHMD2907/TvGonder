// v0.29 – 2026-10-20: ekranı yansıtır; ses yalnız TV'den (telefon kendiliğinden kısılır, gerekirse TV sesi yükseltilir); dönünce yataya geçer
package com.onur.tvgonder.mirror

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Surface
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.onur.tvgonder.Hub
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.roundToInt

class MirrorService : Service() {

    companion object {
        private const val STOP = "tvgonder.mirror.STOP"

        fun start(c: Context, code: Int, data: Intent, host: String, port: Int, key: String, name: String) {
            val i = Intent(c, MirrorService::class.java)
                .putExtra("code", code).putExtra("data", data)
                .putExtra("host", host).putExtra("port", port).putExtra("key", key).putExtra("name", name)
            ContextCompat.startForegroundService(c, i)
        }

        fun stop(c: Context) {
            runCatching { c.startService(Intent(c, MirrorService::class.java).setAction(STOP)) }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var sock: Socket? = null
    private var writer: PacketWriter? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var gen = 0
    @Volatile private var running = false
    private var drain: Thread? = null
    private var audio: AudioRecord? = null
    private var vw = 0
    private var vh = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopSelf(); return START_NOT_STICKY }
        if (running || intent == null) return START_NOT_STICKY
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        val ok = runCatching { ServiceCompat.startForeground(this, 7, notification(), type) }.isSuccess
        if (!ok) { stopSelf(); return START_NOT_STICKY }

        val code = intent.getIntExtra("code", 0)
        @Suppress("DEPRECATION")
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra("data", Intent::class.java) else intent.getParcelableExtra("data")
        val host = intent.getStringExtra("host") ?: ""
        val port = intent.getIntExtra("port", Proto.PORT)
        val key = intent.getStringExtra("key") ?: ""
        val name = intent.getStringExtra("name") ?: Build.MODEL
        if (data == null) { stopSelf(); return START_NOT_STICKY }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = runCatching { mpm.getMediaProjection(code, data) }.getOrNull()
        if (p == null) { fail("Ekran kaydı izni alınamadı"); return START_NOT_STICKY }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { main.post { stopSelf() } }
        }, main)
        running = true
        Hub.mirrorOut.value = true
        worker.execute { connectAndRun(host, port, key, name) }
        return START_NOT_STICKY
    }

    private fun fail(msg: String) {
        main.post {
            Toast.makeText(this, "Yansıtma: $msg", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    private fun connectAndRun(host: String, port: Int, key: String, name: String) {
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.sendBufferSize = 1 shl 20
            s.connect(InetSocketAddress(host, port), 6000)
            sock = s
            val o = java.io.DataOutputStream(s.getOutputStream())
            o.writeUTF(key); o.writeUTF(name); o.flush()
            s.soTimeout = 8000
            if (s.getInputStream().read() != 1) { fail("Karşı cihaz kabul etmedi (eşleşme?)"); return }
            s.soTimeout = 0
            writer = PacketWriter(s.getOutputStream())
            // Geri kanal: alıcı anahtar kare isterse ver; bağlantı koparsa dur
            Thread {
                runCatching {
                    val inp = s.getInputStream()
                    while (running) {
                        val b = inp.read()
                        if (b < 0) break
                        if (b == Proto.REQ_KEY) requestKey()
                    }
                }
                if (running) main.post { stopSelf() }
            }.apply { isDaemon = true }.start()

            val (w, h, dpi) = dims()
            val surface = startEncoder(w, h)
            display = projection!!.createVirtualDisplay(
                "TvGonder", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null,
            )
            main.post { runCatching { getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, main) } }
            startAudio()
            checkRotation()
        } catch (e: Exception) {
            fail(if (e is java.net.ConnectException || e is java.net.SocketTimeoutException) "Cihaza bağlanılamadı (aynı Wi‑Fi'da mı, TV Gönder güncel mi?)" else (e.message ?: "hata"))
        }
    }

    /** Ekranın gerçek boyutu, uzun kenar en çok 1280 olacak şekilde; kodlayıcı için 16'nın katı. */
    private fun dims(): Triple<Int, Int, Int> {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val long = max(dm.widthPixels, dm.heightPixels).toFloat()
        val s = if (long > 1280f) 1280f / long else 1f
        fun al(v: Float) = max(16, (v / 16f).roundToInt() * 16)
        return Triple(al(dm.widthPixels * s), al(dm.heightPixels * s), dm.densityDpi)
    }

    private fun format(w: Int, h: Int, baseline: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
        setInteger(MediaFormat.KEY_BIT_RATE, 5_000_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, 30)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
        setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
        if (baseline) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
    }

    private fun startEncoder(w: Int, h: Int): Surface {
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try { c.configure(format(w, h, true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        catch (_: Exception) { c.reset(); c.configure(format(w, h, false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        val surface = c.createInputSurface()
        c.start()
        vw = w; vh = h
        writer?.send(Proto.SIZE, 0, Proto.sizeBytes(w, h))
        codec = c
        val myGen = ++gen
        drain = Thread {
            val info = MediaCodec.BufferInfo()
            var buf = ByteArray(1 shl 17)
            try {
                while (running && gen == myGen) {
                    val idx = c.dequeueOutputBuffer(info, 100_000)
                    if (idx < 0) continue
                    val ob = c.getOutputBuffer(idx)
                    if (ob != null && info.size > 0) {
                        if (buf.size < info.size) buf = ByteArray(info.size + 4096)
                        ob.position(info.offset); ob.get(buf, 0, info.size)
                        val type = when {
                            info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0 -> Proto.CONFIG
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0 -> Proto.KEY
                            else -> Proto.FRAME
                        }
                        writer?.send(type, info.presentationTimeUs, buf, info.size)
                    }
                    c.releaseOutputBuffer(idx, false)
                }
            } catch (_: Exception) {
                if (running && gen == myGen) main.post { stopSelf() }
            }
        }.apply { isDaemon = true; start() }
        return surface
    }

    private fun requestKey() {
        runCatching { codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    /** Telefon dönünce (dikey ↔ yatay) görüntü boyutu yenilenir; karşı taraf kendini ayarlar. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        checkRotation()
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) {}
        override fun onDisplayRemoved(id: Int) {}
        override fun onDisplayChanged(id: Int) { if (id == android.view.Display.DEFAULT_DISPLAY) checkRotation() }
    }

    private fun checkRotation() {
        if (!running) return
        worker.execute {
            runCatching {
                val (w, h, dpi) = dims()
                if (w == vw && h == vh || display == null) return@runCatching
                // Eski kodlayıcıyı tamamen kapat, sonra yenisini aç (bazı telefonlar aynı anda iki kodlayıcıya izin vermez)
                val old = codec
                gen++
                display?.surface = null
                drain?.join(500)
                runCatching { old?.stop() }; runCatching { old?.release() }
                codec = null
                display?.resize(w, h, dpi)
                display?.surface = startEncoder(w, h)
            }.onFailure { main.post { Toast.makeText(this, "Yansıtma: dönüş ayarlanamadı", Toast.LENGTH_SHORT).show() } }
        }
    }

    /** Telefonda çalan ses (Android 10+, izin verilmişse). Korumalı uygulamaların sesi gelmez. */
    @SuppressLint("MissingPermission")
    private fun startAudio() {
        if (Build.VERSION.SDK_INT < 29) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val p = projection ?: return
        val rec = runCatching {
            val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(Proto.RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
            val min = AudioRecord.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord.Builder().setAudioFormat(fmt).setBufferSizeInBytes(max(min, 3840) * 4)
                .setAudioPlaybackCaptureConfig(cfg).build()
        }.getOrNull() ?: return
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        audio = rec
        Thread {
            val b = ByteArray(3840)   // 20 ms
            val music = android.media.AudioManager.STREAM_MUSIC
            // Ses yalnız TV'den gelsin: ses ilk duyulunca telefon kısılır. Bazı telefonlarda kısınca yakalanan ses de
            // susar; o zaman telefon çok kısık bırakılır ve TV'ye giden ses aynı oranda yükseltilir.
            var phase = 0
            var mutedAt = 0L
            runCatching {
                rec.startRecording()
                while (running) {
                    val n = rec.read(b, 0, b.size)
                    if (n <= 0) continue
                    val pk = peak(b, n)
                    val now = android.os.SystemClock.elapsedRealtime()
                    when (phase) {
                        0 -> if (pk > 800) runCatching {
                            savedVol = am.getStreamVolume(music)
                            am.setStreamVolume(music, 0, 0)
                            mutedAt = now; phase = 1
                        }.onFailure { phase = 2 }
                        1 -> if (pk > 150) phase = 2
                        else if (now - mutedAt > 1500) {
                            runCatching {
                                val max = am.getStreamMaxVolume(music)
                                val idx = maxOf(1, Math.round(max * 0.2f)).coerceAtMost(maxOf(1, savedVol))
                                am.setStreamVolume(music, idx, 0)
                                gain = if (Build.VERSION.SDK_INT >= 28) {
                                    val db = am.getStreamVolumeDb(music, idx, android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
                                    if (db.isFinite() && db < 0) Math.pow(10.0, -db / 20.0).toFloat() else 4f
                                } else (max.toFloat() / idx).coerceAtMost(8f)
                                gain = gain.coerceIn(1f, 40f)
                            }
                            phase = 2
                        }
                    }
                    if (gain > 1.01f) amplify(b, n, gain)
                    writer?.send(Proto.AUDIO, 0, b, n)
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private val am by lazy { getSystemService(AUDIO_SERVICE) as android.media.AudioManager }
    @Volatile private var savedVol = -1
    @Volatile private var gain = 1f

    private fun peak(b: ByteArray, n: Int): Int {
        var m = 0
        var i = 0
        while (i + 1 < n) {
            val v = (b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)
            val a = kotlin.math.abs(v.toShort().toInt())
            if (a > m) m = a
            i += 8   // örnekleme yeterli
        }
        return m
    }

    private fun amplify(b: ByteArray, n: Int, g: Float) {
        var i = 0
        while (i + 1 < n) {
            val v = ((b[i].toInt() and 0xFF) or (b[i + 1].toInt() shl 8)).toShort().toInt()
            val o = (v * g).toInt().coerceIn(-32768, 32767)
            b[i] = (o and 0xFF).toByte(); b[i + 1] = ((o shr 8) and 0xFF).toByte()
            i += 2
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("mirror", "Ekran yansıtma", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MirrorService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this).apply {
            if (Build.VERSION.SDK_INT >= 26) setChannelId("mirror")
            setSmallIcon(android.R.drawable.ic_menu_slideshow)
            setContentTitle("Ekran yansıtılıyor")
            setContentText("Durdurmak için dokun")
            setContentIntent(stop)
            @Suppress("DEPRECATION")
            addAction(Notification.Action.Builder(null, "Durdur", stop).build())
            setOngoing(true)
        }.build()
    }

    override fun onDestroy() {
        if (savedVol >= 0) runCatching { am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, savedVol, 0) }
        runCatching { getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener) }
        running = false
        gen++
        Hub.mirrorOut.value = false
        runCatching { audio?.stop() }; runCatching { audio?.release() }
        runCatching { display?.release() }
        runCatching { codec?.stop() }; runCatching { codec?.release() }
        runCatching { projection?.stop() }
        runCatching { sock?.close() }
        worker.shutdownNow()
        super.onDestroy()
    }
}
