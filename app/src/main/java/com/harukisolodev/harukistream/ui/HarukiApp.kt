package com.harukisolodev.harukistream.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harukisolodev.harukistream.data.*
import com.harukisolodev.harukistream.player.PlaybackService
import com.harukisolodev.harukistream.ui.screens.*
import com.harukisolodev.harukistream.ui.theme.*

private enum class Destination(val label: String, val icon: ImageVector) {
    YOUTUBE("Home", Icons.Rounded.Home),
    NOVA_AI("Nova AI", Icons.Rounded.AutoAwesome),
    LIBRARY("Library", Icons.Rounded.VideoLibrary),
    YOU("You", Icons.Rounded.Person),
    DOWNLOADS("Downloads", Icons.Rounded.Download),
    PLAYLISTS("Playlists", Icons.Rounded.PlaylistPlay),
    HISTORY("History", Icons.Rounded.History),
    SAVED("Saved", Icons.Rounded.Bookmark),
    EQUALIZER("Equalizer", Icons.Rounded.GraphicEq),
    SETTINGS("Settings", Icons.Rounded.Settings),
    ABOUT("About", Icons.Rounded.Info)
}

private val primaryDestinations = setOf(Destination.YOUTUBE, Destination.NOVA_AI, Destination.LIBRARY, Destination.YOU)

@Composable
fun HarukiApp(
    initialUrl: String = "",
    openRequestId: Long = 0L,
    launchDestination: String = "",
    navigationRequestId: Long = 0L,
    browseVm: BrowseViewModel = viewModel(),
    downloadVm: HarukiViewModel = viewModel()
) {
    HarukiTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val adaptive = remember(maxWidth, maxHeight) { novaAdaptiveInfo(maxWidth, maxHeight) }
            val context = LocalContext.current
            val browse by browseVm.state.collectAsStateWithLifecycle()
            val history by browseVm.history.collectAsStateWithLifecycle()
            val saved by browseVm.saved.collectAsStateWithLifecycle()
            val playlists by browseVm.playlists.collectAsStateWithLifecycle()
            val queue by downloadVm.downloadQueue.collectAsStateWithLifecycle()
            val library by downloadVm.library.collectAsStateWithLifecycle()
            val settings by downloadVm.settings.collectAsStateWithLifecycle()

            val downloadByKey = remember(queue) {
                queue.filter { it.sourceUrl.isNotBlank() }.groupBy { SavedVideoStore.canonicalKey(it.sourceUrl) }
                    .mapValues { (_, items) -> items.maxByOrNull { it.createdAt }!! }
            }
            val offlineVideoByKey = remember(library) {
                library.filter { it.mimeType.startsWith("video/") && it.sourceUrl.isNotBlank() }
                    .groupBy { SavedVideoStore.canonicalKey(it.sourceUrl, it.mediaId) }
                    .mapValues { (_, items) -> items.maxByOrNull { it.downloadedAt }!! }
            }
            val downloadedKeys = remember(offlineVideoByKey) { offlineVideoByKey.keys }
            val savedKeys = remember(saved) { saved.map { SavedVideoStore.canonicalKey(it.url, it.id) }.toSet() }

            var destination by rememberSaveable { mutableStateOf(Destination.YOUTUBE) }
            val destinationBackStack = remember { mutableStateListOf<Destination>() }
            var showWatch by rememberSaveable { mutableStateOf(false) }
            var watchMinimized by rememberSaveable { mutableStateOf(false) }
            var showShorts by rememberSaveable { mutableStateOf(false) }
            var shortsInitialUrl by rememberSaveable { mutableStateOf("") }
            var returnToWatchAfterShorts by rememberSaveable { mutableStateOf(false) }
            var resumeWatchAfterShorts by rememberSaveable { mutableStateOf(false) }
            var watchWasMinimizedBeforeShorts by rememberSaveable { mutableStateOf(false) }
            var sheetMedia by remember { mutableStateOf<AnalyzedMedia?>(null) }
            var sheetSignalVideo by remember { mutableStateOf<BrowseVideo?>(null) }

            fun leaveShorts(restoreWatch: Boolean) {
                if (!showShorts) return
                showShorts = false
                shortsInitialUrl = ""
                if (returnToWatchAfterShorts && browse.watch.item != null) {
                    PlaybackService.resumeAfterShorts(resumeWatchAfterShorts)
                    val restoreFullWatch = restoreWatch && !watchWasMinimizedBeforeShorts
                    showWatch = restoreFullWatch
                    watchMinimized = !restoreFullWatch
                }
                returnToWatchAfterShorts = false
                resumeWatchAfterShorts = false
                watchWasMinimizedBeforeShorts = false
            }
            fun navigateTo(next: Destination) {
                if (next == destination) return
                destinationBackStack.add(destination); destination = next
                if (next == Destination.DOWNLOADS || next == Destination.LIBRARY) downloadVm.refreshLibrary()
            }
            fun selectPrimary(next: Destination) {
                if (showShorts) leaveShorts(restoreWatch = false)
                destinationBackStack.clear(); destination = next
                if (next != Destination.YOUTUBE) browseVm.closeCollection()
                if (next == Destination.LIBRARY) downloadVm.refreshLibrary()
            }
            fun navigateBackSection() {
                destination = if (destinationBackStack.isNotEmpty()) destinationBackStack.removeAt(destinationBackStack.lastIndex)
                else Destination.YOUTUBE
            }
            fun openShort(short: BrowseVideo? = null) {
                if (!showShorts) {
                    val hasReturnableWatch = browse.watch.item != null && (showWatch || watchMinimized)
                    returnToWatchAfterShorts = hasReturnableWatch
                    watchWasMinimizedBeforeShorts = watchMinimized
                    resumeWatchAfterShorts = if (hasReturnableWatch) PlaybackService.pauseForShorts() else false
                    showWatch = false
                    watchMinimized = hasReturnableWatch
                }
                if (short != null) { shortsInitialUrl = short.url; browseVm.seedShort(short) } else shortsInitialUrl = ""
                showShorts = true
            }
            fun openVideo(item: BrowseVideo) {
                if (item.shortForm || item.url.contains("/shorts/", true)) { openShort(item); return }
                showShorts = false; shortsInitialUrl = ""
                returnToWatchAfterShorts = false; resumeWatchAfterShorts = false; watchWasMinimizedBeforeShorts = false
                watchMinimized = false; showWatch = true
                val key = SavedVideoStore.canonicalKey(item.url, item.id)
                offlineVideoByKey[key]?.let { browseVm.openOffline(item, it) } ?: browseVm.openYouTube(item)
            }
            fun openChannel(url: String, name: String, thumbnailUrl: String) {
                if (url.isBlank()) return
                context.stopService(Intent(context, PlaybackService::class.java))
                showWatch = false; watchMinimized = false; showShorts = false
                returnToWatchAfterShorts = false; resumeWatchAfterShorts = false; watchWasMinimizedBeforeShorts = false
                destinationBackStack.clear(); destination = Destination.YOUTUBE
                browseVm.closeWatch(); browseVm.openChannel(url, name, thumbnailUrl)
            }

            LaunchedEffect(initialUrl, openRequestId) {
                if (initialUrl.startsWith("http")) {
                    destinationBackStack.clear(); destination = Destination.YOUTUBE
                    val isShort = initialUrl.contains("/shorts/", true)
                    if (isShort) openShort(BrowseVideo(initialUrl.hashCode().toString(), initialUrl, "YouTube Short", "", "", shortForm = true))
                    else {
                        showShorts = false; shortsInitialUrl = ""
                        returnToWatchAfterShorts = false; resumeWatchAfterShorts = false; watchWasMinimizedBeforeShorts = false
                        val current = browse.watch.item
                        val samePlayingVideo = current != null && SavedVideoStore.canonicalKey(current.url, current.id) == SavedVideoStore.canonicalKey(initialUrl)
                        watchMinimized = false; showWatch = true
                        if (!samePlayingVideo || browse.watch.media == null) browseVm.openSharedUrl(initialUrl)
                    }
                }
            }
            LaunchedEffect(launchDestination, navigationRequestId) {
                if (launchDestination == "DOWNLOADS") {
                    if (showShorts) leaveShorts(restoreWatch = false)
                    if (showWatch && browse.watch.item != null) { showWatch = false; watchMinimized = true }
                    destinationBackStack.clear(); destination = Destination.DOWNLOADS; downloadVm.refreshLibrary()
                }
            }

            BackHandler(enabled = showShorts || showWatch || destination !in primaryDestinations || destinationBackStack.isNotEmpty()) {
                when {
                    showShorts -> leaveShorts(restoreWatch = true)
                    showWatch -> { context.stopService(Intent(context, PlaybackService::class.java)); showWatch = false; watchMinimized = false; browseVm.closeWatch() }
                    else -> navigateBackSection()
                }
            }

            val showPrimaryNavigation = !showWatch && (showShorts || destination in primaryDestinations)
            val showRail = showPrimaryNavigation && adaptive.useNavigationRail
            val showBottomBar = showPrimaryNavigation && !adaptive.useNavigationRail

            Row(Modifier.fillMaxSize().background(HarukiBg)) {
                if (showRail) {
                    NovaNavigationRail(
                        destination = destination, shortsSelected = showShorts,
                        large = adaptive.largeTouchTargets,
                        onHome = {
                            val clean = !showShorts && destination == Destination.YOUTUBE && browse.youtubeQuery.isBlank() && browse.collection.entity == null
                            selectPrimary(Destination.YOUTUBE); browseVm.goHome(refresh = clean)
                        },
                        onShorts = { openShort(null) }, onAi = { selectPrimary(Destination.NOVA_AI) },
                        onLibrary = { selectPrimary(Destination.LIBRARY) }, onYou = { selectPrimary(Destination.YOU) }
                    )
                }

                Box(Modifier.weight(1f).fillMaxHeight()) {
                    AnimatedContent(
                        targetState = destination,
                        transitionSpec = {
                            (fadeIn(tween(150)) + slideInHorizontally(tween(180)) { it / 18 }) togetherWith
                                (fadeOut(tween(110)) + slideOutHorizontally(tween(140)) { -it / 22 })
                        },
                        label = "nova-destination"
                    ) { animatedDestination ->
                        DestinationScreen(
                            destination = animatedDestination, browse = browse, history = history, saved = saved,
                            playlists = playlists, queue = queue, library = library, settings = settings,
                            adaptive = adaptive, downloadedKeys = downloadedKeys,
                            browseVm = browseVm, downloadVm = downloadVm,
                            onBackSubpage = ::navigateBackSection, onNavigate = ::navigateTo,
                            onOpenVideo = ::openVideo, onOpenShorts = ::openShort
                        )
                    }

                    if (watchMinimized && browse.watch.item != null && !showShorts) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = if (showBottomBar) 80.dp else 0.dp).navigationBarsPadding()) {
                            MiniPlayerBar(
                                watch = browse.watch,
                                onRestore = { watchMinimized = false; showWatch = true },
                                onClose = { context.stopService(Intent(context, PlaybackService::class.java)); watchMinimized = false; showWatch = false; browseVm.closeWatch() }
                            )
                        }
                    }

                    if (showWatch && browse.watch.item != null && !showShorts) {
                        val watchKey = browse.watch.item?.let { SavedVideoStore.canonicalKey(it.url, it.id) }.orEmpty()
                        WatchScreen(
                            watch = browse.watch, downloadVm = downloadVm,
                            downloadState = downloadByKey[watchKey],
                            downloaded = watchKey in downloadedKeys,
                            saved = browse.watch.item?.url?.let { browseVm.isSaved(it) } == true,
                            playlists = playlists,
                            adaptive = adaptive,
                            autoplayNext = settings.autoplayNext, playbackQualityPreference = settings.playbackQuality,
                            equalizerEnabled = settings.equalizerEnabled, equalizerPreset = settings.equalizerPreset,
                            onAutoplayChanged = downloadVm::setAutoplayNext,
                            onEqualizerEnabled = downloadVm::setEqualizerEnabled,
                            onEqualizerPreset = downloadVm::setEqualizerPreset,
                            onToggleSave = browseVm::toggleSaved,
                            onCreatePlaylist = browseVm::createLocalPlaylist,
                            onAddToPlaylist = browseVm::addToLocalPlaylist,
                            onDownloadQueued = browseVm::recordDownload,
                            onOpenComments = { browseVm.loadComments(reset = true) }, onLoadMoreComments = { browseVm.loadComments(reset = false) },
                            onLoadMoreRelated = browseVm::loadMoreRelated,
                            onBack = { context.stopService(Intent(context, PlaybackService::class.java)); showWatch = false; watchMinimized = false; browseVm.closeWatch() },
                            onMinimize = { showWatch = false; watchMinimized = true }, onOpenRelated = ::openVideo, onOpenChannel = ::openChannel
                        )
                    }

                    if (showShorts) {
                        ShortsScreen(
                            state = browse, vm = browseVm, initialUrl = shortsInitialUrl,
                            savedUrls = savedKeys,
                            playbackQualityPreference = settings.playbackQuality,
                            equalizerEnabled = settings.equalizerEnabled,
                            equalizerPreset = settings.equalizerPreset,
                            equalizerCustomBands = settings.equalizerCustomBands,
                            downloadByUrl = downloadByKey,
                            onBack = { leaveShorts(restoreWatch = true) },
                            onToggleSave = browseVm::toggleSaved,
                            onDownload = { video, media -> sheetSignalVideo = video; sheetMedia = media },
                            onEqualizerEnabled = downloadVm::setEqualizerEnabled,
                            onEqualizerPreset = downloadVm::setEqualizerPreset,
                            bottomBarVisible = showBottomBar,
                            adaptive = adaptive
                        )
                    }

                    if (showBottomBar) {
                        NovaBottomBar(
                            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(), destination = destination, shortsSelected = showShorts,
                            onHome = {
                                val clean = !showShorts && destination == Destination.YOUTUBE && browse.youtubeQuery.isBlank() && browse.collection.entity == null
                                selectPrimary(Destination.YOUTUBE); browseVm.goHome(refresh = clean)
                            },
                            onShorts = { openShort(null) }, onAi = { selectPrimary(Destination.NOVA_AI) },
                            onLibrary = { selectPrimary(Destination.LIBRARY) }, onYou = { selectPrimary(Destination.YOU) }
                        )
                    }
                }
            }

            if (sheetMedia != null) {
                DownloadQualitySheet(
                    media = sheetMedia!!,
                    onDismiss = { sheetMedia = null; sheetSignalVideo = null },
                    onQueue = { variant ->
                        downloadVm.queueMedia(sheetMedia!!, variant, MediaMode.VIDEO)
                        sheetSignalVideo?.let(browseVm::recordDownload) ?: browseVm.recordDownload(sheetMedia!!)
                        sheetMedia = null; sheetSignalVideo = null
                    },
                    onQueueMp3 = {
                        downloadVm.queueMp3(sheetMedia!!)
                        sheetSignalVideo?.let(browseVm::recordDownload) ?: browseVm.recordDownload(sheetMedia!!)
                        sheetMedia = null; sheetSignalVideo = null
                    }
                )
            }
        }
    }
}

@Composable
private fun NovaBottomBar(modifier: Modifier = Modifier, destination: Destination, shortsSelected: Boolean, onHome: () -> Unit, onShorts: () -> Unit, onAi: () -> Unit, onLibrary: () -> Unit, onYou: () -> Unit) {
    val homeSelected = !shortsSelected && destination == Destination.YOUTUBE
    val aiSelected = !shortsSelected && destination == Destination.NOVA_AI
    val librarySelected = !shortsSelected && destination == Destination.LIBRARY
    val youSelected = !shortsSelected && destination == Destination.YOU
    NavigationBar(modifier = modifier.fillMaxWidth(), containerColor = HarukiSidebar, contentColor = HarukiText, tonalElevation = 0.dp) {
        NavigationBarItem(homeSelected, onHome, { NovaNavIcon(Icons.Rounded.Home, homeSelected) }, label = { Text("Home") }, colors = novaNavColors())
        NavigationBarItem(shortsSelected, onShorts, { NovaNavIcon(Icons.Rounded.PlayCircle, shortsSelected) }, label = { Text("Shorts") }, colors = novaNavColors())
        NavigationBarItem(aiSelected, onAi, {
            val scale by animateFloatAsState(if (aiSelected) 1.08f else 1f, tween(150), label = "ai-nav-scale")
            Surface(
                modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale },
                color = if (aiSelected) HarukiViolet else HarukiCard2,
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.padding(7.dp), tint = Color.White)
            }
        }, label = { Text("Nova AI", fontWeight = FontWeight.Bold) }, colors = novaNavColors())
        NavigationBarItem(librarySelected, onLibrary, { NovaNavIcon(Icons.Rounded.VideoLibrary, librarySelected) }, label = { Text("Library") }, colors = novaNavColors())
        NavigationBarItem(youSelected, onYou, { NovaNavIcon(Icons.Rounded.Person, youSelected) }, label = { Text("You") }, colors = novaNavColors())
    }
}

@Composable
private fun NovaNavigationRail(destination: Destination, shortsSelected: Boolean, large: Boolean, onHome: () -> Unit, onShorts: () -> Unit, onAi: () -> Unit, onLibrary: () -> Unit, onYou: () -> Unit) {
    val homeSelected = !shortsSelected && destination == Destination.YOUTUBE
    val aiSelected = !shortsSelected && destination == Destination.NOVA_AI
    val librarySelected = !shortsSelected && destination == Destination.LIBRARY
    val youSelected = !shortsSelected && destination == Destination.YOU
    NavigationRail(containerColor = HarukiSidebar, modifier = Modifier.fillMaxHeight().statusBarsPadding().navigationBarsPadding().width(if (large) 96.dp else 82.dp)) {
        Spacer(Modifier.height(if (large) 16.dp else 8.dp))
        val itemModifier = Modifier.padding(vertical = if (large) 5.dp else 1.dp)
        NavigationRailItem(homeSelected, onHome, { NovaNavIcon(Icons.Rounded.Home, homeSelected) }, label = { Text("Home") }, modifier = itemModifier)
        NavigationRailItem(shortsSelected, onShorts, { NovaNavIcon(Icons.Rounded.PlayCircle, shortsSelected) }, label = { Text("Shorts") }, modifier = itemModifier)
        NavigationRailItem(aiSelected, onAi, { NovaNavIcon(Icons.Rounded.AutoAwesome, aiSelected, HarukiViolet) }, label = { Text("Nova AI") }, modifier = itemModifier)
        NavigationRailItem(librarySelected, onLibrary, { NovaNavIcon(Icons.Rounded.VideoLibrary, librarySelected) }, label = { Text("Library") }, modifier = itemModifier)
        NavigationRailItem(youSelected, onYou, { NovaNavIcon(Icons.Rounded.Person, youSelected) }, label = { Text("You") }, modifier = itemModifier)
    }
}

@Composable
private fun NovaNavIcon(icon: ImageVector, selected: Boolean, selectedTint: Color? = null) {
    val scale by animateFloatAsState(if (selected) 1.12f else 1f, tween(150), label = "nav-icon-scale")
    val lift by animateFloatAsState(if (selected) -2f else 0f, tween(150), label = "nav-icon-lift")
    Icon(
        icon,
        contentDescription = null,
        tint = selectedTint ?: LocalContentColor.current,
        modifier = Modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            translationY = lift
        }
    )
}

@Composable
private fun novaNavColors() = NavigationBarItemDefaults.colors(selectedIconColor = HarukiPrimary, selectedTextColor = HarukiText, indicatorColor = HarukiCard2, unselectedIconColor = HarukiMuted, unselectedTextColor = HarukiMuted)

@Composable
private fun DestinationScreen(
    destination: Destination, browse: BrowseState, history: List<BrowseVideo>, saved: List<BrowseVideo>,
    playlists: List<LocalPlaylist>, queue: List<DownloadQueueItem>, library: List<LibraryItem>, settings: AppSettings,
    adaptive: NovaAdaptiveInfo, downloadedKeys: Set<String>, browseVm: BrowseViewModel, downloadVm: HarukiViewModel,
    onBackSubpage: () -> Unit, onNavigate: (Destination) -> Unit, onOpenVideo: (BrowseVideo) -> Unit, onOpenShorts: (BrowseVideo?) -> Unit
) {
    when (destination) {
        Destination.YOUTUBE -> YouTubeScreen(browse, history, browseVm, onOpenVideo, onOpenShorts, adaptive, downloadedKeys)
        Destination.NOVA_AI -> NovaAiScreen(browse.novaAi, { prompt, mode -> browseVm.searchWithNovaAi(prompt, library, mode) }, browseVm::clearNovaAi, onOpenVideo, adaptive)
        Destination.LIBRARY -> LibraryHubScreen(saved, history, queue, library, playlists.size, { onNavigate(Destination.SAVED) }, { onNavigate(Destination.HISTORY) }, { onNavigate(Destination.DOWNLOADS) }, { onNavigate(Destination.PLAYLISTS) })
        Destination.YOU -> YouHubScreen(
            { onNavigate(Destination.EQUALIZER) },
            { onNavigate(Destination.SETTINGS) },
            { onNavigate(Destination.ABOUT) }
        )
        Destination.DOWNLOADS -> DownloadsScreen(downloadVm, queue, library, settings, onBackSubpage)
        Destination.PLAYLISTS -> LocalPlaylistsScreen(
            playlists = playlists, onBack = onBackSubpage,
            onPlay = { id, index ->
                val playlist = playlists.firstOrNull { it.id == id }
                val video = playlist?.videos?.getOrNull(index)
                if (playlist != null && video != null) {
                    browseVm.prepareLocalPlaylistPlayback(id, index)
                    onOpenVideo(video)
                }
            },
            onDelete = browseVm::deleteLocalPlaylist
        )
        Destination.HISTORY -> HistoryScreen(browseVm, history, onBackSubpage, onOpenVideo)
        Destination.SAVED -> SavedScreen(saved, onBackSubpage, onOpenVideo, browseVm::removeSaved, browseVm::clearSaved)
        Destination.EQUALIZER -> EqualizerScreen(downloadVm, settings, onBackSubpage)
        Destination.SETTINGS -> SettingsScreen(downloadVm, settings, onBackSubpage)
        Destination.ABOUT -> AboutScreen(onBackSubpage)
    }
}
