// v0.35 – 2026-10-23: oyun kolu/kumanda tuşları TV kumandası bağlıysa Wi‑Fi'dan gider (Bluetooth eşleşmesi gerekmez); Bluetooth yedek yol
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
    const val C_CH_UP = 0x9C; const val C_CH_DOWN = 0x9D; const val C_SEARCH = 0x221

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
    /** TV eşleşmede kod isterse (enter=true: kodu telefona yaz) ya da kod gösterirse. */
    data class PinRequest(val device: BluetoothDevice, val enter: Boolean, val key: Int)
    val pinRequest = MutableStateFlow<PinRequest?>(null)

    fun submitPin(pin: String) {
        val r = pinRequest.value ?: return
        val ok = runCatching { r.device.setPin(pin.toByteArray()) }.getOrDefault(false)
        status.value = if (ok) "Kod gönderildi, eşleşiyor…" else "Kod gönderilemedi – telefonun Bluetooth ayarlarından TV'ye dokunup kodu orada gir"
        pinRequest.value = null
    }

    /** Telefonun aramada bulduğu cihazlar (TV "Aksesuar ekle" ekranındayken görünür olur). */
    val found = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val scanning = MutableStateFlow(false)
    private var receiver: android.content.BroadcastReceiver? = null

    fun scan(c: Context) {
        val a = adapter ?: c.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        adapter = a
        if (receiver == null) {
            val r = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context, i: android.content.Intent) {
                    @Suppress("DEPRECATION")
                    val d: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    when (i.action) {
                        BluetoothDevice.ACTION_PAIRING_REQUEST -> if (d != null) {
                            val v = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                            val key = i.getIntExtra(BluetoothDevice.EXTRA_PAIRING_KEY, -1)
                            // 0: PIN yaz · 1: TV'nin gösterdiği sayıyı yaz · 2: aynı sayı mı onayla
                            pinRequest.value = PinRequest(d, enter = v == BluetoothDevice.PAIRING_VARIANT_PIN || v == 1, key = key)
                        }
                        BluetoothDevice.ACTION_FOUND -> if (d != null && found.value.none { it.address == d.address }) found.value = found.value + d
                        BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> scanning.value = false
                        BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                            val st = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                            if (d != null && d.address == wanted?.address) when (st) {
                                BluetoothDevice.BOND_BONDING -> status.value = "Eşleşiyor… TV'de ve telefonda çıkan kodu onayla"
                                BluetoothDevice.BOND_BONDED -> { pinRequest.value = null; status.value = "Eşleşti, bağlanıyor…"; connect(d) }
                                BluetoothDevice.BOND_NONE -> status.value = "Eşleşme olmadı – TV \"Aksesuar ekle\" ekranındayken tekrar dene"
                            }
                        }
                    }
                }
            }
            val f = android.content.IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_PAIRING_REQUEST)
                priority = 1000
            }
            androidx.core.content.ContextCompat.registerReceiver(c.applicationContext, r, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED)
            receiver = r
        }
        found.value = emptyList()
        runCatching { a.cancelDiscovery() }
        scanning.value = runCatching { a.startDiscovery() }.getOrDefault(false)
        if (!scanning.value) status.value = "Arama başlatılamadı (Bluetooth/konum izni?)"
    }

    /** Eşleşmemişse eşleşir, eşleşince kendiliğinden bağlanır. */
    fun pairAndConnect(d: BluetoothDevice) {
        wanted = d
        runCatching { adapter?.cancelDiscovery() }
        scanning.value = false
        if (d.bondState == BluetoothDevice.BOND_BONDED) { connect(d); return }
        status.value = "Eşleşme isteniyor…"
        if (runCatching { d.createBond() }.getOrDefault(false) != true) status.value = "Eşleşme başlatılamadı"
    }

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

    // ---------------------------------------------------------------- Wi‑Fi yolu (TV kumandası bağlıysa)
    // Bluetooth eşleşmesi gerekmez: tuşlar TV'nin kendi kumanda hizmetine gider (basılı tutma dahil).

    /** TV kumandası (hızlı) bağlıysa oyun kolu ve kumanda tuşları Wi‑Fi'dan gider. */
    val wifi: Boolean get() = AtvRemote.state.value == AtvRemote.State.Connected

    private val btnKey = mapOf(
        A to android.view.KeyEvent.KEYCODE_BUTTON_A, B to android.view.KeyEvent.KEYCODE_BUTTON_B,
        X to android.view.KeyEvent.KEYCODE_BUTTON_X, Y to android.view.KeyEvent.KEYCODE_BUTTON_Y,
        L1 to android.view.KeyEvent.KEYCODE_BUTTON_L1, R1 to android.view.KeyEvent.KEYCODE_BUTTON_R1,
        L2 to android.view.KeyEvent.KEYCODE_BUTTON_L2, R2 to android.view.KeyEvent.KEYCODE_BUTTON_R2,
        SELECT to android.view.KeyEvent.KEYCODE_BUTTON_SELECT, START to android.view.KeyEvent.KEYCODE_BUTTON_START,
        MODE to android.view.KeyEvent.KEYCODE_BUTTON_MODE, L3 to android.view.KeyEvent.KEYCODE_BUTTON_THUMBL,
        R3 to android.view.KeyEvent.KEYCODE_BUTTON_THUMBR,
    )
    private val consumerKey = mapOf(
        C_BACK to android.view.KeyEvent.KEYCODE_BACK, C_HOME to android.view.KeyEvent.KEYCODE_HOME,
        C_MENU to android.view.KeyEvent.KEYCODE_MENU, C_PLAY to android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        C_VOL_UP to android.view.KeyEvent.KEYCODE_VOLUME_UP, C_VOL_DOWN to android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
        C_MUTE to android.view.KeyEvent.KEYCODE_VOLUME_MUTE, C_CH_UP to android.view.KeyEvent.KEYCODE_CHANNEL_UP,
        C_CH_DOWN to android.view.KeyEvent.KEYCODE_CHANNEL_DOWN, C_SEARCH to android.view.KeyEvent.KEYCODE_SEARCH,
    )
    private val held = HashSet<Int>()   // Wi‑Fi'da basılı tutulan yön tuşları

    private fun dirKeys(): Set<Int> {
        val k = HashSet<Int>()
        val up = android.view.KeyEvent.KEYCODE_DPAD_UP; val dn = android.view.KeyEvent.KEYCODE_DPAD_DOWN
        val lf = android.view.KeyEvent.KEYCODE_DPAD_LEFT; val rt = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        when (hat) { 0 -> k += up; 1 -> { k += up; k += rt }; 2 -> k += rt; 3 -> { k += dn; k += rt }
            4 -> k += dn; 5 -> { k += dn; k += lf }; 6 -> k += lf; 7 -> { k += up; k += lf } }
        // Sol çubuk da yön tuşu gibi davranır
        if (ly < -60) k += up; if (ly > 60) k += dn; if (lx < -60) k += lf; if (lx > 60) k += rt
        return k
    }

    @Synchronized private fun syncDirs() {
        val now = dirKeys()
        for (k in held - now) AtvRemote.key(k, 2)     // bırak
        for (k in now - held) AtvRemote.key(k, 1)     // bas (basılı tut)
        held.clear(); held.addAll(now)
    }

    fun sendPad() {
        if (wifi) { syncDirs(); return }
        report(1, byteArrayOf(
            (buttons and 0xFF).toByte(), ((buttons shr 8) and 0xFF).toByte(), (hat and 0x0F).toByte(),
            b(lx), b(ly), b(rx), b(ry),
        ))
    }

    fun press(btn: Int, down: Boolean) {
        buttons = if (down) buttons or (1 shl btn) else buttons and (1 shl btn).inv()
        if (wifi) { btnKey[btn]?.let { AtvRemote.key(it, if (down) 1 else 2) }; return }
        sendPad()
    }

    fun pressHat(h: Int) { hat = h; sendPad() }

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
        if (wifi) { consumerKey[usage]?.let { AtvRemote.key(it) }; return }
        report(3, byteArrayOf((usage and 0xFF).toByte(), ((usage shr 8) and 0xFF).toByte()))
        report(3, byteArrayOf(0, 0))
    }
}
