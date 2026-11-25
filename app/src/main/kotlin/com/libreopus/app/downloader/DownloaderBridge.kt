// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import com.libreopus.audio.api.AudioMetadata
import java.io.File

enum class DownloadPreset {
    BEST_AUDIO,
    IMAGE_ONLY,
    AUDIO_AND_IMAGE,
    CUSTOM_SCRIPT,
}

enum class AudioFormatPreference(val formatSelector: String) {
    AUTO_BEST("bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio"),
    OPUS_WEBM("bestaudio[ext=webm]/bestaudio[acodec*=opus]/bestaudio"),
    M4A_AAC("bestaudio[ext=m4a]/bestaudio[acodec*=mp4a]/bestaudio"),
}

enum class YtDlpUpdateChannel {
    STABLE,
    NIGHTLY,
}

enum class YtDlpUpdateStatus {
    UPDATED,
    ALREADY_UP_TO_DATE,
    UNAVAILABLE,
}

data class YtDlpEngineStatus(
    val available: Boolean,
    val initialized: Boolean,
    val version: String? = null,
    val versionName: String? = null,
)

data class YtDlpUpdateOutcome(
    val status: YtDlpUpdateStatus,
    val version: String? = null,
    val versionName: String? = null,
)

data class DownloadExecutionOptions(
    val audioPreference: AudioFormatPreference = AudioFormatPreference.AUTO_BEST,
    val cookieFilePath: String? = null,
    val forceIpv4: Boolean = false,
    val geoBypass: Boolean = true,
    val allowPlaylist: Boolean = false,
    val userAgent: String = "",
    val proxyUrl: String = "",
    val socketTimeoutSec: Int = 20,
    val retries: Int = 3,
)

data class MediaTrackPreview(
    val title: String,
    val uploader: String = "",
    val durationSec: Int = 0,
    val thumbnailUrl: String? = null,
    val webpageUrl: String? = null,
    val extractor: String? = null,
    val audioExt: String? = null,
    val approxFileSizeBytes: Long = 0L,
)

enum class YtDlpErrorKind {
    BOT_CHECK_NEEDS_COOKIES,
    AGE_RESTRICTED_NEEDS_COOKIES,
    PRIVATE_OR_AUTH_NEEDS_COOKIES,
    HTTP_403_FORBIDDEN,
    FFMPEG_REQUIRED,
    NETWORK_OR_TIMEOUT,
    UNSUPPORTED_URL,
    UNAVAILABLE,
    UNKNOWN,
}

data class YtDlpErrorDiagnostic(
    val kind: YtDlpErrorKind,
    val rawDetails: String,
)

class YtDlpClassifiedException(
    val diagnostic: YtDlpErrorDiagnostic,
    cause: Throwable? = null,
) : Exception(diagnostic.rawDetails.ifBlank { cause?.message ?: "Download failed" }, cause)

data class DownloadedArtifact(
    val audioFile: File? = null,
    val audioDisplayName: String? = null,
    val imageFile: File? = null,
    val imageDisplayName: String? = null,
    val imageMimeType: String? = null,
    val metadata: AudioMetadata? = null,
    val sourceUrl: String? = null,
)

interface MediaDownloader {
    val isAvailable: Boolean

    suspend fun getEngineStatus(context: Context): YtDlpEngineStatus

    suspend fun updateEngine(context: Context, channel: YtDlpUpdateChannel): YtDlpUpdateOutcome

    suspend fun fetchPreview(
        context: Context,
        url: String,
        options: DownloadExecutionOptions = DownloadExecutionOptions(),
    ): MediaTrackPreview

    suspend fun execute(
        context: Context,
        url: String,
        preset: DownloadPreset,
        customArgs: String,
        workDir: File,
        options: DownloadExecutionOptions = DownloadExecutionOptions(),
        onProgress: (percent: Float, etaSec: Long, line: String) -> Unit,
    ): DownloadedArtifact

    fun cancel()
}

data class YtDlpCommandPlan(
    val options: List<Pair<String, String?>>,
    val rawCommands: List<String> = emptyList(),
)

object PresetCommandBuilder {

    private val valueFlags = setOf(
        "-f", "--format", "-S", "--format-sort",
        "-o", "--output", "-P", "--paths",
        "--cookies", "--cookies-from-browser",
        "--user-agent", "--referer", "--proxy",
        "--socket-timeout", "--retries", "--fragment-retries",
        "--limit-rate", "-r", "--throttled-rate",
        "--playlist-items", "--max-downloads",
        "--extractor-args", "--add-headers",
        "--audio-format", "--audio-quality",
        "--sub-langs", "--sleep-requests", "--sleep-interval",
    )

    fun buildExecutionPlan(
        preset: DownloadPreset,
        customArgs: String,
        outputTemplate: String,
        execOptions: DownloadExecutionOptions = DownloadExecutionOptions(),
    ): YtDlpCommandPlan {
        val opts = mutableListOf<Pair<String, String?>>()

        fun addSharedNetworkAndCookieOpts(
            existingTokens: List<String> = emptyList(),
            includePlaylistFlag: Boolean = true,
        ) {
            val hasToken: (String) -> Boolean = { flag ->
                existingTokens.any { it == flag || it.startsWith("$flag=") }
            }
            if (includePlaylistFlag && !execOptions.allowPlaylist &&
                !hasToken("--no-playlist") && !hasToken("--yes-playlist")
            ) {
                opts += "--no-playlist" to null
            }
            if (!hasToken("--retries") && !hasToken("-R")) {
                opts += "--retries" to execOptions.retries.coerceIn(1, 10).toString()
            }
            if (!hasToken("--fragment-retries")) {
                opts += "--fragment-retries" to execOptions.retries.coerceIn(1, 10).toString()
            }
            if (!hasToken("--socket-timeout")) {
                opts += "--socket-timeout" to execOptions.socketTimeoutSec.coerceIn(5, 120).toString()
            }
            if (execOptions.forceIpv4 && !hasToken("--force-ipv4") && !hasToken("-4")) {
                opts += "--force-ipv4" to null
            }
            if (execOptions.geoBypass && !hasToken("--geo-bypass")) {
                opts += "--geo-bypass" to null
            }
            val cookiePath = execOptions.cookieFilePath?.takeIf { it.isNotBlank() }
            if (cookiePath != null && !hasToken("--cookies")) {
                opts += "--cookies" to cookiePath
            }
            val ua = execOptions.userAgent.trim()
            if (ua.isNotBlank() && !hasToken("--user-agent")) {
                opts += "--user-agent" to ua
            }
            val proxy = execOptions.proxyUrl.trim()
            if (proxy.isNotBlank() && !hasToken("--proxy")) {
                opts += "--proxy" to proxy
            }
            if (!hasToken("--newline")) {
                opts += "--newline" to null
            }
        }

        return when (preset) {
            DownloadPreset.BEST_AUDIO -> {
                opts += "-f" to execOptions.audioPreference.formatSelector
                opts += "--write-info-json" to null
                opts += "--no-mtime" to null
                opts += "--restrict-filenames" to null
                addSharedNetworkAndCookieOpts()
                opts += "-o" to outputTemplate
                YtDlpCommandPlan(options = opts)
            }
            DownloadPreset.IMAGE_ONLY -> {
                opts += "--skip-download" to null
                opts += "--write-thumbnail" to null
                opts += "--write-info-json" to null
                opts += "--no-mtime" to null
                opts += "--restrict-filenames" to null
                addSharedNetworkAndCookieOpts()
                opts += "-o" to outputTemplate
                YtDlpCommandPlan(options = opts)
            }
            DownloadPreset.AUDIO_AND_IMAGE -> {
                opts += "-f" to execOptions.audioPreference.formatSelector
                opts += "--write-thumbnail" to null
                opts += "--write-info-json" to null
                opts += "--no-mtime" to null
                opts += "--restrict-filenames" to null
                addSharedNetworkAndCookieOpts()
                opts += "-o" to outputTemplate
                YtDlpCommandPlan(options = opts)
            }
            DownloadPreset.CUSTOM_SCRIPT -> {
                val tokens = tokenizeArgs(customArgs)
                val hasOutput = tokens.any { it == "-o" || it == "--output" || it.startsWith("--output=") }
                addSharedNetworkAndCookieOpts(existingTokens = tokens, includePlaylistFlag = false)
                if (!tokens.any { it == "--write-info-json" || it == "--no-write-info-json" }) {
                    opts += "--write-info-json" to null
                }
                if (!hasOutput) {
                    opts += "-o" to outputTemplate
                }
                YtDlpCommandPlan(options = opts, rawCommands = tokens)
            }
        }
    }

    fun buildArguments(
        preset: DownloadPreset,
        customArgs: String,
        outputTemplate: String,
        execOptions: DownloadExecutionOptions = DownloadExecutionOptions(),
    ): List<Pair<String, String?>> {
        val plan = buildExecutionPlan(preset, customArgs, outputTemplate, execOptions)
        if (plan.rawCommands.isEmpty()) return plan.options

        val merged = plan.options.toMutableList()
        val tokens = plan.rawCommands
        var i = 0
        while (i < tokens.size) {
            val tok = tokens[i]
            if (tok == "--") {
                for (j in i until tokens.size) {
                    merged += tokens[j] to null
                }
                break
            }
            val eqIdx = tok.indexOf('=')
            if (tok.startsWith("--") && eqIdx > 2) {
                merged += tok.substring(0, eqIdx) to tok.substring(eqIdx + 1)
                i++
            } else if (tok in valueFlags && i + 1 < tokens.size) {
                merged += tok to tokens[i + 1]
                i += 2
            } else {
                merged += tok to null
                i++
            }
        }
        return merged
    }

    fun formatPreview(
        preset: DownloadPreset,
        execOptions: DownloadExecutionOptions = DownloadExecutionOptions(),
    ): String {
        val parts = mutableListOf<String>()
        when (preset) {
            DownloadPreset.BEST_AUDIO -> parts += "-f ${execOptions.audioPreference.formatSelector}"
            DownloadPreset.IMAGE_ONLY -> parts += "--skip-download --write-thumbnail"
            DownloadPreset.AUDIO_AND_IMAGE -> parts += "-f ${execOptions.audioPreference.formatSelector} --write-thumbnail"
            DownloadPreset.CUSTOM_SCRIPT -> Unit
        }
        if (preset != DownloadPreset.CUSTOM_SCRIPT && !execOptions.allowPlaylist) {
            parts += "--no-playlist"
        }
        if (!execOptions.cookieFilePath.isNullOrBlank()) {
            parts += "--cookies <active>"
        }
        if (execOptions.forceIpv4) parts += "--force-ipv4"
        if (execOptions.proxyUrl.isNotBlank()) parts += "--proxy ${execOptions.proxyUrl.trim()}"
        return parts.joinToString(" ")
    }

    fun tokenizeArgs(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in input) {
            when {
                quote != null -> {
                    if (ch == quote) {
                        quote = null
                    } else {
                        cur.append(ch)
                    }
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString()
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}

object YtDlpErrorClassifier {

    fun classify(rawError: String): YtDlpErrorDiagnostic {
        val lower = rawError.lowercase()
        val kind = when {
            lower.contains("age-restricted") ||
                lower.contains("confirm your age") ||
                lower.contains("inappropriate for some users") ->
                YtDlpErrorKind.AGE_RESTRICTED_NEEDS_COOKIES

            lower.contains("confirm you're not a bot") ||
                lower.contains("confirm you’re not a bot") ||
                lower.contains("sign in to confirm you") ||
                lower.contains("not a bot") ||
                lower.contains("use --cookies") ||
                lower.contains("cookies-from-browser") ||
                lower.contains("login required") ->
                YtDlpErrorKind.BOT_CHECK_NEEDS_COOKIES

            lower.contains("members-only") ||
                lower.contains("private video") ||
                lower.contains("join this channel") ||
                lower.contains("subscribers-only") ||
                lower.contains("http error 401") ->
                YtDlpErrorKind.PRIVATE_OR_AUTH_NEEDS_COOKIES

            lower.contains("http error 403") ||
                lower.contains("403: forbidden") ||
                lower.contains("unable to download video data: http error 403") ->
                YtDlpErrorKind.HTTP_403_FORBIDDEN

            lower.contains("ffmpeg is not installed") ||
                lower.contains("ffprobe and ffmpeg not found") ||
                (lower.contains("ffmpeg") && lower.contains("postprocessing")) ->
                YtDlpErrorKind.FFMPEG_REQUIRED

            lower.contains("timed out") ||
                lower.contains("connection reset") ||
                lower.contains("name or service not known") ||
                lower.contains("temporary failure in name resolution") ||
                lower.contains("failed to resolve") ||
                lower.contains("unable to download webpage") ||
                lower.contains("ssl") ||
                lower.contains("network is unreachable") ->
                YtDlpErrorKind.NETWORK_OR_TIMEOUT

            lower.contains("unsupported url") ->
                YtDlpErrorKind.UNSUPPORTED_URL

            lower.contains("video unavailable") ||
                lower.contains("has been removed") ||
                lower.contains("not available in your country") ||
                lower.contains("copyright") ->
                YtDlpErrorKind.UNAVAILABLE

            else -> YtDlpErrorKind.UNKNOWN
        }
        return YtDlpErrorDiagnostic(kind = kind, rawDetails = rawError.trim())
    }
}

object InfoJsonMetadataParser {

    fun parse(jsonText: String): AudioMetadata? {
        val root = runCatching { SimpleJsonParser.parse(jsonText) as? Map<*, *> }.getOrNull() ?: return null

        val track = root["track"]?.toString()?.trim().orEmpty()
        val rawTitle = root["title"]?.toString()?.trim().orEmpty()
        val title = track.ifBlank { rawTitle }

        val artistsList = (root["artists"] as? List<*>)
            ?.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotBlank) }
            ?.joinToString(", ")
            .orEmpty()
        val artist = listOf(
            root["artist"]?.toString()?.trim().orEmpty(),
            artistsList,
            root["creator"]?.toString()?.trim().orEmpty(),
            root["uploader"]?.toString()?.trim().orEmpty(),
            root["channel"]?.toString()?.trim().orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val album = root["album"]?.toString()?.trim().orEmpty()
        val albumArtist = listOf(
            root["album_artist"]?.toString()?.trim().orEmpty(),
            root["uploader"]?.toString()?.trim().orEmpty(),
        ).firstOrNull { it.isNotBlank() }.orEmpty()

        val releaseYear = (root["release_year"] as? Number)?.toInt()?.takeIf { it in 1900..2100 }?.toString()
        val releaseDate = root["release_date"]?.toString()?.trim().orEmpty()
        val uploadDate = root["upload_date"]?.toString()?.trim().orEmpty()
        val year = releaseYear
            ?: releaseDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: uploadDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }
            ?: ""

        val trackNumber = (root["track_number"] as? Number)?.toInt()?.takeIf { it > 0 }?.toString()
            ?: root["track_number"]?.toString()?.trim()?.takeIf { it.isNotBlank() && it != "null" }
            ?: ""

        val genre = listOf(
            root["genre"]?.toString()?.trim().orEmpty(),
            (root["genres"] as? List<*>)?.firstOrNull()?.toString()?.trim().orEmpty(),
            (root["categories"] as? List<*>)?.firstOrNull()?.toString()?.trim().orEmpty(),
        ).firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()

        val webpageUrl = listOf(
            root["webpage_url"]?.toString()?.trim().orEmpty(),
            root["original_url"]?.toString()?.trim().orEmpty(),
        ).firstOrNull { it.isNotBlank() && it != "null" }.orEmpty()

        val meta = AudioMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            trackNumber = trackNumber,
            genre = genre,
            comment = webpageUrl,
        )
        return meta.takeUnless { it.isEmpty }
    }
}
