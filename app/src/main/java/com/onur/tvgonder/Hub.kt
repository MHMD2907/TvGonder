// v0.6 – 2026-10-12: TV güncellemesi durumu (updateReady)
package com.onur.tvgonder

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.CompletableFuture

/** Sunucu (arka plan) ile TV ekranı arasındaki ortak durum. */
data class PairRequest(val name: String, val decision: CompletableFuture<Boolean>)

object Hub {
    val pairingCode = MutableStateFlow<String?>(null)
    val pending = MutableStateFlow<PairRequest?>(null)
    val devices = MutableStateFlow<Map<String, String>>(emptyMap())
    val running = MutableStateFlow(false)
    /** Telefondan gelen yeni sürüm APK'sı; TV ekranında "Yükle" penceresi açar. */
    val updateReady = MutableStateFlow<File?>(null)
}
