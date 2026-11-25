// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.container

import com.libreopus.audio.api.AudioMetadata
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ID3v2.3 / ID3v2.4 reader (including UTF-8, UTF-16, and APIC cover art).
 */
object Mp3TagReader {

    fun readMetadata(input: InputStream): AudioMetadata? {
        return try {
            val header = ByteArray(10)
            if (readUpTo(input, header) < 10) return null
            if (header[0] != 'I'.code.toByte() || header[1] != 'D'.code.toByte() || header[2] != '3'.code.toByte()) {
                return null
            }
            val majorVersion = header[3].toInt() and 0xFF
            val flags = header[5].toInt() and 0xFF
            val tagSize = readSynchSafeInt(header, 6).coerceIn(0, 16 * 1024 * 1024)
            val data = ByteArray(tagSize)
            val actual = readUpTo(input, data)
            if (actual <= 0) return null

            var pos = 0
            if (majorVersion >= 3 && (flags and 0x40) != 0 && pos + 4 <= actual) {
                val extSize = if (majorVersion == 4) readSynchSafeInt(data, pos) else readBeInt(data, pos)
                pos += extSize.coerceAtLeast(4)
            }

            var title = ""
            var artist = ""
            var album = ""
            var albumArtist = ""
            var year = ""
            var track = ""
            var genre = ""
            var comment = ""
            var composer = ""
            var coverBytes: ByteArray? = null
            var coverMime: String? = null

            while (pos + 10 <= actual) {
                if (data[pos] == 0.toByte()) break
                val frameId = String(data, pos, 4, StandardCharsets.US_ASCII)
                val frameSize = if (majorVersion == 4) {
                    readSynchSafeInt(data, pos + 4)
                } else {
                    readBeInt(data, pos + 4)
                }
                pos += 10
                if (frameSize <= 0 || pos + frameSize > actual) break

                when (frameId) {
                    "TIT2" -> title = decodeTextFrame(data, pos, frameSize)
                    "TPE1" -> artist = decodeTextFrame(data, pos, frameSize)
                    "TALB" -> album = decodeTextFrame(data, pos, frameSize)
                    "TPE2" -> albumArtist = decodeTextFrame(data, pos, frameSize)
                    "TYER", "TDRC" -> if (year.isEmpty()) year = decodeTextFrame(data, pos, frameSize)
                    "TRCK" -> track = decodeTextFrame(data, pos, frameSize)
                    "TCON" -> genre = decodeTextFrame(data, pos, frameSize)
                    "TCOM" -> composer = decodeTextFrame(data, pos, frameSize)
                    "COMM" -> if (comment.isEmpty()) comment = decodeCommentFrame(data, pos, frameSize)
                    "APIC" -> if (coverBytes == null) {
                        val pic = decodeApicFrame(data, pos, frameSize)
                        if (pic != null) {
                            coverMime = pic.first
                            coverBytes = pic.second
                        }
                    }
                }
                pos += frameSize
            }

            AudioMetadata(
                title = title,
                artist = artist,
                album = album,
                albumArtist = albumArtist,
                year = year,
                trackNumber = track,
                genre = genre,
                comment = comment,
                composer = composer,
                coverArtBytes = coverBytes,
                coverArtMimeType = coverMime,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeTextFrame(buf: ByteArray, offset: Int, len: Int): String {
        if (len <= 1) return ""
        val enc = buf[offset].toInt() and 0xFF
        return decodeEncodedString(buf, offset + 1, len - 1, enc).trimEnd('\u0000').trim()
    }

    private fun decodeCommentFrame(buf: ByteArray, offset: Int, len: Int): String {
        if (len <= 4) return ""
        val enc = buf[offset].toInt() and 0xFF
        var pos = offset + 4 // skip encoding + 3-char language
        // skip short content descriptor terminated by 0 (or 0x00 0x00 in UTF-16)
        val isWide = enc == 1 || enc == 2
        val end = offset + len
        if (isWide) {
            while (pos + 1 < end) {
                if (buf[pos] == 0.toByte() && buf[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < end) {
                if (buf[pos] == 0.toByte()) {
                    pos++
                    break
                }
                pos++
            }
        }
        return if (pos < end) decodeEncodedString(buf, pos, end - pos, enc).trimEnd('\u0000').trim() else ""
    }

    private fun decodeApicFrame(buf: ByteArray, offset: Int, len: Int): Pair<String, ByteArray>? {
        if (len <= 4) return null
        val enc = buf[offset].toInt() and 0xFF
        val end = offset + len
        var pos = offset + 1
        val mimeStart = pos
        while (pos < end && buf[pos] != 0.toByte()) pos++
        val mime = String(buf, mimeStart, pos - mimeStart, StandardCharsets.ISO_8859_1).ifBlank { "image/jpeg" }
        if (pos >= end) return null
        pos++ // null terminator
        if (pos >= end) return null
        pos++ // picture type byte

        val isWide = enc == 1 || enc == 2
        if (isWide) {
            while (pos + 1 < end) {
                if (buf[pos] == 0.toByte() && buf[pos + 1] == 0.toByte()) {
                    pos += 2
                    break
                }
                pos += 2
            }
        } else {
            while (pos < end) {
                if (buf[pos] == 0.toByte()) {
                    pos++
                    break
                }
                pos++
            }
        }
        if (pos >= end) return null
        return mime to buf.copyOfRange(pos, end)
    }

    private fun decodeEncodedString(buf: ByteArray, offset: Int, len: Int, enc: Int): String {
        if (len <= 0) return ""
        val charset: Charset = when (enc) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> StandardCharsets.UTF_16
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> StandardCharsets.UTF_8
        }
        return String(buf, offset, len, charset)
    }

    private fun readSynchSafeInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0x7F) shl 21) or
            ((b[o + 1].toInt() and 0x7F) shl 14) or
            ((b[o + 2].toInt() and 0x7F) shl 7) or
            (b[o + 3].toInt() and 0x7F)

    private fun readBeInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) break
            total += r
        }
        return total
    }
}
