// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp

import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.PcmTimelineReader
import com.libreopus.dsp.container.Mp4MetadataWriter
import com.libreopus.dsp.waveform.WaveformPeakGenerator
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Mp4AndStreamingWaveformTest {

    @Test
    fun `streaming waveform builder matches classic generate across byte and short chunks`() {
        val sampleRate = 48000
        val channels = 2
        val totalFrames = 5000
        val pcmShorts = ShortArray(totalFrames * channels)
        for (f in 0 until totalFrames) {
            val l = (sin(f * 0.03) * 24000.0).toInt().toShort()
            val r = (sin(f * 0.07) * 18000.0).toInt().toShort()
            pcmShorts[f * 2] = l
            pcmShorts[f * 2 + 1] = r
        }

        val reader = object : PcmTimelineReader {
            override val sampleRate: Int = sampleRate
            override val channels: Int = channels
            override val totalFrames: Long = totalFrames.toLong()
            override fun readFrames(startFrame: Long, destInterleaved16: ShortArray, frameOffset: Int, frameCount: Int): Int {
                val start = startFrame.toInt()
                val n = minOf(frameCount, totalFrames - start)
                if (n <= 0) return 0
                System.arraycopy(pcmShorts, start * channels, destInterleaved16, frameOffset * channels, n * channels)
                return n
            }
        }
        val expected = WaveformPeakGenerator.generate(reader)

        // Feed little-endian PCM bytes in uneven chunk sizes (testing leftover carry across boundaries)
        val leBytes = ByteArray(pcmShorts.size * 2)
        for (i in pcmShorts.indices) {
            val v = pcmShorts[i].toInt()
            leBytes[i * 2] = (v and 0xFF).toByte()
            leBytes[i * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
        }

        val builder = WaveformPeakGenerator.StreamingBuilder(sampleRate, channels)
        var pos = 0
        val stepPattern = intArrayOf(13, 37, 511, 4093)
        var pIdx = 0
        while (pos < leBytes.size) {
            val step = minOf(stepPattern[pIdx++ % stepPattern.size], leBytes.size - pos)
            val slice = leBytes.copyOfRange(pos, pos + step)
            builder.pushPcm16LeBytes(slice, slice.size)
            pos += step
        }
        val streamed = builder.build()

        assertEquals(expected.totalFrames, streamed.totalFrames)
        assertEquals(expected.sampleRate, streamed.sampleRate)
        assertEquals(expected.levels.size, streamed.levels.size)
        for (lvlIdx in expected.levels.indices) {
            val eLvl = expected.levels[lvlIdx]
            val sLvl = streamed.levels[lvlIdx]
            assertEquals(eLvl.framesPerBin, sLvl.framesPerBin)
            assertEquals(eLvl.binCount, sLvl.binCount)
            for (b in 0 until eLvl.binCount) {
                assertTrue("min mismatch level $lvlIdx bin $b", abs(eLvl.minPeaks[b] - sLvl.minPeaks[b]) < 1e-5f)
                assertTrue("max mismatch level $lvlIdx bin $b", abs(eLvl.maxPeaks[b] - sLvl.maxPeaks[b]) < 1e-5f)
                assertTrue("rms mismatch level $lvlIdx bin $b", abs(eLvl.rms[b] - sLvl.rms[b]) < 1e-5f)
            }
        }
    }

    @Test
    fun `mp4 metadata writer injects tags and cover art before mdat and shifts stco offsets`() {
        val dir = Files.createTempDirectory("mp4_test_moov_first").toFile()
        try {
            val ftyp = box("ftyp", "M4A \u0000\u0000\u0002\u0000M4A mp42isom".toByteArray(StandardCharsets.ISO_8859_1))
            val fakeMdatPayload = ByteArray(1024) { (it and 0xFF).toByte() }
            val mdat = box("mdat", fakeMdatPayload)

            val mvhd = box("mvhd", ByteArray(24))
            val placeholderStco = fullStcoBox(longArrayOf(0L, 0L))
            val placeholderMoov = box("moov", mvhd + box("trak", box("mdia", box("minf", box("stbl", placeholderStco)))))
            val originalMdatPayloadOffset = (ftyp.size + placeholderMoov.size + 8).toLong()
            val realStco = fullStcoBox(longArrayOf(originalMdatPayloadOffset, originalMdatPayloadOffset + 512L))
            val realMoov = box("moov", mvhd + box("trak", box("mdia", box("minf", box("stbl", realStco)))))

            val srcFile = File(dir, "in_moov_first.m4a")
            srcFile.writeBytes(ftyp + realMoov + mdat)

            val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)
            val meta = AudioMetadata(
                title = "Night Drive",
                artist = "LibreSynth",
                album = "Midnight EP",
                year = "2026",
                trackNumber = "4",
                genre = "Electronic",
                comment = "https://youtube.com/watch?v=test",
                coverArtBytes = fakeJpeg,
                coverArtMimeType = "image/jpeg",
            )

            val outFile = File(dir, "out_moov_first.m4a")
            outFile.outputStream().use { out ->
                assertTrue(Mp4MetadataWriter.writeMetadata(srcFile, meta, out))
            }

            val parsed = Mp4MetadataWriter.readMetadata(outFile)
            assertNotNull(parsed)
            assertEquals("Night Drive", parsed!!.title)
            assertEquals("LibreSynth", parsed.artist)
            assertEquals("Midnight EP", parsed.album)
            assertEquals("2026", parsed.year)
            assertEquals("4", parsed.trackNumber)
            assertEquals("Electronic", parsed.genre)
            assertEquals("https://youtube.com/watch?v=test", parsed.comment)
            assertEquals("image/jpeg", parsed.coverArtMimeType)
            assertArrayEquals(fakeJpeg, parsed.coverArtBytes)

            val newBytes = outFile.readBytes()
            val newMoovSize = readInt32Be(newBytes, ftyp.size)
            val newMdatOffset = ftyp.size + newMoovSize
            assertEquals("mdat", String(newBytes, newMdatOffset + 4, 4, StandardCharsets.US_ASCII))
            val shiftedOffsets = findStcoOffsets(newBytes, ftyp.size, newMoovSize)
            val expectedShift = newMoovSize - realMoov.size
            assertEquals(listOf(originalMdatPayloadOffset + expectedShift, originalMdatPayloadOffset + 512L + expectedShift), shiftedOffsets)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `mp4 metadata writer leaves preceding mdat offsets unchanged when moov is after mdat`() {
        val dir = Files.createTempDirectory("mp4_test_mdat_first").toFile()
        try {
            val ftyp = box("ftyp", "M4A \u0000\u0000\u0002\u0000M4A mp42isom".toByteArray(StandardCharsets.ISO_8859_1))
            val mdat = box("mdat", ByteArray(2048) { 0x55 })
            val fixedMdatOffset = (ftyp.size + 8).toLong()
            val mvhd = box("mvhd", ByteArray(24))
            val stco = fullStcoBox(longArrayOf(fixedMdatOffset, fixedMdatOffset + 1024L))
            val moov = box("moov", mvhd + box("trak", box("mdia", box("minf", box("stbl", stco)))))

            val srcFile = File(dir, "in_mdat_first.m4a")
            srcFile.writeBytes(ftyp + mdat + moov)

            val fakePng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A)
            val outFile = File(dir, "out_mdat_first.m4a")
            val meta = AudioMetadata(
                title = "Dawn Track",
                artist = "Opus Lab",
                coverArtBytes = fakePng,
                coverArtMimeType = "image/png",
            )
            outFile.outputStream().use { out ->
                assertTrue(Mp4MetadataWriter.writeMetadata(srcFile, meta, out))
            }

            val parsed = Mp4MetadataWriter.readMetadata(outFile)
            assertNotNull(parsed)
            assertEquals("Dawn Track", parsed!!.title)
            assertEquals("Opus Lab", parsed.artist)
            assertEquals("image/png", parsed.coverArtMimeType)
            assertArrayEquals(fakePng, parsed.coverArtBytes)

            val outBytes = outFile.readBytes()
            val moovOff = ftyp.size + mdat.size
            val moovSz = readInt32Be(outBytes, moovOff)
            assertEquals(listOf(fixedMdatOffset, fixedMdatOffset + 1024L), findStcoOffsets(outBytes, moovOff, moovSz))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeInt32Be(out, 0, out.size)
        type.toByteArray(StandardCharsets.ISO_8859_1).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun fullStcoBox(offsets: LongArray): ByteArray {
        val out = ByteArrayOutputStream()
        val hdr = ByteArray(8)
        writeInt32Be(hdr, 4, offsets.size)
        out.write(hdr)
        val entry = ByteArray(4)
        for (o in offsets) {
            writeInt32Be(entry, 0, o.toInt())
            out.write(entry)
        }
        return box("stco", out.toByteArray())
    }

    private fun findStcoOffsets(buf: ByteArray, start: Int, size: Int): List<Long> {
        val end = start + size
        for (i in start..(end - 16)) {
            if (buf[i] == 's'.code.toByte() &&
                buf[i + 1] == 't'.code.toByte() &&
                buf[i + 2] == 'c'.code.toByte() &&
                buf[i + 3] == 'o'.code.toByte()
            ) {
                val count = readInt32Be(buf, i + 8)
                val out = mutableListOf<Long>()
                for (j in 0 until count) {
                    out += readInt32Be(buf, i + 12 + j * 4).toLong() and 0xFFFFFFFFL
                }
                return out
            }
        }
        return emptyList()
    }

    private fun readInt32Be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun writeInt32Be(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }
}
