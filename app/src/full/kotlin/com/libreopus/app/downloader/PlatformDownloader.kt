// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.libreopus.audio.api.AudioMetadata
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object PlatformDownloader {
    private val initMutex = Mutex()

    @Volatile
    private var initialized = false

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

        override suspend fun updateEngine(
            context: Context,
            channel: YtDlpUpdateChannel,
        ): YtDlpUpdateOutcome = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            ensureInitialized(appCtx)
            val ytChannel = when (channel) {
                YtDlpUpdateChannel.STABLE -> YoutubeDL.UpdateChannel.STABLE
                YtDlpUpdateChannel.NIGHTLY -> YoutubeDL.UpdateChannel.NIGHTLY
            }
            val result = YoutubeDL.getInstance().updateYoutubeDL(appCtx, ytChannel)
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

        override suspend fun fetchPreview(
            context: Context,
            url: String,
            options: DownloadExecutionOptions,
        ): MediaTrackPreview = withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            ensureInitialized(appCtx)

            val firstUrl = PresetCommandBuilder.parseUrls(url).firstOrNull() ?: url.trim()
            val req = YoutubeDLRequest(firstUrl).apply {
                addOption("-J")
                if (options.allowPlaylist) {
                    addOption("--flat-playlist")
                } else {
                    addOption("--no-playlist")
                }
                addOption("--retries", options.retries.coerceIn(1, 10).toString())
                addOption("--socket-timeout", options.socketTimeoutSec.coerceIn(5, 120).toString())
                if (options.forceIpv4) addOption("--force-ipv4")
                if (options.geoBypass) addOption("--geo-bypass")
                if (options.noCheckCertificates) addOption("--no-check-certificates")
                options.cookieFilePath?.takeIf { it.isNotBlank() }?.let { addOption("--cookies", it) }
                options.userAgent.trim().takeIf { it.isNotBlank() }?.let { addOption("--user-agent", it) }
                options.referer.trim().takeIf { it.isNotBlank() }?.let { addOption("--referer", it) }
                options.proxyUrl.trim().takeIf { it.isNotBlank() }?.let { addOption("--proxy", it) }
                PresetCommandBuilder.buildExtractorArgsString(options)?.let { addOption("--extractor-args", it) }
            }

            val procId = UUID.randomUUID().toString()
            activeProcessId = procId
            val response = try {
                YoutubeDL.getInstance().execute(req, procId, false, null)
            } catch (_: YoutubeDL.CanceledException) {
                throw CancellationException("Preview canceled")
            } catch (e: YoutubeDLException) {
                throw YtDlpClassifiedException(YtDlpErrorClassifier.classify(e.message.orEmpty()), e)
            } finally {
                if (activeProcessId == procId) activeProcessId = null
            }

            val jsonLine = response.out.lineSequence()
                .map { it.trim() }
                .lastOrNull { it.startsWith("{") }
                ?: throw YoutubeDLException("Unable to parse media info")

            DumpJsonPreviewParser.parse(
                jsonText = jsonLine,
                sourceUrl = firstUrl,
                audioPreference = options.audioPreference,
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
            if (!workDir.exists()) workDir.mkdirs()
            onProgress(0f, -1L, "yt-dlp...")
            ensureInitialized(context)

            val urls = PresetCommandBuilder.parseUrls(url)
            val template = PresetCommandBuilder.resolveOutputTemplate(workDir, options)
            val req = if (urls.size > 1) YoutubeDLRequest(urls) else YoutubeDLRequest(urls.firstOrNull() ?: url.trim())
            val plan = PresetCommandBuilder.buildExecutionPlan(preset, customArgs, template, options)
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
                    onProgress(progress.coerceIn(0f, 100f), etaInSeconds, line.orEmpty())
                }
            } catch (_: YoutubeDL.CanceledException) {
                throw CancellationException("Download canceled")
            } catch (e: YoutubeDLException) {
                throw YtDlpClassifiedException(YtDlpErrorClassifier.classify(e.message.orEmpty()), e)
            } finally {
                if (activeProcessId == procId) activeProcessId = null
            }

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

            DownloadedArtifact(
                audioFile = primaryMedia?.file,
                audioDisplayName = primaryMedia?.displayName,
                imageFile = primaryImage,
                imageDisplayName = primaryImage?.name,
                imageMimeType = imageMime,
                metadata = primaryMedia?.metadata ?: fallbackMeta,
                sourceUrl = urls.firstOrNull() ?: url.trim(),
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
