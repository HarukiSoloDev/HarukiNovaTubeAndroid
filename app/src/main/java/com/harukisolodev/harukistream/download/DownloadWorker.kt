package com.harukisolodev.harukistream.download

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.harukisolodev.harukistream.MainActivity
import com.harukisolodev.harukistream.R
import com.harukisolodev.harukistream.core.HarukiConstants
import com.harukisolodev.harukistream.data.LibraryStore
import com.harukisolodev.harukistream.data.DownloadSpeedMode
import com.harukisolodev.harukistream.player.PlaybackNetworkCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class DownloadWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    private var currentTitle = "Haruki download"
    private var currentQueueId = ""
    private var currentToken = ""
    private var lastNotificationUpdate = 0L
    @Volatile private var lastQueueCheck = 0L
    @Volatile private var cachedQueueAbort = false
    private var currentLane = 0
    private var speedMode = DownloadSpeedMode.AUTO
    private val queueStore by lazy { DownloadQueueStore(applicationContext) }

    private val client: OkHttpClient get() = sharedClient

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val token = inputData.getString(KEY_REQUEST_TOKEN).orEmpty()
        if (token.isBlank()) return@withContext failure("Download request is missing.")
        currentToken = token
        currentQueueId = inputData.getString(KEY_QUEUE_ID).orEmpty()
        currentLane = inputData.getInt(KEY_LANE, 0).coerceAtLeast(0)
        speedMode = runCatching { DownloadSpeedMode.valueOf(inputData.getString(KEY_SPEED_MODE).orEmpty()) }
            .getOrDefault(DownloadSpeedMode.AUTO)

        val store = DownloadRequestStore(applicationContext)
        if (shouldSkipQueuedWork(force = true)) {
            store.remove(token)
            return@withContext Result.success(workDataOf(KEY_QUEUE_SKIPPED to true))
        }
        DownloadCancellationRegistry.clear(currentQueueId)
        val request = store.get(token) ?: return@withContext failure("Download request expired. Please tap Download again.")
        currentTitle = request.title.ifBlank { "Haruki download" }
        var output: OutputTarget? = null
        var success = false
        PlaybackNetworkCoordinator.registerDownloadLane(currentLane)

        try {
            if (request.mediaUrl.isBlank()) return@withContext failure("No media stream was selected.")

            // Android 14+ validates this foreground service type against the merged manifest.
            setForeground(createForegroundInfo(0, "Preparing download…", 0L, 0L, 0L))

            output = createOutput(request.title, request.extension, request.mimeType, request.mode)
                ?: return@withContext failure("Could not create the output file.")

            if (request.transcodeToMp3) {
                val sourceExt = request.sourceExtension.ifBlank { "audio" }
                val tempAudio = File(applicationContext.cacheDir, "${id}-mp3-source.$sourceExt")
                try {
                    val fast = downloadChunkedRanges(
                        url = request.mediaUrl, target = tempAudio, start = 0, end = 78,
                        status = "Downloading audio for MP3…", headers = request.requestHeaders,
                        connectionCap = connectionCap(video = false)
                    )
                    if (!fast) {
                        FileOutputStream(tempAudio).use { out ->
                            download(request.mediaUrl, out, 0, 78, "Downloading audio for MP3…",
                                fallbackUrls = request.mediaFallbackUrls, headers = request.requestHeaders)
                        }
                    }
                    if (shouldSkipQueuedWork(force = true)) throw QueueAbortException()
                    updateProgress(82, "Converting to MP3…")
                    output.open().use { out -> Mp3Transcoder.transcode(tempAudio, out, request.title) }
                    updateProgress(99, "Finishing MP3…")
                } finally {
                    tempAudio.delete()
                }
            } else if (request.audioUrl.isBlank()) {
                val tempMedia = File(applicationContext.cacheDir, "${id}-media.${request.extension}")
                try {
                    val fast = downloadChunkedRanges(
                        url = request.mediaUrl,
                        target = tempMedia,
                        start = 0,
                        end = 100,
                        status = "Smart chunk download…",
                        headers = request.requestHeaders,
                        connectionCap = connectionCap(video = true)
                    )
                    if (fast) {
                        output.open().use { out -> FileInputStream(tempMedia).use { input -> input.copyTo(out, 512 * 1024) } }
                    } else {
                        output.open().use { stream ->
                            download(
                                request.mediaUrl, stream, 0, 100, "Downloading…",
                                fallbackUrls = request.mediaFallbackUrls,
                                headers = request.requestHeaders
                            )
                        }
                    }
                } finally {
                    tempMedia.delete()
                }
            } else {
                val tempVideo = File(applicationContext.cacheDir, "${id}-video.mp4")
                val tempAudio = File(applicationContext.cacheDir, "${id}-audio.m4a")
                try {
                    // Video and separate audio may use parallel streams while the player is idle.
                    // During playback, keep one media stream at a time so even Turbo cannot starve
                    // ExoPlayer; bounded chunks still keep the download moving.
                    val parallel = !PlaybackNetworkCoordinator.isPlaybackActive()
                    if (parallel) {
                        downloadVideoAndAudioInParallel(request, tempVideo, tempAudio)
                    } else {
                        val fastVideo = downloadChunkedRanges(
                            url = request.mediaUrl, target = tempVideo, start = 0, end = 72,
                            status = "Downloading video…", headers = request.requestHeaders,
                            connectionCap = connectionCap(video = true)
                        )
                        if (!fastVideo) {
                            FileOutputStream(tempVideo).use {
                                download(
                                    request.mediaUrl, it, 0, 72, "Downloading video…",
                                    fallbackUrls = request.mediaFallbackUrls,
                                    headers = request.requestHeaders
                                )
                            }
                        }
                        val fastAudio = downloadChunkedRanges(
                            url = request.audioUrl, target = tempAudio, start = 72, end = 92,
                            status = "Downloading audio…", headers = request.requestHeaders,
                            connectionCap = connectionCap(video = false)
                        )
                        if (!fastAudio) {
                            FileOutputStream(tempAudio).use {
                                download(request.audioUrl, it, 72, 92, "Downloading audio…", headers = request.requestHeaders)
                            }
                        }
                    }
                    if (shouldSkipQueuedWork(force = true)) throw QueueAbortException()
                    updateProgress(94, "Combining video + audio…")
                    output.mux(tempVideo, tempAudio)
                    if (shouldSkipQueuedWork(force = true)) throw QueueAbortException()
                    updateProgress(99, "Finishing…")
                } finally {
                    tempVideo.delete()
                    tempAudio.delete()
                }
            }

            if (shouldSkipQueuedWork(force = true)) throw QueueAbortException()
            output.finish(true)
            val libraryId = LibraryStore(applicationContext).insert(
                mediaId = request.mediaId,
                title = request.title,
                uri = output.uri.toString(),
                sourceUrl = request.sourceUrl,
                thumbnailUrl = request.thumbnail,
                quality = request.quality,
                mimeType = request.mimeType
            )
            success = true
            updateProgress(100, "Download complete")
            postFinishedNotification(true, "Download complete")
            Result.success(workDataOf(KEY_LIBRARY_ID to libraryId, KEY_OUTPUT_URI to output.uri.toString()))
        } catch (_: QueueAbortException) {
            // Pause/cancel/obsolete queued generations are intentional. Returning success is
            // important because WorkManager lane descendants must remain runnable.
            runCatching { output?.finish(false) }
            Result.success(workDataOf(KEY_QUEUE_SKIPPED to true))
        } catch (t: Throwable) {
            // A Worker failure should be reported to the UI, never take the app process down.
            runCatching { output?.finish(false) }
            val message = friendlyError(t)
            if (!isStopped) postFinishedNotification(false, message)
            failure(message)
        } finally {
            if (!success) runCatching { output?.cleanup() }
            PlaybackNetworkCoordinator.unregisterDownloadLane(currentLane)
            store.remove(token)
        }
    }

    private fun shouldSkipQueuedWork(force: Boolean = false): Boolean {
        if (currentQueueId.isBlank()) return false
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastQueueCheck < 350L) return cachedQueueAbort
        lastQueueCheck = now
        val queued = queueStore.get(currentQueueId)
        cachedQueueAbort = DownloadCancellationRegistry.isCancelled(currentQueueId) ||
            queued == null ||
            queued.token != currentToken ||
            queued.state == "PAUSED" ||
            queued.state == "CANCELLED"
        return cachedQueueAbort
    }

    private class QueueAbortException : RuntimeException()

    private fun failure(message: String): Result = Result.failure(workDataOf(KEY_ERROR to message))

    private fun friendlyError(t: Throwable): String {
        val raw = t.message.orEmpty()
        return when {
            isStopped -> "Download cancelled"
            raw.contains("foreground", ignoreCase = true) -> "Android could not start the download service. Reopen Haruki and try again."
            raw.contains("HTTP 403", ignoreCase = true) -> "The website blocked this stream. Refresh the link and try another quality."
            raw.contains("HTTP 429", ignoreCase = true) -> "The website is rate-limiting requests. Wait a moment and retry."
            raw.isNotBlank() -> raw
            else -> "Download failed. Refresh the link and try again."
        }
    }

    private suspend fun download(
        url: String,
        output: OutputStream,
        start: Int,
        end: Int,
        status: String,
        fallbackUrls: List<String> = emptyList(),
        headers: Map<String, String> = emptyMap(),
        tracker: TransferTracker? = null,
        reportUi: Boolean = true,
        laneOverride: Int = currentLane
    ) {
        val candidates = (listOf(url) + fallbackUrls).filter { it.startsWith("http") }.distinct()
        if (candidates.isEmpty()) error("No valid download URL was provided.")

        var lastCode = 0
        var lastMessage = ""
        for ((candidateIndex, candidate) in candidates.withIndex()) {
            while (!PlaybackNetworkCoordinator.downloadLaneAllowed(laneOverride, speedMode)) {
                if (isStopped || shouldSkipQueuedWork()) throw QueueAbortException()
                delay(80L)
            }
            val builder = Request.Builder()
                .url(candidate)
                .header("User-Agent", HarukiConstants.USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank()) builder.header(name, value)
            }

            val call = client.newCall(builder.build())
            DownloadCancellationRegistry.register(currentQueueId, call)
            val response = try {
                call.execute()
            } catch (t: Throwable) {
                if (DownloadCancellationRegistry.isCancelled(currentQueueId) || shouldSkipQueuedWork(force = true)) {
                    throw QueueAbortException()
                }
                throw t
            }
            if (!response.isSuccessful) {
                DownloadCancellationRegistry.unregister(currentQueueId, call)
                lastCode = response.code
                lastMessage = response.message
                response.close()
                // Some media endpoints provide multiple signed mirrors; try them in order.
                // Try the next mirror before surfacing the failure to the user.
                continue
            }

            try {
                response.use { goodResponse ->
                val body = goodResponse.body
                val total = body.contentLength()
                val buffer = ByteArray(256 * 1024)
                var readTotal = 0L
                var lastProgress = -1
                var lastUiUpdate = 0L
                val startedAt = SystemClock.elapsedRealtime()
                val baseStatus = if (candidateIndex > 0) "$status (alternate server)" else status
                val activeStatus = if (speedMode == DownloadSpeedMode.TURBO && baseStatus.startsWith("Downloading")) "Turbo • $baseStatus" else baseStatus

                tracker?.total?.set(total.coerceAtLeast(0L))
                if (reportUi) updateProgress(start, activeStatus, 0L, total.coerceAtLeast(0L), 0L)
                var bytesSinceYield = 0L

                body.byteStream().use { input ->
                    while (true) {
                        if (isStopped) error("Download cancelled")
                        if (shouldSkipQueuedWork()) throw QueueAbortException()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        readTotal += read

                        // Fallback path only. Yield after a multi-megabyte quantum so playback
                        // can reclaim bandwidth without putting an artificial cap on every read.
                        while (!PlaybackNetworkCoordinator.downloadLaneAllowed(laneOverride, speedMode)) {
                            if (isStopped) error("Download cancelled")
                            if (shouldSkipQueuedWork()) throw QueueAbortException()
                            delay(180L)
                        }
                        bytesSinceYield += read
                        if (bytesSinceYield >= YIELD_QUANTUM_BYTES) {
                            val playbackYield = PlaybackNetworkCoordinator.downloadYieldDelayMs(speedMode)
                            if (playbackYield > 0L) delay(playbackYield)
                            bytesSinceYield = 0L
                        }

                        val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                        val speed = (readTotal * 1000L) / elapsedMs
                        tracker?.downloaded?.set(readTotal)
                        tracker?.speed?.set(speed)
                        val local = if (total > 0) {
                            ((readTotal * 100L) / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        val progress = if (total > 0) {
                            start + ((end - start) * local / 100)
                        } else {
                            start
                        }

                        val now = SystemClock.elapsedRealtime()
                        val percentChanged = progress != lastProgress
                        val heartbeatDue = now - lastUiUpdate >= 650L
                        if (reportUi && (percentChanged || heartbeatDue)) {
                            lastProgress = progress
                            lastUiUpdate = now
                            updateProgress(progress, activeStatus, readTotal, total.coerceAtLeast(0L), speed)
                        }
                    }
                    output.flush()
                }

                tracker?.downloaded?.set(readTotal)
                tracker?.speed?.set(0L)
                if (reportUi) updateProgress(end, activeStatus, readTotal, total.coerceAtLeast(0L), 0L)
                return
                }
            } catch (t: Throwable) {
                if (DownloadCancellationRegistry.isCancelled(currentQueueId) || shouldSkipQueuedWork(force = true)) {
                    throw QueueAbortException()
                }
                throw t
            } finally {
                DownloadCancellationRegistry.unregister(currentQueueId, call)
            }
        }

        if (lastCode > 0) error("Server returned HTTP $lastCode${if (lastMessage.isNotBlank()) " ($lastMessage)" else ""}")
        error("All available download servers failed.")
    }

    private data class RangeProbe(val totalBytes: Long)

    private fun connectionCap(video: Boolean): Int = when (speedMode) {
        DownloadSpeedMode.TURBO -> if (video) 4 else 3
        DownloadSpeedMode.AUTO -> if (video) 3 else 2
        DownloadSpeedMode.PLAYBACK_PRIORITY -> 1
    }

    /**
     * Probe with a one-byte request. YouTube/googlevideo streams support byte ranges and can
     * throttle a single large/open-ended request. v0.8.1 therefore downloads bounded 8 MiB
     * chunks when the endpoint exposes a valid Content-Range total.
     */
    private fun probeRangeSupport(url: String, headers: Map<String, String>): RangeProbe? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", HarukiConstants.USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-0")
        headers.forEach { (name, value) ->
            if (name.isNotBlank() && value.isNotBlank() && !name.equals("Range", true)) builder.header(name, value)
        }
        val call = client.newCall(builder.build())
        DownloadCancellationRegistry.register(currentQueueId, call)
        return try {
            call.execute().use { response ->
                if (response.code != 206) return null
                val contentRange = response.header("Content-Range").orEmpty()
                val total = Regex("/(\\d+)$").find(contentRange)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return null
                if (total < MIN_CHUNKED_RANGE_BYTES) null else RangeProbe(total)
            }
        } catch (t: Throwable) {
            if (DownloadCancellationRegistry.isCancelled(currentQueueId) || shouldSkipQueuedWork(force = true)) {
                throw QueueAbortException()
            }
            null
        } finally {
            DownloadCancellationRegistry.unregister(currentQueueId, call)
        }
    }

    private suspend fun awaitDownloadTurn() {
        while (!PlaybackNetworkCoordinator.downloadLaneAllowed(currentLane, speedMode)) {
            if (isStopped || shouldSkipQueuedWork()) throw QueueAbortException()
            delay(80L)
        }
    }

    /**
     * Download to one random-access file using bounded range requests. Connection count adapts at
     * every chunk boundary, so starting playback immediately reduces network pressure without
     * stopping the download worker or discarding already transferred bytes.
     */
    private suspend fun downloadChunkedRanges(
        url: String,
        target: File,
        start: Int,
        end: Int,
        status: String,
        headers: Map<String, String>,
        tracker: TransferTracker? = null,
        reportUi: Boolean = true,
        connectionCap: Int
    ): Boolean {
        val probe = probeRangeSupport(url, headers) ?: return false
        val total = probe.totalBytes
        val chunkCount = ((total + HTTP_CHUNK_BYTES - 1L) / HTTP_CHUNK_BYTES).toInt().coerceAtLeast(1)
        // Create up to the mode cap once. Extra workers wait at chunk boundaries while playback
        // is active, and can immediately scale back up when playback no longer needs bandwidth.
        val initialConnections = minOf(chunkCount, connectionCap.coerceAtLeast(1)).coerceAtLeast(1)
        val nextChunk = AtomicInteger(0)
        val downloaded = AtomicLong(0L)
        val startedAt = SystemClock.elapsedRealtime()
        target.delete()
        RandomAccessFile(target, "rw").use { it.setLength(total) }
        tracker?.total?.set(total)
        if (reportUi) updateProgress(start, status, 0L, total, 0L)

        return try {
            coroutineScope {
                val jobs = (0 until initialConnections).map { workerIndex ->
                    async(Dispatchers.IO) {
                        RandomAccessFile(target, "rw").use { raf ->
                            while (true) {
                                if (isStopped || shouldSkipQueuedWork()) throw QueueAbortException()
                                awaitDownloadTurn()
                                // If all chunks have already been claimed, throttled helper workers
                                // must exit instead of waiting forever for playback to release them.
                                if (nextChunk.get() >= chunkCount) break
                                val allowedConnections = minOf(
                                    connectionCap.coerceAtLeast(1),
                                    PlaybackNetworkCoordinator.maxConnectionsPerDownload(speedMode)
                                ).coerceAtLeast(1)
                                // Workers above the current adaptive connection budget wait for the
                                // next boundary instead of keeping extra CDN connections alive.
                                if (workerIndex >= allowedConnections) {
                                    delay(60L)
                                    continue
                                }
                                val index = nextChunk.getAndIncrement()
                                if (index >= chunkCount) break
                                val first = index.toLong() * HTTP_CHUNK_BYTES
                                val last = minOf(total - 1L, first + HTTP_CHUNK_BYTES - 1L)
                                downloadRangeChunk(url, headers, first, last, raf, downloaded)
                                val pause = PlaybackNetworkCoordinator.downloadYieldDelayMs(speedMode)
                                if (pause > 0L) delay(pause)
                            }
                        }
                    }
                }

                while (jobs.any { !it.isCompleted }) {
                    if (isStopped || shouldSkipQueuedWork()) throw QueueAbortException()
                    val done = downloaded.get().coerceAtMost(total)
                    val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                    val speed = (done * 1000L) / elapsedMs
                    tracker?.downloaded?.set(done)
                    tracker?.speed?.set(speed)
                    if (reportUi) {
                        val local = ((done * 100L) / total).toInt().coerceIn(0, 100)
                        val progress = start + ((end - start) * local / 100)
                        updateProgress(progress, status, done, total, speed)
                    }
                    delay(300L)
                }
                jobs.awaitAll()
            }

            val finalSize = target.length()
            val done = downloaded.get()
            if (finalSize != total || done != total) error("Chunked download size mismatch ($done / $total).")
            tracker?.downloaded?.set(total)
            tracker?.speed?.set(0L)
            if (reportUi) updateProgress(end, status, total, total, 0L)
            true
        } catch (abort: QueueAbortException) {
            throw abort
        } catch (t: Throwable) {
            if (DownloadCancellationRegistry.isCancelled(currentQueueId) || shouldSkipQueuedWork(force = true)) {
                throw QueueAbortException()
            }
            target.delete()
            false
        }
    }

    private suspend fun downloadRangeChunk(
        url: String,
        headers: Map<String, String>,
        first: Long,
        last: Long,
        output: RandomAccessFile,
        downloaded: AtomicLong
    ) {
        var lastError: Throwable? = null
        repeat(CHUNK_RETRY_COUNT) { attempt ->
            if (attempt > 0) delay(120L * attempt)
            awaitDownloadTurn()
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", HarukiConstants.USER_AGENT)
                .header("Accept", "*/*")
                .header("Accept-Encoding", "identity")
                .header("Range", "bytes=$first-$last")
            headers.forEach { (name, value) ->
                if (name.isNotBlank() && value.isNotBlank() && !name.equals("Range", true)) builder.header(name, value)
            }
            val call = client.newCall(builder.build())
            DownloadCancellationRegistry.register(currentQueueId, call)
            try {
                call.execute().use { response ->
                    if (response.code != 206) error("Range server returned HTTP ${response.code}")
                    val expected = last - first + 1L
                    var written = 0L
                    val buffer = ByteArray(512 * 1024)
                    output.seek(first)
                    response.body.byteStream().use { input ->
                        while (true) {
                            if (isStopped || shouldSkipQueuedWork()) throw QueueAbortException()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                        }
                    }
                    if (written != expected) error("Incomplete range $first-$last ($written / $expected)")
                    downloaded.addAndGet(written)
                    return
                }
            } catch (abort: QueueAbortException) {
                throw abort
            } catch (t: Throwable) {
                lastError = t
                if (DownloadCancellationRegistry.isCancelled(currentQueueId) || shouldSkipQueuedWork(force = true)) {
                    throw QueueAbortException()
                }
            } finally {
                DownloadCancellationRegistry.unregister(currentQueueId, call)
            }
        }
        throw lastError ?: IllegalStateException("Chunk $first-$last failed")
    }

    private class TransferTracker {
        val downloaded = AtomicLong(0L)
        val total = AtomicLong(0L)
        val speed = AtomicLong(0L)
    }

    private suspend fun downloadVideoAndAudioInParallel(
        request: PendingDownload,
        videoFile: File,
        audioFile: File
    ) = coroutineScope {
        val video = TransferTracker()
        val audio = TransferTracker()
        val videoJob = async(Dispatchers.IO) {
            val ranged = downloadChunkedRanges(
                url = request.mediaUrl,
                target = videoFile,
                start = 0,
                end = 92,
                status = "Downloading video + audio…",
                headers = request.requestHeaders,
                tracker = video,
                reportUi = false,
                connectionCap = connectionCap(video = true)
            )
            if (!ranged) {
                FileOutputStream(videoFile).use { out ->
                    download(
                        request.mediaUrl, out, 0, 92, "Downloading video + audio…",
                        fallbackUrls = request.mediaFallbackUrls,
                        headers = request.requestHeaders,
                        tracker = video,
                        reportUi = false
                    )
                }
            }
        }
        val audioJob = async(Dispatchers.IO) {
            val ranged = downloadChunkedRanges(
                url = request.audioUrl,
                target = audioFile,
                start = 0,
                end = 92,
                status = "Downloading video + audio…",
                headers = request.requestHeaders,
                tracker = audio,
                reportUi = false,
                connectionCap = connectionCap(video = false)
            )
            if (!ranged) {
                FileOutputStream(audioFile).use { out ->
                    download(
                        request.audioUrl, out, 0, 92, "Downloading video + audio…",
                        headers = request.requestHeaders,
                        tracker = audio,
                        reportUi = false
                    )
                }
            }
        }

        while (!videoJob.isCompleted || !audioJob.isCompleted) {
            if (shouldSkipQueuedWork()) throw QueueAbortException()
            val downloaded = video.downloaded.get() + audio.downloaded.get()
            val totalVideo = video.total.get()
            val totalAudio = audio.total.get()
            val total = if (totalVideo > 0L && totalAudio > 0L) totalVideo + totalAudio else 0L
            val speed = video.speed.get() + audio.speed.get()
            val progress = if (total > 0L) ((downloaded * 92L) / total).toInt().coerceIn(0, 92) else 0
            val label = if (speedMode == DownloadSpeedMode.TURBO) "Turbo • video + audio…" else "Downloading video + audio…"
            updateProgress(progress, label, downloaded, total, speed)
            delay(500L)
        }
        videoJob.await()
        audioJob.await()
        val finalVideoTotal = video.total.get()
        val finalAudioTotal = audio.total.get()
        val total = if (finalVideoTotal > 0L && finalAudioTotal > 0L) finalVideoTotal + finalAudioTotal else 0L
        val downloaded = video.downloaded.get() + audio.downloaded.get()
        updateProgress(92, "Video + audio downloaded", downloaded, total, 0L)
    }

    private suspend fun updateProgress(
        progress: Int,
        status: String,
        bytesDownloaded: Long = 0L,
        totalBytes: Long = 0L,
        speedBytesPerSecond: Long = 0L
    ) {
        setProgress(
            workDataOf(
                KEY_PROGRESS to progress,
                KEY_STATUS to status,
                KEY_BYTES_DOWNLOADED to bytesDownloaded,
                KEY_TOTAL_BYTES to totalBytes,
                KEY_SPEED_BPS to speedBytesPerSecond
            )
        )

        // Refresh the foreground notification frequently enough to feel live, but not on every buffer read.
        val now = SystemClock.elapsedRealtime()
        val importantStage = progress <= 0 || progress >= 100 || status.contains("Combining", true) || status.contains("Finishing", true)
        if (importantStage || now - lastNotificationUpdate >= 900L) {
            lastNotificationUpdate = now
            setForeground(
                createForegroundInfo(
                    progress = progress,
                    status = status,
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    speedBytesPerSecond = speedBytesPerSecond
                )
            )
        }
    }

    private fun createForegroundInfo(
        progress: Int,
        status: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long
    ): ForegroundInfo {
        val safeProgress = progress.coerceIn(0, 100)
        val indeterminate = totalBytes <= 0L && safeProgress < 100 && status.contains("Downloading", ignoreCase = true)
        val detailParts = buildList {
            if (safeProgress in 1..99) add("$safeProgress%")
            if (bytesDownloaded > 0L) {
                add(
                    if (totalBytes > 0L) {
                        "${formatBytes(bytesDownloaded)} / ${formatBytes(totalBytes)}"
                    } else {
                        formatBytes(bytesDownloaded)
                    }
                )
            }
            if (speedBytesPerSecond > 0L) {
                add("${formatBytes(speedBytesPerSecond)}/s")
                if (totalBytes > bytesDownloaded && bytesDownloaded > 0L) {
                    val etaSeconds = (totalBytes - bytesDownloaded) / speedBytesPerSecond.coerceAtLeast(1L)
                    if (etaSeconds in 1..86_400) add("${formatEta(etaSeconds)} left")
                }
            }
        }
        val content = if (detailParts.isEmpty()) status else "$status • ${detailParts.joinToString(" • ")}"

        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = DownloadLaunch.ACTION_OPEN_DOWNLOADS
            putExtra(DownloadLaunch.EXTRA_QUEUE_ID, currentQueueId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext,
            notificationId(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = if (currentQueueId.isNotBlank()) {
            val intent = Intent(applicationContext, DownloadActionReceiver::class.java).apply {
                action = DownloadActionReceiver.ACTION_CANCEL_QUEUE_ITEM
                putExtra(DownloadActionReceiver.EXTRA_QUEUE_ID, currentQueueId)
            }
            PendingIntent.getBroadcast(
                applicationContext,
                notificationId(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else {
            WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        }

        val notification: Notification = NotificationCompat.Builder(applicationContext, HarukiConstants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(currentTitle)
            .setContentText(content)
            .setContentIntent(openPendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(safeProgress < 100)
            .setAutoCancel(false)
            .setProgress(100, safeProgress, indeterminate)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_notification_cancel, "Cancel", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId(), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId(), notification)
        }
    }

    private fun postFinishedNotification(success: Boolean, message: String) {
        if (!notificationsAllowed()) return
        val openIntent = Intent(applicationContext, MainActivity::class.java).apply {
            action = DownloadLaunch.ACTION_OPEN_DOWNLOADS
            putExtra(DownloadLaunch.EXTRA_QUEUE_ID, currentQueueId)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext,
            completionNotificationId(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, HarukiConstants.DOWNLOAD_CHANNEL_ID)
            .setSmallIcon(if (success) R.drawable.ic_notification_done else R.drawable.ic_notification_error)
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText(if (success) currentTitle else message)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (success) NotificationCompat.CATEGORY_STATUS else NotificationCompat.CATEGORY_ERROR)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(completionNotificationId(), notification)
    }

    private fun notificationsAllowed(): Boolean {
        if (!NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun notificationId(): Int = (id.hashCode() and 0x3FFFFFFF).coerceAtLeast(1)
    private fun completionNotificationId(): Int = notificationId() or 0x40000000

    private fun formatBytes(value: Long): String {
        if (value <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var amount = value.toDouble()
        var index = 0
        while (amount >= 1024.0 && index < units.lastIndex) {
            amount /= 1024.0
            index++
        }
        return if (index == 0) "${amount.toLong()} ${units[index]}" else String.format("%.1f %s", amount, units[index])
    }

    private fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val hours = safe / 3600L
        val minutes = (safe % 3600L) / 60L
        val secs = safe % 60L
        return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
    }

    private fun createOutput(title: String, extension: String, mime: String, mode: String): OutputTarget? {
        val safeTitle = sanitize(title).take(120).ifBlank { "Haruki download" }
        val fileName = "$safeTitle.$extension"
        val isAudio = mode == "AUDIO" || mime.startsWith("audio/")

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (isAudio) MediaStore.Audio.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (isAudio) HarukiConstants.AUDIO_RELATIVE_PATH else HarukiConstants.VIDEO_RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = applicationContext.contentResolver.insert(collection, values) ?: return null
            MediaStoreTarget(applicationContext, uri)
        } else {
            val base = applicationContext.getExternalFilesDir(if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_MOVIES)
                ?: applicationContext.filesDir
            val folder = File(base, "Haruki NovaTube").apply { mkdirs() }
            FileTarget(File(folder, fileName))
        }
    }

    private fun sanitize(value: String): String = value
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .replace(Regex("\\s+"), " ")
        .trim()

    private sealed interface OutputTarget {
        val uri: Uri
        fun open(): OutputStream
        fun mux(video: File, audio: File)
        fun finish(success: Boolean)
        fun cleanup()
    }

    private class MediaStoreTarget(private val context: Context, override val uri: Uri) : OutputTarget {
        override fun open(): OutputStream = context.contentResolver.openOutputStream(uri, "w")
            ?: error("Could not open the Android media file.")

        override fun mux(video: File, audio: File) {
            context.contentResolver.openFileDescriptor(uri, "w")?.use { pfd ->
                Mp4Muxer.mux(video, audio, pfd.fileDescriptor)
            } ?: error("Could not open the Android media file for muxing.")
        }

        override fun finish(success: Boolean) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && success) {
                val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                context.contentResolver.update(uri, values, null, null)
            }
        }

        override fun cleanup() { context.contentResolver.delete(uri, null, null) }
    }

    private class FileTarget(private val file: File) : OutputTarget {
        override val uri: Uri = Uri.fromFile(file)
        override fun open(): OutputStream = FileOutputStream(file)
        override fun mux(video: File, audio: File) {
            FileOutputStream(file).use { fos -> Mp4Muxer.mux(video, audio, fos.fd) }
        }
        override fun finish(success: Boolean) = Unit
        override fun cleanup() { file.delete() }
    }

    companion object {
        private const val MIN_CHUNKED_RANGE_BYTES = 2L * 1024L * 1024L
        private const val HTTP_CHUNK_BYTES = 8L * 1024L * 1024L
        private const val CHUNK_RETRY_COUNT = 2
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .dispatcher(Dispatcher().apply {
                    maxRequests = 24
                    maxRequestsPerHost = 12
                })
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .retryOnConnectionFailure(true)
                .build()
        }
        const val KEY_REQUEST_TOKEN = "request_token"
        const val KEY_QUEUE_ID = "queue_id"
        const val KEY_SPEED_MODE = "speed_mode"
        const val KEY_LANE = "download_lane"
        const val KEY_QUEUE_SKIPPED = "queue_skipped"
        const val KEY_PROGRESS = "progress"
        const val KEY_STATUS = "status"
        const val KEY_BYTES_DOWNLOADED = "bytes_downloaded"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_BPS = "speed_bps"
        const val KEY_ERROR = "error"
        const val KEY_LIBRARY_ID = "library_id"
        const val KEY_OUTPUT_URI = "output_uri"
        private const val YIELD_QUANTUM_BYTES = 4L * 1024L * 1024L
    }
}
