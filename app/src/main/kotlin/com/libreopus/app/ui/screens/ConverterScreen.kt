// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libreopus.app.R
import com.libreopus.app.viewmodel.ConverterUiState
import com.libreopus.app.viewmodel.ConverterViewModel
import com.libreopus.app.viewmodel.QueueItemStatus
import com.libreopus.audio.api.ChannelMode
import com.libreopus.audio.api.ExportFormat
import com.libreopus.audio.api.WavBitDepth
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConverterScreen(
    state: ConverterUiState,
    viewModel: ConverterViewModel,
    onAddFiles: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenConvertedUri: (Uri) -> Unit = {},
    onShareConvertedUri: (Uri, String) -> Unit = { _, _ -> },
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(stringResource(R.string.converter_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.converter_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.mergeMode,
                    onClick = { if (!state.isRunning) viewModel.updateConfig(mergeMode = false) },
                    label = { Text(stringResource(R.string.converter_mode_batch)) },
                )
                FilterChip(
                    selected = state.mergeMode,
                    onClick = { if (!state.isRunning) viewModel.updateConfig(mergeMode = true) },
                    label = { Text(stringResource(R.string.converter_mode_merge)) },
                )
            }
        }

        item {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.export_format), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExportFormat.entries.forEach { fmt ->
                            FilterChip(
                                selected = state.targetFormat == fmt,
                                onClick = { if (!state.isRunning) viewModel.updateConfig(format = fmt) },
                                label = { Text(fmt.displayName) },
                            )
                        }
                    }

                    if (state.targetFormat.supportsBitrate) {
                        Text(stringResource(R.string.export_bitrate, state.bitrateKbps))
                        @Suppress("DEPRECATION")
                        Slider(
                            value = state.bitrateKbps.toFloat(),
                            onValueChange = { if (!state.isRunning) viewModel.updateConfig(bitrateKbps = (it / 8f).roundToInt() * 8) },
                            valueRange = 24f..320f,
                        )
                    }

                    Text(stringResource(R.string.converter_channels_label), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val channelOptions = listOf(
                            ChannelMode.KEEP to R.string.converter_channel_keep,
                            ChannelMode.MONO to R.string.converter_channel_mono,
                            ChannelMode.STEREO to R.string.converter_channel_stereo,
                        )
                        channelOptions.forEach { (mode, labelRes) ->
                            FilterChip(
                                selected = state.channelMode == mode,
                                onClick = { if (!state.isRunning) viewModel.updateConfig(channelMode = mode) },
                                label = { Text(stringResource(labelRes)) },
                            )
                        }
                    }

                    val isOpus = state.targetFormat == ExportFormat.OPUS || state.targetFormat == ExportFormat.OGG_OPUS
                    Text(stringResource(R.string.converter_samplerate_label), style = MaterialTheme.typography.labelLarge)
                    if (isOpus) {
                        Text(
                            text = stringResource(R.string.converter_samplerate_opus_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val sampleRates = listOf(0, 16_000, 44_100, 48_000)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            sampleRates.forEach { hz ->
                                val label = when (hz) {
                                    0 -> stringResource(R.string.converter_samplerate_original)
                                    16_000 -> "16 kHz"
                                    44_100 -> "44.1 kHz"
                                    48_000 -> "48 kHz"
                                    else -> "$hz Hz"
                                }
                                FilterChip(
                                    selected = state.sampleRateHz == hz,
                                    onClick = { if (!state.isRunning) viewModel.updateConfig(sampleRateHz = hz) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    if (state.targetFormat == ExportFormat.WAV) {
                        Text(stringResource(R.string.converter_wav_depth_label), style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WavBitDepth.entries.forEach { depth ->
                                val label = if (depth.isFloat) "32-bit Float" else "${depth.bits}-bit PCM"
                                FilterChip(
                                    selected = state.wavBitDepth == depth,
                                    onClick = { if (!state.isRunning) viewModel.updateConfig(wavBitDepth = depth) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    if (state.mergeMode) {
                        Text(
                            text = stringResource(R.string.merge_crossfade_label, state.crossfadeMs / 1000f),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        @Suppress("DEPRECATION")
                        Slider(
                            value = state.crossfadeMs.toFloat(),
                            onValueChange = {
                                if (!state.isRunning) {
                                    val snapped = ((it / 250f).roundToLong() * 250L).coerceIn(0L, 5000L)
                                    viewModel.updateConfig(crossfadeMs = snapped)
                                }
                            },
                            valueRange = 0f..5000f,
                        )
                        OutlinedTextField(
                            value = state.mergedOutputName,
                            onValueChange = { if (!state.isRunning) viewModel.updateConfig(mergedOutputName = it) },
                            label = { Text(stringResource(R.string.merge_output_name_label)) },
                            placeholder = { Text(stringResource(R.string.merge_output_name_hint)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.converter_lufs_toggle), modifier = Modifier.weight(1f))
                        Switch(
                            checked = state.normalizeLufs,
                            onCheckedChange = { if (!state.isRunning) viewModel.updateConfig(normalizeLufs = it) },
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilledTonalButton(
                    onClick = onAddFiles,
                    enabled = !state.isRunning,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.converter_add_files), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (state.queue.isNotEmpty() && !state.isRunning) {
                    OutlinedButton(onClick = { viewModel.clearQueue() }) {
                        Text(stringResource(R.string.converter_clear_queue))
                    }
                }
            }
        }

        if (state.queue.isNotEmpty()) {
            item {
                if (state.isRunning) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.mergeMode) {
                            LinearProgressIndicator(
                                progress = { state.mergedProgress.coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.cancelBatch() },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                } else {
                    val canRun = if (state.mergeMode) state.queue.size >= 2 else true
                    Button(
                        onClick = { viewModel.startAction() },
                        enabled = canRun,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (state.mergeMode) {
                                if (state.queue.size < 2) {
                                    stringResource(R.string.merge_need_two_tracks)
                                } else {
                                    stringResource(R.string.merge_start, state.queue.size)
                                }
                            } else {
                                stringResource(R.string.converter_start)
                            },
                        )
                    }
                }
            }
        }

        val mergedUri = state.mergedResultUri
        val mergedPath = state.mergedResultPath
        if (state.mergeMode && mergedUri != null && mergedPath != null) {
            item {
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.merge_done_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = mergedPath,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { onOpenConvertedUri(mergedUri) }) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.download_open_in_editor))
                            }
                            TextButton(onClick = { onShareConvertedUri(mergedUri, state.mergedResultMimeType ?: "audio/*") }) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.export_share))
                            }
                        }
                    }
                }
            }
        }

        if (state.mergeMode && state.mergedError != null) {
            item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = state.mergedError,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(14.dp),
                    )
                }
            }
        }

        itemsIndexed(state.queue, key = { _, item -> item.uri.toString() }) { idx, item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        headlineContent = {
                            val title = if (state.mergeMode) "${idx + 1}. ${item.displayName}" else item.displayName
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val txt = when (item.status) {
                                QueueItemStatus.PENDING -> stringResource(R.string.converter_status_pending)
                                QueueItemStatus.PROCESSING -> "${(item.progress * 100).toInt()}%"
                                QueueItemStatus.DONE -> item.outputName ?: stringResource(R.string.converter_status_done)
                                QueueItemStatus.ERROR -> item.error ?: stringResource(R.string.converter_status_error)
                            }
                            Text(txt, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = {
                            when (item.status) {
                                QueueItemStatus.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                QueueItemStatus.ERROR -> Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                else -> Icon(Icons.Rounded.AudioFile, contentDescription = null)
                            }
                        },
                        trailingContent = {
                            if (!state.isRunning) {
                                Row {
                                    if (state.mergeMode) {
                                        IconButton(
                                            onClick = { viewModel.moveItem(idx, -1) },
                                            enabled = idx > 0,
                                        ) {
                                            Icon(Icons.Rounded.ArrowUpward, contentDescription = null)
                                        }
                                        IconButton(
                                            onClick = { viewModel.moveItem(idx, 1) },
                                            enabled = idx < state.queue.lastIndex,
                                        ) {
                                            Icon(Icons.Rounded.ArrowDownward, contentDescription = null)
                                        }
                                    }
                                    IconButton(onClick = { viewModel.removeItem(item.uri) }) {
                                        Icon(Icons.Rounded.Close, contentDescription = null)
                                    }
                                }
                            }
                        },
                    )
                    if (item.status == QueueItemStatus.PROCESSING) {
                        LinearProgressIndicator(
                            progress = { item.progress.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                        )
                    }
                    val outUri = item.outputContentUri
                    val outMime = item.outputMimeType ?: "audio/*"
                    if (!state.mergeMode && item.status == QueueItemStatus.DONE && outUri != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TextButton(onClick = { onOpenConvertedUri(outUri) }) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.download_open_in_editor),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(onClick = { onShareConvertedUri(outUri, outMime) }) {
                                Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    stringResource(R.string.export_share),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
