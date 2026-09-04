package com.harukisolodev.harukistream.ui.screens

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.harukisolodev.harukistream.data.AppSettings
import com.harukisolodev.harukistream.data.EqualizerPreset
import com.harukisolodev.harukistream.data.DownloadQueueItem
import com.harukisolodev.harukistream.data.DownloadQueueStatus
import com.harukisolodev.harukistream.data.LibraryItem
import com.harukisolodev.harukistream.ui.HarukiViewModel
import com.harukisolodev.harukistream.player.NovaEqualizerEngine
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.components.formatBytes
import com.harukisolodev.harukistream.ui.components.formatSpeed
import com.harukisolodev.harukistream.ui.theme.*
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun DownloadsScreen(
    vm: HarukiViewModel,
    queue: List<DownloadQueueItem>,
    library: List<LibraryItem>,
    settings: AppSettings,
    onMenu: () -> Unit
) {
    var playingItem by remember { mutableStateOf<LibraryItem?>(null) }
    val active = queue.filter { it.status == DownloadQueueStatus.RUNNING || it.status == DownloadQueueStatus.QUEUED || it.status == DownloadQueueStatus.PAUSED }
    val finished = queue.filter { it.status == DownloadQueueStatus.SUCCEEDED || it.status == DownloadQueueStatus.FAILED || it.status == DownloadQueueStatus.CANCELLED }
    val runningCount = active.count { it.status == DownloadQueueStatus.RUNNING }
    val totalActiveSpeed = active.sumOf { it.speedBps.coerceAtLeast(0L) }

    Column(Modifier.fillMaxSize().background(HarukiBg)) {
        HeaderRow("Downloads", onMenu)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "engine") {
                DownloadEngineCard(totalActiveSpeed, runningCount)
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Downloading (${active.size})", style = MaterialTheme.typography.titleMedium, color = HarukiText, modifier = Modifier.weight(1f))
                    if (finished.isNotEmpty()) TextButton(onClick = { vm.clearFinishedDownloads() }) { Text("Clear finished", color = HarukiPrimary) }
                }
            }
            if (active.isEmpty()) item { EmptyDownloadCard("No active downloads") }
            items(active, key = { it.queueId }) { DownloadQueueCard(it, vm) }
            if (library.isNotEmpty()) {
                item { Text("Completed (${library.size})", style = MaterialTheme.typography.titleMedium, color = HarukiText, modifier = Modifier.padding(top = 10.dp)) }
                items(library.take(40), key = { "lib-${it.id}" }) { LibraryDownloadCard(it, onPlay = { playingItem = it }) }
            }
            if (finished.isNotEmpty()) {
                item { Text("Recent queue", style = MaterialTheme.typography.titleMedium, color = HarukiText, modifier = Modifier.padding(top = 10.dp)) }
                items(finished.take(12), key = { "done-${it.queueId}" }) { DownloadQueueCard(it, vm) }
            }
        }
    }

    playingItem?.let { item ->
        DownloadedPlayerDialog(item = item, settings = settings, onDismiss = { playingItem = null })
    }
}

@Composable
private fun DownloadEngineCard(totalSpeed: Long, runningCount: Int) {
    Surface(
        color = HarukiCardSoft,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiPrimary.copy(alpha = .28f))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(color = HarukiPrimary.copy(alpha = .14f), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Rounded.Bolt, null, tint = HarukiPrimary, modifier = Modifier.padding(10.dp).size(22.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Performance download engine", color = HarukiText, fontWeight = FontWeight.Bold)
                Text(
                    if (runningCount > 0) "$runningCount active • playback-safe smart chunks" else "Smart 8 MB chunks • playback-safe scheduling",
                    color = HarukiMuted,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (totalSpeed > 0L) {
                Surface(color = HarukiPrimary.copy(alpha = .12f), shape = RoundedCornerShape(999.dp)) {
                    Text(formatSpeed(totalSpeed), color = HarukiPrimary, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun DownloadQueueCard(item: DownloadQueueItem, vm: HarukiViewModel) {
    Surface(color = HarukiCard, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                RemoteImage(item.thumbnailUrl, Modifier.width(92.dp).aspectRatio(16f / 9f))
                Column(Modifier.weight(1f)) {
                    Text(item.title, color = HarukiText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${item.quality} • ${item.statusText}", color = HarukiMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    val sizeLine = buildList {
                        if (item.bytesDownloaded > 0 && item.totalBytes > 0) {
                            add("${formatBytes(item.bytesDownloaded)} / ${formatBytes(item.totalBytes)}")
                        } else if (item.bytesDownloaded > 0) {
                            add(formatBytes(item.bytesDownloaded))
                        }
                        if (item.speedBps > 0) add(formatSpeed(item.speedBps))
                        if (item.speedBps > 0 && item.totalBytes > item.bytesDownloaded) {
                            val eta = (item.totalBytes - item.bytesDownloaded) / item.speedBps.coerceAtLeast(1L)
                            if (eta in 1..86_400) add("${formatEta(eta)} left")
                        }
                    }.joinToString(" • ")
                    if (sizeLine.isNotBlank()) Text(sizeLine, color = HarukiMuted2, style = MaterialTheme.typography.labelSmall)
                }
                when (item.status) {
                    DownloadQueueStatus.RUNNING -> IconButton(onClick = { vm.pauseDownload(item.queueId) }) { Icon(Icons.Rounded.Pause, "Pause", tint = HarukiText) }
                    DownloadQueueStatus.PAUSED -> IconButton(onClick = { vm.resumeDownload(item.queueId) }) { Icon(Icons.Rounded.PlayArrow, "Resume", tint = HarukiPrimary) }
                    DownloadQueueStatus.FAILED, DownloadQueueStatus.CANCELLED -> IconButton(onClick = { vm.resumeDownload(item.queueId) }) { Icon(Icons.Rounded.Refresh, "Retry", tint = HarukiPrimary) }
                    else -> Unit
                }
            }
            if (item.status == DownloadQueueStatus.RUNNING || item.status == DownloadQueueStatus.QUEUED) {
                LinearProgressIndicator(
                    progress = { item.progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth(),
                    color = HarukiPrimary,
                    trackColor = HarukiCard2
                )
            }
            if (item.status == DownloadQueueStatus.RUNNING || item.status == DownloadQueueStatus.QUEUED || item.status == DownloadQueueStatus.PAUSED) {
                TextButton(onClick = { vm.cancelDownload(item.queueId) }) { Text("Cancel", color = HarukiDanger) }
            }
        }
    }
}

@Composable
private fun LibraryDownloadCard(item: LibraryItem, onPlay: (LibraryItem) -> Unit) {
    Surface(color = HarukiCard, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
        Row(Modifier.fillMaxWidth().padding(11.dp), horizontalArrangement = Arrangement.spacedBy(11.dp), verticalAlignment = Alignment.CenterVertically) {
            RemoteImage(item.thumbnailUrl, Modifier.width(110.dp).aspectRatio(16f / 9f))
            Column(Modifier.weight(1f)) {
                Text(item.title, color = HarukiText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.quality, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { onPlay(item) }) {
                Icon(Icons.Rounded.PlayCircle, "Play download", tint = HarukiPrimary)
            }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun DownloadedPlayerDialog(item: LibraryItem, settings: AppSettings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val audioSessionId = remember(item.uri) {
        runCatching { context.getSystemService(AudioManager::class.java).generateAudioSessionId() }.getOrDefault(0)
    }
    val player = remember(item.uri) {
        ExoPlayer.Builder(context).build().apply {
            if (audioSessionId > 0) setAudioSessionId(audioSessionId)
            setMediaItem(MediaItem.fromUri(item.uri))
            prepare()
            playWhenReady = true
        }
    }
    val equalizerEngine = remember(item.uri, audioSessionId) { NovaEqualizerEngine(audioSessionId) }
    LaunchedEffect(settings.equalizerEnabled, settings.equalizerPreset, settings.equalizerCustomBands) {
        val curve = if (settings.equalizerPreset == EqualizerPreset.CUSTOM) settings.equalizerCustomBands else settings.equalizerPreset.bandsDb
        equalizerEngine.applyCurve(settings.equalizerEnabled, curve)
    }
    DisposableEffect(player, equalizerEngine) {
        onDispose {
            equalizerEngine.release()
            player.release()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color.Black,
            shape = RoundedCornerShape(18.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorder)
        ) {
            Column {
                Row(
                    Modifier.fillMaxWidth().background(HarukiCard).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(item.title, color = HarukiText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Close player", tint = HarukiText) }
                }
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        }
                    },
                    update = { it.player = player },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
                )
            }
        }
    }
}

private fun formatEta(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val hours = safe / 3600L
    val minutes = (safe % 3600L) / 60L
    val secs = safe % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

@Composable
private fun EmptyDownloadCard(text: String) {
    Surface(color = HarukiCardSoft, shape = RoundedCornerShape(18.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Download, null, tint = HarukiMuted)
            Spacer(Modifier.width(10.dp))
            Text(text, color = HarukiMuted)
        }
    }
}

@Composable
fun HeaderRow(title: String, onMenu: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().height(52.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu, modifier = Modifier.size(42.dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarukiText) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = HarukiText)
    }
}
