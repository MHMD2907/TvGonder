// v0.3 – 2026-10-12: yazdırma paylaşımı Yazdır sekmesine, diğerleri Gönder sekmesine iletilir
package com.onur.tvgonder.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onur.tvgonder.Shared
import com.onur.tvgonder.Store

/** Telefondaki ana ekran: "TV'ye gönder" ve "Yazdır" sekmeleri. */
@Composable
fun PhoneRoot(store: Store, shared: Shared? = null, onSharedHandled: () -> Unit = {}) {
    var tab by remember { mutableIntStateOf(0) }
    LaunchedEffect(shared) { if (shared != null) tab = if (shared.print) 1 else 0 }
    Column(Modifier.fillMaxSize().background(Ink)) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) PhoneScreen(store, shared?.takeIf { !it.print }, onSharedHandled)
            else PrintScreen(shared?.takeIf { it.print }?.files?.firstOrNull(), onSharedHandled)
        }
        Row(Modifier.fillMaxWidth().background(Panel)) {
            TabItem("TV'ye gönder", tab == 0, Modifier.weight(1f)) { tab = 0 }
            TabItem("Yazdır", tab == 1, Modifier.weight(1f)) { tab = 1 }
        }
    }
}

@Composable
private fun TabItem(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(modifier.clickable(onClick = onClick).padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            color = if (selected) Accent else TextMuted,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
