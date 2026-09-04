package com.harukisolodev.harukistream.data

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Local Watch Later / Saved list. No account is required. */
class SavedVideoStore(context: Context) {
    private val prefs = context.getSharedPreferences("haruki_stream_saved", Context.MODE_PRIVATE)

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
                        sessionCookie = o.optString("sessionCookie"),
                        uploaderAvatarUrl = o.optString("uploaderAvatar"),
                        uploaderVerified = o.optBoolean("uploaderVerified", false)
                    ))
                }
            }.distinctBy { canonicalKey(it.url, it.id) }
        }.getOrDefault(emptyList())
    }

    fun contains(url: String, id: String = ""): Boolean {
        val key = canonicalKey(url, id)
        return key.isNotBlank() && all().any { canonicalKey(it.url, it.id) == key }
    }

    @Synchronized
    fun toggle(item: BrowseVideo): Boolean {
        val items = all().toMutableList()
        val key = canonicalKey(item.url, item.id)
        val index = items.indexOfFirst { canonicalKey(it.url, it.id) == key }
        val nowSaved = index < 0
        if (nowSaved) items.add(0, item) else items.removeAt(index)
        write(items)
        return nowSaved
    }

    @Synchronized
    fun remove(url: String, id: String = "") {
        val key = canonicalKey(url, id)
        write(all().filterNot { canonicalKey(it.url, it.id) == key })
    }

    @Synchronized
    fun replace(items: List<BrowseVideo>) = write(items.distinctBy { canonicalKey(it.url, it.id) })

    @Synchronized
    fun clear() {
        // commit() makes the user's Save state durable before this call returns.
        prefs.edit().remove(KEY).commit()
    }

    private fun write(items: List<BrowseVideo>) {
        val array = JSONArray()
        items.distinctBy { canonicalKey(it.url, it.id) }.take(300).forEach { video ->
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
                put("sessionCookie", video.sessionCookie)
                put("uploaderAvatar", video.uploaderAvatarUrl)
                put("uploaderVerified", video.uploaderVerified)
            })
        }
        // Saved is a user action, not telemetry. Persist synchronously so a fast
        // app close/process kill cannot lose the tap after the button already glowed.
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    companion object {
        private const val KEY = "items"

        /** Stable identity across youtu.be, watch?v=, Shorts, live and embed URLs. */
        fun canonicalKey(url: String, id: String = ""): String {
            val cleanId = id.trim()
            val cleanUrl = url.trim()
            if (cleanUrl.isBlank()) {
                return if (cleanId.matches(Regex("[A-Za-z0-9_-]{11}"))) "yt:$cleanId" else cleanId
            }
            return runCatching {
                val uri = Uri.parse(cleanUrl)
                val host = uri.host.orEmpty().lowercase()
                val path = uri.pathSegments
                val videoId = when {
                    host == "youtu.be" || host.endsWith(".youtu.be") -> path.firstOrNull()
                    host.contains("youtube.com") && uri.getQueryParameter("v").orEmpty().isNotBlank() -> uri.getQueryParameter("v")
                    host.contains("youtube.com") && path.firstOrNull() in setOf("shorts", "live", "embed") -> path.getOrNull(1)
                    else -> null
                }?.trim().orEmpty()
                when {
                    videoId.matches(Regex("[A-Za-z0-9_-]{11}")) -> "yt:$videoId"
                    cleanId.matches(Regex("[A-Za-z0-9_-]{11}")) -> "yt:$cleanId"
                    else -> cleanUrl.substringBefore('#').removeSuffix("/")
                }
            }.getOrElse {
                if (cleanId.matches(Regex("[A-Za-z0-9_-]{11}"))) "yt:$cleanId"
                else cleanUrl.substringBefore('#').removeSuffix("/")
            }
        }
    }
}
