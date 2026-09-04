package com.harukisolodev.harukistream.extractor

import com.harukisolodev.harukistream.data.AnalyzedMedia
import com.harukisolodev.harukistream.data.AudioTrackOption
import com.harukisolodev.harukistream.data.MediaVariant
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.net.URI
import java.util.Locale

class HarukiExtractor {
    fun analyze(input: String, sessionCookie: String = ""): AnalyzedMedia {
        val url = input.trim()
        require(url.startsWith("http://") || url.startsWith("https://")) { "Paste a full web link first." }

        return try {
            fromNewPipe(url)
        } catch (e: ExtractionException) {
            directMedia(url) ?: throw FriendlyExtractionException(
                "This website is not supported by the Android extractor yet. " +
                    "Haruki NovaTube focuses on YouTube. Paste a normal YouTube video, Short, playlist item, or direct media link.",
                e
            )
        }
    }

    fun fromStreamInfo(info: StreamInfo, sourceUrl: String): AnalyzedMedia = fromNewPipe(sourceUrl, info)

    private fun fromNewPipe(url: String, suppliedInfo: StreamInfo? = null): AnalyzedMedia {
        val info = suppliedInfo ?: StreamInfo.getInfo(url)
        val serviceName = runCatching {
            NewPipe.getService(info.serviceId).serviceInfo.name
        }.getOrDefault("Web")

        val playableAudioStreams = info.audioStreams
            .filter { it.isUrl && it.content.startsWith("http") && it.format != null }
        val m4aAudioStreams = playableAudioStreams.filter { it.format == MediaFormat.M4A }

        // YouTube can expose original, dubbed, descriptive and secondary audio in
        // either M4A/AAC or WebM/Opus. Keep every playable track for the player menu,
        // while the default adaptive MP4 download/playback pairing still prefers M4A.
        val defaultAudioM4a = m4aAudioStreams
            .sortedWith(
                compareByDescending<AudioStream> { it.audioTrackType == AudioTrackType.ORIGINAL }
                    .thenByDescending { it.audioTrackType != AudioTrackType.DUBBED }
                    .thenByDescending { maxOf(it.averageBitrate, it.bitrate) }
            )
            .firstOrNull()

        val audioTracks = buildAudioTrackOptions(playableAudioStreams)

        val combined = info.videoStreams
            .filter { it.isUrl && it.content.startsWith("http") && !it.isVideoOnly() }
            .mapNotNull { combinedVariant(it) }

        val adaptive = info.videoOnlyStreams
            .filter { it.isUrl && it.content.startsWith("http") }
            .mapNotNull { video -> adaptiveVariant(video, defaultAudioM4a) }

        // Keep both progressive (video+audio in one stream) and adaptive
        // (video-only + separate audio) variants when YouTube exposes both.
        // The player can prefer progressive for smooth Original playback, then
        // switch to the adaptive sibling only when Dubbed/Description is selected.
        val videoVariants = (combined + adaptive)
            .distinctBy { it.id }
            .sortedWith(
                compareByDescending<MediaVariant> { it.qualityHeight }
                    .thenBy { it.label.contains("60", ignoreCase = true) || it.label.contains("fps60", ignoreCase = true) }
                    .thenBy { if (it.bitrate > 0) it.bitrate else Int.MAX_VALUE }
                    .thenBy { it.fps.coerceAtLeast(0) }
                    .thenBy { it.separateAudio }
                    .thenByDescending { it.extension == "mp4" }
                    .thenByDescending { it.codecNote.contains("H.264", ignoreCase = true) }
            )

        val audioVariants = info.audioStreams
            .filter { it.isUrl && it.content.startsWith("http") }
            .mapNotNull { audioVariant(it) }
            .distinctBy { it.label }
            .sortedByDescending { it.qualityHeight }
            .take(6)

        if (videoVariants.isEmpty() && audioVariants.isEmpty()) {
            throw FriendlyExtractionException("No downloadable stream was returned for this link.")
        }

        val thumbnail = info.thumbnails
            .maxByOrNull { maxOf(it.width, 0) * maxOf(it.height, 0) }
            ?.url.orEmpty()

        return AnalyzedMedia(
            mediaId = info.id,
            sourceUrl = url,
            title = info.name.ifBlank { "Untitled media" },
            uploader = info.uploaderName.orEmpty(),
            durationSeconds = info.duration,
            thumbnailUrl = thumbnail,
            serviceName = serviceName,
            videoVariants = videoVariants,
            audioVariants = audioVariants,
            audioTracks = audioTracks
        )
    }

    private fun buildAudioTrackOptions(streams: List<AudioStream>): List<AudioTrackOption> {
        if (streams.isEmpty()) return emptyList()
        val groups = streams.groupBy { audio ->
            audio.audioTrackId?.takeIf { it.isNotBlank() }
                ?: audio.audioTrackName?.takeIf { it.isNotBlank() }
                ?: audio.audioLocale?.toLanguageTag()?.takeIf { it.isNotBlank() }
                ?: "default"
        }
        return groups.mapNotNull { (groupId, options) ->
            val audio = options.maxByOrNull { maxOf(it.averageBitrate, it.bitrate) } ?: return@mapNotNull null
            val type = audio.audioTrackType
            val localeName = audio.audioLocale?.getDisplayName(Locale.getDefault()).orEmpty()
            val providedName = audio.audioTrackName.orEmpty()
            val typeLabel = when (type) {
                AudioTrackType.ORIGINAL -> "Original"
                AudioTrackType.DUBBED -> "Dubbed"
                AudioTrackType.DESCRIPTIVE -> "Audio description"
                AudioTrackType.SECONDARY -> "Alternate"
                else -> "Audio"
            }
            val label = when {
                providedName.isNotBlank() && providedName.contains(typeLabel, ignoreCase = true) -> providedName
                providedName.isNotBlank() -> "$providedName • $typeLabel"
                localeName.isNotBlank() -> "$localeName • $typeLabel"
                else -> typeLabel
            }
            AudioTrackOption(
                id = groupId,
                label = label,
                languageCode = audio.audioLocale?.toLanguageTag().orEmpty(),
                url = audio.content,
                mimeType = audio.format?.mimeType ?: "audio/mp4",
                original = type == AudioTrackType.ORIGINAL || (type == null && groups.size == 1),
                dubbed = type == AudioTrackType.DUBBED,
                descriptive = type == AudioTrackType.DESCRIPTIVE
            )
        }.sortedWith(
            compareByDescending<AudioTrackOption> { it.original }
                .thenBy { it.dubbed }
                .thenBy { it.label.lowercase(Locale.getDefault()) }
        )
    }

    private fun combinedVariant(stream: VideoStream): MediaVariant? {
        val format = stream.format ?: return null
        if (format != MediaFormat.MPEG_4 && format != MediaFormat.WEBM && format != MediaFormat.v3GPP) return null
        val label = stream.getResolution().ifBlank { "Video" }
        val codec = stream.codec.orEmpty()
        return MediaVariant(
            id = "v-${stream.id}",
            label = label,
            qualityHeight = parseHeight(label),
            videoUrl = stream.content,
            mimeType = format.mimeType,
            extension = format.suffix,
            codecNote = prettyCodec(codec),
            bitrate = stream.bitrate.coerceAtLeast(0),
            fps = stream.fps.coerceAtLeast(0),
            separateAudio = false
        )
    }

    private fun adaptiveVariant(video: VideoStream, audio: AudioStream?): MediaVariant? {
        if (audio == null || video.format != MediaFormat.MPEG_4 || audio.format != MediaFormat.M4A) return null
        val codec = video.codec.orEmpty()
        // Android MediaMuxer can safely combine the common MP4 H.264 + M4A/AAC pair.
        if (codec.isNotBlank() && !codec.contains("avc", true) && !codec.contains("h264", true)) return null
        val label = video.getResolution().ifBlank { return null }
        return MediaVariant(
            id = "va-${video.id}-${audio.id}",
            label = label,
            qualityHeight = parseHeight(label),
            videoUrl = video.content,
            audioUrl = audio.content,
            mimeType = "video/mp4",
            extension = "mp4",
            codecNote = "H.264 + AAC",
            bitrate = video.bitrate.coerceAtLeast(0),
            fps = video.fps.coerceAtLeast(0),
            separateAudio = true
        )
    }

    private fun audioVariant(audio: AudioStream): MediaVariant? {
        val format = audio.format ?: return null
        val kbps = maxOf(audio.averageBitrate, audio.bitrate).coerceAtLeast(0) / 1000
        val label = if (kbps > 0) "$kbps kbps • ${format.name}" else format.name
        return MediaVariant(
            id = "a-${audio.id}",
            label = label,
            qualityHeight = kbps,
            videoUrl = audio.content,
            mimeType = format.mimeType,
            extension = format.suffix,
            codecNote = audio.codec.orEmpty(),
            bitrate = maxOf(audio.averageBitrate, audio.bitrate).coerceAtLeast(0),
            fps = 0,
            separateAudio = false
        )
    }

    private fun directMedia(url: String): AnalyzedMedia? {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = when (ext) {
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "ogg", "opus" -> "audio/ogg"
            else -> return null
        }
        val isVideo = mime.startsWith("video/")
        val fileName = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { "Direct media" }
        val variant = MediaVariant(
            id = "direct-${url.hashCode()}",
            label = if (isVideo) "Source quality" else "Original audio",
            qualityHeight = 0,
            videoUrl = url,
            mimeType = mime,
            extension = ext
        )
        return AnalyzedMedia(
            mediaId = "direct-${url.hashCode()}",
            sourceUrl = url,
            title = fileName,
            uploader = "Direct link",
            durationSeconds = 0,
            thumbnailUrl = "",
            serviceName = "Direct",
            videoVariants = if (isVideo) listOf(variant) else emptyList(),
            audioVariants = if (isVideo) emptyList() else listOf(variant)
        )
    }

    private fun parseHeight(label: String): Int = Regex("(\\d{3,4})").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun prettyCodec(codec: String): String = when {
        codec.contains("avc", true) || codec.contains("h264", true) -> "H.264"
        codec.contains("av01", true) || codec.contains("av1", true) -> "AV1"
        codec.contains("vp9", true) -> "VP9"
        else -> codec
    }
}

class FriendlyExtractionException(message: String, cause: Throwable? = null) : Exception(message, cause)
