package com.harukisolodev.harukistream.player

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.media.AudioManager
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.harukisolodev.harukistream.MainActivity
import com.harukisolodev.harukistream.data.AnalyzedMedia
import com.harukisolodev.harukistream.data.BrowseVideo
import com.harukisolodev.harukistream.data.MediaVariant
import com.harukisolodev.harukistream.data.SettingsRepository
import com.harukisolodev.harukistream.data.EqualizerPreset
import com.harukisolodev.harukistream.extractor.BrowseRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var defaultDataSourceFactory: DataSource.Factory
    private lateinit var bandwidthMeter: DefaultBandwidthMeter
    private var currentPlaybackKey: String = ""
    private var equalizerEngine: NovaEqualizerEngine? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val browseRepository by lazy { BrowseRepository() }
    private var autoplayEnabled: Boolean = false
    private var autoplayQueue: MutableList<BrowseVideo> = mutableListOf()
    private var preferredHeight: Int = 0
    private var backgroundAdvanceInProgress: Boolean = false
    private var prefetchJob: Job? = null
    private var prefetchedUrl: String = ""
    private var prefetchedPayload: BrowseRepository.WatchPayload? = null
    private var prefetchedVariant: MediaVariant? = null
    private var prefetchedAudioMime: String = "audio/mp4"
    private var prefetchedHeight: Int = -1
    private var appInForeground: Boolean = true
    private var currentBackgroundReduced: Boolean = false
    private var currentBrowseVideo: BrowseVideo? = null
    private val previousStack: MutableList<BrowseVideo> = mutableListOf()
    private var suppressPreviousPush: Boolean = false

    // Use matching custom buttons for both sides so Android renders Previous and Next
    // with the same visual weight instead of mixing a native player action with a
    // smaller custom action.
    private val notificationPreviousButton by lazy {
        CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setDisplayName("Previous")
            .setSessionCommand(PlaybackCommands.SKIP_PREVIOUS)
            .setSlots(CommandButton.SLOT_BACK)
            .build()
    }

    // ExoPlayer only contains the currently extracted direct stream, while NovaTube's
    // upcoming videos live in autoplayQueue. Publish that queue as a real Media3
    // forward-slot action so Android's lock-screen/notification controls show Next.
    private val notificationNextButton by lazy {
        CommandButton.Builder(CommandButton.ICON_NEXT)
            .setDisplayName("Next")
            .setSessionCommand(PlaybackCommands.SKIP_NEXT)
            .setSlots(CommandButton.SLOT_FORWARD)
            .build()
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        bandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(this)
        defaultDataSourceFactory = PlaybackDataSourceFactory.create(this, bandwidthMeter)
        // Direct extracted streams benefit from a healthy buffer, but v0.8.0 waited too long
        // before first play/rebuffer. Keep enough runway for HD while making startup and recovery
        // substantially more responsive.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(45_000, 120_000, 2_500, 6_000)
            .setBackBuffer(12_000, true)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(true)
            .build()
        val audioManager = getSystemService(AudioManager::class.java)
        val audioSessionId = runCatching { audioManager.generateAudioSessionId() }.getOrDefault(0)
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
            .apply {
                if (audioSessionId > 0) setAudioSessionId(audioSessionId)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    true
                )
                playWhenReady = true
            }
        equalizerEngine = NovaEqualizerEngine(audioSessionId)
        serviceScope.launch {
            SettingsRepository(this@PlaybackService).settings
                .map { settings -> Triple(settings.equalizerEnabled, settings.equalizerPreset, settings.equalizerCustomBands) }
                .distinctUntilChanged()
                .collect { (enabled, preset, customBands) ->
                    val curve = if (preset == EqualizerPreset.CUSTOM) customBands else preset.bandsDb
                    equalizerEngine?.applyCurve(enabled, curve)
                }
        }

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                PlaybackNetworkCoordinator.update(isPlaying, player.playbackState)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                PlaybackNetworkCoordinator.update(player.isPlaying, playbackState)
                if (playbackState == Player.STATE_ENDED && autoplayEnabled) {
                    advanceAutoplayInService()
                }
            }
        })

        val callback = object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                    .buildUpon()
                    .add(PlaybackCommands.PLAY_VARIANT)
                    .add(PlaybackCommands.SKIP_PREVIOUS)
                    .add(PlaybackCommands.SKIP_NEXT)
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: androidx.media3.session.SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                return when (customCommand.customAction) {
                    PlaybackCommands.ACTION_PLAY_VARIANT -> try {
                        playVariant(args)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    } catch (t: Throwable) {
                        Futures.immediateFuture(
                            SessionResult(
                                SessionResult.RESULT_ERROR_UNKNOWN,
                                Bundle().apply { putString("error", t.message ?: t.javaClass.simpleName) }
                            )
                        )
                    }
                    PlaybackCommands.ACTION_SKIP_PREVIOUS -> {
                        advancePreviousInService()
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    PlaybackCommands.ACTION_SKIP_NEXT -> {
                        // Manual Next should work from the notification even when the
                        // visible Watch screen is minimized/backgrounded. A user tap is
                        // an explicit skip, so it isn't blocked by the Autoplay toggle.
                        advanceAutoplayInService(force = true)
                        Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    else -> Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                }
            }
        }
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setMediaButtonPreferences(listOf(notificationPreviousButton, notificationNextButton))
            .build()
    }

    @OptIn(UnstableApi::class)
    private fun playVariant(args: Bundle) {
        updateAutoplayState(args)

        val mediaId = args.getString(PlaybackCommands.ARG_MEDIA_ID).orEmpty()
        val pageUrl = args.getString(PlaybackCommands.ARG_PAGE_URL).orEmpty()
        val title = args.getString(PlaybackCommands.ARG_TITLE).orEmpty()
        val uploader = args.getString(PlaybackCommands.ARG_UPLOADER).orEmpty()
        val artwork = args.getString(PlaybackCommands.ARG_ARTWORK).orEmpty()
        val videoUrl = args.getString(PlaybackCommands.ARG_VIDEO_URL).orEmpty()
        val variantId = args.getString(PlaybackCommands.ARG_VARIANT_ID).orEmpty()
        val audioUrl = args.getString(PlaybackCommands.ARG_AUDIO_URL).orEmpty()
        val videoMime = args.getString(PlaybackCommands.ARG_VIDEO_MIME).orEmpty().ifBlank { "video/mp4" }
        val audioMime = args.getString(PlaybackCommands.ARG_AUDIO_MIME).orEmpty().ifBlank { "audio/mp4" }
        val explicitQuality = args.getBoolean(PlaybackCommands.ARG_EXPLICIT_QUALITY, false)
        val backgroundReduced = args.getBoolean(PlaybackCommands.ARG_BACKGROUND_REDUCED, false)
        PlaybackNetworkCoordinator.setPlaybackHeight(preferredHeight)
        @Suppress("DEPRECATION")
        val subtitleBundles = args.getParcelableArrayList<Bundle>(PlaybackCommands.ARG_SUBTITLES).orEmpty()
        val subtitleConfigurations = subtitleBundles.mapNotNull { subtitleArgs ->
            val url = subtitleArgs.getString(PlaybackCommands.ARG_SUBTITLE_URL).orEmpty()
            if (url.isBlank()) return@mapNotNull null
            val mime = subtitleArgs.getString(PlaybackCommands.ARG_SUBTITLE_MIME).orEmpty().ifBlank { "text/vtt" }
            val language = subtitleArgs.getString(PlaybackCommands.ARG_SUBTITLE_LANGUAGE).orEmpty()
            val label = subtitleArgs.getString(PlaybackCommands.ARG_SUBTITLE_LABEL).orEmpty()
            val id = subtitleArgs.getString(PlaybackCommands.ARG_SUBTITLE_ID).orEmpty()
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(url))
                .setMimeType(mime)
                .apply {
                    if (id.isNotBlank()) setId(id)
                    if (language.isNotBlank()) setLanguage(language)
                    if (label.isNotBlank()) setLabel(label)
                }
                .build()
        }
        val requestHeaders = args.getString(PlaybackCommands.ARG_REQUEST_HEADERS).orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val index = line.indexOf(':')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }
            .filter { it.first.isNotBlank() && it.second.isNotBlank() }
            .toMap()
        require(videoUrl.isNotBlank()) { "Missing video stream URL" }

        // Reconnecting the Watch UI (fullscreen/miniplayer/normal view) sends the
        // same PLAY_VARIANT command again. Reuse the already buffered source instead
        // of calling setMediaSource/prepare and forcing another network buffer.
        val subtitleKey = subtitleConfigurations.joinToString(";") {
            listOf(it.id.orEmpty(), it.uri.toString(), it.mimeType.orEmpty(), it.language.orEmpty(), it.label.orEmpty()).joinToString("~")
        }
        val playbackKey = listOf(
            mediaId, variantId, videoUrl, audioUrl, videoMime, audioMime,
            subtitleKey, requestHeaders.toSortedMap().toString()
        ).joinToString("|")
        val incomingItem = BrowseVideo(
            id = mediaId.ifBlank { pageUrl },
            url = pageUrl.ifBlank { mediaId.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }.orEmpty() },
            title = title,
            uploader = uploader,
            thumbnailUrl = artwork,
            service = "YouTube"
        )
        val sameMediaIdentity = player.currentMediaItem?.mediaId == mediaId && mediaId.isNotBlank()
        if (sameMediaIdentity && currentBackgroundReduced && appInForeground && !explicitQuality &&
            player.playbackState != Player.STATE_IDLE
        ) {
            // Returning from a background low-bandwidth item must not re-prepare a
            // higher-resolution source just because the Watch composable reconnected.
            // Keep playback continuous; the user can still explicitly choose quality.
            currentBrowseVideo = incomingItem.takeIf { it.url.isNotBlank() } ?: currentBrowseVideo
            updateSessionActivity(currentBrowseVideo, mediaId, title)
            ensureVideoTrackEnabled()
            return
        }
        if (currentPlaybackKey == playbackKey &&
            player.currentMediaItem?.mediaId == mediaId &&
            player.playbackState != Player.STATE_IDLE
        ) {
            currentBrowseVideo = incomingItem.takeIf { it.url.isNotBlank() } ?: currentBrowseVideo
            updateSessionActivity(currentBrowseVideo, mediaId, title)
            return
        }

        val sameMedia = player.currentMediaItem?.mediaId == mediaId
        if (!sameMedia && !suppressPreviousPush) {
            currentBrowseVideo?.takeIf { it.url.isNotBlank() }?.let { previous ->
                previousStack.removeAll { it.url == previous.url }
                previousStack.add(previous)
                while (previousStack.size > 40) previousStack.removeAt(0)
            }
        }
        suppressPreviousPush = false
        val resume = if (sameMedia) player.currentPosition.coerceAtLeast(0L) else 0L
        val wasPlaying = player.playWhenReady || player.isPlaying
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .apply { if (uploader.isNotBlank()) setArtist(uploader) }
            .apply { if (artwork.isNotBlank()) setArtworkUri(Uri.parse(artwork)) }
            .build()

        val videoBuilder = MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(videoUrl)
            .setMimeType(videoMime)
            .setCustomCacheKey("video:$mediaId:${variantId.ifBlank { videoUrl.hashCode().toString() }}")
            .setMediaMetadata(metadata)

        if (subtitleConfigurations.isNotEmpty()) {
            videoBuilder.setSubtitleConfigurations(subtitleConfigurations)
        }
        val videoItem = videoBuilder.build()

        val sourceFactory = if (requestHeaders.isEmpty()) {
            defaultDataSourceFactory
        } else {
            PlaybackDataSourceFactory.create(this, bandwidthMeter, requestHeaders)
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(sourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(7))
        val videoSource = mediaSourceFactory.createMediaSource(videoItem)

        if (audioUrl.isBlank()) {
            player.setMediaSource(videoSource)
        } else {
            val audioItem = MediaItem.Builder()
                .setMediaId("$mediaId-audio")
                .setUri(audioUrl)
                .setMimeType(audioMime)
                .setCustomCacheKey("audio:$mediaId:${audioUrl.hashCode()}")
                .build()
            val audioSource = mediaSourceFactory.createMediaSource(audioItem)
            player.setMediaSource(MergingMediaSource(true, true, videoSource, audioSource))
        }
        player.prepare()
        currentPlaybackKey = playbackKey
        currentBackgroundReduced = backgroundReduced
        currentBrowseVideo = incomingItem.takeIf { it.url.isNotBlank() } ?: currentBrowseVideo
        updateSessionActivity(currentBrowseVideo, mediaId, title)
        // Keep the video renderer selected even when the app is backgrounded.
        // Newly started background items are already capped near 360p, so leaving
        // the renderer warm avoids a black decoder/surface warm-up when the user
        // taps the media notification to return to Watch.
        ensureVideoTrackEnabled()
        if (resume > 0) player.seekTo(resume)
        player.playWhenReady = if (sameMedia) wasPlaying else true
    }

    private fun updateAutoplayState(args: Bundle) {
        if (args.containsKey(PlaybackCommands.ARG_AUTOPLAY_ENABLED)) {
            autoplayEnabled = args.getBoolean(PlaybackCommands.ARG_AUTOPLAY_ENABLED, false)
        }
        if (args.containsKey(PlaybackCommands.ARG_PREFERRED_HEIGHT)) {
            preferredHeight = args.getInt(PlaybackCommands.ARG_PREFERRED_HEIGHT, 0).coerceAtLeast(0)
        }
        if (args.containsKey(PlaybackCommands.ARG_AUTOPLAY_QUEUE)) {
            @Suppress("DEPRECATION")
            val bundles = args.getParcelableArrayList<Bundle>(PlaybackCommands.ARG_AUTOPLAY_QUEUE).orEmpty()
            autoplayQueue = bundles.mapNotNull(::browseVideoFromBundle)
                .filter { it.url.isNotBlank() }
                .distinctBy { it.url }
                .toMutableList()
            prefetchNextAutoplay()
        }
    }

    private fun prefetchNextAutoplay() {
        val target = autoplayQueue.firstOrNull()
        if (target == null) {
            prefetchJob?.cancel()
            prefetchedUrl = ""
            prefetchedPayload = null
            prefetchedVariant = null
            return
        }
        val targetHeight = playbackTargetHeight()
        if (prefetchedUrl == target.url && prefetchedPayload != null && prefetchedVariant != null && prefetchedHeight == targetHeight) return
        prefetchJob?.cancel()
        prefetchedUrl = target.url
        prefetchedPayload = null
        prefetchedVariant = null
        prefetchedHeight = targetHeight
        prefetchJob = serviceScope.launch {
            val payload = runCatching {
                withContext(Dispatchers.IO) { browseRepository.openYouTube(target.url) }
            }.getOrNull()
            if (payload == null) {
                if (prefetchedUrl == target.url) prefetchedUrl = ""
                return@launch
            }

            val selectedVariant = chooseVariant(payload.media, targetHeight, preferProgressive = !appInForeground)
            val selectedAudio = payload.media.audioTracks.firstOrNull { it.original }
                ?: payload.media.audioTracks.firstOrNull()
            val effectiveVariant = if (selectedVariant?.separateAudio == true && selectedAudio != null) {
                selectedVariant.copy(audioUrl = selectedAudio.url)
            } else selectedVariant

            if (effectiveVariant == null) {
                if (prefetchedUrl == target.url) prefetchedUrl = ""
                return@launch
            }

            prefetchedPayload = payload
            prefetchedVariant = effectiveVariant
            prefetchedAudioMime = selectedAudio?.mimeType.orEmpty().ifBlank { "audio/mp4" }

            // Do not steal bandwidth from a struggling current stream. Wait until the
            // active item has a healthy reserve, then warm the exact cache keys that
            // the next playback will use. This turns background/notification Next into
            // a cache-backed start instead of a cold CDN request.
            for (attempt in 0 until 40) {
                if (prefetchedUrl != target.url) return@launch
                val bufferedAhead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                val nearEnd = player.duration > 0 && player.duration - player.currentPosition < 45_000L
                if (player.playbackState != Player.STATE_BUFFERING && (bufferedAhead >= 55_000L || (nearEnd && bufferedAhead >= 25_000L))) {
                    break
                }
                delay(500L)
            }

            // At 720p+ every spare connection belongs to the current video. Metadata is
            // still prefetched, but byte-cache warming is disabled until lower qualities so
            // the next item cannot steal throughput from a demanding direct stream.
            if (preferredHeight < 720) {
                runCatching {
                    withContext(Dispatchers.IO) { warmNextStreamCache(payload.media, target, effectiveVariant) }
                }
            }
        }
    }

    private fun warmNextStreamCache(media: AnalyzedMedia, item: BrowseVideo, variant: MediaVariant) {
        val mediaId = media.mediaId.ifBlank { item.id }
        val videoKey = "video:$mediaId:${variant.id.ifBlank { variant.videoUrl.hashCode().toString() }}"
        prefetchPrefix(
            url = variant.videoUrl,
            cacheKey = videoKey,
            headers = variant.requestHeaders,
            maxBytes = 4L * 1024L * 1024L
        )
        if (variant.audioUrl.isNotBlank()) {
            val audioKey = "audio:$mediaId:${variant.audioUrl.hashCode()}"
            prefetchPrefix(
                url = variant.audioUrl,
                cacheKey = audioKey,
                headers = variant.requestHeaders,
                maxBytes = 768L * 1024L
            )
        }
    }

    private fun prefetchPrefix(
        url: String,
        cacheKey: String,
        headers: Map<String, String>,
        maxBytes: Long
    ) {
        if (url.isBlank() || maxBytes <= 0L) return
        val dataSource = PlaybackDataSourceFactory.create(this, bandwidthMeter, headers).createDataSource()
        val dataSpec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(cacheKey)
            .setPosition(0L)
            .setLength(maxBytes)
            .build()
        val buffer = ByteArray(64 * 1024)
        var remaining = maxBytes
        try {
            dataSource.open(dataSpec)
            while (remaining > 0L) {
                // The current video always wins. If its reserve falls or ExoPlayer
                // starts buffering, abandon this optional next-item warmup immediately.
                if (::player.isInitialized) {
                    val ahead = (player.bufferedPosition - player.currentPosition).coerceAtLeast(0L)
                    if (player.playbackState == Player.STATE_BUFFERING || (player.isPlaying && ahead < 35_000L)) break
                }
                val read = dataSource.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read == C.RESULT_END_OF_INPUT) break
                if (read <= 0) break
                remaining -= read.toLong()
            }
        } finally {
            runCatching { dataSource.close() }
        }
    }

    private fun advanceAutoplayInService(force: Boolean = false) {
        if (backgroundAdvanceInProgress || (!autoplayEnabled && !force)) return
        val next = autoplayQueue.firstOrNull() ?: return
        autoplayQueue.removeAt(0)
        backgroundAdvanceInProgress = true

        serviceScope.launch {
            try {
                val usePrefetched = prefetchedUrl == next.url
                val cachedPayload = if (usePrefetched) prefetchedPayload else null
                val cachedVariant = if (usePrefetched) prefetchedVariant else null
                val cachedAudioMime = if (usePrefetched) prefetchedAudioMime else "audio/mp4"
                val payload = cachedPayload ?: withContext(Dispatchers.IO) { browseRepository.openYouTube(next.url) }
                if (usePrefetched) {
                    prefetchedUrl = ""
                    prefetchedPayload = null
                    prefetchedVariant = null
                }
                val selectedVariant = cachedVariant ?: chooseVariant(
                    payload.media,
                    playbackTargetHeight(),
                    preferProgressive = !appInForeground
                ) ?: return@launch
                val selectedAudio = payload.media.audioTracks.firstOrNull { it.original }
                    ?: payload.media.audioTracks.firstOrNull()
                val effectiveVariant = if (cachedVariant != null) {
                    cachedVariant
                } else if (selectedVariant.separateAudio && selectedAudio != null) {
                    selectedVariant.copy(audioUrl = selectedAudio.url)
                } else selectedVariant

                // Keep background autoplay open-ended. Once the queue becomes short,
                // seed it with fresh related long-form videos from the newly loaded item.
                if (autoplayQueue.size < 5) {
                    val known = buildSet {
                        add(next.url)
                        autoplayQueue.forEach { add(it.url) }
                    }
                    payload.related
                        .asSequence()
                        .filter { !it.shortForm && !it.url.contains("/shorts/", ignoreCase = true) }
                        .filter { it.url !in known }
                        .take(8)
                        .forEach { autoplayQueue.add(it) }
                }

                val internalArgs = buildPlaybackArgs(
                    media = payload.media,
                    item = next,
                    variant = effectiveVariant,
                    audioMime = if (cachedVariant != null) cachedAudioMime else selectedAudio?.mimeType.orEmpty().ifBlank { "audio/mp4" },
                    payload = payload
                ).apply {
                    putBoolean(PlaybackCommands.ARG_BACKGROUND_REDUCED, !appInForeground)
                }
                playVariant(internalArgs)
                PlaybackAutoplayBridge.publish(PlaybackAutoplayBridge.Advance(next, payload))
                prefetchNextAutoplay()
            } catch (_: Throwable) {
                // Skip a broken/removed video and try the next queued item rather
                // than leaving background music permanently stopped.
                backgroundAdvanceInProgress = false
                if ((autoplayEnabled || force) && autoplayQueue.isNotEmpty()) advanceAutoplayInService(force)
                return@launch
            } finally {
                backgroundAdvanceInProgress = false
            }
        }
    }

    private fun chooseVariant(
        media: AnalyzedMedia,
        requestedHeight: Int,
        preferProgressive: Boolean = false
    ): MediaVariant? {
        val variants = media.videoVariants.filter { it.videoUrl.isNotBlank() }
        if (variants.isEmpty()) return null
        val target = requestedHeight.takeIf { it > 0 } ?: 720
        val within = variants.filter { it.qualityHeight > 0 && it.qualityHeight <= target }
        fun smoothest(items: List<MediaVariant>): MediaVariant? = items.sortedWith(
            compareByDescending<MediaVariant> { it.qualityHeight }
                .thenBy { if (it.fps > 0) it.fps else 30 }
                .thenBy { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
                .thenBy { it.separateAudio }
        ).firstOrNull()
        if (preferProgressive) {
            smoothest(within.filterNot { it.separateAudio })?.let { return it }
        }
        return smoothest(within)
            ?: variants.filter { it.qualityHeight > 0 }.minByOrNull { kotlin.math.abs(it.qualityHeight - target) }
            ?: variants.firstOrNull()
    }

    private fun playbackTargetHeight(): Int {
        if (appInForeground) return preferredHeight
        val requested = preferredHeight.takeIf { it > 0 } ?: 360
        return minOf(requested, 360)
    }

    private fun buildPlaybackArgs(
        media: AnalyzedMedia,
        item: BrowseVideo,
        variant: MediaVariant,
        audioMime: String,
        payload: BrowseRepository.WatchPayload
    ): Bundle {
        val subtitleBundles = ArrayList<Bundle>(payload.details.subtitles.size)
        payload.details.subtitles.forEach { subtitle ->
            subtitleBundles += Bundle().apply {
                putString(PlaybackCommands.ARG_SUBTITLE_ID, subtitle.id)
                putString(PlaybackCommands.ARG_SUBTITLE_URL, subtitle.url)
                putString(PlaybackCommands.ARG_SUBTITLE_MIME, subtitle.mimeType.ifBlank { "text/vtt" })
                putString(PlaybackCommands.ARG_SUBTITLE_LANGUAGE, subtitle.languageCode)
                putString(PlaybackCommands.ARG_SUBTITLE_LABEL, subtitle.label)
            }
        }
        val queueBundles = ArrayList<Bundle>(autoplayQueue.size)
        autoplayQueue.forEach { queueBundles += browseVideoToBundle(it) }
        return Bundle().apply {
            putString(PlaybackCommands.ARG_MEDIA_ID, media.mediaId.ifBlank { item.id })
            putString(PlaybackCommands.ARG_PAGE_URL, media.sourceUrl.ifBlank { item.url })
            putString(PlaybackCommands.ARG_TITLE, media.title.ifBlank { item.title })
            putString(PlaybackCommands.ARG_UPLOADER, media.uploader.ifBlank { item.uploader })
            putString(PlaybackCommands.ARG_ARTWORK, media.thumbnailUrl.ifBlank { item.thumbnailUrl })
            putString(PlaybackCommands.ARG_VIDEO_URL, variant.videoUrl)
            putString(PlaybackCommands.ARG_VARIANT_ID, variant.id)
            putString(PlaybackCommands.ARG_AUDIO_URL, variant.audioUrl)
            putString(PlaybackCommands.ARG_VIDEO_MIME, variant.mimeType.ifBlank { "video/mp4" })
            putString(PlaybackCommands.ARG_AUDIO_MIME, audioMime)
            putString(PlaybackCommands.ARG_REQUEST_HEADERS, variant.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" })
            putParcelableArrayList(PlaybackCommands.ARG_SUBTITLES, subtitleBundles)
            putBoolean(PlaybackCommands.ARG_AUTOPLAY_ENABLED, autoplayEnabled)
            putInt(PlaybackCommands.ARG_PREFERRED_HEIGHT, preferredHeight)
            putBoolean(PlaybackCommands.ARG_EXPLICIT_QUALITY, false)
            putParcelableArrayList(PlaybackCommands.ARG_AUTOPLAY_QUEUE, queueBundles)
        }
    }

    private fun browseVideoToBundle(video: BrowseVideo): Bundle = Bundle().apply {
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

    private fun browseVideoFromBundle(bundle: Bundle): BrowseVideo? {
        val url = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_URL).orEmpty()
        if (url.isBlank()) return null
        return BrowseVideo(
            id = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_ID).orEmpty().ifBlank { url },
            url = url,
            title = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_TITLE).orEmpty(),
            uploader = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_UPLOADER).orEmpty(),
            thumbnailUrl = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_ARTWORK).orEmpty(),
            durationSeconds = bundle.getLong(PlaybackCommands.ARG_AUTOPLAY_DURATION, 0L),
            viewCount = bundle.getLong(PlaybackCommands.ARG_AUTOPLAY_VIEWS, -1L),
            uploadText = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_UPLOAD_TEXT).orEmpty(),
            service = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_SERVICE).orEmpty().ifBlank { "YouTube" },
            uploaderAvatarUrl = bundle.getString(PlaybackCommands.ARG_AUTOPLAY_AVATAR).orEmpty(),
            uploaderVerified = bundle.getBoolean(PlaybackCommands.ARG_AUTOPLAY_VERIFIED, false)
        )
    }

    private fun advancePreviousInService() {
        val previous = previousStack.removeLastOrNull()
        if (previous == null) {
            if (::player.isInitialized) player.seekTo(0L)
            return
        }
        currentBrowseVideo?.takeIf { it.url.isNotBlank() }?.let { current ->
            autoplayQueue.removeAll { it.url == current.url }
            autoplayQueue.add(0, current)
        }
        suppressPreviousPush = true
        backgroundAdvanceInProgress = true
        serviceScope.launch {
            try {
                val payload = withContext(Dispatchers.IO) { browseRepository.openYouTube(previous.url) }
                val variant = chooseVariant(payload.media, playbackTargetHeight(), preferProgressive = !appInForeground)
                    ?: return@launch
                val audio = payload.media.audioTracks.firstOrNull { it.original } ?: payload.media.audioTracks.firstOrNull()
                val effective = if (variant.separateAudio && audio != null) variant.copy(audioUrl = audio.url) else variant
                val args = buildPlaybackArgs(
                    media = payload.media,
                    item = previous,
                    variant = effective,
                    audioMime = audio?.mimeType.orEmpty().ifBlank { "audio/mp4" },
                    payload = payload
                ).apply { putBoolean(PlaybackCommands.ARG_BACKGROUND_REDUCED, !appInForeground) }
                playVariant(args)
                PlaybackAutoplayBridge.publish(PlaybackAutoplayBridge.Advance(previous, payload))
                prefetchNextAutoplay()
            } catch (_: Throwable) {
                suppressPreviousPush = false
            } finally {
                backgroundAdvanceInProgress = false
            }
        }
    }

    private fun pauseForShortsInternal(): Boolean {
        if (!::player.isInitialized) return false
        val shouldResume = player.isPlaying || player.playWhenReady
        player.pause()
        PlaybackNetworkCoordinator.update(false, player.playbackState)
        return shouldResume
    }

    private fun resumeAfterShortsInternal(shouldResume: Boolean) {
        if (!::player.isInitialized) return
        ensureVideoTrackEnabled()
        if (shouldResume) player.play()
        PlaybackNetworkCoordinator.update(player.isPlaying, player.playbackState)
    }

    private fun setAppForeground(foreground: Boolean) {
        if (appInForeground == foreground) {
            if (foreground) ensureVideoTrackEnabled()
            return
        }
        appInForeground = foreground
        // Do NOT disable the currently playing video renderer when NovaTube goes
        // into the background. Disabling it saved bandwidth, but returning through
        // the media notification had to re-enable/restart the video decoder and the
        // new PlayerView showed black while audio kept playing. Background-started
        // items are already capped near 360p, which gives us the bandwidth saving
        // without sacrificing instant visual restoration.
        if (::player.isInitialized) ensureVideoTrackEnabled()
        // Re-evaluate the next warm-cache target. Background playback intentionally
        // caps the next item around 360p and prefers progressive streams.
        prefetchNextAutoplay()
    }

    private fun ensureVideoTrackEnabled() {
        if (!::player.isInitialized) return
        val params = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
            .build()
        player.trackSelectionParameters = params
    }

    private fun updateSessionActivity(item: BrowseVideo?, mediaId: String, title: String) {
        val url = item?.url.orEmpty().ifBlank {
            mediaId.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }.orEmpty()
        }
        if (url.isBlank()) return
        val intent = Intent(this, MainActivity::class.java).apply {
            action = PlaybackLaunch.ACTION_OPEN_NOW_PLAYING
            putExtra(PlaybackLaunch.EXTRA_VIDEO_URL, url)
            putExtra(PlaybackLaunch.EXTRA_MEDIA_ID, mediaId)
            putExtra(PlaybackLaunch.EXTRA_TITLE, title)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            this,
            7002,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session?.setSessionActivity(pending)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // Background audio should behave like a music app. Keep an actively
        // playing/buffering session (or an in-progress autoplay transition) alive.
        // When playback is genuinely inactive, delegate to MediaSessionService so
        // it can terminate the foreground-service lifecycle safely.
        val ongoing = ::player.isInitialized && (
            player.isPlaying ||
                player.playbackState == Player.STATE_BUFFERING ||
                backgroundAdvanceInProgress
            )
        if (ongoing) return
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (activeInstance === this) activeInstance = null
        serviceScope.cancel()
        session?.release()
        session = null
        equalizerEngine?.release()
        equalizerEngine = null
        if (::player.isInitialized) player.release()
        PlaybackAutoplayBridge.clear()
        PlaybackNetworkCoordinator.reset()
        super.onDestroy()
    }

    companion object {
        @Volatile private var activeInstance: PlaybackService? = null

        /**
         * Called by MainActivity on the main thread without starting a second service.
         * Apply this synchronously so a notification tap re-enables/keeps the video
         * renderer ready before Compose attaches the new PlayerView surface.
         */
        fun notifyAppForeground(foreground: Boolean) {
            activeInstance?.setAppForeground(foreground)
        }

        /** Preserve the long-form player while Shorts temporarily owns the visible player UI. */
        fun pauseForShorts(): Boolean = activeInstance?.pauseForShortsInternal() ?: false

        fun resumeAfterShorts(shouldResume: Boolean) {
            activeInstance?.resumeAfterShortsInternal(shouldResume)
        }

        /** Live preview used by the equalizer sliders without writing DataStore every frame. */
        fun previewEqualizer(enabled: Boolean, bandsDb: List<Float>) {
            activeInstance?.equalizerEngine?.applyCurve(enabled, bandsDb)
        }
    }

}
