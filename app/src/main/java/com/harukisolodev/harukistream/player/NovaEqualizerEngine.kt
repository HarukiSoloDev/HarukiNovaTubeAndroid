package com.harukisolodev.harukistream.player

import android.media.audiofx.Equalizer
import com.harukisolodev.harukistream.data.AppSettings
import com.harukisolodev.harukistream.data.EqualizerPreset
import kotlin.math.ln

/** Lightweight wrapper around Android's native per-audio-session Equalizer effect. */
class NovaEqualizerEngine(audioSessionId: Int) {
    private var equalizer: Equalizer? = if (audioSessionId > 0) {
        runCatching { Equalizer(0, audioSessionId) }.getOrNull()
    } else null

    val available: Boolean get() = equalizer != null

    fun apply(settings: AppSettings) {
        val curve = if (settings.equalizerPreset == EqualizerPreset.CUSTOM) {
            settings.equalizerCustomBands
        } else {
            settings.equalizerPreset.bandsDb
        }
        applyCurve(settings.equalizerEnabled, curve)
    }

    @Synchronized
    fun applyCurve(enabled: Boolean, requestedBandsDb: List<Float>) {
        val eq = equalizer ?: return
        val curve = List(5) { index -> (requestedBandsDb.getOrNull(index) ?: 0f).coerceIn(-10f, 10f) }
        runCatching {
            if (enabled) {
                val range = eq.bandLevelRange
                val minLevel = range.getOrNull(0)?.toInt() ?: -1500
                val maxLevel = range.getOrNull(1)?.toInt() ?: 1500
                repeat(eq.numberOfBands.toInt()) { index ->
                    val band = index.toShort()
                    val freqHz = (eq.getCenterFreq(band).toFloat() / 1000f).coerceAtLeast(1f)
                    val db = interpolateCurve(freqHz, curve)
                    val milliBels = (db * 100f).toInt().coerceIn(minLevel, maxLevel).toShort()
                    eq.setBandLevel(band, milliBels)
                }
            }
            eq.enabled = enabled
        }
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.enabled = false }
        runCatching { equalizer?.release() }
        equalizer = null
    }

    private fun interpolateCurve(frequencyHz: Float, curve: List<Float>): Float {
        val anchors = FREQUENCY_ANCHORS_HZ
        if (frequencyHz <= anchors.first()) return curve.first()
        if (frequencyHz >= anchors.last()) return curve.last()
        val x = ln(frequencyHz)
        for (i in 0 until anchors.lastIndex) {
            val left = anchors[i]
            val right = anchors[i + 1]
            if (frequencyHz in left..right) {
                val leftLog = ln(left)
                val rightLog = ln(right)
                val fraction = ((x - leftLog) / (rightLog - leftLog)).coerceIn(0f, 1f)
                return curve[i] + (curve[i + 1] - curve[i]) * fraction
            }
        }
        return 0f
    }

    companion object {
        /** Familiar five-band centers shown in the NovaTube UI. */
        val FREQUENCY_ANCHORS_HZ = listOf(60f, 230f, 910f, 3600f, 14000f)
    }
}
