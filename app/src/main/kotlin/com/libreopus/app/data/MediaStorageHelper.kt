// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.data

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import com.libreopus.audio.api.AudioSource
import com.libreopus.audio.api.ExportFormat
import java.io.File
import java.io.InputStream
import java.io.OutputStream

enum class ExportTargetType(
    val displayDir: String,
    val ringtoneType: Int?,
) {
    MUSIC("Music/LibreOpus", null),
    RINGTONE("Ringtones/LibreOpus", RingtoneManager.TYPE_RINGTONE),
    NOTIFICATION("Notifications/LibreOpus", RingtoneManager.TYPE_NOTIFICATION),
    ALARM("Alarms/LibreOpus", RingtoneManager.TYPE_ALARM),
    ;

    fun resolveRelativeBaseDir(): String = when (this) {
        MUSIC -> Environment.DIRECTORY_MUSIC ?: "Music"
        RINGTONE -> Environment.DIRECTORY_RINGTONES ?: "Ringtones"
        NOTIFICATION -> Environment.DIRECTORY_NOTIFICATIONS ?: "Notifications"
        ALARM -> Environment.DIRECTORY_ALARMS ?: "Alarms"
    }
}

enum class RingtoneAssignOutcome {
    ASSIGNED,
    NEEDS_PERMISSION,
    FALLBACK_TO_PICKER,
    FAILED,
}

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

    data class ExportTargetSpec(
        val relativePath: String,
        val displayDir: String,
        val isMusic: Boolean,
        val isRingtone: Boolean,
        val isNotification: Boolean,
        val isAlarm: Boolean,
    )

    fun buildExportTargetSpec(targetType: ExportTargetType): ExportTargetSpec = ExportTargetSpec(
        relativePath = "${targetType.resolveRelativeBaseDir()}/LibreOpus",
        displayDir = targetType.displayDir,
        isMusic = targetType == ExportTargetType.MUSIC,
        isRingtone = targetType == ExportTargetType.RINGTONE,
        isNotification = targetType == ExportTargetType.NOTIFICATION,
        isAlarm = targetType == ExportTargetType.ALARM,
    )

    /**
     * Inserts a new audio entry in `Music/LibreOpus` via MediaStore (API 29+ requires zero storage permissions).
     */
    fun createMusicExportTarget(
        context: Context,
        fileName: String,
        format: ExportFormat,
    ): Pair<Uri, OutputStream> = createAudioExportTarget(
        context = context,
        fileName = fileName,
        format = format,
        targetType = ExportTargetType.MUSIC,
        title = "",
    )

    /**
     * Inserts a new audio entry into Music, Ringtones, Notifications, or Alarms under MediaStore.
     */
    fun createAudioExportTarget(
        context: Context,
        fileName: String,
        format: ExportFormat,
        targetType: ExportTargetType,
        title: String = "",
    ): Pair<Uri, OutputStream> {
        val spec = buildExportTargetSpec(targetType)
        val cleanTitle = title.trim().ifBlank { fileName.substringBeforeLast('.') }
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.TITLE, cleanTitle)
            put(MediaStore.Audio.Media.MIME_TYPE, format.mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, spec.relativePath)
            put(MediaStore.Audio.Media.IS_MUSIC, if (spec.isMusic) 1 else 0)
            put(MediaStore.Audio.Media.IS_RINGTONE, if (spec.isRingtone) 1 else 0)
            put(MediaStore.Audio.Media.IS_NOTIFICATION, if (spec.isNotification) 1 else 0)
            put(MediaStore.Audio.Media.IS_ALARM, if (spec.isAlarm) 1 else 0)
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

    fun canWriteSystemSettings(context: Context): Boolean = Settings.System.canWrite(context)

    fun buildManageWriteSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())

    fun buildRingtonePickerIntent(
        targetType: ExportTargetType,
        title: String,
        existingUri: Uri?,
    ): Intent = Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
        putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, targetType.ringtoneType ?: RingtoneManager.TYPE_RINGTONE)
        putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, title)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
        putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
        existingUri?.let { putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, it) }
    }

    fun buildSoundSettingsIntent(): Intent = Intent(Settings.ACTION_SOUND_SETTINGS)

    fun assignDefaultRingtone(
        context: Context,
        uri: Uri,
        targetType: ExportTargetType,
    ): RingtoneAssignOutcome {
        val rType = targetType.ringtoneType ?: return RingtoneAssignOutcome.FAILED
        if (!Settings.System.canWrite(context)) return RingtoneAssignOutcome.NEEDS_PERMISSION
        return try {
            RingtoneManager.setActualDefaultRingtoneUri(context, rType, uri)
            RingtoneAssignOutcome.ASSIGNED
        } catch (_: SecurityException) {
            RingtoneAssignOutcome.FALLBACK_TO_PICKER
        } catch (_: Throwable) {
            RingtoneAssignOutcome.FAILED
        }
    }

    data class SavedStorageEntry(
        val displayPath: String,
        val contentUriString: String?,
    )

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
    ): String? = saveDownloadedMediaDetailed(context, fileName, mimeType, sourceFile, isVideo)?.displayPath

    fun saveDownloadedMediaDetailed(
        context: Context,
        fileName: String,
        mimeType: String,
        sourceFile: File,
        isVideo: Boolean = mimeType.startsWith("video/") ||
            fileName.endsWith(".mp4", ignoreCase = true) ||
            fileName.endsWith(".mkv", ignoreCase = true),
    ): SavedStorageEntry? {
        if (isVideo) {
            val videoUri = insertAndWrite(
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
            if (videoUri != null) {
                return SavedStorageEntry("Movies/LibreOpus/$fileName", videoUri.toString())
            }
        } else {
            val musicUri = insertAndWrite(
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
            if (musicUri != null) {
                return SavedStorageEntry("Music/LibreOpus/$fileName", musicUri.toString())
            }
        }

        return saveFileToDownloadsDetailed(context, fileName, mimeType, sourceFile)
    }

    fun saveFileToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        sourceFile: File,
    ): String? = saveFileToDownloadsDetailed(context, fileName, mimeType, sourceFile)?.displayPath

    fun saveFileToDownloadsDetailed(
        context: Context,
        fileName: String,
        mimeType: String,
        sourceFile: File,
    ): SavedStorageEntry? {
        val downloadMime = if (mimeType == "audio/*" || mimeType.isBlank()) "application/octet-stream" else mimeType
        val dlUri = insertAndWrite(
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
        return dlUri?.let { SavedStorageEntry("Download/LibreOpus/$fileName", it.toString()) }
    }

    fun saveImageToPictures(context: Context, fileName: String, mimeType: String, sourceFile: File): String? {
        val savedUri = insertAndWrite(
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
        return if (savedUri != null) "Pictures/LibreOpus/$fileName" else null
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
    ): Uri? {
        val values = ContentValues().apply {
            put(displayNameCol, fileName)
            put(mimeTypeCol, mimeType)
            put(relativePathCol, relativePath)
            put(isPendingCol, 1)
        }
        val uri = runCatching { context.contentResolver.insert(collection, values) }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out, 32768) }
            } ?: error("Cannot open MediaStore stream")
            val done = ContentValues().apply { put(isPendingCol, 0) }
            context.contentResolver.update(uri, done, null, null)
            uri
        }.getOrElse {
            runCatching { context.contentResolver.delete(uri, null, null) }
            null
        }
    }

    fun createShareUriIntent(context: Context, contentUri: Uri, mimeType: String): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    fun createShareIntent(context: Context, tempFile: File, mimeType: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
        return Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun computeTempCacheSizeBytes(cacheDir: File): Long {
        if (!cacheDir.exists()) return 0L
        var total = 0L
        cacheDir.walkTopDown().forEach { f ->
            if (f.isFile) total += f.length()
        }
        return total
    }

    fun clearTempCache(cacheDir: File, keepPaths: Set<String> = emptySet()): Long {
        if (!cacheDir.exists()) return 0L
        val targetDirs = cacheDir.listFiles()?.filter { entry ->
            entry.name in setOf("sessions", "exports", "recordings", "ytdlp_work") ||
                entry.name.startsWith("batch_")
        } ?: emptyList()
        var freed = 0L
        for (entry in targetDirs) {
            if (entry.absolutePath in keepPaths) continue
            if (entry.isDirectory) {
                entry.listFiles()?.forEach { child ->
                    if (child.absolutePath !in keepPaths) {
                        freed += computeTempCacheSizeBytes(child)
                        child.deleteRecursively()
                    }
                }
                if (entry.listFiles().isNullOrEmpty()) {
                    entry.delete()
                }
            } else if (entry.isFile) {
                freed += entry.length()
                entry.delete()
            }
        }
        return freed
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
