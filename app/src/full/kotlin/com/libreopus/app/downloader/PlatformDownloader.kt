// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.edit
import com.libreopus.app.R
import com.libreopus.audio.api.AudioMetadata
import com.libreopus.dsp.container.FlacMetadataCodec
import com.libreopus.dsp.container.LosslessOpusEditor
import com.libreopus.dsp.container.Mp4MetadataWriter
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
                addOption("--no-warnings")
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

                val artifact = collectArtifact(appCtx, workDir, urls, url, preset)
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

        override suspend fun fetchPreviewThumbnail(
            context: Context,
            thumbnailUrl: String,
        ): File? = withContext(Dispatchers.IO) {
            fetchOrCacheThumbnail(context.applicationContext, thumbnailUrl)
        }

        private fun fetchOrCacheThumbnail(context: Context, rawUrl: String?): File? {
            val cleanUrl = rawUrl?.trim().orEmpty()
            if (!cleanUrl.startsWith("https://", ignoreCase = true) && !cleanUrl.startsWith("http://", ignoreCase = true)) {
                return null
            }
            val thumbDir = File(context.cacheDir, "ytdlp_thumbs").apply { mkdirs() }
            val key = sha256HexPrefix(cleanUrl)
            val cached = File(thumbDir, "$key.jpg")
            if (cached.exists() && cached.isFile && cached.length() > 512L) {
                return cached
            }
            return runCatching {
                val rawBytes = downloadImageBytes(cleanUrl) ?: return null
                val jpegBytes = normalizeBytesToJpeg(rawBytes, maxEdgePx = 1280) ?: return null
                val tmp = File(thumbDir, "$key.tmp")
                FileOutputStream(tmp).use { it.write(jpegBytes) }
                if (!tmp.renameTo(cached)) {
                    cached.writeBytes(jpegBytes)
                    tmp.delete()
                }
                if (cached.exists() && cached.length() > 0L) cached else null
            }.getOrNull()
        }

        private fun downloadImageBytes(urlStr: String, maxBytes: Int = 6 * 1024 * 1024): ByteArray? {
            var currentUrl = urlStr
            repeat(3) {
                val conn = (URL(currentUrl).openConnection() as? HttpURLConnection) ?: return null
                try {
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                    val code = conn.responseCode
                    if (code in 300..399) {
                        val loc = conn.getHeaderField("Location")?.takeIf { it.isNotBlank() } ?: return null
                        currentUrl = URL(URL(currentUrl), loc).toString()
                        return@repeat
                    }
                    if (code !in 200..299) return null
                    conn.inputStream.use { input ->
                        val out = ByteArrayOutputStream()
                        val buf = ByteArray(16384)
                        var total = 0
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > maxBytes) return null
                            out.write(buf, 0, n)
                        }
                        return out.toByteArray()
                    }
                } finally {
                    conn.disconnect()
                }
            }
            return null
        }

        private fun normalizeBytesToJpeg(rawBytes: ByteArray, maxEdgePx: Int): ByteArray? {
            if (rawBytes.isEmpty()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, bounds)
            val w = bounds.outWidth
            val h = bounds.outHeight
            if (w <= 0 || h <= 0) return null
            var sampleSize = 1
            var longest = maxOf(w, h)
            while (longest / sampleSize > maxEdgePx) {
                sampleSize *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bmp = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size, opts) ?: return null
            return try {
                val out = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
                out.toByteArray().takeIf { it.isNotEmpty() }
            } finally {
                bmp.recycle()
            }
        }

        private fun sha256HexPrefix(text: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            return buildString(24) {
                for (i in 0 until 12) {
                    val b = digest[i].toInt() and 0xFF
                    append(((b ushr 4) and 0xF).toString(16))
                    append((b and 0xF).toString(16))
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
            appCtx: Context,
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

            val infoJsonDetailsMap = files
                .filter { it.name.endsWith(".info.json", ignoreCase = true) }
                .associate { jf ->
                    val details = runCatching { InfoJsonMetadataParser.parseDetails(jf.readText(Charsets.UTF_8)) }.getOrNull()
                    jf.name.removeSuffix(".info.json") to details
                }

            val fallbackDetails = infoJsonDetailsMap.values.firstOrNull { it != null }
            val rawImages = files
                .filter { it.extension.lowercase() in imageExts }
                .sortedByDescending { it.length() }
            val normalizedImages = rawImages.map { normalizeImageForAndroidAndTags(it) }.distinctBy { it.absolutePath }

            val coverFile: File? = normalizedImages.firstOrNull() ?: run {
                val cachedThumb = fetchOrCacheThumbnail(appCtx, fallbackDetails?.thumbnailUrl)
                if (cachedThumb != null && cachedThumb.exists()) {
                    val localCover = File(workDir, "cover_thumb.jpg")
                    runCatching {
                        cachedThumb.copyTo(localCover, overwrite = true)
                        localCover
                    }.getOrNull()
                } else {
                    null
                }
            }

            val imageMime = when (coverFile?.extension?.lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "jpg", "jpeg" -> "image/jpeg"
                else -> null
            }
            val coverBytes = coverFile?.takeIf { it.exists() && it.length() in 1L..4_000_000L }?.let { cf ->
                runCatching { cf.readBytes() }.getOrNull()
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
                val itemDetails = infoJsonDetailsMap[base] ?: fallbackDetails
                val baseMeta = itemDetails?.metadata
                val itemThumbFile = if (itemDetails?.thumbnailUrl != null &&
                    itemDetails.thumbnailUrl != fallbackDetails?.thumbnailUrl
                ) {
                    fetchOrCacheThumbnail(appCtx, itemDetails.thumbnailUrl)
                } else {
                    null
                }
                val itemCoverBytes = itemThumbFile?.takeIf { it.exists() && it.length() in 1L..4_000_000L }
                    ?.let { runCatching { it.readBytes() }.getOrNull() } ?: coverBytes
                val itemCoverMime = if (itemThumbFile != null) "image/jpeg" else (imageMime ?: "image/jpeg")

                val finalMeta = if (baseMeta != null || itemCoverBytes != null) {
                    val m = baseMeta ?: AudioMetadata(title = mediaFile.nameWithoutExtension)
                    if (itemCoverBytes != null) {
                        m.copy(coverArtBytes = itemCoverBytes, coverArtMimeType = itemCoverMime)
                    } else {
                        m
                    }
                } else {
                    null
                }

                if (finalMeta != null && !finalMeta.isEmpty) {
                    embedMetadataIntoDownloadedFile(mediaFile, workDir, finalMeta)
                }

                DownloadedMediaItem(
                    file = mediaFile,
                    displayName = mediaFile.name,
                    isVideo = isVideo,
                    mimeType = mime,
                    metadata = finalMeta,
                    durationSec = itemDetails?.durationSec ?: 0,
                    formatLabel = itemDetails?.formatLabel?.ifBlank { ext.uppercase() } ?: ext.uppercase(),
                )
            }

            val subtitleFiles = files.filter { it.extension.lowercase() in subtitleExts }
            val primaryMedia = mediaItems.firstOrNull()
            val saveToPictures = preset == DownloadPreset.IMAGE_ONLY ||
                preset == DownloadPreset.AUDIO_AND_IMAGE ||
                (normalizedImages.isNotEmpty() && preset == DownloadPreset.CUSTOM_SCRIPT)

            return DownloadedArtifact(
                audioFile = primaryMedia?.file,
                audioDisplayName = primaryMedia?.displayName,
                imageFile = coverFile,
                imageDisplayName = coverFile?.name,
                imageMimeType = imageMime,
                saveCoverToPictures = saveToPictures,
                metadata = primaryMedia?.metadata ?: fallbackDetails?.metadata,
                sourceUrl = urls.firstOrNull() ?: rawUrl.trim(),
                mediaItems = mediaItems,
                imageFiles = if (normalizedImages.isNotEmpty()) normalizedImages else listOfNotNull(coverFile),
                subtitleFiles = subtitleFiles,
            )
        }

        private fun embedMetadataIntoDownloadedFile(
            mediaFile: File,
            workDir: File,
            metadata: AudioMetadata,
        ) {
            val header = ByteArray(12)
            val read = runCatching { FileInputStream(mediaFile).use { it.read(header) } }.getOrDefault(0)
            if (read < 8) return
            val sig4 = String(header, 0, 4, Charsets.US_ASCII)
            val isOgg = sig4 == "OggS"
            val isFlac = sig4 == "fLaC"
            val isMp4 = String(header, 4, 4, Charsets.US_ASCII) == "ftyp"
            if (!isOgg && !isFlac && !isMp4) return

            val tmpFile = File(workDir, "${mediaFile.name}.tagged.tmp")
            val ok = try {
                val wrote = BufferedOutputStream(FileOutputStream(tmpFile), 262144).use { out ->
                    when {
                        isOgg -> LosslessOpusEditor.rewriteTagsLossless(mediaFile, metadata, out)
                        isFlac -> FlacMetadataCodec.rewriteMetadata(mediaFile, metadata, out)
                        isMp4 -> Mp4MetadataWriter.writeMetadata(mediaFile, metadata, out)
                        else -> false
                    }
                }
                wrote && tmpFile.exists() && tmpFile.length() >= 64L &&
                    (!isMp4 || Mp4MetadataWriter.readMetadata(tmpFile) != null)
            } catch (_: Exception) {
                false
            }
            if (ok) {
                if (!tmpFile.renameTo(mediaFile)) {
                    runCatching { tmpFile.copyTo(mediaFile, overwrite = true) }
                    tmpFile.delete()
                }
            } else {
                tmpFile.delete()
            }
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
