package com.harukisolodev.harukistream.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.harukisolodev.harukistream.data.*
import com.harukisolodev.harukistream.download.DownloadQueueStore
import com.harukisolodev.harukistream.download.DownloadRequestStore
import com.harukisolodev.harukistream.download.StoredQueueDownload
import com.harukisolodev.harukistream.download.DownloadWorker
import com.harukisolodev.harukistream.download.DownloadCancellationRegistry
import com.harukisolodev.harukistream.download.PendingDownload
import com.harukisolodev.harukistream.extractor.HarukiExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.io.File

class HarukiViewModel(app: Application) : AndroidViewModel(app) {
    private val extractor = HarukiExtractor()
    private val libraryStore = LibraryStore(app)
    private val settingsRepo = SettingsRepository(app)
    private val workManager = WorkManager.getInstance(app)
    private val requestStore = DownloadRequestStore(app)
    private val queueStore = DownloadQueueStore(app)

    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings()
    )

    private val _state = MutableStateFlow(HarukiUiState())
    val state: StateFlow<HarukiUiState> = _state.asStateFlow()

    private val _library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val library: StateFlow<List<LibraryItem>> = _library.asStateFlow()

    private val _playing = MutableStateFlow<LibraryItem?>(null)
    val playing: StateFlow<LibraryItem?> = _playing.asStateFlow()

    private var analyzeJob: Job? = null
    private var autoAnalyzeJob: Job? = null
    private var queueMonitorJob: Job? = null
    private val finishHandled = mutableSetOf<String>()
    private var lastAnalyzedUrl = ""

    private val _downloadQueue = MutableStateFlow<List<DownloadQueueItem>>(emptyList())
    val downloadQueue: StateFlow<List<DownloadQueueItem>> = _downloadQueue.asStateFlow()

    init {
        refreshLibrary()
        startQueueMonitor()
    }

    fun setUrl(url: String) {
        val current = _state.value
        val changedFromAnalyzed = url.trim() != lastAnalyzedUrl
        _state.value = current.copy(
            url = url,
            media = if (changedFromAnalyzed) null else current.media,
            selectedVariantId = if (changedFromAnalyzed) "" else current.selectedVariantId,
            isAnalyzing = false,
            status = if (changedFromAnalyzed) "Ready for link" else current.status,
            error = ""
        )
    }

    fun clearUrl() {
        lastAnalyzedUrl = ""
        analyzeJob?.cancel()
        autoAnalyzeJob?.cancel()
        _state.value = HarukiUiState(mode = settings.value.defaultMode)
    }

    fun setMode(mode: MediaMode) {
        val media = _state.value.media
        val variants = if (mode == MediaMode.VIDEO) media?.videoVariants.orEmpty() else media?.audioVariants.orEmpty()
        _state.value = _state.value.copy(
            mode = mode,
            selectedVariantId = chooseVariant(variants, settings.value.defaultQuality)?.id.orEmpty(),
            error = if (variants.isEmpty() && media != null) "No ${mode.name.lowercase()} stream is available for this link." else ""
        )
    }

    fun selectVariant(id: String) { _state.value = _state.value.copy(selectedVariantId = id) }

    fun analyze(force: Boolean = false) {
        val url = _state.value.url.trim()
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        if (!force && url == lastAnalyzedUrl && _state.value.media != null) return
        analyzeJob?.cancel()
        analyzeJob = viewModelScope.launch {
            _state.value = _state.value.copy(isAnalyzing = true, error = "", status = "Reading link…")
            try {
                val media = withContext(Dispatchers.IO) { extractor.analyze(url) }
                lastAnalyzedUrl = url
                val preferredMode = when {
                    settings.value.defaultMode == MediaMode.VIDEO && media.videoVariants.isNotEmpty() -> MediaMode.VIDEO
                    settings.value.defaultMode == MediaMode.AUDIO && media.audioVariants.isNotEmpty() -> MediaMode.AUDIO
                    media.videoVariants.isNotEmpty() -> MediaMode.VIDEO
                    else -> MediaMode.AUDIO
                }
                val variants = if (preferredMode == MediaMode.VIDEO) media.videoVariants else media.audioVariants
                _state.value = _state.value.copy(
                    isAnalyzing = false,
                    media = media,
                    mode = preferredMode,
                    selectedVariantId = chooseVariant(variants, settings.value.defaultQuality)?.id.orEmpty(),
                    status = if (libraryStore.hasMedia(media.mediaId)) "Already in Library • you can download again" else "Ready",
                    error = ""
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isAnalyzing = false,
                    media = null,
                    selectedVariantId = "",
                    status = "Could not read this link",
                    error = e.message ?: "Could not analyze this link."
                )
            }
        }
    }

    fun scheduleAutoAnalyze() {
        if (!settings.value.autoAnalyze) return
        autoAnalyzeJob?.cancel()
        autoAnalyzeJob = viewModelScope.launch {
            delay(550)
            analyze(false)
        }
    }

    fun queueMp3(media: AnalyzedMedia): String? {
        val source = media.audioVariants.maxByOrNull { it.bitrate.takeIf { b -> b > 0 } ?: it.qualityHeight } ?: return null
        val pending = PendingDownload(
            mediaId = media.mediaId,
            title = media.title,
            sourceUrl = media.sourceUrl,
            thumbnail = media.thumbnailUrl,
            quality = "MP3 • 192 kbps",
            mediaUrl = source.videoUrl,
            audioUrl = "",
            mediaFallbackUrls = source.fallbackUrls,
            requestHeaders = source.requestHeaders,
            mimeType = "audio/mpeg",
            extension = "mp3",
            mode = MediaMode.AUDIO.name,
            transcodeToMp3 = true,
            sourceMimeType = source.mimeType,
            sourceExtension = source.extension
        )
        return runCatching { enqueuePending(pending) }.getOrNull()
    }

    fun queueMedia(media: AnalyzedMedia, variant: MediaVariant, mode: MediaMode = MediaMode.VIDEO): String? {
        val pending = PendingDownload(
            mediaId = media.mediaId,
            title = media.title,
            sourceUrl = media.sourceUrl,
            thumbnail = media.thumbnailUrl,
            quality = variant.label,
            mediaUrl = variant.videoUrl,
            audioUrl = variant.audioUrl,
            mediaFallbackUrls = variant.fallbackUrls,
            requestHeaders = variant.requestHeaders,
            mimeType = variant.mimeType,
            extension = variant.extension,
            mode = mode.name
        )
        return runCatching { enqueuePending(pending) }.getOrNull()
    }

    fun startDownload() {
        val s = _state.value
        val media = s.media ?: return
        val variants = if (s.mode == MediaMode.VIDEO) media.videoVariants else media.audioVariants
        val variant = variants.firstOrNull { it.id == s.selectedVariantId } ?: variants.firstOrNull() ?: return
        val pending = PendingDownload(
            mediaId = media.mediaId,
            title = media.title,
            sourceUrl = media.sourceUrl,
            thumbnail = media.thumbnailUrl,
            quality = variant.label,
            mediaUrl = variant.videoUrl,
            audioUrl = variant.audioUrl,
            mediaFallbackUrls = variant.fallbackUrls,
            requestHeaders = variant.requestHeaders,
            mimeType = variant.mimeType,
            extension = variant.extension,
            mode = s.mode.name
        )

        try {
            enqueuePending(pending)
            _state.value = s.copy(status = "Added to download queue", error = "")
        } catch (t: Throwable) {
            _state.value = s.copy(
                status = "Could not queue download",
                error = t.message ?: "Android could not add this download to the queue."
            )
        }
    }

    private fun enqueuePending(
        pending: PendingDownload,
        existingQueueId: String? = null,
        existingCreatedAt: Long? = null
    ): String {
        val queueId = existingQueueId ?: UUID.randomUUID().toString()
        val token = UUID.randomUUID().toString()
        val lane = chooseLane(queueId)
        val createdAt = existingCreatedAt ?: System.currentTimeMillis()
        DownloadCancellationRegistry.clear(queueId)
        requestStore.put(token, pending)

        try {
            val data = Data.Builder()
                .putString(DownloadWorker.KEY_REQUEST_TOKEN, token)
                .putString(DownloadWorker.KEY_QUEUE_ID, queueId)
                .putString(DownloadWorker.KEY_SPEED_MODE, settings.value.downloadSpeedMode.name)
                .putInt(DownloadWorker.KEY_LANE, lane)
                .build()
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (settings.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .build()
            val work = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .addTag(DOWNLOAD_TAG)
                .addTag("$QUEUE_TAG_PREFIX$queueId")
                .build()

            queueStore.put(
                StoredQueueDownload(
                    queueId = queueId,
                    workId = work.id.toString(),
                    token = token,
                    lane = lane,
                    request = pending,
                    state = "ENQUEUED",
                    createdAt = createdAt
                )
            )
            workManager.beginUniqueWork(
                laneName(lane),
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                work
            ).enqueue()

            // Optimistic UI update: make the Download button glow/progress state
            // appear immediately instead of waiting for the 550 ms WorkManager poll.
            val optimistic = DownloadQueueItem(
                queueId = queueId,
                workId = work.id.toString(),
                title = pending.title,
                sourceUrl = pending.sourceUrl,
                thumbnailUrl = pending.thumbnail,
                quality = pending.quality,
                mode = runCatching { MediaMode.valueOf(pending.mode) }.getOrDefault(MediaMode.VIDEO),
                status = DownloadQueueStatus.QUEUED,
                statusText = "Queued",
                progress = 0,
                lane = lane,
                createdAt = createdAt
            )
            _downloadQueue.value = (listOf(optimistic) + _downloadQueue.value.filterNot { it.queueId == queueId })
                .sortedByDescending { it.createdAt }
            return queueId
        } catch (t: Throwable) {
            requestStore.remove(token)
            if (existingQueueId == null) queueStore.remove(queueId)
            throw t
        }
    }

    fun pauseDownload(queueId: String) {
        val stored = queueStore.get(queueId) ?: return
        // Do not cancel the WorkManager node itself: jobs in the same lane are chained.
        // The worker sees PAUSED and exits successfully, allowing later queued jobs to continue.
        queueStore.updateState(queueId, "PAUSED")
        refreshQueueSnapshot()
    }

    fun resumeDownload(queueId: String) {
        val stored = queueStore.get(queueId) ?: return
        if (stored.state != "PAUSED" && stored.state != "FAILED" && stored.state != "CANCELLED") return
        runCatching {
            enqueuePending(stored.request, stored.queueId, stored.createdAt)
        }.onFailure { t ->
            queueStore.updateState(queueId, "FAILED")
            _state.value = _state.value.copy(error = t.message ?: "Could not resume this download.")
        }
        refreshQueueSnapshot()
    }

    fun cancelDownload(queueId: String) {
        val stored = queueStore.get(queueId) ?: return
        // Mark-and-skip instead of cancelling the WorkManager node so descendants in
        // this lane are not cancelled by WorkManager dependency propagation.
        queueStore.updateState(queueId, "CANCELLED")
        DownloadCancellationRegistry.cancel(queueId)
        refreshQueueSnapshot()
    }

    fun removeQueueItem(queueId: String) {
        val stored = queueStore.get(queueId) ?: return
        if (stored.state == "RUNNING" || stored.state == "ENQUEUED") return
        queueStore.remove(queueId)
        refreshQueueSnapshot()
    }

    fun clearFinishedDownloads() {
        queueStore.all()
            .filter { it.state in setOf("SUCCEEDED", "FAILED", "CANCELLED") }
            .forEach { queueStore.remove(it.queueId) }
        refreshQueueSnapshot()
    }

    private fun chooseLane(queueId: String): Int {
        val counts = IntArray(MAX_CONCURRENT_DOWNLOADS)
        queueStore.all().forEach { item ->
            if (item.state == "ENQUEUED" || item.state == "RUNNING") {
                counts[item.lane.coerceIn(0, MAX_CONCURRENT_DOWNLOADS - 1)]++
            }
        }
        // Deterministic lowest-lane assignment means the first active job gets lane 0 instead of
        // occasionally landing on lane 2/3 and waiting behind a playback throttle.
        return counts.indices.minByOrNull { counts[it] } ?: 0
    }

    private fun laneName(lane: Int) = "haruki_download_lane_${lane.coerceIn(0, MAX_CONCURRENT_DOWNLOADS - 1)}"

    private fun startQueueMonitor() {
        queueMonitorJob?.cancel()
        queueMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                syncQueueFromWorkManager()
                delay(550)
            }
        }
    }

    private suspend fun syncQueueFromWorkManager() {
        val storedItems = queueStore.all()
        val workInfos: List<WorkInfo> = runCatching { workManager.getWorkInfosByTag(DOWNLOAD_TAG).get() }
            .getOrElse { emptyList() }
        val infoById: Map<String, WorkInfo> = workInfos.associateBy { info -> info.id.toString() }
        var libraryNeedsRefresh = false

        val uiItems = storedItems.map { stored ->
            val info = stored.workId.takeIf { it.isNotBlank() }?.let { workId -> infoById[workId] }
            val manualState = stored.state
            val status = when {
                manualState == "PAUSED" -> DownloadQueueStatus.PAUSED
                manualState == "CANCELLED" -> DownloadQueueStatus.CANCELLED
                info == null && manualState == "SUCCEEDED" -> DownloadQueueStatus.SUCCEEDED
                info == null && manualState == "FAILED" -> DownloadQueueStatus.FAILED
                info == null -> DownloadQueueStatus.QUEUED
                info.state == WorkInfo.State.RUNNING -> DownloadQueueStatus.RUNNING
                info.state == WorkInfo.State.ENQUEUED || info.state == WorkInfo.State.BLOCKED -> DownloadQueueStatus.QUEUED
                info.state == WorkInfo.State.SUCCEEDED -> DownloadQueueStatus.SUCCEEDED
                info.state == WorkInfo.State.FAILED -> DownloadQueueStatus.FAILED
                info.state == WorkInfo.State.CANCELLED -> DownloadQueueStatus.CANCELLED
                else -> DownloadQueueStatus.QUEUED
            }
            val persistedState = status.name
            if (manualState != "PAUSED" && manualState != persistedState) {
                queueStore.updateState(stored.queueId, persistedState)
            }

            if (info?.state?.isFinished == true && finishHandled.add(info.id.toString())) {
                val skipped = info.outputData.getBoolean(DownloadWorker.KEY_QUEUE_SKIPPED, false)
                if (info.state == WorkInfo.State.SUCCEEDED && !skipped && manualState != "CANCELLED" && manualState != "PAUSED") {
                    libraryNeedsRefresh = true
                    if (settings.value.playAfterDownload) {
                        val otherActive = storedItems.any { other ->
                            other.queueId != stored.queueId && other.state in setOf("ENQUEUED", "RUNNING")
                        }
                        if (!otherActive) {
                            val libraryId = info.outputData.getLong(DownloadWorker.KEY_LIBRARY_ID, -1L)
                            libraryStore.byId(libraryId)?.let { item -> withContext(Dispatchers.Main) { play(item) } }
                        }
                    }
                }
            }

            val progress = info?.progress?.getInt(DownloadWorker.KEY_PROGRESS, 0) ?: if (status == DownloadQueueStatus.SUCCEEDED) 100 else 0
            val statusText = when (status) {
                DownloadQueueStatus.PAUSED -> "Paused"
                DownloadQueueStatus.CANCELLED -> "Cancelled"
                DownloadQueueStatus.SUCCEEDED -> "Download complete"
                DownloadQueueStatus.FAILED -> info?.outputData?.getString(DownloadWorker.KEY_ERROR).orEmpty().ifBlank { "Download failed" }
                DownloadQueueStatus.QUEUED -> "Queued"
                DownloadQueueStatus.RUNNING -> info?.progress?.getString(DownloadWorker.KEY_STATUS).orEmpty().ifBlank { "Downloading…" }
            }
            DownloadQueueItem(
                queueId = stored.queueId,
                workId = stored.workId,
                title = stored.request.title,
                sourceUrl = stored.request.sourceUrl,
                thumbnailUrl = stored.request.thumbnail,
                quality = stored.request.quality,
                mode = runCatching { MediaMode.valueOf(stored.request.mode) }.getOrDefault(MediaMode.VIDEO),
                status = status,
                statusText = statusText,
                progress = progress,
                bytesDownloaded = info?.progress?.getLong(DownloadWorker.KEY_BYTES_DOWNLOADED, 0L) ?: 0L,
                totalBytes = info?.progress?.getLong(DownloadWorker.KEY_TOTAL_BYTES, 0L) ?: 0L,
                speedBps = info?.progress?.getLong(DownloadWorker.KEY_SPEED_BPS, 0L) ?: 0L,
                error = if (status == DownloadQueueStatus.FAILED) info?.outputData?.getString(DownloadWorker.KEY_ERROR).orEmpty() else "",
                lane = stored.lane,
                createdAt = stored.createdAt
            )
        }
        if (libraryNeedsRefresh) withContext(Dispatchers.Main) { refreshLibrary() }
        withContext(Dispatchers.Main) { _downloadQueue.value = uiItems }
    }

    private fun refreshQueueSnapshot() {
        viewModelScope.launch(Dispatchers.IO) { syncQueueFromWorkManager() }
    }

    fun refreshLibrary() {
        viewModelScope.launch {
            _library.value = withContext(Dispatchers.IO) { libraryStore.all() }
        }
    }

    fun removeLibraryEntry(item: LibraryItem, deleteFile: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (deleteFile) {
                val uri = Uri.parse(item.uri)
                runCatching {
                    if (uri.scheme == "file") {
                        uri.path?.let { File(it).delete() }
                    } else {
                        getApplication<Application>().contentResolver.delete(uri, null, null)
                    }
                }
            }
            libraryStore.remove(item.id)
            withContext(Dispatchers.Main) { refreshLibrary() }
        }
    }

    fun play(item: LibraryItem) { _playing.value = item }

    fun setDefaultMode(value: MediaMode) = viewModelScope.launch { settingsRepo.setDefaultMode(value) }
    fun setDefaultQuality(value: String) = viewModelScope.launch { settingsRepo.setDefaultQuality(value) }
    fun setAutoAnalyze(value: Boolean) = viewModelScope.launch { settingsRepo.setAutoAnalyze(value) }
    fun setPlayAfter(value: Boolean) = viewModelScope.launch { settingsRepo.setPlayAfter(value) }
    fun setWifiOnly(value: Boolean) = viewModelScope.launch { settingsRepo.setWifiOnly(value) }
    fun setAutoplayNext(value: Boolean) = viewModelScope.launch { settingsRepo.setAutoplayNext(value) }
    fun setPlaybackQuality(value: String) = viewModelScope.launch { settingsRepo.setPlaybackQuality(value) }
    fun setDownloadSpeedMode(value: DownloadSpeedMode) = viewModelScope.launch { settingsRepo.setDownloadSpeedMode(value) }
    fun setEqualizerEnabled(value: Boolean) = viewModelScope.launch { settingsRepo.setEqualizerEnabled(value) }
    fun setEqualizerPreset(value: EqualizerPreset) = viewModelScope.launch { settingsRepo.setEqualizerPreset(value) }
    fun setEqualizerCustomBands(value: List<Float>) = viewModelScope.launch { settingsRepo.setEqualizerCustomBands(value) }
    fun resetSettings() = viewModelScope.launch { settingsRepo.reset() }

    private fun chooseVariant(variants: List<MediaVariant>, quality: String): MediaVariant? {
        if (variants.isEmpty()) return null
        if (quality == "Best") return variants.first()
        val height = Regex("(\\d{3,4})").find(quality)?.groupValues?.get(1)?.toIntOrNull()
        if (height != null) {
            return variants.minByOrNull { kotlin.math.abs(it.qualityHeight - height) }
        }
        return variants.first()
    }
}

data class HarukiUiState(
    val url: String = "",
    val media: AnalyzedMedia? = null,
    val mode: MediaMode = MediaMode.VIDEO,
    val selectedVariantId: String = "",
    val isAnalyzing: Boolean = false,
    val status: String = "Ready",
    val error: String = ""
)

private const val DOWNLOAD_TAG = "haruki_download"
private const val QUEUE_TAG_PREFIX = "haruki_queue_"
private const val MAX_CONCURRENT_DOWNLOADS = 4
