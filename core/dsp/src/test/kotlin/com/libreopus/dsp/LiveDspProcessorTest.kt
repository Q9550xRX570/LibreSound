// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp

import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.dsp.effects.LiveDspChain
import com.libreopus.dsp.effects.LiveDspConfig
import com.libreopus.dsp.effects.ResamplingTimelineReader
import com.libreopus.dsp.effects.SoftClipLimiter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDspProcessorTest {

    @Test
    fun `bypass returns identical input samples without mutating output`() {
        val chain = LiveDspChain(sampleRate = 48000, channels = 2)
        chain.updateConfig(
            LiveDspConfig(
                gainDb = 9f,
                eqBandsDb = listOf(6f, 0f, -4f, 0f, 3f, 0f, -6f),
                highPassHz = 200f,
                gateEnabled = true,
                bypass = true,
            ),
        )
        val input = ShortArray(512) { idx ->
            (sin(2.0 * PI * 440.0 * (idx / 2) / 48000.0) * 10000.0).toInt().toShort()
        }
        val expected = input.copyOf()
        chain.processInterleaved16(input, frameCount = 256)
        assertArrayEquals(expected, input)
    }

    @Test
    fun `live gain and high pass attenuate low frequencies safely without clipping`() {
        val sr = 48000
        val frames = 2048
        val chain = LiveDspChain(sampleRate = sr, channels = 1)
        chain.updateConfig(
            LiveDspConfig(
                gainDb = 6f,
                highPassHz = 400f,
                bypass = false,
            ),
        )

        // 60 Hz tone should be strongly attenuated by 400 Hz 4th-order HPF even with +6 dB gain
        val lowTone = ShortArray(frames) { idx ->
            (sin(2.0 * PI * 60.0 * idx / sr) * 12000.0).toInt().toShort()
        }
        chain.processInterleaved16(lowTone, frames)
        val tailMaxLow = (frames / 2 until frames).maxOf { abs(lowTone[it].toInt()) }
        assertTrue("Expected 60 Hz tone to be attenuated (<1000), got $tailMaxLow", tailMaxLow < 1000)

        // +18 dB gain on loud 1 kHz tone should soft-limit without overflowing Short range
        val chainHot = LiveDspChain(sampleRate = sr, channels = 1)
        chainHot.updateConfig(LiveDspConfig(gainDb = 18f))
        val hotTone = ShortArray(frames) { idx ->
            (sin(2.0 * PI * 1000.0 * idx / sr) * 20000.0).toInt().toShort()
        }
        chainHot.processInterleaved16(hotTone, frames)
        assertFalse(hotTone.any { it == Short.MIN_VALUE })
        assertTrue(hotTone.maxOf { abs(it.toInt()) } >= SoftClipLimiter.floatToPcm16(0.95f).toInt())
    }

    @Test
    fun `noise gate suppresses sub-threshold background hum while passing loud speech`() {
        val sr = 48000
        val chain = LiveDspChain(sampleRate = sr, channels = 1)
        chain.updateConfig(
            LiveDspConfig(
                gateEnabled = true,
                gateThresholdDb = -30f,
                bypass = false,
            ),
        )

        // Warm up gate closure on very quiet -55 dBFS signal (~58 amplitude)
        val quiet = ShortArray(4096) { 58 }
        repeat(4) {
            val copy = quiet.copyOf()
            chain.processInterleaved16(copy, copy.size)
            if (it == 3) {
                val tailAvg = (copy.size - 512 until copy.size).map { i -> abs(copy[i].toInt()) }.average()
                assertTrue("Expected gated signal near zero, got $tailAvg", tailAvg < 5.0)
            }
        }
    }

    @Test
    fun `resamplingTimelineReader downsamples 48kHz to 16kHz cleanly across chunk boundaries`() {
        val srcRate = 48000
        val dstRate = 16000
        val srcFrames = 4800L // 100 ms
        val toneHz = 500.0
        val srcSamples = ShortArray(srcFrames.toInt()) { i ->
            (sin(2.0 * PI * toneHz * i / srcRate) * 20000.0).toInt().toShort()
        }
        val delegate = object : PcmTimelineReader {
            override val sampleRate: Int = srcRate
            override val channels: Int = 1
            override val totalFrames: Long = srcFrames
            override fun readFrames(startFrame: Long, destInterleaved16: ShortArray, frameOffset: Int, frameCount: Int): Int {
                val avail = minOf(frameCount.toLong(), srcFrames - startFrame).toInt().coerceAtLeast(0)
                System.arraycopy(srcSamples, startFrame.toInt(), destInterleaved16, frameOffset, avail)
                return avail
            }
        }

        val resampled = ResamplingTimelineReader(delegate, dstRate)
        assertEquals(dstRate, resampled.sampleRate)
        assertEquals(1600L, resampled.totalFrames)

        // Read in small 257-frame chunks to verify sub-sample phase continuity at boundaries
        val allOut = ShortArray(1600)
        var cursor = 0L
        while (cursor < resampled.totalFrames) {
            val chunk = resampled.readFrames(cursor, allOut, cursor.toInt(), 257)
            if (chunk <= 0) break
            cursor += chunk
        }
        assertEquals(1600L, cursor)

        // Check accuracy against ideal 500 Hz sine sampled at 16 kHz across interior points
        var maxErr = 0
        for (i in 16 until 1584) {
            val expected = (sin(2.0 * PI * toneHz * i / dstRate) * 20000.0).toInt()
            val err = abs(allOut[i].toInt() - expected)
            if (err > maxErr) maxErr = err
        }
        assertTrue("Expected low Hermite resampling error (<200), got $maxErr", maxErr < 200)
    }
}
