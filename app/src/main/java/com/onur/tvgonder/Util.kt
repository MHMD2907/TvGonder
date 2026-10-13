// v0.12 – 2026-10-13: uygulamadan çıkış; depolama birimleri (USB/TV hafızası); sürüm ve APK kurulum yardımcıları
package com.onur.tvgonder

import android.app.UiModeManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.net.Inet4Address
import java.net.NetworkInterface

fun isTv(c: Context): Boolean =
    (c.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType ==
        Configuration.UI_MODE_TYPE_TELEVISION

object Net {
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    fun qr(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(
            text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2)
        )
        val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}

/** Kurulu uygulamanın sürüm kodu (build.gradle'daki versionCode). */
fun appVersionCode(c: Context): Long = runCatching {
    val pi = c.packageManager.getPackageInfo(c.packageName, 0)
    if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
}.getOrDefault(0L)

object Installer {
    /** Bu uygulamanın APK kurma izni var mı (Android 8+ "bilinmeyen kaynaklar"). */
    fun canInstall(c: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()

    /** İzin ekranını açar; açılamazsa false döner. */
    fun openPermission(c: Context): Boolean = runCatching {
        c.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.isSuccess

    /** Android'in kurulum ekranını açar. */
    fun install(c: Context, apk: File) {
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", apk)
        c.startActivity(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/** TV'de takılı depolama birimleri. Uygulamanın her birimdeki kendi klasörü izinsiz yazılabilir. */
object Volumes {
    data class Vol(val label: String, val dir: File, val removable: Boolean)

    fun list(c: Context): List<Vol> {
        val sm = c.getSystemService(android.os.storage.StorageManager::class.java)
        return c.getExternalFilesDirs(null).filterNotNull().mapNotNull { f ->
            val v = runCatching { sm.getStorageVolume(f) }.getOrNull()
            val removable = v?.isRemovable ?: false
            val label = v?.getDescription(c) ?: if (removable) "USB bellek" else "TV hafızası"
            Vol(label, File(f, "TvGonder"), removable)
        }.sortedByDescending { it.removable }
    }

    /** Kayıt yeri adresi (file://...) için okunur ad. */
    fun labelFor(c: Context, tree: String): String? {
        val u = Uri.parse(tree)
        if (u.scheme != "file") return null
        val path = u.path ?: return null
        return list(c).firstOrNull { path.startsWith(it.dir.path) }?.label ?: "Klasör"
    }
}

/** Uygulamayı kapatır (TV'de alıcı arka planda çalışmaya devam eder). */
fun exitApp(c: Context) {
    var x: Context? = c
    while (x is android.content.ContextWrapper) {
        if (x is android.app.Activity) { x.finishAffinity(); return }
        x = x.baseContext
    }
}
