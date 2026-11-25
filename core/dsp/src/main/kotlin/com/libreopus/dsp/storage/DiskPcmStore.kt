// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.storage

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages immutable 16-bit signed little-endian interleaved PCM chunk files on disk
 * so long recordings stay well under a few MB of heap memory.
 */
class DiskPcmStore(
    val sessionDir: File,
) {
    private val chunkCounter = AtomicInteger(0)

    init {
        if (!sessionDir.exists()) {
            sessionDir.mkdirs()
        }
    }

    fun createChunkFile(prefix: String = "chunk"): File {
        val idx = chunkCounter.incrementAndGet()
        return File(sessionDir, "${prefix}_$idx.pcm")
    }

    fun writePcm16Le(file: File, samples: ShortArray, sampleCount: Int = samples.size) {
        val bytes = ByteArray(sampleCount * 2)
        for (i in 0 until sampleCount) {
            val v = samples[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }
        BufferedOutputStream(FileOutputStream(file)).use { it.write(bytes) }
    }

    fun clearSession() {
        sessionDir.listFiles()?.forEach { it.delete() }
    }

    companion object {
        fun readFramesFromFile(
            file: File,
            channels: Int,
            startFrame: Long,
            destInterleaved16: ShortArray,
            frameOffset: Int,
            frameCount: Int,
            gain: Float = 1f,
        ): Int {
            if (frameCount <= 0 || !file.exists()) return 0
            val bytesPerFrame = channels * 2L
            val byteOffset = startFrame * bytesPerFrame
            val totalFileFrames = file.length() / bytesPerFrame
            val available = (totalFileFrames - startFrame).coerceAtLeast(0L)
            val toRead = minOf(frameCount.toLong(), available).toInt()
            if (toRead <= 0) return 0

            val byteBuf = ByteArray(toRead * channels * 2)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(byteOffset)
                var readTotal = 0
                while (readTotal < byteBuf.size) {
                    val r = raf.read(byteBuf, readTotal, byteBuf.size - readTotal)
                    if (r < 0) break
                    readTotal += r
                }
                val actualFrames = readTotal / (channels * 2)
                val destStart = frameOffset * channels
                val applyGain = kotlin.math.abs(gain - 1f) > 1e-4f
                for (i in 0 until actualFrames * channels) {
                    val lo = byteBuf[i * 2].toInt() and 0xFF
                    val hi = byteBuf[i * 2 + 1].toInt()
                    val sample = ((hi shl 8) or lo).toShort()
                    destInterleaved16[destStart + i] = if (applyGain) {
                        (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    } else {
                        sample
                    }
                }
                return actualFrames
            }
        }

        fun encodeSamplesToBytesLe(samples: ShortArray, sampleCount: Int, outBytes: ByteArray) {
            for (i in 0 until sampleCount) {
                val v = samples[i].toInt()
                outBytes[i * 2] = (v and 0xFF).toByte()
                outBytes[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
            }
        }

        fun decodeBytesLeToSamples(bytes: ByteArray, byteCount: Int, outSamples: ShortArray): Int {
            val sampleCount = byteCount / 2
            for (i in 0 until sampleCount) {
                val lo = bytes[i * 2].toInt() and 0xFF
                val hi = bytes[i * 2 + 1].toInt()
                outSamples[i] = ((hi shl 8) or lo).toShort()
            }
            return sampleCount
        }
    }
}
