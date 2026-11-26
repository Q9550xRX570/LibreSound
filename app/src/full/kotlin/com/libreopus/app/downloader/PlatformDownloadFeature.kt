// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.libreopus.app.R
import com.libreopus.app.ui.screens.DownloadScreen
import com.libreopus.app.viewmodel.DownloadUiState
import com.libreopus.app.viewmodel.DownloadViewModel
import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.MediaChapter
import java.io.File

private class FullDownloadHost(
    private val downloadVm: DownloadViewModel,
    private val downloadState: DownloadUiState,
) : PlatformDownloadHost {

    @Composable
    override fun HandleIncomingUrl(
        incomingUrl: String?,
        onNavigateToDownload: () -> Unit,
        onConsumeIncomingUrl: () -> Unit,
    ) {
        LaunchedEffect(incomingUrl) {
            if (incomingUrl != null) {
                downloadVm.setUrl(incomingUrl)
                onNavigateToDownload()
                onConsumeIncomingUrl()
            }
        }
    }

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
    ) {
        DownloadScreen(
            state = downloadState,
            viewModel = downloadVm,
            hasActiveEditorSession = hasActiveEditorSession,
            onOpenInEditor = onOpenLocalInEditor,
            onUseAsCover = onUseAsCover,
            onShareFile = onShareFile,
            onOpenHistoryUri = onOpenHistoryUri,
            onShareHistoryUri = onShareHistoryUri,
            onShowMessage = onShowMessage,
            modifier = modifier,
        )
    }

    @Composable
    override fun RenderYtDlpSettingsCard() {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.settings_ytdlp_title), style = MaterialTheme.typography.titleMedium)
                val verLabel = downloadState.engineStatus.version
                    ?: downloadState.engineStatus.versionName
                    ?: stringResource(R.string.settings_ytdlp_bundled)
                Text(
                    text = stringResource(R.string.settings_ytdlp_version, verLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(stringResource(R.string.settings_ytdlp_channel), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = downloadState.updateChannel == YtDlpUpdateChannel.STABLE,
                        onClick = { downloadVm.setUpdateChannel(YtDlpUpdateChannel.STABLE) },
                        enabled = !downloadState.isUpdatingEngine,
                        label = { Text(stringResource(R.string.settings_ytdlp_stable)) },
                    )
                    FilterChip(
                        selected = downloadState.updateChannel == YtDlpUpdateChannel.NIGHTLY,
                        onClick = { downloadVm.setUpdateChannel(YtDlpUpdateChannel.NIGHTLY) },
                        enabled = !downloadState.isUpdatingEngine,
                        label = { Text(stringResource(R.string.settings_ytdlp_nightly)) },
                    )
                }

                FilledTonalButton(
                    onClick = { downloadVm.updateEngine() },
                    enabled = !downloadState.isUpdatingEngine && !downloadState.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (downloadState.isUpdatingEngine) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_ytdlp_updating))
                    } else {
                        Text(stringResource(R.string.settings_ytdlp_update_btn))
                    }
                }

                downloadState.engineUpdateOutcome?.let { outcome ->
                    val msg = when (outcome.status) {
                        YtDlpUpdateStatus.UPDATED -> stringResource(
                            R.string.settings_ytdlp_updated,
                            outcome.version ?: outcome.versionName ?: "",
                        )
                        YtDlpUpdateStatus.ALREADY_UP_TO_DATE -> stringResource(R.string.settings_ytdlp_up_to_date)
                        YtDlpUpdateStatus.UNAVAILABLE -> ""
                    }
                    if (msg.isNotBlank()) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                downloadState.engineUpdateError?.let { err ->
                    Text(
                        text = err,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

object PlatformDownloadFeature {
    @Composable
    fun rememberHost(): PlatformDownloadHost {
        val downloadVm: DownloadViewModel = viewModel()
        val state by downloadVm.state.collectAsStateWithLifecycle()
        return remember(downloadVm, state) {
            FullDownloadHost(downloadVm, state)
        }
    }
}
