// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.effects

import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

enum class CompressorPreset(
    val thresholdDb: Float,
    val ratio: Float,
    val attackMs: Float,
    val releaseMs: Float,
    val makeupDb: Float,
) {
    GENTLE(thresholdDb = -14f, ratio = 2.2f, attackMs = 20f, releaseMs = 160f, makeupDb = 1.5f),
    VOCAL(thresholdDb = -18f, ratio = 3.5f, attackMs = 10f, releaseMs = 120f, makeupDb = 3.5f),
    PODCAST(thresholdDb = -22f, ratio = 4.5f, attackMs = 8f, releaseMs = 150f, makeupDb = 5.5f),
    PUNCHY(thresholdDb = -20f, ratio = 6.0f, attackMs = 4f, releaseMs = 90f, makeupDb = 5.0f),
}

enum class ReverbPreset(
    val delayMs: Float,
    val feedback: Float,
    val roomDamping: Float,
    val wetMix: Float,
) {
    SMALL_ROOM(delayMs = 45f, feedback = 0.32f, roomDamping = 0.55f, wetMix = 0.24f),
    HALL(delayMs = 130f, feedback = 0.54f, roomDamping = 0.40f, wetMix = 0.34f),
    STUDIO_PLATE(delayMs = 80f, feedback = 0.46f, roomDamping = 0.25f, wetMix = 0.28f),
    ECHO_DELAY(delayMs = 260f, feedback = 0.48f, roomDamping = 0.30f, wetMix = 0.35f),
}

enum class StereoBassPreset(
    val width: Float,
    val bassBoostDb: Float,
) {
    FOCUSED_MONO(width = 0.45f, bassBoostDb = 0f),
    WARM_BASS(width = 1.10f, bassBoostDb = 4.5f),
    WIDE_STAGE(width = 1.55f, bassBoostDb = 1.5f),
    CLUB_BOOST(width = 1.40f, bassBoostDb = 7.5f),
}

/**
 * Stateful soft-knee RMS/peak dynamic range compressor usable for both real-time auditioning
 * and offline timeline rendering.
 */
internal class CompressorKernel(
    sampleRate: Double,
    channels: Int,
) {
    private val sr = sampleRate.coerceAtLeast(8000.0)
    private val ch = channels.coerceIn(1, 2)

    private var thresholdDb = -18f
    private var ratio = 4f
    private var attackCoeff = 0.99f
    private var releaseCoeff = 0.998f
    private var makeupLinear = 1f
    private var envDb = -96f

    init {
        configure(thresholdDb, ratio, 10f, 120f, 0f)
    }

    fun configure(
        thresholdDb: Float,
        ratio: Float,
        attackMs: Float,
        releaseMs: Float,
        makeupDb: Float,
    ) {
        this.thresholdDb = thresholdDb.coerceIn(-40f, 0f)
        this.ratio = ratio.coerceIn(1.5f, 12f)
        val aSec = attackMs.coerceIn(1f, 50f) / 1000.0
        val rSec = releaseMs.coerceIn(40f, 500f) / 1000.0
        this.attackCoeff = exp(-1.0 / (sr * aSec)).toFloat()
        this.releaseCoeff = exp(-1.0 / (sr * rSec)).toFloat()
        this.makeupLinear = SoftClipLimiter.dbToLinear(makeupDb.coerceIn(0f, 18f))
    }

    fun reset() {
        envDb = -96f
    }

    fun processInPlace(interleaved: FloatArray, frameCount: Int) {
        val thr = thresholdDb
        val invRatioFactor = 1f - (1f / ratio)
        val knee = 3f
        val halfKnee = knee * 0.5f

        for (f in 0 until frameCount) {
            val base = f * ch
            var peak = 0f
            for (c in 0 until ch) {
                val a = abs(interleaved[base + c])
                if (a > peak) peak = a
            }
            val levelDb = SoftClipLimiter.linearToDb(peak).coerceAtLeast(-96f)
            val coeff = if (levelDb > envDb) attackCoeff else releaseCoeff
            envDb = coeff * envDb + (1f - coeff) * levelDb

            val over = envDb - thr
            val reductionDb = when {
                over <= -halfKnee -> 0f
                over >= halfKnee -> over * invRatioFactor
                else -> {
                    val delta = over + halfKnee
                    (delta * delta) / (2f * knee) * invRatioFactor
                }
            }
            val gain = SoftClipLimiter.dbToLinear(-reductionDb) * makeupLinear
            for (c in 0 until ch) {
                interleaved[base + c] *= gain
            }
        }
    }
}

/**
 * Low-memory Schroeder room reverb + damped pre-delay echo line for zero-bloat mobile acoustics.
 */
internal class SchroederReverbKernel(
    sampleRate: Double,
    channels: Int,
) {
    private val sr = sampleRate.coerceAtLeast(8000.0)
    private val ch = channels.coerceIn(1, 2)

    private var delayMs = 120f
    private var feedback = 0.42f
    private var roomDamping = 0.45f
    private var wetMix = 0.28f

    // Pre-delay / echo line (up to ~600 ms)
    private val maxEchoFrames = (sr * 0.60).toInt().coerceAtLeast(64)
    private var echoBufs = Array(ch) { FloatArray(0) }
    private var echoWriteIdx = 0
    private var echoDelayFrames = 1
    private var echoLpState = FloatArray(ch)

    // 4 parallel damped comb filters + 2 series allpass diffusers per channel
    private val combDelaysMs = floatArrayOf(29.7f, 33.1f, 37.3f, 41.9f)
    private val allpassDelaysMs = floatArrayOf(5.1f, 1.7f)

    private var combBufs: Array<Array<FloatArray>> = emptyArray()
    private var combIdx: Array<IntArray> = emptyArray()
    private var combLp: Array<FloatArray> = emptyArray()

    private var allpassBufs: Array<Array<FloatArray>> = emptyArray()
    private var allpassIdx: Array<IntArray> = emptyArray()

    private var initialized = false

    fun configure(
        delayMs: Float,
        feedback: Float,
        roomDamping: Float,
        wetMix: Float,
    ) {
        this.delayMs = delayMs.coerceIn(30f, 450f)
        this.feedback = feedback.coerceIn(0f, 0.75f)
        this.roomDamping = roomDamping.coerceIn(0f, 0.9f)
        this.wetMix = wetMix.coerceIn(0f, 0.6f)
        ensureAllocated()
        echoDelayFrames = ((this.delayMs - 25f).coerceAtLeast(1f) * sr / 1000.0)
            .toInt()
            .coerceIn(1, maxEchoFrames - 1)
    }

    private fun ensureAllocated() {
        if (initialized) return
        echoBufs = Array(ch) { FloatArray(maxEchoFrames) }
        combBufs = Array(ch) { c ->
            Array(combDelaysMs.size) { i ->
                val stereoMs = if (c == 1) 1.3f else 0f
                val len = ((combDelaysMs[i] + stereoMs) * sr / 1000.0).toInt().coerceAtLeast(16)
                FloatArray(len)
            }
        }
        combIdx = Array(ch) { IntArray(combDelaysMs.size) }
        combLp = Array(ch) { FloatArray(combDelaysMs.size) }

        allpassBufs = Array(ch) { c ->
            Array(allpassDelaysMs.size) { i ->
                val stereoMs = if (c == 1) 0.4f else 0f
                val len = ((allpassDelaysMs[i] + stereoMs) * sr / 1000.0).toInt().coerceAtLeast(8)
                FloatArray(len)
            }
        }
        allpassIdx = Array(ch) { IntArray(allpassDelaysMs.size) }
        initialized = true
    }

    fun reset() {
        if (!initialized) return
        echoBufs.forEach { it.fill(0f) }
        echoLpState.fill(0f)
        echoWriteIdx = 0
        for (c in 0 until ch) {
            combBufs[c].forEach { it.fill(0f) }
            combIdx[c].fill(0)
            combLp[c].fill(0f)
            allpassBufs[c].forEach { it.fill(0f) }
            allpassIdx[c].fill(0)
        }
    }

    fun processInPlace(interleaved: FloatArray, frameCount: Int) {
        if (wetMix <= 0.005f) return
        ensureAllocated()

        val wet = wetMix
        val dryScale = 1f - wet * 0.35f
        val damp = (roomDamping * 0.78f).coerceIn(0f, 0.85f)
        val combFb = (0.34f + feedback * 0.50f).coerceIn(0.10f, 0.84f)
        val echoFb = (feedback * 0.70f).coerceIn(0f, 0.70f)
        val echoReadIdxStart = (echoWriteIdx - echoDelayFrames + maxEchoFrames) % maxEchoFrames
        var echoReadIdx = echoReadIdxStart

        // Balance direct echo tap vs lush Schroeder diffusion as delay grows past ~110ms
        val echoTapAmount = ((delayMs - 70f) / 140f).coerceIn(0.15f, 0.75f)
        val diffAmount = 1f - echoTapAmount * 0.35f

        for (f in 0 until frameCount) {
            val base = f * ch
            for (c in 0 until ch) {
                val dry = interleaved[base + c]

                val echoBuf = echoBufs[c]
                val rawEcho = echoBuf[echoReadIdx]
                val dampedEcho = rawEcho * (1f - damp * 0.55f) + echoLpState[c] * (damp * 0.55f)
                echoLpState[c] = dampedEcho
                echoBuf[echoWriteIdx] = (dry + dampedEcho * echoFb).coerceIn(-2f, 2f)

                val tankInput = (dry * 0.35f + dampedEcho * 0.65f) * 0.22f
                var combSum = 0f
                val cBufs = combBufs[c]
                val cIndices = combIdx[c]
                val cLps = combLp[c]

                for (k in 0 until cBufs.size) {
                    val buf = cBufs[k]
                    val idx = cIndices[k]
                    val delayed = buf[idx]
                    val lp = delayed * (1f - damp) + cLps[k] * damp
                    cLps[k] = lp
                    buf[idx] = tankInput + lp * combFb
                    cIndices[k] = if (idx + 1 >= buf.size) 0 else idx + 1
                    combSum += delayed
                }

                var diffused = combSum
                val aBufs = allpassBufs[c]
                val aIndices = allpassIdx[c]
                for (a in 0 until aBufs.size) {
                    val buf = aBufs[a]
                    val idx = aIndices[a]
                    val bufOut = buf[idx]
                    val out = -diffused + bufOut
                    buf[idx] = diffused + bufOut * 0.5f
                    aIndices[a] = if (idx + 1 >= buf.size) 0 else idx + 1
                    diffused = out
                }

                val wetSample = dampedEcho * echoTapAmount + diffused * diffAmount
                interleaved[base + c] = dry * dryScale + wetSample * wet
            }
            echoWriteIdx = if (echoWriteIdx + 1 >= maxEchoFrames) 0 else echoWriteIdx + 1
            echoReadIdx = if (echoReadIdx + 1 >= maxEchoFrames) 0 else echoReadIdx + 1
        }
    }
}

/**
 * Mid-Side stereo width processor paired with a 95 Hz low-shelf bass boost filter.
 */
internal class StereoBassKernel(
    sampleRate: Double,
    channels: Int,
) {
    private val sr = sampleRate.coerceAtLeast(8000.0)
    private val ch = channels.coerceIn(1, 2)

    private var width = 1f
    private var bassBoostDb = 0f
    private var lastFilterDb = 0f
    private var bassFilter: BiquadFilter? = null

    fun configure(width: Float, bassBoostDb: Float) {
        this.width = width.coerceIn(0f, 2f)
        val clampedDb = bassBoostDb.coerceIn(0f, 9f)
        this.bassBoostDb = clampedDb
        if (abs(clampedDb - lastFilterDb) > 0.05f) {
            lastFilterDb = clampedDb
            bassFilter = if (clampedDb > 0.05f && 95.0 < sr * 0.45) {
                EqualizerProcessor.createLowShelf(sr, 95.0, clampedDb.toDouble()).apply {
                    ensureChannels(ch)
                }
            } else {
                null
            }
        }
    }

    fun reset() {
        bassFilter?.reset()
    }

    fun processInPlace(interleaved: FloatArray, frameCount: Int) {
        val flt = bassFilter
        if (flt != null) {
            for (f in 0 until frameCount) {
                val base = f * ch
                for (c in 0 until ch) {
                    interleaved[base + c] = flt.process(interleaved[base + c].toDouble(), c).toFloat()
                }
            }
        }
        val w = width
        if (ch == 2 && abs(w - 1f) > 0.01f) {
            for (f in 0 until frameCount) {
                val base = f * 2
                val l = interleaved[base]
                val r = interleaved[base + 1]
                val mid = 0.5f * (l + r)
                val side = 0.5f * (l - r) * w
                interleaved[base] = mid + side
                interleaved[base + 1] = mid - side
            }
        }
    }
}

object CompressorProcessor {
    suspend fun apply(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        thresholdDb: Float = -18f,
        ratio: Float = 4f,
        attackMs: Float = 10f,
        releaseMs: Float = 120f,
        makeupDb: Float = 0f,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        val kernel = CompressorKernel(timeline.sampleRate.toDouble(), timeline.channels).apply {
            configure(thresholdDb, ratio, attackMs, releaseMs, makeupDb)
        }
        return streamProcessRange(timeline, store, startFrame, endFrame, "comp", onProgress) { buf, frames, _ ->
            kernel.processInPlace(buf, frames)
        }
    }
}

object ReverbEchoProcessor {
    suspend fun apply(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        delayMs: Float = 120f,
        feedback: Float = 0.42f,
        roomDamping: Float = 0.45f,
        wetMix: Float = 0.28f,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        if (wetMix <= 0.005f) return timeline
        val kernel = SchroederReverbKernel(timeline.sampleRate.toDouble(), timeline.channels).apply {
            configure(delayMs, feedback, roomDamping, wetMix)
        }
        return streamProcessRange(timeline, store, startFrame, endFrame, "reverb", onProgress) { buf, frames, _ ->
            kernel.processInPlace(buf, frames)
        }
    }
}

object StereoWidenerProcessor {
    suspend fun apply(
        timeline: EditTimeline,
        store: DiskPcmStore,
        startFrame: Long,
        endFrame: Long,
        width: Float = 1f,
        bassBoostDb: Float = 0f,
        onProgress: (Float) -> Unit = {},
    ): EditTimeline {
        if (abs(width - 1f) <= 0.01f && bassBoostDb <= 0.05f) return timeline
        val kernel = StereoBassKernel(timeline.sampleRate.toDouble(), timeline.channels).apply {
            configure(width, bassBoostDb)
        }
        return streamProcessRange(timeline, store, startFrame, endFrame, "stereo_bass", onProgress) { buf, frames, _ ->
            kernel.processInPlace(buf, frames)
        }
    }
}
