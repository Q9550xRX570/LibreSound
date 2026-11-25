// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.ensureActive

object GainAndNormalizeProcessor {

    suspend fun applyGain(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        gainDb: Float,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val gain = SoftClipLimiter.dbToLinear(gainDb)
        return streamProcessRange(timeline, store, startFrame, endFrame, "gain", onProgress) { buf, frames, _ ->
            val count = frames * timeline.channels
            for (i in 0 until count) {
                buf[i] *= gain
            }
        }
    }

    suspend fun normalizePeak(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        targetDbfs: Float = -1.0f,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val s = startFrame.coerceIn(0L, timeline.totalFrames)
        val e = endFrame.coerceIn(s, timeline.totalFrames)
        if (e <= s) return timeline

        val peak = measurePeakLinear(timeline, s, e) { p -> onProgress(p * 0.4f) }
        if (peak <= 1e-6f) return timeline

        val targetLinear = SoftClipLimiter.dbToLinear(targetDbfs.coerceIn(-30f, 0f))
        val gain = targetLinear / peak

        return streamProcessRange(timeline, store, s, e, "norm_peak", { p -> onProgress(0.4f + p * 0.6f) }) { buf, frames, _ ->
            val count = frames * timeline.channels
            for (i in 0 until count) {
                buf[i] *= gain
            }
        }
    }

    /**
     * Two-pass ITU-R BS.1770 K-weighted integrated loudness normalization (LUFS).
     */
    suspend fun normalizeLufs(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        targetLufs: Float = -14.0f,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val s = startFrame.coerceIn(0L, timeline.totalFrames)
        val e = endFrame.coerceIn(s, timeline.totalFrames)
        if (e <= s) return timeline

        val measuredLufs = measureIntegratedLufs(timeline, s, e) { p -> onProgress(p * 0.45f) }
        if (!measuredLufs.isFinite() || measuredLufs < -65f) {
            return normalizePeak(timeline, store, s, e, -1.0f, onProgress)
        }

        val gainDb = (targetLufs - measuredLufs).coerceIn(-30f, 30f)
        val gain = SoftClipLimiter.dbToLinear(gainDb)
        return streamProcessRange(timeline, store, s, e, "norm_lufs", { p -> onProgress(0.45f + p * 0.55f) }) { buf, frames, _ ->
            val count = frames * timeline.channels
            for (i in 0 until count) {
                buf[i] *= gain
            }
        }
    }

    suspend fun measurePeakLinear(
        reader: PcmTimelineReader,
        startFrame: Long = 0L,
        endFrame: Long = reader.totalFrames,
        onProgress: (Float) -> Unit = {},
    ): Float {
        val s = startFrame.coerceIn(0L, reader.totalFrames)
        val e = endFrame.coerceIn(s, reader.totalFrames)
        val total = e - s
        if (total <= 0L) return 0f
        val channels = reader.channels
        val buf = ShortArray(8192 * channels)
        var cursor = 0L
        var peak = 0f
        while (cursor < total) {
            coroutineContext.ensureActive()
            val toRead = min(8192L, total - cursor).toInt()
            val read = reader.readFrames(s + cursor, buf, 0, toRead)
            if (read <= 0) break
            for (i in 0 until read * channels) {
                val a = abs(buf[i] / 32768f)
                if (a > peak) peak = a
            }
            cursor += read
            onProgress(cursor.toFloat() / total)
        }
        return peak
    }

    /**
     * Computes ITU-R BS.1770 K-weighted integrated loudness (LUFS) with -70 LUFS absolute
     * and -10 LU relative gating over 400 ms blocks (75% overlap / 100 ms step).
     */
    suspend fun measureIntegratedLufs(
        reader: PcmTimelineReader,
        startFrame: Long = 0L,
        endFrame: Long = reader.totalFrames,
        onProgress: (Float) -> Unit = {},
    ): Float {
        val s = startFrame.coerceIn(0L, reader.totalFrames)
        val e = endFrame.coerceIn(s, reader.totalFrames)
        val total = e - s
        val sr = reader.sampleRate.coerceAtLeast(8000)
        val channels = reader.channels.coerceAtLeast(1)
        if (total <= 0L) return Float.NEGATIVE_INFINITY

        val stage1 = createKWeightStage1HighShelf(sr.toDouble()).apply { ensureChannels(channels) }
        val stage2 = createKWeightStage2HighPass(sr.toDouble()).apply { ensureChannels(channels) }

        val stepFrames = (sr * 0.100).toInt().coerceAtLeast(1) // 100 ms hop
        val blockStepMeans = mutableListOf<Double>()
        var accEnergy = 0.0
        var accFrames = 0

        val buf = ShortArray(8192 * channels)
        var cursor = 0L
        while (cursor < total) {
            coroutineContext.ensureActive()
            val toRead = min(8192L, total - cursor).toInt()
            val read = reader.readFrames(s + cursor, buf, 0, toRead)
            if (read <= 0) break

            for (f in 0 until read) {
                var framePower = 0.0
                val base = f * channels
                for (ch in 0 until channels) {
                    val x = buf[base + ch] / 32768.0
                    val y1 = stage1.process(x, ch)
                    val y2 = stage2.process(y1, ch)
                    framePower += y2 * y2
                }
                accEnergy += framePower
                accFrames++
                if (accFrames >= stepFrames) {
                    blockStepMeans += accEnergy / accFrames
                    accEnergy = 0.0
                    accFrames = 0
                }
            }
            cursor += read
            onProgress(cursor.toFloat() / total)
        }
        if (accFrames > 0) {
            blockStepMeans += accEnergy / accFrames
        }
        if (blockStepMeans.isEmpty()) return Float.NEGATIVE_INFINITY

        // Form 400 ms overlapping blocks (4 consecutive 100 ms steps) or single global if shorter
        val blockEnergies = if (blockStepMeans.size >= 4) {
            List(blockStepMeans.size - 3) { i ->
                (blockStepMeans[i] + blockStepMeans[i + 1] + blockStepMeans[i + 2] + blockStepMeans[i + 3]) / 4.0
            }
        } else {
            listOf(blockStepMeans.average())
        }

        val absThresholdPower = 10.0.pow((-70.0 + 0.691) / 10.0)
        val aboveAbs = blockEnergies.filter { it >= absThresholdPower }
        if (aboveAbs.isEmpty()) return Float.NEGATIVE_INFINITY

        val absMean = aboveAbs.average()
        val relGateLufs = -0.691 + 10.0 * log10(absMean) - 10.0
        val relThresholdPower = 10.0.pow((relGateLufs + 0.691) / 10.0)
        val gated = aboveAbs.filter { it >= relThresholdPower }
        val finalPower = (if (gated.isNotEmpty()) gated else aboveAbs).average()
        return (-0.691 + 10.0 * log10(max(finalPower, 1e-12))).toFloat()
    }

    private fun createKWeightStage1HighShelf(fs: Double): BiquadFilter {
        // BS.1770 pre-filter: high-shelf +4 dB around 1681.97 Hz, Q = 0.707175
        val db = 3.999843853973347
        val f0 = 1681.974450955533
        val q = 0.7071752369554196
        val k = kotlin.math.tan(PI * f0 / fs)
        val vh = 10.0.pow(db / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return BiquadFilter(
            b0 = (vh + vb * k / q + k * k) / a0,
            b1 = 2.0 * (k * k - vh) / a0,
            b2 = (vh - vb * k / q + k * k) / a0,
            a1 = 2.0 * (k * k - 1.0) / a0,
            a2 = (1.0 - k / q + k * k) / a0,
        )
    }

    private fun createKWeightStage2HighPass(fs: Double): BiquadFilter {
        // BS.1770 RLB weighting: 2nd-order high-pass at ~38.135 Hz
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val w0 = 2.0 * PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha
        return BiquadFilter(
            b0 = ((1.0 + cosW0) / 2.0) / a0,
            b1 = (-(1.0 + cosW0)) / a0,
            b2 = ((1.0 + cosW0) / 2.0) / a0,
            a1 = (-2.0 * cosW0) / a0,
            a2 = (1.0 - alpha) / a0,
        )
    }
}
