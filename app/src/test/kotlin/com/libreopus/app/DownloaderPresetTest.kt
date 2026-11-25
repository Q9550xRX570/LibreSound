// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.PresetCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderPresetTest {

    @Test
    fun `preset builder emits expected flags for all 4 presets`() {
        val audioArgs = PresetCommandBuilder.buildArguments(DownloadPreset.BEST_AUDIO, "", "/tmp/%(title)s.%(ext)s")
        assertTrue(audioArgs.any { it.first == "-f" && it.second!!.contains("bestaudio") })
        assertTrue(audioArgs.any { it.first == "--no-playlist" })

        val imgArgs = PresetCommandBuilder.buildArguments(DownloadPreset.IMAGE_ONLY, "", "/tmp/%(title)s.%(ext)s")
        assertTrue(imgArgs.any { it.first == "--skip-download" })
        assertTrue(imgArgs.any { it.first == "--write-thumbnail" })

        val bothArgs = PresetCommandBuilder.buildArguments(DownloadPreset.AUDIO_AND_IMAGE, "", "/tmp/%(title)s.%(ext)s")
        assertTrue(bothArgs.any { it.first == "-f" })
        assertTrue(bothArgs.any { it.first == "--write-thumbnail" })

        val custom = PresetCommandBuilder.buildArguments(
            DownloadPreset.CUSTOM_SCRIPT,
            "-f \"bestaudio[ext=opus]/bestaudio\" --write-thumbnail --no-playlist",
            "/tmp/%(title)s.%(ext)s",
        )
        assertEquals("bestaudio[ext=opus]/bestaudio", custom.first { it.first == "-f" }.second)
        assertTrue(custom.any { it.first == "--write-thumbnail" })
        assertTrue(custom.any { it.first == "-o" })
    }

    @Test
    fun `tokenizeArgs respects quoted arguments`() {
        val tokens = PresetCommandBuilder.tokenizeArgs("--format 'bestaudio' -o \"my file.opus\" --no-mtime")
        assertEquals(listOf("--format", "bestaudio", "-o", "my file.opus", "--no-mtime"), tokens)
    }
}
