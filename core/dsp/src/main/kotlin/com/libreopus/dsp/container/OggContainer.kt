// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp.container

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

data class OggPage(
    val version: Int,
    val headerType: Int,
    val granulePosition: Long,
    val serialNumber: Int,
    val pageSequence: Int,
    val segmentTable: ByteArray,
    val payload: ByteArray,
) {
    val isContinuation: Boolean get() = (headerType and FLAG_CONTINUATION) != 0
    val isBos: Boolean get() = (headerType and FLAG_BOS) != 0
    val isEos: Boolean get() = (headerType and FLAG_EOS) != 0

    fun toBytes(): ByteArray {
        val headerSize = 27 + segmentTable.size
        val out = ByteArray(headerSize + payload.size)
        out[0] = 'O'.code.toByte()
        out[1] = 'g'.code.toByte()
        out[2] = 'g'.code.toByte()
        out[3] = 'S'.code.toByte()
        out[4] = version.toByte()
        out[5] = headerType.toByte()
        writeLeLong(out, 6, granulePosition)
        writeLeInt(out, 14, serialNumber)
        writeLeInt(out, 18, pageSequence)
        // CRC bytes [22..25] stay 0 during CRC calculation
        out[26] = segmentTable.size.toByte()
        System.arraycopy(segmentTable, 0, out, 27, segmentTable.size)
        System.arraycopy(payload, 0, out, headerSize, payload.size)

        val crc = OggCrc32.compute(out)
        writeLeInt(out, 22, crc)
        return out
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OggPage) return false
        return version == other.version &&
            headerType == other.headerType &&
            granulePosition == other.granulePosition &&
            serialNumber == other.serialNumber &&
            pageSequence == other.pageSequence &&
            segmentTable.contentEquals(other.segmentTable) &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = version
        result = 31 * result + headerType
        result = 31 * result + granulePosition.hashCode()
        result = 31 * result + serialNumber
        result = 31 * result + pageSequence
        result = 31 * result + segmentTable.contentHashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        const val FLAG_CONTINUATION = 0x01
        const val FLAG_BOS = 0x02
        const val FLAG_EOS = 0x04
    }
}

/**
 * Exact RFC 3533 Ogg CRC-32 (polynomial 0x04c11db7, MSB-first, init 0, no final XOR).
 */
object OggCrc32 {
    private val TABLE = IntArray(256).also { table ->
        for (i in 0 until 256) {
            var r = i shl 24
            for (bit in 0 until 8) {
                r = if ((r and Int.MIN_VALUE) != 0) {
                    (r shl 1) xor 0x04C11DB7
                } else {
                    r shl 1
                }
            }
            table[i] = r
        }
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size - offset): Int {
        var crc = 0
        for (i in offset until offset + length) {
            val idx = ((crc ushr 24) xor (data[i].toInt() and 0xFF)) and 0xFF
            crc = (crc shl 8) xor TABLE[idx]
        }
        return crc
    }
}

class OggPageReader(private val input: InputStream) {

    fun readNextPage(): OggPage? {
        val header = ByteArray(27)
        val first = input.read()
        if (first < 0) return null
        header[0] = first.toByte()
        readFully(header, 1, 26)

        if (header[0] != 'O'.code.toByte() ||
            header[1] != 'g'.code.toByte() ||
            header[2] != 'g'.code.toByte() ||
            header[3] != 'S'.code.toByte()
        ) {
            throw IllegalStateException("Invalid OggS page capture pattern")
        }

        val version = header[4].toInt() and 0xFF
        val headerType = header[5].toInt() and 0xFF
        val granule = readLeLong(header, 6)
        val serial = readLeInt(header, 14)
        val sequence = readLeInt(header, 18)
        val numSegments = header[26].toInt() and 0xFF

        val segTable = ByteArray(numSegments)
        if (numSegments > 0) readFully(segTable, 0, numSegments)

        var payloadSize = 0
        for (b in segTable) payloadSize += (b.toInt() and 0xFF)
        val payload = ByteArray(payloadSize)
        if (payloadSize > 0) readFully(payload, 0, payloadSize)

        return OggPage(
            version = version,
            headerType = headerType,
            granulePosition = granule,
            serialNumber = serial,
            pageSequence = sequence,
            segmentTable = segTable,
            payload = payload,
        )
    }

    private fun readFully(buf: ByteArray, offset: Int, len: Int) {
        var readTotal = 0
        while (readTotal < len) {
            val r = input.read(buf, offset + readTotal, len - readTotal)
            if (r < 0) throw EOFException("Unexpected EOF while reading Ogg page")
            readTotal += r
        }
    }
}

data class OggPacket(
    val data: ByteArray,
    val granulePosition: Long,
    val isBosPage: Boolean,
    val isEosPage: Boolean,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OggPacket) return false
        return granulePosition == other.granulePosition &&
            isBosPage == other.isBosPage &&
            isEosPage == other.isEosPage &&
            data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + granulePosition.hashCode()
        result = 31 * result + isBosPage.hashCode()
        result = 31 * result + isEosPage.hashCode()
        return result
    }
}

/**
 * Reassembles logical Ogg packets from Ogg pages of a single logical bitstream.
 */
object OggPacketExtractor {
    fun extractPackets(input: InputStream, targetSerial: Int? = null): Pair<Int, List<OggPacket>> {
        val reader = OggPageReader(input)
        val packets = mutableListOf<OggPacket>()
        var streamSerial = targetSerial
        val currentPacket = ByteArrayOutputStream()
        var building = false
        var packetBos = false

        while (true) {
            val page = reader.readNextPage() ?: break
            if (streamSerial == null) {
                streamSerial = page.serialNumber
            } else if (page.serialNumber != streamSerial) {
                continue
            }

            var payloadOffset = 0
            for (segIdx in page.segmentTable.indices) {
                val segLen = page.segmentTable[segIdx].toInt() and 0xFF
                if (!building) {
                    packetBos = page.isBos && packets.isEmpty()
                    building = true
                }
                if (segLen > 0) {
                    currentPacket.write(page.payload, payloadOffset, segLen)
                    payloadOffset += segLen
                }
                if (segLen < 255) {
                    packets += OggPacket(
                        data = currentPacket.toByteArray(),
                        granulePosition = page.granulePosition,
                        isBosPage = packetBos,
                        isEosPage = page.isEos && segIdx == page.segmentTable.lastIndex,
                    )
                    currentPacket.reset()
                    building = false
                }
            }
        }
        return (streamSerial ?: 1) to packets
    }
}

/**
 * Packs packets into valid RFC 3533 Ogg pages, respecting 255-segment & 255-byte lacing limits.
 */
class OggPageMuxer(
    private val output: OutputStream,
    private val serialNumber: Int,
) {
    private var pageSequence = 0

    fun writeSinglePacketPage(
        packet: ByteArray,
        granulePosition: Long,
        bos: Boolean = false,
        eos: Boolean = false,
    ) {
        writePacketsOnPages(
            packets = listOf(packet to granulePosition),
            bos = bos,
            eos = eos,
            maxPacketsPerPage = 1,
        )
    }

    fun writePacketsOnPages(
        packets: List<Pair<ByteArray, Long>>,
        bos: Boolean = false,
        eos: Boolean = false,
        maxPacketsPerPage: Int = 25,
    ) {
        if (packets.isEmpty()) return

        var segList = ArrayList<Byte>(255)
        var payloadBuf = ByteArrayOutputStream()
        var pageGranule = -1L
        var packetsInPage = 0
        var firstPage = bos
        var isContinuation = false

        fun flushPage(markEos: Boolean) {
            if (segList.isEmpty()) return
            var flags = 0
            if (isContinuation) flags = flags or OggPage.FLAG_CONTINUATION
            if (firstPage) flags = flags or OggPage.FLAG_BOS
            if (markEos) flags = flags or OggPage.FLAG_EOS

            val page = OggPage(
                version = 0,
                headerType = flags,
                granulePosition = pageGranule,
                serialNumber = serialNumber,
                pageSequence = pageSequence++,
                segmentTable = segList.toByteArray(),
                payload = payloadBuf.toByteArray(),
            )
            output.write(page.toBytes())
            segList = ArrayList(255)
            payloadBuf = ByteArrayOutputStream()
            pageGranule = -1L
            packetsInPage = 0
            firstPage = false
        }

        for ((pktIdx, entry) in packets.withIndex()) {
            val (pkt, granule) = entry
            val isLastPacketOverall = pktIdx == packets.lastIndex

            // Compute lacing segments for this packet (including trailing 0 if exact multiple of 255)
            val pktSegments = ArrayList<Int>()
            var rem = pkt.size
            while (rem >= 255) {
                pktSegments += 255
                rem -= 255
            }
            pktSegments += rem

            // Flush current page before starting if it cannot hold at least one segment or hit packet cap
            if (packetsInPage >= maxPacketsPerPage || segList.size + pktSegments.size > 255 && packetsInPage > 0) {
                isContinuation = false
                flushPage(markEos = false)
            }

            var pktOffset = 0
            for (sIdx in pktSegments.indices) {
                if (segList.size == 255) {
                    // Packet spans across page boundary: page granule must be -1 if no packet finishes on it
                    val finishedBefore = packetsInPage > 0
                    if (!finishedBefore) pageGranule = -1L
                    flushPage(markEos = false)
                    isContinuation = true
                }
                val segSize = pktSegments[sIdx]
                segList.add(segSize.toByte())
                if (segSize > 0) {
                    payloadBuf.write(pkt, pktOffset, segSize)
                    pktOffset += segSize
                }
                if (sIdx == pktSegments.lastIndex) {
                    pageGranule = granule
                    packetsInPage++
                }
            }
            isContinuation = false

            if (isLastPacketOverall) {
                flushPage(markEos = eos)
            }
        }
    }
}

internal fun readLeInt(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8) or
        ((buf[offset + 2].toInt() and 0xFF) shl 16) or
        ((buf[offset + 3].toInt() and 0xFF) shl 24)

internal fun readLeShort(buf: ByteArray, offset: Int): Int =
    (buf[offset].toInt() and 0xFF) or
        ((buf[offset + 1].toInt() and 0xFF) shl 8)

internal fun readLeLong(buf: ByteArray, offset: Int): Long =
    (buf[offset].toLong() and 0xFFL) or
        ((buf[offset + 1].toLong() and 0xFFL) shl 8) or
        ((buf[offset + 2].toLong() and 0xFFL) shl 16) or
        ((buf[offset + 3].toLong() and 0xFFL) shl 24) or
        ((buf[offset + 4].toLong() and 0xFFL) shl 32) or
        ((buf[offset + 5].toLong() and 0xFFL) shl 40) or
        ((buf[offset + 6].toLong() and 0xFFL) shl 48) or
        ((buf[offset + 7].toLong() and 0xFFL) shl 56)

internal fun writeLeShort(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun writeLeInt(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = (value and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

internal fun writeLeLong(buf: ByteArray, offset: Int, value: Long) {
    for (i in 0 until 8) {
        buf[offset + i] = ((value ushr (i * 8)) and 0xFFL).toByte()
    }
}
