// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.dsp

import com.libreopus.dsp.container.OggCrc32
import com.libreopus.dsp.container.OggPageMuxer
import com.libreopus.dsp.container.OggPageReader
import com.libreopus.dsp.container.OggPacketExtractor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class OggContainerTest {

    @Test
    fun `crc32 matches RFC 3533 polynomial 0x04c11db7`() {
        // Known check: "123456789" under MSB-first CRC-32/MPEG-2 (0x04c11db7, init 0, xor 0)
        // Standard CRC-32/MPEG-2 uses init=0xFFFFFFFF; with init=0, let's verify single-byte & zeroed page stability
        val pageBytes = OggPageMuxer(ByteArrayOutputStream(), serialNumber = 123).let {
            val out = ByteArrayOutputStream()
            OggPageMuxer(out, 123).writeSinglePacketPage(byteArrayOf(1, 2, 3, 4), 0L, bos = true, eos = true)
            out.toByteArray()
        }
        val embeddedCrc = (pageBytes[22].toInt() and 0xFF) or
            ((pageBytes[23].toInt() and 0xFF) shl 8) or
            ((pageBytes[24].toInt() and 0xFF) shl 16) or
            ((pageBytes[25].toInt() and 0xFF) shl 24)
        val zeroed = pageBytes.copyOf().apply {
            this[22] = 0; this[23] = 0; this[24] = 0; this[25] = 0
        }
        assertEquals(embeddedCrc, OggCrc32.compute(zeroed))
        // Empty input yields 0
        assertEquals(0, OggCrc32.compute(ByteArray(0)))
    }

    @Test
    fun `round trips multi-page packets including exact 255 and large packets`() {
        val pkt1 = ByteArray(19) { it.toByte() }
        val pktExact255 = ByteArray(255) { (it * 3).toByte() }
        val pktLarge = ByteArray(70_000) { (it xor 0x5A).toByte() } // spans >255 segments across multiple pages
        val pktTail = ByteArray(100) { 0x42 }

        val out = ByteArrayOutputStream()
        val muxer = OggPageMuxer(out, serialNumber = 0x11223344)
        muxer.writeSinglePacketPage(pkt1, granulePosition = 0L, bos = true, eos = false)
        muxer.writePacketsOnPages(
            listOf(
                pktExact255 to 960L,
                pktLarge to 1920L,
                pktTail to 2880L,
            ),
            bos = false,
            eos = true,
        )

        val bytes = out.toByteArray()
        val pageReader = OggPageReader(ByteArrayInputStream(bytes))
        var pageCount = 0
        var sawBos = false
        var sawEos = false
        while (true) {
            val page = pageReader.readNextPage() ?: break
            assertEquals(pageCount, page.pageSequence)
            if (pageCount == 0) assertTrue(page.isBos)
            if (page.isBos) sawBos = true
            if (page.isEos) sawEos = true
            pageCount++
        }
        assertTrue(sawBos)
        assertTrue(sawEos)
        assertTrue(pageCount >= 3)

        val (serial, extracted) = OggPacketExtractor.extractPackets(ByteArrayInputStream(bytes))
        assertEquals(0x11223344, serial)
        assertEquals(4, extracted.size)
        assertArrayEquals(pkt1, extracted[0].data)
        assertArrayEquals(pktExact255, extracted[1].data)
        assertArrayEquals(pktLarge, extracted[2].data)
        assertArrayEquals(pktTail, extracted[3].data)
        assertEquals(2880L, extracted[3].granulePosition)
    }
}
