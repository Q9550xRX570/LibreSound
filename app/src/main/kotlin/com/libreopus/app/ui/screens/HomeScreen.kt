// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

import android.net.Uri
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.libreopus.app.R
import com.libreopus.app.data.Formatters
import com.libreopus.app.data.RecentFileEntry

@Composable
fun HomeScreen(
    recentFiles: List<RecentFileEntry>,
    hasDownloader: Boolean,
    onPickAudio: () -> Unit,
    onPickVideo: () -> Unit,
    onOpenRecent: (Uri) -> Unit,
    onClearRecents: () -> Unit,
    onOpenConverter: () -> Unit,
    onOpenDownloader: () -> Unit,
    onRecordAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            ElevatedCard(
                colors = CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                text = stringResource(R.string.tagline),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.home_hero_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.home_hero_subtitle),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Button(
                            onClick = onPickAudio,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.AudioFile, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.home_pick_audio), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(
                            onClick = onPickVideo,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Rounded.Movie, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.size(6.dp))
                            Text(stringResource(R.string.home_extract_video), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item {
            Text(stringResource(R.string.home_quick_tools), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (hasDownloader) {
                        OutlinedCard(
                            onClick = onOpenDownloader,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.home_tool_download), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.home_tool_download_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        OutlinedCard(
                            onClick = onPickAudio,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Icon(Icons.Rounded.ContentCut, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Text(stringResource(R.string.home_tool_trim_edit), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    stringResource(R.string.home_tool_trim_edit_desc),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    OutlinedCard(
                        onClick = onOpenConverter,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(Icons.Rounded.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Text(stringResource(R.string.home_tool_converter), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.home_tool_converter_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                OutlinedCard(
                    onClick = onRecordAudio,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Rounded.Mic,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(28.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.home_tool_recorder), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(R.string.home_tool_recorder_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(stringResource(R.string.home_recent_files), style = MaterialTheme.typography.titleMedium)
                if (recentFiles.isNotEmpty()) {
                    TextButton(onClick = onClearRecents) {
                        Text(stringResource(R.string.home_clear_recent))
                    }
                }
            }
        }

        if (recentFiles.isEmpty()) {
            item {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.home_recent_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            items(recentFiles, key = { it.uriString }) { entry ->
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenRecent(entry.uriString.toUri()) },
                ) {
                    ListItem(
                        headlineContent = { Text(entry.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("${entry.formatLabel} • ${Formatters.formatDurationMs(entry.durationMs, false)} • ${entry.sampleRate} Hz • ${entry.bitrateKbps} kbps")
                        },
                        leadingContent = {
                            Icon(Icons.Rounded.AudioFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                    )
                }
            }
        }
    }
}
