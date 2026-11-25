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

            val req = YoutubeDLRequest(url.trim()).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--retries", options.retries.coerceIn(1, 10).toString())
                addOption("--socket-timeout", options.socketTimeoutSec.coerceIn(5, 120).toString())
                if (options.forceIpv4) addOption("--force-ipv4")
                if (options.geoBypass) addOption("--geo-bypass")
                options.cookieFilePath?.takeIf { it.isNotBlank() }?.let { addOption("--cookies", it) }
                options.userAgent.trim().takeIf { it.isNotBlank() }?.let { addOption("--user-agent", it) }
                options.proxyUrl.trim().takeIf { it.isNotBlank() }?.let { addOption("--proxy", it) }
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

            val firstJsonLine = response.out.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("{") }
                ?: throw YoutubeDLException("Unable to parse media info")

            val root = runCatching { SimpleJsonParser.parse(firstJsonLine) as? Map<*, *> }.getOrNull()
                ?: throw YoutubeDLException("Unable to parse media info")

            val formats = (root["formats"] as? List<*>)
                .orEmpty()
                .mapNotNull { it as? Map<*, *> }

            val audioFormats = formats.filter { f ->
                val acodec = f["acodec"]?.toString().orEmpty()
                val vcodec = f["vcodec"]?.toString().orEmpty()
                acodec.isNotBlank() && acodec != "none" && acodec != "null" &&
                    (vcodec.isBlank() || vcodec == "none" || vcodec == "null")
            }
            val preferredExt = when (options.audioPreference) {
                AudioFormatPreference.OPUS_WEBM -> "webm"
                AudioFormatPreference.M4A_AAC -> "m4a"
                AudioFormatPreference.AUTO_BEST -> null
            }
            val bestFormat = audioFormats
                .filter { preferredExt == null || it["ext"]?.toString().equals(preferredExt, ignoreCase = true) }
                .maxByOrNull { (it["abr"] as? Number)?.toDouble() ?: 0.0 }
                ?: audioFormats.maxByOrNull { (it["abr"] as? Number)?.toDouble() ?: 0.0 }

            val approxSize = ((bestFormat?.get("filesize") as? Number)?.toLong()?.takeIf { it > 0L }
                ?: (bestFormat?.get("filesize_approx") as? Number)?.toLong()?.takeIf { it > 0L }
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

            MediaTrackPreview(
                title = title,
                uploader = uploader,
                durationSec = ((root["duration"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
                thumbnailUrl = root["thumbnail"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                webpageUrl = root["webpage_url"]?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: url.trim(),
                extractor = root["extractor_key"]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: root["extractor"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                audioExt = bestFormat?.get("ext")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                    ?: root["ext"]?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                approxFileSizeBytes = approxSize,
            )
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

            val template = File(workDir, "%(title).80B.%(ext)s").absolutePath
            val req = YoutubeDLRequest(url.trim())
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
            val audioExts = setOf("opus", "ogg", "webm", "m4a", "aac", "mp3", "flac", "wav", "mp4", "mkv")
            val imageExts = setOf("jpg", "jpeg", "png", "webp")

            val infoJsonFile = files.firstOrNull { it.name.endsWith(".info.json", ignoreCase = true) }
            val parsedMeta: AudioMetadata? = infoJsonFile?.let { jf ->
                runCatching { InfoJsonMetadataParser.parse(jf.readText(Charsets.UTF_8)) }.getOrNull()
            }

            val audioFile = files
                .filter { it.extension.lowercase() in audioExts }
                .maxByOrNull { it.length() }

            val rawImageFile = files
                .filter { it.extension.lowercase() in imageExts }
                .maxByOrNull { it.length() }

            val imageFile = rawImageFile?.let { normalizeImageForAndroidAndTags(it) }

            val imageMime = when (imageFile?.extension?.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> null
            }

            DownloadedArtifact(
                audioFile = audioFile,
                audioDisplayName = audioFile?.name,
                imageFile = imageFile,
                imageDisplayName = imageFile?.name,
                imageMimeType = imageMime,
                metadata = parsedMeta,
                sourceUrl = url.trim(),
            )
        }

        private fun normalizeImageForAndroidAndTags(file: File): File {
            if (!file.extension.equals("webp", ignoreCase = true)) return file
            return runCatching {
                val bmp = BitmapFactory.decodeFile(file.absolutePath) ?: return file
                val baseName = file.nameWithoutExtension
                val jpgFile = File(file.parentFile, "$baseName.jpg")
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
