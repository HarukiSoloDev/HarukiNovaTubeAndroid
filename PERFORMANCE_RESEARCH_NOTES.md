# NovaTube v0.8.1 Performance Research Notes

This release focuses on changes that directly match observed NovaTube bottlenecks rather than adding large new frameworks.

## Download engine
- YouTube/googlevideo transfers can throttle large or open-ended HTTP requests. Current yt-dlp guidance and recent field reports show bounded chunks below roughly 10 MB can avoid the slow path. NovaTube therefore uses 8 MiB byte-range chunks when Content-Range support is confirmed.
- Audio is now chunked too. v0.8.0 accelerated the video side but could still download the separate audio stream as one open-ended request, matching the reported ~32 KB/s symptom.
- Connections adapt at chunk boundaries. Playback keeps priority, but downloads are never deliberately reduced to zero workers.
- A shared OkHttp client reuses connections across WorkManager downloads.

## Playback
- Media3 remains on 1.11.0. Long-form startup/rebuffer thresholds were reduced while retaining a substantial forward buffer.
- Entering Shorts pauses but does not destroy the long-form MediaSession/ExoPlayer. Back restores the existing Watch session instead of re-extracting/recreating it.

## Nova AI
- Fast: few first-page strategies, no slow per-result metadata enrichment.
- Smart: balanced search with limited deeper checks.
- Deep: the full multi-query, extra-page, channel/playlist and metadata search.
- This follows the common current AI search product split between fast lookup and deeper multi-step research. NovaTube does not embed third-party AI API secrets in the APK.

## Sources reviewed
- Android Media3 documentation: preload, caching, priority/data-source and current 1.11.0 release documentation.
- Android Compose performance guidance and Baseline Profile documentation.
- yt-dlp FAQ / issue reports on YouTube HTTP chunk-size throttling and range requests.
- Perplexity and OpenAI/Google documentation describing fast search versus multi-step/deep research modes.
