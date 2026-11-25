// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import java.io.File

object PlatformDownloader {
    fun create(): MediaDownloader = object : MediaDownloader {
        override val isAvailable: Boolean = false

        override suspend fun execute(
            context: Context,
            url: String,
            preset: DownloadPreset,
            customArgs: String,
            workDir: File,
            onProgress: (Float, Long, String) -> Unit,
        ): DownloadedArtifact {
            throw UnsupportedOperationException("Downloader is only enabled in the full flavor")
        }

        override fun cancel() = Unit
    }
}
