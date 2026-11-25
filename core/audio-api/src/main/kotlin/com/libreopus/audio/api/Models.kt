// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.audio.api

import java.io.File

enum class AudioContainerFormat(
    val displayName: String,
    val extension: String,
    val mimeTypes: List<String>,
) {
    OPUS("Opus", "opus", listOf("audio/opus", "audio/x-opus+ogg")),
    OGG_OPUS("Ogg Opus", "ogg", listOf("audio/ogg", "application/ogg")),
    OGG_VORBIS("Ogg Vorbis", "ogg", listOf("audio/ogg", "application/ogg", "audio/vorbis")),
    MP3("MP3", "mp3", listOf("audio/mpeg", "audio/mp3")),
    M4A("AAC / M4A", "m4a", listOf("audio/mp4", "audio/m4a", "audio/aac", "audio/x-m4a")),
    FLAC("FLAC", "flac", listOf("audio/flac", "audio/x-flac")),
    WAV("WAV", "wav", listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")),
    AMR("AMR", "amr", listOf("audio/amr", "audio/amr-wb", "audio/3gpp")),
    WEBM("WebM / Matroska", "webm", listOf("audio/webm", "audio/x-matroska")),
    VIDEO_AUDIO("Video Audio", "m4a", listOf("video/mp4", "video/x-matroska", "video/webm", "video/*")),
    UNKNOWN("Audio", "dat", listOf("audio/*")),
    ;

    val isOggOpus: Boolean
        get() = this == OPUS || this == OGG_OPUS

    companion object {
        fun fromExtensionOrMime(extension: String?, mimeType: String?): AudioContainerFormat {
            val ext = extension?.lowercase()?.trimStart('.')
            val mime = mimeType?.lowercase()?.substringBefore(';')?.trim()
            return when {
                ext == "opus" || mime == "audio/opus" || mime == "audio/x-opus+ogg" -> OPUS
                ext == "ogg" || ext == "oga" || mime == "audio/ogg" || mime == "application/ogg" -> OGG_OPUS
                ext == "mp3" || mime == "audio/mpeg" || mime == "audio/mp3" -> MP3
                ext == "m4a" || ext == "aac" || ext == "mp4a" || mime in listOf("audio/mp4", "audio/m4a", "audio/aac", "audio/x-m4a") -> M4A
                ext == "flac" || mime == "audio/flac" || mime == "audio/x-flac" -> FLAC
                ext == "wav" || mime in listOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave") -> WAV
                ext == "amr" || ext == "awb" || mime in listOf("audio/amr", "audio/amr-wb") -> AMR
                ext == "webm" || ext == "mka" || mime in listOf("audio/webm", "audio/x-matroska") -> WEBM
                ext in listOf("mp4", "mkv", "mov", "3gp") || mime?.startsWith("video/") == true -> VIDEO_AUDIO
                else -> UNKNOWN
            }
        }
    }
}

enum class ExportFormat(
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val supportsBitrate: Boolean,
    val supportsLosslessTags: Boolean,
) {
    OPUS("Opus (.opus)", "opus", "audio/opus", true, true),
    OGG_OPUS("Ogg Opus (.ogg)", "ogg", "audio/ogg", true, true),
    FLAC("FLAC (Lossless)", "flac", "audio/flac", false, true),
    M4A("AAC / M4A", "m4a", "audio/mp4", true, false),
    WAV("WAV (PCM)", "wav", "audio/wav", false, false),
}

enum class ChannelMode {
    KEEP,
    STEREO,
    MONO,
}

enum class WavBitDepth(val bits: Int, val isFloat: Boolean) {
    PCM_16(16, false),
    PCM_24(24, false),
    FLOAT_32(32, true),
}

data class AudioMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val genre: String = "",
    val comment: String = "",
    val composer: String = "",
    val coverArtBytes: ByteArray? = null,
    val coverArtMimeType: String? = null,
    val extraTags: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean
        get() = title.isBlank() &&
            artist.isBlank() &&
            album.isBlank() &&
            albumArtist.isBlank() &&
            year.isBlank() &&
            trackNumber.isBlank() &&
            genre.isBlank() &&
            comment.isBlank() &&
            composer.isBlank() &&
            coverArtBytes == null &&
            extraTags.isEmpty()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioMetadata) return false
        return title == other.title &&
            artist == other.artist &&
            album == other.album &&
            albumArtist == other.albumArtist &&
            year == other.year &&
            trackNumber == other.trackNumber &&
            genre == other.genre &&
            comment == other.comment &&
            composer == other.composer &&
            coverArtMimeType == other.coverArtMimeType &&
            extraTags == other.extraTags &&
            coverArtBytes.contentEquals(other.coverArtBytes)
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + albumArtist.hashCode()
        result = 31 * result + year.hashCode()
        result = 31 * result + trackNumber.hashCode()
        result = 31 * result + genre.hashCode()
        result = 31 * result + comment.hashCode()
        result = 31 * result + composer.hashCode()
        result = 31 * result + (coverArtBytes?.contentHashCode() ?: 0)
        result = 31 * result + (coverArtMimeType?.hashCode() ?: 0)
        result = 31 * result + extraTags.hashCode()
        return result
    }
}

data class AudioInfo(
    val displayName: String,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long,
    val bitrateKbps: Int,
    val codecName: String,
    val containerFormat: AudioContainerFormat,
    val fileSizeBytes: Long,
    val supportsLosslessOpus: Boolean,
)

data class ExportConfig(
    val format: ExportFormat,
    val bitrateKbps: Int = 128,
    val sampleRateHz: Int = 0,
    val channelMode: ChannelMode = ChannelMode.KEEP,
    val wavBitDepth: WavBitDepth = WavBitDepth.PCM_16,
    val losslessStreamCopy: Boolean = false,
    val normalizeTargetLufs: Float? = null,
    val metadata: AudioMetadata = AudioMetadata(),
)

data class WaveformLevel(
    val framesPerBin: Int,
    val minPeaks: FloatArray,
    val maxPeaks: FloatArray,
    val rms: FloatArray,
) {
    val binCount: Int get() = maxPeaks.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WaveformLevel) return false
        return framesPerBin == other.framesPerBin &&
            minPeaks.contentEquals(other.minPeaks) &&
            maxPeaks.contentEquals(other.maxPeaks) &&
            rms.contentEquals(other.rms)
    }

    override fun hashCode(): Int {
        var result = framesPerBin
        result = 31 * result + minPeaks.contentHashCode()
        result = 31 * result + maxPeaks.contentHashCode()
        result = 31 * result + rms.contentHashCode()
        return result
    }
}

data class WaveformPyramid(
    val totalFrames: Long,
    val sampleRate: Int,
    val levels: List<WaveformLevel>,
) {
    fun bestLevelFor(visibleFrames: Long, pixelWidth: Int): WaveformLevel? {
        if (levels.isEmpty()) return null
        val targetFramesPerBin = (visibleFrames.toFloat() / pixelWidth.coerceAtLeast(1)).coerceAtLeast(1f)
        return levels
            .sortedBy { it.framesPerBin }
            .lastOrNull { it.framesPerBin <= targetFramesPerBin }
            ?: levels.minByOrNull { it.framesPerBin }
    }
}

data class LosslessOpusTrimPlan(
    val sourceOggFile: File,
    val startMs: Long,
    val endMs: Long,
)

data class ExportResult(
    val bytesWritten: Long,
    val durationMs: Long,
    val wasLossless: Boolean,
)

data class EngineCapabilities(
    val engineId: String,
    val displayName: String,
    val supportedImportExtensions: Set<String>,
    val supportedExportFormats: Set<ExportFormat>,
    val supportsLosslessOpus: Boolean,
)

data class MediaChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

