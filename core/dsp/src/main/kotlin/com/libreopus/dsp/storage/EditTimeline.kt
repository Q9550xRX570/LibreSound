// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.storage

import com.libreopus.audio.api.PcmTimelineReader
import java.io.File

data class AudioSegment(
    val file: File?,
    val startFrame: Long,
    val frameCount: Long,
    val gain: Float = 1f,
    val isSilence: Boolean = false,
) {
    init {
        require(frameCount >= 0) { "frameCount must be non-negative" }
        require(startFrame >= 0) { "startFrame must be non-negative" }
    }

    fun slice(localStart: Long, localEnd: Long): AudioSegment {
        val clampedStart = localStart.coerceIn(0L, frameCount)
        val clampedEnd = localEnd.coerceIn(clampedStart, frameCount)
        return copy(
            startFrame = startFrame + clampedStart,
            frameCount = clampedEnd - clampedStart,
        )
    }
}

/**
 * Immutable piece-table timeline backed by disk PCM chunks.
 * Supports O(1) non-destructive trim, cut, split, silence, copy/paste, and append.
 */
data class EditTimeline(
    override val sampleRate: Int,
    override val channels: Int,
    val segments: List<AudioSegment>,
) : PcmTimelineReader {

    override val totalFrames: Long = segments.sumOf { it.frameCount }

    val durationMs: Long
        get() = if (sampleRate <= 0) 0L else (totalFrames * 1000L) / sampleRate

    fun msToFrame(ms: Long): Long = ((ms.coerceAtLeast(0L) * sampleRate) / 1000L).coerceIn(0L, totalFrames)

    fun frameToMs(frame: Long): Long = if (sampleRate <= 0) 0L else (frame.coerceAtLeast(0L) * 1000L) / sampleRate

    override fun readFrames(
        startFrame: Long,
        destInterleaved16: ShortArray,
        frameOffset: Int,
        frameCount: Int,
    ): Int {
        if (frameCount <= 0 || startFrame >= totalFrames || startFrame < 0) return 0
        var remaining = minOf(frameCount.toLong(), totalFrames - startFrame).toInt()
        var cursorTimelineFrame = startFrame
        var destCursorFrame = frameOffset
        var totalRead = 0

        var segOrigin = 0L
        for (seg in segments) {
            if (remaining <= 0) break
            val segEnd = segOrigin + seg.frameCount
            if (cursorTimelineFrame < segEnd) {
                val offsetInSeg = cursorTimelineFrame - segOrigin
                val availableInSeg = seg.frameCount - offsetInSeg
                val toRead = minOf(remaining.toLong(), availableInSeg).toInt()
                if (seg.isSilence || seg.file == null) {
                    val sampleStart = destCursorFrame * channels
                    val sampleEnd = sampleStart + toRead * channels
                    destInterleaved16.fill(0, sampleStart, sampleEnd)
                    cursorTimelineFrame += toRead
                    destCursorFrame += toRead
                    remaining -= toRead
                    totalRead += toRead
                } else {
                    val actuallyRead = DiskPcmStore.readFramesFromFile(
                        file = seg.file,
                        channels = channels,
                        startFrame = seg.startFrame + offsetInSeg,
                        destInterleaved16 = destInterleaved16,
                        frameOffset = destCursorFrame,
                        frameCount = toRead,
                        gain = seg.gain,
                    )
                    if (actuallyRead < toRead) {
                        val sStart = (destCursorFrame + actuallyRead) * channels
                        val sEnd = (destCursorFrame + toRead) * channels
                        destInterleaved16.fill(0, sStart, sEnd)
                    }
                    cursorTimelineFrame += toRead
                    destCursorFrame += toRead
                    remaining -= toRead
                    totalRead += toRead
                }
            }
            segOrigin = segEnd
        }
        return totalRead
    }

    fun extractSlice(startFrame: Long, endFrame: Long): List<AudioSegment> {
        val s = startFrame.coerceIn(0L, totalFrames)
        val e = endFrame.coerceIn(s, totalFrames)
        if (s == e) return emptyList()

        val result = mutableListOf<AudioSegment>()
        var segOrigin = 0L
        for (seg in segments) {
            val segEnd = segOrigin + seg.frameCount
            if (segEnd > s && segOrigin < e) {
                val localStart = (s - segOrigin).coerceAtLeast(0L)
                val localEnd = (e - segOrigin).coerceAtMost(seg.frameCount)
                val sliced = seg.slice(localStart, localEnd)
                if (sliced.frameCount > 0) result += sliced
            }
            segOrigin = segEnd
        }
        return mergeAdjacent(result)
    }

    fun trim(startFrame: Long, endFrame: Long): EditTimeline =
        copy(segments = extractSlice(startFrame, endFrame))

    fun cut(startFrame: Long, endFrame: Long): EditTimeline {
        val s = startFrame.coerceIn(0L, totalFrames)
        val e = endFrame.coerceIn(s, totalFrames)
        if (s == e) return this
        val before = extractSlice(0L, s)
        val after = extractSlice(e, totalFrames)
        return copy(segments = mergeAdjacent(before + after))
    }

    fun split(atFrame: Long): Pair<EditTimeline, EditTimeline> {
        val f = atFrame.coerceIn(0L, totalFrames)
        return trim(0L, f) to trim(f, totalFrames)
    }

    fun silenceRange(startFrame: Long, endFrame: Long): EditTimeline {
        val s = startFrame.coerceIn(0L, totalFrames)
        val e = endFrame.coerceIn(s, totalFrames)
        if (s == e) return this
        val before = extractSlice(0L, s)
        val silence = AudioSegment(file = null, startFrame = 0L, frameCount = e - s, isSilence = true)
        val after = extractSlice(e, totalFrames)
        return copy(segments = mergeAdjacent(before + silence + after))
    }

    fun insertSilence(atFrame: Long, durationFrames: Long): EditTimeline {
        if (durationFrames <= 0) return this
        val f = atFrame.coerceIn(0L, totalFrames)
        val before = extractSlice(0L, f)
        val silence = AudioSegment(file = null, startFrame = 0L, frameCount = durationFrames, isSilence = true)
        val after = extractSlice(f, totalFrames)
        return copy(segments = mergeAdjacent(before + silence + after))
    }

    fun pasteAt(atFrame: Long, clipSegments: List<AudioSegment>): EditTimeline {
        if (clipSegments.isEmpty()) return this
        val f = atFrame.coerceIn(0L, totalFrames)
        val before = extractSlice(0L, f)
        val after = extractSlice(f, totalFrames)
        return copy(segments = mergeAdjacent(before + clipSegments + after))
    }

    fun append(otherSegments: List<AudioSegment>): EditTimeline {
        if (otherSegments.isEmpty()) return this
        return copy(segments = mergeAdjacent(segments + otherSegments))
    }

    fun replaceRange(
        startFrame: Long,
        endFrame: Long,
        replacementFile: File,
        replacementFrames: Long,
    ): EditTimeline {
        val s = startFrame.coerceIn(0L, totalFrames)
        val e = endFrame.coerceIn(s, totalFrames)
        val before = extractSlice(0L, s)
        val mid = if (replacementFrames > 0) {
            listOf(AudioSegment(file = replacementFile, startFrame = 0L, frameCount = replacementFrames))
        } else {
            emptyList()
        }
        val after = extractSlice(e, totalFrames)
        return copy(segments = mergeAdjacent(before + mid + after))
    }

    companion object {
        fun fromSingleFile(sampleRate: Int, channels: Int, file: File, totalFrames: Long): EditTimeline =
            EditTimeline(
                sampleRate = sampleRate,
                channels = channels,
                segments = if (totalFrames > 0) {
                    listOf(AudioSegment(file = file, startFrame = 0L, frameCount = totalFrames))
                } else {
                    emptyList()
                },
            )

        private fun mergeAdjacent(list: List<AudioSegment>): List<AudioSegment> {
            val out = mutableListOf<AudioSegment>()
            for (seg in list) {
                if (seg.frameCount <= 0) continue
                val prev = out.lastOrNull()
                if (prev != null &&
                    prev.isSilence && seg.isSilence
                ) {
                    out[out.lastIndex] = prev.copy(frameCount = prev.frameCount + seg.frameCount)
                } else if (prev != null &&
                    !prev.isSilence && !seg.isSilence &&
                    prev.file == seg.file &&
                    prev.gain == seg.gain &&
                    prev.startFrame + prev.frameCount == seg.startFrame
                ) {
                    out[out.lastIndex] = prev.copy(frameCount = prev.frameCount + seg.frameCount)
                } else {
                    out += seg
                }
            }
            return out
        }
    }
}
