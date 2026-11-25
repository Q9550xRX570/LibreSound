// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EqPreset(val gainsDb: List<Float>) {
    FLAT(listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    VOICE_CLARITY(listOf(-4f, -1f, 1.5f, 3f, 5f, 4f, 1.5f)),
    PODCAST(listOf(-6f, 1f, 0f, 2f, 4f, 3f, 1f)),
    BASS_BOOST(listOf(6.5f, 4.5f, 2f, 0f, 0f, 0f, 0f)),
    TREBLE_SPARKLE(listOf(0f, 0f, 0f, 0.5f, 2.5f, 4.5f, 6f)),
    WARM(listOf(3f, 2.5f, 1.5f, 0f, -1.5f, -2.5f, -3f)),
}

object EqualizerProcessor {
    val BAND_FREQUENCIES_HZ = floatArrayOf(60f, 170f, 350f, 1000f, 3000f, 6000f, 12000f)

    suspend fun applyEq(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        bandGainsDb: List<Float>,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val sr = timeline.sampleRate.toDouble()
        val channels = timeline.channels
        val filters = BAND_FREQUENCIES_HZ.toList().mapIndexedNotNull { idx, freq ->
            val gain = bandGainsDb.getOrElse(idx) { 0f }.coerceIn(-12f, 12f)
            if (kotlin.math.abs(gain) < 0.05f || freq >= sr * 0.45) {
                null
            } else {
                val filter = when (idx) {
                    0 -> createLowShelf(sr, freq.toDouble(), gain.toDouble())
                    BAND_FREQUENCIES_HZ.lastIndex -> createHighShelf(sr, freq.toDouble(), gain.toDouble())
                    else -> createPeaking(sr, freq.toDouble(), 1.2, gain.toDouble())
                }
                filter.apply { ensureChannels(channels) }
            }
        }
        if (filters.isEmpty()) return timeline

        return streamProcessRange(timeline, store, startFrame, endFrame, "eq", onProgress) { buf, frames, _ ->
            for (f in 0 until frames) {
                val base = f * channels
                for (c in 0 until channels) {
                    var s = buf[base + c].toDouble()
                    for (filter in filters) {
                        s = filter.process(s, c)
                    }
                    buf[base + c] = s.toFloat()
                }
            }
        }
    }

    internal fun createPeaking(fs: Double, f0: Double, q: Double, gainDb: Double): BiquadFilter {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW0 = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha / a
        return BiquadFilter(
            b0 = (1.0 + alpha * a) / a0,
            b1 = (-2.0 * cosW0) / a0,
            b2 = (1.0 - alpha * a) / a0,
            a1 = (-2.0 * cosW0) / a0,
            a2 = (1.0 - alpha / a) / a0,
        )
    }

    internal fun createLowShelf(fs: Double, f0: Double, gainDb: Double): BiquadFilter {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 0.9 - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
        return BiquadFilter(
            b0 = (a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0,
            b1 = (2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)) / a0,
            b2 = (a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0,
            a1 = (-2.0 * ((a - 1.0) + (a + 1.0) * cosW0)) / a0,
            a2 = ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0,
        )
    }

    internal fun createHighShelf(fs: Double, f0: Double, gainDb: Double): BiquadFilter {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW0 = cos(w0)
        val alpha = sin(w0) / 2.0 * sqrt((a + 1.0 / a) * (1.0 / 0.9 - 1.0) + 2.0)
        val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
        val a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
        return BiquadFilter(
            b0 = (a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)) / a0,
            b1 = (-2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)) / a0,
            b2 = (a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)) / a0,
            a1 = (2.0 * ((a - 1.0) - (a + 1.0) * cosW0)) / a0,
            a2 = ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha) / a0,
        )
    }
}
