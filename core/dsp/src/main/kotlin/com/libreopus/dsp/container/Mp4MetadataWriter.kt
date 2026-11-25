// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.container

import com.libreopus.audio.api.AudioMetadata
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Pure-Kotlin ISO BMFF (M4A / MP4) iTunes `moov.udta.meta.ilst` metadata reader and writer.
 * Safely relocates `stco` / `co64` chunk offsets whenever `moov` size changes ahead of `mdat`,
 * enabling zero-dependency cover art & tag embedding without FFmpeg or Mutagen.
 */
object Mp4MetadataWriter {
    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024L

    private data class TopBox(
        val type: String,
        val offset: Long,
        val size: Long,
        val headerSize: Int,
    )

    private data class MemBox(
        val type: String,
        val payload: ByteArray,
    )

    private data class IlstAtom(
        val keyBytes: ByteArray,
        val dataType: Int,
        val valueBytes: ByteArray,
    )

    fun readMetadata(file: File): AudioMetadata? {
        if (!file.exists() || !file.isFile || file.length() < 16L) return null
        return runCatching {
            val topBoxes = scanTopLevelBoxes(file) ?: return null
            if (topBoxes.none { it.type == "ftyp" }) return null
            val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return null
            if (moovBox.size > MAX_MOOV_BYTES || moovBox.size < moovBox.headerSize) return null
            val moovPayloadLen = (moovBox.size - moovBox.headerSize).toInt()
            val moovPayload = ByteArray(moovPayloadLen)
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(moovBox.offset + moovBox.headerSize)
                raf.readFully(moovPayload)
            }
            parseMoovMetadata(moovPayload)
        }.getOrNull()
    }

    fun writeMetadata(
        sourceFile: File,
        metadata: AudioMetadata,
        outputStream: OutputStream,
    ): Boolean {
        if (!sourceFile.exists() || !sourceFile.isFile || sourceFile.length() < 16L) return false
        return try {
            val fileLen = sourceFile.length()
            val topBoxes = scanTopLevelBoxes(sourceFile) ?: return false
            if (topBoxes.none { it.type == "ftyp" } || topBoxes.any { it.type == "moof" }) return false
            val moovBox = topBoxes.firstOrNull { it.type == "moov" } ?: return false
            if (moovBox.size > MAX_MOOV_BYTES || moovBox.size <= moovBox.headerSize) return false

            val oldMoovPayload = ByteArray((moovBox.size - moovBox.headerSize).toInt())
            RandomAccessFile(sourceFile, "r").use { raf ->
                raf.seek(moovBox.offset + moovBox.headerSize)
                raf.readFully(oldMoovPayload)
            }

            val moovChildren = parseChildBoxes(oldMoovPayload, 0, oldMoovPayload.size) ?: return false
            val existingMetaPayload = moovChildren
                .firstOrNull { it.type == "udta" }
                ?.let { parseChildBoxes(it.payload, 0, it.payload.size) }
                ?.firstOrNull { it.type == "meta" }
                ?.payload

            val newMetaBoxPayload = buildMetaPayload(metadata, existingMetaPayload)
            val newMetaAtom = wrapBox("meta", newMetaBoxPayload)

            val existingUdta = moovChildren.firstOrNull { it.type == "udta" }
            val udtaPayloadOut = ByteArrayOutputStream()
            if (existingUdta != null) {
                val udtaChildren = parseChildBoxes(existingUdta.payload, 0, existingUdta.payload.size) ?: return false
                for (child in udtaChildren) {
                    if (child.type != "meta") {
                        udtaPayloadOut.write(wrapBox(child.type, child.payload))
                    }
                }
            }
            udtaPayloadOut.write(newMetaAtom)
            val newUdtaAtom = wrapBox("udta", udtaPayloadOut.toByteArray())

            val patchedMoovPayloadOut = ByteArrayOutputStream(oldMoovPayload.size + newUdtaAtom.size)
            for (child in moovChildren) {
                if (child.type != "udta") {
                    patchedMoovPayloadOut.write(wrapBox(child.type, child.payload))
                }
            }
            patchedMoovPayloadOut.write(newUdtaAtom)

            val rawNewMoov = wrapBox("moov", patchedMoovPayloadOut.toByteArray())
            val oldMoovStart = moovBox.offset
            val oldMoovEnd = moovBox.offset + moovBox.size
            val deltaBytes = rawNewMoov.size.toLong() - moovBox.size

            val finalMoov = if (deltaBytes != 0L) {
                shiftChunkOffsetsInMoov(rawNewMoov, oldMoovEnd, deltaBytes) ?: return false
            } else {
                rawNewMoov
            }

            FileInputStream(sourceFile).use { fis ->
                val buf = ByteArray(262144)
                copyExactBytes(fis, outputStream, oldMoovStart, buf)
                outputStream.write(finalMoov)
                skipExactBytes(fis, moovBox.size)
                copyExactBytes(fis, outputStream, fileLen - oldMoovEnd, buf)
                outputStream.flush()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun scanTopLevelBoxes(file: File): List<TopBox>? {
        val len = file.length()
        val result = ArrayList<TopBox>()
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            val hdr = ByteArray(16)
            while (offset + 8L <= len) {
                raf.seek(offset)
                raf.readFully(hdr, 0, 8)
                val size32 = readUInt32Be(hdr, 0)
                val type = String(hdr, 4, 4, StandardCharsets.ISO_8859_1)
                var headerSize = 8
                val boxSize = when {
                    size32 == 0L -> len - offset
                    size32 == 1L -> {
                        if (offset + 16L > len) return null
                        raf.readFully(hdr, 8, 8)
                        headerSize = 16
                        readInt64Be(hdr, 8)
                    }
                    else -> size32
                }
                if (boxSize < headerSize || offset + boxSize > len) return null
                result += TopBox(type, offset, boxSize, headerSize)
                offset += boxSize
            }
            if (offset != len) return null
        }
        return result
    }

    private fun parseChildBoxes(data: ByteArray, start: Int, end: Int): List<MemBox>? {
        val list = ArrayList<MemBox>()
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readUInt32Be(data, pos)
            val type = String(data, pos + 4, 4, StandardCharsets.ISO_8859_1)
            val headerSize: Int
            val boxSize: Long
            if (size32 == 0L) {
                headerSize = 8
                boxSize = (end - pos).toLong()
            } else if (size32 == 1L) {
                if (pos + 16 > end) return null
                headerSize = 16
                boxSize = readInt64Be(data, pos + 8)
            } else {
                headerSize = 8
                boxSize = size32
            }
            if (boxSize < headerSize || pos + boxSize > end) return null
            val payload = data.copyOfRange(pos + headerSize, pos + boxSize.toInt())
            list += MemBox(type, payload)
            pos += boxSize.toInt()
        }
        return if (pos == end) list else null
    }

    private fun parseMoovMetadata(moovPayload: ByteArray): AudioMetadata? {
        val moovChildren = parseChildBoxes(moovPayload, 0, moovPayload.size) ?: return null
        val udta = moovChildren.firstOrNull { it.type == "udta" } ?: return null
        val udtaChildren = parseChildBoxes(udta.payload, 0, udta.payload.size) ?: return null
        val meta = udtaChildren.firstOrNull { it.type == "meta" } ?: return null
        if (meta.payload.size < 4) return null
        val metaChildren = parseChildBoxes(meta.payload, 4, meta.payload.size) ?: return null
        val ilst = metaChildren.firstOrNull { it.type == "ilst" } ?: return null
        val items = parseChildBoxes(ilst.payload, 0, ilst.payload.size) ?: return null

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

        for (item in items) {
            val children = parseChildBoxes(item.payload, 0, item.payload.size) ?: continue
            val dataAtom = children.firstOrNull { it.type == "data" } ?: continue
            if (dataAtom.payload.size < 8) continue
            val typeFlags = readInt32Be(dataAtom.payload, 0) and 0x00FFFFFF
            val valBytes = dataAtom.payload.copyOfRange(8, dataAtom.payload.size)
            val key = item.type
            val cleanSuffix = if (key.length == 4 && key[0].code == 0xA9) key.substring(1) else ""

            when {
                cleanSuffix == "nam" -> title = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                cleanSuffix == "ART" -> artist = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                cleanSuffix == "alb" -> album = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                key == "aART" -> albumArtist = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                cleanSuffix == "day" -> year = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                cleanSuffix == "gen" -> genre = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                cleanSuffix == "cmt" -> comment = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                cleanSuffix == "wrt" -> composer = String(valBytes, StandardCharsets.UTF_8).trimEnd('\u0000')
                key == "trkn" && valBytes.size >= 4 -> {
                    val trk = ((valBytes[2].toInt() and 0xFF) shl 8) or (valBytes[3].toInt() and 0xFF)
                    if (trk > 0) trackNumber = trk.toString()
                }
                key == "covr" && valBytes.isNotEmpty() -> {
                    coverBytes = valBytes
                    coverMime = when (typeFlags) {
                        14 -> "image/png"
                        13 -> "image/jpeg"
                        else -> if (valBytes.size >= 4 &&
                            valBytes[0] == 0x89.toByte() &&
                            valBytes[1] == 0x50.toByte()
                        ) {
                            "image/png"
                        } else {
                            "image/jpeg"
                        }
                    }
                }
            }
        }
        val metaOut = AudioMetadata(
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
        )
        return metaOut.takeUnless { it.isEmpty }
    }

    private fun buildMetaPayload(
        metadata: AudioMetadata,
        existingMetaPayload: ByteArray?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(4)) // version=0, flags=0

        val preservedMetaChildren = if (existingMetaPayload != null && existingMetaPayload.size >= 4) {
            parseChildBoxes(existingMetaPayload, 4, existingMetaPayload.size)
                .orEmpty()
                .filter { it.type != "hdlr" && it.type != "ilst" }
        } else {
            emptyList()
        }

        // Required iTunes `hdlr` box
        val hdlr = ByteArray(25)
        // version/flags = 0 (4 bytes), pre_defined = 0 (4 bytes)
        "mdir".toByteArray(StandardCharsets.US_ASCII).copyInto(hdlr, 8)
        "appl".toByteArray(StandardCharsets.US_ASCII).copyInto(hdlr, 12)
        // reserved = 0 (8 bytes), name = "\0" (1 byte)
        out.write(wrapBox("hdlr", hdlr))

        for (child in preservedMetaChildren) {
            out.write(wrapBox(child.type, child.payload))
        }

        val ilstOut = ByteArrayOutputStream()
        val atoms = buildIlstAtoms(metadata)
        for (atom in atoms) {
            val dataLen = 16 + atom.valueBytes.size
            val itemOut = ByteArrayOutputStream(8 + dataLen)
            val dataHdr = ByteArray(16)
            writeInt32Be(dataHdr, 0, dataLen)
            "data".toByteArray(StandardCharsets.US_ASCII).copyInto(dataHdr, 4)
            writeInt32Be(dataHdr, 8, atom.dataType)
            writeInt32Be(dataHdr, 12, 0) // locale
            itemOut.write(dataHdr)
            itemOut.write(atom.valueBytes)

            val itemPayload = itemOut.toByteArray()
            val itemHdr = ByteArray(8)
            writeInt32Be(itemHdr, 0, 8 + itemPayload.size)
            atom.keyBytes.copyInto(itemHdr, 4)
            ilstOut.write(itemHdr)
            ilstOut.write(itemPayload)
        }
        out.write(wrapBox("ilst", ilstOut.toByteArray()))
        return out.toByteArray()
    }

    private fun buildIlstAtoms(metadata: AudioMetadata): List<IlstAtom> {
        val atoms = ArrayList<IlstAtom>()
        fun addText(b0: Int, rest: String, value: String) {
            val clean = value.trim()
            if (clean.isEmpty()) return
            val key = byteArrayOf(b0.toByte(), rest[0].code.toByte(), rest[1].code.toByte(), rest[2].code.toByte())
            atoms += IlstAtom(key, 1, clean.toByteArray(StandardCharsets.UTF_8))
        }
        fun addAscii(tag: String, value: String) {
            val clean = value.trim()
            if (clean.isEmpty()) return
            atoms += IlstAtom(tag.toByteArray(StandardCharsets.US_ASCII), 1, clean.toByteArray(StandardCharsets.UTF_8))
        }

        addText(0xA9, "nam", metadata.title)
        addText(0xA9, "ART", metadata.artist)
        addText(0xA9, "alb", metadata.album)
        addAscii("aART", metadata.albumArtist)
        addText(0xA9, "day", metadata.year)
        addText(0xA9, "gen", metadata.genre)
        addText(0xA9, "cmt", metadata.comment)
        addText(0xA9, "wrt", metadata.composer)

        val trk = metadata.trackNumber.substringBefore('/').trim().toIntOrNull()
        if (trk != null && trk in 1..65535) {
            val trkn = ByteArray(8)
            trkn[2] = ((trk ushr 8) and 0xFF).toByte()
            trkn[3] = (trk and 0xFF).toByte()
            atoms += IlstAtom("trkn".toByteArray(StandardCharsets.US_ASCII), 0, trkn)
        }

        val cover = metadata.coverArtBytes
        if (cover != null && cover.isNotEmpty()) {
            val isPng = metadata.coverArtMimeType.equals("image/png", ignoreCase = true) ||
                (cover.size >= 4 && cover[0] == 0x89.toByte() && cover[1] == 0x50.toByte())
            val dataType = if (isPng) 14 else 13
            atoms += IlstAtom("covr".toByteArray(StandardCharsets.US_ASCII), dataType, cover)
        }
        return atoms
    }

    private fun shiftChunkOffsetsInMoov(
        moovBoxBytes: ByteArray,
        oldMoovEnd: Long,
        deltaBytes: Long,
    ): ByteArray? {
        val copy = moovBoxBytes.copyOf()
        val ok = patchOffsetsRecursive(copy, 8, copy.size, oldMoovEnd, deltaBytes)
        return if (ok) copy else null
    }

    private fun patchOffsetsRecursive(
        buf: ByteArray,
        start: Int,
        end: Int,
        oldMoovEnd: Long,
        deltaBytes: Long,
    ): Boolean {
        var pos = start
        while (pos + 8 <= end) {
            val size32 = readUInt32Be(buf, pos)
            val type = String(buf, pos + 4, 4, StandardCharsets.ISO_8859_1)
            val headerSize: Int
            val boxSize: Long
            if (size32 == 0L) {
                headerSize = 8
                boxSize = (end - pos).toLong()
            } else if (size32 == 1L) {
                if (pos + 16 > end) return false
                headerSize = 16
                boxSize = readInt64Be(buf, pos + 8)
            } else {
                headerSize = 8
                boxSize = size32
            }
            if (boxSize < headerSize || pos + boxSize > end) return false
            val payloadStart = pos + headerSize
            val payloadEnd = pos + boxSize.toInt()

            when (type) {
                "trak", "mdia", "minf", "stbl" -> {
                    if (!patchOffsetsRecursive(buf, payloadStart, payloadEnd, oldMoovEnd, deltaBytes)) return false
                }
                "stco" -> {
                    if (payloadStart + 8 > payloadEnd) return false
                    val count = readUInt32Be(buf, payloadStart + 4).toInt()
                    val entriesStart = payloadStart + 8
                    if (entriesStart + count * 4L > payloadEnd) return false
                    for (i in 0 until count) {
                        val offPos = entriesStart + i * 4
                        val oldOff = readUInt32Be(buf, offPos)
                        val newOff = if (oldOff >= oldMoovEnd) oldOff + deltaBytes else oldOff
                        if (newOff < 0L || newOff > 0xFFFFFFFFL) return false
                        writeInt32Be(buf, offPos, newOff.toInt())
                    }
                }
                "co64" -> {
                    if (payloadStart + 8 > payloadEnd) return false
                    val count = readUInt32Be(buf, payloadStart + 4).toInt()
                    val entriesStart = payloadStart + 8
                    if (entriesStart + count * 8L > payloadEnd) return false
                    for (i in 0 until count) {
                        val offPos = entriesStart + i * 8
                        val oldOff = readInt64Be(buf, offPos)
                        val newOff = if (oldOff >= oldMoovEnd) oldOff + deltaBytes else oldOff
                        if (newOff < 0L) return false
                        writeInt64Be(buf, offPos, newOff)
                    }
                }
            }
            pos = payloadEnd
        }
        return pos == end
    }

    private fun wrapBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        writeInt32Be(out, 0, out.size)
        type.toByteArray(StandardCharsets.ISO_8859_1).copyInto(out, 4, 0, 4)
        payload.copyInto(out, 8)
        return out
    }

    private fun copyExactBytes(input: FileInputStream, out: OutputStream, count: Long, buf: ByteArray) {
        var rem = count
        while (rem > 0L) {
            val toRead = minOf(buf.size.toLong(), rem).toInt()
            val n = input.read(buf, 0, toRead)
            if (n <= 0) break
            out.write(buf, 0, n)
            rem -= n
        }
    }

    private fun skipExactBytes(input: FileInputStream, count: Long) {
        var rem = count
        while (rem > 0L) {
            val s = input.skip(rem)
            if (s > 0L) {
                rem -= s
            } else {
                if (input.read() < 0) break
                rem--
            }
        }
    }

    private fun readUInt32Be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun readInt32Be(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readInt64Be(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 56) or
            ((b[off + 1].toLong() and 0xFF) shl 48) or
            ((b[off + 2].toLong() and 0xFF) shl 40) or
            ((b[off + 3].toLong() and 0xFF) shl 32) or
            ((b[off + 4].toLong() and 0xFF) shl 24) or
            ((b[off + 5].toLong() and 0xFF) shl 16) or
            ((b[off + 6].toLong() and 0xFF) shl 8) or
            (b[off + 7].toLong() and 0xFF)

    private fun writeInt32Be(b: ByteArray, off: Int, v: Int) {
        b[off] = ((v ushr 24) and 0xFF).toByte()
        b[off + 1] = ((v ushr 16) and 0xFF).toByte()
        b[off + 2] = ((v ushr 8) and 0xFF).toByte()
        b[off + 3] = (v and 0xFF).toByte()
    }

    private fun writeInt64Be(b: ByteArray, off: Int, v: Long) {
        b[off] = ((v ushr 56) and 0xFF).toByte()
        b[off + 1] = ((v ushr 48) and 0xFF).toByte()
        b[off + 2] = ((v ushr 40) and 0xFF).toByte()
        b[off + 3] = ((v ushr 32) and 0xFF).toByte()
        b[off + 4] = ((v ushr 24) and 0xFF).toByte()
        b[off + 5] = ((v ushr 16) and 0xFF).toByte()
        b[off + 6] = ((v ushr 8) and 0xFF).toByte()
        b[off + 7] = (v and 0xFF).toByte()
    }
}
