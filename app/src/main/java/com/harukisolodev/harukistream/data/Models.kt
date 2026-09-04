package com.harukisolodev.harukistream.data

enum class MediaMode { VIDEO, AUDIO }

enum class DownloadSpeedMode { AUTO, TURBO, PLAYBACK_PRIORITY }

data class MediaVariant(
    val id: String,
    val label: String,
    val qualityHeight: Int,
    val videoUrl: String,
    val audioUrl: String = "",
    val mimeType: String,
    val extension: String,
    val codecNote: String = "",
    val bitrate: Int = 0,
    val fps: Int = 0,
    val separateAudio: Boolean = false,
    val fallbackUrls: List<String> = emptyList(),
    val requestHeaders: Map<String, String> = emptyMap()
)

data class AudioTrackOption(
    val id: String,
    val label: String,
    val languageCode: String = "",
    val url: String,
    val mimeType: String = "audio/mp4",
    val original: Boolean = false,
    val dubbed: Boolean = false,
    val descriptive: Boolean = false
)

data class AnalyzedMedia(
    val mediaId: String,
    val sourceUrl: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val serviceName: String,
    val videoVariants: List<MediaVariant>,
    val audioVariants: List<MediaVariant>,
    val audioTracks: List<AudioTrackOption> = emptyList()
)

data class LibraryItem(
    val id: Long,
    val mediaId: String,
    val title: String,
    val uri: String,
    val sourceUrl: String,
    val thumbnailUrl: String,
    val quality: String,
    val mimeType: String,
    val downloadedAt: Long
)

data class AppSettings(
    val defaultMode: MediaMode = MediaMode.VIDEO,
    val defaultQuality: String = "Best",
    val autoAnalyze: Boolean = true,
    val playAfterDownload: Boolean = false,
    val wifiOnly: Boolean = false,
    val autoplayNext: Boolean = true,
    val playbackQuality: String = "Auto",
    val downloadSpeedMode: DownloadSpeedMode = DownloadSpeedMode.AUTO
)


enum class DownloadQueueStatus { QUEUED, RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED }

data class DownloadQueueItem(
    val queueId: String,
    val workId: String = "",
    val title: String,
    val sourceUrl: String = "",
    val thumbnailUrl: String,
    val quality: String,
    val mode: MediaMode,
    val status: DownloadQueueStatus,
    val statusText: String,
    val progress: Int = 0,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBps: Long = 0L,
    val error: String = "",
    val lane: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)
