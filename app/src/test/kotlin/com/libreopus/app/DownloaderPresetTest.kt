// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import com.libreopus.app.downloader.AudioFormatPreference
import com.libreopus.app.downloader.DownloadExecutionOptions
import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.PresetCommandBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderPresetTest {

    @Test
    fun `preset builder emits expected flags for all 4 presets`() {
        val audioArgs = PresetCommandBuilder.buildArguments(DownloadPreset.BEST_AUDIO, "", "/tmp/%(title)s.%(ext)s")
        assertTrue(audioArgs.any { it.first == "-f" && it.second!!.contains("bestaudio") })
        assertTrue(audioArgs.any { it.first == "--no-playlist" })
        assertTrue(audioArgs.any { it.first == "--write-info-json" })
        assertTrue(audioArgs.any { it.first == "--newline" })

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
    fun `execution plan injects cookies, ipv4, proxy, and audio format preferences`() {
        val opts = DownloadExecutionOptions(
            audioPreference = AudioFormatPreference.M4A_AAC,
            cookieFilePath = "/data/user/0/com.libreopus.app/files/ytdlp/cookies.txt",
            forceIpv4 = true,
            geoBypass = true,
            proxyUrl = "socks5://127.0.0.1:9050",
            userAgent = "Mozilla/5.0",
        )
        val plan = PresetCommandBuilder.buildExecutionPlan(
            preset = DownloadPreset.BEST_AUDIO,
            customArgs = "",
            outputTemplate = "/tmp/out.%(ext)s",
            execOptions = opts,
        )
        assertEquals("bestaudio[ext=m4a]/bestaudio[acodec*=mp4a]/bestaudio", plan.options.first { it.first == "-f" }.second)
        assertEquals("/data/user/0/com.libreopus.app/files/ytdlp/cookies.txt", plan.options.first { it.first == "--cookies" }.second)
        assertTrue(plan.options.any { it.first == "--force-ipv4" })
        assertTrue(plan.options.any { it.first == "--geo-bypass" })
        assertEquals("socks5://127.0.0.1:9050", plan.options.first { it.first == "--proxy" }.second)
        assertEquals("Mozilla/5.0", plan.options.first { it.first == "--user-agent" }.second)
    }

    @Test
    fun `custom script keeps explicit cookies and output overrides without duplicating`() {
        val opts = DownloadExecutionOptions(
            cookieFilePath = "/internal/cookies.txt",
        )
        val plan = PresetCommandBuilder.buildExecutionPlan(
            preset = DownloadPreset.CUSTOM_SCRIPT,
            customArgs = "--cookies /custom/c.txt -o /custom/%(title)s.%(ext)s --playlist-items 1-3",
            outputTemplate = "/tmp/default.%(ext)s",
            execOptions = opts,
        )
        assertFalse(plan.options.any { it.first == "--cookies" })
        assertFalse(plan.options.any { it.first == "-o" })
        assertEquals(
            listOf("--cookies", "/custom/c.txt", "-o", "/custom/%(title)s.%(ext)s", "--playlist-items", "1-3"),
            plan.rawCommands,
        )
    }

    @Test
    fun `tokenizeArgs respects quoted arguments`() {
        val tokens = PresetCommandBuilder.tokenizeArgs("--format 'bestaudio' -o \"my file.opus\" --no-mtime")
        assertEquals(listOf("--format", "bestaudio", "-o", "my file.opus", "--no-mtime"), tokens)
    }
}
