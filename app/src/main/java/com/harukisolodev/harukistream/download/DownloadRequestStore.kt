package com.harukisolodev.harukistream.download

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the long stream URLs outside WorkManager Data.
 * WorkManager Data is intentionally small and should only carry our request token.
 */
data class PendingDownload(
    val mediaId: String,
    val title: String,
    val sourceUrl: String,
    val thumbnail: String,
    val quality: String,
    val mediaUrl: String,
    val audioUrl: String,
    val mediaFallbackUrls: List<String> = emptyList(),
    val requestHeaders: Map<String, String> = emptyMap(),
    val mimeType: String,
    val extension: String,
    val mode: String,
    val transcodeToMp3: Boolean = false,
    val sourceMimeType: String = "",
    val sourceExtension: String = ""
)

class DownloadRequestStore(context: Context) {
    private val dir = File(context.filesDir, "pending_downloads").apply { mkdirs() }

    fun put(token: String, request: PendingDownload) {
        val json = JSONObject().apply {
            put("mediaId", request.mediaId)
            put("title", request.title)
            put("sourceUrl", request.sourceUrl)
            put("thumbnail", request.thumbnail)
            put("quality", request.quality)
            put("mediaUrl", request.mediaUrl)
            put("audioUrl", request.audioUrl)
            put("mediaFallbackUrls", JSONArray(request.mediaFallbackUrls))
            put("requestHeaders", JSONObject(request.requestHeaders))
            put("mimeType", request.mimeType)
            put("extension", request.extension)
            put("mode", request.mode)
            put("transcodeToMp3", request.transcodeToMp3)
            put("sourceMimeType", request.sourceMimeType)
            put("sourceExtension", request.sourceExtension)
        }
        File(dir, "$token.json").writeText(json.toString())
    }

    fun get(token: String): PendingDownload? = runCatching {
        val file = File(dir, "$token.json")
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        PendingDownload(
            mediaId = json.optString("mediaId"),
            title = json.optString("title"),
            sourceUrl = json.optString("sourceUrl"),
            thumbnail = json.optString("thumbnail"),
            quality = json.optString("quality"),
            mediaUrl = json.optString("mediaUrl"),
            audioUrl = json.optString("audioUrl"),
            mediaFallbackUrls = json.optJSONArray("mediaFallbackUrls")?.let { array ->
                (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
            }.orEmpty(),
            requestHeaders = json.optJSONObject("requestHeaders")?.let { headers ->
                buildMap {
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        headers.optString(key).takeIf(String::isNotBlank)?.let { put(key, it) }
                    }
                }
            }.orEmpty(),
            mimeType = json.optString("mimeType", "video/mp4"),
            extension = json.optString("extension", "mp4"),
            mode = json.optString("mode"),
            transcodeToMp3 = json.optBoolean("transcodeToMp3", false),
            sourceMimeType = json.optString("sourceMimeType"),
            sourceExtension = json.optString("sourceExtension")
        )
    }.getOrNull()

    fun remove(token: String) {
        runCatching { File(dir, "$token.json").delete() }
    }
}
