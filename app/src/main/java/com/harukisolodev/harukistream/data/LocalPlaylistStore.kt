package com.harukisolodev.harukistream.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class LocalPlaylist(
    val id: String,
    val name: String,
    val videos: List<BrowseVideo> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/** Lightweight local playlists. These do not require a Google/YouTube account. */
class LocalPlaylistStore(context: Context) {
    private val prefs = context.getSharedPreferences("haruki_local_playlists", Context.MODE_PRIVATE)

    @Synchronized
    fun all(): List<LocalPlaylist> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]").orEmpty())
        buildList {
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val videos = o.optJSONArray("videos")?.let(::videosFromJson).orEmpty()
                add(LocalPlaylist(
                    id = o.optString("id"),
                    name = o.optString("name", "Playlist"),
                    videos = videos,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis())
                ))
            }
        }.sortedByDescending { it.updatedAt }
    }.getOrDefault(emptyList())

    @Synchronized
    fun create(name: String): LocalPlaylist {
        val clean = name.trim().ifBlank { "My playlist" }.take(80)
        val playlist = LocalPlaylist(UUID.randomUUID().toString(), clean)
        write(listOf(playlist) + all())
        return playlist
    }

    @Synchronized
    fun addVideo(playlistId: String, video: BrowseVideo) {
        val key = SavedVideoStore.canonicalKey(video.url, video.id)
        val now = System.currentTimeMillis()
        val updated = all().map { playlist ->
            if (playlist.id != playlistId) playlist else {
                val videos = (playlist.videos.filterNot { SavedVideoStore.canonicalKey(it.url, it.id) == key } + video)
                    .takeLast(500)
                playlist.copy(videos = videos, updatedAt = now)
            }
        }
        write(updated)
    }

    @Synchronized
    fun removeVideo(playlistId: String, video: BrowseVideo) {
        val key = SavedVideoStore.canonicalKey(video.url, video.id)
        write(all().map { playlist ->
            if (playlist.id != playlistId) playlist else playlist.copy(
                videos = playlist.videos.filterNot { SavedVideoStore.canonicalKey(it.url, it.id) == key },
                updatedAt = System.currentTimeMillis()
            )
        })
    }

    @Synchronized
    fun delete(playlistId: String) = write(all().filterNot { it.id == playlistId })

    @Synchronized
    fun rename(playlistId: String, name: String) {
        val clean = name.trim().ifBlank { "My playlist" }.take(80)
        write(all().map { if (it.id == playlistId) it.copy(name = clean, updatedAt = System.currentTimeMillis()) else it })
    }

    fun contains(playlistId: String, video: BrowseVideo): Boolean {
        val key = SavedVideoStore.canonicalKey(video.url, video.id)
        return all().firstOrNull { it.id == playlistId }?.videos?.any {
            SavedVideoStore.canonicalKey(it.url, it.id) == key
        } == true
    }

    private fun write(items: List<LocalPlaylist>) {
        val array = JSONArray()
        items.take(100).forEach { playlist ->
            array.put(JSONObject().apply {
                put("id", playlist.id)
                put("name", playlist.name)
                put("createdAt", playlist.createdAt)
                put("updatedAt", playlist.updatedAt)
                val videos = JSONArray()
                playlist.videos.take(500).forEach { videos.put(videoToJson(it)) }
                put("videos", videos)
            })
        }
        prefs.edit().putString(KEY, array.toString()).apply()
    }

    private fun videosFromJson(array: JSONArray): List<BrowseVideo> = buildList {
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            add(BrowseVideo(
                id = o.optString("id"), url = o.optString("url"), title = o.optString("title"),
                uploader = o.optString("uploader"), thumbnailUrl = o.optString("thumbnail"),
                durationSeconds = o.optLong("duration", 0L), viewCount = o.optLong("views", -1L),
                uploadText = o.optString("uploadText"), shortForm = o.optBoolean("shortForm", false),
                service = o.optString("service", "YouTube"), playbackUrl = o.optString("playbackUrl"),
                sessionCookie = o.optString("sessionCookie"), uploaderAvatarUrl = o.optString("uploaderAvatar"),
                uploaderVerified = o.optBoolean("uploaderVerified", false)
            ))
        }
    }

    private fun videoToJson(video: BrowseVideo) = JSONObject().apply {
        put("id", video.id); put("url", video.url); put("title", video.title); put("uploader", video.uploader)
        put("thumbnail", video.thumbnailUrl); put("duration", video.durationSeconds); put("views", video.viewCount)
        put("uploadText", video.uploadText); put("shortForm", video.shortForm); put("service", video.service)
        put("playbackUrl", video.playbackUrl); put("sessionCookie", video.sessionCookie)
        put("uploaderAvatar", video.uploaderAvatarUrl); put("uploaderVerified", video.uploaderVerified)
    }

    companion object { private const val KEY = "playlists" }
}
