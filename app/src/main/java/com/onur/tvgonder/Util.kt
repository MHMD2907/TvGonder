// v0.40 – 2026-10-27: parçalı paketlerde TV'nin işlemcisine uyan parça seçilir, uymuyorsa nedeni açıkça yazılır; USB'den APK/XAPK/APKS; oturumlu kurulum
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

    /**
     * APK'yı kurar. Önce Android'in "oturumlu" kurulumunu kullanır: böyle kurulan uygulamalarda
     * Android 13+ "kısıtlanmış ayar" engeli olmaz (Fare eklentisinin erişilebilirlik anahtarı kapanmaz).
     * Olmazsa eski yöntemle kurulum ekranını açar.
     */
    fun install(c: Context, apk: File) {
        if (Build.VERSION.SDK_INT >= 23 && runCatching { sessionInstall(c, apk) }.isSuccess) return
        viewInstall(c, apk)
    }

    /**
     * USB / paylaşılan bir adresteki kurulum dosyasını kurar (arka planda çağrılmalı).
     * .apk → normal kurulum. .xapk / .apks / .apkm → içindeki parça APK'lar tek oturumda kurulur.
     */
    fun installUri(c: Context, uri: Uri, name: String) {
        val dir = File(c.cacheDir, "inst").apply { deleteRecursively(); mkdirs() }
        val f = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
        (c.contentResolver.openInputStream(uri) ?: error("Dosya okunamadı")).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        val isZipBundle = !name.endsWith(".apk", true) || runCatching {
            java.util.zip.ZipFile(f).use { z -> z.getEntry("AndroidManifest.xml") == null && z.entries().asSequence().any { it.name.endsWith(".apk", true) } }
        }.getOrDefault(false)
        if (!isZipBundle) { install(c, f); return }
        java.util.zip.ZipFile(f).use { z ->
            val all = z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.toList()
            if (all.isEmpty()) error("Paketin içinde APK yok (şifreli olabilir)")
            // İşlemci parçaları (config.arm64_v8a vb.): TV'nin desteklediği varsa yalnız onları al; hiçbiri uymuyorsa açıkça söyle
            val abis = Build.SUPPORTED_ABIS.map { it.replace('-', '_').lowercase() }
            fun abiOf(n: String): String? {
                val b = n.substringAfterLast('/').lowercase().removeSuffix(".apk")
                return listOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips").firstOrNull { b.endsWith(".$it") || b.endsWith("_$it") }
            }
            val abiParts = all.filter { abiOf(it.name) != null }
            val okAbi = abiParts.filter { abiOf(it.name) in abis }
            if (abiParts.isNotEmpty() && okAbi.isEmpty()) {
                val has = abiParts.mapNotNull { abiOf(it.name) }.distinct().joinToString { it.replace('_', '-') }
                error(
                    "Bu uygulama telefonun işlemcisine ($has) göre indirilmiş; TV'nin işlemcisi ${Build.SUPPORTED_ABIS.joinToString()}. " +
                        "Telefondan gönderilen sürüm bu TV'ye kurulamaz. APKPure / APKCombo gibi bir siteden " +
                        "\"armeabi-v7a\" (ya da universal) sürümünü XAPK/APK olarak flaşa indirip buradan kur."
                )
            }
            val best = abis.firstOrNull { a -> okAbi.any { abiOf(it.name) == a } }
            val parts = all.filter { abiOf(it.name) == null } + okAbi.filter { abiOf(it.name) == best }
            val pi = c.packageManager.packageInstaller
            val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
            val id = pi.createSession(params)
            try {
                pi.openSession(id).use { s ->
                    parts.forEachIndexed { n, en ->
                        val size = if (en.size >= 0) en.size else -1L
                        z.getInputStream(en).use { inp ->
                            s.openWrite("p$n.apk", 0, size).use { out -> inp.copyTo(out); s.fsync(out) }
                        }
                    }
                    val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                    val p = android.app.PendingIntent.getBroadcast(
                        c, id, Intent(c, InstallReceiver::class.java).setPackage(c.packageName), flags,
                    )
                    s.commit(p.intentSender)
                }
            } catch (e: Exception) {
                runCatching { pi.abandonSession(id) }
                throw e
            }
        }
    }

    private fun sessionInstall(c: Context, apk: File) {
        val pi = c.packageManager.packageInstaller
        val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        params.setSize(apk.length())
        val id = pi.createSession(params)
        try {
            pi.openSession(id).use { s ->
                apk.inputStream().use { inp ->
                    s.openWrite("app.apk", 0, apk.length()).use { out -> inp.copyTo(out); s.fsync(out) }
                }
                val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                val p = android.app.PendingIntent.getBroadcast(
                    c, id, Intent(c, InstallReceiver::class.java).setPackage(c.packageName), flags,
                )
                s.commit(p.intentSender)
            }
        } catch (e: Exception) {
            runCatching { pi.abandonSession(id) }
            throw e
        }
    }

    private fun viewInstall(c: Context, apk: File) {
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
