# NovaTube v0.8.2 Equalizer + Performance Research Notes

This release adds visible polish without trading away responsiveness.

## Equalizer architecture
- Android's native `Equalizer` effect attaches to a specific audio session. NovaTube gives each relevant ExoPlayer a session ID and attaches one lightweight EQ engine to that session.
- NovaTube exposes a familiar five-point curve, then interpolates it over however many EQ bands the phone actually reports and clamps levels to the device-supported range.
- Popular presets use explicit portable curves instead of relying on device/manufacturer preset names, which can differ by device.
- Only the active Short owns an EQ engine. Neighboring Shorts may be preloaded for responsiveness but do not hold additional native effects.

## Interaction / Compose performance
- Press feedback uses `graphicsLayer` scale/alpha transforms so the visual response happens at the draw layer rather than forcing repeated layout changes.
- Destination transitions are intentionally short (roughly 110–180 ms) so the app feels deliberate without making navigation feel slow.
- Equalizer slider previews update the active playback engine directly and persist the custom curve only when the drag finishes.
- The playback service observes a distinct tuple of EQ-only settings rather than reacting to unrelated Settings changes.

## v0.8.1 performance work retained
- 8 MiB bounded HTTP range chunks for video and separate audio to avoid large/open-ended CDN slow paths.
- Playback-safe download scheduling keeps one worker progressing while extra workers yield bandwidth to playback.
- Shared OkHttp connection reuse and adaptive download concurrency.
- Long-form Watch preservation when entering Shorts and restoring with Back.
- Nova AI Fast / Smart / Deep profiles.

## Sources reviewed
- Android `android.media.audiofx.Equalizer` and `AudioManager` documentation.
- Android Media3 ExoPlayer audio-session APIs.
- Jetpack Compose animation/performance guidance, including `graphicsLayer` and content transitions.
- Existing v0.8.1 Media3/download research notes and yt-dlp range-request guidance.
