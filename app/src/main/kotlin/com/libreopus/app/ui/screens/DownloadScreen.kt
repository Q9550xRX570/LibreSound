// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.libreopus.app.R
import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.PresetCommandBuilder
import com.libreopus.app.viewmodel.DownloadUiState
import com.libreopus.app.viewmodel.DownloadViewModel
import java.io.File

@Composable
fun DownloadScreen(
    state: DownloadUiState,
    viewModel: DownloadViewModel,
    hasActiveEditorSession: Boolean,
    onOpenInEditor: (audioFile: File, displayName: String, coverFile: File?, coverMime: String?) -> Unit,
    onUseAsCover: (imageFile: File, mimeType: String) -> Unit,
    onShareFile: (file: File, mimeType: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var showLogs by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.download_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.download_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            OutlinedTextField(
                value = state.url,
                onValueChange = { viewModel.setUrl(it) },
                label = { Text(stringResource(R.string.download_url_hint)) },
                singleLine = true,
                enabled = !state.isRunning,
                trailingIcon = {
                    Row {
                        if (state.url.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setUrl("") }, enabled = !state.isRunning) {
                                Icon(Icons.Rounded.Clear, contentDescription = stringResource(R.string.download_clear))
                            }
                        }
                        IconButton(
                            onClick = {
                                val clip = clipboard.getText()?.text?.trim()
                                if (!clip.isNullOrBlank()) viewModel.setUrl(clip)
                            },
                            enabled = !state.isRunning,
                        ) {
                            Icon(Icons.Rounded.ContentPaste, contentDescription = stringResource(R.string.download_paste))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Text(stringResource(R.string.download_presets_label), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetCard(
                    selected = state.preset == DownloadPreset.BEST_AUDIO,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.AudioFile,
                    title = stringResource(R.string.download_preset_best_audio),
                    subtitle = stringResource(R.string.download_preset_best_audio_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.BEST_AUDIO) },
                )
                PresetCard(
                    selected = state.preset == DownloadPreset.IMAGE_ONLY,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Image,
                    title = stringResource(R.string.download_preset_image_only),
                    subtitle = stringResource(R.string.download_preset_image_only_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.IMAGE_ONLY) },
                )
                PresetCard(
                    selected = state.preset == DownloadPreset.AUDIO_AND_IMAGE,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Album,
                    title = stringResource(R.string.download_preset_audio_image),
                    subtitle = stringResource(R.string.download_preset_audio_image_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.AUDIO_AND_IMAGE) },
                )
                PresetCard(
                    selected = state.preset == DownloadPreset.CUSTOM_SCRIPT,
                    enabled = !state.isRunning,
                    icon = Icons.Rounded.Code,
                    title = stringResource(R.string.download_preset_custom),
                    subtitle = stringResource(R.string.download_preset_custom_desc),
                    onClick = { viewModel.selectPreset(DownloadPreset.CUSTOM_SCRIPT) },
                )
            }
        }

        item {
            if (state.preset == DownloadPreset.CUSTOM_SCRIPT) {
                OutlinedTextField(
                    value = state.customArgs,
                    onValueChange = { viewModel.setCustomArgs(it) },
                    label = { Text(stringResource(R.string.download_custom_args_label)) },
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Text(
                    text = stringResource(R.string.download_command_preview, PresetCommandBuilder.formatPreview(state.preset)),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            if (state.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelDownload() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.cancel))
                }
            } else {
                Button(
                    onClick = { viewModel.startDownload() },
                    enabled = state.url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.download_start))
                }
            }
        }

        if (state.isRunning) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(
                                text = if (state.progress <= 0f) stringResource(R.string.download_initializing) else "${(state.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                            )
                            if (state.etaSeconds > 0L) {
                                Text(stringResource(R.string.download_eta, state.etaSeconds.toInt()), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        if (state.progress <= 0f) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }

        if (state.error != null) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = state.error,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        val res = state.result
        if (res != null && (res.audioFile != null || res.imageFile != null)) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val imgFile = res.imageFile
                        if (imgFile != null) {
                            val bmp = remember(imgFile.absolutePath) {
                                runCatching { BitmapFactory.decodeFile(imgFile.absolutePath)?.asImageBitmap() }.getOrNull()
                            }
                            if (bmp != null) {
                                Image(
                                    bitmap = bmp,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(170.dp)
                                        .clip(RoundedCornerShape(14.dp)),
                                )
                            }
                        }

                        state.savedAudioPath?.let { p ->
                            Text(stringResource(R.string.download_saved_to, p), style = MaterialTheme.typography.labelMedium)
                        }
                        state.savedImagePath?.let { p ->
                            Text(stringResource(R.string.download_saved_to, p), style = MaterialTheme.typography.labelMedium)
                        }

                        if (res.audioFile != null) {
                            Button(
                                onClick = {
                                    onOpenInEditor(
                                        res.audioFile,
                                        res.audioDisplayName ?: res.audioFile.name,
                                        res.imageFile,
                                        res.imageMimeType,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.download_open_in_editor))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (res.imageFile != null && hasActiveEditorSession) {
                                FilledTonalButton(
                                    onClick = { onUseAsCover(res.imageFile, res.imageMimeType ?: "image/jpeg") },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(R.string.download_set_as_cover))
                                }
                            }
                            val shareTarget = res.audioFile ?: res.imageFile
                            val shareMime = if (res.audioFile != null) "audio/*" else (res.imageMimeType ?: "image/*")
                            if (shareTarget != null) {
                                OutlinedButton(
                                    onClick = { onShareFile(shareTarget, shareMime) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.export_share))
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.logs.isNotEmpty()) {
            item {
                TextButton(onClick = { showLogs = !showLogs }) {
                    Text(stringResource(R.string.download_logs))
                }
                AnimatedVisibility(visible = showLogs) {
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.logs.joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    selected: Boolean,
    enabled: Boolean,
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        colors = if (selected) {
            CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f))
        } else {
            CardDefaults.outlinedCardColors()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RadioButton(selected = selected, onClick = onClick, enabled = enabled)
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
