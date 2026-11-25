// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.storage

import com.libreopus.audio.api.AudioMetadata
import java.io.File

/**
 * Tracks whether the active timeline remains eligible for zero-re-encoding
 * lossless Ogg Opus packet trimming & tag rewriting.
 */
data class LosslessOpusState(
    val sourceOggFile: File?,
    val sourceDurationMs: Long,
    val startMs: Long,
    val endMs: Long,
    val isLosslessEligible: Boolean,
) {
    fun trimmed(subStartMs: Long, subEndMs: Long): LosslessOpusState {
        if (!isLosslessEligible || sourceOggFile == null) return copy(isLosslessEligible = false)
        val newStart = (startMs + subStartMs).coerceIn(0L, sourceDurationMs)
        val newEnd = (startMs + subEndMs).coerceIn(newStart, sourceDurationMs)
        return copy(startMs = newStart, endMs = newEnd)
    }

    fun invalidated(): LosslessOpusState = copy(isLosslessEligible = false)

    companion object {
        fun initial(sourceOggFile: File?, durationMs: Long, supportsLossless: Boolean): LosslessOpusState =
            LosslessOpusState(
                sourceOggFile = sourceOggFile,
                sourceDurationMs = durationMs,
                startMs = 0L,
                endMs = durationMs,
                isLosslessEligible = supportsLossless && sourceOggFile != null,
            )
    }
}

data class HistorySnapshot(
    val timeline: EditTimeline,
    val metadata: AudioMetadata,
    val losslessState: LosslessOpusState,
    val actionLabel: String,
)

class TimelineHistory(
    private val maxEntries: Int = 50,
) {
    private val undoStack = ArrayDeque<HistorySnapshot>()
    private val redoStack = ArrayDeque<HistorySnapshot>()

    var current: HistorySnapshot? = null
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoLabel: String? get() = if (undoStack.isNotEmpty()) current?.actionLabel else null
    val redoLabel: String? get() = redoStack.lastOrNull()?.actionLabel

    fun reset(initial: HistorySnapshot) {
        undoStack.clear()
        redoStack.clear()
        current = initial
    }

    fun push(next: HistorySnapshot) {
        val prev = current
        if (prev != null) {
            undoStack.addLast(prev)
            while (undoStack.size > maxEntries) {
                undoStack.removeFirst()
            }
        }
        redoStack.clear()
        current = next
    }

    fun undo(): HistorySnapshot? {
        val prev = undoStack.removeLastOrNull() ?: return null
        current?.let { redoStack.addLast(it) }
        current = prev
        return prev
    }

    fun redo(): HistorySnapshot? {
        val next = redoStack.removeLastOrNull() ?: return null
        current?.let { undoStack.addLast(it) }
        current = next
        return next
    }
}
