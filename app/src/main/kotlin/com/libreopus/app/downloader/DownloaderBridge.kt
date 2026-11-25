// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import com.libreopus.audio.api.AudioMetadata
import java.io.File
import kotlinx.coroutines.flow.StateFlow

enum class DownloadPreset {
    BEST_AUDIO,
    IMAGE_ONLY,
    AUDIO_AND_IMAGE,
    VIDEO_MUXED,
    CUSTOM_SCRIPT,
}

enum class AudioFormatPreference(val formatSelector: String) {
    AUTO_BEST("bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio/best[acodec!=none]/best"),
    OPUS_WEBM("bestaudio[ext=webm]/bestaudio[acodec*=opus]/bestaudio/best[acodec!=none]/best"),
    M4A_AAC("bestaudio[ext=m4a]/bestaudio[acodec*=mp4a]/bestaudio/best[acodec!=none]/best"),
}

enum class FilenameTemplate(val pattern: String) {
    TITLE("%(title).80B.%(ext)s"),
    UPLOADER_TITLE("%(uploader).40B - %(title).70B.%(ext)s"),
    PLAYLIST_INDEX_TITLE("%(playlist_index)02d - %(title).70B.%(ext)s"),
    ID_ONLY("%(id)s.%(ext)s"),
    CUSTOM(""),
}

enum class YoutubeClientMode(val clientArg: String) {
    AUTO(""),
    ANDROID_VR("android_vr"),
    ANDROID("android"),
    IOS("ios"),
    WEB_SAFARI("web_safari"),
    WEB_EMBEDDED("web_embedded"),
    TV_DOWNGRADED("tv_downgraded"),
    MWEB("mweb"),
    TV("tv"),
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

data class CommandTemplateEntry(
    val id: String,
    val name: String,
    val args: String,
    val builtIn: Boolean = false,
)

private fun escapeJsonString(s: String): String = buildString(s.length) {
    for (ch in s) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

object CommandTemplateCodec {
    val builtInTemplates: List<CommandTemplateEntry> = listOf(
        CommandTemplateEntry(
            id = "builtin_best_audio",
            name = "Best Audio + Info",
            args = "-f bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio --no-playlist --write-info-json",
            builtIn = true,
        ),
        CommandTemplateEntry(
            id = "builtin_video_720",
            name = "Video 720p MP4",
            args = "-f \"best[height<=720][ext=mp4][acodec!=none][vcodec!=none]/best[ext=mp4]/best\" --no-playlist",
            builtIn = true,
        ),
        CommandTemplateEntry(
            id = "builtin_audio_subs",
            name = "Audio + Subtitles",
            args = "-f bestaudio --write-subs --write-auto-subs --sub-langs tr,en --no-playlist",
            builtIn = true,
        ),
        CommandTemplateEntry(
            id = "builtin_playlist_10",
            name = "Playlist Audio (1-10)",
            args = "-f bestaudio --yes-playlist --playlist-items 1-10 --ignore-errors",
            builtIn = true,
        ),
    )

    fun encode(templates: List<CommandTemplateEntry>): String {
        val sb = StringBuilder("[")
        templates.forEachIndexed { idx, t ->
            if (idx > 0) sb.append(',')
            sb.append("{\"id\":\"").append(escapeJsonString(t.id))
                .append("\",\"name\":\"").append(escapeJsonString(t.name))
                .append("\",\"args\":\"").append(escapeJsonString(t.args)).append("\"}")
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(raw: String): List<CommandTemplateEntry> {
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching { SimpleJsonParser.parse(raw) as? List<*> }.getOrNull() ?: return emptyList()
        return parsed.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = map["name"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val args = map["args"]?.toString().orEmpty()
            CommandTemplateEntry(id = id, name = name, args = args, builtIn = false)
        }
    }
}

data class DownloadHistoryEntry(
    val id: String,
    val title: String,
    val sourceUrl: String,
    val savedPath: String,
    val contentUriString: String? = null,
    val cacheFilePath: String? = null,
    val mimeType: String = "audio/*",
    val isVideo: Boolean = false,
    val sizeBytes: Long = 0L,
    val timestampMs: Long = 0L,
)

object DownloadHistoryCodec {
    const val MAX_ENTRIES = 30

    fun encode(entries: List<DownloadHistoryEntry>): String {
        val capped = entries.take(MAX_ENTRIES)
        val sb = StringBuilder("[")
        capped.forEachIndexed { idx, e ->
            if (idx > 0) sb.append(',')
            sb.append("{\"id\":\"").append(escapeJsonString(e.id))
                .append("\",\"title\":\"").append(escapeJsonString(e.title))
                .append("\",\"sourceUrl\":\"").append(escapeJsonString(e.sourceUrl))
                .append("\",\"savedPath\":\"").append(escapeJsonString(e.savedPath))
                .append("\",\"contentUri\":\"").append(escapeJsonString(e.contentUriString.orEmpty()))
                .append("\",\"cacheFile\":\"").append(escapeJsonString(e.cacheFilePath.orEmpty()))
                .append("\",\"mimeType\":\"").append(escapeJsonString(e.mimeType))
                .append("\",\"isVideo\":").append(e.isVideo)
                .append(",\"sizeBytes\":").append(e.sizeBytes)
                .append(",\"timestampMs\":").append(e.timestampMs)
                .append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    fun decode(raw: String): List<DownloadHistoryEntry> {
        if (raw.isBlank()) return emptyList()
        val parsed = runCatching { SimpleJsonParser.parse(raw) as? List<*> }.getOrNull() ?: return emptyList()
        return parsed.mapNotNull { row ->
            val map = row as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val title = map["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val sourceUrl = map["sourceUrl"]?.toString().orEmpty()
            val savedPath = map["savedPath"]?.toString().orEmpty()
            val contentUri = map["contentUri"]?.toString()?.takeIf { it.isNotBlank() }
            val cacheFile = map["cacheFile"]?.toString()?.takeIf { it.isNotBlank() }
            val mimeType = map["mimeType"]?.toString()?.takeIf { it.isNotBlank() } ?: "audio/*"
            val isVideo = map["isVideo"] as? Boolean ?: false
            val sizeBytes = (map["sizeBytes"] as? Number)?.toLong() ?: 0L
            val timestampMs = (map["timestampMs"] as? Number)?.toLong() ?: 0L
            DownloadHistoryEntry(
                id = id,
                title = title,
                sourceUrl = sourceUrl,
                savedPath = savedPath,
                contentUriString = contentUri,
                cacheFilePath = cacheFile,
                mimeType = mimeType,
                isVideo = isVideo,
                sizeBytes = sizeBytes,
                timestampMs = timestampMs,
            )
        }.take(MAX_ENTRIES)
    }
}

data class DownloadExecutionOptions(
    val audioPreference: AudioFormatPreference = AudioFormatPreference.AUTO_BEST,
    val selectedFormatId: String? = null,
    val cookieFilePath: String? = null,
    val forceIpv4: Boolean = false,
    val geoBypass: Boolean = true,
    val allowPlaylist: Boolean = false,
    val playlistItems: String = "",
    val filenameTemplate: FilenameTemplate = FilenameTemplate.TITLE,
    val customOutputTemplate: String = "%(title).80B.%(ext)s",
    val youtubeClient: YoutubeClientMode = YoutubeClientMode.AUTO,
    val extraExtractorArgs: String = "",
    val concurrentFragments: Int = 4,
    val rateLimit: String = "",
    val noCheckCertificates: Boolean = false,
    val writeSubtitles: Boolean = false,
    val referer: String = "",
    val userAgent: String = "",
    val proxyUrl: String = "",
    val socketTimeoutSec: Int = 20,
    val retries: Int = 3,
    val cacheDirPath: String? = null,
    val enableRemoteEjs: Boolean = true,
)

data class MediaFormatOption(
    val formatId: String,
    val ext: String,
    val vcodec: String,
    val acodec: String,
    val resolution: String,
    val abrKbps: Int,
    val tbrKbps: Int,
    val asrHz: Int,
    val height: Int,
    val formatNote: String,
    val fileSizeBytes: Long,
) {
    val isAudioOnly: Boolean
        get() = acodec.isNotBlank() && acodec != "none" && (vcodec.isBlank() || vcodec == "none")

    val isMuxedVideo: Boolean
        get() = acodec.isNotBlank() && acodec != "none" && vcodec.isNotBlank() && vcodec != "none"

    val isVideoOnly: Boolean
        get() = vcodec.isNotBlank() && vcodec != "none" && (acodec.isBlank() || acodec == "none")
}

data class PlaylistPreviewEntry(
    val index: Int,
    val title: String,
    val uploader: String = "",
    val durationSec: Int = 0,
    val url: String = "",
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
    val formats: List<MediaFormatOption> = emptyList(),
    val isPlaylist: Boolean = false,
    val playlistTitle: String = "",
    val playlistCount: Int = 0,
    val playlistEntries: List<PlaylistPreviewEntry> = emptyList(),
)

enum class YtDlpErrorKind {
    BOT_CHECK_NEEDS_COOKIES,
    AGE_RESTRICTED_NEEDS_COOKIES,
    PRIVATE_OR_AUTH_NEEDS_COOKIES,
    HTTP_403_FORBIDDEN,
    EXTRACTOR_OR_FORMAT_FAILED,
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

data class DownloadedMediaItem(
    val file: File,
    val displayName: String,
    val isVideo: Boolean,
    val mimeType: String,
    val metadata: AudioMetadata? = null,
)

data class DownloadedArtifact(
    val audioFile: File? = null,
    val audioDisplayName: String? = null,
    val imageFile: File? = null,
    val imageDisplayName: String? = null,
    val imageMimeType: String? = null,
    val metadata: AudioMetadata? = null,
    val sourceUrl: String? = null,
    val mediaItems: List<DownloadedMediaItem> = emptyList(),
    val imageFiles: List<File> = emptyList(),
    val subtitleFiles: List<File> = emptyList(),
)

interface MediaDownloader {
    val isAvailable: Boolean

    suspend fun getEngineStatus(context: Context): YtDlpEngineStatus

    suspend fun refreshEngineIfStale(
        context: Context,
        channel: YtDlpUpdateChannel = YtDlpUpdateChannel.STABLE,
    ): YtDlpEngineStatus? = null

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

data class SavedMediaResult(
    val item: DownloadedMediaItem,
    val savedPath: String?,
    val contentUriString: String? = null,
)

data class BackgroundDownloadState(
    val isRunning: Boolean = false,
    val activeTitle: String = "",
    val progress: Float = 0f,
    val etaSeconds: Long = -1L,
    val logs: List<String> = emptyList(),
    val result: DownloadedArtifact? = null,
    val savedAudioPath: String? = null,
    val savedImagePath: String? = null,
    val savedMediaEntries: List<SavedMediaResult> = emptyList(),
    val savedSubtitlePaths: List<String> = emptyList(),
    val errorDiagnostic: YtDlpErrorDiagnostic? = null,
    val engineStatus: YtDlpEngineStatus? = null,
    val lastCompletedTimestampMs: Long = 0L,
)

interface DownloadExecutionCoordinator {
    val state: StateFlow<BackgroundDownloadState>

    fun startDownload(
        context: Context,
        url: String,
        preset: DownloadPreset,
        customArgs: String,
        options: DownloadExecutionOptions,
        notificationTitle: String,
    )

    fun cancelDownload()

    fun clearOutcome()
}

data class YtDlpCommandPlan(
    val options: List<Pair<String, String?>>,
    val rawCommands: List<String> = emptyList(),
)

object PresetCommandBuilder {

    const val VIDEO_MUXED_SELECTOR =
        "best[ext=mp4][acodec!=none][vcodec!=none]/best[acodec!=none][vcodec!=none]/best"

    private val valueFlags = setOf(
        "-f", "--format", "-S", "--format-sort",
        "-o", "--output", "-P", "--paths",
        "--cookies", "--cookies-from-browser",
        "--user-agent", "--referer", "--proxy",
        "--socket-timeout", "--retries", "--fragment-retries",
        "--limit-rate", "-r", "--throttled-rate",
        "--playlist-items", "-I", "--max-downloads",
        "--extractor-args", "--add-headers", "--add-header",
        "--audio-format", "--audio-quality",
        "--sub-langs", "--sleep-requests", "--sleep-interval",
        "-N", "--concurrent-fragments",
        "--cache-dir", "--remote-components",
        "--match-filter", "--compat-options",
    )

    fun parseUrls(rawInput: String): List<String> = rawInput
        .lineSequence()
        .flatMap { it.trim().split(Regex("\\s+")).asSequence() }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
        .toList()

    fun resolveOutputTemplate(workDir: File, execOptions: DownloadExecutionOptions): String {
        val rawPattern = when (execOptions.filenameTemplate) {
            FilenameTemplate.CUSTOM -> execOptions.customOutputTemplate.trim()
            else -> execOptions.filenameTemplate.pattern
        }.ifBlank { FilenameTemplate.TITLE.pattern }
        val sanitized = rawPattern
            .replace("..", "")
            .trimStart('/', '\\')
            .ifBlank { FilenameTemplate.TITLE.pattern }
        return File(workDir, sanitized).absolutePath
    }

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
            if (includePlaylistFlag && !hasToken("--no-playlist") && !hasToken("--yes-playlist")) {
                if (execOptions.allowPlaylist) {
                    opts += "--yes-playlist" to null
                    opts += "--ignore-errors" to null
                    val items = execOptions.playlistItems.trim()
                    if (items.isNotBlank() && !hasToken("--playlist-items") && !hasToken("-I")) {
                        opts += "--playlist-items" to items
                    }
                } else {
                    opts += "--no-playlist" to null
                }
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
            if (execOptions.concurrentFragments > 1 && !hasToken("-N") && !hasToken("--concurrent-fragments")) {
                opts += "-N" to execOptions.concurrentFragments.coerceIn(1, 8).toString()
            }
            val rate = execOptions.rateLimit.trim()
            if (rate.isNotBlank() && !hasToken("-r") && !hasToken("--limit-rate")) {
                opts += "--limit-rate" to rate
            }
            if (execOptions.forceIpv4 && !hasToken("--force-ipv4") && !hasToken("-4")) {
                opts += "--force-ipv4" to null
            }
            if (execOptions.geoBypass && !hasToken("--geo-bypass")) {
                opts += "--geo-bypass" to null
            }
            if (execOptions.noCheckCertificates && !hasToken("--no-check-certificates")) {
                opts += "--no-check-certificates" to null
            }
            val cookiePath = execOptions.cookieFilePath?.takeIf { it.isNotBlank() }
            if (cookiePath != null && !hasToken("--cookies")) {
                opts += "--cookies" to cookiePath
            }
            val ua = execOptions.userAgent.trim()
            if (ua.isNotBlank() && !hasToken("--user-agent")) {
                opts += "--user-agent" to ua
            }
            val referer = execOptions.referer.trim()
            if (referer.isNotBlank() && !hasToken("--referer")) {
                opts += "--referer" to referer
            }
            val proxy = execOptions.proxyUrl.trim()
            if (proxy.isNotBlank() && !hasToken("--proxy")) {
                opts += "--proxy" to proxy
            }
            val cacheDir = execOptions.cacheDirPath?.trim()?.takeIf { it.isNotBlank() }
            if (cacheDir != null && !hasToken("--cache-dir") && !hasToken("--no-cache-dir")) {
                opts += "--cache-dir" to cacheDir
            }
            if (execOptions.enableRemoteEjs && !hasToken("--remote-components")) {
                opts += "--remote-components" to "ejs:github"
            }
            if (!hasToken("--extractor-args")) {
                buildExtractorArgsString(execOptions)?.let { extArg ->
                    opts += "--extractor-args" to extArg
                }
            }
            if (execOptions.writeSubtitles && preset != DownloadPreset.IMAGE_ONLY && preset != DownloadPreset.CUSTOM_SCRIPT) {
                if (!hasToken("--write-subs")) opts += "--write-subs" to null
                if (!hasToken("--write-auto-subs")) opts += "--write-auto-subs" to null
                if (!hasToken("--sub-langs")) opts += "--sub-langs" to "tr,en"
            }
            if (!hasToken("--newline")) {
                opts += "--newline" to null
            }
        }

        val formatSelector = execOptions.selectedFormatId
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: when (preset) {
                DownloadPreset.VIDEO_MUXED -> VIDEO_MUXED_SELECTOR
                else -> execOptions.audioPreference.formatSelector
            }

        return when (preset) {
            DownloadPreset.BEST_AUDIO -> {
                opts += "-f" to formatSelector
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
                opts += "-f" to formatSelector
                opts += "--write-thumbnail" to null
                opts += "--write-info-json" to null
                opts += "--no-mtime" to null
                opts += "--restrict-filenames" to null
                addSharedNetworkAndCookieOpts()
                opts += "-o" to outputTemplate
                YtDlpCommandPlan(options = opts)
            }
            DownloadPreset.VIDEO_MUXED -> {
                opts += "-f" to formatSelector
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

    fun buildExtractorArgsString(execOptions: DownloadExecutionOptions): String? {
        val extra = execOptions.extraExtractorArgs.trim()
        val client = execOptions.youtubeClient.clientArg
        if (client.isBlank() && extra.isBlank()) return null
        return when {
            client.isNotBlank() && extra.isBlank() -> "youtube:player_client=$client"
            client.isBlank() -> if (extra.contains(':')) extra else "youtube:$extra"
            extra.startsWith("youtube:", ignoreCase = true) ->
                "youtube:player_client=$client;${extra.substringAfter(':')}"
            !extra.contains(':') -> "youtube:player_client=$client;$extra"
            else -> "youtube:player_client=$client"
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

    fun exportAsCustomCommand(
        preset: DownloadPreset,
        customArgs: String,
        execOptions: DownloadExecutionOptions = DownloadExecutionOptions(),
    ): String {
        if (preset == DownloadPreset.CUSTOM_SCRIPT && customArgs.isNotBlank()) return customArgs
        val plan = buildExecutionPlan(
            preset = preset,
            customArgs = customArgs,
            outputTemplate = "",
            execOptions = execOptions.copy(cookieFilePath = null, cacheDirPath = null),
        )
        return plan.options
            .filterNot { it.first == "-o" || it.first == "--newline" || it.first == "--cache-dir" }
            .joinToString(" ") { (flag, value) ->
                if (value == null) {
                    flag
                } else if (value.any { it.isWhitespace() || it == ';' || it == '<' || it == '>' }) {
                    "$flag \"$value\""
                } else {
                    "$flag $value"
                }
            }
    }

    fun formatPreview(
        preset: DownloadPreset,
        execOptions: DownloadExecutionOptions = DownloadExecutionOptions(),
    ): String {
        val parts = mutableListOf<String>()
        val selected = execOptions.selectedFormatId?.trim()?.takeIf { it.isNotBlank() }
        when (preset) {
            DownloadPreset.BEST_AUDIO -> parts += "-f ${selected ?: execOptions.audioPreference.formatSelector}"
            DownloadPreset.IMAGE_ONLY -> parts += "--skip-download --write-thumbnail"
            DownloadPreset.AUDIO_AND_IMAGE -> parts += "-f ${selected ?: execOptions.audioPreference.formatSelector} --write-thumbnail"
            DownloadPreset.VIDEO_MUXED -> parts += "-f ${selected ?: VIDEO_MUXED_SELECTOR} --write-thumbnail"
            DownloadPreset.CUSTOM_SCRIPT -> Unit
        }
        if (preset != DownloadPreset.CUSTOM_SCRIPT) {
            if (execOptions.allowPlaylist) {
                parts += "--yes-playlist"
                execOptions.playlistItems.trim().takeIf { it.isNotBlank() }?.let { parts += "--playlist-items $it" }
            } else {
                parts += "--no-playlist"
            }
        }
        if (!execOptions.cookieFilePath.isNullOrBlank()) parts += "--cookies <active>"
        if (execOptions.youtubeClient != YoutubeClientMode.AUTO) {
            parts += "--extractor-args youtube:player_client=${execOptions.youtubeClient.clientArg}"
        }
        if (execOptions.concurrentFragments > 1) parts += "-N ${execOptions.concurrentFragments}"
        if (execOptions.forceIpv4) parts += "--force-ipv4"
        if (execOptions.noCheckCertificates) parts += "--no-check-certificates"
        if (execOptions.writeSubtitles && preset != DownloadPreset.IMAGE_ONLY) parts += "--write-subs"
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

object DumpJsonPreviewParser {

    fun parse(
        jsonText: String,
        sourceUrl: String,
        audioPreference: AudioFormatPreference = AudioFormatPreference.AUTO_BEST,
    ): MediaTrackPreview? {
        val root = runCatching { SimpleJsonParser.parse(jsonText) as? Map<*, *> }.getOrNull() ?: return null

        val type = root["_type"]?.toString().orEmpty()
        val entriesRaw = (root["entries"] as? List<*>)?.mapNotNull { it as? Map<*, *> }.orEmpty()
        if (type == "playlist" || entriesRaw.isNotEmpty()) {
            val playlistTitle = root["title"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: "Playlist"
            val entries = entriesRaw.mapIndexed { idx, e ->
                PlaylistPreviewEntry(
                    index = (e["playlist_index"] as? Number)?.toInt() ?: (idx + 1),
                    title = e["title"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        ?: "Track ${idx + 1}",
                    uploader = listOf("uploader", "channel", "creator")
                        .firstNotNullOfOrNull { k -> e[k]?.toString()?.takeIf { it.isNotBlank() && it != "null" } }
                        .orEmpty(),
                    durationSec = ((e["duration"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                    url = e["url"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        ?: e["webpage_url"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                        ?: "",
                )
            }
            val totalCount = ((root["playlist_count"] as? Number)?.toInt() ?: entries.size).coerceAtLeast(entries.size)
            val uploader = listOf("uploader", "channel", "playlist_uploader")
                .firstNotNullOfOrNull { k -> root[k]?.toString()?.takeIf { it.isNotBlank() && it != "null" } }
                .orEmpty()
            return MediaTrackPreview(
                title = playlistTitle,
                uploader = uploader,
                durationSec = entries.sumOf { it.durationSec },
                webpageUrl = root["webpage_url"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: sourceUrl,
                extractor = root["extractor_key"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: root["extractor"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                isPlaylist = true,
                playlistTitle = playlistTitle,
                playlistCount = totalCount,
                playlistEntries = entries,
            )
        }

        val parsedFormats = ((root["formats"] as? List<*>).orEmpty())
            .mapNotNull { it as? Map<*, *> }
            .mapNotNull { f ->
                val formatId = f["format_id"]?.toString()?.trim().orEmpty()
                val ext = f["ext"]?.toString()?.trim().orEmpty()
                val note = f["format_note"]?.toString()?.takeIf { it != "null" }.orEmpty()
                if (formatId.isBlank() || ext.isBlank() || note.contains("storyboard", ignoreCase = true) || ext == "mhtml") {
                    return@mapNotNull null
                }
                val vcodec = f["vcodec"]?.toString()?.takeIf { it != "null" }.orEmpty()
                val acodec = f["acodec"]?.toString()?.takeIf { it != "null" }.orEmpty()
                val height = (f["height"] as? Number)?.toInt() ?: 0
                val resolution = f["resolution"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: if (height > 0) "${height}p" else if (vcodec == "none" || vcodec.isBlank()) "audio only" else ""
                val size = ((f["filesize"] as? Number)?.toLong()?.takeIf { it > 0L }
                    ?: (f["filesize_approx"] as? Number)?.toLong()
                    ?: 0L).coerceAtLeast(0L)
                MediaFormatOption(
                    formatId = formatId,
                    ext = ext,
                    vcodec = vcodec,
                    acodec = acodec,
                    resolution = resolution,
                    abrKbps = ((f["abr"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                    tbrKbps = ((f["tbr"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                    asrHz = ((f["asr"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                    height = height.coerceAtLeast(0),
                    formatNote = note,
                    fileSizeBytes = size,
                )
            }
            .distinctBy { it.formatId }

        val sortedFormats = buildList {
            addAll(parsedFormats.filter { it.isAudioOnly }.sortedByDescending { maxOf(it.abrKbps, it.tbrKbps) })
            addAll(parsedFormats.filter { it.isMuxedVideo }.sortedWith(compareByDescending<MediaFormatOption> { it.height }.thenByDescending { it.tbrKbps }))
            addAll(parsedFormats.filter { it.isVideoOnly }.sortedWith(compareByDescending<MediaFormatOption> { it.height }.thenByDescending { it.tbrKbps }))
        }

        val audioFormats = sortedFormats.filter { it.isAudioOnly }
        val preferredExt = when (audioPreference) {
            AudioFormatPreference.OPUS_WEBM -> "webm"
            AudioFormatPreference.M4A_AAC -> "m4a"
            AudioFormatPreference.AUTO_BEST -> null
        }
        val bestAudio = audioFormats
            .filter { preferredExt == null || it.ext.equals(preferredExt, ignoreCase = true) }
            .maxByOrNull { maxOf(it.abrKbps, it.tbrKbps) }
            ?: audioFormats.maxByOrNull { maxOf(it.abrKbps, it.tbrKbps) }

        val approxSize = ((bestAudio?.fileSizeBytes?.takeIf { it > 0L })
            ?: (root["filesize"] as? Number)?.toLong()?.takeIf { it > 0L }
            ?: (root["filesize_approx"] as? Number)?.toLong()
            ?: 0L).coerceAtLeast(0L)

        val title = root["title"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: root["fulltitle"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            ?: "Media"

        val uploader = listOf("uploader", "channel", "creator")
            .firstNotNullOfOrNull { key ->
                root[key]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            }.orEmpty()

        return MediaTrackPreview(
            title = title,
            uploader = uploader,
            durationSec = ((root["duration"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            thumbnailUrl = root["thumbnail"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
            webpageUrl = root["webpage_url"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: sourceUrl,
            extractor = root["extractor_key"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: root["extractor"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
            audioExt = bestAudio?.ext ?: root["ext"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
            approxFileSizeBytes = approxSize,
            formats = sortedFormats,
        )
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

            lower.contains("requested format is not available") ||
                lower.contains("no video formats found") ||
                lower.contains("unable to extract") ||
                lower.contains("nsig extraction failed") ||
                lower.contains("signature extraction failed") ||
                lower.contains("no supported javascript runtime") ||
                lower.contains("remote component challenge solver") ||
                lower.contains("only images are available for download") ||
                lower.contains("playability status: unplayable") ->
                YtDlpErrorKind.EXTRACTOR_OR_FORMAT_FAILED

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
                lower.contains("unable to download api page") ||
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
