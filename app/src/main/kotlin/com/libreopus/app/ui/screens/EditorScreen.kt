// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libreopus.app.R
import com.libreopus.app.data.Formatters
import com.libreopus.app.ui.components.WaveformCanvas
import com.libreopus.app.ui.sheets.ChannelSheet
import com.libreopus.app.ui.sheets.EqualizerSheet
import com.libreopus.app.ui.sheets.ExportSheet
import com.libreopus.app.ui.sheets.FadeSheet
import com.libreopus.app.ui.sheets.FilterGateSheet
import com.libreopus.app.ui.sheets.GainNormalizeSheet
import com.libreopus.app.ui.sheets.InsertSilenceDialog
import com.libreopus.app.ui.sheets.MetadataSheet
import com.libreopus.app.ui.sheets.MixOverlaySheet
import com.libreopus.app.ui.sheets.SpeedPitchSheet
import com.libreopus.app.ui.sheets.SplitOptionsSheet
import com.libreopus.app.viewmodel.EditorUiState
import com.libreopus.app.viewmodel.EditorViewModel
import com.libreopus.audio.api.AudioContainerFormat
import com.libreopus.audio.api.ExportConfig
import com.libreopus.audio.api.ExportFormat
import kotlin.math.abs

private enum class ActiveSheet {
    NONE, GAIN_NORM, FADE, EQ, SPEED_PITCH, FILTER_GATE, CHANNELS, METADATA, EXPORT, SPLIT, INSERT_SILENCE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    viewModel: EditorViewModel,
    snackbarHostState: SnackbarHostState,
    defaultExportFormat: ExportFormat,
    defaultBitrateKbps: Int,
    onBack: () -> Unit,
    onPickAppendFile: () -> Unit,
    onPickMixFile: () -> Unit,
    onPickCoverImage: (onPicked: (ByteArray, String) -> Unit) -> Unit,
    onExportSaveAs: (ExportConfig) -> Unit,
    pendingMixOverlayName: String? = null,
    onConfirmMixOverlay: (offsetMs: Long, baseGainDb: Float, overlayGainDb: Float) -> Unit = { _, _, _ -> },
    onDismissMixOverlay: () -> Unit = {},
) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var scrollFrac by remember { mutableFloatStateOf(0f) }
    var activeTab by remember { mutableIntStateOf(0) }
    var activeSheet by remember { mutableStateOf(ActiveSheet.NONE) }

    val info = state.info

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                title = {
                    Column {
                        Text(
                            text = info?.displayName ?: stringResource(R.string.nav_editor),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (info != null) {
                            Text(
                                text = "${info.containerFormat.displayName} • ${info.sampleRate} Hz • ${info.channels}ch • ${info.bitrateKbps} kbps",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.undo() }, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = { viewModel.redo() }, enabled = state.canRedo) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.editor_redo))
                    }
                    FilledTonalButton(
                        onClick = { activeSheet = ActiveSheet.EXPORT },
                        enabled = state.hasSession,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.editor_export))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.hasSession) {
                // Waveform Timeline Canvas
                WaveformCanvas(
                    waveform = state.waveform,
                    durationMs = state.durationMs,
                    selectionStartMs = state.selectionStartMs,
                    selectionEndMs = state.selectionEndMs,
                    playheadMs = state.playheadMs,
                    zoom = zoom,
                    scrollFraction = scrollFrac,
                    onZoomChange = { z, sc -> zoom = z; scrollFrac = sc },
                    onSelectionChange = { s, e -> viewModel.updateSelection(s, e) },
                    onSeek = { ms -> viewModel.seekTo(ms) },
                )

                // Selection + Zoom Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(
                                R.string.editor_selection_range,
                                Formatters.formatDurationMs(state.selectionStartMs),
                                Formatters.formatDurationMs(state.selectionEndMs),
                                Formatters.formatDurationMs(state.selectionEndMs - state.selectionStartMs),
                            ),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            text = "${Formatters.formatDurationMs(state.playheadMs)} / ${Formatters.formatDurationMs(state.durationMs)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.editor_select_all))
                        }
                        IconButton(onClick = { zoom = (zoom / 1.5f).coerceAtLeast(1f); if (zoom <= 1.01f) scrollFrac = 0f }) {
                            Icon(Icons.Rounded.ZoomOut, contentDescription = stringResource(R.string.editor_zoom_out))
                        }
                        IconButton(onClick = { zoom = (zoom * 1.5f).coerceAtMost(64f) }) {
                            Icon(Icons.Rounded.ZoomIn, contentDescription = stringResource(R.string.editor_zoom_in))
                        }
                    }
                }

                // Transport + Live dBFS Peak Meter
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { viewModel.seekTo(0L) }) {
                                Icon(Icons.Rounded.FastRewind, contentDescription = stringResource(R.string.transport_jump_start))
                            }
                            FilledIconButton(
                                onClick = { viewModel.togglePlayPause(selectionOnly = false) },
                                modifier = Modifier.size(52.dp),
                            ) {
                                Icon(
                                    imageVector = if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                    contentDescription = stringResource(if (state.isPlaying) R.string.transport_pause else R.string.transport_play),
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                            FilledTonalButton(onClick = { viewModel.togglePlayPause(selectionOnly = true) }) {
                                Text(stringResource(R.string.transport_play_selection))
                            }
                            FilledIconToggleButton(
                                checked = state.loopSelection,
                                onCheckedChange = { viewModel.toggleLoop() },
                            ) {
                                Icon(Icons.Rounded.Loop, contentDescription = stringResource(R.string.transport_loop_ab))
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(stringResource(R.string.transport_level), style = MaterialTheme.typography.labelSmall)
                            LinearProgressIndicator(
                                progress = { state.peakMeter },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(6.dp),
                            )
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val speedLabel = if (state.playbackSpeed == state.playbackSpeed.toInt().toFloat()) {
                                "${state.playbackSpeed.toInt()}x"
                            } else {
                                "${state.playbackSpeed}x"
                            }
                            Text(
                                text = stringResource(R.string.transport_speed, speedLabel),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                            speeds.forEach { spd ->
                                val chipText = when (spd) {
                                    1.0f -> "1x"
                                    2.0f -> "2x"
                                    else -> "${spd}x"
                                }
                                FilterChip(
                                    selected = abs(state.playbackSpeed - spd) < 0.02f,
                                    onClick = { viewModel.setPlaybackSpeed(spd) },
                                    label = { Text(chipText) },
                                )
                            }
                        }
                    }
                }

                // Categorized Tool Dock
                val tabs = listOf(
                    stringResource(R.string.tab_edit),
                    stringResource(R.string.tab_effects),
                    stringResource(R.string.tab_tags),
                )
                PrimaryTabRow(selectedTabIndex = activeTab) {
                    tabs.forEachIndexed { idx, title ->
                        Tab(
                            selected = activeTab == idx,
                            onClick = { activeTab = idx },
                            text = { Text(title) },
                        )
                    }
                }

                when (activeTab) {
                    0 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ToolActionChip(Icons.Rounded.Crop, stringResource(R.string.action_trim)) { viewModel.trimToSelection() }
                            ToolActionChip(Icons.Rounded.ContentCut, stringResource(R.string.action_cut)) { viewModel.cutSelection() }
                            ToolActionChip(Icons.Rounded.CallSplit, stringResource(R.string.action_split)) { activeSheet = ActiveSheet.SPLIT }
                            ToolActionChip(Icons.Rounded.VolumeOff, stringResource(R.string.action_silence)) { viewModel.silenceSelection() }
                            ToolActionChip(Icons.Rounded.SpaceBar, stringResource(R.string.action_insert_silence)) { activeSheet = ActiveSheet.INSERT_SILENCE }
                            ToolActionChip(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy)) { viewModel.copySelection() }
                            ToolActionChip(Icons.Rounded.ContentPaste, stringResource(R.string.action_paste), enabled = state.hasClipboard) {
                                viewModel.pasteAtCursor()
                            }
                            ToolActionChip(Icons.Rounded.Add, stringResource(R.string.action_append_file), onClick = onPickAppendFile)
                            ToolActionChip(Icons.Rounded.Layers, stringResource(R.string.action_mix_overlay), onClick = onPickMixFile)
                        }
                    }
                    1 -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            ToolActionChip(Icons.Rounded.VolumeUp, stringResource(R.string.effect_gain_norm)) { activeSheet = ActiveSheet.GAIN_NORM }
                            ToolActionChip(Icons.Rounded.TrendingUp, stringResource(R.string.effect_fade)) { activeSheet = ActiveSheet.FADE }
                            ToolActionChip(Icons.Rounded.Equalizer, stringResource(R.string.effect_eq)) { activeSheet = ActiveSheet.EQ }
                            ToolActionChip(Icons.Rounded.Speed, stringResource(R.string.effect_speed_pitch)) { activeSheet = ActiveSheet.SPEED_PITCH }
                            ToolActionChip(Icons.Rounded.FilterAlt, stringResource(R.string.effect_filter_gate)) { activeSheet = ActiveSheet.FILTER_GATE }
                            ToolActionChip(Icons.Rounded.SwapHoriz, stringResource(R.string.effect_reverse)) { viewModel.reverseSelection() }
                            ToolActionChip(Icons.Rounded.Tune, stringResource(R.string.effect_channels)) { activeSheet = ActiveSheet.CHANNELS }
                        }
                    }
                    else -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = { activeSheet = ActiveSheet.METADATA },
                                modifier = Modifier.weight(1f),
                            ) {
                                Icon(Icons.Rounded.Tag, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.meta_title))
                            }
                        }
                    }
                }
            }
        }
    }

    // Busy Progress Dialog
    if (state.busyLabel != null) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.processing_applying, state.busyLabel)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { state.busyProgress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("${(state.busyProgress * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.cancelActiveTask() }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // Active Bottom Sheets
    when (activeSheet) {
        ActiveSheet.GAIN_NORM -> GainNormalizeSheet(
            onDismiss = { activeSheet = ActiveSheet.NONE },
            onApplyGain = { viewModel.applyGain(it) },
            onNormalizePeak = { viewModel.normalizePeak(it) },
            onNormalizeLufs = { viewModel.normalizeLufs(it) },
        )
        ActiveSheet.FADE -> FadeSheet(
            onDismiss = { activeSheet = ActiveSheet.NONE },
            onApplyFade = { fadeIn, curve -> viewModel.applyFade(fadeIn, curve) },
        )
        ActiveSheet.EQ -> EqualizerSheet(
            onDismiss = { activeSheet = ActiveSheet.NONE },
            onApplyEq = { viewModel.applyEq(it) },
        )
        ActiveSheet.SPEED_PITCH -> SpeedPitchSheet(
            onDismiss = { activeSheet = ActiveSheet.NONE },
            onApply = { tempo, pitch, linked -> viewModel.applySpeedAndPitch(tempo, pitch, linked) },
        )
        ActiveSheet.FILTER_GATE -> FilterGateSheet(
            onDismiss = { activeSheet = ActiveSheet.NONE },
            onApplyFilterGate = { hp, lp, gate, gateDb -> viewModel.applyFilterAndGate(hp, lp, gate, gateDb) },
            onTrimSilence = { threshDb, minMs -> viewModel.trimSilence(threshDb, minMs) },
        )
        ActiveSheet.CHANNELS -> ChannelSheet(
            onDismiss = { activeSheet = ActiveSheet.NONE },
            onApply = { viewModel.applyChannelOperation(it) },
        )
        ActiveSheet.METADATA -> {
            val canLosslessTags = state.losslessSourceFile != null &&
                (info?.containerFormat?.isOggOpus == true || info?.containerFormat == AudioContainerFormat.FLAC)
            MetadataSheet(
                initial = state.metadata,
                canSaveTagsLosslessly = canLosslessTags,
                onPickCoverArt = onPickCoverImage,
                onApplyToSession = { viewModel.updateMetadataInSession(it) },
                onSaveTagsLosslessly = { viewModel.saveTagsLosslesslyToMusic(it) },
                onDismiss = { activeSheet = ActiveSheet.NONE },
            )
        }
        ActiveSheet.EXPORT -> ExportSheet(
            info = info,
            durationMs = state.durationMs,
            metadata = state.metadata,
            isLosslessOpusReady = state.isLosslessOpusReady,
            defaultFormat = defaultExportFormat,
            defaultBitrateKbps = defaultBitrateKbps,
            onExportToMusic = { cfg, share -> viewModel.exportToMusic(cfg, share) },
            onExportSaveAs = onExportSaveAs,
            onDismiss = { activeSheet = ActiveSheet.NONE },
        )
        ActiveSheet.SPLIT -> SplitOptionsSheet(
            playheadMs = state.playheadMs,
            durationMs = state.durationMs,
            onKeepLeft = { viewModel.splitKeepLeft() },
            onKeepRight = { viewModel.splitKeepRight() },
            onExportBoth = { viewModel.splitAndExportBothHalves(defaultExportFormat, defaultBitrateKbps) },
            onDismiss = { activeSheet = ActiveSheet.NONE },
        )
        ActiveSheet.INSERT_SILENCE -> InsertSilenceDialog(
            onConfirm = { durMs -> viewModel.insertSilenceAtCursor(durMs) },
            onDismiss = { activeSheet = ActiveSheet.NONE },
        )
        ActiveSheet.NONE -> Unit
    }

    if (pendingMixOverlayName != null) {
        MixOverlaySheet(
            overlayDisplayName = pendingMixOverlayName,
            timelineDurationMs = state.durationMs,
            playheadMs = state.playheadMs,
            selectionStartMs = state.selectionStartMs,
            onConfirm = onConfirmMixOverlay,
            onDismiss = onDismissMixOverlay,
        )
    }
}

@Composable
private fun ToolActionChip(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = AssistChipDefaults.assistChipColors(),
    )
}
