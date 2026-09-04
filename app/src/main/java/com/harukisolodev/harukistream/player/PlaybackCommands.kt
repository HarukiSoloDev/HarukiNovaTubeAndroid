package com.harukisolodev.harukistream.player

import android.os.Bundle
import androidx.media3.session.SessionCommand

object PlaybackCommands {
    const val ACTION_PLAY_VARIANT = "com.harukisolodev.harukistream.PLAY_VARIANT"
    const val ACTION_SKIP_NEXT = "com.harukisolodev.harukistream.SKIP_NEXT"
    const val ACTION_SKIP_PREVIOUS = "com.harukisolodev.harukistream.SKIP_PREVIOUS"

    const val ARG_MEDIA_ID = "media_id"
    const val ARG_PAGE_URL = "page_url"
    const val ARG_TITLE = "title"
    const val ARG_UPLOADER = "uploader"
    const val ARG_ARTWORK = "artwork"
    const val ARG_VIDEO_URL = "video_url"
    const val ARG_VARIANT_ID = "variant_id"
    const val ARG_AUDIO_URL = "audio_url"
    const val ARG_VIDEO_MIME = "video_mime"
    const val ARG_AUDIO_MIME = "audio_mime"
    const val ARG_REQUEST_HEADERS = "request_headers"
    const val ARG_SUBTITLES = "subtitles"
    const val ARG_SUBTITLE_ID = "subtitle_id"
    const val ARG_SUBTITLE_URL = "subtitle_url"
    const val ARG_SUBTITLE_MIME = "subtitle_mime"
    const val ARG_SUBTITLE_LANGUAGE = "subtitle_language"
    const val ARG_SUBTITLE_LABEL = "subtitle_label"

    // Background/miniplayer autoplay state. The visible WatchScreen refreshes
    // these values whenever the current queue or Autoplay toggle changes, then
    // PlaybackService owns the actual end-of-item transition.
    const val ARG_AUTOPLAY_ENABLED = "autoplay_enabled"
    const val ARG_AUTOPLAY_QUEUE = "autoplay_queue"
    const val ARG_AUTOPLAY_URL = "autoplay_url"
    const val ARG_AUTOPLAY_ID = "autoplay_id"
    const val ARG_AUTOPLAY_TITLE = "autoplay_title"
    const val ARG_AUTOPLAY_UPLOADER = "autoplay_uploader"
    const val ARG_AUTOPLAY_ARTWORK = "autoplay_artwork"
    const val ARG_AUTOPLAY_DURATION = "autoplay_duration"
    const val ARG_AUTOPLAY_VIEWS = "autoplay_views"
    const val ARG_AUTOPLAY_UPLOAD_TEXT = "autoplay_upload_text"
    const val ARG_AUTOPLAY_SERVICE = "autoplay_service"
    const val ARG_AUTOPLAY_AVATAR = "autoplay_avatar"
    const val ARG_AUTOPLAY_VERIFIED = "autoplay_verified"
    const val ARG_PREFERRED_HEIGHT = "preferred_height"
    const val ARG_EXPLICIT_QUALITY = "explicit_quality"
    const val ARG_BACKGROUND_REDUCED = "background_reduced"

    val PLAY_VARIANT = SessionCommand(ACTION_PLAY_VARIANT, Bundle.EMPTY)
    val SKIP_NEXT = SessionCommand(ACTION_SKIP_NEXT, Bundle.EMPTY)
    val SKIP_PREVIOUS = SessionCommand(ACTION_SKIP_PREVIOUS, Bundle.EMPTY)
}
