package com.harukisolodev.harukistream.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class WatchHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("haruki_stream_history", Context.MODE_PRIVATE)

    fun all(): List<BrowseVideo> {
        val raw = prefs.getString(KEY, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    add(BrowseVideo(
                        id = o.optString("id"),
                        url = o.optString("url"),
                        title = o.optString("title"),
                        uploader = o.optString("uploader"),
                        thumbnailUrl = o.optString("thumbnail"),
                        durationSeconds = o.optLong("duration", 0L),
                        viewCount = o.optLong("views", -1L),
                        uploadText = o.optString("uploadText"),
                        shortForm = o.optBoolean("shortForm", false),
                        service = o.optString("service", "YouTube"),
                        playbackUrl = o.optString("playbackUrl"),
                        uploaderAvatarUrl = o.optString("uploaderAvatar"),
                        uploaderVerified = o.optBoolean("uploaderVerified", false)
                    ))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun add(item: BrowseVideo) {
        val items = all().filterNot { it.url == item.url }.toMutableList()
        items.add(0, item)
        val array = JSONArray()
        items.take(100).forEach { video ->
            array.put(JSONObject().apply {
                put("id", video.id)
                put("url", video.url)
                put("title", video.title)
                put("uploader", video.uploader)
                put("thumbnail", video.thumbnailUrl)
                put("duration", video.durationSeconds)
                put("views", video.viewCount)
                put("uploadText", video.uploadText)
                put("shortForm", video.shortForm)
                put("service", video.service)
                put("playbackUrl", video.playbackUrl)
                put("uploaderAvatar", video.uploaderAvatarUrl)
                put("uploaderVerified", video.uploaderVerified)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    fun clear() = prefs.edit().remove(KEY).apply()

    companion object { private const val KEY = "items" }
}
