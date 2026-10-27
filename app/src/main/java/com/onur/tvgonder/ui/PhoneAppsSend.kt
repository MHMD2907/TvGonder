// v0.40 – 2026-10-27: telefondaki uygulamayı TV'ye gönderme: göndermeden önce TV'nin işlemcisi/Android sürümüyle uyum kontrolü; parçalılar .apks
package com.onur.tvgonder.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ApkPack {
    data class PhoneApp(val pkg: String, val label: String, val split: Boolean, val size: Long)

    /** Telefona sonradan yüklenmiş (sistem dışı) uygulamalar. */
    fun list(c: Context): List<PhoneApp> {
        val pm = c.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(main, 0).map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != c.packageName }
            .map { ai ->
                val splits = ai.splitSourceDirs.orEmpty()
                val size = (listOf(ai.sourceDir) + splits).sumOf { File(it).length() }
                PhoneApp(ai.packageName, pm.getApplicationLabel(ai).toString(), splits.isNotEmpty(), size)
            }
            .sortedBy { it.label.lowercase() }
    }

    /** Uygulamanın yerel kod işlemci türleri (boşsa her işlemcide çalışır). */
    fun abis(c: Context, pkg: String): Set<String> {
        val ai = c.packageManager.getApplicationInfo(pkg, 0)
        val out = mutableSetOf<String>()
        val known = listOf("arm64-v8a", "armeabi-v7a", "armeabi", "x86_64", "x86")
        ai.splitSourceDirs.orEmpty().forEach { p ->
            val b = File(p).name.lowercase().removeSuffix(".apk").replace('_', '-')
            known.firstOrNull { b.endsWith(".$it") || b.endsWith("-$it") }?.let { out += it }
        }
        runCatching {
            java.util.zip.ZipFile(ai.sourceDir).use { z ->
                z.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }
                    .forEach { out += it.removePrefix("lib/").substringBefore('/') }
            }
        }
        return out
    }

    /** Uygulamanın istediği en düşük Android sürümü. */
    fun minSdk(c: Context, pkg: String): Int =
        if (android.os.Build.VERSION.SDK_INT >= 24) c.packageManager.getApplicationInfo(pkg, 0).minSdkVersion else 0

    fun icon(c: Context, pkg: String): ImageBitmap? = runCatching {
        c.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap()
    }.getOrNull()

    /**
     * Gönderilecek kurulum dosyasını hazırlar: tek parçalıysa .apk, parçalıysa (Play Store'dan gelenlerin çoğu)
     * bütün parçaları içeren .apks. TV Gönder TV'de ikisini de kurar.
     */
    fun build(c: Context, pkg: String, onProgress: (Float) -> Unit = {}): File {
        val pm = c.packageManager
        val ai = pm.getApplicationInfo(pkg, 0)
        val label = pm.getApplicationLabel(ai).toString().replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().ifBlank { pkg }
        val ver = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()?.let { " $it" } ?: ""
        val dir = File(c.cacheDir, "appsend").apply { deleteRecursively(); mkdirs() }
        val parts = listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()
        if (parts.size == 1) {
            val out = File(dir, "$label$ver.apk")
            File(ai.sourceDir).copyTo(out, overwrite = true)
            onProgress(1f)
            return out
        }
        val out = File(dir, "$label$ver.apks")
        val total = parts.sumOf { File(it).length() }.coerceAtLeast(1)
        var done = 0L
        ZipOutputStream(out.outputStream().buffered()).use { z ->
            z.setLevel(0) // APK zaten sıkıştırılmış; hızlı paketle
            parts.forEachIndexed { i, p ->
                val f = File(p)
                z.putNextEntry(ZipEntry(if (i == 0) "base.apk" else f.name))
                f.inputStream().use { inp ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        z.write(buf, 0, n); done += n; onProgress(done.toFloat() / total)
                    }
                }
                z.closeEntry()
            }
        }
        return out
    }
}

/** Telefondaki uygulamalardan birini seçtirir. */
@Composable
fun PhoneAppPicker(onPick: (ApkPack.PhoneApp) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var apps by remember { mutableStateOf<List<ApkPack.PhoneApp>?>(null) }
    var q by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { runCatching { ApkPack.list(ctx) }.getOrDefault(emptyList()) } }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("📦 Telefondaki uygulamayı TV'ye kur") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Seçtiğin uygulama TV'ye gönderilir; TV'de bildirime ya da Dosyalarım'da dosyaya basıp Yükle de. " +
                        "Telefon için yapılmış uygulamalar TV'de dokunmatik isteyebilir (Fare eklentisiyle kullanılır).",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Ara…") })
                val list = apps
                if (list == null) Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                    items(list.filter { q.isBlank() || it.label.contains(q, true) }, key = { it.pkg }) { a ->
                        val icon by produceState<ImageBitmap?>(null, a.pkg) { value = withContext(Dispatchers.IO) { ApkPack.icon(ctx, a.pkg) } }
                        Row(
                            Modifier.fillMaxWidth().clickable { onPick(a) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(Modifier.size(40.dp)) { icon?.let { Image(it, null, Modifier.fillMaxSize()) } }
                            Column(Modifier.weight(1f)) {
                                Text(a.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "%.0f MB".format(a.size / 1_048_576.0) + if (a.split) " · parçalı (.apks olarak gider)" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Kapat") } },
    )
}
