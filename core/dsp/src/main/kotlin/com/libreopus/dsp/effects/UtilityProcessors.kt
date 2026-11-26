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

    /**
     * Concatenates multiple readers sequentially in streaming chunks, adapting differing
     * sample rates / channels and blending overlaps up to 5 seconds via equal-power crossfade.
     */
    suspend fun concatenateWithCrossfade(
        readers: List<PcmTimelineReader>,
        store: DiskPcmStore,
        targetSampleRate: Int,
        targetChannels: Int,
        crossfadeMs: Long = 0L,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val sr = targetSampleRate.coerceAtLeast(8000)
        val ch = targetChannels.coerceIn(1, 2)
        val nonEmpty = readers.filter { it.totalFrames > 0L }
        if (nonEmpty.isEmpty()) return EditTimeline(sr, ch, emptyList())

        val adaptedReaders = nonEmpty.map { raw ->
            val chAdapted = if (raw.channels == ch) raw else ChannelAdapterReader(raw, ch)
            if (chAdapted.sampleRate == sr || chAdapted.sampleRate <= 0) chAdapted else ResamplingTimelineReader(chAdapted, sr)
        }

        val totalEstimatedFrames = adaptedReaders.sumOf { it.totalFrames }.coerceAtLeast(1L)
        val desiredOverlapFrames = ((crossfadeMs.coerceIn(0L, 5000L) * sr) / 1000L).toInt()
        val outFile = store.createChunkFile("merge")
        val chunkFrames = 8192
        val shortBuf = ShortArray(chunkFrames * ch)
        val outShortBuf = ShortArray(chunkFrames * ch)
        val byteBuf = ByteArray(chunkFrames * ch * 2)

        var prevTail: FloatArray? = null
        var prevTailFrames = 0
        var writtenFrames = 0L
        var processedInputFrames = 0L

        BufferedOutputStream(FileOutputStream(outFile)).use { out ->
            for (idx in adaptedReaders.indices) {
                coroutineContext.ensureActive()
                val reader = adaptedReaders[idx]
                val trackTotal = reader.totalFrames
                if (trackTotal <= 0L) continue

                val hasNext = idx < adaptedReaders.lastIndex
                val fadeInFrames = if (prevTail != null && prevTailFrames > 0) {
                    minOf(prevTailFrames.toLong(), trackTotal).toInt()
                } else {
                    0
                }
                val nextTailFrames = if (hasNext && desiredOverlapFrames > 0) {
                    val remaining = (trackTotal - fadeInFrames).coerceAtLeast(0L)
                    minOf(desiredOverlapFrames.toLong(), remaining / 2L).toInt()
                } else {
                    0
                }

                // 1. Blend previous tail with current track head using equal-power cosine/sine curve
                if (fadeInFrames > 0 && prevTail != null) {
                    var offset = 0
                    while (offset < fadeInFrames) {
                        coroutineContext.ensureActive()
                        val block = minOf(chunkFrames, fadeInFrames - offset)
                        val read = reader.readFrames(offset.toLong(), shortBuf, 0, block)
                        if (read <= 0) break
                        for (f in 0 until read) {
                            val absF = offset + f
                            val t = if (fadeInFrames <= 1) 1f else absF.toFloat() / (fadeInFrames - 1).toFloat()
                            val fadeOutGain = kotlin.math.cos(t * kotlin.math.PI * 0.5).toFloat()
                            val fadeInGain = kotlin.math.sin(t * kotlin.math.PI * 0.5).toFloat()
                            val base = f * ch
                            val tailBase = absF * ch
                            for (c in 0 until ch) {
                                val prevS = prevTail[tailBase + c]
                                val curS = shortBuf[base + c] / 32768f
                                outShortBuf[base + c] = SoftClipLimiter.floatToPcm16(prevS * fadeOutGain + curS * fadeInGain)
                            }
                        }
                        DiskPcmStore.encodeSamplesToBytesLe(outShortBuf, read * ch, byteBuf)
                        out.write(byteBuf, 0, read * ch * 2)
                        writtenFrames += read
                        offset += read
                        processedInputFrames += read
                        onProgress((processedInputFrames.toFloat() / totalEstimatedFrames).coerceIn(0f, 1f))
                    }
                    // If previous tail happened to exceed short trackTotal, flush excess cleanly faded down
                    if (prevTailFrames > fadeInFrames) {
                        var tailRemOff = fadeInFrames
                        while (tailRemOff < prevTailFrames) {
                            val block = minOf(chunkFrames, prevTailFrames - tailRemOff)
                            for (f in 0 until block) {
                                val absF = tailRemOff + f
                                val g = 1f - ((absF - fadeInFrames).toFloat() / (prevTailFrames - fadeInFrames).coerceAtLeast(1))
                                for (c in 0 until ch) {
                                    outShortBuf[f * ch + c] = SoftClipLimiter.floatToPcm16(prevTail[absF * ch + c] * g)
                                }
                            }
                            DiskPcmStore.encodeSamplesToBytesLe(outShortBuf, block * ch, byteBuf)
                            out.write(byteBuf, 0, block * ch * 2)
                            writtenFrames += block
                            tailRemOff += block
                        }
                    }
                    prevTail = null
                    prevTailFrames = 0
                }

                // 2. Stream middle body straight through
                val bodyEnd = trackTotal - nextTailFrames
                var cursor = fadeInFrames.toLong()
                while (cursor < bodyEnd) {
                    coroutineContext.ensureActive()
                    val block = minOf(chunkFrames.toLong(), bodyEnd - cursor).toInt()
                    val read = reader.readFrames(cursor, shortBuf, 0, block)
                    if (read <= 0) break
                    DiskPcmStore.encodeSamplesToBytesLe(shortBuf, read * ch, byteBuf)
                    out.write(byteBuf, 0, read * ch * 2)
                    writtenFrames += read
                    cursor += read
                    processedInputFrames += read
                    onProgress((processedInputFrames.toFloat() / totalEstimatedFrames).coerceIn(0f, 1f))
                }

                // 3. Buffer trailing tail for overlap with next track
                if (nextTailFrames > 0) {
                    val tailArr = FloatArray(nextTailFrames * ch)
                    var tCursor = 0
                    while (tCursor < nextTailFrames) {
                        coroutineContext.ensureActive()
                        val block = minOf(chunkFrames, nextTailFrames - tCursor)
                        val read = reader.readFrames(bodyEnd + tCursor, shortBuf, 0, block)
                        if (read <= 0) break
                        for (i in 0 until read * ch) {
                            tailArr[tCursor * ch + i] = shortBuf[i] / 32768f
                        }
                        tCursor += read
                        processedInputFrames += read
                        onProgress((processedInputFrames.toFloat() / totalEstimatedFrames).coerceIn(0f, 1f))
                    }
                    prevTail = tailArr
                    prevTailFrames = tCursor
                }
            }
        }
        onProgress(1f)
        return EditTimeline.fromSingleFile(sr, ch, outFile, writtenFrames)
    }

    /**
     * Scans a timeline in 10 ms windows and extracts `(startMs, endMs)` intervals of non-silent audio.
     */
    suspend fun detectNonSilentRegions(
        reader: PcmTimelineReader,
        thresholdDb: Float = -38f,
        minSilenceMs: Int = 500,
        minSegmentMs: Int = 400,
        paddingMs: Int = 60,
        onProgress: (Float) -> Unit = {},
    ): List<Pair<Long, Long>> {
        val total = reader.totalFrames
        if (total <= 0L) return emptyList()
        val sr = reader.sampleRate.coerceAtLeast(8000)
        val ch = reader.channels.coerceIn(1, 2)
        val durationMs = (total * 1000L) / sr
        val windowFrames = (sr / 100).coerceAtLeast(80) // 10 ms windows
        val windowMs = 10L
        val threshLinear = SoftClipLimiter.dbToLinear(thresholdDb)

        val windowActive = ArrayList<Boolean>(((total / windowFrames) + 2).toInt())
        val chunkWindows = 128
        val buf = ShortArray(chunkWindows * windowFrames * ch)
        var cursor = 0L

        while (cursor < total) {
            coroutineContext.ensureActive()
            val toRead = minOf((chunkWindows * windowFrames).toLong(), total - cursor).toInt()
            val read = reader.readFrames(cursor, buf, 0, toRead)
            if (read <= 0) break

            var fOff = 0
            while (fOff < read) {
                val winEnd = minOf(read, fOff + windowFrames)
                var peak = 0f
                for (f in fOff until winEnd) {
                    val base = f * ch
                    for (c in 0 until ch) {
                        val a = kotlin.math.abs(buf[base + c] / 32768f)
                        if (a > peak) peak = a
                    }
                }
                windowActive += (peak >= threshLinear)
                fOff = winEnd
            }
            cursor += read
            onProgress((cursor.toFloat() / total) * 0.9f)
        }

        val rawRuns = mutableListOf<Pair<Int, Int>>()
        var runStart = -1
        for (w in windowActive.indices) {
            if (windowActive[w]) {
                if (runStart < 0) runStart = w
            } else if (runStart >= 0) {
                rawRuns += runStart to w
                runStart = -1
            }
        }
        if (runStart >= 0) {
            rawRuns += runStart to windowActive.size
        }
        if (rawRuns.isEmpty()) {
            onProgress(1f)
            return emptyList()
        }

        // Bridge short silent gaps (< minSilenceMs) inside continuous speech/music
        val minGapWindows = (minSilenceMs / windowMs).toInt().coerceAtLeast(1)
        val bridged = mutableListOf<Pair<Int, Int>>()
        for (run in rawRuns) {
            if (bridged.isEmpty()) {
                bridged += run
            } else {
                val prev = bridged.last()
                if (run.first - prev.second < minGapWindows) {
                    bridged[bridged.lastIndex] = prev.first to run.second
                } else {
                    bridged += run
                }
            }
        }

        val minSegWindows = (minSegmentMs / windowMs).toInt().coerceAtLeast(1)
        val padMsLong = paddingMs.toLong().coerceAtLeast(0L)
        val out = mutableListOf<Pair<Long, Long>>()
        for ((wStart, wEnd) in bridged) {
            if (wEnd - wStart < minSegWindows) continue
            val rawStartMs = wStart * windowMs
            val rawEndMs = minOf(durationMs, wEnd * windowMs)
            val prevEnd = out.lastOrNull()?.second ?: 0L
            val startMs = maxOf(prevEnd, rawStartMs - padMsLong).coerceIn(0L, durationMs)
            val endMs = (rawEndMs + padMsLong).coerceIn(startMs, durationMs)
            if (endMs > startMs) {
                out += startMs to endMs
            }
        }
        onProgress(1f)
        return out
    }
}

private class ChannelAdapterReader(
    private val delegate: PcmTimelineReader,
    private val targetChannels: Int,
) : PcmTimelineReader {
    override val sampleRate: Int = delegate.sampleRate
    override val channels: Int = targetChannels
    override val totalFrames: Long = delegate.totalFrames
    private val srcChannels: Int = delegate.channels.coerceIn(1, 2)
    private var scratch = ShortArray(0)

    override fun readFrames(
        startFrame: Long,
        destInterleaved16: ShortArray,
        frameOffset: Int,
        frameCount: Int,
    ): Int {
        if (frameCount <= 0) return 0
        if (srcChannels == targetChannels) {
            return delegate.readFrames(startFrame, destInterleaved16, frameOffset, frameCount)
        }
        val needed = frameCount * srcChannels
        if (scratch.size < needed) scratch = ShortArray(needed)
        val read = delegate.readFrames(startFrame, scratch, 0, frameCount)
        for (f in 0 until read) {
            val sb = f * srcChannels
            val db = (frameOffset + f) * targetChannels
            if (targetChannels == 1) {
                destInterleaved16[db] = ((scratch[sb].toInt() + scratch[sb + 1].toInt()) / 2).toShort()
            } else {
                val s = scratch[sb]
                destInterleaved16[db] = s
                destInterleaved16[db + 1] = s
            }
        }
        return read
    }
}
