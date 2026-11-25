// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.libreopus.app.data.Formatters
import com.libreopus.audio.api.WaveformPyramid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class DragTarget { NONE, START_HANDLE, END_HANDLE, PLAYHEAD }

@Composable
fun WaveformCanvas(
    waveform: WaveformPyramid?,
    durationMs: Long,
    selectionStartMs: Long,
    selectionEndMs: Long,
    playheadMs: Long,
    zoom: Float,
    scrollFraction: Float,
    onZoomChange: (newZoom: Float, newScrollFraction: Float) -> Unit,
    onSelectionChange: (startMs: Long, endMs: Long) -> Unit,
    onSeek: (ms: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val surfaceBg = colorScheme.surfaceContainerLow
    val rulerColor = colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
    val gridColor = colorScheme.outlineVariant.copy(alpha = 0.35f)
    val peakColor = colorScheme.primary.copy(alpha = 0.42f)
    val rmsColor = colorScheme.primary
    val selectedPeakColor = colorScheme.tertiary.copy(alpha = 0.55f)
    val selectedRmsColor = colorScheme.tertiary
    val selectionTint = colorScheme.primaryContainer.copy(alpha = 0.28f)
    val handleColor = colorScheme.tertiary
    val playheadColor = colorScheme.error

    val handleHitRadiusPx = with(density) { 26.dp.toPx() }
    val rulerHeightPx = with(density) { 24.dp.toPx() }

    var activeDrag by remember { mutableStateOf(DragTarget.NONE) }
    var canvasWidthPx by remember { mutableFloatStateOf(1f) }

    val safeDuration = durationMs.coerceAtLeast(1L)
    val safeZoom = zoom.coerceIn(1f, 64f)
    val visibleSpanMs = (safeDuration / safeZoom).toLong().coerceAtLeast(10L)
    val maxScrollMs = (safeDuration - visibleSpanMs).coerceAtLeast(0L)
    val viewStartMs = (scrollFraction.coerceIn(0f, 1f) * maxScrollMs).toLong()
    val viewEndMs = (viewStartMs + visibleSpanMs).coerceAtMost(safeDuration)

    fun msToX(ms: Long, width: Float): Float {
        return (((ms - viewStartMs).toFloat() / (viewEndMs - viewStartMs).coerceAtLeast(1L)) * width)
    }

    fun xToMs(x: Float, width: Float): Long {
        val ratio = (x / width.coerceAtLeast(1f)).coerceIn(0f, 1f)
        return (viewStartMs + (ratio * (viewEndMs - viewStartMs))).toLong().coerceIn(0L, safeDuration)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(210.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(surfaceBg)
            .pointerInput(durationMs, zoom, scrollFraction) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    if (activeDrag == DragTarget.NONE) {
                        val oldZoom = safeZoom
                        val newZoom = (oldZoom * gestureZoom).coerceIn(1f, 64f)
                        val width = size.width.toFloat().coerceAtLeast(1f)
                        val oldSpan = safeDuration / oldZoom
                        val newSpan = safeDuration / newZoom
                        val centroidFrac = (centroid.x / width).coerceIn(0f, 1f)
                        val anchorMs = viewStartMs + centroidFrac * oldSpan
                        val panMs = -(pan.x / width) * newSpan
                        val newStart = (anchorMs - centroidFrac * newSpan + panMs)
                            .coerceIn(0f, (safeDuration - newSpan).coerceAtLeast(0f))
                        val newScrollFrac = if (safeDuration - newSpan <= 1f) 0f else newStart / (safeDuration - newSpan)
                        onZoomChange(newZoom, newScrollFrac)
                    }
                }
            }
            .pointerInput(durationMs, selectionStartMs, selectionEndMs, zoom, scrollFraction) {
                detectTapGestures { offset ->
                    val tappedMs = xToMs(offset.x, size.width.toFloat())
                    onSeek(tappedMs)
                }
            }
            .pointerInput(durationMs, selectionStartMs, selectionEndMs, zoom, scrollFraction) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val w = size.width.toFloat()
                        val sx = msToX(selectionStartMs, w)
                        val ex = msToX(selectionEndMs, w)
                        val dStart = abs(offset.x - sx)
                        val dEnd = abs(offset.x - ex)
                        activeDrag = when {
                            dStart <= handleHitRadiusPx && dStart <= dEnd -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                DragTarget.START_HANDLE
                            }
                            dEnd <= handleHitRadiusPx -> {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                DragTarget.END_HANDLE
                            }
                            else -> DragTarget.PLAYHEAD
                        }
                    },
                    onDragEnd = { activeDrag = DragTarget.NONE },
                    onDragCancel = { activeDrag = DragTarget.NONE },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        val w = size.width.toFloat().coerceAtLeast(1f)
                        val targetMs = xToMs(change.position.x, w)
                        when (activeDrag) {
                            DragTarget.START_HANDLE -> {
                                onSelectionChange(min(targetMs, selectionEndMs - 10L).coerceAtLeast(0L), selectionEndMs)
                            }
                            DragTarget.END_HANDLE -> {
                                onSelectionChange(selectionStartMs, max(targetMs, selectionStartMs + 10L).coerceAtMost(safeDuration))
                            }
                            DragTarget.PLAYHEAD -> {
                                if (zoom > 1.05f && abs(dragAmount.x) > 0f) {
                                    // Pan smoothly when dragging middle in zoomed state, or seek if near top ruler
                                    if (change.position.y < rulerHeightPx * 1.5f) {
                                        onSeek(targetMs)
                                    } else {
                                        val deltaFrac = (-dragAmount.x / w) / (safeZoom - 1f).coerceAtLeast(0.1f)
                                        onZoomChange(safeZoom, (scrollFraction + deltaFrac).coerceIn(0f, 1f))
                                    }
                                } else {
                                    onSeek(targetMs)
                                }
                            }
                            DragTarget.NONE -> Unit
                        }
                    },
                )
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            canvasWidthPx = size.width
            val w = size.width
            val h = size.height
            val waveTop = rulerHeightPx + 4f
            val waveHeight = (h - waveTop - 8f).coerceAtLeast(20f)
            val centerY = waveTop + waveHeight / 2f

            // 1. Time Ruler & subtle grid
            drawLine(
                color = gridColor,
                start = Offset(0f, rulerHeightPx),
                end = Offset(w, rulerHeightPx),
                strokeWidth = 1.5f,
            )
            drawLine(
                color = gridColor,
                start = Offset(0f, centerY),
                end = Offset(w, centerY),
                strokeWidth = 1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)),
            )

            val tickCount = 5
            val labelStyle = TextStyle(color = rulerColor, fontSize = 10.sp)
            for (i in 0..tickCount) {
                val frac = i.toFloat() / tickCount
                val x = frac * w
                val tMs = viewStartMs + (frac * (viewEndMs - viewStartMs)).toLong()
                drawLine(color = rulerColor, start = Offset(x, rulerHeightPx - 6f), end = Offset(x, rulerHeightPx), strokeWidth = 1.5f)
                val label = Formatters.formatDurationMs(tMs, includeMillis = visibleSpanMs < 15_000L)
                val measured = textMeasurer.measure(label, labelStyle)
                val tx = x.coerceIn(4f, (w - measured.size.width - 4f).coerceAtLeast(4f))
                drawText(measured, topLeft = Offset(tx, 3f))
            }

            // 2. Selection Overlay
            val selStartX = msToX(selectionStartMs, w).coerceIn(0f, w)
            val selEndX = msToX(selectionEndMs, w).coerceIn(0f, w)
            if (selEndX > selStartX) {
                drawRect(
                    color = selectionTint,
                    topLeft = Offset(selStartX, waveTop),
                    size = Size(selEndX - selStartX, waveHeight),
                )
            }

            // 3. Multi-resolution Waveform Bars (Min/Max envelope + RMS core)
            if (waveform != null && waveform.totalFrames > 0L) {
                val sr = waveform.sampleRate.coerceAtLeast(1)
                val visStartFrame = (viewStartMs * sr) / 1000L
                val visEndFrame = ((viewEndMs * sr) / 1000L).coerceIn(visStartFrame + 1L, waveform.totalFrames)
                val visFrames = visEndFrame - visStartFrame
                val level = waveform.bestLevelFor(visFrames, w.toInt())

                if (level != null && level.binCount > 0) {
                    val barWidthPx = 4f
                    val barStepPx = 6f
                    val bars = (w / barStepPx).toInt().coerceAtLeast(1)
                    val halfWave = (waveHeight / 2f) * 0.92f

                    for (b in 0 until bars) {
                        val x = b * barStepPx
                        val f0 = visStartFrame + ((b.toDouble() / bars) * visFrames).toLong()
                        val f1 = visStartFrame + (((b + 1).toDouble() / bars) * visFrames).toLong()
                        val bin0 = (f0 / level.framesPerBin).toInt().coerceIn(0, level.binCount - 1)
                        val bin1 = (f1 / level.framesPerBin).toInt().coerceIn(bin0, level.binCount - 1)

                        var maxPeak = 0f
                        var maxRms = 0f
                        for (idx in bin0..bin1) {
                            val amp = max(abs(level.minPeaks[idx]), abs(level.maxPeaks[idx]))
                            if (amp > maxPeak) maxPeak = amp
                            if (level.rms[idx] > maxRms) maxRms = level.rms[idx]
                        }

                        val inSelection = x in selStartX..selEndX
                        val pH = (maxPeak.coerceIn(0f, 1f) * halfWave).coerceAtLeast(2f)
                        val rH = (maxRms.coerceIn(0f, 1f) * halfWave).coerceAtLeast(1.5f)

                        drawRect(
                            color = if (inSelection) selectedPeakColor else peakColor,
                            topLeft = Offset(x, centerY - pH),
                            size = Size(barWidthPx, pH * 2f),
                        )
                        drawRect(
                            color = if (inSelection) selectedRmsColor else rmsColor,
                            topLeft = Offset(x, centerY - rH),
                            size = Size(barWidthPx, rH * 2f),
                        )
                    }
                }
            }

            // 4. Selection Start & End Handles
            drawSelectionHandle(selStartX, waveTop, waveHeight, handleColor, isStart = true)
            drawSelectionHandle(selEndX, waveTop, waveHeight, handleColor, isStart = false)

            // 5. Playhead Cursor
            val playX = msToX(playheadMs, w)
            if (playX in 0f..w) {
                drawLine(
                    color = playheadColor,
                    start = Offset(playX, rulerHeightPx),
                    end = Offset(playX, h),
                    strokeWidth = 3.5f,
                )
                drawCircle(
                    color = playheadColor,
                    radius = 7f,
                    center = Offset(playX, rulerHeightPx + 4f),
                )
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSelectionHandle(
    x: Float,
    top: Float,
    height: Float,
    color: Color,
    isStart: Boolean,
) {
    val clampedX = x.coerceIn(2f, size.width - 2f)
    drawLine(
        color = color,
        start = Offset(clampedX, top),
        end = Offset(clampedX, top + height),
        strokeWidth = 4f,
    )
    val knobY = if (isStart) top + 14f else top + height - 14f
    drawCircle(
        color = color,
        radius = 12f,
        center = Offset(clampedX, knobY),
    )
}
