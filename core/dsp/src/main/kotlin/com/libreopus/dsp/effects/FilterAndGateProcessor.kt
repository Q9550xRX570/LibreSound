// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.dsp.storage.AudioSegment
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.ensureActive

object FilterAndGateProcessor {

    suspend fun applyHighLowPass(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        highPassHz: Float?,
        lowPassHz: Float?,
        fourthOrder: Boolean = true,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val sr = timeline.sampleRate.toDouble()
        val ch = timeline.channels
        val cascade = mutableListOf<BiquadFilter>()

        if (highPassHz != null && highPassHz > 5f && highPassHz < sr * 0.45) {
            cascade += createButterworthHighPass(sr, highPassHz.toDouble()).apply { ensureChannels(ch) }
            if (fourthOrder) {
                cascade += createButterworthHighPass(sr, highPassHz.toDouble()).apply { ensureChannels(ch) }
            }
        }
        if (lowPassHz != null && lowPassHz > 50f && lowPassHz < sr * 0.48) {
            cascade += createButterworthLowPass(sr, lowPassHz.toDouble()).apply { ensureChannels(ch) }
            if (fourthOrder) {
                cascade += createButterworthLowPass(sr, lowPassHz.toDouble()).apply { ensureChannels(ch) }
            }
        }
        if (cascade.isEmpty()) return timeline

        return streamProcessRange(timeline, store, startFrame, endFrame, "filter", onProgress) { buf, frames, _ ->
            for (f in 0 until frames) {
                val base = f * ch
                for (c in 0 until ch) {
                    var s = buf[base + c].toDouble()
                    for (flt in cascade) {
                        s = flt.process(s, c)
                    }
                    buf[base + c] = s.toFloat()
                }
            }
        }
    }

    suspend fun applyNoiseGate(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        thresholdDb: Float = -40f,
        reductionDb: Float = -48f,
        attackMs: Float = 8f,
        releaseMs: Float = 90f,
        holdMs: Float = 40f,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val sr = timeline.sampleRate.toFloat().coerceAtLeast(8000f)
        val ch = timeline.channels
        val threshLinear = SoftClipLimiter.dbToLinear(thresholdDb)
        val floorGain = SoftClipLimiter.dbToLinear(reductionDb.coerceAtMost(0f))
        val attackCoeff = exp((-1.0 / (sr * attackMs.coerceAtLeast(0.5f) / 1000f))).toFloat()
        val releaseCoeff = exp((-1.0 / (sr * releaseMs.coerceAtLeast(1f) / 1000f))).toFloat()
        val envAttack = exp((-1.0 / (sr * 0.002f))).toFloat()
        val envRelease = exp((-1.0 / (sr * 0.050f))).toFloat()
        val holdFrames = (sr * holdMs.coerceAtLeast(0f) / 1000f).toInt()

        var env = 0f
        var gateGain = floorGain
        var holdRemaining = 0

        return streamProcessRange(timeline, store, startFrame, endFrame, "gate", onProgress) { buf, frames, _ ->
            for (f in 0 until frames) {
                val base = f * ch
                var peakFrame = 0f
                for (c in 0 until ch) {
                    val a = abs(buf[base + c])
                    if (a > peakFrame) peakFrame = a
                }
                env = if (peakFrame > env) {
                    envAttack * env + (1f - envAttack) * peakFrame
                } else {
                    envRelease * env + (1f - envRelease) * peakFrame
                }

                val targetGain = if (env >= threshLinear) {
                    holdRemaining = holdFrames
                    1f
                } else if (holdRemaining > 0) {
                    holdRemaining--
                    1f
                } else {
                    floorGain
                }

                gateGain = if (targetGain > gateGain) {
                    attackCoeff * gateGain + (1f - attackCoeff) * targetGain
                } else {
                    releaseCoeff * gateGain + (1f - releaseCoeff) * targetGain
                }

                for (c in 0 until ch) {
                    buf[base + c] *= gateGain
                }
            }
        }
    }

    /**
     * Removes silent runs longer than [minSilenceMs] below [thresholdDb], keeping [keepPaddingMs]
     * at edges with short cross-fades to avoid clicks.
     */
    suspend fun trimSilence(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        thresholdDb: Float = -42f,
        minSilenceMs: Int = 350,
        keepPaddingMs: Int = 40,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val s = startFrame.coerceIn(0L, timeline.totalFrames)
        val e = endFrame.coerceIn(s, timeline.totalFrames)
        val total = e - s
        if (total <= 0L) return timeline

        val sr = timeline.sampleRate.coerceAtLeast(8000)
        val ch = timeline.channels
        val thresh = SoftClipLimiter.dbToLinear(thresholdDb)
        val minSilentFrames = ((minSilenceMs.toLong() * sr) / 1000L).coerceAtLeast(1L)
        val padFrames = ((keepPaddingMs.toLong() * sr) / 1000L).coerceAtLeast(0L)

        // Pass 1: find silent intervals
        val silentRuns = mutableListOf<LongRange>()
        val buf = ShortArray(8192 * ch)
        var cursor = 0L
        var runStart = -1L

        while (cursor < total) {
            coroutineContext.ensureActive()
            val toRead = min(8192L, total - cursor).toInt()
            val read = timeline.readFrames(s + cursor, buf, 0, toRead)
            if (read <= 0) break

            for (f in 0 until read) {
                var peak = 0f
                val base = f * ch
                for (c in 0 until ch) {
                    val a = abs(buf[base + c] / 32768f)
                    if (a > peak) peak = a
                }
                val framePos = s + cursor + f
                if (peak < thresh) {
                    if (runStart < 0L) runStart = framePos
                } else if (runStart >= 0L) {
                    if (framePos - runStart >= minSilentFrames) {
                        silentRuns += runStart until framePos
                    }
                    runStart = -1L
                }
            }
            cursor += read
            onProgress((cursor.toFloat() / total) * 0.7f)
        }
        if (runStart >= 0L && e - runStart >= minSilentFrames) {
            silentRuns += runStart until e
        }

        if (silentRuns.isEmpty()) {
            onProgress(1f)
            return timeline
        }

        // Convert long silent runs into cut regions while preserving padding
        val keptSegments = mutableListOf<AudioSegment>()
        if (s > 0L) keptSegments += timeline.extractSlice(0L, s)

        var keepCursor = s
        for (run in silentRuns) {
            val cutStart = if (run.first == s) run.first else min(run.first + padFrames, run.last + 1)
            val cutEnd = if (run.last + 1 == e) run.last + 1 else max(cutStart, (run.last + 1) - padFrames)
            if (cutStart > keepCursor) {
                keptSegments += timeline.extractSlice(keepCursor, cutStart)
            }
            keepCursor = max(keepCursor, cutEnd)
        }
        if (keepCursor < e) {
            keptSegments += timeline.extractSlice(keepCursor, e)
        }
        if (e < timeline.totalFrames) {
            keptSegments += timeline.extractSlice(e, timeline.totalFrames)
        }

        // Ensure at least a tiny slice remains if the whole file was silence
        if (keptSegments.isEmpty()) {
            return EditTimeline(sr, ch, emptyList())
        }

        onProgress(1f)
        return EditTimeline(sr, ch, keptSegments)
    }

    private fun createButterworthHighPass(fs: Double, f0: Double): BiquadFilter {
        val w0 = 2.0 * PI * f0 / fs
        val cosW0 = cos(w0)
        val alpha = sin(w0) / sqrt(2.0)
        val a0 = 1.0 + alpha
        return BiquadFilter(
            b0 = ((1.0 + cosW0) / 2.0) / a0,
            b1 = (-(1.0 + cosW0)) / a0,
            b2 = ((1.0 + cosW0) / 2.0) / a0,
            a1 = (-2.0 * cosW0) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }

    private fun createButterworthLowPass(fs: Double, f0: Double): BiquadFilter {
        val w0 = 2.0 * PI * f0 / fs
        val cosW0 = cos(w0)
        val alpha = sin(w0) / sqrt(2.0)
        val a0 = 1.0 + alpha
        return BiquadFilter(
            b0 = ((1.0 - cosW0) / 2.0) / a0,
            b1 = (1.0 - cosW0) / a0,
            b2 = ((1.0 - cosW0) / 2.0) / a0,
            a1 = (-2.0 * cosW0) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }
}
