// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import com.libreopus.app.R
import com.libreopus.audio.api.AudioMetadata
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object PlatformDownloader {
    private const val PREFS_RUNTIME = "libreopus_ytdlp_runtime"
    private const val KEY_LAST_AUTO_UPDATE_MS = "last_auto_update_ms"
    private const val AUTO_UPDATE_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private val initMutex = Mutex()
    private val updateMutex = Mutex()

    @Volatile
    private var initialized = false

    @Volatile
    private var updateAttemptedThisSession = false

    fun create(): MediaDownloader = YoutubeDlMediaDownloader()

    private suspend fun ensureInitialized(context: Context) {
        if (initialized) return
        initMutex.withLock {
            if (!initialized) {
                YoutubeDL.getInstance().init(context.applicationContext)
                initialized = true
            }
        }
    }

    private class YoutubeDlMediaDownloader : MediaDownloader {
        override val isAvailable: Boolean = true

        @Volatile
        private var activeProcessId: String? = null

        override suspend fun getEngineStatus(context: Context): YtDlpEngineStatus = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            YtDlpEngineStatus(
                available = true,
                initialized = initialized,
                version = YoutubeDL.getInstance().version(appCtx),
                versionName = YoutubeDL.getInstance().versionName(appCtx),
            )
        }

        override suspend fun refreshEngineIfStale(
            context: Context,
            channel: YtDlpUpdateChannel,
        ): YtDlpEngineStatus? = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            ensureInitialized(appCtx)
            val prefs = appCtx.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE)
            val lastCheck = prefs.getLong(KEY_LAST_AUTO_UPDATE_MS, 0L)
            val now = System.currentTimeMillis()
            val curVersion = YoutubeDL.getInstance().version(appCtx)
            val isBundledOrUnchecked = curVersion.isNullOrBlank() || curVersion == "2025.11.12"
            val isStale = (now - lastCheck) >= AUTO_UPDATE_INTERVAL_MS
            if (!updateAttemptedThisSession && (isBundledOrUnchecked || isStale)) {
                runCatching { updateEngineInternal(appCtx, channel, timeoutMs = 20_000L) }
            }
            getEngineStatus(appCtx)
        }

        override suspend fun updateEngine(
            context: Context,
            channel: YtDlpUpdateChannel,
        ): YtDlpUpdateOutcome = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            updateEngineInternal(appCtx, channel, timeoutMs = 0L)
        }

        private suspend fun updateEngineInternal(
            appCtx: Context,
            channel: YtDlpUpdateChannel,
            timeoutMs: Long,
        ): YtDlpUpdateOutcome = updateMutex.withLock {
            ensureInitialized(appCtx)
            updateAttemptedThisSession = true
            appCtx.getSharedPreferences(PREFS_RUNTIME, Context.MODE_PRIVATE).edit {
                putLong(KEY_LAST_AUTO_UPDATE_MS, System.currentTimeMillis())
            }
            val ytChannel = when (channel) {
                YtDlpUpdateChannel.STABLE -> YoutubeDL.UpdateChannel.STABLE
                YtDlpUpdateChannel.NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
            }
            val result = if (timeoutMs > 0L) {
                withTimeoutOrNull(timeoutMs) {
                    YoutubeDL.getInstance().updateYoutubeDL(appCtx, ytChannel)
                }
            } else {
                YoutubeDL.getInstance().updateYoutubeDL(appCtx, ytChannel)
            }
            val mapped = when (result) {
                YoutubeDL.UpdateStatus.DONE -> YtDlpUpdateStatus.UPDATED
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> YtDlpUpdateStatus.ALREADY_UP_TO_DATE
                null -> YtDlpUpdateStatus.UNAVAILABLE
            }
            YtDlpUpdateOutcome(
                status = mapped,
                version = YoutubeDL.getInstance().version(appCtx),
                versionName = YoutubeDL.getInstance().versionName(appCtx),
            )
        }

        private fun prepareOptionsWithCache(
            context: Context,
            options: DownloadExecutionOptions,
        ): DownloadExecutionOptions {
            if (!options.cacheDirPath.isNullOrBlank()) return options
            val cacheDir = File(context.applicationContext.cacheDir, "ytdlp_cache").apply { mkdirs() }
            return options.copy(cacheDirPath = cacheDir.absolutePath)
        }

        override suspend fun fetchPreview(
            context: Context,
            url: String,
            options: DownloadExecutionOptions,
        ): MediaTrackPreview = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            ensureInitialized(appCtx)
            val baseOptions = prepareOptionsWithCache(appCtx, options)
            val firstUrl = PresetCommandBuilder.parseUrls(url).firstOrNull() ?: url.trim()

            fun buildPreviewReq(opts: DownloadExecutionOptions): YoutubeDLRequest = YoutubeDLRequest(firstUrl).apply {
                addOption("-J")
                if (opts.allowPlaylist) {
                    addOption("--flat-playlist")
                } else {
                    addOption("--no-playlist")
                }
                addOption("--retries", opts.retries.coerceIn(1, 10).toString())
                addOption("--socket-timeout", opts.socketTimeoutSec.coerceIn(5, 120).toString())
                opts.cacheDirPath?.takeIf { it.isNotBlank() }?.let { addOption("--cache-dir", it) }
                if (opts.enableRemoteEjs) addOption("--remote-components", "ejs:github")
                if (opts.forceIpv4) addOption("--force-ipv4")
                if (opts.geoBypass) addOption("--geo-bypass")
                if (opts.noCheckCertificates) addOption("--no-check-certificates")
                opts.cookieFilePath?.takeIf { it.isNotBlank() }?.let { addOption("--cookies", it) }
                opts.userAgent.trim().takeIf { it.isNotBlank() }?.let { addOption("--user-agent", it) }
                opts.referer.trim().takeIf { it.isNotBlank() }?.let { addOption("--referer", it) }
                opts.proxyUrl.trim().takeIf { it.isNotBlank() }?.let { addOption("--proxy", it) }
                PresetCommandBuilder.buildExtractorArgsString(opts)?.let { addOption("--extractor-args", it) }
            }

            fun runPreviewOnce(opts: DownloadExecutionOptions): String {
                val procId = UUID.randomUUID().toString()
                activeProcessId = procId
                val response = try {
                    YoutubeDL.getInstance().execute(buildPreviewReq(opts), procId, false, null)
                } catch (_: YoutubeDL.CanceledException) {
                    throw CancellationException("Preview canceled")
                } finally {
                    if (activeProcessId == procId) activeProcessId = null
                }
                return response.out.lineSequence()
                    .map { it.trim() }
                    .lastOrNull { it.startsWith("{") }
                    ?: throw YoutubeDLException("Unable to parse media info")
            }

            var currentOpts = baseOptions
            val jsonLine = try {
                runPreviewOnce(currentOpts)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: YoutubeDLException) {
                var lastErr: Throwable = e
                val msg = e.message.orEmpty()
                if (msg.contains("no such option: --remote-components", ignoreCase = true)) {
                    currentOpts = currentOpts.copy(enableRemoteEjs = false)
                } else if (!updateAttemptedThisSession) {
                    runCatching { updateEngineInternal(appCtx, YtDlpUpdateChannel.STABLE, timeoutMs = 20_000L) }
                }
                var recovered: String? = runCatching { runPreviewOnce(currentOpts) }
                    .onFailure { if (it is CancellationException) throw it else lastErr = it }
                    .getOrNull()

                if (recovered == null && currentOpts.youtubeClient == YoutubeClientMode.AUTO) {
                    val fallbackClientArgs = if (currentOpts.cookieFilePath.isNullOrBlank()) {
                        "player_client=android_vr,web_safari"
                    } else {
                        "player_client=tv_downgraded,web_safari"
                    }
                    val fallbackOpts = currentOpts.copy(extraExtractorArgs = fallbackClientArgs)
                    recovered = runCatching { runPreviewOnce(fallbackOpts) }
                        .onFailure { if (it is CancellationException) throw it else lastErr = it }
                        .getOrNull()
                    if (recovered != null) {
                        currentOpts = fallbackOpts
                    }
                }
                recovered ?: throw YtDlpClassifiedException(
                    YtDlpErrorClassifier.classify(buildDiagnosticText(appCtx, lastErr, emptyList())),
                    lastErr,
                )
            }

            DumpJsonPreviewParser.parse(
                jsonText = jsonLine,
                sourceUrl = firstUrl,
                audioPreference = currentOpts.audioPreference,
            ) ?: throw YoutubeDLException("Unable to parse media info")
        }

        override suspend fun execute(
            context: Context,
            url: String,
            preset: DownloadPreset,
            customArgs: String,
            workDir: File,
            options: DownloadExecutionOptions,
            onProgress: (percent: Float, etaSec: Long, line: String) -> Unit,
        ): DownloadedArtifact = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            if (!workDir.exists()) workDir.mkdirs()
            onProgress(0f, -1L, "yt-dlp...")
            ensureInitialized(appCtx)

            val urls = PresetCommandBuilder.parseUrls(url)
            val baseOptions = prepareOptionsWithCache(appCtx, options)
            val capturedLines = Collections.synchronizedList(ArrayList<String>())

            fun runDownloadAttempt(attemptOpts: DownloadExecutionOptions): DownloadedArtifact {
                val template = PresetCommandBuilder.resolveOutputTemplate(workDir, attemptOpts)
                val req = if (urls.size > 1) {
                    YoutubeDLRequest(urls)
                } else {
                    YoutubeDLRequest(urls.firstOrNull() ?: url.trim())
                }
                val plan = PresetCommandBuilder.buildExecutionPlan(preset, customArgs, template, attemptOpts)
                for ((flag, value) in plan.options) {
                    if (value != null) {
                        req.addOption(flag, value)
                    } else {
                        req.addOption(flag)
                    }
                }
                if (plan.rawCommands.isNotEmpty()) {
                    req.addCommands(plan.rawCommands)
                }

                val procId = UUID.randomUUID().toString()
                activeProcessId = procId
                try {
                    YoutubeDL.getInstance().execute(req, procId, false) { progress, etaInSeconds, line ->
                        val text = line.orEmpty()
                        if (text.isNotBlank()) {
                            synchronized(capturedLines) {
                                if (capturedLines.size >= 40) capturedLines.removeAt(0)
                                capturedLines.add(text)
                            }
                        }
                        onProgress(progress.coerceIn(0f, 100f), etaInSeconds, text)
                    }
                } catch (_: YoutubeDL.CanceledException) {
                    throw CancellationException("Download canceled")
                } finally {
                    if (activeProcessId == procId) activeProcessId = null
                }

                val artifact = collectArtifact(workDir, urls, url, preset)
                if (artifact.mediaItems.isEmpty() && artifact.imageFile == null && artifact.subtitleFiles.isEmpty()) {
                    throw YoutubeDLException("No downloadable media output produced by yt-dlp.")
                }
                return artifact
            }

            var currentOpts = baseOptions
            try {
                runDownloadAttempt(currentOpts)
            } catch (ce: CancellationException) {
                throw ce
            } catch (firstErr: Throwable) {
                var lastErr: Throwable = firstErr
                val firstMsg = firstErr.message.orEmpty()

                if (firstMsg.contains("no such option: --remote-components", ignoreCase = true)) {
                    currentOpts = currentOpts.copy(enableRemoteEjs = false)
                } else if (preset != DownloadPreset.CUSTOM_SCRIPT && !updateAttemptedThisSession) {
                    onProgress(0f, -1L, appCtx.getString(R.string.ytdlp_auto_updating_retry))
                    runCatching { updateEngineInternal(appCtx, YtDlpUpdateChannel.STABLE, timeoutMs = 20_000L) }
                }

                cleanPartialFiles(workDir)
                var recovered: DownloadedArtifact? = runCatching { runDownloadAttempt(currentOpts) }
                    .onFailure { if (it is CancellationException) throw it else lastErr = it }
                    .getOrNull()

                if (recovered == null &&
                    preset != DownloadPreset.CUSTOM_SCRIPT &&
                    currentOpts.youtubeClient == YoutubeClientMode.AUTO
                ) {
                    onProgress(0f, -1L, appCtx.getString(R.string.ytdlp_fallback_client_retry))
                    val fallbackClientArgs = if (currentOpts.cookieFilePath.isNullOrBlank()) {
                        "player_client=android_vr,web_safari"
                    } else {
                        "player_client=tv_downgraded,web_safari"
                    }
                    val fallbackOpts = currentOpts.copy(
                        extraExtractorArgs = fallbackClientArgs,
                        concurrentFragments = 1,
                    )
                    cleanPartialFiles(workDir)
                    recovered = runCatching { runDownloadAttempt(fallbackOpts) }
                        .onFailure { if (it is CancellationException) throw it else lastErr = it }
                        .getOrNull()
                }

                if (recovered != null) {
                    recovered
                } else {
                    val snapshot = synchronized(capturedLines) { capturedLines.toList() }
                    val diagText = buildDiagnosticText(appCtx, lastErr, snapshot)
                    throw YtDlpClassifiedException(YtDlpErrorClassifier.classify(diagText), lastErr)
                }
            }
        }

        private fun cleanPartialFiles(workDir: File) {
            workDir.listFiles()?.forEach { f ->
                if (f.isFile && (f.name.endsWith(".part") || f.name.endsWith(".ytdl") || f.length() == 0L)) {
                    runCatching { f.delete() }
                }
            }
        }

        private fun buildDiagnosticText(
            context: Context,
            err: Throwable,
            stdoutTail: List<String>,
        ): String {
            val errMsg = err.message.orEmpty().trim()
            val causeChain = buildList {
                var cur: Throwable? = err
                while (cur != null && size < 4) {
                    val msg = cur.message?.trim().orEmpty()
                    add(if (msg.isNotBlank()) "${cur::class.java.simpleName}: $msg" else cur::class.java.simpleName)
                    cur = cur.cause
                }
            }.joinToString(" <- ")
            val version = runCatching { YoutubeDL.getInstance().version(context) }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: "2025.11.12 (bundled)"
            val tailText = stdoutTail.takeLast(8).joinToString("\n")
            return buildList {
                if (errMsg.isNotBlank()) add(errMsg)
                if (tailText.isNotBlank()) add(tailText)
                add("[yt-dlp $version] $causeChain")
            }.distinct().joinToString("\n")
        }

        private fun collectArtifact(
            workDir: File,
            urls: List<String>,
            rawUrl: String,
            preset: DownloadPreset,
        ): DownloadedArtifact {
            val files = workDir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: emptyList()
            val audioOnlyExts = setOf("opus", "ogg", "m4a", "aac", "mp3", "flac", "wav", "mka")
            val videoOrContainerExts = setOf("mp4", "mkv", "mov", "3gp")
            val mediaExts = audioOnlyExts + videoOrContainerExts + "webm"
            val imageExts = setOf("jpg", "jpeg", "png", "webp")
            val subtitleExts = setOf("vtt", "srt", "lrc", "ass")

            val infoJsonMap = files
                .filter { it.name.endsWith(".info.json", ignoreCase = true) }
                .associateBy { it.name.removeSuffix(".info.json") }

            val fallbackMeta: AudioMetadata? = infoJsonMap.values.firstOrNull()?.let { jf ->
                runCatching { InfoJsonMetadataParser.parse(jf.readText(Charsets.UTF_8)) }.getOrNull()
            }

            val mediaFiles = files
                .filter { it.extension.lowercase() in mediaExts }
                .sortedByDescending { it.length() }

            val mediaItems = mediaFiles.map { mediaFile ->
                val ext = mediaFile.extension.lowercase()
                val isVideo = ext in videoOrContainerExts ||
                    (ext == "webm" && preset == DownloadPreset.VIDEO_MUXED)
                val mime = mimeForExtension(ext, isVideo)
                val base = mediaFile.nameWithoutExtension
                val matchedJson = infoJsonMap[base]
                val itemMeta = matchedJson?.let { jf ->
                    runCatching { InfoJsonMetadataParser.parse(jf.readText(Charsets.UTF_8)) }.getOrNull()
                } ?: fallbackMeta
                DownloadedMediaItem(
                    file = mediaFile,
                    displayName = mediaFile.name,
                    isVideo = isVideo,
                    mimeType = mime,
                    metadata = itemMeta,
                )
            }

            val rawImages = files
                .filter { it.extension.lowercase() in imageExts }
                .sortedByDescending { it.length() }
            val normalizedImages = rawImages.map { normalizeImageForAndroidAndTags(it) }.distinctBy { it.absolutePath }
            val primaryImage = normalizedImages.firstOrNull()
            val imageMime = when (primaryImage?.extension?.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> null
            }

            val subtitleFiles = files.filter { it.extension.lowercase() in subtitleExts }
            val primaryMedia = mediaItems.firstOrNull()

            return DownloadedArtifact(
                audioFile = primaryMedia?.file,
                audioDisplayName = primaryMedia?.displayName,
                imageFile = primaryImage,
                imageDisplayName = primaryImage?.name,
                imageMimeType = imageMime,
                metadata = primaryMedia?.metadata ?: fallbackMeta,
                sourceUrl = urls.firstOrNull() ?: rawUrl.trim(),
                mediaItems = mediaItems,
                imageFiles = normalizedImages,
                subtitleFiles = subtitleFiles,
            )
        }

        private fun mimeForExtension(ext: String, isVideo: Boolean): String = when (ext) {
            "opus" -> "audio/opus"
            "ogg" -> "audio/ogg"
            "webm" -> if (isVideo) "video/webm" else "audio/webm"
            "m4a", "aac" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "mka" -> "audio/x-matroska"
            "mp4" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "mov" -> "video/quicktime"
            else -> if (isVideo) "video/*" else "audio/*"
        }

        private fun normalizeImageForAndroidAndTags(file: File): File {
            if (!file.extension.equals("webp", ignoreCase = true)) return file
            return runCatching {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return file
                val jpgFile = File(file.parentFile, "${file.nameWithoutExtension}.jpg")
                FileOutputStream(jpgFile).use { out ->
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
                }
                bmp.recycle()
                if (jpgFile.exists() && jpgFile.length() > 0L) jpgFile else file
            }.getOrDefault(file)
        }

        override fun cancel() {
            activeProcessId?.let { id ->
                runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            }
        }
    }
}
