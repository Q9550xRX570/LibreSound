// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp

import com.libreopus.dsp.effects.ChannelOperation
import com.libreopus.dsp.effects.EqualizerProcessor
import com.libreopus.dsp.effects.FadeCurve
import com.libreopus.dsp.effects.FadeProcessor
import com.libreopus.dsp.effects.FilterAndGateProcessor
import com.libreopus.dsp.effects.GainAndNormalizeProcessor
import com.libreopus.dsp.effects.TimePitchProcessor
import com.libreopus.dsp.effects.UtilityProcessors
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import com.libreopus.dsp.waveform.WaveformPeakGenerator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspProcessorsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun createSineTimeline(
        store: DiskPcmStore,
        freqHz: Double = 1000.0,
        amplitude: Float = 0.25f,
        durationSec: Double = 1.0,
        sampleRate: Int = 48000,
        channels: Int = 2,
    ): EditTimeline {
        val frames = (sampleRate * durationSec).toInt()
        val samples = ShortArray(frames * channels)
        for (f in 0 until frames) {
            val s = (sin(2.0 * PI * freqHz * f / sampleRate) * amplitude * 32767.0).toInt().toShort()
            for (c in 0 until channels) {
                samples[f * channels + c] = s
            }
        }
        val file = store.createChunkFile("sine")
        store.writePcm16Le(file, samples)
        return EditTimeline.fromSingleFile(sampleRate, channels, file, frames.toLong())
    }

    private fun measureRms(timeline: EditTimeline, start: Long = 0L, end: Long = timeline.totalFrames): Float {
        val frames = (end - start).toInt()
        val buf = ShortArray(frames * timeline.channels)
        timeline.readFrames(start, buf, 0, frames)
        var sum = 0.0
        for (s in buf) {
            val x = s / 32768.0
            sum += x * x
        }
        return sqrt(sum / buf.size).toFloat()
    }

    @Test
    fun `gain peak normalize and LUFS normalize reach target levels`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("gain"))
        val tl = createSineTimeline(store, freqHz = 1000.0, amplitude = 0.2f, durationSec = 1.2)

        val boosted = GainAndNormalizeProcessor.applyGain(tl, store, 0L, tl.totalFrames, 6.02f)
        val peakBoosted = GainAndNormalizeProcessor.measurePeakLinear(boosted)
        assertEquals(0.4f, peakBoosted, 0.02f)

        val peakNorm = GainAndNormalizeProcessor.normalizePeak(tl, store, 0L, tl.totalFrames, -1.0f)
        assertEquals(0.891f, GainAndNormalizeProcessor.measurePeakLinear(peakNorm), 0.02f)

        val lufsNorm = GainAndNormalizeProcessor.normalizeLufs(tl, store, 0L, tl.totalFrames, -16.0f)
        val measured = GainAndNormalizeProcessor.measureIntegratedLufs(lufsNorm)
        assertEquals(-16.0f, measured, 0.8f)
    }

    @Test
    fun `all fade curves taper smoothly at endpoints`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("fade"))
        val tl = createSineTimeline(store, amplitude = 0.8f, durationSec = 0.5, channels = 1)
        for (curve in FadeCurve.entries) {
            val fadedIn = FadeProcessor.applyFade(tl, store, 0L, tl.totalFrames, fadeIn = true, curve = curve)
            val startRms = measureRms(fadedIn, 0L, 1000L)
            val endRms = measureRms(fadedIn, fadedIn.totalFrames - 1000L, fadedIn.totalFrames)
            assertTrue("FadeIn $curve startRms=$startRms endRms=$endRms", startRms < endRms * 0.25f)

            val fadedOut = FadeProcessor.applyFade(tl, store, 0L, tl.totalFrames, fadeIn = false, curve = curve)
            val outStart = measureRms(fadedOut, 0L, 1000L)
            val outEnd = measureRms(fadedOut, fadedOut.totalFrames - 1000L, fadedOut.totalFrames)
            assertTrue("FadeOut $curve outEnd=$outEnd outStart=$outStart", outEnd < outStart * 0.25f)
        }
    }

    @Test
    fun `eq and butterworth highpass attenuate target frequencies`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("eq"))
        val lowSine = createSineTimeline(store, freqHz = 60.0, amplitude = 0.5f, durationSec = 1.0, channels = 1)
        val baseRms = measureRms(lowSine)

        val hpFiltered = FilterAndGateProcessor.applyHighLowPass(lowSine, store, 0L, lowSine.totalFrames, highPassHz = 300f, lowPassHz = null)
        assertTrue(measureRms(hpFiltered) < baseRms * 0.2f)

        val eqCut = EqualizerProcessor.applyEq(lowSine, store, 0L, lowSine.totalFrames, listOf(-12f, 0f, 0f, 0f, 0f, 0f, 0f))
        // At corner frequency 60 Hz, a -12 dB low-shelf attenuates by ~-6 dB (~0.50x)
        assertTrue(measureRms(eqCut) < baseRms * 0.60f)
    }

    @Test
    fun `noise gate and silence trimmer suppress and remove silence`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("gate"))
        val sr = 48000
        // 0.5s silence + 0.5s tone + 0.5s silence
        val frames = sr * 3 / 2
        val samples = ShortArray(frames) { idx ->
            if (idx in (sr / 2) until sr) (sin(2.0 * PI * 440.0 * idx / sr) * 16000.0).toInt().toShort()
            else ((idx % 5) - 2).toShort() // tiny noise floor
        }
        val file = store.createChunkFile("sil")
        store.writePcm16Le(file, samples)
        val tl = EditTimeline.fromSingleFile(sr, 1, file, frames.toLong())

        val trimmed = FilterAndGateProcessor.trimSilence(tl, store, 0L, tl.totalFrames, thresholdDb = -40f, minSilenceMs = 200, keepPaddingMs = 20)
        assertTrue("Expected trimmed frames < 35000, was ${trimmed.totalFrames}", trimmed.totalFrames < 35000L)

        val gated = FilterAndGateProcessor.applyNoiseGate(tl, store, 0L, tl.totalFrames, thresholdDb = -20f, reductionDb = -60f)
        val silentTailRms = measureRms(gated, sr + 8000L, gated.totalFrames)
        assertTrue(silentTailRms < 0.001f)
    }

    @Test
    fun `wsola tempo changes duration while linked speed and pitch work cleanly`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("time"))
        val tl = createSineTimeline(store, freqHz = 440.0, amplitude = 0.5f, durationSec = 1.0, channels = 1)

        val faster = TimePitchProcessor.applySpeedAndPitch(tl, store, 0L, tl.totalFrames, tempoFactor = 2.0f, pitchSemitones = 0f, linkedSpeedPitch = false)
        assertEquals(24000L, faster.totalFrames, 500L)

        val pitchedUp = TimePitchProcessor.applySpeedAndPitch(tl, store, 0L, tl.totalFrames, tempoFactor = 1.0f, pitchSemitones = 5f, linkedSpeedPitch = false)
        assertEquals(48000L, pitchedUp.totalFrames, 1000L)
    }

    @Test
    fun `reverse channel ops mix overlay and waveform pyramid produce valid output`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("util"))
        val f1 = store.createChunkFile("ramp")
        store.writePcm16Le(f1, ShortArray(2000) { i -> ((i / 2) * 10).toShort() }) // 1000 stereo frames
        val tl = EditTimeline.fromSingleFile(48000, 2, f1, 1000L)

        val rev = UtilityProcessors.reverse(tl, store, 0L, tl.totalFrames)
        val revBuf = ShortArray(2000)
        rev.readFrames(0L, revBuf, 0, 1000)
        assertTrue(revBuf[0] > revBuf[1998])

        val mono = UtilityProcessors.applyChannelOperation(tl, store, ChannelOperation.TO_MONO_MIX)
        assertEquals(1, mono.channels)
        assertEquals(1000L, mono.totalFrames)

        val mixed = UtilityProcessors.mixOverlay(tl, tl, store, offsetFrames = 500L, baseGainDb = 0f, overlayGainDb = -3f)
        assertEquals(1500L, mixed.totalFrames)

        val pyramid = WaveformPeakGenerator.generate(mixed)
        assertEquals(3, pyramid.levels.size)
        assertTrue(pyramid.levels[0].binCount > 0)
        val best = pyramid.bestLevelFor(1500L, 100)
        assertTrue(best != null && abs(best.maxPeaks.max()) <= 1.0f)
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Long) {
        assertTrue("Expected $expected +/- $delta, got $actual", abs(expected - actual) <= delta)
    }
}
