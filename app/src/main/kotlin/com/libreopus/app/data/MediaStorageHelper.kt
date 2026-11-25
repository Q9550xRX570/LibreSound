// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.libreopus.audio.api.AudioSource
import com.libreopus.audio.api.ExportFormat
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class ContentUriAudioSource(
    private val resolver: ContentResolver,
    val uri: Uri,
    override val displayName: String,
    override val fileSizeBytes: Long,
    override val mimeType: String?,
) : AudioSource {
    override fun openInputStream(): InputStream =
        resolver.openInputStream(uri) ?: throw IllegalStateException("Cannot open URI: $uri")
}

object MediaStorageHelper {

    fun buildAudioSource(context: Context, uri: Uri): ContentUriAudioSource {
        val resolver = context.contentResolver
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "audio"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                        if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
                    }
                }
        }
        return ContentUriAudioSource(
            resolver = resolver,
            uri = uri,
            displayName = name,
            fileSizeBytes = size,
            mimeType = resolver.getType(uri),
        )
    }

    fun tryTakePersistablePermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * Inserts a new audio entry in `Music/LibreOpus` via MediaStore (API 29+ requires zero storage permissions).
     */
    fun createMusicExportTarget(
        context: Context,
        fileName: String,
        format: ExportFormat,
    ): Pair<Uri, OutputStream> {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/LibreOpus")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Failed to create MediaStore export entry")
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IllegalStateException("Cannot open MediaStore output stream")
        return uri to stream
    }

    fun finalizeMusicExportTarget(context: Context, uri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.IS_PENDING, 0)
        }
        context.contentResolver.update(uri, values, null, null)
    }

    fun saveAudioFileToMusic(context: Context, fileName: String, mimeType: String, sourceFile: File) {
        saveDownloadedMediaToStorage(context, fileName, mimeType, sourceFile)
    }

    /**
     * Saves downloaded audio/video into `Music/LibreOpus` or `Movies/LibreOpus` when accepted by MediaStore,
     * falling back seamlessly to `Download/LibreOpus` for containers like WebM/MKV.
     */
    fun saveDownloadedMediaToStorage(
        context: Context,
        fileName: String,
        mimeType: String,
        sourceFile: File,
        isVideo: Boolean = mimeType.startsWith("video/") ||
            fileName.endsWith(".mp4", ignoreCase = true) ||
            fileName.endsWith(".mkv", ignoreCase = true),
    ): String? {
        if (isVideo) {
            val videoSaved = insertAndWrite(
                context = context,
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                relativePath = "${Environment.DIRECTORY_MOVIES}/LibreOpus",
                displayNameCol = MediaStore.Video.Media.DISPLAY_NAME,
                mimeTypeCol = MediaStore.Video.Media.MIME_TYPE,
                relativePathCol = MediaStore.Video.Media.RELATIVE_PATH,
                isPendingCol = MediaStore.Video.Media.IS_PENDING,
                fileName = fileName,
                mimeType = if (mimeType.startsWith("video/")) mimeType else "video/mp4",
                sourceFile = sourceFile,
            )
            if (videoSaved) return "Movies/LibreOpus/$fileName"
        } else {
            val musicSaved = insertAndWrite(
                context = context,
                collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                relativePath = "${Environment.DIRECTORY_MUSIC}/LibreOpus",
                displayNameCol = MediaStore.Audio.Media.DISPLAY_NAME,
                mimeTypeCol = MediaStore.Audio.Media.MIME_TYPE,
                relativePathCol = MediaStore.Audio.Media.RELATIVE_PATH,
                isPendingCol = MediaStore.Audio.Media.IS_PENDING,
                fileName = fileName,
                mimeType = mimeType,
                sourceFile = sourceFile,
            )
            if (musicSaved) return "Music/LibreOpus/$fileName"
        }

        return saveFileToDownloads(context, fileName, mimeType, sourceFile)
    }

    fun saveFileToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        sourceFile: File,
    ): String? {
        val downloadMime = if (mimeType == "audio/*" || mimeType.isBlank()) "application/octet-stream" else mimeType
        val dlSaved = insertAndWrite(
            context = context,
            collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            relativePath = "${Environment.DIRECTORY_DOWNLOADS}/LibreOpus",
            displayNameCol = MediaStore.Downloads.DISPLAY_NAME,
            mimeTypeCol = MediaStore.Downloads.MIME_TYPE,
            relativePathCol = MediaStore.Downloads.RELATIVE_PATH,
            isPendingCol = MediaStore.Downloads.IS_PENDING,
            fileName = fileName,
            mimeType = downloadMime,
            sourceFile = sourceFile,
        )
        return if (dlSaved) "Download/LibreOpus/$fileName" else null
    }

    fun saveImageToPictures(context: Context, fileName: String, mimeType: String, sourceFile: File): String? {
        val saved = insertAndWrite(
            context = context,
            collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            relativePath = "${Environment.DIRECTORY_PICTURES}/LibreOpus",
            displayNameCol = MediaStore.Images.Media.DISPLAY_NAME,
            mimeTypeCol = MediaStore.Images.Media.MIME_TYPE,
            relativePathCol = MediaStore.Images.Media.RELATIVE_PATH,
            isPendingCol = MediaStore.Images.Media.IS_PENDING,
            fileName = fileName,
            mimeType = mimeType,
            sourceFile = sourceFile,
        )
        return if (saved) "Pictures/LibreOpus/$fileName" else null
    }

    private fun insertAndWrite(
        context: Context,
        collection: Uri,
        relativePath: String,
        displayNameCol: String,
        mimeTypeCol: String,
        relativePathCol: String,
        isPendingCol: String,
        fileName: String,
        mimeType: String,
        sourceFile: File,
    ): Boolean {
        val values = ContentValues().apply {
            put(displayNameCol, fileName)
            put(mimeTypeCol, mimeType)
            put(relativePathCol, relativePath)
            put(isPendingCol, 1)
        }
        val uri = runCatching { context.contentResolver.insert(collection, values) }.getOrNull() ?: return false
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out, 32768) }
            } ?: error("Cannot open MediaStore stream")
            val done = ContentValues().apply { put(isPendingCol, 0) }
            context.contentResolver.update(uri, done, null, null)
            true
        }.getOrElse {
            runCatching { context.contentResolver.delete(uri, null, null) }
            false
        }
    }

    fun createShareIntent(context: Context, tempFile: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

object Formatters {
    fun formatDurationMs(ms: Long, includeMillis: Boolean = true): String {
        val safe = ms.coerceAtLeast(0L)
        val totalSec = safe / 1000L
        val hours = totalSec / 3600L
        val mins = (totalSec % 3600L) / 60L
        val secs = totalSec % 60L
        val millis = (safe % 1000L) / 10L
        return when {
            hours > 0 && includeMillis -> "%d:%02d:%02d.%02d".format(hours, mins, secs, millis)
            hours > 0 -> "%d:%02d:%02d".format(hours, mins, secs)
            includeMillis -> "%02d:%02d.%02d".format(mins, secs, millis)
            else -> "%02d:%02d".format(mins, secs)
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.2f MB".format(mb)
    }

    fun estimateExportSizeBytes(durationMs: Long, format: ExportFormat, bitrateKbps: Int, sampleRate: Int, channels: Int, wavBits: Int): Long {
        if (durationMs <= 0L) return 0L
        val seconds = durationMs / 1000.0
        return when (format) {
            ExportFormat.OPUS, ExportFormat.OGG_OPUS, ExportFormat.M4A ->
                ((bitrateKbps * 1000.0 / 8.0) * seconds).toLong()
            ExportFormat.WAV ->
                (sampleRate * channels * (wavBits / 8) * seconds).toLong() + 44L
            ExportFormat.FLAC ->
                ((sampleRate * channels * 2.0 * seconds) * 0.58).toLong()
        }
    }
}
