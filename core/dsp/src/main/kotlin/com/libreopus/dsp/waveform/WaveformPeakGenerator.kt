// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.waveform

import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.audio.api.WaveformLevel
import com.libreopus.audio.api.WaveformPyramid
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object WaveformPeakGenerator {
    val DEFAULT_LEVELS = intArrayOf(64, 256, 1024)

    fun emptyPyramid(sampleRate: Int, levelSizes: IntArray = DEFAULT_LEVELS): WaveformPyramid {
        val emptyLevels = levelSizes.map { WaveformLevel(it, FloatArray(0), FloatArray(0), FloatArray(0)) }
        return WaveformPyramid(0L, sampleRate, emptyLevels)
    }

    fun generate(
        reader: PcmTimelineReader,
        levelSizes: IntArray = DEFAULT_LEVELS,
        onProgress: (Float) -> Unit = {},
    ): WaveformPyramid {
        val totalFrames = reader.totalFrames
        val channels = reader.channels.coerceAtLeast(1)
        if (totalFrames <= 0L) {
            return emptyPyramid(reader.sampleRate, levelSizes)
        }

        val sortedLevels = levelSizes.sortedArray()
        val canHierarchicalRollup = sortedLevels.isNotEmpty() &&
            sortedLevels.all { it >= sortedLevels[0] && it % sortedLevels[0] == 0 }

        if (canHierarchicalRollup) {
            val builder = StreamingBuilder(
                sampleRate = reader.sampleRate,
                channels = channels,
                levelSizes = levelSizes,
                expectedFrames = totalFrames,
            )
            val chunkFrames = 8192
            val buffer = ShortArray(chunkFrames * channels)
            var frameCursor = 0L

            while (frameCursor < totalFrames) {
                val toRead = min(chunkFrames.toLong(), totalFrames - frameCursor).toInt()
                val read = reader.readFrames(frameCursor, buffer, 0, toRead)
                if (read <= 0) break
                builder.pushPcm16Shorts(buffer, read)
                frameCursor += read
                onProgress((frameCursor.toFloat() / totalFrames).coerceIn(0f, 1f))
            }
            return builder.build()
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

    /**
     * Single-pass in-memory accumulator fed while PCM audio is decoded to disk.
     * Computes only the finest bin resolution per frame and rolls up coarser levels
     * hierarchically on [build], avoiding a second full-file disk read pass.
     */
    class StreamingBuilder(
        private val sampleRate: Int,
        channels: Int,
        private val levelSizes: IntArray = DEFAULT_LEVELS,
        expectedFrames: Long = 0L,
    ) {
        private val ch = channels.coerceAtLeast(1)
        private val sortedSizes = levelSizes.sortedArray()
        private val fineFramesPerBin = sortedSizes.firstOrNull()?.coerceAtLeast(1) ?: 64
        private val fineCanRollup = sortedSizes.all { it % fineFramesPerBin == 0 }

        private var cap = if (expectedFrames > 0L) {
            (((expectedFrames + fineFramesPerBin - 1L) / fineFramesPerBin).toInt() + 16).coerceIn(256, 2_000_000)
        } else {
            2048
        }
        private var minPeaks = FloatArray(cap) { 1f }
        private var maxPeaks = FloatArray(cap) { -1f }
        private var rmsSums = DoubleArray(cap)
        private var counts = IntArray(cap)
        private var maxBinUsed = -1
        private var totalFrames = 0L

        // Fallback extra accumulators only if custom non-multiple levelSizes are provided
        private val extraAccumulators = if (fineCanRollup) {
            emptyList()
        } else {
            sortedSizes.drop(1).map { DynamicAccumulator(it) }
        }

        private var leftoverLen = 0
        private val frameBytes = ByteArray(ch * 2)

        private fun ensureBinCapacity(bin: Int) {
            if (bin < cap) return
            var newCap = cap
            while (newCap <= bin) {
                newCap = max(newCap * 2, bin + 256)
            }
            val oldCap = cap
            minPeaks = minPeaks.copyOf(newCap).apply { java.util.Arrays.fill(this, oldCap, newCap, 1f) }
            maxPeaks = maxPeaks.copyOf(newCap).apply { java.util.Arrays.fill(this, oldCap, newCap, -1f) }
            rmsSums = rmsSums.copyOf(newCap)
            counts = counts.copyOf(newCap)
            cap = newCap
        }

        private fun pushFrame(minVal: Float, maxVal: Float, rmsSq: Float) {
            val frameIdx = totalFrames++
            val bin = (frameIdx / fineFramesPerBin).toInt()
            ensureBinCapacity(bin)
            if (bin > maxBinUsed) maxBinUsed = bin
            if (minVal < minPeaks[bin]) minPeaks[bin] = minVal
            if (maxVal > maxPeaks[bin]) maxPeaks[bin] = maxVal
            rmsSums[bin] += rmsSq.toDouble()
            counts[bin]++
            for (extra in extraAccumulators) {
                extra.pushFrame(frameIdx, minVal, maxVal, rmsSq)
            }
        }

        fun pushPcm16Shorts(shorts: ShortArray, frameCount: Int) {
            val count = frameCount.coerceAtMost(shorts.size / ch)
            for (f in 0 until count) {
                var minVal = 1f
                var maxVal = -1f
                var sqSum = 0.0
                val baseIdx = f * ch
                for (c in 0 until ch) {
                    val norm = shorts[baseIdx + c] / 32768f
                    if (norm < minVal) minVal = norm
                    if (norm > maxVal) maxVal = norm
                    sqSum += norm * norm
                }
                pushFrame(minVal, maxVal, (sqSum / ch).toFloat())
            }
        }

        fun pushPcm16LeBytes(bytes: ByteArray, lengthBytes: Int) {
            var pos = 0
            val end = lengthBytes.coerceAtMost(bytes.size)
            val frameByteSize = ch * 2
            if (leftoverLen > 0) {
                val need = min(frameByteSize - leftoverLen, end - pos)
                System.arraycopy(bytes, pos, frameBytes, leftoverLen, need)
                leftoverLen += need
                pos += need
                if (leftoverLen == frameByteSize) {
                    pushSingleByteFrame(frameBytes, 0)
                    leftoverLen = 0
                }
            }
            while (pos + frameByteSize <= end) {
                pushSingleByteFrame(bytes, pos)
                pos += frameByteSize
            }
            if (pos < end) {
                val rem = end - pos
                System.arraycopy(bytes, pos, frameBytes, 0, rem)
                leftoverLen = rem
            }
        }

        private fun pushSingleByteFrame(buf: ByteArray, offset: Int) {
            var minVal = 1f
            var maxVal = -1f
            var sqSum = 0.0
            var idx = offset
            for (c in 0 until ch) {
                val lo = buf[idx].toInt() and 0xFF
                val hi = buf[idx + 1].toInt()
                idx += 2
                val sample = ((hi shl 8) or lo).toShort()
                val norm = sample / 32768f
                if (norm < minVal) minVal = norm
                if (norm > maxVal) maxVal = norm
                sqSum += norm * norm
            }
            pushFrame(minVal, maxVal, (sqSum / ch).toFloat())
        }

        fun build(): WaveformPyramid {
            if (totalFrames <= 0L || maxBinUsed < 0) {
                return emptyPyramid(sampleRate, levelSizes)
            }
            val fineCount = maxBinUsed + 1
            val fineMin = FloatArray(fineCount)
            val fineMax = FloatArray(fineCount)
            val fineRms = FloatArray(fineCount)
            for (i in 0 until fineCount) {
                val c = counts[i]
                if (c == 0) {
                    fineMin[i] = 0f
                    fineMax[i] = 0f
                    fineRms[i] = 0f
                } else {
                    fineMin[i] = minPeaks[i]
                    fineMax[i] = maxPeaks[i]
                    fineRms[i] = sqrt(rmsSums[i] / c).toFloat().coerceIn(0f, 1f)
                }
            }
            val fineLevel = WaveformLevel(fineFramesPerBin, fineMin, fineMax, fineRms)
            val bySize = HashMap<Int, WaveformLevel>(levelSizes.size)
            bySize[fineFramesPerBin] = fineLevel

            if (fineCanRollup) {
                for (targetSize in sortedSizes) {
                    if (targetSize == fineFramesPerBin) continue
                    val factor = targetSize / fineFramesPerBin
                    val targetCount = ((totalFrames + targetSize - 1L) / targetSize).toInt().coerceAtLeast(1)
                    val tMin = FloatArray(targetCount)
                    val tMax = FloatArray(targetCount)
                    val tRms = FloatArray(targetCount)
                    for (tIdx in 0 until targetCount) {
                        val startFine = tIdx * factor
                        val endFine = min(fineCount, startFine + factor)
                        var mn = 1f
                        var mx = -1f
                        var sqSum = 0.0
                        var totalC = 0
                        for (fIdx in startFine until endFine) {
                            val c = counts[fIdx]
                            if (c > 0) {
                                if (fineMin[fIdx] < mn) mn = fineMin[fIdx]
                                if (fineMax[fIdx] > mx) mx = fineMax[fIdx]
                                sqSum += rmsSums[fIdx]
                                totalC += c
                            }
                        }
                        if (totalC == 0) {
                            tMin[tIdx] = 0f
                            tMax[tIdx] = 0f
                            tRms[tIdx] = 0f
                        } else {
                            tMin[tIdx] = mn
                            tMax[tIdx] = mx
                            tRms[tIdx] = sqrt(sqSum / totalC).toFloat().coerceIn(0f, 1f)
                        }
                    }
                    bySize[targetSize] = WaveformLevel(targetSize, tMin, tMax, tRms)
                }
            } else {
                for (extra in extraAccumulators) {
                    bySize[extra.framesPerBin] = extra.build()
                }
            }

            return WaveformPyramid(
                totalFrames = totalFrames,
                sampleRate = sampleRate,
                levels = buildList(levelSizes.size) {
                    for (sz in levelSizes) {
                        bySize[sz]?.let(::add)
                    }
                },
            )
        }

        private class DynamicAccumulator(val framesPerBin: Int) {
            private var cap = 256
            private var minPeaks = FloatArray(cap) { 1f }
            private var maxPeaks = FloatArray(cap) { -1f }
            private var rmsSums = DoubleArray(cap)
            private var counts = IntArray(cap)
            private var maxBin = -1

            fun pushFrame(frameIndex: Long, minVal: Float, maxVal: Float, rmsSq: Float) {
                val bin = (frameIndex / framesPerBin).toInt()
                if (bin >= cap) {
                    var newCap = cap
                    while (newCap <= bin) newCap = max(newCap * 2, bin + 128)
                    val oldCap = cap
                    minPeaks = minPeaks.copyOf(newCap).apply { java.util.Arrays.fill(this, oldCap, newCap, 1f) }
                    maxPeaks = maxPeaks.copyOf(newCap).apply { java.util.Arrays.fill(this, oldCap, newCap, -1f) }
                    rmsSums = rmsSums.copyOf(newCap)
                    counts = counts.copyOf(newCap)
                    cap = newCap
                }
                if (bin > maxBin) maxBin = bin
                if (minVal < minPeaks[bin]) minPeaks[bin] = minVal
                if (maxVal > maxPeaks[bin]) maxPeaks[bin] = maxVal
                rmsSums[bin] += rmsSq.toDouble()
                counts[bin]++
            }

            fun build(): WaveformLevel {
                val n = maxBin + 1
                if (n <= 0) return WaveformLevel(framesPerBin, FloatArray(0), FloatArray(0), FloatArray(0))
                val rMin = FloatArray(n)
                val rMax = FloatArray(n)
                val rRms = FloatArray(n)
                for (i in 0 until n) {
                    val c = counts[i]
                    if (c > 0) {
                        rMin[i] = minPeaks[i]
                        rMax[i] = maxPeaks[i]
                        rRms[i] = sqrt(rmsSums[i] / c).toFloat().coerceIn(0f, 1f)
                    }
                }
                return WaveformLevel(framesPerBin, rMin, rMax, rRms)
            }
        }
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
