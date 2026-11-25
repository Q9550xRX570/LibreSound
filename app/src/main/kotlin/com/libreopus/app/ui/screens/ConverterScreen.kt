// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import com.libreopus.audio.api.ExportFormat
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConverterScreen(
    state: ConverterUiState,
    viewModel: ConverterViewModel,
    onAddFiles: () -> Unit,
    modifier: Modifier = Modifier,
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
                        Slider(
                            value = state.bitrateKbps.toFloat(),
                            onValueChange = { if (!state.isRunning) viewModel.updateConfig(bitrateKbps = (it / 8f).roundToInt() * 8) },
                            valueRange = 24f..320f,
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
                    OutlinedButton(
                        onClick = { viewModel.cancelBatch() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                } else {
                    Button(
                        onClick = { viewModel.startBatch() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.converter_start))
                    }
                }
            }
        }

        items(state.queue, key = { it.uri.toString() }) { item ->
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(bottom = 8.dp)) {
                    ListItem(
                        headlineContent = { Text(item.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                                IconButton(onClick = { viewModel.removeItem(item.uri) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = null)
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
                }
            }
        }
    }
}
