// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import com.libreopus.app.audio.AudioRecorderHelper
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.app.data.StudioHelpers
import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StudioFeaturesTest {

    @Test
    fun `moveListItem reorders elements safely within bounds`() {
        val list = listOf("A", "B", "C", "D")
        assertEquals(listOf("B", "A", "C", "D"), StudioHelpers.moveListItem(list, 1, -1))
        assertEquals(listOf("A", "C", "B", "D"), StudioHelpers.moveListItem(list, 1, 1))
        assertEquals(list, StudioHelpers.moveListItem(list, 0, -1))
        assertEquals(list, StudioHelpers.moveListItem(list, 3, 1))
        assertEquals(list, StudioHelpers.moveListItem(list, 9, -1))
    }

    @Test
    fun `buildSequentialPartName zero-pads parts accurately`() {
        assertEquals("lecture_01.m4a", StudioHelpers.buildSequentialPartName("lecture", 0, 12, "m4a"))
        assertEquals("lecture_12.m4a", StudioHelpers.buildSequentialPartName("lecture", 11, 12, ".m4a"))
        assertEquals("mix_raw_1_005.opus", StudioHelpers.buildSequentialPartName("mix/raw:1", 4, 120, "opus"))
        assertEquals("libreopus_part_01.opus", StudioHelpers.buildSequentialPartName("   ", 0, 5, ""))
    }

    @Test
    fun `buildMergedOutputFileName handles custom names and defaults`() {
        assertEquals("podcast_mix.opus", StudioHelpers.buildMergedOutputFileName("podcast_mix", "track1.m4a", "opus"))
        assertEquals("podcast_mix.m4a", StudioHelpers.buildMergedOutputFileName("podcast_mix.m4a", "track1.opus", "m4a"))
        assertEquals("track1_merged.flac", StudioHelpers.buildMergedOutputFileName("", "track1.wav", "flac"))
        assertEquals("libreopus_merged.wav", StudioHelpers.buildMergedOutputFileName("...", null, "wav"))
    }

    @Test
    fun `clampCrossfadeMs constrains range between 0 and 5000 ms`() {
        assertEquals(0L, StudioHelpers.clampCrossfadeMs(-500L))
        assertEquals(1500L, StudioHelpers.clampCrossfadeMs(1500L))
        assertEquals(5000L, StudioHelpers.clampCrossfadeMs(9000L))
    }

    @Test
    fun `buildWavHeader16 encodes exact RIFF WAVE PCM16 header`() {
        val dataBytes = 48_000L * 2L // 1 second mono 48kHz PCM16
        val hdr = AudioRecorderHelper.buildWavHeader16(channels = 1, sampleRate = 48_000, dataSizeBytes = dataBytes)
        assertEquals(44, hdr.size)
        assertEquals("RIFF", String(hdr, 0, 4, StandardCharsets.US_ASCII))
        assertEquals("WAVE", String(hdr, 8, 4, StandardCharsets.US_ASCII))
        assertEquals("fmt ", String(hdr, 12, 4, StandardCharsets.US_ASCII))
        assertEquals("data", String(hdr, 36, 4, StandardCharsets.US_ASCII))

        fun leShort(off: Int): Int = (hdr[off].toInt() and 0xFF) or ((hdr[off + 1].toInt() and 0xFF) shl 8)
        fun leInt(off: Int): Int = (hdr[off].toInt() and 0xFF) or
            ((hdr[off + 1].toInt() and 0xFF) shl 8) or
            ((hdr[off + 2].toInt() and 0xFF) shl 16) or
            ((hdr[off + 3].toInt() and 0xFF) shl 24)

        assertEquals(36 + dataBytes.toInt(), leInt(4))
        assertEquals(16, leInt(16))
        assertEquals(1, leShort(20)) // PCM
        assertEquals(1, leShort(22)) // mono
        assertEquals(48_000, leInt(24))
        assertEquals(96_000, leInt(28)) // byteRate
        assertEquals(2, leShort(32)) // blockAlign
        assertEquals(16, leShort(34)) // bitsPerSample
        assertEquals(dataBytes.toInt(), leInt(40))
    }

    @Test
    fun `computeRmsNormalized maps silence to zero and full scale to one`() {
        val silence = ShortArray(256)
        assertEquals(0f, AudioRecorderHelper.computeRmsNormalized(silence, silence.size), 1e-6f)

        val maxSquare = ShortArray(256) { Short.MAX_VALUE }
        val rms = AudioRecorderHelper.computeRmsNormalized(maxSquare, maxSquare.size)
        assertTrue(rms in 0.99f..1.0f)
    }

    @Test
    fun `clearTempCache removes scratch dirs while keeping active session and unrelated cache`() {
        val tempRoot = createTempDirectory("cache_test").toFile()
        try {
            val activeSession = File(tempRoot, "sessions/active").apply { mkdirs() }
            val activeFile = File(activeSession, "pcm.raw").apply { writeBytes(ByteArray(100)) }

            val staleSession = File(tempRoot, "sessions/old").apply { mkdirs() }
            File(staleSession, "pcm.raw").apply { writeBytes(ByteArray(512)) }

            val exportsDir = File(tempRoot, "exports").apply { mkdirs() }
            File(exportsDir, "share.opus").apply { writeBytes(ByteArray(256)) }

            val batchDir = File(tempRoot, "batch_12345").apply { mkdirs() }
            File(batchDir, "tmp.pcm").apply { writeBytes(ByteArray(128)) }

            val unrelatedDir = File(tempRoot, "datastore").apply { mkdirs() }
            val prefsFile = File(unrelatedDir, "prefs.pb").apply { writeBytes(ByteArray(64)) }

            val totalBefore = MediaStorageHelper.computeTempCacheSizeBytes(tempRoot)
            assertEquals(100L + 512L + 256L + 128L + 64L, totalBefore)

            val freed = MediaStorageHelper.clearTempCache(
                cacheDir = tempRoot,
                keepPaths = setOf(activeSession.absolutePath),
            )
            assertEquals(512L + 256L + 128L, freed)
            assertTrue(activeFile.exists())
            assertTrue(prefsFile.exists())
            assertFalse(staleSession.exists())
            assertFalse(exportsDir.exists())
            assertFalse(batchDir.exists())
        } finally {
            tempRoot.deleteRecursively()
        }
    }
}
