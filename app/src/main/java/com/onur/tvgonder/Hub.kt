// v0.35 – 2026-10-23: ana ekrana dönüş isteği; ekran yansıtma durumu, gelen dosya, link açma, ön planda mı
package com.onur.tvgonder

import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.concurrent.CompletableFuture

/** Sunucu (arka plan) ile TV ekranı arasındaki ortak durum. */
data class PairRequest(val name: String, val decision: CompletableFuture<Boolean>)

/** TV'ye gelen dosya (USB bellekteki konumu ile). */
data class Received(val name: String, val uri: String, val mime: String, val at: Long)

object Hub {
    val pairingCode = MutableStateFlow<String?>(null)
    val pending = MutableStateFlow<PairRequest?>(null)
    val devices = MutableStateFlow<Map<String, String>>(emptyMap())
    val running = MutableStateFlow(false)
    /** Telefondan gelen yeni sürüm APK'sı; TV ekranında "Yükle" penceresi açar. */
    val updateReady = MutableStateFlow<File?>(null)
    /** Son gelen dosya; TV ekranında "geldi – Aç" kartı gösterir. */
    val received = MutableStateFlow<Received?>(null)
    /** Uygulama içi tarayıcıda açılacak link. */
    val openUrl = MutableStateFlow<String?>(null)
    /** TV ekranı şu an önde mi. */
    @Volatile var foreground = false
    /** Bu cihaza gelen ekran yansıtması (TV/tablette gösterilir). */
    val mirrorIn = MutableStateFlow<com.onur.tvgonder.mirror.MirrorSession?>(null)
    /** TV kumandasındaki "TV Gönder" düğmesi: TV Gönder'in ana ekranına dön (değer her basışta değişir). */
    val goHome = MutableStateFlow(0L)
    /** Yansıtmada sesin durumu (telefonda gösterilir). */
    val mirrorAudio = MutableStateFlow("")
    /** Bu cihaz şu an ekranını yansıtıyor mu. */
    val mirrorOut = MutableStateFlow(false)
}
