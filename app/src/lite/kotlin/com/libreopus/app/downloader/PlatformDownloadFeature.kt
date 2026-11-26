// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.MediaChapter
import java.io.File

private object NoOpDownloadHost : PlatformDownloadHost {
    @Composable
    override fun HandleIncomingUrl(
        incomingUrl: String?,
        onNavigateToDownload: () -> Unit,
        onConsumeIncomingUrl: () -> Unit,
    ) = Unit

    @Composable
    override fun RenderDownloadTab(
        hasActiveEditorSession: Boolean,
        onOpenLocalInEditor: (
            audioFile: File,
            displayName: String,
            coverFile: File?,
            coverMime: String?,
            metadata: AudioMetadata?,
            chapters: List<MediaChapter>,
        ) -> Unit,
        onUseAsCover: (imgFile: File, mime: String) -> Unit,
        onShareFile: (file: File, mime: String) -> Unit,
        onOpenHistoryUri: (uriStr: String, chapters: List<MediaChapter>) -> Unit,
        onShareHistoryUri: (uriStr: String, mime: String) -> Unit,
        onShowMessage: (String) -> Unit,
        modifier: Modifier,
    ) = Unit

    @Composable
    override fun RenderYtDlpSettingsCard() = Unit
}

object PlatformDownloadFeature {
    @Composable
    fun rememberHost(): PlatformDownloadHost = remember { NoOpDownloadHost }
}
