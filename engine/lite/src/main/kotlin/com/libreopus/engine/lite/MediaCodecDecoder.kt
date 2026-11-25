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
import com.libreopus.dsp.container.FlacMetadataCodec
import com.libreopus.dsp.container.LosslessOpusEditor
import com.libreopus.dsp.container.Mp3TagReader
import com.libreopus.dsp.container.OpusContainer
import com.libreopus.dsp.container.WavCodec
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import com.libreopus.dsp.waveform.WaveformPeakGenerator
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal object MediaCodecDecoder {

    suspend fun decode(
        source: AudioSource,
        sessionDir: File,
        onProgress: (Float) -> Unit,
    ): DecodedAudio {
        if (!sessionDir.exists()) sessionDir.mkdirs()

        val ext = source.displayName.substringAfterLast('.', "").lowercase()
        val guessedFormat = AudioContainerFormat.fromExtensionOrMime(ext, source.mimeType)
        val cachedOrig = File(sessionDir, "source_${System.currentTimeMillis()}.${ext.ifBlank { "bin" }}")

        source.openInputStream().use { input ->
            BufferedOutputStream(FileOutputStream(cachedOrig)).use { out ->
                input.copyTo(out, 32768)
            }
        }
        onProgress(0.12f)

        val actualFormat = detectActualFormat(cachedOrig, guessedFormat)
        val store = DiskPcmStore(sessionDir)
        val pcmFile = store.createChunkFile("decoded")

        var sampleRate = 48000
        var channels = 2
        var bitrateKbps = 0
        var codecName = actualFormat.displayName
        var totalFrames = 0L
        var wavMetadata: AudioMetadata? = null

        if (actualFormat == AudioContainerFormat.WAV) {
            val (hdr, wMeta) = FileInputStream(cachedOrig).buffered().use { fis ->
                WavCodec.decodeStreamToPcm16File(fis, pcmFile) { samplesWritten ->
                    onProgress((0.15f + (samplesWritten.toFloat() / 2_000_000f) * 0.55f).coerceIn(0.15f, 0.70f))
                }
            }
            sampleRate = hdr.sampleRate
            channels = hdr.channels
            totalFrames = pcmFile.length() / (channels * 2L)
            bitrateKbps = ((hdr.sampleRate.toLong() * hdr.channels * hdr.bitsPerSample) / 1000L).toInt()
            codecName = if (hdr.isFloat) "PCM ${hdr.bitsPerSample}-bit Float" else "PCM ${hdr.bitsPerSample}-bit"
            wavMetadata = wMeta
        } else {
            val decoded = decodeWithMediaExtractor(cachedOrig, pcmFile) { p ->
                onProgress(0.12f + p * 0.63f)
            }
            sampleRate = decoded.sampleRate
            channels = decoded.channels
            bitrateKbps = decoded.bitrateKbps
            codecName = decoded.mime
            totalFrames = pcmFile.length() / (channels * 2L)
        }

        val supportsLosslessOpus = actualFormat.isOggOpus && LosslessOpusEditor.isValidOggOpus(cachedOrig)
        val metadata = wavMetadata ?: readMetadataFromFile(cachedOrig, actualFormat)
        val timeline = EditTimeline.fromSingleFile(sampleRate, channels, pcmFile, totalFrames)
        val waveform = WaveformPeakGenerator.generate(timeline) { p ->
            onProgress(0.75f + p * 0.25f)
        }

        val durationMs = if (sampleRate > 0) (totalFrames * 1000L) / sampleRate else 0L
        if (bitrateKbps <= 0 && durationMs > 0L) {
            bitrateKbps = ((cachedOrig.length() * 8L) / durationMs).toInt()
        }

        val info = AudioInfo(
            displayName = source.displayName,
            sampleRate = sampleRate,
            channels = channels,
            durationMs = durationMs,
            bitrateKbps = bitrateKbps,
            codecName = codecName,
            containerFormat = actualFormat,
            fileSizeBytes = cachedOrig.length(),
            supportsLosslessOpus = supportsLosslessOpus,
        )

        onProgress(1f)
        return DecodedAudio(
            info = info,
            metadata = metadata,
            pcmFile = pcmFile,
            totalFrames = totalFrames,
            waveform = waveform,
            cachedOriginalFile = if (supportsLosslessOpus || actualFormat == AudioContainerFormat.FLAC) cachedOrig else null,
        )
    }

    fun readMetadataFromFile(file: File, format: AudioContainerFormat): AudioMetadata {
        val primary: AudioMetadata? = when {
            format.isOggOpus -> FileInputStream(file).buffered().use { OpusContainer.readMetadata(it) }
            format == AudioContainerFormat.FLAC -> FileInputStream(file).buffered().use { FlacMetadataCodec.readMetadata(it) }
            format == AudioContainerFormat.MP3 -> FileInputStream(file).buffered().use { Mp3TagReader.readMetadata(it) }
            else -> null
        }

        if (primary != null && !primary.isEmpty) {
            return primary
        }

        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(file.absolutePath)
            val art = mmr.embeddedPicture
            AudioMetadata(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty(),
                year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty(),
                trackNumber = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty(),
                genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                composer = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER).orEmpty(),
                coverArtBytes = art,
                coverArtMimeType = if (art != null) "image/jpeg" else null,
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
        }
        return fallback
    }

    private data class ExtractorDecodeResult(
        val sampleRate: Int,
        val channels: Int,
        val bitrateKbps: Int,
        val mime: String,
    )

    private suspend fun decodeWithMediaExtractor(
        file: File,
        outPcmFile: File,
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

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false

        try {
            BufferedOutputStream(FileOutputStream(outPcmFile)).use { out ->
                while (!sawOutputEos) {
                    coroutineContext.ensureActive()

                    if (!sawInputEos) {
                        val inIdx = codec.dequeueInputBuffer(10_000L)
                        if (inIdx >= 0) {
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
                    }

                    val outIdx = codec.dequeueOutputBuffer(info, 10_000L)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFmt = codec.outputFormat
                            sampleRate = outFmt.getIntegerOrDefault(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                            channels = outFmt.getIntegerOrDefault(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        }
                        outIdx >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                val bytes = ByteArray(info.size)
                                outBuf.get(bytes)
                                out.write(bytes)
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                            if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                sawOutputEos = true
                            }
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
        }

        return ExtractorDecodeResult(
            sampleRate = sampleRate.coerceAtLeast(8000),
            channels = channels.coerceIn(1, 8),
            bitrateKbps = bitrateKbps,
            mime = mime,
        )
    }

    private fun MediaFormat.getIntegerOrDefault(key: String, default: Int): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(default) else default
}
