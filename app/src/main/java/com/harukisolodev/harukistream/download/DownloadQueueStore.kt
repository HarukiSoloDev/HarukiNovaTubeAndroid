package com.harukisolodev.harukistream.download

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class StoredQueueDownload(
    val queueId: String,
    val workId: String,
    val token: String,
    val lane: Int,
    val request: PendingDownload,
    val state: String = "ENQUEUED",
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Small persistent queue index used only to reconnect the UI to WorkManager.
 * The actual network job remains owned by WorkManager, so downloads can keep
 * running while Haruki is backgrounded or its activity is closed.
 */
class DownloadQueueStore(context: Context) {
    private val dir = File(context.filesDir, "download_queue").apply { mkdirs() }

    @Synchronized
    fun put(item: StoredQueueDownload) {
        File(dir, "${item.queueId}.json").writeText(item.toJson().toString())
    }

    @Synchronized
    fun get(queueId: String): StoredQueueDownload? = read(File(dir, "$queueId.json"))

    @Synchronized
    fun all(): List<StoredQueueDownload> = dir.listFiles()
        .orEmpty()
        .filter { it.extension == "json" }
        .mapNotNull(::read)
        .sortedByDescending { it.createdAt }

    @Synchronized
    fun updateExecution(queueId: String, workId: String, token: String, lane: Int, state: String) {
        val item = get(queueId) ?: return
        put(item.copy(workId = workId, token = token, lane = lane, state = state))
    }

    @Synchronized
    fun updateState(queueId: String, state: String) {
        val item = get(queueId) ?: return
        if (item.state != state) put(item.copy(state = state))
    }

    @Synchronized
    fun remove(queueId: String) {
        runCatching { File(dir, "$queueId.json").delete() }
    }

    private fun read(file: File): StoredQueueDownload? = runCatching {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        StoredQueueDownload(
            queueId = json.optString("queueId"),
            workId = json.optString("workId"),
            token = json.optString("token"),
            lane = json.optInt("lane", 0),
            state = json.optString("state", "ENQUEUED"),
            createdAt = json.optLong("createdAt", System.currentTimeMillis()),
            request = pendingFromJson(json.getJSONObject("request"))
        )
    }.getOrNull()

    private fun StoredQueueDownload.toJson() = JSONObject().apply {
        put("queueId", queueId)
        put("workId", workId)
        put("token", token)
        put("lane", lane)
        put("state", state)
        put("createdAt", createdAt)
        put("request", pendingToJson(request))
    }

    private fun pendingToJson(request: PendingDownload) = JSONObject().apply {
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
    }

    private fun pendingFromJson(json: JSONObject) = PendingDownload(
        mediaId = json.optString("mediaId"),
        title = json.optString("title"),
        sourceUrl = json.optString("sourceUrl"),
        thumbnail = json.optString("thumbnail"),
        quality = json.optString("quality"),
        mediaUrl = json.optString("mediaUrl"),
        audioUrl = json.optString("audioUrl"),
        mediaFallbackUrls = json.optJSONArray("mediaFallbackUrls")?.let { array ->
            (0 until array.length()).mapNotNull { i -> array.optString(i).takeIf(String::isNotBlank) }
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
        mode = json.optString("mode", "VIDEO")
    )
}
