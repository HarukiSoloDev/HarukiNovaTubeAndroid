# Haruki NovaTube Android v0.8.2

NovaTube is a native Android video client written in Kotlin + Jetpack Compose.

## v0.8.2 equalizer + polish highlights

- **Native Nova Equalizer:** lightweight per-audio-session Android EQ attached directly to Media3/ExoPlayer instead of adding a heavy DSP dependency.
- **Popular sound setups:** Flat, Bass Boost, Pop, Rock, Hip-Hop, EDM, Vocal, Podcast, Classical, Movie and Night presets.
- **Custom 5-band tuner:** live 60 Hz / 230 Hz / 910 Hz / 3.6 kHz / 14 kHz controls, safely mapped and clamped to the EQ bands supported by the phone.
- **App-wide audio tuning:** long-form playback, the active Short, and downloaded-video playback all use the selected EQ. Adjacent preloaded Shorts do not keep extra EQ engines alive.
- **Premium interaction feedback:** frequently tapped cards and navigation controls now use short press-scale/alpha feedback while keeping layout stable.
- **Smoother navigation:** subtle fade + horizontal slide transitions between app destinations instead of abrupt screen swaps.
- **Performance-conscious polish:** press animations use draw-layer transforms, EQ settings are observed with distinct state, live slider previews avoid DataStore writes on every drag frame, and long-form back-buffer memory was trimmed slightly.

## v0.8.1 performance improvements retained

- Playback-safe smart downloads with at least one worker continuing during playback.
- Bounded 8 MiB HTTP range chunks for both video and separate audio downloads.
- Adaptive download connection scaling and shared OkHttp connection reuse.
- Faster Media3 long-form startup/rebuffer thresholds.
- Shorts Back restores the preserved long-form Watch/miniplayer session.
- Nova AI Fast, Smart and Deep modes.

## Existing v0.8 features retained

- Adaptive phone / tablet / large-landscape layout with bottom navigation or navigation rail.
- True offline video reuse and Downloaded badges.
- Real 192 kbps MP3 transcoding.
- Local NovaTube playlists and playlist-aware autoplay continuation.
- Recommendation learning from searches, watches, saves, downloads, playlist additions, descriptions and hashtags.
- Background playback, notification restore, Prev/Next, alternate/dubbed audio, captions, Saved and download controls.

## Build

Open the project in Android Studio and run:

```bat
gradlew.bat :app:assembleDebug
```

or run `VERIFY_BUILD.bat`.

Release APKs should be signed with the same NovaTube release keystore used for future updates.
