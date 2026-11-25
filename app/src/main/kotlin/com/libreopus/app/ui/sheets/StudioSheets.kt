// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.sheets

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.libreopus.app.R
import com.libreopus.dsp.effects.ChannelOperation
import com.libreopus.dsp.effects.EqPreset
import com.libreopus.dsp.effects.EqualizerProcessor
import com.libreopus.dsp.effects.FadeCurve
import com.libreopus.dsp.effects.FadeProcessor
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GainNormalizeSheet(
    onDismiss: () -> Unit,
    onApplyGain: (Float) -> Unit,
    onNormalizePeak: (Float) -> Unit,
    onNormalizeLufs: (Float) -> Unit,
) {
    var gainDb by remember { mutableFloatStateOf(0f) }
    var targetLufs by remember { mutableFloatStateOf(-14f) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.effect_gain_norm), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.gain_db_label, gainDb))
            Slider(value = gainDb, onValueChange = { gainDb = it }, valueRange = -24f..24f)
            Button(
                onClick = { onApplyGain(gainDb); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.apply))
            }

            Spacer(Modifier.height(4.dp))
            FilledTonalButton(
                onClick = { onNormalizePeak(-1.0f); onDismiss() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.norm_peak_button))
            }

            Text(stringResource(R.string.lufs_target_label, targetLufs))
            Slider(value = targetLufs, onValueChange = { targetLufs = it.roundToInt().toFloat() }, valueRange = -24f..-9f)
            FilledTonalButton(
                onClick = { onNormalizeLufs(targetLufs); onDismiss() },
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
) {
    val bands = remember { mutableStateListOf(0f, 0f, 0f, 0f, 0f, 0f, 0f) }
    val freqs = EqualizerProcessor.BAND_FREQUENCIES_HZ

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.effect_eq), style = MaterialTheme.typography.headlineSmall)
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
                onClick = { onApplyEq(bands.toList()); onDismiss() },
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
) {
    var linked by remember { mutableStateOf(false) }
    var tempo by remember { mutableFloatStateOf(1.0f) }
    var pitch by remember { mutableFloatStateOf(0f) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.effect_speed_pitch), style = MaterialTheme.typography.headlineSmall)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.speed_linked))
                Switch(checked = linked, onCheckedChange = { linked = it })
            }
            Text(stringResource(R.string.speed_tempo_label, tempo))
            Slider(value = tempo, onValueChange = { tempo = (it * 20f).roundToInt() / 20f }, valueRange = 0.5f..2.0f)
            if (!linked) {
                Text(stringResource(R.string.speed_pitch_label, pitch))
                Slider(value = pitch, onValueChange = { pitch = it.roundToInt().toFloat() }, valueRange = -12f..12f)
            }
            Button(
                onClick = { onApply(tempo, pitch, linked); onDismiss() },
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
) {
    var enableHp by remember { mutableStateOf(true) }
    var hpHz by remember { mutableFloatStateOf(80f) }
    var enableLp by remember { mutableStateOf(false) }
    var lpHz by remember { mutableFloatStateOf(12000f) }
    var enableGate by remember { mutableStateOf(false) }
    var gateDb by remember { mutableFloatStateOf(-40f) }
    var silenceDb by remember { mutableFloatStateOf(-42f) }
    var silenceMinMs by remember { mutableIntStateOf(350) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.effect_filter_gate), style = MaterialTheme.typography.headlineSmall)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filter_enable_hp))
                Switch(checked = enableHp, onCheckedChange = { enableHp = it })
            }
            if (enableHp) {
                Text(stringResource(R.string.filter_hp_label, hpHz.roundToInt()))
                Slider(value = hpHz, onValueChange = { hpHz = it }, valueRange = 30f..1000f)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.filter_enable_lp))
                Switch(checked = enableLp, onCheckedChange = { enableLp = it })
            }
            if (enableLp) {
                Text(stringResource(R.string.filter_lp_label, lpHz.roundToInt()))
                Slider(value = lpHz, onValueChange = { lpHz = it }, valueRange = 1500f..18000f)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.gate_enable))
                Switch(checked = enableGate, onCheckedChange = { enableGate = it })
            }
            if (enableGate) {
                Text(stringResource(R.string.gate_threshold_label, gateDb))
                Slider(value = gateDb, onValueChange = { gateDb = it.roundToInt().toFloat() }, valueRange = -65f..-15f)
            }

            Button(
                onClick = {
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
            Slider(value = silenceDb, onValueChange = { silenceDb = it.roundToInt().toFloat() }, valueRange = -65f..-20f)
            Text(stringResource(R.string.silence_min_ms_label, silenceMinMs))
            Slider(value = silenceMinMs.toFloat(), onValueChange = { silenceMinMs = it.roundToInt() }, valueRange = 150f..2000f)
            FilledTonalButton(
                onClick = { onTrimSilence(silenceDb, silenceMinMs); onDismiss() },
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
