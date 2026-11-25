// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.ensureActive

object SoftClipLimiter {
    /**
     * Soft-knee limiter above -0.5 dBFS (~0.944) asymptotic to 1.0, preventing harsh digital clipping.
     */
    fun limitSample(x: Float): Float {
        val threshold = 0.944f
        val ax = abs(x)
        if (ax <= threshold) return x
        val excess = ax - threshold
        val headroom = 1f - threshold
        val shaped = threshold + headroom * (1f - exp((-excess / headroom).toDouble()).toFloat())
        return if (x >= 0f) shaped else -shaped
    }

    fun floatToPcm16(x: Float): Short {
        val limited = limitSample(x).coerceIn(-1f, 0.999969f)
        val scaled = (limited * 32767f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return scaled.toShort()
    }

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)

    fun linearToDb(linear: Float): Float =
        if (linear <= 1e-6f) -120f else (20.0 * ln(linear.toDouble()) / ln(10.0)).toFloat()
}

internal class BiquadFilter(
    var b0: Double = 1.0,
    var b1: Double = 0.0,
    var b2: Double = 0.0,
    var a1: Double = 0.0,
    var a2: Double = 0.0,
) {
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    fun ensureChannels(channels: Int) {
        if (z1.size != channels) {
            z1 = DoubleArray(channels)
            z2 = DoubleArray(channels)
        }
    }

    fun reset() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    fun process(sample: Double, ch: Int): Double {
        val out = b0 * sample + z1[ch]
        z1[ch] = b1 * sample - a1 * out + z2[ch]
        z2[ch] = b2 * sample - a2 * out
        return out
    }
}

internal suspend fun streamProcessRange(
    timeline: EditTimeline,
    store: DiskPcmStore,
    startFrame: Long,
    endFrame: Long,
    prefix: String,
    onProgress: (Float) -> Unit,
    processBlock: (interleavedFloat: FloatArray, framesRead: Int, blockStartFrameOffset: Long) -> Unit,
): EditTimeline {
    val s = startFrame.coerceIn(0L, timeline.totalFrames)
    val e = endFrame.coerceIn(s, timeline.totalFrames)
    val rangeFrames = e - s
    if (rangeFrames <= 0L) return timeline

    val channels = timeline.channels
    val chunkFrames = 8192
    val shortBuf = ShortArray(chunkFrames * channels)
    val floatBuf = FloatArray(chunkFrames * channels)
    val byteBuf = ByteArray(chunkFrames * channels * 2)
    val outFile: File = store.createChunkFile(prefix)

    BufferedOutputStream(FileOutputStream(outFile)).use { out ->
        var cursor = 0L
        while (cursor < rangeFrames) {
            coroutineContext.ensureActive()
            val toRead = min(chunkFrames.toLong(), rangeFrames - cursor).toInt()
            val read = timeline.readFrames(s + cursor, shortBuf, 0, toRead)
            if (read <= 0) break

            val sampleCount = read * channels
            for (i in 0 until sampleCount) {
                floatBuf[i] = shortBuf[i] / 32768f
            }

            processBlock(floatBuf, read, cursor)

            for (i in 0 until sampleCount) {
                shortBuf[i] = SoftClipLimiter.floatToPcm16(floatBuf[i])
            }
            DiskPcmStore.encodeSamplesToBytesLe(shortBuf, sampleCount, byteBuf)
            out.write(byteBuf, 0, sampleCount * 2)

            cursor += read
            onProgress((cursor.toFloat() / rangeFrames).coerceIn(0f, 1f))
        }
    }

    return timeline.replaceRange(s, e, outFile, rangeFrames)
}

internal suspend fun readTimelineAllFloat(
    reader: PcmTimelineReader,
    startFrame: Long,
    endFrame: Long,
    onProgress: (Float) -> Unit = {},
): FloatArray {
    val s = startFrame.coerceIn(0L, reader.totalFrames)
    val e = endFrame.coerceIn(s, reader.totalFrames)
    val total = (e - s).toInt()
    val channels = reader.channels
    val out = FloatArray(total * channels)
    val buf = ShortArray(8192 * channels)
    var cursor = 0
    while (cursor < total) {
        coroutineContext.ensureActive()
        val toRead = min(8192, total - cursor)
        val read = reader.readFrames(s + cursor, buf, 0, toRead)
        if (read <= 0) break
        val baseOut = cursor * channels
        for (i in 0 until read * channels) {
            out[baseOut + i] = buf[i] / 32768f
        }
        cursor += read
        onProgress(cursor.toFloat() / total.coerceAtLeast(1))
    }
    return out
}
