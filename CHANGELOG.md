# Changelog

## 0.8.1
- Reworked video/audio downloads around bounded 8 MiB HTTP range chunks to avoid large-request CDN throttling.
- Added adaptive per-download connection scaling and shared OkHttp connection reuse.
- Fixed download starvation during playback: one download worker always keeps making progress while extra workers yield.
- Fixed audio-stream downloads falling back to one large open-ended request after the video stream.
- Preserved long-form playback when opening Shorts; Back now restores the previous Watch/miniplayer state and resumes when appropriate.
- Retuned long-form Media3 startup/rebuffer thresholds for faster playback response.
- Added Nova AI Fast, Smart and Deep modes; Smart is lighter than the old always-deep search, while Deep retains the full search depth.
- Reduced avoidable Compose work for Saved-key mapping and added a clearer performance download status card.
- Bumped versionCode to 810.

## 0.8.0
- Added adaptive phone/tablet/large-landscape navigation and feed layouts.
- Added centered wide-screen Watch player and portrait Shorts lane on larger displays.
- Added true offline-video reuse and Downloaded badges across browse surfaces.
- Added real MP3 audio download/transcoding at 192 kbps.
- Added local NovaTube playlists and Watch-page playlist picker.
- Added playlist-aware continuation after the final playlist item.
- Improved recommendation learning from searches, watches, saves, downloads, playlist additions, descriptions and hashtags.
- Added stronger early-feed channel/topic diversity and canonical video dedupe.
- Improved Nova AI with local-playlist evidence, wider query planning, deeper global search and stronger dedupe/diversification.
- Upgraded Media3 to 1.11.0 and retuned HD playback/buffering.
- Prevented optional next-item byte warmup while 720p+ playback needs the network.
- Removed a duplicated playlist-end related-feed load trigger found during the release audit.
- Preserved v0.7.x background playback, notification, Saved, subtitles, alternate audio, download acceleration, and icon improvements.

## 0.7.8
- Fixed Settings crash caused by nested same-direction vertical scrolling.
- Switched playback networking to Media3 OkHttp data source.
- Improved playback buffer/rebuffer behavior and HD fallback protection.
