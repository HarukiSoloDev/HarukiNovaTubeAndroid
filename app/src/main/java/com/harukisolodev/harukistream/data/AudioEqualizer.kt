package com.harukisolodev.harukistream.data

/**
 * NovaTube exposes one predictable five-point curve to the UI, then maps it onto
 * however many hardware EQ bands the current Android device actually provides.
 */
enum class EqualizerPreset(
    val displayName: String,
    val description: String,
    val bandsDb: List<Float>
) {
    FLAT("Flat", "Natural sound with no tonal boost.", listOf(0f, 0f, 0f, 0f, 0f)),
    BASS_BOOST("Bass Boost", "Stronger kick and low-end without burying vocals.", listOf(7f, 5f, 1f, -1f, 0f)),
    POP("Pop", "Clear vocals with a lively low and high end.", listOf(2f, 1f, 3f, 2f, 3f)),
    ROCK("Rock", "Punchy bass, guitars and brighter cymbals.", listOf(5f, 3f, -1f, 3f, 5f)),
    HIP_HOP("Hip-Hop", "Deep bass and crisp presence for beats and vocals.", listOf(7f, 5f, 0f, 2f, 4f)),
    EDM("EDM", "Sub-bass impact with bright electronic detail.", listOf(8f, 5f, -1f, 3f, 6f)),
    VOCAL("Vocal", "Brings speech and singing forward with less boom.", listOf(-2f, 0f, 4f, 5f, 2f)),
    PODCAST("Podcast", "Speech-focused tuning for dialogue and spoken content.", listOf(-4f, -1f, 4f, 5f, 1f)),
    CLASSICAL("Classical", "Balanced detail with a gentle spacious top end.", listOf(1f, 0f, 1f, 2f, 4f)),
    MOVIE("Movie", "Fuller lows and clearer dialogue for long-form video.", listOf(4f, 2f, 3f, 3f, 4f)),
    NIGHT("Night", "Softer bass and treble so quiet listening stays comfortable.", listOf(-3f, -1f, 2f, 1f, -3f)),
    CUSTOM("Custom", "Your own five-band tuning.", listOf(0f, 0f, 0f, 0f, 0f));

    companion object {
        val popular: List<EqualizerPreset> = listOf(
            BASS_BOOST, POP, ROCK, HIP_HOP, EDM, VOCAL, PODCAST, MOVIE, CLASSICAL, NIGHT, FLAT
        )
    }
}

val EqualizerPreset.shortLabel: String
    get() = when (this) {
        EqualizerPreset.BASS_BOOST -> "Bass"
        EqualizerPreset.HIP_HOP -> "Hip-Hop"
        EqualizerPreset.PODCAST -> "Voice"
        else -> displayName
    }
