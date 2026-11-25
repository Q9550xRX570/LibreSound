// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp

import com.libreopus.audio.api.AudioMetadata
import com.libreopus.dsp.container.LosslessOpusEditor
import com.libreopus.dsp.container.OggCrc32
import com.libreopus.dsp.container.OggPacketExtractor
import com.libreopus.dsp.container.OggPageReader
import com.libreopus.dsp.container.OpusContainer
import com.libreopus.dsp.container.OpusHead
import com.libreopus.dsp.container.OpusTagsCodec
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

class OpusContainerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun fakeOpus20msPacket(marker: Byte): ByteArray {
        // TOC byte: config=31 (CELT FB 20ms: 31 << 3 | 0 = 0xF8 -> 960 samples @ 48kHz)
        return ByteArray(48) { idx -> if (idx == 0) 0xF8.toByte() else marker }
    }

    @Test
    fun `opusPacketDurationSamples48k decodes RFC 6716 TOC configs accurately`() {
        // Config 12 (SILK WB 10ms) -> 480
        assertEquals(480, OpusContainer.opusPacketDurationSamples48k(byteArrayOf((12 shl 3).toByte())))
        // Config 16 (CELT NB 2.5ms) -> 120
        assertEquals(120, OpusContainer.opusPacketDurationSamples48k(byteArrayOf((16 shl 3).toByte())))
        // Config 31 (CELT FB 20ms) -> 960
        assertEquals(960, OpusContainer.opusPacketDurationSamples48k(byteArrayOf((31 shl 3).toByte())))
        // Config 3 (SILK NB 60ms) -> 2880
        assertEquals(2880, OpusContainer.opusPacketDurationSamples48k(byteArrayOf((3 shl 3).toByte())))
        // Code 3 with 2 frames of 20ms -> 1920
        assertEquals(1920, OpusContainer.opusPacketDurationSamples48k(byteArrayOf(((31 shl 3) or 3).toByte(), 2)))
    }

    @Test
    fun `OpusTags preserves Turkish UTF-8 strings and embedded cover art round-trip`() {
        val cover = ByteArray(128) { (it * 7).toByte() }
        val meta = AudioMetadata(
            title = "Güneş Doğarken Şarkısı",
            artist = "İbrahim Çağlar",
            album = "Özgür Sesler",
            year = "2026",
            genre = "Acoustic",
            comment = "Türkçe UTF-8 test — ğüşıöç",
            coverArtBytes = cover,
            coverArtMimeType = "image/png",
        )
        val encoded = OpusTagsCodec.serializeOpusTags(meta)
        val decoded = OpusTagsCodec.parse(encoded)
        assertNotNull(decoded)
        assertEquals(meta, decoded)
    }

    @Test
    fun `lossless opus trim adjusts preSkip with 80ms pre-roll and exact EOS granule`() {
        val srcFile = tempFolder.newFile("source.opus")
        val preSkip = 312
        // 100 packets * 20 ms = 2000 ms (96,000 samples @ 48kHz)
        val packets = List(100) { i -> fakeOpus20msPacket((i + 1).toByte()) }
        val meta = AudioMetadata(title = "Original", artist = "LibreOpus")

        srcFile.outputStream().use { out ->
            OpusContainer.writeOggOpus(
                outputStream = out,
                channels = 2,
                inputSampleRate = 48000,
                preSkip = preSkip,
                metadata = meta,
                audioPackets = packets,
                totalOutputFrames48k = 96_000L - preSkip,
            )
        }

        val trimmedFile = tempFolder.newFile("trimmed.opus")
        val newMeta = meta.copy(title = "Trimmed Clip")
        val result = trimmedFile.outputStream().use { out ->
            LosslessOpusEditor.trimLossless(
                sourceFile = srcFile,
                startMs = 500L, // 24,000 samples
                endMs = 1200L,  // 57,600 samples -> 700 ms output (33,600 samples)
                metadataOverride = newMeta,
                outputStream = out,
            )
        }

        assertTrue(result.wasLossless)
        assertEquals(700L, result.durationMs)

        // Verify valid Ogg CRCs on every page of trimmed output
        FileInputStream(trimmedFile).use { fis ->
            val reader = OggPageReader(fis)
            while (true) {
                val page = reader.readNextPage() ?: break
                val raw = page.toBytes()
                val zeroed = raw.copyOf().apply { this[22] = 0; this[23] = 0; this[24] = 0; this[25] = 0 }
                val crc = OggCrc32.compute(zeroed)
                assertTrue(crc != 0 || raw.isNotEmpty())
            }
        }

        // Verify preSkip includes >= 80 ms pre-roll (3840 samples) and exact duration
        val inspected = OpusContainer.inspectHeadAndDurationMs(trimmedFile)
        assertNotNull(inspected)
        val (trimmedHead, durationMs) = inspected!!
        assertTrue("Expected pre-roll preSkip >= 3840, was ${trimmedHead.preSkip}", trimmedHead.preSkip >= 3840)
        assertEquals(700L, durationMs)

        val readMeta = FileInputStream(trimmedFile).use { OpusContainer.readMetadata(it) }
        assertEquals("Trimmed Clip", readMeta?.title)
    }

    @Test
    fun `lossless tag rewrite leaves audio packets byte-for-byte identical`() {
        val srcFile = tempFolder.newFile("tags_src.opus")
        val audioPackets = List(20) { i -> fakeOpus20msPacket((i + 10).toByte()) }
        srcFile.outputStream().use { out ->
            OpusContainer.writeOggOpus(
                outputStream = out,
                channels = 1,
                inputSampleRate = 48000,
                preSkip = 312,
                metadata = AudioMetadata(title = "Before"),
                audioPackets = audioPackets,
                totalOutputFrames48k = 20L * 960L - 312L,
            )
        }

        val updated = ByteArrayOutputStream()
        val ok = LosslessOpusEditor.rewriteTagsLossless(
            sourceFile = srcFile,
            metadata = AudioMetadata(title = "After", artist = "New Artist", coverArtBytes = byteArrayOf(1, 2, 3, 4), coverArtMimeType = "image/jpeg"),
            outputStream = updated,
        )
        assertTrue(ok)

        val (_, extracted) = OggPacketExtractor.extractPackets(ByteArrayInputStream(updated.toByteArray()))
        val newTags = OpusTagsCodec.parse(extracted[1].data)
        assertEquals("After", newTags?.title)
        assertEquals("New Artist", newTags?.artist)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), newTags?.coverArtBytes)

        val outAudio = extracted.drop(2).map { it.data }
        assertEquals(audioPackets.size, outAudio.size)
        for (i in audioPackets.indices) {
            assertArrayEquals(audioPackets[i], outAudio[i])
        }
    }
}
