package com.harukisolodev.harukistream.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.AppSettings
import com.harukisolodev.harukistream.data.DownloadSpeedMode
import com.harukisolodev.harukistream.ui.HarukiViewModel
import com.harukisolodev.harukistream.ui.theme.*

@Composable
fun SettingsScreen(vm: HarukiViewModel, settings: AppSettings, onMenu: () -> Unit) {
    Column(Modifier.fillMaxSize().background(HarukiBg)) {
        HeaderRow("Settings", onMenu)
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SettingsCard("Playback", Icons.Rounded.PlayCircle) {
                Text("Default playback quality", color = HarukiText, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Auto adapts to your current network. A fixed resolution is only used when you choose it.",
                    color = HarukiMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Auto", "2160p", "1440p", "1080p", "720p", "480p", "360p").forEach { q ->
                        FilterChip(
                            selected = settings.playbackQuality == q,
                            onClick = { vm.setPlaybackQuality(q) },
                            label = { Text(q) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = HarukiPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }
                HorizontalDivider(color = HarukiBorderSoft)
                SettingSwitch(
                    "Autoplay next video",
                    "Automatically play the first normal Up Next video when playback ends.",
                    settings.autoplayNext
                ) { vm.setAutoplayNext(it) }
            }

            SettingsCard("Downloads", Icons.Rounded.Download) {
                Text("Download speed", color = HarukiText, style = MaterialTheme.typography.titleMedium)
                Text(
                    when (settings.downloadSpeedMode) {
                        DownloadSpeedMode.AUTO -> "Auto uses full speed when the player is idle, then gives playback more bandwidth when a video is running."
                        DownloadSpeedMode.TURBO -> "Turbo favors download speed and allows more parallel transfer work. Playback still gets emergency priority if it starts buffering."
                        DownloadSpeedMode.PLAYBACK_PRIORITY -> "Playback Priority keeps downloads gentler whenever a video or background audio session is active."
                    },
                    color = HarukiMuted,
                    style = MaterialTheme.typography.bodySmall
                )
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        DownloadSpeedMode.AUTO to "Auto",
                        DownloadSpeedMode.TURBO to "Turbo",
                        DownloadSpeedMode.PLAYBACK_PRIORITY to "Playback Priority"
                    ).forEach { (mode, label) ->
                        FilterChip(
                            selected = settings.downloadSpeedMode == mode,
                            onClick = { vm.setDownloadSpeedMode(mode) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = HarukiPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }
                HorizontalDivider(color = HarukiBorderSoft)
                SettingSwitch(
                    "Wi-Fi only",
                    "Only start queued downloads on unmetered connections.",
                    settings.wifiOnly
                ) { vm.setWifiOnly(it) }
                HorizontalDivider(color = HarukiBorderSoft)
                Text("Default download quality", color = HarukiText, style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Best", "2160p", "1440p", "1080p", "720p", "480p", "360p").forEach { q ->
                        FilterChip(
                            selected = settings.defaultQuality == q,
                            onClick = { vm.setDefaultQuality(q) },
                            label = { Text(q) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = HarukiPrimary,
                                selectedLabelColor = androidx.compose.ui.graphics.Color.White
                            )
                        )
                    }
                }
                HorizontalDivider(color = HarukiBorderSoft)
                SettingSwitch(
                    "Play after download",
                    "Open a completed download automatically.",
                    settings.playAfterDownload
                ) { vm.setPlayAfter(it) }
            }

            SettingsCard("Performance", Icons.Rounded.Bolt) {
                Text("Optimized browsing", color = HarukiText, style = MaterialTheme.typography.titleMedium)
                Text(
                    "Haruki caches thumbnails and video metadata, preloads nearby videos, and loads more results only when you approach the end of the feed.",
                    color = HarukiMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            OutlinedButton(
                onClick = { vm.resetSettings() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = HarukiText)
            ) {
                Icon(Icons.Rounded.RestartAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("Reset settings")
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        color = HarukiCard,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Icon(icon, null, tint = HarukiPrimary)
                Text(title, style = MaterialTheme.typography.titleLarge, color = HarukiText)
            }
            content()
        }
    }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = HarukiText, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = HarukiPrimary
            )
        )
    }
}
