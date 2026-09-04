package com.harukisolodev.harukistream.ui.screens

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Comment
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.text.CueGroup
import androidx.media3.session.MediaController
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.session.SessionToken
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.harukisolodev.harukistream.data.*
import com.harukisolodev.harukistream.player.PlaybackCommands
import com.harukisolodev.harukistream.player.PlaybackService
import com.harukisolodev.harukistream.player.PlaybackSelectionStore
import com.harukisolodev.harukistream.ui.HarukiViewModel
import com.harukisolodev.harukistream.ui.NovaAdaptiveInfo
import com.harukisolodev.harukistream.ui.components.LinkifiedText
import com.harukisolodev.harukistream.ui.components.RemoteImage
import com.harukisolodev.harukistream.ui.components.premiumClickable
import com.harukisolodev.harukistream.ui.components.formatDuration
import com.harukisolodev.harukistream.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WatchScreen(
    watch: WatchState,
    downloadVm: HarukiViewModel,
    downloadState: DownloadQueueItem?,
    downloaded: Boolean,
    saved: Boolean,
    playlists: List<LocalPlaylist>,
    adaptive: NovaAdaptiveInfo,
    autoplayNext: Boolean,
    playbackQualityPreference: String,
    equalizerEnabled: Boolean,
    equalizerPreset: EqualizerPreset,
    onAutoplayChanged: (Boolean) -> Unit,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerPreset: (EqualizerPreset) -> Unit,
    onToggleSave: (BrowseVideo) -> Unit,
    onCreatePlaylist: (String) -> LocalPlaylist,
    onAddToPlaylist: (String, BrowseVideo) -> Unit,
    onDownloadQueued: (BrowseVideo) -> Unit,
    onOpenComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onLoadMoreRelated: () -> Unit,
    onBack: () -> Unit,
    onMinimize: () -> Unit,
    onOpenRelated: (BrowseVideo) -> Unit,
    onOpenChannel: (String, String, String) -> Unit
) {
    val context = LocalContext.current
    val playerWidthModifier = if (adaptive.useNavigationRail) {
        Modifier.fillMaxWidth().widthIn(max = if (adaptive.largeTouchTargets) 840.dp else 1040.dp)
    } else Modifier.fillMaxWidth()
    val activity = context as? Activity
    val item = watch.item
    var showDownload by remember { mutableStateOf(false) }
    var showDescription by remember { mutableStateOf(false) }
    var showComments by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(1f) }
    // "" follows the Settings default, "__AUTO__" forces Auto for this video,
    // otherwise the value is a concrete MediaVariant id selected by the user.
    val playbackSelectionMediaId = watch.media?.mediaId.orEmpty()
    var manualQualityId by remember(playbackSelectionMediaId, playbackQualityPreference) {
        mutableStateOf(PlaybackSelectionStore.qualityFor(playbackSelectionMediaId))
    }
    var subtitleId by remember(watch.media?.mediaId) { mutableStateOf("") }
    var audioTrackId by remember(watch.media?.mediaId) { mutableStateOf("") }
    val autoTargetHeight = remember(watch.media?.mediaId) { estimateWatchNetworkHeight(context) }
    val recommendationListState = rememberLazyListState()

    val variants = remember(watch.media?.mediaId, watch.media?.videoVariants) {
        watch.media?.videoVariants.orEmpty()
            .filter { it.videoUrl.isNotBlank() }
            .sortedWith(compareByDescending<MediaVariant> { it.qualityHeight }
                .thenBy { if (it.fps > 0) it.fps else 30 }
                .thenBy { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
                .thenBy { it.separateAudio })
    }
    val fixedPreferenceHeight = playbackQualityPreference.removeSuffix("p").toIntOrNull()
    val autoVariant = remember(variants, autoTargetHeight) { chooseWatchAutoVariant(variants, autoTargetHeight) }
    val preferredFixed = remember(variants, fixedPreferenceHeight) {
        if (fixedPreferenceHeight == null) null
        else variants.filter { it.qualityHeight <= fixedPreferenceHeight }
            .sortedWith(compareByDescending<MediaVariant> { it.qualityHeight }
                .thenBy { if (it.fps > 0) it.fps else 30 }
                .thenBy { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
                .thenBy { it.separateAudio })
            .firstOrNull() ?: variants.minByOrNull { kotlin.math.abs(it.qualityHeight - fixedPreferenceHeight) }
    }
    val selectedVariant = when {
        manualQualityId == "__AUTO__" -> autoVariant
        manualQualityId.isNotBlank() -> variants.firstOrNull { it.id == manualQualityId } ?: autoVariant
        playbackQualityPreference.equals("Auto", true) -> autoVariant
        else -> preferredFixed ?: autoVariant
    }
    val playerAutoMode = manualQualityId == "__AUTO__" ||
        (manualQualityId.isBlank() && playbackQualityPreference.equals("Auto", true))
    val audioTracks = watch.media?.audioTracks.orEmpty()
    val selectedAudio = audioTracks.firstOrNull { it.id == audioTrackId }
        ?: audioTracks.firstOrNull { it.original }
        ?: audioTracks.firstOrNull()
    val playbackVariant = selectedVariant?.let { variant ->
        val needsExternalAudio = selectedAudio != null && !selectedAudio.original
        val sourceVariant = if (needsExternalAudio && !variant.separateAudio) {
            variants.firstOrNull { sibling ->
                sibling.separateAudio && sibling.qualityHeight == variant.qualityHeight
            } ?: variant
        } else variant
        if (sourceVariant.separateAudio && selectedAudio != null) sourceVariant.copy(audioUrl = selectedAudio.url)
        else sourceVariant
    }
    LaunchedEffect(watch.media?.mediaId, audioTracks) {
        if (audioTracks.isNotEmpty() && audioTracks.none { it.id == audioTrackId }) {
            audioTrackId = (audioTracks.firstOrNull { it.original } ?: audioTracks.first()).id
        }
    }
    val previousVideo = watch.collectionPrevious
    val nextVideo = remember(watch.collectionNext, watch.related, item?.url) {
        watch.collectionNext ?: watch.related.firstOrNull { related ->
            related.url != item?.url && !related.shortForm && !related.url.contains("/shorts/", true)
        }
    }
    val upNextItems = remember(watch.collectionItems, watch.collectionNext, watch.related, item?.url) {
        val playlistUrls = watch.collectionItems.map { it.url }.toSet()
        val source = if (watch.collectionItems.isNotEmpty()) watch.related else listOfNotNull(watch.collectionNext) + watch.related
        source
            .filter { it.url != item?.url && it.url !in playlistUrls && !it.shortForm && !it.url.contains("/shorts/", true) }
            .distinctBy { it.url }
    }
    val backgroundAutoplayQueue = remember(
        watch.collectionItems,
        watch.collectionPosition,
        watch.related,
        nextVideo,
        item?.url
    ) {
        val playlistRemaining = if (watch.collectionItems.isNotEmpty() && watch.collectionPosition >= 0) {
            watch.collectionItems.drop(watch.collectionPosition + 1)
        } else emptyList()
        val discovery = listOfNotNull(nextVideo) + watch.related
        (playlistRemaining + discovery)
            .asSequence()
            .filter { it.url != item?.url && !it.shortForm && !it.url.contains("/shorts/", true) }
            .distinctBy { it.url }
            .take(40)
            .toList()
    }
    LaunchedEffect(recommendationListState, watch.related.size, watch.item?.url) {
        snapshotFlow {
            val info = recommendationListState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last to info.totalItemsCount
        }.collect { (last, total) ->
            if (total > 0 && last >= total - 5 && !watch.relatedLoadingMore && watch.relatedHasMore) {
                onLoadMoreRelated()
            }
        }
    }

    val downloadActive = downloadState?.status in setOf(DownloadQueueStatus.QUEUED, DownloadQueueStatus.RUNNING, DownloadQueueStatus.PAUSED)

    BackHandler(enabled = fullscreen) { fullscreen = false }

    DisposableEffect(fullscreen, activity) {
        if (!fullscreen || activity == null) return@DisposableEffect onDispose { }
        val previousOrientation = activity.requestedOrientation
        val window = activity.window
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.isAppearanceLightStatusBars = false
        insets.isAppearanceLightNavigationBars = false
        insets.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.decorView.postDelayed({
            WindowCompat.getInsetsController(window, window.decorView).hide(WindowInsetsCompat.Type.systemBars())
        }, 180L)
        onDispose {
            insets.show(WindowInsetsCompat.Type.systemBars())
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            activity.requestedOrientation = previousOrientation
        }
    }

    fun shareVideo() {
        val url = watch.media?.sourceUrl ?: item?.url.orEmpty()
        if (url.isBlank()) return
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
            putExtra(Intent.EXTRA_TITLE, watch.media?.title ?: item?.title.orEmpty())
        }, "Share video"))
    }

    if (fullscreen && playbackVariant != null && item != null) {
        HarukiWatchPlayer(
            variant = playbackVariant,
            mediaId = watch.media?.mediaId ?: item.id,
            pageUrl = watch.media?.sourceUrl?.ifBlank { item.url } ?: item.url,
            title = watch.media?.title ?: item.title,
            uploader = watch.media?.uploader ?: item.uploader,
            artwork = watch.media?.thumbnailUrl ?: item.thumbnailUrl,
            modifier = Modifier.fillMaxSize().background(Color.Black),
            allowMinimize = false,
            fullscreen = true,
            speed = speed,
            qualityOptions = variants,
            qualityModeLabel = currentQualityLabel(playbackQualityPreference, manualQualityId, selectedVariant, autoVariant),
            selectedQualityId = manualQualityId,
            autoModeSelected = playerAutoMode,
            explicitQualitySelection = manualQualityId.isNotBlank() || (selectedAudio != null && !selectedAudio.original),
            subtitleOptions = watch.details.subtitles,
            selectedSubtitleId = subtitleId,
            audioOptions = audioTracks,
            selectedAudioTrackId = selectedAudio?.id.orEmpty(),
            equalizerEnabled = equalizerEnabled,
            equalizerPreset = equalizerPreset,
            onEqualizerEnabled = onEqualizerEnabled,
            onEqualizerPreset = onEqualizerPreset,
            onQualitySelected = { selection ->
                manualQualityId = selection
                PlaybackSelectionStore.setQuality(playbackSelectionMediaId, selection)
            },
            onSubtitleSelected = { subtitleId = it },
            onAudioSelected = { audioTrackId = it },
            onSpeedSelected = { speed = it },
            onMinimize = {},
            onFullscreen = { fullscreen = false },
            onBack = { fullscreen = false },
            previousAvailable = previousVideo != null,
            nextAvailable = nextVideo != null,
            onPrevious = { previousVideo?.let(onOpenRelated) },
            onNext = { nextVideo?.let(onOpenRelated) },
            autoplayNextAvailable = autoplayNext && nextVideo != null,
            nextTitle = nextVideo?.title.orEmpty(),
            autoplayQueue = backgroundAutoplayQueue
        )
        return
    }

    Column(
        Modifier.fillMaxSize().background(HarukiBg).statusBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(42.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = HarukiText)
            }
            Text("YouTube", color = HarukiText, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                onMinimize()
            }, modifier = Modifier.size(42.dp)) {
                Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = HarukiMuted)
            }
        }

        when {
            watch.loading -> Box(
                playerWidthModifier.aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    CircularProgressIndicator(color = HarukiPrimary)
                    Text("Preparing video…", color = Color.White.copy(alpha = .78f), style = MaterialTheme.typography.bodySmall)
                }
            }
            playbackVariant != null && item != null -> HarukiWatchPlayer(
                variant = playbackVariant,
                mediaId = watch.media?.mediaId ?: item.id,
                pageUrl = watch.media?.sourceUrl?.ifBlank { item.url } ?: item.url,
                title = watch.media?.title ?: item.title,
                uploader = watch.media?.uploader ?: item.uploader,
                artwork = watch.media?.thumbnailUrl ?: item.thumbnailUrl,
                modifier = playerWidthModifier.aspectRatio(16f / 9f),
                allowMinimize = true,
                fullscreen = false,
                speed = speed,
                qualityOptions = variants,
                qualityModeLabel = currentQualityLabel(playbackQualityPreference, manualQualityId, selectedVariant, autoVariant),
                selectedQualityId = manualQualityId,
                autoModeSelected = playerAutoMode,
                explicitQualitySelection = manualQualityId.isNotBlank() || (selectedAudio != null && !selectedAudio.original),
                subtitleOptions = watch.details.subtitles,
                selectedSubtitleId = subtitleId,
                audioOptions = audioTracks,
                selectedAudioTrackId = selectedAudio?.id.orEmpty(),
                equalizerEnabled = equalizerEnabled,
                equalizerPreset = equalizerPreset,
                onEqualizerEnabled = onEqualizerEnabled,
                onEqualizerPreset = onEqualizerPreset,
                onQualitySelected = { selection ->
                    manualQualityId = selection
                    PlaybackSelectionStore.setQuality(playbackSelectionMediaId, selection)
                },
                onSubtitleSelected = { subtitleId = it },
                onAudioSelected = { audioTrackId = it },
                onSpeedSelected = { speed = it },
                onMinimize = {
                    onMinimize()
                },
                onFullscreen = { fullscreen = true },
                onBack = {},
                previousAvailable = previousVideo != null,
                nextAvailable = nextVideo != null,
                onPrevious = { previousVideo?.let(onOpenRelated) },
                onNext = { nextVideo?.let(onOpenRelated) },
                autoplayNextAvailable = autoplayNext && nextVideo != null,
                nextTitle = nextVideo?.title.orEmpty(),
                autoplayQueue = backgroundAutoplayQueue
            )
            else -> Box(
                playerWidthModifier.aspectRatio(16f / 9f).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Text(watch.error.ifBlank { "Video is unavailable." }, color = Color.White.copy(alpha = .8f), modifier = Modifier.padding(22.dp))
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = recommendationListState,
            contentPadding = PaddingValues(bottom = 100.dp)
        ) {
            item(key = "video-info") {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        watch.media?.title ?: item?.title.orEmpty(),
                        color = HarukiText,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    val detailsMeta = buildList {
                        if (watch.details.viewCount >= 0) add(compactCount(watch.details.viewCount) + " views")
                        if (watch.details.uploadText.isNotBlank()) add(watch.details.uploadText)
                    }.joinToString(" • ")
                    if (detailsMeta.isNotBlank()) Text(detailsMeta, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                    if (watch.collectionName.isNotBlank()) {
                        Surface(color = HarukiCardSoft, shape = RoundedCornerShape(10.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)) {
                            Text(
                                buildString {
                                    append("Playlist • ")
                                    append(watch.collectionName)
                                    if (watch.collectionPosition >= 0 && watch.collectionSize > 0) append(" • ${watch.collectionPosition + 1}/${watch.collectionSize}")
                                },
                                color = HarukiMuted,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(enabled = watch.details.uploaderUrl.isNotBlank()) {
                            onOpenChannel(
                                watch.details.uploaderUrl,
                                watch.media?.uploader ?: item?.uploader.orEmpty(),
                                watch.details.uploaderAvatarUrl.ifBlank { item?.uploaderAvatarUrl.orEmpty() }
                            )
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        val avatar = watch.details.uploaderAvatarUrl.ifBlank { item?.uploaderAvatarUrl.orEmpty() }
                        if (avatar.isNotBlank()) RemoteImage(avatar, Modifier.size(44.dp).clip(CircleShape))
                        else Box(Modifier.size(44.dp).clip(CircleShape).background(HarukiCard2), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Person, null, tint = HarukiMuted)
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                buildString {
                                    append(watch.media?.uploader ?: item?.uploader.orEmpty())
                                    if (watch.details.uploaderVerified || item?.uploaderVerified == true) append(" ✓")
                                },
                                color = HarukiText,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (watch.details.subscriberCount >= 0) {
                                Text("${compactCount(watch.details.subscriberCount)} subscribers", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }

                    val playlistMembership = remember(playlists, item?.url, item?.id) {
                        val current = item ?: return@remember emptyList<LocalPlaylist>()
                        val key = SavedVideoStore.canonicalKey(current.url, current.id)
                        playlists.filter { playlist -> playlist.videos.any { SavedVideoStore.canonicalKey(it.url, it.id) == key } }
                    }

                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        val actionWidth = if (adaptive.largeTouchTargets) 124.dp else 98.dp
                        VideoActionButton(
                            if (saved) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                            if (saved) "Saved" else "Save", Modifier.width(actionWidth), selected = saved
                        ) { item?.let(onToggleSave) }
                        VideoActionButton(
                            Icons.AutoMirrored.Rounded.PlaylistPlay,
                            when {
                                playlistMembership.isEmpty() -> "Playlist"
                                playlistMembership.size == 1 -> playlistMembership.first().name.take(13)
                                else -> "${playlistMembership.size} Playlists"
                            },
                            Modifier.width(actionWidth),
                            selected = playlistMembership.isNotEmpty()
                        ) { showPlaylistPicker = true }
                        VideoActionButton(
                            icon = Icons.AutoMirrored.Rounded.Comment,
                            label = if (watch.commentsCount > 0) compactCount(watch.commentsCount.toLong()) else "Comments",
                            modifier = Modifier.width(actionWidth), selected = showComments
                        ) {
                            showComments = true
                            if (watch.comments.isEmpty() && !watch.commentsLoading) onOpenComments()
                        }
                        VideoActionButton(
                            icon = if (downloaded) Icons.Rounded.Check else Icons.Rounded.Download,
                            label = when {
                                downloaded -> "Downloaded"
                                downloadActive && (downloadState?.progress ?: 0) > 0 -> "${downloadState?.progress}%"
                                downloadActive -> "Queued"
                                else -> "Download"
                            },
                            modifier = Modifier.width(actionWidth), enabled = variants.isNotEmpty() && !downloadActive,
                            selected = downloadActive || downloaded, progress = if (downloadActive) downloadState?.progress else null
                        ) { if (!downloaded) showDownload = true }
                        VideoActionButton(Icons.Rounded.Share, "Share", Modifier.width(actionWidth)) { shareVideo() }
                    }

                    Surface(
                        onClick = { showDescription = true },
                        color = HarukiCardSoft,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
                    ) {
                        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Description", color = HarukiText, fontWeight = FontWeight.SemiBold)
                            Text(
                                watch.details.description.ifBlank { "No description was returned for this video." },
                                color = HarukiMuted,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            if (watch.collectionItems.isNotEmpty()) {
                item(key = "playlist-queue-header") {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                        color = HarukiCardSoft,
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorderSoft)
                    ) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(watch.collectionName.ifBlank { "Playlist" }, color = HarukiText, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "Playlist • ${(watch.collectionPosition + 1).coerceAtLeast(1)}/${watch.collectionSize.coerceAtLeast(watch.collectionItems.size)}",
                                    color = HarukiMuted, style = MaterialTheme.typography.labelMedium
                                )
                            }
                            Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, "Playlist", tint = HarukiPrimary)
                        }
                    }
                }
                itemsIndexed(watch.collectionItems, key = { _, video -> "playlist-${video.url}" }) { index, video ->
                    PlaylistQueueRow(
                        video = video,
                        index = index,
                        current = index == watch.collectionPosition || video.url == item?.url,
                        onClick = { if (video.url != item?.url) onOpenRelated(video) }
                    )
                }
            }

            if (upNextItems.isNotEmpty()) {
                item(key = "up-next-header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Up next", style = MaterialTheme.typography.titleMedium, color = HarukiText, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("Autoplay", color = HarukiMuted, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(7.dp))
                        Switch(
                            checked = autoplayNext,
                            onCheckedChange = onAutoplayChanged,
                            colors = SwitchDefaults.colors(checkedTrackColor = HarukiPrimary, checkedThumbColor = Color.White)
                        )
                    }
                }
                items(upNextItems, key = { "next-${it.url}" }) { related ->
                    RelatedVideoRow(related) { onOpenRelated(related) }
                }
                if (watch.relatedLoadingMore) {
                    item(key = "related-loading-more") {
                        Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = HarukiPrimary, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                        }
                    }
                }
            }
        }
    }

    if (showDownload && watch.media != null) {
        DownloadQualitySheet(
            media = watch.media,
            onDismiss = { showDownload = false },
            onQueue = { variant ->
                downloadVm.queueMedia(watch.media, variant, MediaMode.VIDEO)
                item?.let(onDownloadQueued)
                showDownload = false
            },
            onQueueMp3 = {
                downloadVm.queueMp3(watch.media)
                item?.let(onDownloadQueued)
                showDownload = false
            }
        )
    }

    if (showPlaylistPicker && item != null) {
        PlaylistPickerSheet(
            video = item, playlists = playlists,
            onDismiss = { showPlaylistPicker = false },
            onCreate = { name -> onCreatePlaylist(name) },
            onAdd = { playlistId -> onAddToPlaylist(playlistId, item) }
        )
    }

    if (showComments) {
        ModalBottomSheet(
            onDismissRequest = { showComments = false },
            containerColor = HarukiCard,
            contentColor = HarukiText
        ) {
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(min = 260.dp, max = 620.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                item(key = "comment-sheet-header") {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Comments", color = HarukiText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        if (watch.commentsCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Text(compactCount(watch.commentsCount.toLong()), color = HarukiMuted)
                        }
                    }
                }
                if (watch.comments.isEmpty() && watch.commentsLoading) {
                    item(key = "comment-sheet-loading") {
                        Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = HarukiPrimary, modifier = Modifier.size(28.dp))
                        }
                    }
                } else if (watch.commentsError.isNotBlank() && watch.comments.isEmpty()) {
                    item(key = "comment-sheet-error") {
                        Text(watch.commentsError, color = HarukiMuted, modifier = Modifier.padding(18.dp))
                    }
                }
                items(watch.comments, key = { "sheet-comment-${it.id}" }) { comment -> CommentRow(comment) }
                if (watch.commentsHasMore) {
                    item(key = "comment-sheet-more") {
                        TextButton(onClick = onLoadMoreComments, enabled = !watch.commentsLoading, modifier = Modifier.padding(horizontal = 10.dp)) {
                            if (watch.commentsLoading) CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(17.dp), color = HarukiPrimary)
                            else Text("Load more comments", color = HarukiPrimary)
                        }
                    }
                }
            }
        }
    }

    if (showDescription) {
        ModalBottomSheet(onDismissRequest = { showDescription = false }, containerColor = HarukiCard, contentColor = HarukiText) {
            LazyColumn(Modifier.fillMaxWidth().padding(horizontal = 18.dp), contentPadding = PaddingValues(bottom = 28.dp)) {
                item {
                    Text("Description", color = HarukiText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    val stats = buildList {
                        if (watch.details.viewCount >= 0) add("${compactCount(watch.details.viewCount)} views")
                        if (watch.details.likeCount >= 0) add("${compactCount(watch.details.likeCount)} likes")
                        if (watch.details.uploadText.isNotBlank()) add(watch.details.uploadText)
                    }.joinToString(" • ")
                    if (stats.isNotBlank()) {
                        Text(stats, color = HarukiMuted)
                        Spacer(Modifier.height(12.dp))
                    }
                    LinkifiedText(
                        text = watch.details.description.ifBlank { "No description was returned." },
                        modifier = Modifier.fillMaxWidth(),
                        color = HarukiText,
                        textSizeSp = 15f
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentRow(comment: VideoComment) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (comment.authorAvatarUrl.isNotBlank()) RemoteImage(comment.authorAvatarUrl, Modifier.size(36.dp).clip(CircleShape))
        else Box(Modifier.size(36.dp).clip(CircleShape).background(HarukiCard2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Person, null, tint = HarukiMuted) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                buildString { if (comment.pinned) append("📌 "); append(comment.author); if (comment.verified) append(" ✓"); if (comment.uploadText.isNotBlank()) append(" • ${comment.uploadText}") },
                color = HarukiMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            LinkifiedText(text = comment.text, modifier = Modifier.fillMaxWidth(), color = HarukiText, textSizeSp = 14f)
            if (comment.likeCount >= 0) Text("♡ ${compactCount(comment.likeCount.toLong())}", color = HarukiMuted2, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PlaylistQueueRow(video: BrowseVideo, index: Int, current: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = !current,
        color = if (current) HarukiPrimary.copy(alpha = .12f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("${index + 1}", color = if (current) HarukiPrimary else HarukiMuted, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(24.dp))
            RemoteImage(video.thumbnailUrl, Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)))
            Column(Modifier.weight(1f)) {
                Text(video.title, color = if (current) HarukiPrimary else HarukiText, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
                Text(video.uploader, color = HarukiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            if (current) Icon(Icons.Rounded.GraphicEq, "Playing", tint = HarukiPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun RelatedVideoRow(related: BrowseVideo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 11.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f)) {
            RemoteImage(related.thumbnailUrl, Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)))
            if (related.durationSeconds > 0L) {
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp),
                    color = Color.Black.copy(alpha = .82f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        formatDuration(related.durationSeconds),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(related.title, color = HarukiText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                buildString { append(related.uploader); if (related.uploaderVerified) append(" ✓") },
                color = HarukiMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall
            )
            val meta = buildList {
                if (related.viewCount >= 0) add(compactCount(related.viewCount) + " views")
                if (related.uploadText.isNotBlank()) add(related.uploadText)
            }.joinToString(" • ")
            if (meta.isNotBlank()) Text(meta, color = HarukiMuted2, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VideoActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    modifier: Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    progress: Int? = null,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        color = if (selected) HarukiPrimary.copy(alpha = .15f) else HarukiCardSoft,
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) HarukiPrimary else HarukiBorderSoft)
    ) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = if (selected) HarukiPrimary else if (enabled) HarukiText else HarukiMuted2, modifier = Modifier.size(20.dp))
                if (progress != null) {
                    CircularProgressIndicator(
                        progress = { progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.size(29.dp),
                        color = HarukiPrimary,
                        trackColor = HarukiPrimary.copy(alpha = .18f),
                        strokeWidth = 2.dp
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(label, color = if (selected) HarukiPrimary else if (enabled) HarukiText else HarukiMuted2, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
fun MiniPlayerBar(watch: WatchState, onRestore: () -> Unit, onClose: () -> Unit) {
    val item = watch.item ?: return
    if (watch.media?.videoVariants.isNullOrEmpty() && watch.media?.audioVariants.isNullOrEmpty() && item.playbackUrl.isBlank()) return
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var playing by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess { controller = it } }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.release()
            controller = null
            if (!future.isDone) future.cancel(true)
        }
    }
    LaunchedEffect(controller) {
        while (controller != null) {
            playing = controller?.isPlaying == true
            delay(500)
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(66.dp).premiumClickable(onClick = onRestore),
        color = HarukiCard2,
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, HarukiBorder)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = controller; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM } },
                update = { it.player = controller },
                modifier = Modifier.width(112.dp).fillMaxHeight().background(Color.Black)
            )
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(watch.media?.title ?: item.title, color = HarukiText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                Text(watch.media?.uploader ?: item.uploader, color = HarukiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() } }) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = HarukiText)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, null, tint = HarukiMuted) }
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun HarukiWatchPlayer(
    variant: MediaVariant,
    mediaId: String,
    pageUrl: String,
    title: String,
    uploader: String,
    artwork: String,
    modifier: Modifier,
    allowMinimize: Boolean,
    fullscreen: Boolean,
    speed: Float,
    qualityOptions: List<MediaVariant>,
    qualityModeLabel: String,
    selectedQualityId: String,
    autoModeSelected: Boolean,
    explicitQualitySelection: Boolean,
    subtitleOptions: List<SubtitleTrack>,
    selectedSubtitleId: String,
    audioOptions: List<AudioTrackOption>,
    selectedAudioTrackId: String,
    equalizerEnabled: Boolean,
    equalizerPreset: EqualizerPreset,
    onEqualizerEnabled: (Boolean) -> Unit,
    onEqualizerPreset: (EqualizerPreset) -> Unit,
    onQualitySelected: (String) -> Unit,
    onSubtitleSelected: (String) -> Unit,
    onAudioSelected: (String) -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onMinimize: () -> Unit,
    onFullscreen: () -> Unit,
    onBack: () -> Unit,
    previousAvailable: Boolean,
    nextAvailable: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    autoplayNextAvailable: Boolean,
    nextTitle: String,
    autoplayQueue: List<BrowseVideo>
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var bufferedPosition by remember { mutableLongStateOf(0L) }
    var seekPreview by remember { mutableLongStateOf(-1L) }
    var dragging by remember { mutableFloatStateOf(0f) }
    var qualityMenu by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var subtitleMenu by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var equalizerMenu by remember { mutableStateOf(false) }
    var autoNextSeconds by remember(mediaId) { mutableIntStateOf(0) }
    var centeredCaptionText by remember(mediaId) { mutableStateOf("") }
    // When returning from the media notification the ExoPlayer instance is already
    // playing, but a brand-new PlayerView surface must receive its first frame. Keep
    // the thumbnail visible for that tiny handoff instead of flashing a black box.
    var firstFrameRendered by remember(mediaId) { mutableStateOf(false) }
    var reachedReadyForCurrentVariant by remember(mediaId, variant.id) { mutableStateOf(false) }
    var rebufferCountForCurrentVariant by remember(mediaId, variant.id) { mutableIntStateOf(0) }
    var smartFallbackApplied by remember(mediaId) { mutableStateOf(false) }
    val threshold = with(density) { 92.dp.toPx() }

    DisposableEffect(context) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess { controller = it } }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller?.release()
            controller = null
            if (!future.isDone) future.cancel(true)
        }
    }

    DisposableEffect(controller, mediaId, variant.id, qualityOptions) {
        val c = controller
        if (c == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(value: Boolean) { isPlaying = value }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    reachedReadyForCurrentVariant = true
                } else if (
                    state == Player.STATE_BUFFERING &&
                    reachedReadyForCurrentVariant &&
                    !smartFallbackApplied &&
                    variant.qualityHeight >= 720
                ) {
                    rebufferCountForCurrentVariant += 1
                    if (rebufferCountForCurrentVariant >= 2) {
                        val fallback = qualityOptions
                            .filter { it.videoUrl.isNotBlank() && it.qualityHeight in 1..480 }
                            .sortedWith(
                                compareByDescending<MediaVariant> { !it.separateAudio }
                                    .thenByDescending { it.qualityHeight }
                            )
                            .firstOrNull()
                        if (fallback != null && fallback.id != variant.id) {
                            smartFallbackApplied = true
                            Toast.makeText(
                                context,
                                "720p stream is unstable. Switched to ${fallback.qualityHeight}p for smoother playback.",
                                Toast.LENGTH_SHORT
                            ).show()
                            onQualitySelected(fallback.id)
                        }
                    }
                }
            }
            override fun onCues(cueGroup: CueGroup) {
                centeredCaptionText = cueGroup.cues
                    .mapNotNull { it.text?.toString()?.trim() }
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            }
            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }
        }
        c.addListener(listener)
        isPlaying = c.isPlaying
        buffering = c.playbackState == Player.STATE_BUFFERING
        onDispose {
            centeredCaptionText = ""
            c.removeListener(listener)
        }
    }

    LaunchedEffect(
        controller,
        mediaId,
        variant.videoUrl,
        variant.audioUrl,
        subtitleOptions,
        autoplayNextAvailable,
        autoplayQueue
    ) {
        val c = controller ?: return@LaunchedEffect
        val subtitleBundles = ArrayList<Bundle>(subtitleOptions.size)
        subtitleOptions.forEach { subtitle ->
            subtitleBundles += Bundle().apply {
                putString(PlaybackCommands.ARG_SUBTITLE_ID, subtitle.id)
                putString(PlaybackCommands.ARG_SUBTITLE_URL, subtitle.url)
                putString(PlaybackCommands.ARG_SUBTITLE_MIME, subtitle.mimeType.ifBlank { "text/vtt" })
                putString(PlaybackCommands.ARG_SUBTITLE_LANGUAGE, subtitle.languageCode)
                putString(PlaybackCommands.ARG_SUBTITLE_LABEL, subtitle.label)
            }
        }
        val autoplayBundles = ArrayList<Bundle>(autoplayQueue.size)
        autoplayQueue.forEach { video ->
            autoplayBundles += Bundle().apply {
                putString(PlaybackCommands.ARG_AUTOPLAY_URL, video.url)
                putString(PlaybackCommands.ARG_AUTOPLAY_ID, video.id)
                putString(PlaybackCommands.ARG_AUTOPLAY_TITLE, video.title)
                putString(PlaybackCommands.ARG_AUTOPLAY_UPLOADER, video.uploader)
                putString(PlaybackCommands.ARG_AUTOPLAY_ARTWORK, video.thumbnailUrl)
                putLong(PlaybackCommands.ARG_AUTOPLAY_DURATION, video.durationSeconds)
                putLong(PlaybackCommands.ARG_AUTOPLAY_VIEWS, video.viewCount)
                putString(PlaybackCommands.ARG_AUTOPLAY_UPLOAD_TEXT, video.uploadText)
                putString(PlaybackCommands.ARG_AUTOPLAY_SERVICE, video.service)
                putString(PlaybackCommands.ARG_AUTOPLAY_AVATAR, video.uploaderAvatarUrl)
                putBoolean(PlaybackCommands.ARG_AUTOPLAY_VERIFIED, video.uploaderVerified)
            }
        }
        val args = Bundle().apply {
            putString(PlaybackCommands.ARG_MEDIA_ID, mediaId)
            putString(PlaybackCommands.ARG_PAGE_URL, pageUrl)
            putString(PlaybackCommands.ARG_TITLE, title)
            putString(PlaybackCommands.ARG_UPLOADER, uploader)
            putString(PlaybackCommands.ARG_ARTWORK, artwork)
            putString(PlaybackCommands.ARG_VIDEO_URL, variant.videoUrl)
            putString(PlaybackCommands.ARG_VARIANT_ID, variant.id)
            putString(PlaybackCommands.ARG_AUDIO_URL, variant.audioUrl)
            putString(PlaybackCommands.ARG_VIDEO_MIME, variant.mimeType.ifBlank { "video/mp4" })
            putString(PlaybackCommands.ARG_AUDIO_MIME, audioOptions.firstOrNull { it.id == selectedAudioTrackId }?.mimeType ?: "audio/mp4")
            putString(PlaybackCommands.ARG_REQUEST_HEADERS, variant.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            putParcelableArrayList(PlaybackCommands.ARG_SUBTITLES, subtitleBundles)
            putBoolean(PlaybackCommands.ARG_AUTOPLAY_ENABLED, autoplayNextAvailable)
            putInt(PlaybackCommands.ARG_PREFERRED_HEIGHT, variant.qualityHeight)
            putBoolean(PlaybackCommands.ARG_EXPLICIT_QUALITY, explicitQualitySelection)
            putParcelableArrayList(PlaybackCommands.ARG_AUTOPLAY_QUEUE, autoplayBundles)
        }
        c.sendCustomCommand(PlaybackCommands.PLAY_VARIANT, args)
    }

    // Captions are pre-attached to the current media item. Changing this preference
    // switches Media3's text renderer only, so the video/audio stream does not re-prepare.
    LaunchedEffect(controller, mediaId, variant.videoUrl, variant.audioUrl, selectedSubtitleId, subtitleOptions) {
        val c = controller ?: return@LaunchedEffect
        val selected = subtitleOptions.firstOrNull { it.id == selectedSubtitleId }
        val builder = c.trackSelectionParameters.buildUpon()
        if (selected == null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setSelectTextByDefault(false)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setSelectTextByDefault(true)
                .setPreferredTextLabels(selected.label)
            if (selected.languageCode.isNotBlank()) builder.setPreferredTextLanguages(selected.languageCode)
        }
        c.trackSelectionParameters = builder.build()
    }

    LaunchedEffect(controller, speed) { controller?.setPlaybackSpeed(speed) }

    LaunchedEffect(controller, controlsVisible, isPlaying, autoplayNextAvailable) {
        while (controller != null) {
            val c = controller ?: break
            if (seekPreview < 0L) position = c.currentPosition.coerceAtLeast(0L)
            duration = c.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            bufferedPosition = c.bufferedPosition.coerceAtLeast(0L)
            val remaining = (duration - position).coerceAtLeast(0L)
            autoNextSeconds = if (autoplayNextAvailable && duration > 0L && remaining in 1L..5_200L) {
                ceil(remaining / 1000.0).toInt().coerceIn(1, 5)
            } else 0
            // PlaybackService owns the end-of-item transition so autoplay also
            // works when this composable is minimized or the app is backgrounded.
            delay(if (controlsVisible || autoNextSeconds > 0) 200L else 650L)
        }
    }

    LaunchedEffect(controlsVisible, isPlaying, qualityMenu, speedMenu, subtitleMenu, audioMenu, equalizerMenu) {
        if (controlsVisible && isPlaying && !qualityMenu && !speedMenu && !subtitleMenu && !audioMenu && !equalizerMenu) {
            delay(2_300)
            controlsVisible = false
        }
    }

    Box(
        modifier
            .graphicsLayer { translationY = if (allowMinimize) dragging else 0f }
            .background(Color.Black)
            .pointerInput(allowMinimize) {
                if (allowMinimize) detectVerticalDragGestures(
                    onVerticalDrag = { change, amount ->
                        if (amount > 0 || dragging > 0) {
                            change.consume()
                            dragging = max(0f, dragging + amount)
                        }
                    },
                    onDragEnd = {
                        if (dragging > threshold) onMinimize()
                        dragging = 0f
                    },
                    onDragCancel = { dragging = 0f }
                )
            }
            .pointerInput(mediaId) {
                detectTapGestures(
                    onTap = { controlsVisible = !controlsVisible },
                    onDoubleTap = { offset ->
                        val c = controller ?: return@detectTapGestures
                        val delta = if (offset.x < size.width / 2f) -10_000L else 10_000L
                        c.seekTo((c.currentPosition + delta).coerceAtLeast(0L))
                        controlsVisible = true
                    }
                )
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = controller
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    // YouTube auto-captions can carry left/right positioning cues. Hide the
                    // embedded subtitle view and render the cue text in our centered overlay.
                    subtitleView?.visibility = android.view.View.INVISIBLE
                }
            },
            update = {
                it.player = controller
                it.subtitleView?.visibility = android.view.View.INVISIBLE
            },
            modifier = Modifier.fillMaxSize()
        )

        // Avoid the temporary black surface while an already-playing background
        // session attaches to this newly-created PlayerView. Media3 emits
        // onRenderedFirstFrame for a newly set surface, then the artwork disappears.
        if (!firstFrameRendered && artwork.isNotBlank() && isPlaying) {
            RemoteImage(
                artwork,
                Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp)
            )
        }

        if (selectedSubtitleId.isNotBlank() && centeredCaptionText.isNotBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = if (controlsVisible) 68.dp else 28.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = centeredCaptionText,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = .72f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                        .widthIn(max = 720.dp)
                )
            }
        }

        if (buffering) CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp, modifier = Modifier.align(Alignment.Center).size(42.dp))

        if (autoNextSeconds in 1..5) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                color = Color.Black.copy(alpha = .72f),
                shape = RoundedCornerShape(10.dp)
            ) {
                Column(Modifier.widthIn(max = 230.dp).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text("Up next in ${autoNextSeconds}s", color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
                    if (nextTitle.isNotBlank()) Text(nextTitle, color = Color.White.copy(alpha = .82f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        if (controlsVisible) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = .58f), Color.Transparent, Color.Black.copy(alpha = .66f)))))

            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (fullscreen) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    Text(title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
            }

            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                PlayerCircleButton(Icons.Rounded.SkipPrevious, "Previous video", enabled = previousAvailable, onClick = onPrevious)
                PlayerCircleButton(Icons.Rounded.Replay10, "Back 10 seconds") { controller?.let { it.seekTo((it.currentPosition - 10_000L).coerceAtLeast(0L)) } }
                FilledIconButton(
                    onClick = { controller?.let { if (it.isPlaying) it.pause() else it.play() }; controlsVisible = true },
                    modifier = Modifier.size(58.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .58f), contentColor = Color.White)
                ) { Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, modifier = Modifier.size(34.dp)) }
                PlayerCircleButton(Icons.Rounded.Forward10, "Forward 10 seconds") { controller?.let { it.seekTo(it.currentPosition + 10_000L) } }
                PlayerCircleButton(Icons.Rounded.SkipNext, "Next video", enabled = nextAvailable, onClick = onNext)
            }

            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)) {
                val displayedPosition = if (seekPreview >= 0L) seekPreview else position
                YouTubeSeekBar(
                    position = displayedPosition,
                    bufferedPosition = bufferedPosition,
                    duration = duration,
                    onPreview = { seekPreview = it; controlsVisible = true },
                    onSeek = {
                        controller?.seekTo(it)
                        seekPreview = -1L
                        controlsVisible = true
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${formatPlayerTime(displayedPosition)} / ${formatPlayerTime(duration)}", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { subtitleMenu = true; controlsVisible = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.ClosedCaption,
                                "Subtitles",
                                tint = when {
                                    selectedSubtitleId.isNotBlank() -> HarukiPrimary
                                    subtitleOptions.isEmpty() -> Color.White.copy(alpha = .55f)
                                    else -> Color.White
                                }
                            )
                        }
                        DropdownMenu(expanded = subtitleMenu, onDismissRequest = { subtitleMenu = false }, containerColor = HarukiCard2) {
                            if (subtitleOptions.isEmpty()) {
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("Captions unavailable", color = HarukiText)
                                            Text(
                                                "YouTube did not provide manual or auto-generated subtitles for this video.",
                                                color = HarukiMuted,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    },
                                    enabled = false,
                                    onClick = {}
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Captions off", color = HarukiText) },
                                    trailingIcon = { if (selectedSubtitleId.isBlank()) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                    onClick = { onSubtitleSelected(""); subtitleMenu = false }
                                )
                                subtitleOptions.take(40).forEach { sub ->
                                    DropdownMenuItem(
                                        text = { Text(sub.label, color = HarukiText) },
                                        trailingIcon = { if (selectedSubtitleId == sub.id) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                        onClick = { onSubtitleSelected(sub.id); subtitleMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    if (audioOptions.isNotEmpty()) {
                        Box {
                            val selectedAudioOption = audioOptions.firstOrNull { it.id == selectedAudioTrackId }
                                ?: audioOptions.firstOrNull { it.original } ?: audioOptions.firstOrNull()
                            TextButton(onClick = { audioMenu = true; controlsVisible = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                                Text(audioControlLabel(selectedAudioOption), color = Color.White, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                            }
                            DropdownMenu(expanded = audioMenu, onDismissRequest = { audioMenu = false }, containerColor = HarukiCard2) {
                                audioOptions.take(20).forEach { audio ->
                                    DropdownMenuItem(
                                        text = { Text(audio.label, color = HarukiText, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                        trailingIcon = { if (selectedAudioTrackId == audio.id) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                        onClick = { onAudioSelected(audio.id); audioMenu = false }
                                    )
                                }
                            }
                        }
                    }
                    Box {
                        IconButton(onClick = { equalizerMenu = true; controlsVisible = true }, modifier = Modifier.size(36.dp)) {
                            Icon(
                                Icons.Rounded.GraphicEq,
                                "Equalizer",
                                tint = if (equalizerEnabled) HarukiPrimary else Color.White
                            )
                        }
                        DropdownMenu(expanded = equalizerMenu, onDismissRequest = { equalizerMenu = false }, containerColor = HarukiCard2) {
                            DropdownMenuItem(
                                text = { Text("Equalizer off", color = HarukiText) },
                                trailingIcon = { if (!equalizerEnabled) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                onClick = { onEqualizerEnabled(false); equalizerMenu = false }
                            )
                            EqualizerPreset.selectable.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(if (preset.popularChoice) "${preset.displayName}  •  Popular" else preset.displayName, color = HarukiText) },
                                    trailingIcon = {
                                        if (equalizerEnabled && equalizerPreset == preset) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary)
                                    },
                                    onClick = { onEqualizerPreset(preset); equalizerMenu = false }
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { qualityMenu = true; controlsVisible = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text(qualityModeLabel, color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = qualityMenu, onDismissRequest = { qualityMenu = false }, containerColor = HarukiCard2) {
                            DropdownMenuItem(
                                text = { Text("Auto", color = HarukiText) },
                                trailingIcon = { if (autoModeSelected) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                onClick = { onQualitySelected("__AUTO__"); qualityMenu = false }
                            )
                            qualityOptions.distinctBy { it.qualityHeight }.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option.label, color = HarukiText) },
                                    trailingIcon = { if (selectedQualityId == option.id) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                    onClick = { onQualitySelected(option.id); qualityMenu = false }
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { speedMenu = true; controlsVisible = true }, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
                            Text(if (speed == 1f) "1x" else "${speed}x", color = Color.White, style = MaterialTheme.typography.labelSmall)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }, containerColor = HarukiCard2) {
                            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { value ->
                                DropdownMenuItem(
                                    text = { Text("${value}x", color = HarukiText) },
                                    trailingIcon = { if (value == speed) Icon(Icons.Rounded.Check, null, tint = HarukiPrimary) },
                                    onClick = { onSpeedSelected(value); speedMenu = false }
                                )
                            }
                        }
                    }
                    IconButton(onClick = onFullscreen, modifier = Modifier.size(36.dp)) {
                        Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen, "Fullscreen", tint = Color.White)
                    }
                }
            }
        }

        if (allowMinimize && dragging > 0f) {
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                color = Color.Black.copy(alpha = .62f),
                shape = RoundedCornerShape(999.dp)
            ) {
                Row(Modifier.padding(horizontal = 11.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.KeyboardArrowDown, null, tint = Color.White, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("Release to minimize", color = Color.White, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun YouTubeSeekBar(
    position: Long,
    bufferedPosition: Long,
    duration: Long,
    onPreview: (Long) -> Unit,
    onSeek: (Long) -> Unit
) {
    val progress = if (duration > 0L) (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val buffered = if (duration > 0L) (bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
    var dragTarget by remember { mutableLongStateOf(position) }
    LaunchedEffect(position) { dragTarget = position }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(18.dp)
            .pointerInput(duration) {
                detectTapGestures { offset ->
                    if (duration <= 0L || size.width <= 0) return@detectTapGestures
                    val target = ((offset.x / size.width.toFloat()).coerceIn(0f, 1f) * duration).toLong()
                    onPreview(target)
                    onSeek(target)
                }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        if (duration > 0L && size.width > 0) {
                            dragTarget = ((offset.x / size.width.toFloat()).coerceIn(0f, 1f) * duration).toLong()
                            onPreview(dragTarget)
                        }
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        if (duration > 0L && size.width > 0) {
                            dragTarget = ((change.position.x / size.width.toFloat()).coerceIn(0f, 1f) * duration).toLong()
                            onPreview(dragTarget)
                        }
                    },
                    onDragEnd = { onSeek(dragTarget) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = .30f)))
        Box(Modifier.fillMaxWidth(buffered).height(2.dp).background(Color.White.copy(alpha = .55f)))
        Box(Modifier.fillMaxWidth(progress).height(3.dp).background(HarukiPrimary))
        if (duration > 0L) {
            Box(
                Modifier
                    .offset(x = (maxWidth - 9.dp) * progress)
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(HarukiPrimary)
            )
        }
    }
}

@Composable
private fun PlayerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(48.dp).clip(CircleShape).background(Color.Black.copy(alpha = .48f))) {
        Icon(icon, description, tint = if (enabled) Color.White else Color.White.copy(alpha = .35f), modifier = Modifier.size(27.dp))
    }
}

private fun audioControlLabel(option: AudioTrackOption?): String = when {
    option == null -> "Audio"
    option.dubbed -> "Dubbed"
    option.descriptive -> "Description"
    option.original -> "Original"
    option.label.contains("secondary", true) || option.label.contains("alternate", true) -> "Alternate"
    else -> option.languageCode.substringBefore('-').uppercase().ifBlank { "Audio" }
}

@Composable
private fun rememberWatchAutoHeight(): Int {
    val context = LocalContext.current
    var height by remember { mutableIntStateOf(estimateWatchNetworkHeight(context)) }
    LaunchedEffect(context) {
        var pending = height
        var stableSamples = 0
        while (true) {
            val candidate = estimateWatchNetworkHeight(context)
            if (candidate == pending) stableSamples++ else {
                pending = candidate
                stableSamples = 1
            }
            // Two matching measurements prevents Wi-Fi/cellular estimate jitter from
            // constantly rebuilding the YouTube media source.
            if (stableSamples >= 2 && candidate != height) height = candidate
            delay(5_000)
        }
    }
    return height
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun estimateWatchNetworkHeight(context: Context): Int {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return 480
    val cellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

    // Prefer Media3's measured throughput once playback has transferred data.
    // NetworkCapabilities is only a link estimate and can wildly over-report Wi-Fi.
    val measuredBps = DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate
    val linkBps = caps.linkDownstreamBandwidthKbps.toLong().coerceAtLeast(0L) * 1_000L
    val effectiveBps = when {
        measuredBps >= 1_200_000L -> measuredBps
        linkBps > 0L -> linkBps
        else -> measuredBps
    }
    // Keep headroom for audio, protocol overhead and temporary throughput drops.
    // Direct extracted variants cannot switch as seamlessly as a DASH manifest.
    // Auto therefore keeps generous headroom and caps at 720p for direct-stream
    // smoothness; higher fixed resolutions remain selectable manually.
    return when {
        effectiveBps >= 12_000_000L && !cellular -> 720
        effectiveBps >= 6_000_000L -> 480
        effectiveBps >= 3_500_000L -> 360
        else -> 360
    }
}

private fun chooseWatchAutoVariant(variants: List<MediaVariant>, targetHeight: Int): MediaVariant? {
    val withinTarget = variants.filter { it.qualityHeight > 0 && it.qualityHeight <= targetHeight }
    // Direct YouTube video-only + separate-audio streams can stall independently.
    // Auto prioritizes a progressive/muxed stream when available because one HTTP
    // stream is substantially more resilient on mobile/background transitions.
    fun smoothest(items: List<MediaVariant>): MediaVariant? = items.sortedWith(
        compareByDescending<MediaVariant> { it.qualityHeight }
            .thenBy { if (it.fps > 0) it.fps else 30 }
            .thenBy { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
            .thenBy { it.separateAudio }
    ).firstOrNull()
    val progressive = smoothest(withinTarget.filterNot { it.separateAudio })
    if (progressive != null) return progressive
    return smoothest(withinTarget)
        ?: variants.filter { it.qualityHeight > 0 }.minByOrNull { it.qualityHeight }
        ?: variants.firstOrNull()
}

private fun currentQualityLabel(
    preference: String,
    manualQualityId: String,
    selected: MediaVariant,
    autoVariant: MediaVariant?
): String = when {
    manualQualityId == "__AUTO__" -> "Auto • ${autoVariant?.label ?: selected.label}"
    manualQualityId.isNotBlank() -> selected.label
    preference.equals("Auto", true) -> "Auto • ${autoVariant?.label ?: selected.label}"
    else -> selected.label
}

private fun formatPlayerTime(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val total = ms / 1000L
    val h = total / 3600L
    val m = (total % 3600L) / 60L
    val s = total % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun compactCount(value: Long): String = when {
    value >= 1_000_000_000L -> "%.1fB".format(value / 1_000_000_000.0)
    value >= 1_000_000L -> "%.1fM".format(value / 1_000_000.0)
    value >= 1_000L -> "%.1fK".format(value / 1_000.0)
    value >= 0L -> value.toString()
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQualitySheet(
    media: AnalyzedMedia,
    onDismiss: () -> Unit,
    onQueue: (MediaVariant) -> Unit,
    onQueueMp3: () -> Unit = {}
) {
    var audioMode by remember(media.mediaId) { mutableStateOf(false) }
    var selectedId by remember(media.mediaId) { mutableStateOf(media.videoVariants.firstOrNull()?.id.orEmpty()) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = HarukiCard, contentColor = HarukiText) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Download", style = MaterialTheme.typography.titleLarge, color = HarukiText)
            Text(media.title, color = HarukiMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !audioMode, onClick = { audioMode = false }, label = { Text("Video") }, leadingIcon = { Icon(Icons.Rounded.Movie, null) })
                FilterChip(selected = audioMode, onClick = { audioMode = true }, label = { Text("MP3 audio") }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) })
            }
            if (audioMode) {
                Surface(color = HarukiViolet.copy(alpha = .11f), shape = RoundedCornerShape(14.dp), border = androidx.compose.foundation.BorderStroke(1.dp, HarukiViolet.copy(alpha = .28f))) {
                    Column(Modifier.fillMaxWidth().padding(13.dp)) {
                        Text("MP3 • 192 kbps", color = HarukiText, fontWeight = FontWeight.Bold)
                        Text("NovaTube downloads the best available audio, then converts it to a real MP3 file.", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else {
                media.videoVariants.forEach { variant ->
                    Surface(onClick = { selectedId = variant.id }, color = if (selectedId == variant.id) HarukiPrimary.copy(alpha = .13f) else HarukiCardSoft, shape = RoundedCornerShape(13.dp), border = androidx.compose.foundation.BorderStroke(1.dp, if (selectedId == variant.id) HarukiPrimary else HarukiBorder)) {
                        Row(Modifier.fillMaxWidth().padding(11.dp), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = selectedId == variant.id, onClick = { selectedId = variant.id }, colors = RadioButtonDefaults.colors(selectedColor = HarukiPrimary))
                            Spacer(Modifier.width(7.dp))
                            Column(Modifier.weight(1f)) {
                                Text(variant.label, color = HarukiText, fontWeight = FontWeight.SemiBold)
                                val extra = buildList {
                                    if (variant.codecNote.isNotBlank()) add(variant.codecNote)
                                    if (variant.fps > 0) add("${variant.fps}fps")
                                    if (variant.bitrate > 0) add("${variant.bitrate / 1000} kbps")
                                    if (variant.separateAudio) add("video + audio")
                                }.joinToString(" • ")
                                if (extra.isNotBlank()) Text(extra, color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Button(
                onClick = { if (audioMode) onQueueMp3() else media.videoVariants.firstOrNull { it.id == selectedId }?.let(onQueue) },
                enabled = if (audioMode) media.audioVariants.isNotEmpty() else selectedId.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (audioMode) HarukiViolet else HarukiPrimary),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(if (audioMode) Icons.Rounded.MusicNote else Icons.Rounded.Download, null)
                Spacer(Modifier.width(8.dp)); Text(if (audioMode) "Download MP3" else "Download video")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistPickerSheet(
    video: BrowseVideo,
    playlists: List<LocalPlaylist>,
    onDismiss: () -> Unit,
    onCreate: (String) -> LocalPlaylist,
    onAdd: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = HarukiCard, contentColor = HarukiText) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Save to playlist", color = HarukiText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(video.title, color = HarukiMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            playlists.forEach { playlist ->
                val key = SavedVideoStore.canonicalKey(video.url, video.id)
                val alreadyAdded = playlist.videos.any { SavedVideoStore.canonicalKey(it.url, it.id) == key }
                Surface(
                    onClick = { if (!alreadyAdded) onAdd(playlist.id) },
                    color = if (alreadyAdded) HarukiPrimary.copy(alpha = .14f) else HarukiCardSoft,
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (alreadyAdded) HarukiPrimary else HarukiBorderSoft)
                ) {
                    Row(Modifier.fillMaxWidth().padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistPlay, null, tint = HarukiPrimary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, color = if (alreadyAdded) HarukiPrimary else HarukiText, fontWeight = FontWeight.SemiBold)
                            Text(if (alreadyAdded) "Added • ${playlist.videos.size} videos" else "${playlist.videos.size} videos", color = HarukiMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(if (alreadyAdded) Icons.Rounded.CheckCircle else Icons.Rounded.Add, if (alreadyAdded) "Added" else "Add", tint = HarukiPrimary)
                    }
                }
            }
            OutlinedTextField(
                value = newName, onValueChange = { newName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("New playlist name") }, placeholder = { Text("e.g. Music, Watch later") }
            )
            Button(
                onClick = { val created = onCreate(newName); onAdd(created.id) },
                enabled = newName.trim().isNotBlank(), modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = HarukiViolet)
            ) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Create playlist and add")
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
