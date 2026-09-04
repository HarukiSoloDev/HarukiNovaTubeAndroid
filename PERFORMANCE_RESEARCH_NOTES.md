# NovaTube v0.8.3 Personalization + Performance Research Notes

This release adds visible polish without trading away responsiveness.

## Equalizer architecture
- Android's native `Equalizer` effect attaches to a specific audio session. NovaTube gives each relevant ExoPlayer a session ID and attaches one lightweight EQ engine to that session.
- NovaTube exposes a familiar five-point curve, then interpolates it over however many EQ bands the phone actually reports and clamps levels to the device-supported range.
- Presets use explicit portable curves instead of relying on device/manufacturer preset names, which can differ by device. The Popular badge is an editorial "common preset style" marker, not a claim about live usage data.
- Only the active Short owns an EQ engine. v0.8.3 also avoids retaining an extra off-screen Shorts player; the next Short can still have its extracted media metadata prepared ahead.

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


## v0.8.3
- YouTube documents that Shorts personalization uses watch/search history and topics, and that interests can inform recommendations across formats. NovaTube now reuses the Home profile for most Shorts discovery queries.
- Android Compose guidance recommends stable keys/content types for heterogeneous lazy lists; hot list paths were updated where useful.
- Shorts no longer intentionally retains an extra off-screen pager page/player; media extraction for the next Short is still prefetched.
- Local playlist persistence moved from commit() to apply() so disk I/O is not performed synchronously on the UI interaction path.
- Equalizer genre curves use the AOSP five-band preset family where an AOSP reference exists.
