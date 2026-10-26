// v0.38 – 2026-10-26: kurulum sonucu: başarısızlıkta nedenine göre Türkçe ipucu (işlemci, Android sürümü, parçalı paket, yer); onay ekranı; Fare açma ekranı
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
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(c, "Kurulum tamamlandı ✓", Toast.LENGTH_LONG).show()
                // Fare eklentisi kurulduysa: açma ekranına götür
                if (i.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) == Apps.FARE && !Apps.fareEnabled(c)) {
                    Toast.makeText(c, "Şimdi listede \"TV Gönder Fare\"yi seç ve aç", Toast.LENGTH_LONG).show()
                    runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                val u = msg.uppercase()
                val hint = when {
                    "CONFLICT" in u || "SIGNATURE" in u -> " Eski sürümü kaldırıp yeniden gönder."
                    "NO_MATCHING_ABIS" in u -> " Bu APK telefon işlemcisine göre yapılmış, TV'nin işlemcisi farklı. Uygulamanın \"armeabi-v7a\" ya da \"universal\" sürümünü indir."
                    "OLDER_SDK" in u -> " Uygulama TV'nin Android sürümünden daha yenisini istiyor; daha eski bir sürümünü indir."
                    "SPLIT" in u -> " Uygulama parçalı (XAPK/APKS); tek APK yerine paketin tamamını indir."
                    "INSUFFICIENT_STORAGE" in u -> " TV'de yer yok; birkaç uygulamayı kaldır."
                    "INVALID_APK" in u || "PARSE" in u -> " Dosya bozuk ya da eksik inmiş; yeniden indir."
                    else -> ""
                }
                Toast.makeText(c, "Kurulamadı: $msg$hint", Toast.LENGTH_LONG).show()
            }
        }
    }
}
