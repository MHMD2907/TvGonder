// v0.23 – 2026-10-19: telefon/tablet Bluetooth ile TV'ye gerçek oyun kolu + fare + medya tuşları olur (izin/eklenti gerektirmez)
package com.onur.tvgonder

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/**
 * Android 9+ "Bluetooth HID cihazı": TV bu telefonu takılmış bir oyun kolu / fare sanır.
 * Rapor 1: oyun kolu (16 düğme, yön, 2 analog çubuk) · Rapor 2: fare · Rapor 3: medya/Geri/Ana ekran tuşları.
 */
@SuppressLint("MissingPermission")
object BtPad {
    val status = MutableStateFlow("Kapalı")
    val connected = MutableStateFlow<BluetoothDevice?>(null)
    val ready = MutableStateFlow(false)

    private var hid: BluetoothHidDevice? = null
    private var adapter: BluetoothAdapter? = null
    private val exec = Executors.newSingleThreadExecutor()
    private var wanted: BluetoothDevice? = null

    // Oyun kolu durumu
    @Volatile var buttons = 0
    @Volatile var hat = 8           // 0 yukarı … 7 sol-yukarı, 8 = basılmıyor
    @Volatile var lx = 0; @Volatile var ly = 0; @Volatile var rx = 0; @Volatile var ry = 0

    // Düğme numaraları (Android'de karşılıkları)
    const val A = 0; const val B = 1; const val X = 3; const val Y = 4
    const val L1 = 6; const val R1 = 7; const val L2 = 8; const val R2 = 9
    const val SELECT = 10; const val START = 11; const val MODE = 12; const val L3 = 13; const val R3 = 14

    // Tüketici (medya) tuşları
    const val C_HOME = 0x223; const val C_BACK = 0x224; const val C_PLAY = 0xCD
    const val C_VOL_UP = 0xE9; const val C_VOL_DOWN = 0xEA; const val C_MUTE = 0xE2; const val C_MENU = 0x40

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 28

    private val descriptor = byteArrayOf(
        // Oyun kolu
        0x05, 0x01, 0x09, 0x05, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x10, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95.toByte(), 0x10, 0x81.toByte(), 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x42,
        0x65, 0x00, 0x75, 0x04, 0x95.toByte(), 0x01, 0x81.toByte(), 0x03,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x04, 0x81.toByte(), 0x02,
        0xC0.toByte(),
        // Fare
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x02, 0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte(),
        // Tüketici tuşları (Ana ekran, Geri, oynat, ses)
        0x05, 0x0C, 0x09, 0x01, 0xA1.toByte(), 0x01, 0x85.toByte(), 0x03,
        0x15, 0x00, 0x26, 0xFF.toByte(), 0x03, 0x19, 0x00, 0x2A, 0xFF.toByte(), 0x03, 0x75, 0x10, 0x95.toByte(), 0x01, 0x81.toByte(), 0x00,
        0xC0.toByte(),
    )

    @RequiresApi(28)
    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(device: BluetoothDevice?, registered: Boolean) {
            ready.value = registered
            status.value = if (registered) "Hazır – TV'yi seçip Bağlan'a bas" else "Kapalı"
            if (registered) wanted?.let { d -> exec.execute { runCatching { hid?.connect(d) } } }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> { connected.value = device; status.value = "Bağlı: ${device?.name ?: "TV"}" }
                BluetoothProfile.STATE_CONNECTING -> status.value = "Bağlanıyor…"
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connected.value == device) connected.value = null
                    if (ready.value) status.value = "Bağlantı yok – Bağlan'a bas"
                }
            }
        }
    }

    /** Hizmeti başlatır (izinler verilmiş olmalı). */
    fun start(c: Context) {
        if (!supported) { status.value = "Bu cihazda Android 9+ gerekli"; return }
        if (hid != null) return
        val a = c.getSystemService(BluetoothManager::class.java)?.adapter
        if (a == null) { status.value = "Bu cihazda Bluetooth yok"; return }
        if (!a.isEnabled) { status.value = "Bluetooth kapalı – aç ve tekrar dene"; return }
        adapter = a
        status.value = "Başlatılıyor…"
        val ok = a.getProfileProxy(c.applicationContext, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (Build.VERSION.SDK_INT < 28) return
                val h = proxy as BluetoothHidDevice
                hid = h
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "TV Gönder Kol", "TV Gönder oyun kolu ve fare", "M-ONUR",
                    BluetoothHidDevice.SUBCLASS1_COMBO, descriptor,
                )
                val reg = runCatching { h.registerApp(sdp, null, null, exec, callback) }.getOrDefault(false)
                if (!reg) status.value = "Bu telefon Bluetooth oyun kolu olmayı desteklemiyor"
            }
            override fun onServiceDisconnected(profile: Int) {
                hid = null; ready.value = false; connected.value = null; status.value = "Kapalı"
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!ok) status.value = "Bu telefon Bluetooth oyun kolu olmayı desteklemiyor"
    }

    fun stop() {
        val h = hid ?: return
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching { connected.value?.let { h.disconnect(it) } }
            runCatching { h.unregisterApp() }
            runCatching { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, h) }
        }
        hid = null; ready.value = false; connected.value = null; status.value = "Kapalı"
    }

    /** Daha önce Bluetooth ile eşleşmiş cihazlar (TV burada görünmeli). */
    fun bonded(): List<BluetoothDevice> = runCatching { adapter?.bondedDevices?.toList() ?: emptyList() }.getOrDefault(emptyList())

    fun connect(d: BluetoothDevice) {
        wanted = d
        if (Build.VERSION.SDK_INT < 28) return
        val h = hid ?: return
        status.value = "Bağlanıyor: ${d.name}…"
        exec.execute { if (runCatching { h.connect(d) }.getOrDefault(false) != true) status.value = "Bağlanılamadı – TV'de eşleştirmeyi kontrol et" }
    }

    private fun report(id: Int, data: ByteArray) {
        if (Build.VERSION.SDK_INT < 28) return
        val h = hid ?: return
        val d = connected.value ?: return
        exec.execute { runCatching { h.sendReport(d, id, data) } }
    }

    private fun b(v: Int) = v.coerceIn(-127, 127).toByte()

    fun sendPad() = report(1, byteArrayOf(
        (buttons and 0xFF).toByte(), ((buttons shr 8) and 0xFF).toByte(), (hat and 0x0F).toByte(),
        b(lx), b(ly), b(rx), b(ry),
    ))

    fun press(btn: Int, down: Boolean) {
        buttons = if (down) buttons or (1 shl btn) else buttons and (1 shl btn).inv()
        sendPad()
    }

    fun setHat(h: Int) { hat = h; sendPad() }

    @Volatile private var mouseButtons = 0

    fun mouse(dx: Int, dy: Int, wheel: Int = 0) {
        var rx = dx; var ry = dy
        // büyük hareketleri parçala
        do {
            val sx = rx.coerceIn(-127, 127); val sy = ry.coerceIn(-127, 127)
            report(2, byteArrayOf(mouseButtons.toByte(), sx.toByte(), sy.toByte(), b(wheel)))
            rx -= sx; ry -= sy
        } while (rx != 0 || ry != 0)
    }

    fun mouseButton(btn: Int, down: Boolean) {
        mouseButtons = if (down) mouseButtons or (1 shl btn) else mouseButtons and (1 shl btn).inv()
        report(2, byteArrayOf(mouseButtons.toByte(), 0, 0, 0))
    }

    fun click() { mouseButton(0, true); mouseButton(0, false) }

    fun key(usage: Int) {
        report(3, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))
        report(3, byteArrayOf(0, 0))
    }
}
