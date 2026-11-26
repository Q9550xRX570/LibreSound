// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.data

object StudioHelpers {

    fun <T> moveListItem(items: List<T>, fromIndex: Int, delta: Int): List<T> {
        if (fromIndex !in items.indices || delta == 0) return items
        val toIndex = (fromIndex + delta).coerceIn(0, items.lastIndex)
        if (toIndex == fromIndex) return items
        val mutable = items.toMutableList()
        val moved = mutable.removeAt(fromIndex)
        mutable.add(toIndex, moved)
        return mutable
    }

    fun sanitizeFileBaseName(raw: String, fallback: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim('.', ' ')
        return cleaned.ifBlank { fallback }
    }

    fun buildSequentialPartName(
        baseName: String,
        indexZeroBased: Int,
        totalCount: Int,
        extension: String,
    ): String {
        val safeBase = sanitizeFileBaseName(baseName, "libreopus_part")
        val width = if (totalCount >= 100) 3 else 2
        val num = (indexZeroBased + 1).toString().padStart(width, '0')
        val cleanExt = extension.trimStart('.').ifBlank { "opus" }
        return "${safeBase}_${num}.$cleanExt"
    }

    fun buildMergedOutputFileName(
        customName: String,
        firstQueueName: String?,
        extension: String,
    ): String {
        val defaultBase = firstQueueName?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?.let { "${it}_merged" }
            ?: "libreopus_merged"
        val base = sanitizeFileBaseName(customName, defaultBase)
        val cleanExt = extension.trimStart('.').ifBlank { "opus" }
        val withoutExt = if (base.endsWith(".$cleanExt", ignoreCase = true)) {
            base.dropLast(cleanExt.length + 1)
        } else {
            base
        }
        return "${withoutExt}.$cleanExt"
    }

    fun clampCrossfadeMs(ms: Long): Long = ms.coerceIn(0L, 5000L)
}
