// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import com.libreopus.app.downloader.AudioFormatPreference
import com.libreopus.app.downloader.CommandTemplateCodec
import com.libreopus.app.downloader.CommandTemplateEntry
import com.libreopus.app.downloader.DownloadExecutionOptions
import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.FilenameTemplate
import com.libreopus.app.downloader.PresetCommandBuilder
import com.libreopus.app.downloader.YoutubeClientMode
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloaderPresetTest {

    @Test
    fun `preset builder emits expected flags for all presets including video`() {
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

        val videoArgs = PresetCommandBuilder.buildArguments(DownloadPreset.VIDEO_MUXED, "", "/tmp/%(title)s.%(ext)s")
        assertEquals(PresetCommandBuilder.VIDEO_MUXED_SELECTOR, videoArgs.first { it.first == "-f" }.second)
        assertTrue(videoArgs.any { it.first == "--write-thumbnail" })

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
    fun `execution plan applies YTDLnis format override, player client, playlist, fragments, and network flags`() {
        val opts = DownloadExecutionOptions(
            audioPreference = AudioFormatPreference.M4A_AAC,
            selectedFormatId = "251",
            cookieFilePath = "/data/user/0/com.libreopus.app/files/ytdlp/cookies.txt",
            forceIpv4 = true,
            geoBypass = true,
            allowPlaylist = true,
            playlistItems = "1-5",
            youtubeClient = YoutubeClientMode.ANDROID,
            extraExtractorArgs = "player_skip=webpage",
            concurrentFragments = 8,
            rateLimit = "2M",
            noCheckCertificates = true,
            writeSubtitles = true,
            referer = "https://example.org",
            proxyUrl = "socks5://127.0.0.1:9050",
            userAgent = "Mozilla/5.0",
        )
        val plan = PresetCommandBuilder.buildExecutionPlan(
            preset = DownloadPreset.BEST_AUDIO,
            customArgs = "",
            outputTemplate = "/tmp/out.%(ext)s",
            execOptions = opts,
        )
        assertEquals("251", plan.options.first { it.first == "-f" }.second)
        assertEquals("/data/user/0/com.libreopus.app/files/ytdlp/cookies.txt", plan.options.first { it.first == "--cookies" }.second)
        assertTrue(plan.options.any { it.first == "--yes-playlist" })
        assertEquals("1-5", plan.options.first { it.first == "--playlist-items" }.second)
        assertEquals("youtube:player_client=android;player_skip=webpage", plan.options.first { it.first == "--extractor-args" }.second)
        assertEquals("8", plan.options.first { it.first == "-N" }.second)
        assertEquals("2M", plan.options.first { it.first == "--limit-rate" }.second)
        assertTrue(plan.options.any { it.first == "--no-check-certificates" })
        assertTrue(plan.options.any { it.first == "--write-subs" })
        assertEquals("https://example.org", plan.options.first { it.first == "--referer" }.second)
        assertTrue(plan.options.any { it.first == "--force-ipv4" })
        assertEquals("socks5://127.0.0.1:9050", plan.options.first { it.first == "--proxy" }.second)
    }

    @Test
    fun `parseUrls, resolveOutputTemplate, exportAsCustomCommand, and CommandTemplateCodec behave cleanly`() {
        val urls = PresetCommandBuilder.parseUrls(
            """
            https://youtube.com/watch?v=1
            https://soundcloud.com/a/b https://youtube.com/watch?v=1
            """.trimIndent(),
        )
        assertEquals(listOf("https://youtube.com/watch?v=1", "https://soundcloud.com/a/b"), urls)

        val resolved = PresetCommandBuilder.resolveOutputTemplate(
            File("/tmp/work"),
            DownloadExecutionOptions(
                filenameTemplate = FilenameTemplate.CUSTOM,
                customOutputTemplate = "/../../%(uploader)s - %(title)s.%(ext)s",
            ),
        )
        assertFalse(resolved.contains(".."))
        assertTrue(resolved.startsWith("/tmp/work/"))

        val exported = PresetCommandBuilder.exportAsCustomCommand(
            preset = DownloadPreset.VIDEO_MUXED,
            customArgs = "",
            execOptions = DownloadExecutionOptions(youtubeClient = YoutubeClientMode.IOS, concurrentFragments = 4),
        )
        assertTrue(exported.contains("-f "))
        assertTrue(exported.contains("youtube:player_client=ios"))
        assertTrue(exported.contains("-N 4"))

        val entries = listOf(
            CommandTemplateEntry(id = "t1", name = "My \"Opus\"", args = "-f bestaudio --no-playlist"),
        )
        val encoded = CommandTemplateCodec.encode(entries)
        val decoded = CommandTemplateCodec.decode(encoded)
        assertEquals(entries, decoded)
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
