// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.waveform

import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.audio.api.WaveformLevel
import com.libreopus.audio.api.WaveformPyramid
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object WaveformPeakGenerator {
    private val DEFAULT_LEVELS = intArrayOf(64, 256, 1024)

    fun generate(
        reader: PcmTimelineReader,
        levelSizes: IntArray = DEFAULT_LEVELS,
        onProgress: (Float) -> Unit = {},
    ): WaveformPyramid {
        val totalFrames = reader.totalFrames
        val channels = reader.channels.coerceAtLeast(1)
        if (totalFrames <= 0) {
            val emptyLevels = levelSizes.map { WaveformLevel(it, FloatArray(0), FloatArray(0), FloatArray(0)) }
            return WaveformPyramid(0L, reader.sampleRate, emptyLevels)
        }

        val accumulators = levelSizes.map { framesPerBin ->
            val count = ((totalFrames + framesPerBin - 1L) / framesPerBin).toInt().coerceAtLeast(1)
            LevelAccumulator(framesPerBin, count)
        }

        val chunkFrames = 8192
        val buffer = ShortArray(chunkFrames * channels)
        var frameCursor = 0L

        while (frameCursor < totalFrames) {
            val toRead = min(chunkFrames.toLong(), totalFrames - frameCursor).toInt()
            val read = reader.readFrames(frameCursor, buffer, 0, toRead)
            if (read <= 0) break

            for (f in 0 until read) {
                var minVal = 1f
                var maxVal = -1f
                var sqSum = 0.0
                val baseIdx = f * channels
                for (c in 0 until channels) {
                    val norm = buffer[baseIdx + c] / 32768f
                    if (norm < minVal) minVal = norm
                    if (norm > maxVal) maxVal = norm
                    sqSum += norm * norm
                }
                val frameRmsSq = (sqSum / channels).toFloat()
                val absFrameIdx = frameCursor + f
                for (acc in accumulators) {
                    acc.pushFrame(absFrameIdx, minVal, maxVal, frameRmsSq)
                }
            }

            frameCursor += read
            onProgress((frameCursor.toFloat() / totalFrames).coerceIn(0f, 1f))
        }

        return WaveformPyramid(
            totalFrames = totalFrames,
            sampleRate = reader.sampleRate,
            levels = accumulators.map { it.build() },
        )
    }

    private class LevelAccumulator(
        private val framesPerBin: Int,
        binCount: Int,
    ) {
        private val minPeaks = FloatArray(binCount) { 1f }
        private val maxPeaks = FloatArray(binCount) { -1f }
        private val rmsSums = DoubleArray(binCount)
        private val counts = IntArray(binCount)

        fun pushFrame(frameIndex: Long, minVal: Float, maxVal: Float, rmsSq: Float) {
            val bin = (frameIndex / framesPerBin).toInt().coerceIn(0, minPeaks.lastIndex)
            minPeaks[bin] = min(minPeaks[bin], minVal)
            maxPeaks[bin] = max(maxPeaks[bin], maxVal)
            rmsSums[bin] += rmsSq.toDouble()
            counts[bin]++
        }

        fun build(): WaveformLevel {
            val rms = FloatArray(minPeaks.size)
            for (i in rms.indices) {
                val c = counts[i]
                if (c == 0) {
                    minPeaks[i] = 0f
                    maxPeaks[i] = 0f
                    rms[i] = 0f
                } else {
                    rms[i] = sqrt(rmsSums[i] / c).toFloat().coerceIn(0f, 1f)
                }
            }
            return WaveformLevel(framesPerBin, minPeaks, maxPeaks, rms)
        }
    }
}
