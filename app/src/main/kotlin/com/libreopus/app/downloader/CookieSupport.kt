// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import java.io.File

data class CookieSummary(
    val cookieCount: Int,
    val domains: List<String>,
    val convertedFromJson: Boolean = false,
)

data class CookieParseResult(
    val netscapeText: String,
    val summary: CookieSummary,
)

/**
 * Dependency-free JSON parser (works identically on Android runtime and pure-JVM unit tests
 * without relying on Android stubbed org.json).
 */
internal object SimpleJsonParser {

    fun parse(input: String): Any? {
        val reader = JsonReader(input.removePrefix("\uFEFF"))
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.isAtEnd()) {
            throw IllegalArgumentException("Unexpected trailing JSON content")
        }
        return value
    }

    private class JsonReader(private val src: String) {
        private var pos = 0

        fun isAtEnd(): Boolean = pos >= src.length

        fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) {
                pos++
            }
        }

        fun readValue(): Any? {
            skipWhitespace()
            if (isAtEnd()) throw IllegalArgumentException("Unexpected end of JSON")
            return when (val ch = src[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                '-', in '0'..'9' -> readNumber()
                else -> throw IllegalArgumentException("Unexpected JSON character '$ch' at $pos")
            }
        }

        private fun readObject(): Map<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == '}') {
                pos++
                return map
            }
            while (true) {
                skipWhitespace()
                if (isAtEnd() || src[pos] != '"') {
                    throw IllegalArgumentException("Expected string key at $pos")
                }
                val key = readString()
                skipWhitespace()
                if (isAtEnd() || src[pos] != ':') {
                    throw IllegalArgumentException("Expected ':' after key at $pos")
                }
                pos++
                map[key] = readValue()
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unterminated JSON object")
                when (src[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(): List<Any?> {
            pos++ // '['
            val list = ArrayList<Any?>()
            skipWhitespace()
            if (pos < src.length && src[pos] == ']') {
                pos++
                return list
            }
            while (true) {
                list += readValue()
                skipWhitespace()
                if (isAtEnd()) throw IllegalArgumentException("Unterminated JSON array")
                when (src[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < src.length) {
                val ch = src[pos++]
                when (ch) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) throw IllegalArgumentException("Invalid unicode escape")
                                val hex = src.substring(pos, pos + 4)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> sb.append(esc)
                        }
                    }
                    else -> sb.append(ch)
                }
            }
            throw IllegalArgumentException("Unterminated JSON string")
        }

        private fun readLiteral(expected: String, value: Any?): Any? {
            if (!src.startsWith(expected, pos)) {
                throw IllegalArgumentException("Invalid JSON literal at $pos")
            }
            pos += expected.length
            return value
        }

        private fun readNumber(): Number {
            val start = pos
            if (src[pos] == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            var isFloat = false
            if (pos < src.length && src[pos] == '.') {
                isFloat = true
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                isFloat = true
                pos++
                if (pos < src.length && (src[pos] == '+' || src[pos] == '-')) pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val raw = src.substring(start, pos)
            return if (isFloat) {
                raw.toDoubleOrNull() ?: throw IllegalArgumentException("Invalid number '$raw'")
            } else {
                raw.toLongOrNull()
                    ?: raw.toDoubleOrNull()
                    ?: throw IllegalArgumentException("Invalid number '$raw'")
            }
        }
    }
}

object CookieParser {

    fun parseAndNormalize(rawInput: String): CookieParseResult {
        val cleaned = rawInput.removePrefix("\uFEFF").trim()
        if (cleaned.isBlank()) {
            throw IllegalArgumentException("Cookie input is empty")
        }

        return if (cleaned.startsWith("[") || cleaned.startsWith("{")) {
            parseJsonCookies(cleaned)
        } else {
            parseNetscapeCookies(cleaned)
        }
    }

    private fun parseJsonCookies(jsonText: String): CookieParseResult {
        val parsed = SimpleJsonParser.parse(jsonText)
        val rawItems: List<*> = when (parsed) {
            is List<*> -> parsed
            is Map<*, *> -> (parsed["cookies"] as? List<*>)
                ?: throw IllegalArgumentException("JSON object does not contain a 'cookies' array")
            else -> throw IllegalArgumentException("Unsupported JSON cookie payload")
        }

        val normalizedLines = ArrayList<String>(rawItems.size)
        val domains = LinkedHashSet<String>()

        for (entry in rawItems) {
            val obj = entry as? Map<*, *> ?: continue
            var domain = sanitizeField(obj["domain"]?.toString().orEmpty()).trim()
            val name = sanitizeField(obj["name"]?.toString().orEmpty()).trim()
            val value = sanitizeField(obj["value"]?.toString().orEmpty())
            val path = sanitizeField(obj["path"]?.toString().orEmpty()).trim().ifBlank { "/" }
            val secure = (obj["secure"] as? Boolean) ?: false
            val httpOnly = (obj["httpOnly"] as? Boolean) ?: false
            val hostOnly = obj["hostOnly"] as? Boolean
            val isSession = (obj["session"] as? Boolean) ?: false

            if (domain.isBlank() || name.isBlank() || domain.contains(' ')) continue

            if (hostOnly == false && !domain.startsWith(".")) {
                domain = ".$domain"
            }
            val includeSubdomains = if (domain.startsWith(".")) "TRUE" else "FALSE"
            val netscapeDomain = if (httpOnly) "#HttpOnly_$domain" else domain

            val expirySec = if (isSession) {
                0L
            } else {
                ((obj["expirationDate"] as? Number)?.toLong()
                    ?: (obj["expiry"] as? Number)?.toLong()
                    ?: (obj["expires"] as? Number)?.toLong()
                    ?: 0L).coerceAtLeast(0L)
            }

            normalizedLines += listOf(
                netscapeDomain,
                includeSubdomains,
                path,
                if (secure) "TRUE" else "FALSE",
                expirySec.toString(),
                name,
                value,
            ).joinToString("\t")

            domains += cleanDisplayDomain(domain)
        }

        if (normalizedLines.isEmpty()) {
            throw IllegalArgumentException("No valid cookies found in JSON")
        }

        val text = buildString {
            append("# Netscape HTTP Cookie File\n")
            append("# Converted by LibreOpus\n\n")
            normalizedLines.forEach { line ->
                append(line).append('\n')
            }
        }
        return CookieParseResult(
            netscapeText = text,
            summary = CookieSummary(
                cookieCount = normalizedLines.size,
                domains = domains.toList(),
                convertedFromJson = true,
            ),
        )
    }

    private fun parseNetscapeCookies(rawText: String): CookieParseResult {
        val lines = rawText.replace("\r\n", "\n").replace('\r', '\n').lines()
        val normalizedLines = ArrayList<String>()
        val domains = LinkedHashSet<String>()

        for (rawLine in lines) {
            val line = rawLine.trimEnd()
            if (line.isBlank()) continue
            val isHttpOnlyPrefixed = line.startsWith("#HttpOnly_", ignoreCase = true)
            if (line.startsWith("#") && !isHttpOnlyPrefixed) continue

            val cols = line.split('\t')
            if (cols.size < 6) continue

            val domainCol = cols[0].trim()
            val strippedDomain = if (isHttpOnlyPrefixed) {
                domainCol.substring("#HttpOnly_".length)
            } else {
                domainCol
            }
            if (strippedDomain.isBlank() || strippedDomain.contains(' ') ||
                (!strippedDomain.contains('.') && !strippedDomain.equals("localhost", ignoreCase = true))
            ) {
                continue
            }

            val includeSubdomains = normalizeBoolCol(cols[1]) ?: continue
            val path = cols[2].trim().ifBlank { "/" }
            val secure = normalizeBoolCol(cols[3]) ?: continue
            val expires = cols[4].trim().toLongOrNull() ?: continue
            val name = cols[5].trim()
            if (name.isBlank()) continue
            val value = if (cols.size >= 7) cols.subList(6, cols.size).joinToString("\t") else ""

            val normalizedDomainCol = if (isHttpOnlyPrefixed) "#HttpOnly_$strippedDomain" else strippedDomain
            normalizedLines += listOf(
                normalizedDomainCol,
                includeSubdomains,
                path,
                secure,
                expires.coerceAtLeast(0L).toString(),
                name,
                value,
            ).joinToString("\t")

            domains += cleanDisplayDomain(strippedDomain)
        }

        if (normalizedLines.isEmpty()) {
            throw IllegalArgumentException("No valid Netscape cookie entries found")
        }

        val text = buildString {
            append("# Netscape HTTP Cookie File\n")
            append("# Validated by LibreOpus\n\n")
            normalizedLines.forEach { line ->
                append(line).append('\n')
            }
        }
        return CookieParseResult(
            netscapeText = text,
            summary = CookieSummary(
                cookieCount = normalizedLines.size,
                domains = domains.toList(),
                convertedFromJson = false,
            ),
        )
    }

    private fun normalizeBoolCol(col: String): String? = when (col.trim().uppercase()) {
        "TRUE" -> "TRUE"
        "FALSE" -> "FALSE"
        else -> null
    }

    private fun cleanDisplayDomain(domain: String): String =
        domain.removePrefix("#HttpOnly_").trimStart('.').lowercase()

    private fun sanitizeField(value: String): String =
        value.replace("\t", "").replace("\r", "").replace("\n", "")
}

object CookieFileStore {
    private const val DIR_NAME = "ytdlp"
    private const val FILE_NAME = "cookies.txt"

    private fun cookieFile(context: Context): File {
        val dir = File(context.filesDir, DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    fun saveCookies(context: Context, rawInput: String): CookieSummary {
        val parsed = CookieParser.parseAndNormalize(rawInput)
        val target = cookieFile(context)
        val tmp = File(target.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(parsed.netscapeText, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(parsed.netscapeText, Charsets.UTF_8)
            tmp.delete()
        }
        return parsed.summary
    }

    fun loadSummary(context: Context): CookieSummary? {
        val file = getCookieFileIfPresent(context) ?: return null
        return runCatching {
            CookieParser.parseAndNormalize(file.readText(Charsets.UTF_8)).summary
        }.getOrNull()
    }

    fun getCookieFileIfPresent(context: Context): File? {
        val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)
        return file.takeIf { it.exists() && it.isFile && it.length() > 0L }
    }

    fun clearCookies(context: Context) {
        val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)
        if (file.exists()) file.delete()
    }
}
