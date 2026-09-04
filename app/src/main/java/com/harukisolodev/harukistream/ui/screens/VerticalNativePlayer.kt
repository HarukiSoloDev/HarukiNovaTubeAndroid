package com.harukisolodev.harukistream.ui.screens

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.media.AudioManager
import androidx.annotation.OptIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.harukisolodev.harukistream.data.AnalyzedMedia
import com.harukisolodev.harukistream.data.AudioTrackOption
import com.harukisolodev.harukistream.data.BrowseVideo
import com.harukisolodev.harukistream.data.DownloadQueueItem
import com.harukisolodev.harukistream.data.DownloadQueueStatus
import com.harukisolodev.harukistream.data.MediaVariant
import com.harukisolodev.harukistream.data.EqualizerPreset
import com.harukisolodev.harukistream.data.shortLabel
import com.harukisolodev.harukistream.player.PlaybackNetworkCoordinator
import com.harukisolodev.harukistream.player.PlaybackDataSourceFactory
import com.harukisolodev.harukistream.player.NovaEqualizerEngine
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.theme.*
import kotlinx.coroutines.delay

/** Isolated player for Shorts. Adjacent pager pages are prepared ahead of time. */
@OptIn(UnstableApi::class)
@Composable
internal fun VerticalNativePlayer(
    item: BrowseVideo,
    media: AnalyzedMedia?,
    loading: Boolean,
    error: String,
    active: Boolean,
    saved: Boolean,
    playbackQualityPreference: String,
    equalizerEnabled: Boolean,
    equalizerPreset: EqualizerPreset,
    equalizerCustomBands: List<Float>,
    commentCount: Int,
    downloadState: DownloadQueueItem?,
    onToggleSave: (BrowseVideo) -> Unit,
    onComments: () -> Unit,
    onDownload: (AnalyzedMedia) -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerPreset: (EqualizerPreset) -> Unit,
    onNotInterested: () -> Unit,
    onDontRecommendChannel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val autoHeight = remember(media?.mediaId) { estimateNetworkQualityHeight(context) }
    val loadControl = remember {
        DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(12_000, 45_000, 1_200, 3_500)
            .setBackBuffer(5_000, true)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
            .build()
    }
    val audioSessionId = remember(item.url) {
        runCatching { context.getSystemService(AudioManager::class.java).generateAudioSessionId() }.getOrDefault(0)
    }
    val player = remember(item.url) {
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                if (audioSessionId > 0) setAudioSessionId(audioSessionId)
                repeatMode = Player.REPEAT_MODE_ONE
                playWhenReady = false
            }
    }
    var playing by remember(item.url) { mutableStateOf(false) }
    // Empty = follow Settings. __AUTO__ = force Auto for this Short.
    var selectedId by remember(media?.mediaId, playbackQualityPreference) { mutableStateOf("") }
    var selectedAudioTrackId by remember(media?.mediaId) { mutableStateOf("") }
    var qualityMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    var equalizerMenu by remember { mutableStateOf(false) }
    var equalizerEngine by remember(item.url) { mutableStateOf<NovaEqualizerEngine?>(null) }
    var showCenterState by remember { mutableStateOf(false) }
    var preparedKey by remember(item.url) { mutableStateOf("") }

    val variants = remember(media?.mediaId, media?.videoVariants) {
        media?.videoVariants.orEmpty().filter { it.videoUrl.isNotBlank() }.sortedByDescending { it.qualityHeight }
    }
    val fixedPreferenceHeight = playbackQualityPreference.removeSuffix("p").toIntOrNull()
    val autoSelected = remember(variants, autoHeight) { chooseAutoVariant(variants, autoHeight) }
    val preferredFixed = remember(variants, fixedPreferenceHeight) {
        if (fixedPreferenceHeight == null) null
        else variants.filter { it.qualityHeight <= fixedPreferenceHeight }.maxByOrNull { it.qualityHeight }
            ?: variants.minByOrNull { kotlin.math.abs(it.qualityHeight - fixedPreferenceHeight) }
    }
    val selected = when {
        selectedId == "__AUTO__" -> autoSelected
        selectedId.isNotBlank() -> variants.firstOrNull { it.id == selectedId } ?: autoSelected
        playbackQualityPreference.equals("Auto", true) -> autoSelected
        else -> preferredFixed ?: autoSelected
    }
    val autoMode = selectedId == "__AUTO__" ||
        (selectedId.isBlank() && playbackQualityPreference.equals("Auto", true))
    val qualityLabel = if (autoMode) "Auto • ${selected?.label ?: "Best"}" else selected?.label ?: playbackQualityPreference
    val audioTracks = media?.audioTracks.orEmpty()
    val selectedAudio = audioTracks.firstOrNull { it.id == selectedAudioTrackId }
        ?: audioTracks.firstOrNull { it.original }
        ?: audioTracks.firstOrNull()
    val playbackVariant = selected?.let { variant ->
        if (variant.separateAudio && selectedAudio != null) variant.copy(audioUrl = selectedAudio.url) else variant
    }
    LaunchedEffect(media?.mediaId, audioTracks) {
        if (audioTracks.isNotEmpty() && audioTracks.none { it.id == selectedAudioTrackId }) {
            selectedAudioTrackId = (audioTracks.firstOrNull { it.original } ?: audioTracks.first()).id
        }
    }
    val currentActive by rememberUpdatedState(active)

    DisposableEffect(active, audioSessionId) {
        if (active && audioSessionId > 0) {
            equalizerEngine = NovaEqualizerEngine(audioSessionId).also { engine ->
                val curve = if (equalizerPreset == EqualizerPreset.CUSTOM) equalizerCustomBands else equalizerPreset.bandsDb
                engine.applyCurve(equalizerEnabled, curve)
            }
        }
        onDispose {
            equalizerEngine?.release()
            equalizerEngine = null
        }
    }

    LaunchedEffect(active, equalizerEnabled, equalizerPreset, equalizerCustomBands) {
        if (active) {
            val curve = if (equalizerPreset == EqualizerPreset.CUSTOM) equalizerCustomBands else equalizerPreset.bandsDb
            equalizerEngine?.applyCurve(equalizerEnabled, curve)
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (currentActive) PlaybackNetworkCoordinator.update(isPlaying, player.playbackState)
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (currentActive) PlaybackNetworkCoordinator.update(player.isPlaying, playbackState)
            }
        }
        player.addListener(listener)
        onDispose {
            if (currentActive) PlaybackNetworkCoordinator.reset()
            player.removeListener(listener)
            player.release()
        }
    }

    // Prepare this page even when it is the adjacent, off-screen pager page. That
    // gives the next Short a real buffer before the user swipes to it.
    LaunchedEffect(playbackVariant?.id, playbackVariant?.audioUrl, media?.mediaId) {
        val variant = playbackVariant ?: return@LaunchedEffect
        val currentMedia = media ?: return@LaunchedEffect
        val newKey = "${currentMedia.mediaId}|${variant.id}|${variant.audioUrl}"
        if (newKey != preparedKey) {
            val sameMedia = player.currentMediaItem?.mediaId == currentMedia.mediaId
            val resume = if (sameMedia) player.currentPosition.coerceAtLeast(0L) else 0L
            player.setMediaSource(buildVerticalSource(context, currentMedia, variant))
            player.prepare()
            preparedKey = newKey
            if (resume > 0L) player.seekTo(resume)
        }
        if (active) player.play() else player.pause()
    }

    LaunchedEffect(active) {
        if (active && media != null && selected != null) player.play() else player.pause()
    }

    val downloadActive = downloadState?.status in setOf(DownloadQueueStatus.QUEUED, DownloadQueueStatus.RUNNING, DownloadQueueStatus.PAUSED)
    val downloaded = downloadState?.status == DownloadQueueStatus.SUCCEEDED
    val downloadProgress = downloadState?.progress?.coerceIn(0, 100) ?: 0

    Box(
        modifier
            .background(Color.Black)
            .pointerInput(item.url) {
                detectTapGestures(
                    onTap = {
                        if (player.isPlaying) player.pause() else player.play()
                        showCenterState = true
                    },
                    onDoubleTap = { offset ->
                        val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                        player.seekTo((player.currentPosition + delta).coerceAtLeast(0L))
                        showCenterState = true
                    }
                )
            }
    ) {
        if (item.thumbnailUrl.isNotBlank()) RemoteImage(item.thumbnailUrl, Modifier.fillMaxSize())
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                }
            },
            update = { it.player = player },
            modifier = Modifier.fillMaxSize()
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = .12f), Color.Transparent, Color.Black.copy(alpha = .78f)))
            )
        )

        if (loading && media == null) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(42.dp))
        }
        val safeError = error.takeUnless { it.contains("cancel", true) }.orEmpty()
        if (safeError.isNotBlank() && media == null) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(28.dp),
                color = Color.Black.copy(alpha = .72f),
                shape = RoundedCornerShape(18.dp)
            ) { Text(safeError, color = Color.White, modifier = Modifier.padding(16.dp)) }
        }

        if (showCenterState) {
            LaunchedEffect(playing, showCenterState) {
                delay(600)
                showCenterState = false
            }
            Box(
                Modifier.align(Alignment.Center).size(62.dp).clip(CircleShape).background(Color.Black.copy(alpha = .46f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.size(36.dp))
            }
        }

        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 14.dp, end = 80.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                if (item.uploaderAvatarUrl.isNotBlank()) {
                    RemoteImage(item.uploaderAvatarUrl, Modifier.size(38.dp).clip(CircleShape))
                } else {
                    Box(Modifier.size(38.dp).clip(CircleShape).background(HarukiCard2), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Person, null, tint = Color.White)
                    }
                }
                Text(
                    buildString { append(item.uploader.ifBlank { "YouTube" }); if (item.uploaderVerified) append(" ✓") },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(media?.title ?: item.title, color = Color.White, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            ShortAction(
                icon = if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                label = if (saved) "Saved" else "Save",
                selected = saved
            ) { onToggleSave(item) }

            ShortAction(
                icon = Icons.AutoMirrored.Rounded.Comment,
                label = if (commentCount > 0) compactShortCount(commentCount.toLong()) else "Comments"
            ) { onComments() }

            if (audioTracks.isNotEmpty()) {
                Box {
                    ShortAction(Icons.AutoMirrored.Rounded.VolumeUp, shortAudioLabel(selectedAudio)) { audioMenu = true }
                    DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }, containerColor = HarukiCard2) {
                        audioTracks.take(20).forEach { audio ->
                            DropdownMenuItem(
                                text = { Text(audio.label, color = HarukiText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                trailingIcon = { if (selectedAudio?.id == audio.id) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                onClick = { selectedAudioTrackId = audio.id; audioMenu = false }
                            )
                        }
                    }
                }
            }

            Box {
                ShortAction(
                    Icons.Rounded.GraphicEq,
                    if (equalizerEnabled) equalizerPreset.shortLabel else "EQ",
                    selected = equalizerEnabled
                ) { equalizerMenu = true }
                DropdownMenu(expanded = equalizerMenu, onDismissRequest = { equalizerMenu = false }, containerColor = HarukiCard2) {
                    DropdownMenuItem(
                        text = { Text("Equalizer off", color = HarukiText) },
                        trailingIcon = { if (!equalizerEnabled) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                        onClick = { onEqualizerEnabled(false); equalizerMenu = false }
                    )
                    EqualizerPreset.selectable.forEach { preset ->
                        DropdownMenuItem(
                            text = { Text(if (preset.popularChoice) "${preset.displayName}  •  Popular" else preset.displayName, color = HarukiText) },
                            trailingIcon = { if (equalizerEnabled && equalizerPreset == preset) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                            onClick = { onEqualizerPreset(preset); equalizerMenu = false }
                        )
                    }
                }
            }

            Box {
                ShortAction(Icons.Rounded.HighQuality, qualityLabel) { qualityMenu = true }
                DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }, containerColor = HarukiCard2) {
                    DropdownMenuItem(
                        text = { Text("Auto (${autoSelected?.label ?: "Best"})", color = HarukiText) },
                        trailingIcon = { if (autoMode) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                        onClick = { selectedId = "__AUTO__"; qualityMenu = false }
                    )
                    variants.distinctBy { it.qualityHeight }.take(10).forEach { option ->
                        val optionSelected = selected?.id == option.id && !autoMode
                        DropdownMenuItem(
                            text = { Text(option.label, color = HarukiText) },
                            trailingIcon = { if (optionSelected) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                            onClick = { selectedId = option.id; qualityMenu = false }
                        )
                    }
                }
            }

            Box {
                ShortAction(Icons.Rounded.MoreVert, "More") { moreMenu = true }
                DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }, containerColor = HarukiCard2) {
                    DropdownMenuItem(
                        text = { Text("Not interested", color = HarukiText) },
                        leadingIcon = { Icon(Icons.Rounded.VisibilityOff, null, tint = HarukiMuted) },
                        onClick = { moreMenu = false; onNotInterested() }
                    )
                    DropdownMenuItem(
                        text = { Text("Don't recommend this channel", color = HarukiText) },
                        leadingIcon = { Icon(Icons.Rounded.PersonOff, null, tint = HarukiMuted) },
                        onClick = { moreMenu = false; onDontRecommendChannel() }
                    )
                }
            }

            ShortAction(Icons.Rounded.Share, "Share") {
                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, item.url)
                }, "Share Short"))
            }

            if (media != null) {
                ShortAction(
                    icon = if (downloaded) Icons.Rounded.Check else Icons.Rounded.Download,
                    label = when {
                        downloaded -> "Done"
                        downloadActive && downloadProgress > 0 -> "$downloadProgress%"
                        downloadActive -> "Queued"
                        else -> "Download"
                    },
                    selected = downloadActive || downloaded,
                    progress = if (downloadActive) downloadProgress else null
                ) { if (!downloadActive) onDownload(media) }
            }
        }
    }
}

@Composable
private fun ShortAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean = false,
    progress: Int? = null,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Box(contentAlignment = Alignment.Center) {
            SmallFloatingActionButton(
                onClick = onClick,
                containerColor = if (selected) HarukiPrimary.copy(alpha = .85f) else Color.Black.copy(alpha = .50f),
                contentColor = Color.White,
                modifier = Modifier.then(
                    if (selected) Modifier.clip(CircleShape) else Modifier
                )
            ) { Icon(icon, label) }
            if (progress != null) {
                CircularProgressIndicator(
                    progress = { progress.coerceIn(0, 100) / 100f },
                    modifier = Modifier.size(46.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = .22f),
                    strokeWidth = 2.5.dp
                )
            }
        }
        Text(label, color = if (selected) HarukiPrimary else Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@Composable
private fun rememberAutoQualityHeight(): Int {
    val context = LocalContext.current
    var height by remember { mutableIntStateOf(estimateNetworkQualityHeight(context)) }
    LaunchedEffect(context) {
        var pending = height
        var stableSamples = 0
        while (true) {
            val candidate = estimateNetworkQualityHeight(context)
            if (candidate == pending) stableSamples++ else { pending = candidate; stableSamples = 1 }
            if (stableSamples >= 2 && candidate != height) height = candidate
            delay(5_000)
        }
    }
    return height
}

@OptIn(UnstableApi::class)
private fun estimateNetworkQualityHeight(context: Context): Int {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 480
    val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val measuredBps = DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate
    val linkBps = caps.linkDownstreamBandwidthKbps.toLong().coerceAtLeast(0L) * 1_000L
    val effectiveBps = if (measuredBps >= 1_200_000L) measuredBps else linkBps.coerceAtLeast(measuredBps)
    return when {
        effectiveBps >= 10_000_000L && !cellular -> 720
        effectiveBps >= 4_000_000L -> 480
        else -> 360
    }
}

private fun chooseAutoVariant(variants: List<MediaVariant>, targetHeight: Int): MediaVariant? =
    variants.filter { it.qualityHeight > 0 && it.qualityHeight <= targetHeight }.maxByOrNull { it.qualityHeight }
        ?: variants.filter { it.qualityHeight > 0 }.minByOrNull { it.qualityHeight }
        ?: variants.firstOrNull()

private fun shortAudioLabel(option: AudioTrackOption?): String = when {
    option == null -> "Audio"
    option.dubbed -> "Dubbed"
    option.descriptive -> "Description"
    option.original -> "Original"
    option.label.contains("alternate", true) || option.label.contains("secondary", true) -> "Alternate"
    else -> option.languageCode.substringBefore('-').uppercase().ifBlank { "Audio" }
}

private fun compactShortCount(value: Long): String = when {
    value >= 1_000_000_000L -> "%.1fB".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000L -> "%.1fK".format(value / 1_000.0)
    else -> value.toString()
}

@OptIn(UnstableApi::class)
private fun buildVerticalSource(context: Context, media: AnalyzedMedia, variant: MediaVariant): androidx.media3.exoplayer.source.MediaSource {
    val headers = variant.requestHeaders
    val meter = DefaultBandwidthMeter.getSingletonInstance(context)
    val factory = DefaultMediaSourceFactory(PlaybackDataSourceFactory.create(context, meter, headers))
        .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(5))
    val metadata = MediaMetadata.Builder()
        .setTitle(media.title)
        .apply { if (media.thumbnailUrl.isNotBlank()) setArtworkUri(Uri.parse(media.thumbnailUrl)) }
        .build()
    val videoItem = MediaItem.Builder()
        .setMediaId(media.mediaId)
        .setUri(variant.videoUrl)
        .setMimeType(variant.mimeType.ifBlank { "video/mp4" })
        .setCustomCacheKey("short-video:${media.mediaId}:${variant.id}")
        .setMediaMetadata(metadata)
        .build()
    val video = factory.createMediaSource(videoItem)
    if (variant.audioUrl.isBlank()) return video
    val audio = factory.createMediaSource(
        MediaItem.Builder()
            .setMediaId("${media.mediaId}-audio")
            .setUri(variant.audioUrl)
            .setMimeType(media.audioTracks.firstOrNull { it.url == variant.audioUrl }?.mimeType ?: "audio/mp4")
            .setCustomCacheKey("short-audio:${media.mediaId}:${variant.audioUrl.hashCode()}")
            .build()
    )
    return MergingMediaSource(true, true, video, audio)
}
