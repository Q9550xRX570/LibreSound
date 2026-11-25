// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow

enum class FadeCurve {
    LINEAR,
    EXPONENTIAL,
    LOGARITHMIC,
    S_CURVE,
}

object FadeProcessor {

    fun curveGain(t: Float, fadeIn: Boolean, curve: FadeCurve): Float {
        val u = t.coerceIn(0f, 1f)
        val shaped = when (curve) {
            FadeCurve.LINEAR -> u
            FadeCurve.EXPONENTIAL -> u * u * u
            FadeCurve.LOGARITHMIC -> (ln(1.0 + 9.0 * u) / ln(10.0)).toFloat()
            FadeCurve.S_CURVE -> ((1.0 - cos(PI * u)) * 0.5).toFloat()
        }
        return if (fadeIn) shaped else 1f - shaped
    }

    suspend fun applyFade(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        fadeIn: Boolean,
        curve: FadeCurve,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val s = startFrame.coerceIn(0L, timeline.totalFrames)
        val e = endFrame.coerceIn(s, timeline.totalFrames)
        val total = (e - s).coerceAtLeast(1L)
        val channels = timeline.channels
        val prefix = if (fadeIn) "fade_in" else "fade_out"

        return streamProcessRange(timeline, store, s, e, prefix, onProgress) { buf, frames, blockOffset ->
            for (f in 0 until frames) {
                val t = if (total <= 1L) 1f else ((blockOffset + f).toFloat() / (total - 1L).toFloat())
                val g = curveGain(t, fadeIn, curve)
                val base = f * channels
                for (c in 0 until channels) {
                    buf[base + c] *= g
                }
            }
        }
    }
}
