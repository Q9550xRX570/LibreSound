// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.audio.api

import java.io.File
import java.io.InputStream
import java.io.OutputStream

interface AudioSource {
    val displayName: String
    val fileSizeBytes: Long
    val mimeType: String?
    val localFile: File? get() = null
    fun openInputStream(): InputStream
}

data class DecodedAudio(
    val info: AudioInfo,
    val metadata: AudioMetadata,
    val pcmFile: File,
    val totalFrames: Long,
    val waveform: WaveformPyramid,
    val cachedOriginalFile: File?,
)

/**
 * Streaming reader over an edited timeline of 16-bit signed interleaved PCM frames.
 */
interface PcmTimelineReader {
    val sampleRate: Int
    val channels: Int
    val totalFrames: Long

    /**
     * Reads up to [frameCount] frames starting at [startFrame] into [destInterleaved16]
     * at sample index `frameOffset * channels`. Returns actual frames read.
     */
    fun readFrames(
        startFrame: Long,
        destInterleaved16: ShortArray,
        frameOffset: Int,
        frameCount: Int,
    ): Int
}

/**
 * Pluggable audio codec/container engine contract.
 * `:engine:lite` implements this with MediaCodec + pure-Kotlin Ogg/Opus/FLAC/WAV;
 * a future `:engine:ffmpeg` flavor can implement the exact same interface.
 */
interface AudioEngine {
    val capabilities: EngineCapabilities

    suspend fun decodeToPcm(
        source: AudioSource,
        sessionDir: File,
        generateWaveform: Boolean = true,
        onProgress: (Float) -> Unit = {},
    ): DecodedAudio

    suspend fun exportTimeline(
        reader: PcmTimelineReader,
        config: ExportConfig,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {},
    ): ExportResult

    suspend fun exportLosslessOpus(
        plan: LosslessOpusTrimPlan,
        metadata: AudioMetadata,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {},
    ): ExportResult

    suspend fun readMetadata(source: AudioSource): AudioMetadata

    suspend fun rewriteMetadataLossless(
        sourceFile: File,
        format: AudioContainerFormat,
        metadata: AudioMetadata,
        outputStream: OutputStream,
    ): Boolean
}
