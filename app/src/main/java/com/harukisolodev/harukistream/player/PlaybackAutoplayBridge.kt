package com.harukisolodev.harukistream.player

import com.harukisolodev.harukistream.data.BrowseVideo
import com.harukisolodev.harukistream.extractor.BrowseRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

/**
 * In-process bridge used when PlaybackService advances while the watch UI is
 * minimized/backgrounded. The service owns the actual player transition; the
 * ViewModel only mirrors that already-playing item into the UI without
 * re-extracting/re-preparing it.
 *
 * StateFlow intentionally keeps the latest service advance so reopening the
 * activity while background playback is still alive restores the correct item.
 */
object PlaybackAutoplayBridge {
    data class Advance(
        val item: BrowseVideo,
        val payload: BrowseRepository.WatchPayload
    )

    private val _latest = MutableStateFlow<Advance?>(null)
    val advances = _latest.filterNotNull()

    fun publish(advance: Advance) {
        _latest.value = advance
    }

    fun clear() {
        _latest.value = null
    }
}
