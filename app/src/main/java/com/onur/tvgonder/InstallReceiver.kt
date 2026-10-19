// v0.22 – 2026-10-19: oturumlu kurulumun sonucu: onay ekranını açar, hata olursa bildirir
package com.onur.tvgonder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") i.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { runCatching { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(c, "Kurulum tamamlandı ✓", Toast.LENGTH_LONG).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val hint = if ("CONFLICT" in msg.uppercase() || "SIGNATURE" in msg.uppercase())
                    " Eski sürümü kaldırıp yeniden gönder." else ""
                Toast.makeText(c, "Kurulamadı: $msg$hint", Toast.LENGTH_LONG).show()
            }
        }
    }
}
