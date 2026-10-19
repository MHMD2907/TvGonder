// v0.23 – 2026-10-19: cihazdaki uygulamalar: liste, simge, aç, kaldır (TV/tablet ekranı ve telefondan uzaktan)
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

    fun sizeText(b: Long): String = when {
        b <= 0 -> ""
        b < 1024 * 1024 -> "${b / 1024} KB"
        b < 1024L * 1024 * 1024 -> "${b / (1024 * 1024)} MB"
        else -> "%.1f GB".format(b / (1024.0 * 1024 * 1024))
    }
}
