// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.engine.lite

import com.libreopus.audio.api.AudioContainerFormat
import com.libreopus.audio.api.AudioEngine
import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.AudioSource
import com.libreopus.audio.api.DecodedAudio
import com.libreopus.audio.api.EngineCapabilities
import com.libreopus.audio.api.ExportConfig
import com.libreopus.audio.api.ExportFormat
import com.libreopus.audio.api.ExportResult
import com.libreopus.audio.api.LosslessOpusTrimPlan
import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.dsp.container.FlacMetadataCodec
import com.libreopus.dsp.container.LosslessOpusEditor
import com.libreopus.dsp.container.Mp3TagReader
import com.libreopus.dsp.container.Mp4MetadataWriter
import com.libreopus.dsp.container.OpusContainer
import java.io.File
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LiteAudioEngine(
    private val cacheRootDir: File,
) : AudioEngine {

    override val capabilities: EngineCapabilities = EngineCapabilities(
        engineId = "lite",
        displayName = "LibreOpus Lite (MediaCodec + Native Kotlin Opus)",
        supportedImportExtensions = setOf(
            "opus", "ogg", "oga", "mp3", "m4a", "aac", "mp4a", "flac", "wav", "amr", "awb", "webm", "mka", "mp4", "mkv", "mov",
        ),
        supportedExportFormats = setOf(
            ExportFormat.OPUS,
            ExportFormat.OGG_OPUS,
            ExportFormat.FLAC,
            ExportFormat.M4A,
            ExportFormat.WAV,
        ),
        supportsLosslessOpus = true,
    )

    override suspend fun decodeToPcm(
        source: AudioSource,
        sessionDir: File,
        generateWaveform: Boolean,
        onProgress: (Float) -> Unit,
    ): DecodedAudio = withContext(Dispatchers.IO) {
        MediaCodecDecoder.decode(source, sessionDir, generateWaveform, onProgress)
    }

    override suspend fun exportTimeline(
        reader: PcmTimelineReader,
        config: ExportConfig,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        val tempDir = File(cacheRootDir, "export_tmp").apply { mkdirs() }
        MediaCodecEncoders.export(reader, config, outputStream, tempDir, onProgress)
    }

    override suspend fun exportLosslessOpus(
        plan: LosslessOpusTrimPlan,
        metadata: AudioMetadata,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.IO) {
        LosslessOpusEditor.trimLossless(
            sourceFile = plan.sourceOggFile,
            startMs = plan.startMs,
            endMs = plan.endMs,
            metadataOverride = metadata,
            outputStream = outputStream,
            onProgress = onProgress,
        )
    }

    override suspend fun readMetadata(source: AudioSource): AudioMetadata = withContext(Dispatchers.IO) {
        val ext = source.displayName.substringAfterLast('.', "").lowercase()
        val format = AudioContainerFormat.fromExtensionOrMime(ext, source.mimeType)
        when {
            format.isOggOpus -> source.openInputStream().use { OpusContainer.readMetadata(it) } ?: AudioMetadata()
            format == AudioContainerFormat.FLAC -> source.openInputStream().use { FlacMetadataCodec.readMetadata(it) } ?: AudioMetadata()
            format == AudioContainerFormat.MP3 -> source.openInputStream().use { Mp3TagReader.readMetadata(it) } ?: AudioMetadata()
            (format == AudioContainerFormat.M4A || format == AudioContainerFormat.VIDEO_AUDIO) && source.localFile != null ->
                Mp4MetadataWriter.readMetadata(source.localFile!!) ?: AudioMetadata()
            else -> AudioMetadata()
        }
    }

    override suspend fun rewriteMetadataLossless(
        sourceFile: File,
        format: AudioContainerFormat,
        metadata: AudioMetadata,
        outputStream: OutputStream,
    ): Boolean = withContext(Dispatchers.IO) {
        when {
            format.isOggOpus -> LosslessOpusEditor.rewriteTagsLossless(sourceFile, metadata, outputStream)
            format == AudioContainerFormat.FLAC -> FlacMetadataCodec.rewriteMetadata(sourceFile, metadata, outputStream)
            format == AudioContainerFormat.M4A || format == AudioContainerFormat.VIDEO_AUDIO ->
                Mp4MetadataWriter.writeMetadata(sourceFile, metadata, outputStream)
            else -> false
        }
    }
}
