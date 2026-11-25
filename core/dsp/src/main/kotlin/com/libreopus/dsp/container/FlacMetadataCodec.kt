// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.container

import com.libreopus.audio.api.AudioMetadata
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

object FlacMetadataCodec {
    private const val BLOCK_VORBIS_COMMENT = 4
    private const val BLOCK_PICTURE = 6

    fun readMetadata(input: InputStream): AudioMetadata? {
        return try {
            val magic = ByteArray(4)
            readExact(input, magic)
            if (String(magic, StandardCharsets.US_ASCII) != "fLaC") return null

            var result = AudioMetadata()
            var isLast = false
            val hdr = ByteArray(4)
            while (!isLast) {
                readExact(input, hdr)
                val first = hdr[0].toInt() and 0xFF
                isLast = (first and 0x80) != 0
                val blockType = first and 0x7F
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                val payload = ByteArray(len)
                readExact(input, payload)

                when (blockType) {
                    BLOCK_VORBIS_COMMENT -> {
                        val parsed = OpusTagsCodec.parseVorbisCommentPayload(payload, 0)
                        result = parsed.copy(
                            coverArtBytes = result.coverArtBytes ?: parsed.coverArtBytes,
                            coverArtMimeType = result.coverArtMimeType ?: parsed.coverArtMimeType,
                        )
                    }
                    BLOCK_PICTURE -> {
                        val pic = OpusTagsCodec.decodeFlacPictureBlock(payload)
                        if (pic != null) {
                            result = result.copy(coverArtMimeType = pic.first, coverArtBytes = pic.second)
                        }
                    }
                }
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Rewrites metadata blocks (VORBIS_COMMENT + optional PICTURE) in a FLAC file while
     * preserving STREAMINFO and all compressed audio frames untouched.
     */
    fun rewriteMetadata(
        sourceFile: File,
        metadata: AudioMetadata,
        outputStream: OutputStream,
    ): Boolean {
        return try {
            FileInputStream(sourceFile).buffered().use { fis ->
                val magic = ByteArray(4)
                readExact(fis, magic)
                if (String(magic, StandardCharsets.US_ASCII) != "fLaC") return false

                val preservedBlocks = mutableListOf<Pair<Int, ByteArray>>()
                val hdr = ByteArray(4)
                var isLast = false
                while (!isLast) {
                    readExact(fis, hdr)
                    val first = hdr[0].toInt() and 0xFF
                    isLast = (first and 0x80) != 0
                    val blockType = first and 0x7F
                    val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                        ((hdr[2].toInt() and 0xFF) shl 8) or
                        (hdr[3].toInt() and 0xFF)
                    val payload = ByteArray(len)
                    readExact(fis, payload)

                    // Keep STREAMINFO (0) and any non-comment/non-picture/non-padding blocks
                    if (blockType != BLOCK_VORBIS_COMMENT && blockType != BLOCK_PICTURE && blockType != 1) {
                        preservedBlocks += blockType to payload
                    }
                }

                // Build new VORBIS_COMMENT block (without duplicate Base64 picture)
                val vcOut = ByteArrayOutputStream()
                OpusTagsCodec.writeVorbisCommentBody(
                    vcOut,
                    metadata.copy(coverArtBytes = null, coverArtMimeType = null),
                )
                preservedBlocks += BLOCK_VORBIS_COMMENT to vcOut.toByteArray()

                val cover = metadata.coverArtBytes
                if (cover != null && cover.isNotEmpty()) {
                    val picBlock = OpusTagsCodec.encodeFlacPictureBlock(
                        metadata.coverArtMimeType ?: "image/jpeg",
                        cover,
                    )
                    preservedBlocks += BLOCK_PICTURE to picBlock
                }

                outputStream.write(magic)
                for (i in preservedBlocks.indices) {
                    val (bType, payload) = preservedBlocks[i]
                    val lastFlag = if (i == preservedBlocks.lastIndex) 0x80 else 0x00
                    val bHdr = byteArrayOf(
                        (lastFlag or (bType and 0x7F)).toByte(),
                        ((payload.size ushr 16) and 0xFF).toByte(),
                        ((payload.size ushr 8) and 0xFF).toByte(),
                        (payload.size and 0xFF).toByte(),
                    )
                    outputStream.write(bHdr)
                    outputStream.write(payload)
                }

                // Stream remaining audio frames unchanged
                val copyBuf = ByteArray(32768)
                while (true) {
                    val r = fis.read(copyBuf)
                    if (r < 0) break
                    outputStream.write(copyBuf, 0, r)
                }
                outputStream.flush()
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readExact(input: InputStream, buf: ByteArray) {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r < 0) throw EOFException("Unexpected EOF in FLAC metadata")
            total += r
        }
    }
}
