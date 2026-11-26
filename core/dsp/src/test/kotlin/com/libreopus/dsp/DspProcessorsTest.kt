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

    @Test
    fun `compressor reverb and stereo widener with bass boost process accurately`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("space_dyn"))

        // 1. Compressor: loud -3 dBFS signal compressed toward -18 dBFS threshold with 6:1 ratio (0 dB makeup)
        val hot = createSineTimeline(store, freqHz = 1000.0, amplitude = 0.707f, durationSec = 0.6, channels = 1)
        val compressed = com.libreopus.dsp.effects.CompressorProcessor.apply(
            hot, store, 0L, hot.totalFrames,
            thresholdDb = -18f, ratio = 6f, attackMs = 5f, releaseMs = 80f, makeupDb = 0f,
        )
        val tailBase = measureRms(hot, 8000L, hot.totalFrames)
        val tailComp = measureRms(compressed, 8000L, compressed.totalFrames)
        assertTrue("Expected compressed tail < 0.6x original (orig=$tailBase, comp=$tailComp)", tailComp < tailBase * 0.6f)

        // 2. Reverb: short impulse followed by silence gains non-zero reverberant tail that stays bounded
        val sr = 48000
        val impSamples = ShortArray(sr)
        for (i in 0 until 200) {
            impSamples[i] = (sin(2.0 * PI * 800.0 * i / sr) * 20000.0).toInt().toShort()
        }
        val impFile = store.createChunkFile("imp")
        store.writePcm16Le(impFile, impSamples)
        val impTl = EditTimeline.fromSingleFile(sr, 1, impFile, sr.toLong())
        val reverbed = com.libreopus.dsp.effects.ReverbEchoProcessor.apply(
            impTl, store, 0L, impTl.totalFrames,
            delayMs = 80f, feedback = 0.5f, roomDamping = 0.3f, wetMix = 0.45f,
        )
        val wetTailRms = measureRms(reverbed, 4000L, 12000L)
        assertTrue("Expected reverberant tail > 0.005, got $wetTailRms", wetTailRms > 0.005f)
        val lateTailRms = measureRms(reverbed, 36000L, 48000L)
        assertTrue("Expected reverb tail decay over time ($lateTailRms < $wetTailRms)", lateTailRms < wetTailRms)

        // 3. Stereo widener: mono mix when width=0, widened L-R difference when width=1.8, bass boost at 60Hz
        val stereoFrames = 4800
        val stSamples = ShortArray(stereoFrames * 2)
        for (f in 0 until stereoFrames) {
            stSamples[f * 2] = (sin(2.0 * PI * 500.0 * f / sr) * 12000.0).toInt().toShort()
            stSamples[f * 2 + 1] = (sin(2.0 * PI * 500.0 * f / sr) * 4000.0).toInt().toShort()
        }
        val stFile = store.createChunkFile("stereo")
        store.writePcm16Le(stFile, stSamples)
        val stTl = EditTimeline.fromSingleFile(sr, 2, stFile, stereoFrames.toLong())

        val collapsed = com.libreopus.dsp.effects.StereoWidenerProcessor.apply(stTl, store, 0L, stTl.totalFrames, width = 0f, bassBoostDb = 0f)
        val colBuf = ShortArray(200)
        collapsed.readFrames(100L, colBuf, 0, 100)
        val maxDiffCollapsed = (0 until 100).maxOf { abs(colBuf[it * 2] - colBuf[it * 2 + 1]) }
        assertTrue("Width=0 should collapse L/R diff to ~0, got $maxDiffCollapsed", maxDiffCollapsed <= 2)

        val bassTl = createSineTimeline(store, freqHz = 60.0, amplitude = 0.25f, durationSec = 0.5, channels = 1)
        val boostedBass = com.libreopus.dsp.effects.StereoWidenerProcessor.apply(bassTl, store, 0L, bassTl.totalFrames, width = 1f, bassBoostDb = 8f)
        assertTrue(measureRms(boostedBass, 4000L, boostedBass.totalFrames) > measureRms(bassTl, 4000L, bassTl.totalFrames) * 1.4f)
    }

    @Test
    fun `concatenateWithCrossfade merges mixed rate tracks smoothly`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("merge"))
        val t1 = createSineTimeline(store, freqHz = 440.0, amplitude = 0.4f, durationSec = 1.0, sampleRate = 16000, channels = 1)
        val t2 = createSineTimeline(store, freqHz = 660.0, amplitude = 0.4f, durationSec = 1.0, sampleRate = 48000, channels = 2)

        val merged = UtilityProcessors.concatenateWithCrossfade(
            readers = listOf(t1, t2),
            store = store,
            targetSampleRate = 48000,
            targetChannels = 2,
            crossfadeMs = 200L,
        )
        assertEquals(48000, merged.sampleRate)
        assertEquals(2, merged.channels)
        // Expected ~1.8s = ~86400 frames at 48kHz
        assertEquals(86400L, merged.totalFrames, 500L)
        assertTrue(measureRms(merged, 0L, merged.totalFrames) > 0.1f)
    }

    @Test
    fun `detectNonSilentRegions identifies spoken bursts while bridging brief internal pauses`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("regions"))
        val sr = 48000
        // 3s timeline: [0.2..0.7s] + 60ms internal dip + [0.76..1.1s], silence [1.1..1.8s], second burst [1.8..2.4s]
        val frames = sr * 3
        val samples = ShortArray(frames) { idx ->
            val tMs = (idx * 1000L) / sr
            val active = (tMs in 200L until 700L) || (tMs in 760L until 1100L) || (tMs in 1800L until 2400L)
            if (active) (sin(2.0 * PI * 440.0 * idx / sr) * 14000.0).toInt().toShort() else 0
        }
        val f = store.createChunkFile("seg")
        store.writePcm16Le(f, samples)
        val tl = EditTimeline.fromSingleFile(sr, 1, f, frames.toLong())

        val regions = UtilityProcessors.detectNonSilentRegions(
            reader = tl,
            thresholdDb = -36f,
            minSilenceMs = 400,
            minSegmentMs = 250,
            paddingMs = 20,
        )
        assertEquals("Expected 2 bridged regions, got $regions", 2, regions.size)
        assertTrue(regions[0].first in 170L..210L && regions[0].second in 1090L..1140L)
        assertTrue(regions[1].first in 1770L..1810L && regions[1].second in 2390L..2440L)
    }

    @Test
    fun `vocalRemoveProcessor suppresses centered vocals while preserving stereo diff and low bass`() = runBlocking {
        val store = DiskPcmStore(tempFolder.newFolder("vocal"))
        val sr = 48000
        val frames = sr / 2

        // Pure centered 1kHz sine (L == R): strong vocal attenuation
        val centeredMid = createSineTimeline(store, freqHz = 1000.0, amplitude = 0.5f, durationSec = 0.5, sampleRate = sr, channels = 2)
        val canceled = com.libreopus.dsp.effects.VocalRemoveProcessor.apply(
            centeredMid, store, 0L, centeredMid.totalFrames,
            strength = 0.95f, preserveBassHz = 180f,
        )
        assertTrue(
            "Expected strong center mid suppression",
            measureRms(canceled, 4000L, canceled.totalFrames) < measureRms(centeredMid, 4000L, centeredMid.totalFrames) * 0.25f,
        )

        // Centered 70 Hz kick/bass: must remain preserved by preserveBassHz=180
        val centeredBass = createSineTimeline(store, freqHz = 70.0, amplitude = 0.5f, durationSec = 0.5, sampleRate = sr, channels = 2)
        val bassKept = com.libreopus.dsp.effects.VocalRemoveProcessor.apply(
            centeredBass, store, 0L, centeredBass.totalFrames,
            strength = 0.95f, preserveBassHz = 180f,
        )
        assertTrue(
            "Expected 70Hz centered bass preserved (>0.75x)",
            measureRms(bassKept, 4000L, bassKept.totalFrames) > measureRms(centeredBass, 4000L, centeredBass.totalFrames) * 0.75f,
        )

        // Anti-phase stereo 1kHz side signal (L = -R): must remain 100% intact
        val sideBuf = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (sin(2.0 * PI * 1000.0 * i / sr) * 14000.0).toInt().toShort()
            sideBuf[i * 2] = v
            sideBuf[i * 2 + 1] = (-v).toShort()
        }
        val sf = store.createChunkFile("side")
        store.writePcm16Le(sf, sideBuf)
        val sideTl = EditTimeline.fromSingleFile(sr, 2, sf, frames.toLong())
        val sideOut = com.libreopus.dsp.effects.VocalRemoveProcessor.apply(sideTl, store, 0L, sideTl.totalFrames, strength = 1f, preserveBassHz = 180f)
        assertTrue(measureRms(sideOut, 4000L, sideOut.totalFrames) > measureRms(sideTl, 4000L, sideTl.totalFrames) * 0.9f)
    }

    private fun assertEquals(expected: Long, actual: Long, delta: Long) {
        assertTrue("Expected $expected +/- $delta, got $actual", abs(expected - actual) <= delta)
    }
}
