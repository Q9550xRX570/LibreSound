// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app

import com.libreopus.app.data.Formatters
import com.libreopus.audio.api.ExportFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormattersTest {

    @Test
    fun `formatDurationMs formats milliseconds cleanly`() {
        assertEquals("00:05.25", Formatters.formatDurationMs(5250L, includeMillis = true))
        assertEquals("01:05", Formatters.formatDurationMs(65_000L, includeMillis = false))
        assertEquals("1:01:01", Formatters.formatDurationMs(3_661_000L, includeMillis = false))
    }

    @Test
    fun `estimateExportSizeBytes computes realistic byte sizes`() {
        val opus10s = Formatters.estimateExportSizeBytes(10_000L, ExportFormat.OPUS, 128, 48000, 2, 16)
        assertEquals(160_000L, opus10s)
        val wav1s = Formatters.estimateExportSizeBytes(1_000L, ExportFormat.WAV, 0, 48000, 2, 16)
        assertTrue(wav1s > 192_000L)
    }
}
