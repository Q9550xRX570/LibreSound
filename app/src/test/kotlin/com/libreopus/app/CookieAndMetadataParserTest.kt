// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import com.libreopus.app.downloader.AudioFormatPreference
import com.libreopus.app.downloader.CookieParser
import com.libreopus.app.downloader.DownloadHistoryCodec
import com.libreopus.app.downloader.DownloadHistoryEntry
import com.libreopus.app.downloader.DumpJsonPreviewParser
import com.libreopus.app.downloader.InfoJsonMetadataParser
import com.libreopus.app.downloader.YtDlpErrorClassifier
import com.libreopus.app.downloader.YtDlpErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CookieAndMetadataParserTest {

    @Test
    fun `parseAndNormalize preserves HttpOnly Netscape cookies and normalizes CRLF`() {
        val raw = "# Netscape HTTP Cookie File\r\n" +
            "#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1800000000\tSID\tabc123\r\n" +
            ".google.com\tTRUE\t/\tFALSE\t0\tPREF\t\r\n"

        val parsed = CookieParser.parseAndNormalize(raw)
        assertFalse(parsed.summary.convertedFromJson)
        assertEquals(2, parsed.summary.cookieCount)
        assertEquals(listOf("youtube.com", "google.com"), parsed.summary.domains)
        assertTrue(parsed.netscapeText.contains("#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1800000000\tSID\tabc123"))
        assertTrue(parsed.netscapeText.contains(".google.com\tTRUE\t/\tFALSE\t0\tPREF\t"))
    }

    @Test
    fun `parseAndNormalize converts Cookie-Editor JSON array to Netscape format`() {
        val json = """
            [
              {
                "domain": "youtube.com",
                "hostOnly": false,
                "httpOnly": true,
                "name": "__Secure-1PSID",
                "path": "/",
                "secure": true,
                "session": false,
                "expirationDate": 1893456000.75,
                "value": "token_xyz"
              },
              {
                "domain": ".soundcloud.com",
                "httpOnly": false,
                "name": "oauth_token",
                "path": "/",
                "secure": true,
                "session": true,
                "value": "sc_123"
              }
            ]
        """.trimIndent()

        val parsed = CookieParser.parseAndNormalize(json)
        assertTrue(parsed.summary.convertedFromJson)
        assertEquals(2, parsed.summary.cookieCount)
        assertEquals(listOf("youtube.com", "soundcloud.com"), parsed.summary.domains)
        assertTrue(parsed.netscapeText.startsWith("# Netscape HTTP Cookie File"))
        assertTrue(parsed.netscapeText.contains("#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t1893456000\t__Secure-1PSID\ttoken_xyz"))
        assertTrue(parsed.netscapeText.contains(".soundcloud.com\tTRUE\t/\tTRUE\t0\toauth_token\tsc_123"))
    }

    @Test
    fun `parseAndNormalize converts wrapped cookies object and rejects garbage`() {
        val wrapped = """{"url":"https://youtube.com","cookies":[{"domain":".youtube.com","name":"VISITOR","value":"v1","secure":true}]}"""
        val res = CookieParser.parseAndNormalize(wrapped)
        assertEquals(1, res.summary.cookieCount)
        assertTrue(res.summary.convertedFromJson)

        assertThrows(IllegalArgumentException::class.java) {
            CookieParser.parseAndNormalize("just some random text without tabs")
        }
        assertThrows(IllegalArgumentException::class.java) {
            CookieParser.parseAndNormalize("   ")
        }
    }

    @Test
    fun `infoJsonMetadataParser extracts YouTube and SoundCloud tags cleanly`() {
        val json = """
            {
              "title": "Artist - Track Name (Official Audio)",
              "track": "Track Name",
              "artists": ["Artist One", "Artist Two"],
              "album": "Midnight Studio",
              "release_year": 2025,
              "upload_date": "20250412",
              "track_number": 3,
              "genres": ["Electronic"],
              "webpage_url": "https://music.youtube.com/watch?v=dQw4w9WgXcQ"
            }
        """.trimIndent()

        val meta = InfoJsonMetadataParser.parse(json)
        assertNotNull(meta)
        assertEquals("Track Name", meta!!.title)
        assertEquals("Artist One, Artist Two", meta.artist)
        assertEquals("Midnight Studio", meta.album)
        assertEquals("2025", meta.year)
        assertEquals("3", meta.trackNumber)
        assertEquals("Electronic", meta.genre)
        assertEquals("https://music.youtube.com/watch?v=dQw4w9WgXcQ", meta.comment)
    }

    @Test
    fun `errorClassifier categorizes common yt-dlp failure modes`() {
        assertEquals(
            YtDlpErrorKind.BOT_CHECK_NEEDS_COOKIES,
            YtDlpErrorClassifier.classify("ERROR: [youtube] xyz: Sign in to confirm you're not a bot.").kind,
        )
        assertEquals(
            YtDlpErrorKind.AGE_RESTRICTED_NEEDS_COOKIES,
            YtDlpErrorClassifier.classify("ERROR: Sign in to confirm your age. This video may be inappropriate for some users.").kind,
        )
        assertEquals(
            YtDlpErrorKind.PRIVATE_OR_AUTH_NEEDS_COOKIES,
            YtDlpErrorClassifier.classify("ERROR: [youtube] xyz: Join this channel to get access to members-only content.").kind,
        )
        assertEquals(
            YtDlpErrorKind.HTTP_403_FORBIDDEN,
            YtDlpErrorClassifier.classify("ERROR: unable to download video data: HTTP Error 403: Forbidden").kind,
        )
        assertEquals(
            YtDlpErrorKind.FFMPEG_REQUIRED,
            YtDlpErrorClassifier.classify("ERROR: Postprocessing: ffprobe and ffmpeg not found. Please install or provide the path using --ffmpeg-location").kind,
        )
        assertEquals(
            YtDlpErrorKind.NETWORK_OR_TIMEOUT,
            YtDlpErrorClassifier.classify("ERROR: Unable to download webpage: <urlopen error timed out>").kind,
        )
        assertEquals(
            YtDlpErrorKind.UNSUPPORTED_URL,
            YtDlpErrorClassifier.classify("ERROR: Unsupported URL: https://example.com/foo").kind,
        )
    }

    @Test
    fun `dumpJsonPreviewParser extracts formats without storyboards and parses playlists`() {
        val singleJson = """
            {
              "title": "Sample Track",
              "uploader": "Studio Channel",
              "duration": 215.4,
              "extractor_key": "Youtube",
              "formats": [
                {"format_id":"sb0","ext":"mhtml","format_note":"storyboard","vcodec":"none","acodec":"none"},
                {"format_id":"140","ext":"m4a","acodec":"mp4a.40.2","vcodec":"none","abr":129,"asr":44100,"filesize":3500000,"format_note":"medium"},
                {"format_id":"251","ext":"webm","acodec":"opus","vcodec":"none","abr":160,"asr":48000,"filesize_approx":4100000,"format_note":"medium"},
                {"format_id":"18","ext":"mp4","acodec":"mp4a.40.2","vcodec":"avc1","height":360,"tbr":480,"filesize":12000000}
              ]
            }
        """.trimIndent()

        val track = DumpJsonPreviewParser.parse(singleJson, "https://youtube.com/watch?v=1", AudioFormatPreference.OPUS_WEBM)
        assertNotNull(track)
        assertFalse(track!!.isPlaylist)
        assertEquals("Sample Track", track.title)
        assertEquals("webm", track.audioExt)
        assertEquals(3, track.formats.size)
        assertEquals("251", track.formats.first().formatId)
        assertTrue(track.formats.first().isAudioOnly)
        assertTrue(track.formats.last().isMuxedVideo)

        val playlistJson = """
            {
              "_type": "playlist",
              "title": "Chill Mix",
              "uploader": "Curator",
              "playlist_count": 2,
              "entries": [
                {"playlist_index": 1, "title": "First", "duration": 120, "url": "https://youtube.com/watch?v=a"},
                {"playlist_index": 2, "title": "Second", "duration": 180, "url": "https://youtube.com/watch?v=b"}
              ]
            }
        """.trimIndent()

        val pl = DumpJsonPreviewParser.parse(playlistJson, "https://youtube.com/playlist?list=1")
        assertNotNull(pl)
        assertTrue(pl!!.isPlaylist)
        assertEquals(2, pl.playlistCount)
        assertEquals(300, pl.durationSec)
        assertEquals("First", pl.playlistEntries[0].title)
    }

    @Test
    fun `downloadHistoryCodec roundTrips extended cover and metadata properties and enforces 30 entry limit`() {
        val entries = (1..35).map { idx ->
            DownloadHistoryEntry(
                id = "id_$idx",
                title = "Track #$idx \"Live\" \\ Mix",
                sourceUrl = "https://example.com/watch?v=$idx",
                savedPath = "Music/LibreOpus/track_$idx.opus",
                contentUriString = "content://media/external/audio/media/$idx",
                cacheFilePath = "/tmp/cache/track_$idx.opus",
                mimeType = "audio/opus",
                isVideo = idx % 2 == 0,
                sizeBytes = 1024L * idx,
                timestampMs = 1_700_000_000_000L + idx,
                coverFilePath = "/data/user/0/app/files/ytdlp_covers/c_$idx.jpg",
                artist = "Artist $idx",
                album = "Album $idx",
                year = "2026",
                durationSec = 180 + idx,
                formatLabel = "OPUS • 160 kbps",
            )
        }
        val encoded = DownloadHistoryCodec.encode(entries)
        val decoded = DownloadHistoryCodec.decode(encoded)

        assertEquals(DownloadHistoryCodec.MAX_ENTRIES, decoded.size)
        assertEquals("Track #1 \"Live\" \\ Mix", decoded.first().title)
        assertEquals("content://media/external/audio/media/1", decoded.first().contentUriString)
        assertEquals("audio/opus", decoded.first().mimeType)
        assertEquals("/data/user/0/app/files/ytdlp_covers/c_1.jpg", decoded.first().coverFilePath)
        assertEquals("Artist 1", decoded.first().artist)
        assertEquals("Album 1", decoded.first().album)
        assertEquals("2026", decoded.first().year)
        assertEquals(181, decoded.first().durationSec)
        assertEquals("OPUS • 160 kbps", decoded.first().formatLabel)
        assertFalse(decoded.first().isVideo)
        assertTrue(decoded[1].isVideo)
        assertEquals(2048L, decoded[1].sizeBytes)
        assertTrue(DownloadHistoryCodec.decode("{invalid").isEmpty())
    }

    @Test
    fun `fromWebViewCookieStrings generates valid yt-dlp Netscape cookies with subdomains and host prefixes`() {
        val captured = mapOf(
            ".youtube.com" to "VISITOR_INFO1_LIVE=abc=123; LOGIN_INFO=tok99",
            "accounts.google.com" to "__Host-GAPS=host_only_val; __Secure-3PSID=sec_val",
        )
        val res = CookieParser.fromWebViewCookieStrings(captured, nowEpochSec = 1_700_000_000L)
        assertEquals(4, res.summary.cookieCount)
        assertTrue(res.netscapeText.contains(".youtube.com\tTRUE\t/\tTRUE\t"))
        assertTrue(res.netscapeText.contains("VISITOR_INFO1_LIVE\tabc=123"))
        assertTrue(res.netscapeText.contains("accounts.google.com\tFALSE\t/\tTRUE\t"))
        assertTrue(res.netscapeText.contains("__Host-GAPS\thost_only_val"))

        // Verify the generated text passes strict Netscape round-trip validation
        val reParsed = CookieParser.parseAndNormalize(res.netscapeText)
        assertEquals(4, reParsed.summary.cookieCount)
    }

    @Test
    fun `infoJsonMetadataParser parseDetails extracts thumbnail, duration, and format label`() {
        val json = """
            {
              "title": "Demo Track",
              "uploader": "Channel Name",
              "duration": 245.7,
              "ext": "m4a",
              "acodec": "mp4a.40.2",
              "abr": 128,
              "thumbnails": [
                {"url": "https://img.example.com/small.jpg"},
                {"url": "https://img.example.com/hqdefault.jpg"}
              ]
            }
        """.trimIndent()
        val details = InfoJsonMetadataParser.parseDetails(json)
        assertNotNull(details)
        assertEquals("https://img.example.com/hqdefault.jpg", details!!.thumbnailUrl)
        assertEquals(245, details.durationSec)
        assertEquals("M4A • 128 kbps • mp4a", details.formatLabel)
        assertEquals("Demo Track", details.metadata?.title)
    }
}
