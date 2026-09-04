package com.harukisolodev.harukistream.player

import androidx.media3.common.Player
import com.harukisolodev.harukistream.data.DownloadSpeedMode
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates bulk downloads with foreground/background playback.
 *
 * v0.8.1 uses cooperative throttling instead of fully stopping WorkManager lanes. A download is
 * always allowed to make forward progress, while extra workers/connections stand down quickly
 * when playback is buffering or actively consuming bandwidth.
 */
object PlaybackNetworkCoordinator {
    @Volatile private var playing: Boolean = false
    @Volatile private var buffering: Boolean = false
    @Volatile private var playbackHeight: Int = 0
    private val activeDownloadLanes = ConcurrentHashMap.newKeySet<Int>()

    fun update(isPlaying: Boolean, playbackState: Int) {
        playing = isPlaying || playbackState == Player.STATE_BUFFERING
        buffering = playbackState == Player.STATE_BUFFERING
    }

    fun setPlaybackHeight(height: Int) {
        playbackHeight = height.coerceAtLeast(0)
    }

    fun reset() {
        playing = false
        buffering = false
        playbackHeight = 0
        // Download workers own their lane registration. Playback service restarts must not erase
        // those registrations or all existing workers would temporarily look like worker #0.
    }

    fun isPlaybackActive(): Boolean = playing
    fun isBuffering(): Boolean = buffering

    fun registerDownloadLane(lane: Int) {
        activeDownloadLanes += lane.coerceAtLeast(0)
    }

    fun unregisterDownloadLane(lane: Int) {
        activeDownloadLanes -= lane.coerceAtLeast(0)
    }

    /** Number of queue workers that may actively fetch data at the same time. Never returns zero. */
    fun allowedDownloadWorkers(mode: DownloadSpeedMode): Int {
        val highResolution = playing && playbackHeight >= 720
        if (buffering) return 1
        return when (mode) {
            DownloadSpeedMode.TURBO -> when {
                highResolution -> 1
                playing -> 1
                else -> 4
            }
            DownloadSpeedMode.AUTO -> when {
                highResolution -> 1
                playing -> 1
                else -> 2
            }
            DownloadSpeedMode.PLAYBACK_PRIORITY -> when {
                playing -> 1
                else -> 2
            }
        }
    }

    fun downloadLaneAllowed(lane: Int, mode: DownloadSpeedMode): Boolean {
        val normalized = lane.coerceAtLeast(0)
        val active = activeDownloadLanes.toList().sorted()
        // Existing queues created by an older build can have any lane number. Rank the currently
        // active lanes so a lone lane 2/3 is still allowed to progress as worker #0.
        val rank = active.indexOf(normalized).let { if (it >= 0) it else 0 }
        return rank < allowedDownloadWorkers(mode)
    }

    /**
     * Maximum bounded HTTP chunks one download may fetch concurrently. Changes are observed at
     * each 8 MiB chunk boundary, so playback can reclaim bandwidth without cancelling the job.
     */
    fun maxConnectionsPerDownload(mode: DownloadSpeedMode): Int {
        if (buffering) return 1
        val highResolution = playing && playbackHeight >= 720
        if (highResolution) return 1
        return when (mode) {
            DownloadSpeedMode.TURBO -> if (playing) 1 else 4
            DownloadSpeedMode.AUTO -> if (playing) 1 else 3
            DownloadSpeedMode.PLAYBACK_PRIORITY -> if (playing) 1 else 2
        }
    }

    /** Small cooperative pause between large chunks; never a per-read speed cap. */
    fun downloadYieldDelayMs(mode: DownloadSpeedMode): Long = when (mode) {
        DownloadSpeedMode.TURBO -> when {
            buffering -> 10L
            playing -> 4L
            else -> 0L
        }
        DownloadSpeedMode.AUTO -> when {
            buffering -> 18L
            playing -> 8L
            else -> 0L
        }
        DownloadSpeedMode.PLAYBACK_PRIORITY -> when {
            buffering -> 35L
            playing -> 22L
            else -> 4L
        }
    }
}
