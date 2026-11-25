// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.engine.lite

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import com.libreopus.audio.api.ChannelMode
import com.libreopus.audio.api.ExportConfig
import com.libreopus.audio.api.ExportFormat
import com.libreopus.audio.api.ExportResult
import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.dsp.container.FlacMetadataCodec
import com.libreopus.dsp.container.OpusContainer
import com.libreopus.dsp.container.WavCodec
import com.libreopus.dsp.effects.HermiteResampler
import com.libreopus.dsp.effects.ResamplingTimelineReader
import com.libreopus.dsp.effects.SoftClipLimiter
import com.libreopus.dsp.storage.DiskPcmStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.ensureActive

internal object MediaCodecEncoders {

    suspend fun export(
        reader: PcmTimelineReader,
        config: ExportConfig,
        outputStream: OutputStream,
        tempDir: File,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        return when (config.format) {
            ExportFormat.WAV -> {
                val counting = CountingStream(outputStream)
                val adapted = adaptReader(reader, config.channelMode, config.sampleRateHz)
                WavCodec.writePcmTimelineAsWav(adapted, config.wavBitDepth, config.metadata, counting, onProgress)
                val durMs = if (adapted.sampleRate > 0) (adapted.totalFrames * 1000L) / adapted.sampleRate else 0L
                ExportResult(counting.bytesWritten, durMs, wasLossless = true)
            }
            ExportFormat.OPUS, ExportFormat.OGG_OPUS -> {
                encodeOggOpus(reader, config, outputStream, onProgress)
            }
            ExportFormat.FLAC -> {
                encodeFlac(reader, config, outputStream, tempDir, onProgress)
            }
            ExportFormat.M4A -> {
                encodeM4aAac(reader, config, outputStream, tempDir, onProgress)
            }
        }
    }

    private suspend fun encodeOggOpus(
        reader: PcmTimelineReader,
        config: ExportConfig,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        val adapted = adaptReader(reader, config.channelMode, 0)
        val outChannels = adapted.channels.coerceIn(1, 2)
        val opusRate = 48000
        val bitrate = (config.bitrateKbps.coerceIn(16, 510)) * 1000

        // Resample whole stream or chunked stream to 48 kHz cleanly
        val totalOutFrames48k = if (adapted.sampleRate == opusRate) {
            adapted.totalFrames
        } else {
            ((adapted.totalFrames * opusRate.toDouble()) / adapted.sampleRate).toLong().coerceAtLeast(1L)
        }

        val codec = createSoftwarePreferredEncoder("audio/opus", "c2.android.opus.encoder")
        val format = MediaFormat.createAudioFormat("audio/opus", opusRate, outChannels).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 960 * outChannels * 2 * 4)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val frameSize20ms = 960 // 20 ms at 48 kHz
        val info = MediaCodec.BufferInfo()
        val encodedPackets = mutableListOf<ByteArray>()
        var preSkip = 312

        // Pre-feed buffer in 48 kHz PCM
        val srcChunkFrames = 4096
        val srcShort = ShortArray(srcChunkFrames * outChannels)
        var srcCursor = 0L
        val pcm48Queue = ShortQueue(frameSize20ms * outChannels * 4)

        fun refill48kQueue() {
            while (pcm48Queue.sizeFrames(outChannels) < frameSize20ms && srcCursor < adapted.totalFrames) {
                val toRead = min(srcChunkFrames.toLong(), adapted.totalFrames - srcCursor).toInt()
                val read = adapted.readFrames(srcCursor, srcShort, 0, toRead)
                if (read <= 0) break
                srcCursor += read
                if (adapted.sampleRate == opusRate) {
                    pcm48Queue.pushShorts(srcShort, read * outChannels)
                } else {
                    val fIn = FloatArray(read * outChannels) { i -> srcShort[i] / 32768f }
                    val resampled = HermiteResampler.resampleInterleaved(fIn, read, outChannels, adapted.sampleRate, opusRate)
                    val sOut = ShortArray(resampled.size) { i -> SoftClipLimiter.floatToPcm16(resampled[i]) }
                    pcm48Queue.pushShorts(sOut, sOut.size)
                }
            }
        }

        var sentFrames48k = 0L
        var sawInputEos = false
        var sawOutputEos = false
        var lastPtsUs = -1L

        try {
            while (!sawOutputEos) {
                coroutineContext.ensureActive()

                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        refill48kQueue()
                        val availableFrames = pcm48Queue.sizeFrames(outChannels)
                        if (availableFrames == 0 && srcCursor >= adapted.totalFrames) {
                            codec.queueInputBuffer(inIdx, 0, 0, maxOf(0L, lastPtsUs + 20_000L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val framesToFeed = min(frameSize20ms, availableFrames)
                            val chunk = ShortArray(frameSize20ms * outChannels)
                            pcm48Queue.popShorts(chunk, framesToFeed * outChannels)
                            // Pad final partial frame with silence up to 20ms for clean Opus packet framing
                            val feedBytes = ByteArray(chunk.size * 2)
                            DiskPcmStore.encodeSamplesToBytesLe(chunk, chunk.size, feedBytes)

                            val inBuf = codec.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            inBuf.put(feedBytes)

                            var ptsUs = (sentFrames48k * 1_000_000L) / opusRate
                            if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 20_000L
                            lastPtsUs = ptsUs
                            sentFrames48k += framesToFeed

                            val isLast = (srcCursor >= adapted.totalFrames && pcm48Queue.sizeFrames(outChannels) == 0)
                            val flags = if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                            if (isLast) sawInputEos = true
                            codec.queueInputBuffer(inIdx, 0, feedBytes.size, ptsUs, flags)
                            onProgress(((sentFrames48k.toFloat() / totalOutFrames48k.coerceAtLeast(1L)) * 0.85f).coerceIn(0f, 0.85f))
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        val csd0 = outFmt.getByteBuffer("csd-0")
                        if (csd0 != null && csd0.remaining() >= 19) {
                            val hdr = ByteArray(csd0.remaining())
                            csd0.duplicate().get(hdr)
                            if (String(hdr, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                                preSkip = (hdr[10].toInt() and 0xFF) or ((hdr[11].toInt() and 0xFF) shl 8)
                            }
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val pkt = ByteArray(info.size)
                            outBuf.get(pkt)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                if (pkt.size >= 19 && String(pkt, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                                    preSkip = (pkt[10].toInt() and 0xFF) or ((pkt[11].toInt() and 0xFF) shl 8)
                                }
                            } else {
                                encodedPackets += pkt
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        val bytesWritten = OpusContainer.writeOggOpus(
            outputStream = outputStream,
            channels = outChannels,
            inputSampleRate = adapted.sampleRate,
            preSkip = preSkip,
            metadata = config.metadata,
            audioPackets = encodedPackets,
            totalOutputFrames48k = totalOutFrames48k,
        )
        onProgress(1f)
        return ExportResult(
            bytesWritten = bytesWritten,
            durationMs = (totalOutFrames48k * 1000L) / opusRate,
            wasLossless = false,
        )
    }

    private suspend fun encodeFlac(
        reader: PcmTimelineReader,
        config: ExportConfig,
        outputStream: OutputStream,
        tempDir: File,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        val adapted = adaptReader(reader, config.channelMode, config.sampleRateHz)
        val outSr = adapted.sampleRate
        val ch = adapted.channels.coerceIn(1, 2)

        val codec = createSoftwarePreferredEncoder("audio/flac", "c2.android.flac.encoder")
        val format = MediaFormat.createAudioFormat("audio/flac", outSr, ch).apply {
            setInteger(MediaFormat.KEY_FLAC_COMPRESSION_LEVEL, 5)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192 * ch * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val rawFlacFile = File(tempDir, "enc_${System.currentTimeMillis()}.flac")
        val info = MediaCodec.BufferInfo()
        val chunkFrames = 4096
        val srcBuf = ShortArray(chunkFrames * ch)
        var srcCursor = 0L
        var sentFrames = 0L
        var lastPtsUs = -1L
        var sawInputEos = false
        var sawOutputEos = false

        try {
            rawFlacFile.outputStream().buffered().use { flacOut ->
                while (!sawOutputEos) {
                    coroutineContext.ensureActive()

                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
                            if (srcCursor >= adapted.totalFrames) {
                                codec.queueInputBuffer(inIdx, 0, 0, maxOf(0L, lastPtsUs + 20_000L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                sawInputEos = true
                            } else {
                                val toRead = min(chunkFrames.toLong(), adapted.totalFrames - srcCursor).toInt()
                                val read = adapted.readFrames(srcCursor, srcBuf, 0, toRead)
                                srcCursor += read
                                val bytes = encodeChunkWithResample(srcBuf, read, ch, adapted.sampleRate, outSr)
                                val outFramesThisChunk = bytes.size / (ch * 2)
                                val inBuf = codec.getInputBuffer(inIdx)!!
                                inBuf.clear()
                                inBuf.put(bytes)
                                var ptsUs = (sentFrames * 1_000_000L) / outSr
                                if (ptsUs <= lastPtsUs) ptsUs = lastPtsUs + 1000L
                                lastPtsUs = ptsUs
                                sentFrames += outFramesThisChunk

                                val isLast = srcCursor >= adapted.totalFrames
                                if (isLast) sawInputEos = true
                                codec.queueInputBuffer(inIdx, 0, bytes.size, ptsUs, if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                                onProgress(((srcCursor.toFloat() / adapted.totalFrames.coerceAtLeast(1L)) * 0.85f).coerceIn(0f, 0.85f))
                            }
                        }
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    if (outIdx >= 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            val data = ByteArray(info.size)
                            outBuf.get(data)
                            flacOut.write(data)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }

            val counting = CountingStream(outputStream)
            val tagged = if (!config.metadata.isEmpty) {
                FlacMetadataCodec.rewriteMetadata(rawFlacFile, config.metadata, counting)
            } else {
                false
            }
            if (!tagged) {
                FileInputStream(rawFlacFile).use { it.copyTo(counting, 32768) }
            }
            counting.flush()
            onProgress(1f)
            val durMs = if (adapted.sampleRate > 0) (adapted.totalFrames * 1000L) / adapted.sampleRate else 0L
            return ExportResult(counting.bytesWritten, durMs, wasLossless = false)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            rawFlacFile.delete()
        }
    }

    private suspend fun encodeM4aAac(
        reader: PcmTimelineReader,
        config: ExportConfig,
        outputStream: OutputStream,
        tempDir: File,
        onProgress: (Float) -> Unit,
    ): ExportResult {
        val reqSr = if (config.sampleRateHz > 0) config.sampleRateHz.coerceIn(8000, 96000) else 0
        val adapted = adaptReader(reader, config.channelMode, reqSr)
        val outSr = adapted.sampleRate.coerceIn(8000, 96000)
        val ch = adapted.channels.coerceIn(1, 2)
        val bitrate = config.bitrateKbps.coerceIn(64, 320) * 1000

        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outSr, ch).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192 * ch * 2)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val tempM4a = File(tempDir, "enc_${System.currentTimeMillis()}.m4a")
        val muxer = MediaMuxer(tempM4a.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var trackIndex = -1
        var muxerStarted = false

        val info = MediaCodec.BufferInfo()
        val chunkFrames = 2048
        val srcBuf = ShortArray(chunkFrames * ch)
        var srcCursor = 0L
        var sentFrames = 0L
        var lastInPtsUs = -1L
        var lastOutPtsUs = -1L
        var sawInputEos = false
        var sawOutputEos = false

        try {
            while (!sawOutputEos) {
                coroutineContext.ensureActive()

                if (!sawInputEos) {
                    val inIdx = codec.dequeueInputBuffer(10_000L)
                    if (inIdx >= 0) {
                        if (srcCursor >= adapted.totalFrames) {
                            codec.queueInputBuffer(inIdx, 0, 0, maxOf(0L, lastInPtsUs + 20_000L), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val toRead = min(chunkFrames.toLong(), adapted.totalFrames - srcCursor).toInt()
                            val read = adapted.readFrames(srcCursor, srcBuf, 0, toRead)
                            srcCursor += read
                            val bytes = encodeChunkWithResample(srcBuf, read, ch, adapted.sampleRate, outSr)
                            val outFramesThisChunk = bytes.size / (ch * 2)
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            inBuf.put(bytes)
                            var ptsUs = (sentFrames * 1_000_000L) / outSr
                            if (ptsUs <= lastInPtsUs) ptsUs = lastInPtsUs + 1000L
                            lastInPtsUs = ptsUs
                            sentFrames += outFramesThisChunk

                            val isLast = srcCursor >= adapted.totalFrames
                            if (isLast) sawInputEos = true
                            codec.queueInputBuffer(inIdx, 0, bytes.size, ptsUs, if (isLast) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            onProgress(((srcCursor.toFloat() / adapted.totalFrames.coerceAtLeast(1L)) * 0.90f).coerceIn(0f, 0.90f))
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (!muxerStarted) {
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                    }
                    outIdx >= 0 -> {
                        val outBuf = codec.getOutputBuffer(outIdx)
                        if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && muxerStarted) {
                            outBuf.position(info.offset)
                            outBuf.limit(info.offset + info.size)
                            if (info.presentationTimeUs <= lastOutPtsUs) {
                                info.presentationTimeUs = lastOutPtsUs + 1000L
                            }
                            lastOutPtsUs = info.presentationTimeUs
                            muxer.writeSampleData(trackIndex, outBuf, info)
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            sawOutputEos = true
                        }
                    }
                }
            }
            if (muxerStarted) {
                muxer.stop()
            }
            runCatching { muxer.release() }
            val counting = CountingStream(outputStream)
            val wroteTagged = if (!config.metadata.isEmpty) {
                com.libreopus.dsp.container.Mp4MetadataWriter.writeMetadata(tempM4a, config.metadata, counting)
            } else {
                false
            }
            if (!wroteTagged) {
                FileInputStream(tempM4a).use { it.copyTo(counting, 262144) }
            }
            counting.flush()
            onProgress(1f)
            val durMs = if (adapted.sampleRate > 0) (adapted.totalFrames * 1000L) / adapted.sampleRate else 0L
            return ExportResult(counting.bytesWritten, durMs, wasLossless = false)
        } finally {
            runCatching { muxer.release() }
            runCatching { codec.stop() }
            runCatching { codec.release() }
            tempM4a.delete()
        }
    }

    private fun encodeChunkWithResample(
        srcBuf: ShortArray,
        frames: Int,
        channels: Int,
        srcRate: Int,
        outRate: Int,
    ): ByteArray {
        return if (srcRate == outRate) {
            ByteArray(frames * channels * 2).also {
                DiskPcmStore.encodeSamplesToBytesLe(srcBuf, frames * channels, it)
            }
        } else {
            val fIn = FloatArray(frames * channels) { i -> srcBuf[i] / 32768f }
            val resampled = HermiteResampler.resampleInterleaved(fIn, frames, channels, srcRate, outRate)
            val sOut = ShortArray(resampled.size) { i -> SoftClipLimiter.floatToPcm16(resampled[i]) }
            ByteArray(sOut.size * 2).also {
                DiskPcmStore.encodeSamplesToBytesLe(sOut, sOut.size, it)
            }
        }
    }

    private fun createSoftwarePreferredEncoder(mime: String, preferredName: String): MediaCodec {
        val list = MediaCodecList(MediaCodecList.ALL_CODECS)
        val exact = list.codecInfos.firstOrNull { it.isEncoder && it.name.equals(preferredName, ignoreCase = true) }
        if (exact != null) return MediaCodec.createByCodecName(exact.name)

        val sw = list.codecInfos.firstOrNull { info ->
            info.isEncoder && info.isSoftwareOnly && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (sw != null) return MediaCodec.createByCodecName(sw.name)
        return MediaCodec.createEncoderByType(mime)
    }

    private fun adaptReader(
        reader: PcmTimelineReader,
        mode: ChannelMode,
        targetSampleRateHz: Int = 0,
    ): PcmTimelineReader {
        val targetCh = when (mode) {
            ChannelMode.KEEP -> reader.channels
            ChannelMode.MONO -> 1
            ChannelMode.STEREO -> 2
        }
        val chAdapted = if (targetCh == reader.channels) reader else ChannelAdaptingReader(reader, targetCh)
        return if (targetSampleRateHz > 0 && targetSampleRateHz != chAdapted.sampleRate && chAdapted.sampleRate > 0) {
            ResamplingTimelineReader(chAdapted, targetSampleRateHz)
        } else {
            chAdapted
        }
    }
}

private class ChannelAdaptingReader(
    private val delegate: PcmTimelineReader,
    override val channels: Int,
) : PcmTimelineReader {
    override val sampleRate: Int get() = delegate.sampleRate
    override val totalFrames: Long get() = delegate.totalFrames

    override fun readFrames(startFrame: Long, destInterleaved16: ShortArray, frameOffset: Int, frameCount: Int): Int {
        val srcCh = delegate.channels
        val tmp = ShortArray(frameCount * srcCh)
        val read = delegate.readFrames(startFrame, tmp, 0, frameCount)
        val destStart = frameOffset * channels
        for (f in 0 until read) {
            val sb = f * srcCh
            val db = destStart + f * channels
            if (channels == 1) {
                var sum = 0
                for (c in 0 until srcCh) sum += tmp[sb + c].toInt()
                destInterleaved16[db] = (sum / srcCh).toShort()
            } else {
                for (c in 0 until channels) {
                    destInterleaved16[db + c] = tmp[sb + min(c, srcCh - 1)]
                }
            }
        }
        return read
    }
}

private class ShortQueue(initialCapacity: Int) {
    private var buf = ShortArray(initialCapacity)
    private var size = 0

    fun sizeFrames(channels: Int): Int = size / channels

    fun pushShorts(src: ShortArray, count: Int) {
        if (size + count > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, size + count))
        }
        System.arraycopy(src, 0, buf, size, count)
        size += count
    }

    fun popShorts(dest: ShortArray, count: Int) {
        val toCopy = min(count, size)
        if (toCopy > 0) {
            System.arraycopy(buf, 0, dest, 0, toCopy)
            val rem = size - toCopy
            if (rem > 0) {
                System.arraycopy(buf, toCopy, buf, 0, rem)
            }
            size = rem
        }
    }
}

private class CountingStream(private val delegate: OutputStream) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        bytesWritten += len
    }

    override fun flush() = delegate.flush()
}
