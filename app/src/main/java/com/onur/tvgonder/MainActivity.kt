// v0.14 – 2026-10-13: alıcı moddaki tablet paylaşımları da alır (Gönder ekranına); alıcı modu, karekod paylaşımı
package com.onur.tvgonder

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.onur.tvgonder.ui.AppTheme
import com.onur.tvgonder.ui.PhoneRoot
import com.onur.tvgonder.ui.TvScreen

/** Başka bir uygulamanın Paylaş menüsünden gelen içerik. */
/** print = true ise içerik "TV Gönder ile yazdır" seçeneğinden geldi. */
class Shared(val text: String? = null, val files: List<Uri> = emptyList(), val print: Boolean = false, val qr: Boolean = false)

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private var tree by mutableStateOf<String?>(null)
    private var shared by mutableStateOf<Shared?>(null)

    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            store.treeUri = uri.toString()
            tree = uri.toString()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        tree = store.treeUri
        val tv = isTv(this) || store.receiverMode
        if (tv && !isTv(this)) requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (!isTv(this)) readShare(intent)
        if (tv) intent?.getStringExtra(LinkOpener.EXTRA_URL)?.let { Hub.openUrl.value = it }
        if (tv) ContextCompat.startForegroundService(this, Intent(this, ReceiverService::class.java))

        setContent {
            AppTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (tv) TvScreen(
                        store, tree,
                        onPickSaf = { runCatching { pickTree.launch(null) }.isSuccess },
                        onSetStorage = { store.treeUri = it; tree = it },
                        shared = shared, onSharedHandled = { shared = null },
                    ) else PhoneRoot(store, shared) { shared = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!isTv(this)) readShare(intent)
        if (isTv(this) || store.receiverMode) intent.getStringExtra(LinkOpener.EXTRA_URL)?.let { Hub.openUrl.value = it }
    }

    override fun onResume() { super.onResume(); Hub.foreground = true }
    override fun onPause() { Hub.foreground = false; super.onPause() }

    private fun readShare(i: Intent?) {
        if (i == null) return
        if (i.component?.className?.endsWith(".QrShare") == true) {
            IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { shared = Shared(files = listOf(it), qr = true) }
            return
        }
        val print = i.component?.className?.endsWith(".PrintShare") == true
        if (print) {
            val files = if (i.action == Intent.ACTION_SEND_MULTIPLE)
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.toList().orEmpty()
            else listOfNotNull(IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java))
            if (files.isNotEmpty()) shared = Shared(files = files, print = true)
            return
        }
        val s = when (i.action) {
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                val text = i.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                when {
                    stream != null -> Shared(files = listOf(stream))
                    !text.isNullOrBlank() -> Shared(text = text)
                    else -> null
                }
            }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)
                    ?.takeIf { it.isNotEmpty() }?.let { Shared(files = it.toList()) }
            else -> null
        }
        if (s != null) shared = s
    }
}
