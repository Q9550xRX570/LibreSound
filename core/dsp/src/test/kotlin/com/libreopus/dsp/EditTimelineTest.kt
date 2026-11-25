// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp

import com.libreopus.audio.api.AudioMetadata
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import com.libreopus.dsp.storage.HistorySnapshot
import com.libreopus.dsp.storage.LosslessOpusState
import com.libreopus.dsp.storage.TimelineHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EditTimelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `trim cut split silence paste and undo redo preserve sample accuracy`() {
        val store = DiskPcmStore(tempFolder.newFolder("timeline"))
        val file = store.createChunkFile("base")
        // 100 frames mono where sample[i] = i
        val samples = ShortArray(100) { it.toShort() }
        store.writePcm16Le(file, samples)

        val base = EditTimeline.fromSingleFile(48000, 1, file, 100L)
        val history = TimelineHistory()
        history.reset(
            HistorySnapshot(
                timeline = base,
                metadata = AudioMetadata(),
                losslessState = LosslessOpusState.initial(null, base.durationMs, false),
                actionLabel = "Open",
            ),
        )

        // Trim [20, 80) -> 60 frames: 20..79
        val trimmed = base.trim(20L, 80L)
        assertEquals(60L, trimmed.totalFrames)
        val outTrim = ShortArray(60)
        trimmed.readFrames(0L, outTrim, 0, 60)
        assertEquals(20, outTrim[0].toInt())
        assertEquals(79, outTrim[59].toInt())
        history.push(HistorySnapshot(trimmed, AudioMetadata(), LosslessOpusState.initial(null, 0, false), "Trim"))

        // Cut [10, 30) -> removes 30..49, leaves 20..29 + 50..79 (40 frames)
        val cut = trimmed.cut(10L, 30L)
        assertEquals(40L, cut.totalFrames)
        val outCut = ShortArray(40)
        cut.readFrames(0L, outCut, 0, 40)
        assertEquals(29, outCut[9].toInt())
        assertEquals(50, outCut[10].toInt())

        // Silence [5, 15)
        val silenced = cut.silenceRange(5L, 15L)
        val outSil = ShortArray(40)
        silenced.readFrames(0L, outSil, 0, 40)
        assertEquals(0, outSil[5].toInt())
        assertEquals(0, outSil[14].toInt())
        assertEquals(55, outSil[15].toInt())

        // Split & Append
        val (left, right) = silenced.split(20L)
        assertEquals(20L, left.totalFrames)
        assertEquals(20L, right.totalFrames)
        val merged = left.append(right.segments)
        assertEquals(40L, merged.totalFrames)

        // Undo back to base
        assertTrue(history.canUndo)
        assertEquals("Trim", history.undoLabel)
        val undone = history.undo()!!
        assertEquals(100L, undone.timeline.totalFrames)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun `insertSilence and lossless split ranges remain sample and timestamp accurate`() {
        val store = DiskPcmStore(tempFolder.newFolder("split_silence"))
        val file = store.createChunkFile("src")
        val samples = ShortArray(480) { (it + 1).toShort() }
        store.writePcm16Le(file, samples)

        val base = EditTimeline.fromSingleFile(48000, 1, file, 480L)
        val withGap = base.insertSilence(240L, 120L)
        assertEquals(600L, withGap.totalFrames)
        val out = ShortArray(600)
        withGap.readFrames(0L, out, 0, 600)
        assertEquals(240, out[239].toInt())
        assertEquals(0, out[240].toInt())
        assertEquals(0, out[359].toInt())
        assertEquals(241, out[360].toInt())

        val initialLossless = LosslessOpusState.initial(file, 10_000L, supportsLossless = true)
        val trimmedFirst = initialLossless.trimmed(2_000L, 9_000L)
        val leftSplit = trimmedFirst.trimmed(0L, 3_500L)
        val rightSplit = trimmedFirst.trimmed(3_500L, 7_000L)

        assertTrue(leftSplit.isLosslessEligible)
        assertEquals(2_000L, leftSplit.startMs)
        assertEquals(5_500L, leftSplit.endMs)
        assertTrue(rightSplit.isLosslessEligible)
        assertEquals(5_500L, rightSplit.startMs)
        assertEquals(9_000L, rightSplit.endMs)
    }
}
