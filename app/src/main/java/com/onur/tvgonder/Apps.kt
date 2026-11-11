// v0.49 – 2026-11-11: Fare eklentisi 1.6 (tıklama düzeltmesi, TV Gönder kapalıyken de çalışır); gerçek çalışma yoklaması; uygulamalar
package com.onur.tvgonder

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.provider.Settings
import java.io.ByteArrayOutputStream
import java.io.File

object Apps {
    data class App(val pkg: String, val label: String, val system: Boolean, val size: Long, val installed: Long)

    private val own = setOf("com.onur.tvgonder", "com.onur.tvgonder.fare")

    /** Açılabilir (ana ekranda görünen) uygulamalar, ada göre sıralı. */
    fun list(c: Context, includeSystem: Boolean): List<App> {
        val pm = c.packageManager
        val pkgs = LinkedHashSet<String>()
        for (cat in listOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            runCatching {
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(cat), 0).forEach { pkgs.add(it.activityInfo.packageName) }
            }
        }
        return pkgs.filter { it !in own }.mapNotNull { p ->
            runCatching {
                val ai = pm.getApplicationInfo(p, 0)
                val sys = (ai.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                if (sys && !includeSystem) return@runCatching null
                val pi = pm.getPackageInfo(p, 0)
                App(p, pm.getApplicationLabel(ai).toString(), sys, runCatching { File(ai.sourceDir).length() }.getOrDefault(0L), pi.firstInstallTime)
            }.getOrNull()
        }.sortedBy { it.label.lowercase() }
    }

    fun icon(c: Context, pkg: String, size: Int = 96): Bitmap? = runCatching {
        val d = c.packageManager.getApplicationIcon(pkg)
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(b))
        b
    }.getOrNull()

    fun iconPng(c: Context, pkg: String): ByteArray? = icon(c, pkg)?.let { b ->
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    fun open(c: Context, pkg: String): Boolean {
        val pm = c.packageManager
        val i = (if (isTv(c)) pm.getLeanbackLaunchIntentForPackage(pkg) else null)
            ?: pm.getLaunchIntentForPackage(pkg)
            ?: pm.getLeanbackLaunchIntentForPackage(pkg)
            ?: return false
        return Launcher.start(c, i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }

    /** Android'in "kaldırılsın mı?" penceresini açar (onay cihazın ekranında verilir). */
    fun remove(c: Context, pkg: String): Boolean =
        Launcher.start(c, Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))

    /** Uygulama bilgisi (sistem uygulamaları burada "Devre dışı bırak" ile kapatılır). */
    fun info(c: Context, pkg: String): Boolean = runCatching {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    const val FARE = "com.onur.tvgonder.fare"
    /** Bu sürümle gelen en yeni Fare eklentisi sürümü. */
    const val FARE_LATEST = 7

    /** Kurulu Fare eklentisinin sürüm numarası; kurulu değilse -1. */
    fun fareVersion(c: Context): Long = runCatching {
        val pi = c.packageManager.getPackageInfo(FARE, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(-1L)

    @Volatile private var aliveAt = 0L
    @Volatile private var aliveVal = false

    /**
     * Fare eklentisi gerçekten çalışıyor mu (açık görünüp takılmış olabilir): TV içinde ping → pong.
     * Ağ işi: ana iş parçacığında çağrılmaz (orada yalnız "açık mı" bilgisi döner). Sonuç 8 sn saklanır.
     */
    fun fareAlive(c: Context): Boolean {
        if (!fareEnabled(c)) return false
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return true
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - aliveAt < 8000) return aliveVal
        val token = com.onur.tvgonder.Store(c).devices().keys.firstOrNull() ?: return true
        val ok = runCatching {
            java.net.DatagramSocket().use { s ->
                s.soTimeout = 800
                val b = "$token ping".toByteArray()
                s.send(java.net.DatagramPacket(b, b.size, java.net.InetAddress.getByName("127.0.0.1"), 8767))
                val buf = ByteArray(16)
                val p = java.net.DatagramPacket(buf, buf.size)
                s.receive(p)
                String(buf, 0, p.length) == "pong"
            }
        }.getOrDefault(false)
        aliveAt = now; aliveVal = ok
        return ok
    }

    fun fareEnabled(c: Context): Boolean =
        (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(FARE)

    /** Uygulamanın içine gömülü Fare eklentisini geçici dosyaya çıkarır (internet gerekmez). */
    fun fareApk(c: Context): File {
        val f = File(c.cacheDir, "update/TvGonderFare.apk").apply { parentFile?.mkdirs() }
        c.assets.open("TvGonderFare.apk").use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        if (f.length() < 10_000) error("Eklenti dosyası bozuk")
        return f
    }

    /** TV/tablet: gömülü eklentiyi kurar (Kur / Güncelle). */
    fun downloadAndInstallFare(c: Context) {
        Installer.install(c, fareApk(c))
    }

    fun sizeText(b: Long): String = when {
        b <= 0 -> ""
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
        else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    }
}
