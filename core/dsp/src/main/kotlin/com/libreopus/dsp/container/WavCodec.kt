// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.container

import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.audio.api.WavBitDepth
import com.libreopus.dsp.storage.DiskPcmStore
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.min

data class WavHeader(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val bitsPerSample: Int,
    val dataSizeBytes: Long,
) {
    val bytesPerFrame: Int get() = channels * (bitsPerSample / 8)
    val totalFrames: Long get() = if (bytesPerFrame <= 0) 0L else dataSizeBytes / bytesPerFrame
    val isFloat: Boolean get() = audioFormat == 3
}

object WavCodec {

    fun decodeStreamToPcm16File(
        input: InputStream,
        outPcmFile: File,
        onProgress: (Long) -> Unit = {},
    ): Pair<WavHeader, AudioMetadata> {
        val riff = ByteArray(12)
        readExact(input, riff)
        if (String(riff, 0, 4, StandardCharsets.US_ASCII) != "RIFF" ||
            String(riff, 8, 4, StandardCharsets.US_ASCII) != "WAVE"
        ) {
            throw IllegalArgumentException("Not a valid RIFF/WAVE file")
        }

        var audioFormat = 1
        var channels = 2
        var sampleRate = 48000
        var bitsPerSample = 16
        var metadata = AudioMetadata()
        var foundDataSize = 0L

        val chunkHdr = ByteArray(8)
        while (true) {
            val r = input.read()
            if (r < 0) break
            chunkHdr[0] = r.toByte()
            readExact(input, chunkHdr, 1, 7)
            val id = String(chunkHdr, 0, 4, StandardCharsets.US_ASCII)
            val size = readLeInt(chunkHdr, 4).toLong() and 0xFFFFFFFFL

            when (id) {
                "fmt " -> {
                    val fmt = ByteArray(size.toInt().coerceAtLeast(16))
                    readExact(input, fmt, 0, size.toInt())
                    audioFormat = readLeShort(fmt, 0)
                    channels = readLeShort(fmt, 2).coerceIn(1, 8)
                    sampleRate = readLeInt(fmt, 4).coerceAtLeast(8000)
                    bitsPerSample = readLeShort(fmt, 14)
                    if (audioFormat == 0xFFFE && fmt.size >= 40) {
                        audioFormat = readLeShort(fmt, 24)
                    }
                    if (size % 2L == 1L) skipExact(input, 1)
                }
                "LIST" -> {
                    if (size <= 1_048_576L) {
                        val payload = ByteArray(size.toInt())
                        readExact(input, payload)
                        if (payload.size >= 4 && String(payload, 0, 4, StandardCharsets.US_ASCII) == "INFO") {
                            metadata = parseListInfo(payload)
                        }
                    } else {
                        skipExact(input, size)
                    }
                    if (size % 2L == 1L) skipExact(input, 1)
                }
                "data" -> {
                    foundDataSize = size
                    convertWavDataToPcm16(
                        input = input,
                        dataSize = size,
                        audioFormat = audioFormat,
                        bitsPerSample = bitsPerSample,
                        outPcmFile = outPcmFile,
                        onProgress = onProgress,
                    )
                    if (size % 2L == 1L) skipExact(input, 1)
                }
                else -> {
                    skipExact(input, size + (size % 2L))
                }
            }
        }

        if (foundDataSize <= 0L && !outPcmFile.exists()) {
            outPcmFile.createNewFile()
        }

        return WavHeader(audioFormat, channels, sampleRate, bitsPerSample, foundDataSize) to metadata
    }

    fun writePcmTimelineAsWav(
        reader: PcmTimelineReader,
        bitDepth: WavBitDepth,
        metadata: AudioMetadata,
        output: OutputStream,
        onProgress: (Float) -> Unit = {},
    ): Long {
        val channels = reader.channels.coerceIn(1, 8)
        val sampleRate = reader.sampleRate.coerceAtLeast(8000)
        val totalFrames = reader.totalFrames.coerceAtLeast(0L)
        val bytesPerSample = bitDepth.bits / 8
        val dataSize = totalFrames * channels * bytesPerSample
        val infoChunk = buildListInfoChunk(metadata)
        val riffSize = 36L + dataSize + infoChunk.size

        val header = ByteArray(44)
        "RIFF".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 0)
        writeLeInt(header, 4, riffSize.toInt())
        "WAVE".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 8)
        "fmt ".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 12)
        writeLeInt(header, 16, 16)
        writeLeShort(header, 20, if (bitDepth.isFloat) 3 else 1)
        writeLeShort(header, 22, channels)
        writeLeInt(header, 24, sampleRate)
        writeLeInt(header, 28, sampleRate * channels * bytesPerSample)
        writeLeShort(header, 32, channels * bytesPerSample)
        writeLeShort(header, 34, bitDepth.bits)
        "data".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 36)
        writeLeInt(header, 40, dataSize.toInt())
        output.write(header)

        var totalBytesWritten = 44L
        val chunkFrames = 8192
        val shortBuf = ShortArray(chunkFrames * channels)
        val outBytes = ByteArray(chunkFrames * channels * bytesPerSample)

        var cursor = 0L
        while (cursor < totalFrames) {
            val toRead = min(chunkFrames.toLong(), totalFrames - cursor).toInt()
            val read = reader.readFrames(cursor, shortBuf, 0, toRead)
            if (read <= 0) break
            val sampleCount = read * channels

            when (bitDepth) {
                WavBitDepth.PCM_16 -> {
                    DiskPcmStore.encodeSamplesToBytesLe(shortBuf, sampleCount, outBytes)
                }
                WavBitDepth.PCM_24 -> {
                    for (i in 0 until sampleCount) {
                        val v24 = shortBuf[i].toInt() shl 8
                        outBytes[i * 3] = (v24 and 0xFF).toByte()
                        outBytes[i * 3 + 1] = ((v24 ushr 8) and 0xFF).toByte()
                        outBytes[i * 3 + 2] = ((v24 ushr 16) and 0xFF).toByte()
                    }
                }
                WavBitDepth.FLOAT_32 -> {
                    for (i in 0 until sampleCount) {
                        val fBits = java.lang.Float.floatToRawIntBits(shortBuf[i] / 32768f)
                        writeLeInt(outBytes, i * 4, fBits)
                    }
                }
            }
            val writeLen = sampleCount * bytesPerSample
            output.write(outBytes, 0, writeLen)
            totalBytesWritten += writeLen
            cursor += read
            onProgress((cursor.toFloat() / totalFrames.coerceAtLeast(1L)).coerceIn(0f, 1f))
        }

        if (infoChunk.isNotEmpty()) {
            output.write(infoChunk)
            totalBytesWritten += infoChunk.size
        }
        output.flush()
        onProgress(1f)
        return totalBytesWritten
    }

    private fun convertWavDataToPcm16(
        input: InputStream,
        dataSize: Long,
        audioFormat: Int,
        bitsPerSample: Int,
        outPcmFile: File,
        onProgress: (Long) -> Unit,
    ) {
        val bytesPerSample = (bitsPerSample / 8).coerceIn(1, 4)
        val rawBuf = ByteArray(16384 - (16384 % bytesPerSample))
        val outBuf = ByteArray(16384 * 2)
        var remaining = dataSize
        var totalSamplesWritten = 0L

        BufferedOutputStream(FileOutputStream(outPcmFile)).use { out ->
            while (remaining > 0L) {
                val toRead = min(rawBuf.size.toLong(), remaining).toInt()
                val read = input.read(rawBuf, 0, toRead)
                if (read <= 0) break
                val usable = read - (read % bytesPerSample)
                val samples = usable / bytesPerSample

                for (i in 0 until samples) {
                    val s16: Short = when {
                        audioFormat == 3 && bitsPerSample == 32 -> {
                            val bits = readLeInt(rawBuf, i * 4)
                            val f = java.lang.Float.intBitsToFloat(bits).coerceIn(-1f, 0.999969f)
                            (f * 32767f).toInt().toShort()
                        }
                        bitsPerSample == 8 -> {
                            val u = rawBuf[i].toInt() and 0xFF
                            ((u - 128) shl 8).toShort()
                        }
                        bitsPerSample == 16 -> {
                            val lo = rawBuf[i * 2].toInt() and 0xFF
                            val hi = rawBuf[i * 2 + 1].toInt()
                            ((hi shl 8) or lo).toShort()
                        }
                        bitsPerSample == 24 -> {
                            val mid = rawBuf[i * 3 + 1].toInt() and 0xFF
                            val hi = rawBuf[i * 3 + 2].toInt()
                            ((hi shl 8) or mid).toShort()
                        }
                        bitsPerSample == 32 -> {
                            val mid = rawBuf[i * 4 + 2].toInt() and 0xFF
                            val hi = rawBuf[i * 4 + 3].toInt()
                            ((hi shl 8) or mid).toShort()
                        }
                        else -> 0
                    }
                    val v = s16.toInt()
                    outBuf[i * 2] = (v and 0xFF).toByte()
                    outBuf[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
                }
                out.write(outBuf, 0, samples * 2)
                remaining -= read
                totalSamplesWritten += samples
                onProgress(totalSamplesWritten)
            }
        }
    }

    private fun parseListInfo(payload: ByteArray): AudioMetadata {
        var pos = 4
        var title = ""
        var artist = ""
        var album = ""
        var year = ""
        var genre = ""
        var comment = ""
        while (pos + 8 <= payload.size) {
            val tag = String(payload, pos, 4, StandardCharsets.US_ASCII)
            val len = readLeInt(payload, pos + 4).coerceAtLeast(0)
            pos += 8
            if (pos + len > payload.size) break
            val value = String(payload, pos, len, StandardCharsets.UTF_8).trimEnd('\u0000')
            when (tag) {
                "INAM" -> title = value
                "IART" -> artist = value
                "IPRD" -> album = value
                "ICRD" -> year = value
                "IGNR" -> genre = value
                "ICMT" -> comment = value
            }
            pos += len + (len and 1)
        }
        return AudioMetadata(title = title, artist = artist, album = album, year = year, genre = genre, comment = comment)
    }

    private fun buildListInfoChunk(metadata: AudioMetadata): ByteArray {
        val pairs = listOfNotNull(
            metadata.title.takeIf { it.isNotBlank() }?.let { "INAM" to it },
            metadata.artist.takeIf { it.isNotBlank() }?.let { "IART" to it },
            metadata.album.takeIf { it.isNotBlank() }?.let { "IPRD" to it },
            metadata.year.takeIf { it.isNotBlank() }?.let { "ICRD" to it },
            metadata.genre.takeIf { it.isNotBlank() }?.let { "IGNR" to it },
            metadata.comment.takeIf { it.isNotBlank() }?.let { "ICMT" to it },
        )
        if (pairs.isEmpty()) return ByteArray(0)

        val body = java.io.ByteArrayOutputStream()
        body.write("INFO".toByteArray(StandardCharsets.US_ASCII))
        for ((tag, value) in pairs) {
            val bytes = (value + "\u0000").toByteArray(StandardCharsets.UTF_8)
            body.write(tag.toByteArray(StandardCharsets.US_ASCII))
            val lenBuf = ByteArray(4).also { writeLeInt(it, 0, bytes.size) }
            body.write(lenBuf)
            body.write(bytes)
            if (bytes.size % 2 == 1) body.write(0)
        }
        val bodyBytes = body.toByteArray()
        val full = ByteArray(8 + bodyBytes.size)
        "LIST".toByteArray(StandardCharsets.US_ASCII).copyInto(full, 0)
        writeLeInt(full, 4, bodyBytes.size)
        bodyBytes.copyInto(full, 8)
        return full
    }

    private fun readExact(input: InputStream, buf: ByteArray, off: Int = 0, len: Int = buf.size) {
        var total = 0
        while (total < len) {
            val r = input.read(buf, off + total, len - total)
            if (r < 0) throw EOFException("Unexpected EOF in WAV stream")
            total += r
        }
    }

    private fun skipExact(input: InputStream, count: Long) {
        var rem = count
        while (rem > 0) {
            val s = input.skip(rem)
            if (s > 0) {
                rem -= s
            } else {
                if (input.read() < 0) break
                rem--
            }
        }
    }
}
