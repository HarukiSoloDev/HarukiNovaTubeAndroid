package com.harukisolodev.harukistream.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-lifetime playback choices for the currently viewed media.
 * A manual quality selection should survive navigation to Downloads/Library and back,
 * but it should not permanently replace the user's Settings default for other videos.
 */
object PlaybackSelectionStore {
    private val qualityByMediaId = ConcurrentHashMap<String, String>()

    fun qualityFor(mediaId: String): String =
        if (mediaId.isBlank()) "" else qualityByMediaId[mediaId].orEmpty()

    fun setQuality(mediaId: String, qualityId: String) {
        if (mediaId.isBlank()) return
        if (qualityId.isBlank()) qualityByMediaId.remove(mediaId)
        else qualityByMediaId[mediaId] = qualityId
    }

    fun clear(mediaId: String) {
        if (mediaId.isNotBlank()) qualityByMediaId.remove(mediaId)
    }
}
