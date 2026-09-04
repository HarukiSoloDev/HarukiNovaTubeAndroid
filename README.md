# Haruki NovaTube Android v0.8.1

NovaTube is a native Android video client written in Kotlin + Jetpack Compose.

## v0.8.1 performance highlights

- **Playback-safe downloads:** downloads no longer intentionally stop when playback buffers. At least one active worker keeps progressing while extra workers/connections yield bandwidth to Media3.
- **Faster video + audio transfers:** supported CDN streams use bounded **8 MiB HTTP range chunks**. Separate audio uses the same fast path instead of falling back to one large request after the video finishes.
- **Adaptive connection scaling:** Auto/Turbo/Playback Priority adjust queue workers and per-download connections at chunk boundaries; a shared OkHttp client reuses connections across workers.
- **Faster long-form response:** Media3 1.11.0 remains in place, with a 45–120 s forward buffer and lower 2.5 s startup / 6 s post-rebuffer thresholds.
- **Shorts back-navigation fix:** opening Shorts pauses but keeps the existing long-form MediaSession/ExoPlayer alive. Back restores the previous Watch or miniplayer state and resumes only if it was previously playing.
- **Nova AI modes:** Fast minimizes network/search work, Smart balances speed and coverage, and Deep retains the full multi-query/channel/playlist/metadata search.
- **UI polish:** Nova AI now exposes clear search-mode controls, Downloads shows the performance engine/combined active speed, and Saved URL keys are memoized to reduce avoidable recomposition work.

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
