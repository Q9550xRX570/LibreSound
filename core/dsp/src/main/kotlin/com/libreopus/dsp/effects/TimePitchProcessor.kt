// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.ensureActive

/**
 * 4-point cubic Hermite fractional resampler (also used prior to 48 kHz Opus encoding).
 */
object HermiteResampler {

    fun resampleInterleaved(
        input: FloatArray,
        inputFrames: Int,
        channels: Int,
        sourceRate: Int,
        targetRate: Int,
    ): FloatArray {
        if (inputFrames <= 0 || sourceRate <= 0 || targetRate <= 0) return FloatArray(0)
        if (sourceRate == targetRate) return input.copyOf(inputFrames * channels)
        val ratio = sourceRate.toDouble() / targetRate.toDouble()
        return resampleByRatio(input, inputFrames, channels, ratio)
    }

    fun resampleByRatio(
        input: FloatArray,
        inputFrames: Int,
        channels: Int,
        srcStepPerOutFrame: Double,
    ): FloatArray {
        if (inputFrames <= 1 || srcStepPerOutFrame <= 0.0) return FloatArray(0)
        val outFrames = ((inputFrames - 1) / srcStepPerOutFrame).toInt().coerceAtLeast(1)
        val out = FloatArray(outFrames * channels)

        for (outIdx in 0 until outFrames) {
            val pos = outIdx * srcStepPerOutFrame
            val i1 = pos.toInt().coerceIn(0, inputFrames - 1)
            val mu = (pos - i1).toFloat()
            val i0 = max(0, i1 - 1)
            val i2 = min(inputFrames - 1, i1 + 1)
            val i3 = min(inputFrames - 1, i1 + 2)

            val outBase = outIdx * channels
            for (c in 0 until channels) {
                val y0 = input[i0 * channels + c]
                val y1 = input[i1 * channels + c]
                val y2 = input[i2 * channels + c]
                val y3 = input[i3 * channels + c]
                out[outBase + c] = hermite4(y0, y1, y2, y3, mu)
            }
        }
        return out
    }

    private fun hermite4(y0: Float, y1: Float, y2: Float, y3: Float, mu: Float): Float {
        val c0 = y1
        val c1 = 0.5f * (y2 - y0)
        val c2 = y0 - 2.5f * y1 + 2f * y2 - 0.5f * y3
        val c3 = 0.5f * (y3 - y0) + 1.5f * (y1 - y2)
        return ((c3 * mu + c2) * mu + c1) * mu + c0
    }
}

/**
 * Waveform Similarity Overlap-Add (WSOLA) time-stretcher preserving pitch cleanly.
 */
object WsolaTimeStretch {

    suspend fun stretch(
        input: FloatArray,
        inputFrames: Int,
        channels: Int,
        sampleRate: Int,
        tempoFactor: Float,
        onProgress: (Float) -> Unit = {},
    ): FloatArray {
        val factor = tempoFactor.coerceIn(0.25f, 4.0f)
        if (abs(factor - 1f) < 0.005f || inputFrames < 64) {
            return input.copyOf(inputFrames * channels)
        }

        val winSize = ((sampleRate * 0.032).roundToInt().coerceIn(256, 2048)) and -2
        val halfWin = winSize / 2
        val searchRadius = (sampleRate * 0.012).roundToInt().coerceIn(32, halfWin)
        val outFrames = (inputFrames / factor).roundToInt().coerceAtLeast(1)

        if (inputFrames <= winSize) {
            return HermiteResampler.resampleByRatio(input, inputFrames, channels, factor.toDouble())
        }

        // Hann window
        val window = FloatArray(winSize) { i ->
            (0.5 * (1.0 - cos(2.0 * PI * i / winSize))).toFloat()
        }

        val output = FloatArray((outFrames + winSize) * channels)
        val normAccum = FloatArray(outFrames + winSize)

        var outPos = 0
        var lastSrcCenter = 0

        // Copy initial window
        for (i in 0 until min(winSize, inputFrames)) {
            val w = window[i]
            for (c in 0 until channels) {
                output[i * channels + c] += input[i * channels + c] * w
            }
            normAccum[i] += w
        }
        outPos += halfWin

        while (outPos < outFrames) {
            coroutineContext.ensureActive()
            val nominalSrc = (outPos * factor).roundToInt().coerceIn(0, inputFrames - winSize)
            val expectedContinuation = (lastSrcCenter + halfWin).coerceIn(0, inputFrames - winSize)

            // Search around nominalSrc for maximum cross-correlation with expectedContinuation
            val searchStart = max(0, nominalSrc - searchRadius)
            val searchEnd = min(inputFrames - winSize, nominalSrc + searchRadius)
            var bestSrc = nominalSrc
            var bestCorr = Double.NEGATIVE_INFINITY

            for (cand in searchStart..searchEnd step 2) {
                var corr = 0.0
                for (k in 0 until halfWin step 2) {
                    val idxA = (expectedContinuation + k) * channels
                    val idxB = (cand + k) * channels
                    corr += input[idxA] * input[idxB]
                }
                if (corr > bestCorr) {
                    bestCorr = corr
                    bestSrc = cand
                }
            }

            for (i in 0 until winSize) {
                val oFrame = outPos + i
                if (oFrame >= normAccum.size) break
                val sFrame = bestSrc + i
                if (sFrame >= inputFrames) break
                val w = window[i]
                for (c in 0 until channels) {
                    output[oFrame * channels + c] += input[sFrame * channels + c] * w
                }
                normAccum[oFrame] += w
            }

            lastSrcCenter = bestSrc
            outPos += halfWin
            onProgress((outPos.toFloat() / outFrames).coerceIn(0f, 1f))
        }

        val trimmed = FloatArray(outFrames * channels)
        for (f in 0 until outFrames) {
            val norm = normAccum[f]
            val inv = if (norm > 1e-4f) 1f / norm else 1f
            for (c in 0 until channels) {
                trimmed[f * channels + c] = output[f * channels + c] * inv
            }
        }
        return trimmed
    }
}

object TimePitchProcessor {

    suspend fun applySpeedAndPitch(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        tempoFactor: Float,
        pitchSemitones: Float,
        linkedSpeedPitch: Boolean,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val s = startFrame.coerceIn(0L, timeline.totalFrames)
        val e = endFrame.coerceIn(s, timeline.totalFrames)
        if (e <= s) return timeline

        val sr = timeline.sampleRate
        val ch = timeline.channels
        val input = readTimelineAllFloat(timeline, s, e) { p -> onProgress(p * 0.2f) }
        val inputFrames = input.size / ch

        val processed: FloatArray = if (linkedSpeedPitch) {
            val ratio = tempoFactor.coerceIn(0.25f, 4.0f).toDouble()
            onProgress(0.6f)
            HermiteResampler.resampleByRatio(input, inputFrames, ch, ratio)
        } else {
            val pitchRatio = 2.0.pow(pitchSemitones.coerceIn(-12f, 12f) / 12.0)
            val pitched = if (abs(pitchSemitones) >= 0.05f) {
                HermiteResampler.resampleByRatio(input, inputFrames, ch, pitchRatio)
            } else {
                input
            }
            val pitchedFrames = pitched.size / ch
            // When pitch changes by pitchRatio, duration shrinks by 1/pitchRatio; WSOLA compensates
            val effectiveStretch = (tempoFactor.coerceIn(0.25f, 4.0f) / pitchRatio).toFloat()
            WsolaTimeStretch.stretch(pitched, pitchedFrames, ch, sr, effectiveStretch) { p ->
                onProgress(0.2f + p * 0.7f)
            }
        }

        val outFrames = processed.size / ch
        val outFile = store.createChunkFile("timepitch")
        val shortBuf = ShortArray(processed.size)
        for (i in processed.indices) {
            shortBuf[i] = SoftClipLimiter.floatToPcm16(processed[i])
        }
        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
            val bytes = ByteArray(shortBuf.size * 2)
            DiskPcmStore.encodeSamplesToBytesLe(shortBuf, shortBuf.size, bytes)
            out.write(bytes)
        }
        onProgress(1f)
        return timeline.replaceRange(s, e, outFile, outFrames.toLong())
    }
}
