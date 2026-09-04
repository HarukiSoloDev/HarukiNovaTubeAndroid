# Feature Status — v0.8.1

## Performance / playback
- PASS — Media3 1.11.0 with OkHttp playback data source and existing disk cache.
- PASS — long-form buffer retuned to 45–120 s with 2.5 s startup and 6 s post-rebuffer target.
- PASS — downloads never intentionally drop to zero active workers while playback is active.
- PASS — opening Shorts preserves the long-form playback service and exact Watch/miniplayer return state.
- PASS — existing background playback, notification restore, Prev/Next, alternate audio, captions and autoplay retained.

## Download engine
- PASS — video and separate audio use bounded 8 MiB byte-range chunks when the endpoint supports Content-Range.
- PASS — shared OkHttp client for connection reuse.
- PASS — adaptive per-download connection count at chunk boundaries.
- PASS — Auto, Turbo and Playback Priority modes retained.
- PASS — MP3 mode downloads source audio through the same chunk engine before 192 kbps transcoding.
- PASS — queue pause/cancel/retry and download notifications retained.

## Nova AI
- PASS — Fast mode: low-latency first-page search with no slow metadata enrichment.
- PASS — Smart mode: balanced query coverage with limited deep-page/entity/metadata checks.
- PASS — Deep mode: up to 16 strategies plus extra pages, channels/playlists and metadata enrichment.
- PASS — History, Saved, Downloads and local playlists remain optional ranking evidence; wider YouTube search remains primary.

## Adaptive UI / library
- PASS — compact phone bottom navigation and larger-screen navigation rail.
- PASS — multi-column feeds and constrained wide-screen Watch/Shorts layouts retained.
- PASS — local playlists, offline reuse and Downloaded badges retained.
- PASS — Downloads performance card and Nova AI search-mode controls added.

## Build validation
- PASS — project preflight checks 50 Kotlin files, XML parsing, delimiter balance, critical playback/download/AI/navigation regressions, AndroidX/TAndroidLame compatibility guard, removed ad/TikTok markers and obvious embedded secrets.
- NOTE — final Android Gradle compilation must still be run in Android Studio because this sandbox has no Android SDK and its Gradle wrapper cannot download the distribution here.
