// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import kotlinx.coroutines.ensureActive

enum class ChannelOperation {
    TO_MONO_MIX,
    TO_MONO_LEFT,
    TO_MONO_RIGHT,
    SWAP_LR,
    MONO_TO_STEREO,
}

object UtilityProcessors {

    suspend fun reverse(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val s = startFrame.coerceIn(0L, timeline.totalFrames)
        val e = endFrame.coerceIn(s, timeline.totalFrames)
        val rangeFrames = e - s
        if (rangeFrames <= 1L) return timeline

        val ch = timeline.channels
        val chunkFrames = 8192
        val buf = ShortArray(chunkFrames * ch)
        val revBuf = ShortArray(chunkFrames * ch)
        val byteBuf = ByteArray(chunkFrames * ch * 2)
        val outFile = store.createChunkFile("reverse")

        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
            var remaining = rangeFrames
            while (remaining > 0L) {
                coroutineContext.ensureActive()
                val toRead = min(chunkFrames.toLong(), remaining).toInt()
                val readStart = s + remaining - toRead
                val read = timeline.readFrames(readStart, buf, 0, toRead)
                if (read <= 0) break

                for (f in 0 until read) {
                    val srcFrame = read - 1 - f
                    for (c in 0 until ch) {
                        revBuf[f * ch + c] = buf[srcFrame * ch + c]
                    }
                }
                DiskPcmStore.encodeSamplesToBytesLe(revBuf, read * ch, byteBuf)
                out.write(byteBuf, 0, read * ch * 2)

                remaining -= read
                onProgress(1f - (remaining.toFloat() / rangeFrames))
            }
        }
        return timeline.replaceRange(s, e, outFile, rangeFrames)
    }

    suspend fun applyChannelOperation(
        timeline: EditTimeline,
        store: DiskPcmStore,
        op: ChannelOperation,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val total = timeline.totalFrames
        if (total <= 0L) return timeline
        val inCh = timeline.channels

        if (op == ChannelOperation.SWAP_LR && inCh != 2) return timeline
        if (op == ChannelOperation.MONO_TO_STEREO && inCh == 2) return timeline

        val outCh = when (op) {
            ChannelOperation.TO_MONO_MIX,
            ChannelOperation.TO_MONO_LEFT,
            ChannelOperation.TO_MONO_RIGHT,
            -> 1
            ChannelOperation.SWAP_LR,
            ChannelOperation.MONO_TO_STEREO,
            -> 2
        }

        val chunkFrames = 8192
        val inBuf = ShortArray(chunkFrames * inCh)
        val outBuf = ShortArray(chunkFrames * outCh)
        val byteBuf = ByteArray(chunkFrames * outCh * 2)
        val outFile = store.createChunkFile("channels")

        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
            var cursor = 0L
            while (cursor < total) {
                coroutineContext.ensureActive()
                val toRead = min(chunkFrames.toLong(), total - cursor).toInt()
                val read = timeline.readFrames(cursor, inBuf, 0, toRead)
                if (read <= 0) break

                for (f in 0 until read) {
                    val inBase = f * inCh
                    val outBase = f * outCh
                    when (op) {
                        ChannelOperation.TO_MONO_MIX -> {
                            var sum = 0
                            for (c in 0 until inCh) sum += inBuf[inBase + c].toInt()
                            outBuf[outBase] = (sum / inCh).toShort()
                        }
                        ChannelOperation.TO_MONO_LEFT -> {
                            outBuf[outBase] = inBuf[inBase]
                        }
                        ChannelOperation.TO_MONO_RIGHT -> {
                            outBuf[outBase] = inBuf[inBase + min(1, inCh - 1)]
                        }
                        ChannelOperation.SWAP_LR -> {
                            outBuf[outBase] = inBuf[inBase + 1]
                            outBuf[outBase + 1] = inBuf[inBase]
                        }
                        ChannelOperation.MONO_TO_STEREO -> {
                            val s = inBuf[inBase]
                            outBuf[outBase] = s
                            outBuf[outBase + 1] = s
                        }
                    }
                }
                DiskPcmStore.encodeSamplesToBytesLe(outBuf, read * outCh, byteBuf)
                out.write(byteBuf, 0, read * outCh * 2)

                cursor += read
                onProgress((cursor.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        return EditTimeline.fromSingleFile(timeline.sampleRate, outCh, outFile, total)
    }

    /**
     * Mixes [overlayReader] on top of [baseTimeline] starting at [offsetFrames], automatically
     * resampling overlay if sample rates differ and applying soft-clip protection.
     */
    suspend fun mixOverlay(
        baseTimeline: EditTimeline,
        overlayReader: PcmTimelineReader,
        store: DiskPcmStore,
        offsetFrames: Long = 0L,
        baseGainDb: Float = 0f,
        overlayGainDb: Float = 0f,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val sr = baseTimeline.sampleRate
        val ch = baseTimeline.channels
        val baseGain = SoftClipLimiter.dbToLinear(baseGainDb)
        val overlayGain = SoftClipLimiter.dbToLinear(overlayGainDb)

        val rawOverlay = readTimelineAllFloat(overlayReader, 0L, overlayReader.totalFrames) { p ->
            onProgress(p * 0.25f)
        }
        val overlaySameCh = adaptChannels(rawOverlay, overlayReader.channels, ch)
        val overlayReady = if (overlayReader.sampleRate != sr && overlayReader.sampleRate > 0) {
            HermiteResampler.resampleInterleaved(
                input = overlaySameCh,
                inputFrames = overlaySameCh.size / ch,
                channels = ch,
                sourceRate = overlayReader.sampleRate,
                targetRate = sr,
            )
        } else {
            overlaySameCh
        }
        val overlayFrames = (overlayReady.size / ch).toLong()
        val safeOffset = offsetFrames.coerceAtLeast(0L)
        val outTotalFrames = maxOf(baseTimeline.totalFrames, safeOffset + overlayFrames)
        if (outTotalFrames <= 0L) return baseTimeline

        val outFile = store.createChunkFile("mix")
        val chunkFrames = 8192
        val baseShort = ShortArray(chunkFrames * ch)
        val outShort = ShortArray(chunkFrames * ch)
        val byteBuf = ByteArray(chunkFrames * ch * 2)

        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
            var cursor = 0L
            while (cursor < outTotalFrames) {
                coroutineContext.ensureActive()
                val toProcess = min(chunkFrames.toLong(), outTotalFrames - cursor).toInt()
                val baseRead = if (cursor < baseTimeline.totalFrames) {
                    baseTimeline.readFrames(cursor, baseShort, 0, toProcess)
                } else {
                    0
                }
                if (baseRead < toProcess) {
                    baseShort.fill(0, baseRead * ch, toProcess * ch)
                }

                for (f in 0 until toProcess) {
                    val absFrame = cursor + f
                    val ovFrame = absFrame - safeOffset
                    val hasOv = ovFrame in 0 until overlayFrames
                    val baseIdx = f * ch
                    val ovIdx = ovFrame.toInt() * ch
                    for (c in 0 until ch) {
                        val bSample = (baseShort[baseIdx + c] / 32768f) * baseGain
                        val oSample = if (hasOv) overlayReady[ovIdx + c] * overlayGain else 0f
                        outShort[baseIdx + c] = SoftClipLimiter.floatToPcm16(bSample + oSample)
                    }
                }
                DiskPcmStore.encodeSamplesToBytesLe(outShort, toProcess * ch, byteBuf)
                out.write(byteBuf, 0, toProcess * ch * 2)

                cursor += toProcess
                onProgress(0.25f + (cursor.toFloat() / outTotalFrames) * 0.75f)
            }
        }
        return EditTimeline.fromSingleFile(sr, ch, outFile, outTotalFrames)
    }

    private fun adaptChannels(input: FloatArray, inCh: Int, outCh: Int): FloatArray {
        if (inCh == outCh || inCh <= 0) return input
        val frames = input.size / inCh
        val out = FloatArray(frames * outCh)
        for (f in 0 until frames) {
            val ib = f * inCh
            val ob = f * outCh
            if (outCh == 1) {
                var sum = 0f
                for (c in 0 until inCh) sum += input[ib + c]
                out[ob] = sum / inCh
            } else {
                for (c in 0 until outCh) {
                    out[ob + c] = input[ib + min(c, inCh - 1)]
                }
            }
        }
        return out
    }
}
