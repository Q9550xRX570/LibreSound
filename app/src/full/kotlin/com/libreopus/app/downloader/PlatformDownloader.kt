// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
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

        override suspend fun execute(
            context: Context,
            url: String,
            preset: DownloadPreset,
            customArgs: String,
            workDir: File,
            onProgress: (percent: Float, etaSec: Long, line: String) -> Unit,
        ): DownloadedArtifact = withContext(Dispatchers.IO) {
            if (!workDir.exists()) workDir.mkdirs()
            onProgress(0f, -1L, "yt-dlp...")
            ensureInitialized(context)

            val template = File(workDir, "%(title).80B.%(ext)s").absolutePath
            val req = YoutubeDLRequest(url.trim())
            val args = PresetCommandBuilder.buildArguments(preset, customArgs, template)
            for ((flag, value) in args) {
                if (value != null) {
                    req.addOption(flag, value)
                } else {
                    req.addOption(flag)
                }
            }

            val procId = UUID.randomUUID().toString()
            activeProcessId = procId
            try {
                YoutubeDL.getInstance().execute(req, procId) { progress, etaInSeconds, line ->
                    onProgress(progress.coerceIn(0f, 100f), etaInSeconds, line.orEmpty())
                }
            } finally {
                if (activeProcessId == procId) activeProcessId = null
            }

            val files = workDir.listFiles()?.filter { it.isFile && it.length() > 0L } ?: emptyList()
            val audioExts = setOf("opus", "ogg", "webm", "m4a", "aac", "mp3", "flac", "wav", "mp4", "mkv")
            val imageExts = setOf("jpg", "jpeg", "png", "webp")

            val audioFile = files
                .filter { it.extension.lowercase() in audioExts }
                .maxByOrNull { it.length() }
            val imageFile = files
                .filter { it.extension.lowercase() in imageExts }
                .maxByOrNull { it.length() }

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
            )
        }

        override fun cancel() {
            activeProcessId?.let { id ->
                runCatching { YoutubeDL.getInstance().destroyProcessById(id) }
            }
        }
    }
}
