// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.libreopus.app.R
import com.libreopus.app.audio.AudioRecorderHelper
import com.libreopus.app.data.Formatters
import com.libreopus.dsp.effects.ChannelOperation
import com.libreopus.dsp.effects.CompressorPreset
import com.libreopus.dsp.effects.EqPreset
import com.libreopus.dsp.effects.EqualizerProcessor
import com.libreopus.dsp.effects.FadeCurve
import com.libreopus.dsp.effects.FadeProcessor
import com.libreopus.dsp.effects.LiveDspConfig
import com.libreopus.dsp.effects.ReverbPreset
import com.libreopus.dsp.effects.StereoBassPreset
import com.libreopus.dsp.effects.VocalRemovePreset
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
private fun LivePreviewBar(
    isPlaying: Boolean,
    isBypassed: Boolean,
    onTogglePlay: () -> Unit,
    onToggleBypass: () -> Unit,
    note: String? = null,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilledTonalButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(if (isPlaying) R.string.preview_stop else R.string.preview_start),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                FilterChip(
                    selected = !isBypassed,
                    onClick = onToggleBypass,
                    label = {
                        Text(
                            stringResource(if (isBypassed) R.string.preview_ab_original else R.string.preview_ab_effect),
                            maxLines = 1,
                        )
                    },
                )
            }
            if (!note.isNullOrBlank()) {
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GainNormalizeSheet(
    onDismiss: () -> Unit,
    onApplyGain: (Float) -> Unit,
    onNormalizePeak: (Float) -> Unit,
    onNormalizeLufs: (Float) -> Unit,
    isPlaying: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onLiveDspChange: (LiveDspConfig) -> Unit = {},
    onStopPreview: () -> Unit = {},
) {
    var gainDb by remember { mutableFloatStateOf(0f) }
    var targetLufs by remember { mutableFloatStateOf(-14f) }
    var isBypassed by remember { mutableStateOf(false) }

    LaunchedEffect(gainDb, isBypassed) {
        onLiveDspChange(LiveDspConfig(gainDb = gainDb, bypass = isBypassed))
    }
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.effect_gain_norm), style = MaterialTheme.typography.headlineSmall)
            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
            )
            Text(stringResource(R.string.gain_db_label, gainDb))
            @Suppress("DEPRECATION")
            Slider(value = gainDb, onValueChange = { gainDb = it }, valueRange = -24f..24f)
            Button(
                onClick = { onStopPreview(); onApplyGain(gainDb); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }

            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = { onStopPreview(); onNormalizePeak(-1.0f); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.norm_peak_button))
            }

            Text(stringResource(R.string.lufs_target_label, targetLufs))
            @Suppress("DEPRECATION")
            Slider(value = targetLufs, onValueChange = { targetLufs = it.roundToInt().toFloat() }, valueRange = -24f..-9f)
            FilledTonalButton(
                onClick = { onStopPreview(); onNormalizeLufs(targetLufs); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.norm_lufs_button, targetLufs))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FadeSheet(
    onDismiss: () -> Unit,
    onApplyFade: (fadeIn: Boolean, curve: FadeCurve) -> Unit,
) {
    var fadeIn by remember { mutableStateOf(true) }
    var curve by remember { mutableStateOf(FadeCurve.S_CURVE) }
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.effect_fade), style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = fadeIn,
                    onClick = { fadeIn = true },
                    label = { Text(stringResource(R.string.fade_direction_in)) },
                )
                FilterChip(
                    selected = !fadeIn,
                    onClick = { fadeIn = false },
                    label = { Text(stringResource(R.string.fade_direction_out)) },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val labels = mapOf(
                    FadeCurve.LINEAR to stringResource(R.string.fade_curve_linear),
                    FadeCurve.EXPONENTIAL to stringResource(R.string.fade_curve_exp),
                    FadeCurve.LOGARITHMIC to stringResource(R.string.fade_curve_log),
                    FadeCurve.S_CURVE to stringResource(R.string.fade_curve_scurve),
                )
                FadeCurve.entries.forEach { c ->
                    FilterChip(
                        selected = curve == c,
                        onClick = { curve = c },
                        label = { Text(labels[c] ?: c.name) },
                    )
                }
            }

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp),
            ) {
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                val path = Path()
                val steps = 48
                for (i in 0..steps) {
                    val t = i.toFloat() / steps
                    val g = FadeProcessor.curveGain(t, fadeIn, curve)
                    val x = t * size.width
                    val y = size.height - g * (size.height - 8f) - 4f
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, primary, style = Stroke(width = 5f))
            }

            Button(
                onClick = { onApplyFade(fadeIn, curve); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    onDismiss: () -> Unit,
    onApplyEq: (List<Float>) -> Unit,
    isPlaying: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onLiveDspChange: (LiveDspConfig) -> Unit = {},
    onStopPreview: () -> Unit = {},
) {
    val bands = remember { mutableStateListOf(0f, 0f, 0f, 0f, 0f, 0f, 0f) }
    val freqs = EqualizerProcessor.BAND_FREQUENCIES_HZ
    var isBypassed by remember { mutableStateOf(false) }

    val snapshotBands = bands.toList()
    LaunchedEffect(snapshotBands, isBypassed) {
        onLiveDspChange(LiveDspConfig(eqBandsDb = snapshotBands, bypass = isBypassed))
    }
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.effect_eq), style = MaterialTheme.typography.headlineSmall)
            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
            )
            Text(stringResource(R.string.eq_presets), style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EqPreset.entries.forEach { preset ->
                    OutlinedButton(onClick = {
                        preset.gainsDb.forEachIndexed { idx, v -> bands[idx] = v }
                    }) {
                        Text(preset.name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
                    }
                }
            }
            freqs.forEachIndexed { idx, hz ->
                val label = if (hz >= 1000f) "${(hz / 1000f).roundToInt()} kHz" else "${hz.roundToInt()} Hz"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, modifier = Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium)
                    @Suppress("DEPRECATION")
                    Slider(
                        value = bands[idx],
                        onValueChange = { bands[idx] = it },
                        valueRange = -12f..12f,
                        modifier = Modifier.weight(1f),
                    )
                    Text("%+.1f".format(bands[idx]), modifier = Modifier.width(44.dp), style = MaterialTheme.typography.labelMedium)
                }
            }
            Button(
                onClick = { onStopPreview(); onApplyEq(bands.toList()); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedPitchSheet(
    onDismiss: () -> Unit,
    onApply: (tempo: Float, pitchSemitones: Float, linked: Boolean) -> Unit,
    isPlaying: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onLiveSpeedPitchChange: (tempo: Float, pitchSemitones: Float, linked: Boolean, bypass: Boolean) -> Unit = { _, _, _, _ -> },
    onStopPreview: () -> Unit = {},
) {
    var linked by remember { mutableStateOf(false) }
    var tempo by remember { mutableFloatStateOf(1.0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var isBypassed by remember { mutableStateOf(false) }

    LaunchedEffect(tempo, pitch, linked, isBypassed) {
        onLiveSpeedPitchChange(tempo, pitch, linked, isBypassed)
    }
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.effect_speed_pitch), style = MaterialTheme.typography.headlineSmall)
            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
                note = if (!linked) stringResource(R.string.preview_speed_pitch_note) else null,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.speed_linked))
                Switch(checked = linked, onCheckedChange = { linked = it })
            }
            Text(stringResource(R.string.speed_tempo_label, tempo))
            @Suppress("DEPRECATION")
            Slider(value = tempo, onValueChange = { tempo = (it * 20f).roundToInt() / 20f }, valueRange = 0.5f..2.0f)
            if (!linked) {
                Text(stringResource(R.string.speed_pitch_label, pitch))
                @Suppress("DEPRECATION")
                Slider(value = pitch, onValueChange = { pitch = it.roundToInt().toFloat() }, valueRange = -12f..12f)
            }
            Button(
                onClick = { onStopPreview(); onApply(tempo, pitch, linked); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterGateSheet(
    onDismiss: () -> Unit,
    onApplyFilterGate: (hpHz: Float?, lpHz: Float?, gate: Boolean, gateDb: Float) -> Unit,
    onTrimSilence: (threshDb: Float, minMs: Int) -> Unit,
    isPlaying: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onLiveDspChange: (LiveDspConfig) -> Unit = {},
    onStopPreview: () -> Unit = {},
) {
    var enableHp by remember { mutableStateOf(true) }
    var hpHz by remember { mutableFloatStateOf(80f) }
    var enableLp by remember { mutableStateOf(false) }
    var lpHz by remember { mutableFloatStateOf(12000f) }
    var enableGate by remember { mutableStateOf(false) }
    var gateDb by remember { mutableFloatStateOf(-40f) }
    var silenceDb by remember { mutableFloatStateOf(-42f) }
    var silenceMinMs by remember { mutableIntStateOf(350) }
    var isBypassed by remember { mutableStateOf(false) }

    LaunchedEffect(enableHp, hpHz, enableLp, lpHz, enableGate, gateDb, isBypassed) {
        onLiveDspChange(
            LiveDspConfig(
                highPassHz = if (enableHp) hpHz else null,
                lowPassHz = if (enableLp) lpHz else null,
                gateEnabled = enableGate,
                gateThresholdDb = gateDb,
                bypass = isBypassed,
            ),
        )
    }
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.effect_filter_gate), style = MaterialTheme.typography.headlineSmall)
            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filter_enable_hp))
                Switch(checked = enableHp, onCheckedChange = { enableHp = it })
            }
            if (enableHp) {
                Text(stringResource(R.string.filter_hp_label, hpHz.roundToInt()))
                @Suppress("DEPRECATION")
                Slider(value = hpHz, onValueChange = { hpHz = it }, valueRange = 30f..1000f)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filter_enable_lp))
                Switch(checked = enableLp, onCheckedChange = { enableLp = it })
            }
            if (enableLp) {
                Text(stringResource(R.string.filter_lp_label, lpHz.roundToInt()))
                @Suppress("DEPRECATION")
                Slider(value = lpHz, onValueChange = { lpHz = it }, valueRange = 1500f..18000f)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gate_enable))
                Switch(checked = enableGate, onCheckedChange = { enableGate = it })
            }
            if (enableGate) {
                Text(stringResource(R.string.gate_threshold_label, gateDb))
                @Suppress("DEPRECATION")
                Slider(value = gateDb, onValueChange = { gateDb = it.roundToInt().toFloat() }, valueRange = -65f..-15f)
            }

            Button(
                onClick = {
                    onStopPreview()
                    onApplyFilterGate(
                        if (enableHp) hpHz else null,
                        if (enableLp) lpHz else null,
                        enableGate,
                        gateDb,
                    )
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }

            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.effect_trim_silence), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.silence_thresh_label, silenceDb))
            @Suppress("DEPRECATION")
            Slider(value = silenceDb, onValueChange = { silenceDb = it.roundToInt().toFloat() }, valueRange = -65f..-20f)
            Text(stringResource(R.string.silence_min_ms_label, silenceMinMs))
            @Suppress("DEPRECATION")
            Slider(value = silenceMinMs.toFloat(), onValueChange = { silenceMinMs = it.roundToInt() }, valueRange = 150f..2000f)
            FilledTonalButton(
                onClick = { onStopPreview(); onTrimSilence(silenceDb, silenceMinMs); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.effect_trim_silence))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelSheet(
    onDismiss: () -> Unit,
    onApply: (ChannelOperation) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.effect_channels), style = MaterialTheme.typography.headlineSmall)
            val ops = listOf(
                ChannelOperation.TO_MONO_MIX to stringResource(R.string.channel_to_mono_mix),
                ChannelOperation.TO_MONO_LEFT to stringResource(R.string.channel_to_mono_left),
                ChannelOperation.TO_MONO_RIGHT to stringResource(R.string.channel_to_mono_right),
                ChannelOperation.SWAP_LR to stringResource(R.string.channel_swap_lr),
                ChannelOperation.MONO_TO_STEREO to stringResource(R.string.channel_mono_to_stereo),
            )
            ops.forEach { (op, label) ->
                FilledTonalButton(
                    onClick = { onApply(op); onDismiss() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MixOverlaySheet(
    overlayDisplayName: String,
    timelineDurationMs: Long,
    playheadMs: Long,
    selectionStartMs: Long,
    onConfirm: (offsetMs: Long, baseGainDb: Float, overlayGainDb: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val maxDurMs = max(1000L, timelineDurationMs)
    var offsetMs by remember { mutableLongStateOf(playheadMs.coerceIn(0L, maxDurMs)) }
    var baseGainDb by remember { mutableFloatStateOf(0f) }
    var overlayGainDb by remember { mutableFloatStateOf(-3f) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.mix_dialog_title), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.mix_selected_file, overlayDisplayName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                text = stringResource(R.string.mix_offset_label, Formatters.formatDurationMs(offsetMs)),
                style = MaterialTheme.typography.labelLarge,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = offsetMs == playheadMs.coerceIn(0L, maxDurMs),
                    onClick = { offsetMs = playheadMs.coerceIn(0L, maxDurMs) },
                    label = { Text(stringResource(R.string.mix_offset_cursor)) },
                )
                FilterChip(
                    selected = offsetMs == selectionStartMs.coerceIn(0L, maxDurMs),
                    onClick = { offsetMs = selectionStartMs.coerceIn(0L, maxDurMs) },
                    label = { Text(stringResource(R.string.mix_offset_selection)) },
                )
                FilterChip(
                    selected = offsetMs == 0L,
                    onClick = { offsetMs = 0L },
                    label = { Text(stringResource(R.string.mix_offset_start)) },
                )
            }
            @Suppress("DEPRECATION")
            Slider(
                value = offsetMs.toFloat(),
                onValueChange = { offsetMs = it.roundToLong().coerceIn(0L, maxDurMs) },
                valueRange = 0f..maxDurMs.toFloat(),
            )

            Text(stringResource(R.string.mix_base_gain, baseGainDb))
            @Suppress("DEPRECATION")
            Slider(
                value = baseGainDb,
                onValueChange = { baseGainDb = (it * 2f).roundToInt() / 2f },
                valueRange = -18f..6f,
            )

            Text(stringResource(R.string.mix_overlay_gain, overlayGainDb))
            @Suppress("DEPRECATION")
            Slider(
                value = overlayGainDb,
                onValueChange = { overlayGainDb = (it * 2f).roundToInt() / 2f },
                valueRange = -18f..6f,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.cancel))
                }
                Button(
                    onClick = {
                        onConfirm(offsetMs, baseGainDb, overlayGainDb)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitOptionsSheet(
    playheadMs: Long,
    durationMs: Long,
    chapterCount: Int,
    onKeepLeft: () -> Unit,
    onKeepRight: () -> Unit,
    onExportBoth: () -> Unit,
    onDetectSilence: (thresholdDb: Float, minSilenceMs: Int) -> Unit,
    onExportAllChapters: () -> Unit,
    onDismiss: () -> Unit,
) {
    val canKeepLeft = playheadMs > 0L
    val canKeepRight = playheadMs < durationMs
    val canSplitInterior = canKeepLeft && canKeepRight
    var silenceThreshDb by remember { mutableFloatStateOf(-38f) }
    var minSilenceMs by remember { mutableIntStateOf(500) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.split_sheet_title, Formatters.formatDurationMs(playheadMs)),
                style = MaterialTheme.typography.headlineSmall,
            )
            FilledTonalButton(
                onClick = { onKeepLeft(); onDismiss() },
                enabled = canKeepLeft,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.split_keep_left))
            }
            FilledTonalButton(
                onClick = { onKeepRight(); onDismiss() },
                enabled = canKeepRight,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.split_keep_right))
            }
            Button(
                onClick = { onExportBoth(); onDismiss() },
                enabled = canSplitInterior,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.split_export_both))
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            Text(
                text = stringResource(R.string.split_silence_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.silence_thresh_label, silenceThreshDb))
            @Suppress("DEPRECATION")
            Slider(
                value = silenceThreshDb,
                onValueChange = { silenceThreshDb = it.roundToInt().toFloat() },
                valueRange = -60f..-20f,
            )
            Text(stringResource(R.string.silence_min_ms_label, minSilenceMs))
            @Suppress("DEPRECATION")
            Slider(
                value = minSilenceMs.toFloat(),
                onValueChange = { minSilenceMs = ((it / 50f).roundToInt() * 50).coerceIn(200, 2000) },
                valueRange = 200f..2000f,
            )
            FilledTonalButton(
                onClick = {
                    onDetectSilence(silenceThreshDb, minSilenceMs)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.split_detect_silence_btn))
            }
            if (chapterCount > 0) {
                Button(
                    onClick = {
                        onExportAllChapters()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.split_export_all_chapters_btn, chapterCount))
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsertSilenceDialog(
    onConfirm: (durationMs: Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var durationMs by remember { mutableLongStateOf(1000L) }
    val presetsMs = listOf(250L, 500L, 1000L, 2000L, 5000L)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silence_insert_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.silence_insert_duration, durationMs / 1000f))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    presetsMs.forEach { preset ->
                        FilterChip(
                            selected = durationMs == preset,
                            onClick = { durationMs = preset },
                            label = { Text(if (preset < 1000L) "${preset}ms" else "${preset / 1000}s") },
                        )
                    }
                }
                @Suppress("DEPRECATION")
                Slider(
                    value = durationMs.toFloat(),
                    onValueChange = { durationMs = ((it / 50f).roundToLong() * 50L).coerceIn(100L, 10_000L) },
                    valueRange = 100f..10_000f,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(durationMs); onDismiss() }) {
                Text(stringResource(R.string.apply))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CompressorSheet(
    onDismiss: () -> Unit,
    onApply: (thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float, makeupDb: Float) -> Unit,
    isPlaying: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onLiveDspChange: (LiveDspConfig) -> Unit = {},
    onStopPreview: () -> Unit = {},
) {
    var thresholdDb by remember { mutableFloatStateOf(-18f) }
    var ratio by remember { mutableFloatStateOf(3.5f) }
    var attackMs by remember { mutableFloatStateOf(10f) }
    var releaseMs by remember { mutableFloatStateOf(120f) }
    var makeupDb by remember { mutableFloatStateOf(3.5f) }
    var selectedPreset by remember { mutableStateOf<CompressorPreset?>(CompressorPreset.VOCAL) }
    var isBypassed by remember { mutableStateOf(false) }

    LaunchedEffect(thresholdDb, ratio, attackMs, releaseMs, makeupDb, isBypassed) {
        onLiveDspChange(
            LiveDspConfig(
                compEnabled = true,
                compThresholdDb = thresholdDb,
                compRatio = ratio,
                compAttackMs = attackMs,
                compReleaseMs = releaseMs,
                compMakeupDb = makeupDb,
                bypass = isBypassed,
            ),
        )
    }
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.effect_compressor), style = MaterialTheme.typography.headlineSmall)

            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
            )

            val presets = listOf(
                CompressorPreset.GENTLE to stringResource(R.string.comp_preset_gentle),
                CompressorPreset.VOCAL to stringResource(R.string.comp_preset_vocal),
                CompressorPreset.PODCAST to stringResource(R.string.comp_preset_podcast),
                CompressorPreset.PUNCHY to stringResource(R.string.comp_preset_punchy),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (preset, label) ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            thresholdDb = preset.thresholdDb
                            ratio = preset.ratio
                            attackMs = preset.attackMs
                            releaseMs = preset.releaseMs
                            makeupDb = preset.makeupDb
                        },
                        label = { Text(label) },
                    )
                }
            }

            Text(stringResource(R.string.comp_threshold_label, thresholdDb))
            @Suppress("DEPRECATION")
            Slider(
                value = thresholdDb,
                onValueChange = { selectedPreset = null; thresholdDb = it.roundToInt().toFloat() },
                valueRange = -40f..0f,
            )

            Text(stringResource(R.string.comp_ratio_label, ratio))
            @Suppress("DEPRECATION")
            Slider(
                value = ratio,
                onValueChange = { selectedPreset = null; ratio = (it * 2f).roundToInt() / 2f },
                valueRange = 1.5f..12f,
            )

            Text(stringResource(R.string.comp_attack_label, attackMs))
            @Suppress("DEPRECATION")
            Slider(
                value = attackMs,
                onValueChange = { selectedPreset = null; attackMs = it.roundToInt().toFloat() },
                valueRange = 1f..50f,
            )

            Text(stringResource(R.string.comp_release_label, releaseMs))
            @Suppress("DEPRECATION")
            Slider(
                value = releaseMs,
                onValueChange = { selectedPreset = null; releaseMs = (it / 10f).roundToInt() * 10f },
                valueRange = 40f..500f,
            )

            Text(stringResource(R.string.comp_makeup_label, makeupDb))
            @Suppress("DEPRECATION")
            Slider(
                value = makeupDb,
                onValueChange = { selectedPreset = null; makeupDb = (it * 2f).roundToInt() / 2f },
                valueRange = 0f..18f,
            )

            Button(
                onClick = {
                    onStopPreview()
                    onApply(thresholdDb, ratio, attackMs, releaseMs, makeupDb)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReverbEchoSheet(
    onDismiss: () -> Unit,
    onApply: (delayMs: Float, feedback: Float, roomDamping: Float, wetMix: Float) -> Unit,
    isPlaying: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onLiveDspChange: (LiveDspConfig) -> Unit = {},
    onStopPreview: () -> Unit = {},
) {
    var delayMs by remember { mutableFloatStateOf(80f) }
    var feedback by remember { mutableFloatStateOf(0.46f) }
    var roomDamping by remember { mutableFloatStateOf(0.25f) }
    var wetMix by remember { mutableFloatStateOf(0.28f) }
    var selectedPreset by remember { mutableStateOf<ReverbPreset?>(ReverbPreset.STUDIO_PLATE) }
    var isBypassed by remember { mutableStateOf(false) }

    LaunchedEffect(delayMs, feedback, roomDamping, wetMix, isBypassed) {
        onLiveDspChange(
            LiveDspConfig(
                reverbEnabled = true,
                reverbDelayMs = delayMs,
                reverbFeedback = feedback,
                reverbDamping = roomDamping,
                reverbWetMix = wetMix,
                bypass = isBypassed,
            ),
        )
    }
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.effect_reverb), style = MaterialTheme.typography.headlineSmall)

            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
            )

            val presets = listOf(
                ReverbPreset.SMALL_ROOM to stringResource(R.string.reverb_preset_room),
                ReverbPreset.HALL to stringResource(R.string.reverb_preset_hall),
                ReverbPreset.STUDIO_PLATE to stringResource(R.string.reverb_preset_plate),
                ReverbPreset.ECHO_DELAY to stringResource(R.string.reverb_preset_echo),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (preset, label) ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            delayMs = preset.delayMs
                            feedback = preset.feedback
                            roomDamping = preset.roomDamping
                            wetMix = preset.wetMix
                        },
                        label = { Text(label) },
                    )
                }
            }

            Text(stringResource(R.string.reverb_delay_label, delayMs))
            @Suppress("DEPRECATION")
            Slider(
                value = delayMs,
                onValueChange = { selectedPreset = null; delayMs = (it / 5f).roundToInt() * 5f },
                valueRange = 30f..450f,
            )

            Text(stringResource(R.string.reverb_feedback_label, (feedback * 100f).roundToInt()))
            @Suppress("DEPRECATION")
            Slider(
                value = feedback,
                onValueChange = { selectedPreset = null; feedback = (it * 100f).roundToInt() / 100f },
                valueRange = 0.05f..0.75f,
            )

            Text(stringResource(R.string.reverb_damping_label, (roomDamping * 100f).roundToInt()))
            @Suppress("DEPRECATION")
            Slider(
                value = roomDamping,
                onValueChange = { selectedPreset = null; roomDamping = (it * 100f).roundToInt() / 100f },
                valueRange = 0f..0.90f,
            )

            Text(stringResource(R.string.reverb_wet_label, (wetMix * 100f).roundToInt()))
            @Suppress("DEPRECATION")
            Slider(
                value = wetMix,
                onValueChange = { selectedPreset = null; wetMix = (it * 100f).roundToInt() / 100f },
                valueRange = 0.05f..0.60f,
            )

            Button(
                onClick = {
                    onStopPreview()
                    onApply(delayMs, feedback, roomDamping, wetMix)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun StereoBassSheet(
    onDismiss: () -> Unit,
    onApply: (width: Float, bassBoostDb: Float) -> Unit,
    isPlaying: Boolean = false,
    onTogglePreview: () -> Unit = {},
    onLiveDspChange: (LiveDspConfig) -> Unit = {},
    onStopPreview: () -> Unit = {},
) {
    var width by remember { mutableFloatStateOf(1.10f) }
    var bassBoostDb by remember { mutableFloatStateOf(4.5f) }
    var selectedPreset by remember { mutableStateOf<StereoBassPreset?>(StereoBassPreset.WARM_BASS) }
    var isBypassed by remember { mutableStateOf(false) }

    LaunchedEffect(width, bassBoostDb, isBypassed) {
        onLiveDspChange(
            LiveDspConfig(
                stereoWidth = width,
                bassBoostDb = bassBoostDb,
                bypass = isBypassed,
            ),
        )
    }
    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.effect_stereo_bass), style = MaterialTheme.typography.headlineSmall)

            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
            )

            val presets = listOf(
                StereoBassPreset.FOCUSED_MONO to stringResource(R.string.stereo_preset_mono),
                StereoBassPreset.WARM_BASS to stringResource(R.string.stereo_preset_warm),
                StereoBassPreset.WIDE_STAGE to stringResource(R.string.stereo_preset_wide),
                StereoBassPreset.CLUB_BOOST to stringResource(R.string.stereo_preset_club),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (preset, label) ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            width = preset.width
                            bassBoostDb = preset.bassBoostDb
                        },
                        label = { Text(label) },
                    )
                }
            }

            Text(stringResource(R.string.stereo_width_label, (width * 100f).roundToInt()))
            @Suppress("DEPRECATION")
            Slider(
                value = width,
                onValueChange = { selectedPreset = null; width = (it * 20f).roundToInt() / 20f },
                valueRange = 0f..2f,
            )

            Text(stringResource(R.string.bass_boost_label, bassBoostDb))
            @Suppress("DEPRECATION")
            Slider(
                value = bassBoostDb,
                onValueChange = { selectedPreset = null; bassBoostDb = (it * 2f).roundToInt() / 2f },
                valueRange = 0f..9f,
            )

            Button(
                onClick = {
                    onStopPreview()
                    onApply(width, bassBoostDb)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VocalRemoveSheet(
    onDismiss: () -> Unit,
    onApply: (strength: Float, preserveBassHz: Float) -> Unit,
    isPlaying: Boolean,
    onTogglePreview: () -> Unit,
    onLiveDspChange: (LiveDspConfig) -> Unit,
    onStopPreview: () -> Unit,
) {
    var selectedPreset by remember { mutableStateOf<VocalRemovePreset?>(VocalRemovePreset.STANDARD) }
    var strength by remember { mutableFloatStateOf(VocalRemovePreset.STANDARD.strength) }
    var preserveBassHz by remember { mutableFloatStateOf(VocalRemovePreset.STANDARD.preserveBassHz) }
    var isBypassed by remember { mutableStateOf(false) }

    LaunchedEffect(isPlaying, isBypassed, strength, preserveBassHz) {
        if (isPlaying) {
            onLiveDspChange(
                LiveDspConfig(
                    vocalRemoveEnabled = true,
                    vocalRemoveStrength = strength,
                    vocalRemoveBassHz = preserveBassHz,
                    bypass = isBypassed,
                ),
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose { onStopPreview() }
    }

    ModalBottomSheet(onDismissRequest = { onStopPreview(); onDismiss() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.effect_vocal_remove), style = MaterialTheme.typography.headlineSmall)
            Text(
                text = stringResource(R.string.vocal_remove_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            LivePreviewBar(
                isPlaying = isPlaying,
                isBypassed = isBypassed,
                onTogglePlay = onTogglePreview,
                onToggleBypass = { isBypassed = !isBypassed },
            )

            val presets = listOf(
                VocalRemovePreset.LIGHT to stringResource(R.string.vocal_preset_light),
                VocalRemovePreset.STANDARD to stringResource(R.string.vocal_preset_standard),
                VocalRemovePreset.STRONG to stringResource(R.string.vocal_preset_strong),
                VocalRemovePreset.PURE_DIFF to stringResource(R.string.vocal_preset_karaoke),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.forEach { (preset, label) ->
                    FilterChip(
                        selected = selectedPreset == preset,
                        onClick = {
                            selectedPreset = preset
                            strength = preset.strength
                            preserveBassHz = preset.preserveBassHz
                        },
                        label = { Text(label) },
                    )
                }
            }

            Text(stringResource(R.string.vocal_strength_label, (strength * 100f).roundToInt()))
            @Suppress("DEPRECATION")
            Slider(
                value = strength,
                onValueChange = { selectedPreset = null; strength = (it * 20f).roundToInt() / 20f },
                valueRange = 0.25f..1f,
            )

            val bassLabel = if (preserveBassHz <= 40f) {
                stringResource(R.string.vocal_bass_preserve_off)
            } else {
                stringResource(R.string.vocal_bass_preserve_hz, preserveBassHz.roundToInt())
            }
            Text(bassLabel)
            @Suppress("DEPRECATION")
            Slider(
                value = preserveBassHz,
                onValueChange = {
                    selectedPreset = null
                    preserveBassHz = if (it < 50f) 0f else ((it / 10f).roundToInt() * 10f)
                },
                valueRange = 0f..350f,
            )

            Button(
                onClick = {
                    onStopPreview()
                    onApply(strength, preserveBassHz)
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderSheet(
    onFinished: (file: File, displayName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val helper = remember { AudioRecorderHelper() }
    val recState by helper.state.collectAsStateWithLifecycle()
    val levelHistory = remember { mutableStateListOf<Float>() }
    var pendingFileName by remember { mutableStateOf("") }
    var finalizedSuccessfully by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "LibreOpus_Rec_$stamp.wav"
        pendingFileName = name
        val recFile = File(File(context.cacheDir, "recordings").apply { mkdirs() }, name)
        helper.start(recFile)
    }

    LaunchedEffect(recState.rmsLevel, recState.elapsedMs, recState.isPaused) {
        if (recState.isRecording && !recState.isPaused) {
            levelHistory.add(recState.rmsLevel.coerceIn(0.04f, 1f))
            if (levelHistory.size > 36) levelHistory.removeAt(0)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (!finalizedSuccessfully) {
                helper.discard()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            if (!finalizedSuccessfully) helper.discard()
            onDismiss()
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Mic,
                    contentDescription = null,
                    tint = if (recState.isPaused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error,
                )
                Text(
                    text = if (recState.isPaused) {
                        stringResource(R.string.recorder_paused_title)
                    } else {
                        stringResource(R.string.recorder_active_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
            }

            Text(
                text = Formatters.formatDurationMs(recState.elapsedMs),
                style = MaterialTheme.typography.displaySmall,
            )

            Text(
                text = stringResource(R.string.recorder_format_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val barColor = MaterialTheme.colorScheme.primary
            val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        shape = MaterialTheme.shapes.medium,
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Canvas(modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    val bars = 36
                    val slotW = size.width / bars
                    val barW = (slotW * 0.62f).coerceAtLeast(3f)
                    val centerY = size.height / 2f
                    val startOffset = max(0, bars - levelHistory.size)
                    for (i in 0 until bars) {
                        val amp = if (i >= startOffset && (i - startOffset) < levelHistory.size) {
                            levelHistory[i - startOffset]
                        } else {
                            0.05f
                        }
                        val barH = (amp * size.height).coerceAtLeast(4f)
                        val x = i * slotW + (slotW - barW) / 2f
                        drawRoundRect(
                            color = if (i >= startOffset) barColor else trackColor,
                            topLeft = Offset(x, centerY - barH / 2f),
                            size = Size(barW, barH),
                            cornerRadius = CornerRadius(barW / 2f, barW / 2f),
                        )
                    }
                }
            }

            recState.errorMessage?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = {
                        helper.discard()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.recorder_discard), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                FilledTonalButton(
                    onClick = { helper.togglePause() },
                    enabled = recState.isRecording,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (recState.isPaused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = if (recState.isPaused) {
                            stringResource(R.string.recorder_resume)
                        } else {
                            stringResource(R.string.recorder_pause)
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Button(
                onClick = {
                    val wavFile = helper.stopAndFinalize()
                    if (wavFile != null && wavFile.exists()) {
                        finalizedSuccessfully = true
                        onFinished(wavFile, pendingFileName.ifBlank { wavFile.name })
                    }
                    onDismiss()
                },
                enabled = recState.elapsedMs >= 150L,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.recorder_stop_and_edit))
            }
        }
    }
}

