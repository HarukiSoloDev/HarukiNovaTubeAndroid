package com.harukisolodev.harukistream.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.naman14.androidlame.AndroidLame
import com.naman14.androidlame.LameBuilder
import java.io.File
import java.io.OutputStream
import java.nio.ByteOrder

/** Decodes a downloaded YouTube audio stream to PCM and encodes a real MP3 file. */
object Mp3Transcoder {
    fun transcode(inputFile: File, output: OutputStream, title: String, artist: String = "", bitrateKbps: Int = 192) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputFile.absolutePath)
        var track = -1
        var mime = ""
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val candidate = format.getString(MediaFormat.KEY_MIME).orEmpty()
            if (candidate.startsWith("audio/")) { track = i; mime = candidate; break }
        }
        require(track >= 0 && mime.isNotBlank()) { "No decodable audio track was found." }
        extractor.selectTrack(track)
        val inputFormat = extractor.getTrackFormat(track)
        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        var lame: AndroidLame? = null
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val mp3Buffer = ByteArray(128 * 1024)

        try {
            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val buffer = decoder.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                            extractor.advance()
                        }
                    }
                }

                val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        if (lame == null) {
                            lame = LameBuilder()
                                .setInSampleRate(sampleRate)
                                .setOutSampleRate(sampleRate)
                                .setOutChannels(channels)
                                .setOutBitrate(bitrateKbps)
                                .setQuality(3)
                                .setId3tagTitle(title)
                                .setId3tagArtist(artist)
                                .build()
                        }
                    }
                    outputIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outputIndex)
                        if (info.size > 0 && buffer != null) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcmBytes = ByteArray(info.size)
                            buffer.get(pcmBytes)
                            val shortBuffer = java.nio.ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val pcm = ShortArray(shortBuffer.remaining())
                            shortBuffer.get(pcm)
                            val encoder = lame ?: LameBuilder()
                                .setInSampleRate(sampleRate)
                                .setOutSampleRate(sampleRate)
                                .setOutChannels(channels)
                                .setOutBitrate(bitrateKbps)
                                .setQuality(3)
                                .setId3tagTitle(title)
                                .setId3tagArtist(artist)
                                .build().also { lame = it }
                            val encoded = if (channels == 1) {
                                encoder.encode(pcm, pcm, pcm.size, mp3Buffer)
                            } else {
                                encoder.encodeBufferInterLeaved(pcm, pcm.size / channels, mp3Buffer)
                            }
                            if (encoded > 0) output.write(mp3Buffer, 0, encoded)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        decoder.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }
            lame?.let { encoder ->
                val flushed = encoder.flush(mp3Buffer)
                if (flushed > 0) output.write(mp3Buffer, 0, flushed)
            }
            output.flush()
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { extractor.release() }
            runCatching { lame?.close() }
        }
    }
}
