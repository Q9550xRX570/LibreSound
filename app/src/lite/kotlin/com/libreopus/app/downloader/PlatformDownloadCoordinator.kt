// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object PlatformDownloadCoordinator {
    private val stubCoordinator = object : DownloadExecutionCoordinator {
        private val _state = MutableStateFlow(BackgroundDownloadState())
        override val state: StateFlow<BackgroundDownloadState> = _state.asStateFlow()

        override fun startDownload(
            context: Context,
            url: String,
            preset: DownloadPreset,
            customArgs: String,
            options: DownloadExecutionOptions,
            notificationTitle: String,
        ) = Unit

        override fun cancelDownload() = Unit

        override fun clearOutcome() = Unit
    }

    @Suppress("UNUSED_PARAMETER")
    fun get(context: Context): DownloadExecutionCoordinator = stubCoordinator
}
