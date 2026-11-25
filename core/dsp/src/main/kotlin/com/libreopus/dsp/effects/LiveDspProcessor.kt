// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import kotlin.math.abs
import kotlin.math.exp

/**
 * Immutable snapshot describing active real-time DSP auditioning parameters.
 */
data class LiveDspConfig(
    val gainDb: Float = 0f,
    val eqBandsDb: List<Float> = emptyList(),
    val highPassHz: Float? = null,
    val lowPassHz: Float? = null,
    val gateEnabled: Boolean = false,
    val gateThresholdDb: Float = -40f,
    val bypass: Boolean = false,
) {
    val hasAnyEffect: Boolean
        get() = abs(gainDb) > 0.05f ||
            eqBandsDb.any { abs(it) > 0.05f } ||
            highPassHz != null ||
            lowPassHz != null ||
            gateEnabled
}

/**
 * Zero-allocation chunk processor for real-time A/B effect auditioning in [AudioPreviewPlayer].
 * Reuses the exact filter math of [EqualizerProcessor] and [FilterAndGateProcessor].
 */
class LiveDspChain(
    private val sampleRate: Int,
    channels: Int,
) {
    private val safeChannels = channels.coerceIn(1, 2)
    private val safeSampleRate = sampleRate.coerceAtLeast(8000).toDouble()

    private var config = LiveDspConfig()
    private var lastEqBands: List<Float> = emptyList()
    private var lastHpHz: Float? = null
    private var lastLpHz: Float? = null

    private var eqFilters: List<BiquadFilter> = emptyList()
    private var passFilters: List<BiquadFilter> = emptyList()

    // Noise gate envelope state
    private val envAttack = exp((-1.0 / (safeSampleRate * 0.002))).toFloat()
    private val envRelease = exp((-1.0 / (safeSampleRate * 0.050))).toFloat()
    private val gateAttack = exp((-1.0 / (safeSampleRate * 0.008))).toFloat()
    private val gateRelease = exp((-1.0 / (safeSampleRate * 0.090))).toFloat()
    private val holdFrames = (safeSampleRate * 0.040).toInt()
    private val floorGain = SoftClipLimiter.dbToLinear(-48f)
    private var env = 0f
    private var gateGain = 1f
    private var holdRemaining = 0

    private var floatScratch = FloatArray(2048 * safeChannels)

    fun updateConfig(next: LiveDspConfig) {
        if (next == config) return
        val normalizedEq = next.eqBandsDb
        if (normalizedEq != lastEqBands) {
            lastEqBands = normalizedEq
            eqFilters = buildEqFilters(normalizedEq)
        }
        if (next.highPassHz != lastHpHz || next.lowPassHz != lastLpHz) {
            lastHpHz = next.highPassHz
            lastLpHz = next.lowPassHz
            passFilters = buildPassFilters(next.highPassHz, next.lowPassHz)
        }
        if (next.gateEnabled && !config.gateEnabled) {
            env = 0f
            gateGain = 1f
            holdRemaining = 0
        }
        config = next
    }

    fun resetState() {
        eqFilters.forEach { it.reset() }
        passFilters.forEach { it.reset() }
        env = 0f
        gateGain = 1f
        holdRemaining = 0
    }

    fun processInterleaved16(buf: ShortArray, frameCount: Int) {
        val cfg = config
        if (cfg.bypass || !cfg.hasAnyEffect || frameCount <= 0) return

        val sampleCount = frameCount * safeChannels
        if (floatScratch.size < sampleCount) {
            floatScratch = FloatArray(sampleCount)
        }

        val gainLinear = if (abs(cfg.gainDb) > 0.05f) SoftClipLimiter.dbToLinear(cfg.gainDb) else 1f
        for (i in 0 until sampleCount) {
            floatScratch[i] = (buf[i] / 32768f) * gainLinear
        }

        val eq = eqFilters
        val pass = passFilters
        if (eq.isNotEmpty() || pass.isNotEmpty()) {
            for (f in 0 until frameCount) {
                val base = f * safeChannels
                for (c in 0 until safeChannels) {
                    var s = floatScratch[base + c].toDouble()
                    for (flt in eq) {
                        s = flt.process(s, c)
                    }
                    for (flt in pass) {
                        s = flt.process(s, c)
                    }
                    floatScratch[base + c] = s.toFloat()
                }
            }
        }

        if (cfg.gateEnabled) {
            val threshLinear = SoftClipLimiter.dbToLinear(cfg.gateThresholdDb)
            for (f in 0 until frameCount) {
                val base = f * safeChannels
                var peakFrame = 0f
                for (c in 0 until safeChannels) {
                    val a = abs(floatScratch[base + c])
                    if (a > peakFrame) peakFrame = a
                }
                env = if (peakFrame > env) {
                    envAttack * env + (1f - envAttack) * peakFrame
                } else {
                    envRelease * env + (1f - envRelease) * peakFrame
                }
                val targetGain = if (env >= threshLinear) {
                    holdRemaining = holdFrames
                    1f
                } else if (holdRemaining > 0) {
                    holdRemaining--
                    1f
                } else {
                    floorGain
                }
                gateGain = if (targetGain > gateGain) {
                    gateAttack * gateGain + (1f - gateAttack) * targetGain
                } else {
                    gateRelease * gateGain + (1f - gateRelease) * targetGain
                }
                for (c in 0 until safeChannels) {
                    floatScratch[base + c] *= gateGain
                }
            }
        }

        for (i in 0 until sampleCount) {
            buf[i] = SoftClipLimiter.floatToPcm16(floatScratch[i])
        }
    }

    private fun buildEqFilters(bandGainsDb: List<Float>): List<BiquadFilter> {
        val freqs = EqualizerProcessor.BAND_FREQUENCIES_HZ
        return freqs.toList().mapIndexedNotNull { idx, freq ->
            val gain = bandGainsDb.getOrElse(idx) { 0f }.coerceIn(-12f, 12f)
            if (abs(gain) < 0.05f || freq >= safeSampleRate * 0.45) {
                null
            } else {
                val flt = when (idx) {
                    0 -> EqualizerProcessor.createLowShelf(safeSampleRate, freq.toDouble(), gain.toDouble())
                    freqs.lastIndex -> EqualizerProcessor.createHighShelf(safeSampleRate, freq.toDouble(), gain.toDouble())
                    else -> EqualizerProcessor.createPeaking(safeSampleRate, freq.toDouble(), 1.2, gain.toDouble())
                }
                flt.apply { ensureChannels(safeChannels) }
            }
        }
    }

    private fun buildPassFilters(hpHz: Float?, lpHz: Float?): List<BiquadFilter> {
        val cascade = mutableListOf<BiquadFilter>()
        if (hpHz != null && hpHz > 5f && hpHz < safeSampleRate * 0.45) {
            cascade += FilterAndGateProcessor.createButterworthHighPass(safeSampleRate, hpHz.toDouble())
                .apply { ensureChannels(safeChannels) }
            cascade += FilterAndGateProcessor.createButterworthHighPass(safeSampleRate, hpHz.toDouble())
                .apply { ensureChannels(safeChannels) }
        }
        if (lpHz != null && lpHz > 50f && lpHz < safeSampleRate * 0.48) {
            cascade += FilterAndGateProcessor.createButterworthLowPass(safeSampleRate, lpHz.toDouble())
                .apply { ensureChannels(safeChannels) }
            cascade += FilterAndGateProcessor.createButterworthLowPass(safeSampleRate, lpHz.toDouble())
                .apply { ensureChannels(safeChannels) }
        }
        return cascade
    }
}
