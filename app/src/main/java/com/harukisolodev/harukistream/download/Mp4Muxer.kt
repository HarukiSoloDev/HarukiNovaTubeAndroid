package com.harukisolodev.harukistream.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileDescriptor
import java.nio.ByteBuffer

object Mp4Muxer {
    fun mux(videoFile: File, audioFile: File, outputFd: FileDescriptor) {
        val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
        val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
        val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        try {
            val videoInput = findTrack(videoExtractor, "video/")
            val audioInput = findTrack(audioExtractor, "audio/")
            require(videoInput >= 0) { "No video track was found." }
            require(audioInput >= 0) { "No audio track was found." }

            val videoFormat = videoExtractor.getTrackFormat(videoInput)
            val audioFormat = audioExtractor.getTrackFormat(audioInput)
            val videoOutput = muxer.addTrack(videoFormat)
            val audioOutput = muxer.addTrack(audioFormat)

            if (videoFormat.containsKey(MediaFormat.KEY_ROTATION)) {
                muxer.setOrientationHint(videoFormat.getInteger(MediaFormat.KEY_ROTATION))
            }

            muxer.start()
            copyTrack(videoExtractor, videoInput, muxer, videoOutput, videoFormat)
            copyTrack(audioExtractor, audioInput, muxer, audioOutput, audioFormat)
        } finally {
            runCatching { muxer.stop() }
            runCatching { muxer.release() }
            videoExtractor.release()
            audioExtractor.release()
        }
    }

    private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
            if (mime.startsWith(prefix)) return i
        }
        return -1
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        inputTrack: Int,
        muxer: MediaMuxer,
        outputTrack: Int,
        format: MediaFormat
    ) {
        extractor.selectTrack(inputTrack)
        val suggested = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else 8 * 1024 * 1024
        val buffer = ByteBuffer.allocate(maxOf(suggested, 8 * 1024 * 1024))
        val info = MediaCodec.BufferInfo()

        while (true) {
            buffer.clear()
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outputTrack, buffer, info)
            extractor.advance()
        }
        extractor.unselectTrack(inputTrack)
    }
}
