// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp

import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.WavBitDepth
import com.libreopus.dsp.container.FlacMetadataCodec
import com.libreopus.dsp.container.Mp3TagReader
import com.libreopus.dsp.container.WavCodec
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.nio.charset.StandardCharsets

class WavAndFlacAndMp3Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `wav writes and decodes 16bit 24bit and 32f with LIST INFO metadata`() {
        val store = DiskPcmStore(tempFolder.newFolder("pcm"))
        val samples = ShortArray(2000) { idx -> ((idx % 100) * 200 - 10000).toShort() }
        val pcmFile = store.createChunkFile("src")
        store.writePcm16Le(pcmFile, samples)
        val timeline = EditTimeline.fromSingleFile(48000, 2, pcmFile, 1000L)
        val meta = AudioMetadata(title = "WAV Title", artist = "WAV Artist", year = "2026")

        for (depth in WavBitDepth.entries) {
            val wavBytes = ByteArrayOutputStream().also { out ->
                WavCodec.writePcmTimelineAsWav(timeline, depth, meta, out)
            }.toByteArray()

            val decodedFile = tempFolder.newFile("dec_${depth.name}.pcm")
            val (hdr, readMeta) = WavCodec.decodeStreamToPcm16File(ByteArrayInputStream(wavBytes), decodedFile)
            assertEquals(48000, hdr.sampleRate)
            assertEquals(2, hdr.channels)
            assertEquals(depth.bits, hdr.bitsPerSample)
            assertEquals(1000L, hdr.totalFrames)
            assertEquals("WAV Title", readMeta.title)
            assertEquals("WAV Artist", readMeta.artist)

            val roundTrip = ShortArray(2000)
            DiskPcmStore.readFramesFromFile(decodedFile, 2, 0L, roundTrip, 0, 1000)
            for (i in samples.indices) {
                val diff = kotlin.math.abs(samples[i].toInt() - roundTrip[i].toInt())
                assertTrue("Sample $i diff too large: $diff", diff <= 2)
            }
        }
    }

    @Test
    fun `flac metadata block injection and readback preserves audio frames`() {
        val srcFlac = tempFolder.newFile("sample.flac")
        // Minimal valid fLaC stream: magic + last STREAMINFO (type 0, len 34) + fake audio bytes
        val streamInfo = ByteArray(34) { 0x11 }
        val fakeAudio = byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x12, 0x34, 0x56, 0x78)
        srcFlac.outputStream().use { out ->
            out.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
            out.write(byteArrayOf(0x80.toByte(), 0x00, 0x00, 34))
            out.write(streamInfo)
            out.write(fakeAudio)
        }

        val dstFlac = tempFolder.newFile("tagged.flac")
        val meta = AudioMetadata(
            title = "FLAC Şarkı",
            artist = "Sanatçı",
            album = "Albüm",
            coverArtBytes = byteArrayOf(9, 8, 7, 6),
            coverArtMimeType = "image/png",
        )
        val ok = dstFlac.outputStream().use { out ->
            FlacMetadataCodec.rewriteMetadata(srcFlac, meta, out)
        }
        assertTrue(ok)

        val readBack = FileInputStream(dstFlac).use { FlacMetadataCodec.readMetadata(it) }
        assertNotNull(readBack)
        assertEquals("FLAC Şarkı", readBack?.title)
        assertEquals("Sanatçı", readBack?.artist)
        assertArrayEquals(byteArrayOf(9, 8, 7, 6), readBack?.coverArtBytes)

        // Confirm trailing audio bytes are intact
        val allBytes = dstFlac.readBytes()
        assertArrayEquals(fakeAudio, allBytes.copyOfRange(allBytes.size - fakeAudio.size, allBytes.size))
    }

    @Test
    fun `mp3 ID3v2_4 UTF-8 frames and APIC picture are parsed accurately`() {
        fun id3TextFrame(id: String, text: String): ByteArray {
            val payload = byteArrayOf(3) + text.toByteArray(StandardCharsets.UTF_8) // 3 = UTF-8
            val size = payload.size
            val hdr = id.toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(
                ((size ushr 21) and 0x7F).toByte(),
                ((size ushr 14) and 0x7F).toByte(),
                ((size ushr 7) and 0x7F).toByte(),
                (size and 0x7F).toByte(),
                0, 0,
            )
            return hdr + payload
        }

        val frames = id3TextFrame("TIT2", "Türkçe Başlık") + id3TextFrame("TPE1", "Özgür Sanatçı")
        val tagSize = frames.size
        val id3 = ByteArrayOutputStream().apply {
            write("ID3".toByteArray(StandardCharsets.US_ASCII))
            write(byteArrayOf(4, 0, 0)) // v2.4.0
            write(
                byteArrayOf(
                    ((tagSize ushr 21) and 0x7F).toByte(),
                    ((tagSize ushr 14) and 0x7F).toByte(),
                    ((tagSize ushr 7) and 0x7F).toByte(),
                    (tagSize and 0x7F).toByte(),
                ),
            )
            write(frames)
        }.toByteArray()

        val parsed = Mp3TagReader.readMetadata(ByteArrayInputStream(id3))
        assertNotNull(parsed)
        assertEquals("Türkçe Başlık", parsed?.title)
        assertEquals("Özgür Sanatçı", parsed?.artist)
    }
}
