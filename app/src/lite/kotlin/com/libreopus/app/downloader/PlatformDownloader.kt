// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import java.io.File

object PlatformDownloader {
    fun create(): MediaDownloader = object : MediaDownloader {
        override val isAvailable: Boolean = false

        override suspend fun getEngineStatus(context: Context): YtDlpEngineStatus =
            YtDlpEngineStatus(available = false, initialized = false)

        override suspend fun updateEngine(
            context: Context,
            channel: YtDlpUpdateChannel,
        ): YtDlpUpdateOutcome = YtDlpUpdateOutcome(status = YtDlpUpdateStatus.UNAVAILABLE)

        override suspend fun fetchPreview(
            context: Context,
            url: String,
            options: DownloadExecutionOptions,
        ): MediaTrackPreview {
            throw UnsupportedOperationException("Downloader is only enabled in the full flavor")
        }

        override suspend fun execute(
            context: Context,
            url: String,
            preset: DownloadPreset,
            customArgs: String,
            workDir: File,
            options: DownloadExecutionOptions,
            onProgress: (Float, Long, String) -> Unit,
        ): DownloadedArtifact {
            throw UnsupportedOperationException("Downloader is only enabled in the full flavor")
        }

        override fun cancel() = Unit
    }
}
