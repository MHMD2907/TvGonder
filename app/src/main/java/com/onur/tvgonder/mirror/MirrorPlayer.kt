// v0.23 – 2026-10-19: gelen yansıtmayı oynatır: H.264 çözücü (ekrana), PCM ses (AudioTrack); boyut değişince çözücüyü yeniler
package com.onur.tvgonder.mirror

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class MirrorPlayer(
    private val session: MirrorSession,
    private val onSize: (Int, Int) -> Unit,
    private val onEnd: () -> Unit,
) {
    @Volatile private var running = false
    private var surface: Surface? = null
    private var decoder: MediaCodec? = null
    private var config: ByteArray? = null
    private var w = 0
    private var h = 0
    private var gotKey = false
    private val info = MediaCodec.BufferInfo()

    private val sound = ArrayBlockingQueue<ByteArray>(25)
    private var track: AudioTrack? = null

    fun start(s: Surface) {
        if (running) return
        surface = s
        running = true
        Thread({ readLoop() }, "mirror-read").apply { isDaemon = true; start() }
        Thread({ audioLoop() }, "mirror-audio").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        session.close()
        releaseDecoder()
        runCatching { track?.stop() }; runCatching { track?.release() }
        track = null
    }

    private fun readLoop() {
        val inp = session.input
        try {
            while (running) {
                val type = inp.readUnsignedByte()
                val len = inp.readInt()
                val pts = inp.readLong()
                if (len < 0 || len > 8 shl 20) break
                val buf = ByteArray(len)
                inp.readFully(buf)
                when (type) {
                    Proto.SIZE -> {
                        val bb = ByteBuffer.wrap(buf)
                        w = bb.int; h = bb.int
                        onSize(w, h)
                        releaseDecoder()
                        config = null
                    }
                    Proto.CONFIG -> {
                        config = buf
                        decoder?.let { queue(it, buf, pts, MediaCodec.BUFFER_FLAG_CODEC_CONFIG) }
                    }
                    Proto.KEY, Proto.FRAME -> {
                        val d = decoder ?: makeDecoder() ?: continue
                        if (type == Proto.KEY) gotKey = true
                        if (!gotKey) continue
                        if (!queue(d, buf, pts, if (type == Proto.KEY) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)) {
                            releaseDecoder()   // çözücü bozulduysa yeniden kur, anahtar kare iste
                        }
                    }
                    Proto.AUDIO -> sound.offer(buf)
                }
            }
        } catch (_: Exception) { }
        if (running) { running = false; releaseDecoder(); onEnd() }
    }

    private fun makeDecoder(): MediaCodec? {
        val s = surface ?: return null
        val cfg = config ?: return null
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h)
            if (Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            val d = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            try { d.configure(f, s, null, 0) } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= 30) { d.reset(); f.removeKey(MediaFormat.KEY_LOW_LATENCY); d.configure(f, s, null, 0) } else throw e
            }
            d.start()
            decoder = d
            gotKey = false
            queue(d, cfg, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            session.requestKeyFrame()
            d
        }.getOrNull()
    }

    private fun queue(d: MediaCodec, data: ByteArray, pts: Long, flags: Int): Boolean = try {
        val idx = d.dequeueInputBuffer(50_000)
        if (idx >= 0) {
            d.getInputBuffer(idx)?.apply { clear(); put(data) }
            d.queueInputBuffer(idx, 0, data.size, pts, flags)
        }
        // Hazır kareleri hemen göster (gecikme olmasın)
        while (true) {
            val o = d.dequeueOutputBuffer(info, 0)
            if (o >= 0) d.releaseOutputBuffer(o, true)
            else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) continue
            else break
        }
        true
    } catch (_: Exception) { false }

    private fun releaseDecoder() {
        val d = decoder ?: return
        decoder = null
        runCatching { d.stop() }; runCatching { d.release() }
    }

    private fun audioLoop() {
        while (running) {
            val b = runCatching { sound.poll(200, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            val t = track ?: runCatching {
                val min = AudioTrack.getMinBufferSize(Proto.RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Proto.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build())
                    .setBufferSizeInBytes(maxOf(min, 3840 * 4))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build().also { it.play(); track = it }
            }.getOrNull() ?: continue
            // Birikme olursa (ağ takıldıysa) eski sesi at, gecikme büyümesin
            if (sound.size > 10) sound.clear()
            runCatching { t.write(b, 0, b.size) }
        }
    }
}
