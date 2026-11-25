// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.engine.lite

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import com.libreopus.audio.api.AudioContainerFormat
import com.libreopus.audio.api.AudioInfo
import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.AudioSource
import com.libreopus.audio.api.DecodedAudio
import com.libreopus.audio.api.WaveformPyramid
import com.libreopus.dsp.container.FlacMetadataCodec
import com.libreopus.dsp.container.LosslessOpusEditor
import com.libreopus.dsp.container.Mp3TagReader
import com.libreopus.dsp.container.Mp4MetadataWriter
import com.libreopus.dsp.container.OpusContainer
import com.libreopus.dsp.container.WavCodec
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.waveform.WaveformPeakGenerator
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal object MediaCodecDecoder {

    private class ProgressThrottler(
        private val minDelta: Float = 0.015f,
        private val minIntervalMs: Long = 50L,
        private val sink: (Float) -> Unit,
    ) {
        private var lastReported = -1f
        private var lastTimeMs = 0L

        fun emit(progress: Float, force: Boolean = false) {
            val clamped = progress.coerceIn(0f, 1f)
            val now = System.currentTimeMillis()
            if (force || lastReported < 0f || (clamped - lastReported) >= minDelta || (now - lastTimeMs) >= minIntervalMs) {
                lastReported = clamped
                lastTimeMs = now
                sink(clamped)
            }
        }
    }

    suspend fun decode(
        source: AudioSource,
        sessionDir: File,
        generateWaveform: Boolean = true,
        onProgress: (Float) -> Unit,
    ): DecodedAudio {
        if (!sessionDir.exists()) sessionDir.mkdirs()

        val throttler = ProgressThrottler(sink = onProgress)
        val ext = source.displayName.substringAfterLast('.', "").lowercase()
        val guessedFormat = AudioContainerFormat.fromExtensionOrMime(ext, source.mimeType)

        val directLocal = source.localFile?.takeIf { it.exists() && it.isFile && it.canRead() }
        val inputFile: File
        val copiedToSession: File?
        if (directLocal != null) {
            inputFile = directLocal
            copiedToSession = null
        } else {
            val tempCopy = File(sessionDir, "source_${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}")
            source.openInputStream().use { input ->
                BufferedOutputStream(FileOutputStream(tempCopy), 262144).use { out ->
                    input.copyTo(out, 262144)
                }
            }
            inputFile = tempCopy
            copiedToSession = tempCopy
            throttler.emit(0.08f)
        }

        val actualFormat = detectActualFormat(inputFile, guessedFormat)
        val store = DiskPcmStore(sessionDir)
        val pcmFile = store.createChunkFile("decoded")

        var sampleRate = 48000
        var channels = 2
        var bitrateKbps = 0
        var codecName = actualFormat.displayName
        var totalFrames = 0L
        var wavMetadata: AudioMetadata? = null
        val streamingWaveform: WaveformPyramid

        val decodeBase = if (copiedToSession != null) 0.08f else 0.02f
        val decodeSpan = 0.94f - decodeBase

        if (actualFormat == AudioContainerFormat.WAV) {
            var builder: WaveformPeakGenerator.StreamingBuilder? = null
            val expectedBytes = inputFile.length().coerceAtLeast(1L)
            val (hdr, wMeta) = FileInputStream(inputFile).buffered(262144).use { fis ->
                WavCodec.decodeStreamToPcm16File(
                    input = fis,
                    outPcmFile = pcmFile,
                    onPcmChunk = { sr, ch, pcmBytes, byteLen ->
                        if (generateWaveform) {
                            val b = builder ?: WaveformPeakGenerator.StreamingBuilder(
                                sampleRate = sr,
                                channels = ch,
                            ).also { builder = it }
                            b.pushPcm16LeBytes(pcmBytes, byteLen)
                        }
                    },
                    onProgress = { samplesWritten ->
                        val frac = ((samplesWritten * 2f) / expectedBytes.toFloat()).coerceIn(0f, 0.98f)
                        throttler.emit(decodeBase + frac * decodeSpan)
                    },
                )
            }
            sampleRate = hdr.sampleRate
            channels = hdr.channels
            totalFrames = pcmFile.length() / (channels * 2L)
            bitrateKbps = ((hdr.sampleRate.toLong() * hdr.channels * hdr.bitsPerSample) / 1000L).toInt()
            codecName = if (hdr.isFloat) "PCM ${hdr.bitsPerSample}-bit Float" else "PCM ${hdr.bitsPerSample}-bit"
            wavMetadata = wMeta
            streamingWaveform = if (generateWaveform) {
                builder?.build() ?: WaveformPeakGenerator.emptyPyramid(sampleRate)
            } else {
                WaveformPeakGenerator.emptyPyramid(sampleRate)
            }
        } else {
            val decoded = decodeWithMediaExtractor(
                file = inputFile,
                outPcmFile = pcmFile,
                generateWaveform = generateWaveform,
            ) { p ->
                throttler.emit(decodeBase + p * decodeSpan)
            }
            sampleRate = decoded.sampleRate
            channels = decoded.channels
            bitrateKbps = decoded.bitrateKbps
            codecName = decoded.mime
            totalFrames = decoded.totalFrames
            streamingWaveform = decoded.waveform
        }

        val supportsLosslessOpus = actualFormat.isOggOpus && LosslessOpusEditor.isValidOggOpus(inputFile)
        val keepCachedOriginal = supportsLosslessOpus || actualFormat == AudioContainerFormat.FLAC
        val metadata = wavMetadata ?: readMetadataFromFile(inputFile, actualFormat)

        if (!keepCachedOriginal && copiedToSession != null) {
            runCatching { copiedToSession.delete() }
        }

        val cachedOriginalForSession = if (keepCachedOriginal) {
            if (copiedToSession != null) {
                copiedToSession
            } else {
                val safeCopy = File(sessionDir, "source_${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}")
                runCatching {
                    inputFile.copyTo(safeCopy, overwrite = true)
                    safeCopy
                }.getOrDefault(inputFile)
            }
        } else {
            null
        }

        val durationMs = if (sampleRate > 0) (totalFrames * 1000L) / sampleRate else 0L
        if (bitrateKbps <= 0 && durationMs > 0L) {
            bitrateKbps = ((inputFile.length() * 8L) / durationMs).toInt()
        }

        val info = AudioInfo(
            displayName = source.displayName,
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            bitrateKbps = bitrateKbps,
            codecName = codecName,
            containerFormat = actualFormat,
            fileSizeBytes = inputFile.length(),
            supportsLosslessOpus = supportsLosslessOpus,
        )

        throttler.emit(1f, force = true)
        return DecodedAudio(
            info = info,
            metadata = metadata,
            pcmFile = pcmFile,
            totalFrames = totalFrames,
            waveform = streamingWaveform,
            cachedOriginalFile = cachedOriginalForSession,
        )
    }

    fun readMetadataFromFile(file: File, format: AudioContainerFormat): AudioMetadata {
        val primary: AudioMetadata? = when {
            format.isOggOpus -> FileInputStream(file).buffered().use { OpusContainer.readMetadata(it) }
            format == AudioContainerFormat.FLAC -> FileInputStream(file).buffered().use { FlacMetadataCodec.readMetadata(it) }
            format == AudioContainerFormat.MP3 -> FileInputStream(file).buffered().use { Mp3TagReader.readMetadata(it) }
            format == AudioContainerFormat.M4A || format == AudioContainerFormat.VIDEO_AUDIO ->
                Mp4MetadataWriter.readMetadata(file)
            else -> null
        }

        if (primary != null && !primary.isEmpty && primary.coverArtBytes != null) {
            return primary
        }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val art = primary?.coverArtBytes ?: mmr.embeddedPicture
            val mime = primary?.coverArtMimeType ?: if (art != null) "image/jpeg" else null
            AudioMetadata(
                title = primary?.title?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = primary?.artist?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = primary?.album?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                albumArtist = primary?.albumArtist?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty(),
                year = primary?.year?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty(),
                trackNumber = primary?.trackNumber?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty(),
                genre = primary?.genre?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                composer = primary?.composer?.ifBlank { null } ?: mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER).orEmpty(),
                comment = primary?.comment.orEmpty(),
                coverArtBytes = art,
                coverArtMimeType = mime,
                extraTags = primary?.extraTags.orEmpty(),
            )
        } catch (_: Exception) {
            primary ?: AudioMetadata()
        } finally {
            runCatching { mmr.release() }
        }
    }

    private fun detectActualFormat(file: File, fallback: AudioContainerFormat): AudioContainerFormat {
        val header = ByteArray(12)
        val read = FileInputStream(file).use { it.read(header) }
        if (read >= 4) {
            val sig4 = String(header, 0, 4, Charsets.US_ASCII)
            if (sig4 == "OggS") return if (fallback == AudioContainerFormat.OGG_OPUS) AudioContainerFormat.OGG_OPUS else AudioContainerFormat.OPUS
            if (sig4 == "fLaC") return AudioContainerFormat.FLAC
            if (sig4 == "RIFF" && read >= 12 && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") return AudioContainerFormat.WAV
            if (sig4.startsWith("ID3")) return AudioContainerFormat.MP3
            if (read >= 8 && String(header, 4, 4, Charsets.US_ASCII) == "ftyp") {
                return if (fallback == AudioContainerFormat.VIDEO_AUDIO) AudioContainerFormat.VIDEO_AUDIO else AudioContainerFormat.M4A
            }
        }
        return fallback
    }

    private data class ExtractorDecodeResult(
        val sampleRate: Int,
        val channels: Int,
        val bitrateKbps: Int,
        val mime: String,
        val totalFrames: Long,
        val waveform: WaveformPyramid,
    )

    private suspend fun decodeWithMediaExtractor(
        file: File,
        outPcmFile: File,
        generateWaveform: Boolean,
        onProgress: (Float) -> Unit,
    ): ExtractorDecodeResult {
        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        var trackIdx = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            val mime = f.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                trackIdx = i
                format = f
                break
            }
        }
        if (trackIdx < 0 || format == null) {
            extractor.release()
            throw IllegalStateException("No audio track found in selected file")
        }

        extractor.selectTrack(trackIdx)
        val mime = format.getString(MediaFormat.KEY_MIME) ?: "audio/raw"
        var sampleRate = format.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48000)
        var channels = format.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 2)
        val bitrateKbps = format.getIntegerOrDefault(MediaFormat.KEY_BIT_RATE, 0) / 1000
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val estimatedTotalFrames = if (durationUs > 0L && sampleRate > 0) {
            (durationUs * sampleRate) / 1_000_000L
        } else {
            0L
        }

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        var totalPcmBytes = 0L
        var scratch = ByteArray(32768)
        var waveformBuilder: WaveformPeakGenerator.StreamingBuilder? = null

        try {
            BufferedOutputStream(FileOutputStream(outPcmFile), 262144).use { out ->
                while (!sawOutputEos) {
                    coroutineContext.ensureActive()
                    var didWork = false

                    // Feed all currently available input buffers without sleeping
                    while (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(0L)
                        if (inIdx < 0) break
                        didWork = true
                        val inBuf = codec.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEos = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                            if (durationUs > 0L) {
                                onProgress((pts.toFloat() / durationUs).coerceIn(0f, 0.98f))
                            }
                        }
                    }

                    // Drain all ready output buffers (wait briefly only if loop made zero progress)
                    var outTimeoutUs = if (didWork) 0L else 2_000L
                    while (!sawOutputEos) {
                        val outIdx = codec.dequeueOutputBuffer(info, outTimeoutUs)
                        outTimeoutUs = 0L
                        when {
                            outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                val outFmt = codec.outputFormat
                                val newRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                                val newCh = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                                if (totalPcmBytes == 0L && (newRate != sampleRate || newCh != channels)) {
                                    waveformBuilder = null
                                }
                                sampleRate = newRate
                                channels = newCh
                                didWork = true
                            }
                            outIdx >= 0 -> {
                                didWork = true
                                val outBuf = codec.getOutputBuffer(outIdx)
                                val size = info.size
                                if (outBuf != null && size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                    outBuf.position(info.offset)
                                    outBuf.limit(info.offset + size)
                                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                    if (size > scratch.size) {
                                        scratch = ByteArray(size)
                                    }
                                    outBuf.get(scratch, 0, size)
                                    out.write(scratch, 0, size)
                                    totalPcmBytes += size
                                    if (generateWaveform) {
                                        val wb = waveformBuilder ?: WaveformPeakGenerator.StreamingBuilder(
                                            sampleRate = sampleRate.coerceAtLeast(8000),
                                            channels = channels.coerceIn(1, 8),
                                            expectedFrames = estimatedTotalFrames,
                                        ).also { waveformBuilder = it }
                                        wb.pushPcm16LeBytes(scratch, size)
                                    }
                                }
                                codec.releaseOutputBuffer(outIdx, false)
                                if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                    sawOutputEos = true
                                }
                            }
                            else -> break
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        val finalRate = sampleRate.coerceAtLeast(8000)
        val finalCh = channels.coerceIn(1, 8)
        val finalFrames = totalPcmBytes / (finalCh * 2L)
        val waveform = if (generateWaveform) {
            waveformBuilder?.build() ?: WaveformPeakGenerator.emptyPyramid(finalRate)
        } else {
            WaveformPeakGenerator.emptyPyramid(finalRate)
        }

        return ExtractorDecodeResult(
            sampleRate = finalRate,
            channels = finalCh,
            bitrateKbps = bitrateKbps,
            mime = mime,
            totalFrames = finalFrames,
            waveform = waveform,
        )
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default
}
