package com.harukisolodev.harukistream.data

/**
 * NovaTube exposes one predictable five-point curve to the UI, then maps it onto
 * however many hardware EQ bands the current Android device actually provides.
 *
 * Classical/Dance/Folk/Metal/Hip-Hop/Jazz/Pop/Rock use the long-standing AOSP
 * 5-band preset family for 60/230/910/3600/14000 Hz as a compatibility baseline.
 */
enum class EqualizerPreset(
    val displayName: String,
    val description: String,
    val bandsDb: List<Float>,
    val popularChoice: Boolean = false
) {
    FLAT("Flat", "Natural sound with no tonal boost.", listOf(0f, 0f, 0f, 0f, 0f)),
    BASS_BOOST("Bass Boost", "More low-end impact for music and headphones.", listOf(7f, 5f, 1f, -1f, -2f), true),
    TREBLE_BOOST("Treble Boost", "More clarity, air and high-frequency detail.", listOf(-2f, -1f, 0f, 4f, 6f), true),
    POP("Pop", "AOSP-style vocal-forward pop curve.", listOf(-1f, 2f, 5f, 1f, -2f), true),
    ROCK("Rock", "AOSP-style punchy lows and bright guitars/cymbals.", listOf(5f, 3f, -1f, 3f, 5f), true),
    HIP_HOP("Hip-Hop", "AOSP-style bass weight with clear presence.", listOf(5f, 3f, 0f, 1f, 3f), true),
    EDM("EDM", "Sub-bass impact and bright electronic detail.", listOf(8f, 5f, -1f, 3f, 6f)),
    DANCE("Dance", "AOSP-style energetic dance curve.", listOf(6f, 0f, 2f, 4f, 1f)),
    RNB("R&B", "Warm bass with smooth vocal presence.", listOf(4f, 3f, 2f, 3f, 3f)),
    JAZZ("Jazz", "AOSP-style lows and airy upper detail.", listOf(4f, 2f, -2f, 2f, 5f), true),
    ACOUSTIC("Acoustic", "Natural body with extra string and room detail.", listOf(2f, 1f, 0f, 3f, 4f)),
    CLASSICAL("Classical", "AOSP-style balanced orchestral detail.", listOf(5f, 3f, -2f, 4f, 4f), true),
    FOLK("Folk", "AOSP-style natural mids with restrained treble.", listOf(3f, 0f, 0f, 2f, -1f)),
    METAL("Metal", "AOSP heavy-metal style with strong mid energy.", listOf(4f, 1f, 9f, 3f, 0f)),
    VOCAL("Vocal", "Brings speech and singing forward with less boom.", listOf(-2f, 0f, 4f, 5f, 2f)),
    PODCAST("Podcast", "Speech-focused tuning for dialogue and spoken content.", listOf(-4f, -1f, 4f, 5f, 1f)),
    MOVIE("Movie", "Fuller lows and clearer dialogue for long-form video.", listOf(4f, 2f, 3f, 3f, 4f)),
    GAMING("Gaming", "Punch with extra positional/detail presence.", listOf(3f, 1f, 1f, 4f, 5f)),
    NIGHT("Night", "Softer extremes for comfortable quiet listening.", listOf(-3f, -1f, 2f, 1f, -3f)),
    CUSTOM("Custom", "Your own five-band tuning.", listOf(0f, 0f, 0f, 0f, 0f));

    companion object {
        val selectable: List<EqualizerPreset> = entries.filter { it != CUSTOM }
        val popular: List<EqualizerPreset> = selectable.filter { it.popularChoice }
    }
}

val EqualizerPreset.shortLabel: String
    get() = when (this) {
        EqualizerPreset.BASS_BOOST -> "Bass"
        EqualizerPreset.TREBLE_BOOST -> "Treble"
        EqualizerPreset.HIP_HOP -> "Hip-Hop"
        EqualizerPreset.PODCAST -> "Voice"
        else -> displayName
    }
