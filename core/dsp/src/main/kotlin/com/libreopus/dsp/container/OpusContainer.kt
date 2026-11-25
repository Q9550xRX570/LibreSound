// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.container

import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.ExportResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64

data class OpusHead(
    val version: Int = 1,
    val channels: Int,
    val preSkip: Int,
    val inputSampleRate: Int,
    val outputGainQ78: Int = 0,
    val channelMappingFamily: Int = 0,
    val extraMappingBytes: ByteArray = ByteArray(0),
) {
    fun toBytes(): ByteArray {
        val out = ByteArray(19 + extraMappingBytes.size)
        "OpusHead".toByteArray(StandardCharsets.US_ASCII).copyInto(out, 0)
        out[8] = version.toByte()
        out[9] = channels.toByte()
        writeLeShort(out, 10, preSkip)
        writeLeInt(out, 12, inputSampleRate)
        writeLeShort(out, 16, outputGainQ78)
        out[18] = channelMappingFamily.toByte()
        if (extraMappingBytes.isNotEmpty()) {
            extraMappingBytes.copyInto(out, 19)
        }
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpusHead) return false
        return version == other.version &&
            channels == other.channels &&
            preSkip == other.preSkip &&
            inputSampleRate == other.inputSampleRate &&
            outputGainQ78 == other.outputGainQ78 &&
            channelMappingFamily == other.channelMappingFamily &&
            extraMappingBytes.contentEquals(other.extraMappingBytes)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + channels
        result = 31 * result + preSkip
        result = 31 * result + inputSampleRate
        result = 31 * result + outputGainQ78
        result = 31 * result + channelMappingFamily
        result = 31 * result + extraMappingBytes.contentHashCode()
        return result
    }

    companion object {
        fun parse(packet: ByteArray): OpusHead? {
            if (packet.size < 19) return null
            val magic = String(packet, 0, 8, StandardCharsets.US_ASCII)
            if (magic != "OpusHead") return null
            val extra = if (packet.size > 19) packet.copyOfRange(19, packet.size) else ByteArray(0)
            return OpusHead(
                version = packet[8].toInt() and 0xFF,
                channels = packet[9].toInt() and 0xFF,
                preSkip = readLeShort(packet, 10),
                inputSampleRate = readLeInt(packet, 12),
                outputGainQ78 = readLeShort(packet, 16).toShort().toInt(),
                channelMappingFamily = packet[18].toInt() and 0xFF,
                extraMappingBytes = extra,
            )
        }
    }
}

object OpusTagsCodec {
    private const val VENDOR_STRING = "LibreOpus"

    fun parse(packet: ByteArray): AudioMetadata? {
        if (packet.size < 16) return null
        val magic = String(packet, 0, 8, StandardCharsets.US_ASCII)
        if (magic != "OpusTags") return null
        return parseVorbisCommentPayload(packet, 8)
    }

    fun parseVorbisCommentPayload(data: ByteArray, startOffset: Int): AudioMetadata {
        var offset = startOffset
        if (offset + 4 > data.size) return AudioMetadata()
        val vendorLen = readLeInt(data, offset)
        offset += 4
        if (vendorLen < 0 || offset + vendorLen + 4 > data.size) return AudioMetadata()
        offset += vendorLen

        val userCommentCount = readLeInt(data, offset).coerceIn(0, 4096)
        offset += 4

        var title = ""
        var artist = ""
        var album = ""
        var albumArtist = ""
        var year = ""
        var trackNumber = ""
        var genre = ""
        var comment = ""
        var composer = ""
        var coverBytes: ByteArray? = null
        var coverMime: String? = null
        val extras = linkedMapOf<String, String>()

        for (i in 0 until userCommentCount) {
            if (offset + 4 > data.size) break
            val len = readLeInt(data, offset)
            offset += 4
            if (len < 0 || offset + len > data.size) break
            val entry = String(data, offset, len, StandardCharsets.UTF_8)
            offset += len
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            val key = entry.substring(0, eq).uppercase()
            val value = entry.substring(eq + 1)

            when (key) {
                "TITLE" -> title = value
                "ARTIST" -> artist = value
                "ALBUM" -> album = value
                "ALBUMARTIST", "ALBUM ARTIST" -> albumArtist = value
                "DATE", "YEAR" -> if (year.isEmpty()) year = value
                "TRACKNUMBER" -> trackNumber = value
                "GENRE" -> genre = value
                "COMMENT", "DESCRIPTION" -> if (comment.isEmpty()) comment = value
                "COMPOSER" -> composer = value
                "METADATA_BLOCK_PICTURE" -> {
                    val pic = decodeFlacPictureBase64(value)
                    if (pic != null && coverBytes == null) {
                        coverMime = pic.first
                        coverBytes = pic.second
                    }
                }
                else -> extras[key] = value
            }
        }

        return AudioMetadata(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            year = year,
            trackNumber = trackNumber,
            genre = genre,
            comment = comment,
            composer = composer,
            coverArtBytes = coverBytes,
            coverArtMimeType = coverMime,
            extraTags = extras,
        )
    }

    fun serializeOpusTags(metadata: AudioMetadata, vendor: String = VENDOR_STRING): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("OpusTags".toByteArray(StandardCharsets.US_ASCII))
        writeVorbisCommentBody(out, metadata, vendor)
        return out.toByteArray()
    }

    fun writeVorbisCommentBody(out: ByteArrayOutputStream, metadata: AudioMetadata, vendor: String = VENDOR_STRING) {
        val comments = buildCommentList(metadata)
        val vendorBytes = vendor.toByteArray(StandardCharsets.UTF_8)
        out.write(leIntBytes(vendorBytes.size))
        out.write(vendorBytes)
        out.write(leIntBytes(comments.size))
        for (entry in comments) {
            val bytes = entry.toByteArray(StandardCharsets.UTF_8)
            out.write(leIntBytes(bytes.size))
            out.write(bytes)
        }
    }

    private fun buildCommentList(metadata: AudioMetadata): List<String> {
        val list = mutableListOf<String>()
        if (metadata.title.isNotBlank()) list += "TITLE=${metadata.title}"
        if (metadata.artist.isNotBlank()) list += "ARTIST=${metadata.artist}"
        if (metadata.album.isNotBlank()) list += "ALBUM=${metadata.album}"
        if (metadata.albumArtist.isNotBlank()) list += "ALBUMARTIST=${metadata.albumArtist}"
        if (metadata.year.isNotBlank()) list += "DATE=${metadata.year}"
        if (metadata.trackNumber.isNotBlank()) list += "TRACKNUMBER=${metadata.trackNumber}"
        if (metadata.genre.isNotBlank()) list += "GENRE=${metadata.genre}"
        if (metadata.comment.isNotBlank()) list += "COMMENT=${metadata.comment}"
        if (metadata.composer.isNotBlank()) list += "COMPOSER=${metadata.composer}"
        for ((k, v) in metadata.extraTags) {
            if (k.isNotBlank() && v.isNotBlank()) {
                list += "${k.uppercase()}=$v"
            }
        }
        val cover = metadata.coverArtBytes
        if (cover != null && cover.isNotEmpty()) {
            val mime = metadata.coverArtMimeType ?: "image/jpeg"
            val flacPic = encodeFlacPictureBlock(mime, cover)
            val b64 = Base64.getEncoder().encodeToString(flacPic)
            list += "METADATA_BLOCK_PICTURE=$b64"
        }
        return list
    }

    fun encodeFlacPictureBlock(mimeType: String, imageBytes: ByteArray): ByteArray {
        val mimeBytes = mimeType.toByteArray(StandardCharsets.UTF_8)
        val out = ByteArrayOutputStream(32 + mimeBytes.size + imageBytes.size)
        out.write(beIntBytes(3)) // Picture type 3: Cover (front)
        out.write(beIntBytes(mimeBytes.size))
        out.write(mimeBytes)
        out.write(beIntBytes(0)) // Description length 0
        out.write(beIntBytes(0)) // Width (unknown)
        out.write(beIntBytes(0)) // Height (unknown)
        out.write(beIntBytes(0)) // Color depth
        out.write(beIntBytes(0)) // Colors used
        out.write(beIntBytes(imageBytes.size))
        out.write(imageBytes)
        return out.toByteArray()
    }

    fun decodeFlacPictureBlock(block: ByteArray): Pair<String, ByteArray>? {
        if (block.size < 32) return null
        var pos = 4 // skip picture type
        val mimeLen = readBeInt(block, pos); pos += 4
        if (mimeLen < 0 || pos + mimeLen + 24 > block.size) return null
        val mime = String(block, pos, mimeLen, StandardCharsets.UTF_8); pos += mimeLen
        val descLen = readBeInt(block, pos); pos += 4
        if (descLen < 0 || pos + descLen + 20 > block.size) return null
        pos += descLen
        pos += 16 // width, height, depth, indexed colors
        val dataLen = readBeInt(block, pos); pos += 4
        if (dataLen <= 0 || pos + dataLen > block.size) return null
        return mime to block.copyOfRange(pos, pos + dataLen)
    }

    private fun decodeFlacPictureBase64(b64: String): Pair<String, ByteArray>? {
        return try {
            val bytes = Base64.getDecoder().decode(b64.trim())
            decodeFlacPictureBlock(bytes)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun leIntBytes(v: Int): ByteArray = ByteArray(4).also { writeLeInt(it, 0, v) }
    private fun beIntBytes(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte(),
    )

    private fun readBeInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
            ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or
            (b[o + 3].toInt() and 0xFF)
}

object OpusContainer {

    /**
     * Exact RFC 6716 §3.1 calculation of decoded samples at 48 kHz inside a single Opus packet.
     */
    fun opusPacketDurationSamples48k(packet: ByteArray): Int {
        if (packet.isEmpty()) return 960
        val toc = packet[0].toInt() and 0xFF
        val config = (toc ushr 3) and 0x1F
        val code = toc and 0x03

        val frameSizeSamples = when {
            config in 0..11 -> when (config and 0x03) {
                0 -> 480    // 10 ms
                1 -> 960    // 20 ms
                2 -> 1920   // 40 ms
                else -> 2880 // 60 ms
            }
            config in 12..15 -> when (config and 0x01) {
                0 -> 480    // 10 ms
                else -> 960 // 20 ms
            }
            else -> when (config and 0x03) {
                0 -> 120    // 2.5 ms
                1 -> 240    // 5 ms
                2 -> 480    // 10 ms
                else -> 960 // 20 ms
            }
        }

        val frameCount = when (code) {
            0 -> 1
            1, 2 -> 2
            else -> if (packet.size >= 2) (packet[1].toInt() and 0x3F).coerceAtLeast(1) else 1
        }
        return frameSizeSamples * frameCount
    }

    fun readMetadata(input: InputStream): AudioMetadata? {
        return try {
            val (_, packets) = OggPacketExtractor.extractPackets(input)
            val head = packets.getOrNull(0)?.let { OpusHead.parse(it.data) } ?: return null
            val _channels = head.channels
            val tagsPkt = packets.getOrNull(1)?.data ?: return AudioMetadata()
            OpusTagsCodec.parse(tagsPkt)
        } catch (_: Exception) {
            null
        }
    }

    fun inspectHeadAndDurationMs(file: File): Pair<OpusHead, Long>? {
        return try {
            FileInputStream(file).buffered().use { fis ->
                val (_, packets) = OggPacketExtractor.extractPackets(fis)
                val head = packets.getOrNull(0)?.let { OpusHead.parse(it.data) } ?: return null
                val audioPackets = packets.drop(2)
                val lastGranule = audioPackets.lastOrNull()?.granulePosition ?: 0L
                val totalSamples48k = if (lastGranule > head.preSkip) {
                    lastGranule - head.preSkip
                } else {
                    audioPackets.sumOf { opusPacketDurationSamples48k(it.data).toLong() } - head.preSkip
                }.coerceAtLeast(0L)
                head to (totalSamples48k * 1000L) / 48000L
            }
        } catch (_: Exception) {
            null
        }
    }

    fun writeOggOpus(
        outputStream: OutputStream,
        channels: Int,
        inputSampleRate: Int,
        preSkip: Int,
        metadata: AudioMetadata,
        audioPackets: List<ByteArray>,
        totalOutputFrames48k: Long,
        serialNumber: Int = 0x4C4F5055,
    ): Long {
        val counting = CountingOutputStream(outputStream)
        val muxer = OggPageMuxer(counting, serialNumber)
        val head = OpusHead(
            channels = channels.coerceIn(1, 255),
            preSkip = preSkip.coerceIn(0, 65535),
            inputSampleRate = inputSampleRate,
        )
        muxer.writeSinglePacketPage(head.toBytes(), granulePosition = 0L, bos = true, eos = false)
        muxer.writeSinglePacketPage(OpusTagsCodec.serializeOpusTags(metadata), granulePosition = 0L, bos = false, eos = false)

        if (audioPackets.isNotEmpty()) {
            val paired = ArrayList<Pair<ByteArray, Long>>(audioPackets.size)
            var cumSamples = 0L
            val targetEndGranule = preSkip.toLong() + totalOutputFrames48k
            for (i in audioPackets.indices) {
                val pkt = audioPackets[i]
                cumSamples += opusPacketDurationSamples48k(pkt).toLong()
                val granule = if (i == audioPackets.lastIndex) {
                    minOf(cumSamples, targetEndGranule).coerceAtLeast(preSkip.toLong())
                } else {
                    cumSamples
                }
                paired += pkt to granule
            }
            muxer.writePacketsOnPages(paired, bos = false, eos = true)
        }
        counting.flush()
        return counting.bytesWritten
    }
}

object LosslessOpusEditor {
    private const val PRE_ROLL_SAMPLES_48K = 3840L // 80 ms per RFC 7845 §4.6

    fun isValidOggOpus(file: File): Boolean {
        if (!file.exists() || file.length() < 48) return false
        return try {
            FileInputStream(file).buffered().use { fis ->
                val (_, packets) = OggPacketExtractor.extractPackets(fis)
                packets.size >= 2 && OpusHead.parse(packets[0].data) != null
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Losslessly trims an Ogg Opus file between [startMs] and [endMs] without re-encoding
     * audio frames. Includes 80 ms pre-roll with adjusted OpusHead.preSkip for click-free decode.
     */
    fun trimLossless(
        sourceFile: File,
        startMs: Long,
        endMs: Long,
        metadataOverride: AudioMetadata?,
        outputStream: OutputStream,
        onProgress: (Float) -> Unit = {},
    ): ExportResult {
        val (serial, packets) = FileInputStream(sourceFile).buffered().use {
            OggPacketExtractor.extractPackets(it)
        }
        val origHead = packets.getOrNull(0)?.let { OpusHead.parse(it.data) }
            ?: throw IllegalArgumentException("Source file is not valid Ogg Opus")
        val origTags = packets.getOrNull(1)?.let { OpusTagsCodec.parse(it.data) } ?: AudioMetadata()
        val finalMetadata = metadataOverride ?: origTags

        val audioPackets = packets.drop(2)
        if (audioPackets.isEmpty()) {
            throw IllegalArgumentException("Ogg Opus stream contains no audio packets")
        }

        // Map each packet to raw decoded sample span [rawStart, rawEnd)
        val rawStarts = LongArray(audioPackets.size)
        val rawEnds = LongArray(audioPackets.size)
        var rawCursor = 0L
        for (i in audioPackets.indices) {
            val dur = OpusContainer.opusPacketDurationSamples48k(audioPackets[i].data).toLong()
            rawStarts[i] = rawCursor
            rawCursor += dur
            rawEnds[i] = rawCursor
        }
        val totalPlayable48k = (rawCursor - origHead.preSkip).coerceAtLeast(0L)
        val trimStart48k = (startMs.coerceAtLeast(0L) * 48L).coerceIn(0L, totalPlayable48k)
        val trimEnd48k = (endMs.coerceAtLeast(startMs) * 48L).coerceIn(trimStart48k, totalPlayable48k)

        val targetRawStart = origHead.preSkip.toLong() + trimStart48k
        val targetRawEnd = origHead.preSkip.toLong() + trimEnd48k
        val preRollRawStart = (targetRawStart - PRE_ROLL_SAMPLES_48K).coerceAtLeast(0L)

        var firstIdx = 0
        while (firstIdx < audioPackets.lastIndex && rawEnds[firstIdx] <= preRollRawStart) {
            firstIdx++
        }
        var lastIdx = firstIdx
        while (lastIdx < audioPackets.lastIndex && rawStarts[lastIdx + 1] < targetRawEnd) {
            lastIdx++
        }

        val keptPackets = audioPackets.subList(firstIdx, lastIdx + 1).map { it.data }
        val newPreSkip = (targetRawStart - rawStarts[firstIdx]).coerceIn(0L, 65535L).toInt()
        val desiredOutputFrames48k = (targetRawEnd - targetRawStart).coerceAtLeast(0L)

        val counting = CountingOutputStream(outputStream)
        val muxer = OggPageMuxer(counting, serial)
        val newHead = origHead.copy(preSkip = newPreSkip)
        muxer.writeSinglePacketPage(newHead.toBytes(), granulePosition = 0L, bos = true, eos = false)
        muxer.writeSinglePacketPage(OpusTagsCodec.serializeOpusTags(finalMetadata), granulePosition = 0L, bos = false, eos = false)

        val rebased = ArrayList<Pair<ByteArray, Long>>(keptPackets.size)
        var cum = 0L
        val targetEndGranule = newPreSkip.toLong() + desiredOutputFrames48k
        for (i in keptPackets.indices) {
            val pkt = keptPackets[i]
            cum += OpusContainer.opusPacketDurationSamples48k(pkt).toLong()
            val granule = if (i == keptPackets.lastIndex) {
                minOf(cum, targetEndGranule).coerceAtLeast(newPreSkip.toLong())
            } else {
                cum
            }
            rebased += pkt to granule
            onProgress(0.3f + (i.toFloat() / keptPackets.size.coerceAtLeast(1)) * 0.7f)
        }
        muxer.writePacketsOnPages(rebased, bos = false, eos = true)
        counting.flush()
        onProgress(1f)

        return ExportResult(
            bytesWritten = counting.bytesWritten,
            durationMs = (desiredOutputFrames48k * 1000L) / 48000L,
            wasLossless = true,
        )
    }

    /**
     * Rewrites only the OpusTags packet without altering OpusHead or any audio packet bytes.
     */
    fun rewriteTagsLossless(
        sourceFile: File,
        metadata: AudioMetadata,
        outputStream: OutputStream,
    ): Boolean {
        return try {
            val (serial, packets) = FileInputStream(sourceFile).buffered().use {
                OggPacketExtractor.extractPackets(it)
            }
            if (packets.size < 2 || OpusHead.parse(packets[0].data) == null) return false

            val muxer = OggPageMuxer(outputStream, serial)
            muxer.writeSinglePacketPage(packets[0].data, granulePosition = 0L, bos = true, eos = false)
            muxer.writeSinglePacketPage(OpusTagsCodec.serializeOpusTags(metadata), granulePosition = 0L, bos = false, eos = false)

            val audioPackets = packets.drop(2)
            if (audioPackets.isNotEmpty()) {
                var cum = 0L
                val paired = audioPackets.mapIndexed { idx, pkt ->
                    cum += OpusContainer.opusPacketDurationSamples48k(pkt.data).toLong()
                    val gran = if (pkt.granulePosition >= 0L) pkt.granulePosition else cum
                    pkt.data to gran
                }
                muxer.writePacketsOnPages(paired, bos = false, eos = true)
            }
            outputStream.flush()
            true
        } catch (_: Exception) {
            false
        }
    }
}

private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        delegate.write(b, off, len)
        bytesWritten += len
    }

    override fun flush() = delegate.flush()
}
