// v0.24 – 2026-10-19: uygulamalar (liste, simge, aç, kaldır) + Fare eklentisi sürümü ve TV'nin eklentiyi kendisinin indirmesi
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
        return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) }.isSuccess
    }

    /** Android'in "kaldırılsın mı?" penceresini açar (onay cihazın ekranında verilir). */
    fun remove(c: Context, pkg: String): Boolean = runCatching {
        c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** Uygulama bilgisi (sistem uygulamaları burada "Devre dışı bırak" ile kapatılır). */
    fun info(c: Context, pkg: String): Boolean = runCatching {
        c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    const val FARE = "com.onur.tvgonder.fare"
    /** Bu sürümle gelen en yeni Fare eklentisi sürümü. */
    const val FARE_LATEST = 3

    /** Kurulu Fare eklentisinin sürüm numarası; kurulu değilse -1. */
    fun fareVersion(c: Context): Long = runCatching {
        val pi = c.packageManager.getPackageInfo(FARE, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    }.getOrDefault(-1L)

    fun fareEnabled(c: Context): Boolean =
        (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").contains(FARE)

    /** TV/tablet eklentiyi GitHub'dan kendisi indirir ve kurulum penceresini açar (telefon gerekmez). */
    fun downloadAndInstallFare(c: Context) {
        val f = File(c.cacheDir, "update/TvGonderFare.apk").apply { parentFile?.mkdirs() }
        val req = okhttp3.Request.Builder().url("https://github.com/MHMD2907/TvGonder/releases/latest/download/TvGonderFare.apk").build()
        okhttp3.OkHttpClient.Builder().followRedirects(true).build().newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("İndirilemedi (${r.code})")
            r.body?.byteStream()?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: error("Boş dosya")
        }
        if (f.length() < 10_000) error("İndirilemedi")
        Installer.install(c, f)
    }

    fun sizeText(b: Long): String = when {
        b <= 0 -> ""
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
        else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    }
}
