// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import java.io.File

enum class DownloadPreset {
    BEST_AUDIO,
    IMAGE_ONLY,
    AUDIO_AND_IMAGE,
    CUSTOM_SCRIPT,
}

data class DownloadedArtifact(
    val audioFile: File? = null,
    val audioDisplayName: String? = null,
    val imageFile: File? = null,
    val imageDisplayName: String? = null,
    val imageMimeType: String? = null,
)

interface MediaDownloader {
    val isAvailable: Boolean

    suspend fun execute(
        context: Context,
        url: String,
        preset: DownloadPreset,
        customArgs: String,
        workDir: File,
        onProgress: (percent: Float, etaSec: Long, line: String) -> Unit,
    ): DownloadedArtifact

    fun cancel()
}

object PresetCommandBuilder {

    fun buildArguments(
        preset: DownloadPreset,
        customArgs: String,
        outputTemplate: String,
    ): List<Pair<String, String?>> {
        val args = mutableListOf<Pair<String, String?>>()
        when (preset) {
            DownloadPreset.BEST_AUDIO -> {
                args += "-f" to "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio"
                args += "--no-playlist" to null
                args += "--no-mtime" to null
                args += "--restrict-filenames" to null
                args += "-o" to outputTemplate
            }
            DownloadPreset.IMAGE_ONLY -> {
                args += "--skip-download" to null
                args += "--write-thumbnail" to null
                args += "--no-playlist" to null
                args += "--no-mtime" to null
                args += "--restrict-filenames" to null
                args += "-o" to outputTemplate
            }
            DownloadPreset.AUDIO_AND_IMAGE -> {
                args += "-f" to "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio"
                args += "--write-thumbnail" to null
                args += "--no-playlist" to null
                args += "--no-mtime" to null
                args += "--restrict-filenames" to null
                args += "-o" to outputTemplate
            }
            DownloadPreset.CUSTOM_SCRIPT -> {
                val tokens = tokenizeArgs(customArgs)
                var i = 0
                var hasOutput = false
                while (i < tokens.size) {
                    val tok = tokens[i]
                    if (tok == "-o" || tok == "--output") {
                        hasOutput = true
                    }
                    if (tok.startsWith("-") && i + 1 < tokens.size && !tokens[i + 1].startsWith("-")) {
                        args += tok to tokens[i + 1]
                        i += 2
                    } else {
                        args += tok to null
                        i++
                    }
                }
                if (!hasOutput) {
                    args += "-o" to outputTemplate
                }
            }
        }
        return args
    }

    fun formatPreview(preset: DownloadPreset): String = when (preset) {
        DownloadPreset.BEST_AUDIO -> "-f bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio --no-playlist"
        DownloadPreset.IMAGE_ONLY -> "--skip-download --write-thumbnail --no-playlist"
        DownloadPreset.AUDIO_AND_IMAGE -> "-f bestaudio --write-thumbnail --no-playlist"
        DownloadPreset.CUSTOM_SCRIPT -> ""
    }

    fun tokenizeArgs(input: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        for (ch in input) {
            when {
                quote != null -> {
                    if (ch == quote) {
                        quote = null
                    } else {
                        cur.append(ch)
                    }
                }
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (cur.isNotEmpty()) {
                        out += cur.toString()
                        cur.clear()
                    }
                }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
