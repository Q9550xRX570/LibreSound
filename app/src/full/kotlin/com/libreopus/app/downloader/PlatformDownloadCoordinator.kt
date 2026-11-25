// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.downloader

import android.content.Context
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.app.data.PreferencesRepository
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

object PlatformDownloadCoordinator {
    @Volatile
    private var instance: FullDownloadCoordinator? = null

    fun get(context: Context): DownloadExecutionCoordinator {
        return instance ?: synchronized(this) {
            instance ?: FullDownloadCoordinator(context.applicationContext).also { instance = it }
        }
    }
}

private class FullDownloadCoordinator(
    private val appContext: Context,
) : DownloadExecutionCoordinator {
    private val downloader = PlatformDownloader.create()
    private val prefsRepo = PreferencesRepository(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(BackgroundDownloadState())
    override val state: StateFlow<BackgroundDownloadState> = _state.asStateFlow()

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeWorkDir: File? = null

    override fun startDownload(
        context: Context,
        url: String,
        preset: DownloadPreset,
        customArgs: String,
        options: DownloadExecutionOptions,
        notificationTitle: String,
    ) {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return

        activeJob?.cancel()
        downloader.cancel()

        val ytdlpRoot = File(appContext.cacheDir, "ytdlp").apply { mkdirs() }
        val workDir = File(ytdlpRoot, System.currentTimeMillis().toString()).apply { mkdirs() }
        val oldDir = activeWorkDir
        activeWorkDir = workDir
        if (oldDir != null && oldDir.absolutePath != workDir.absolutePath) {
            scope.launch {
                runCatching { oldDir.deleteRecursively() }
                ytdlpRoot.listFiles()?.forEach { child ->
                    if (child.isDirectory && child.absolutePath != workDir.absolutePath) {
                        runCatching { child.deleteRecursively() }
                    }
                }
            }
        }

        _state.value = BackgroundDownloadState(
            isRunning = true,
            activeTitle = notificationTitle,
            progress = 0f,
            etaSeconds = -1L,
            logs = emptyList(),
        )

        DownloadForegroundService.start(appContext)

        activeJob = scope.launch {
            try {
                val artifact = downloader.execute(
                    context = appContext,
                    url = trimmedUrl,
                    preset = preset,
                    customArgs = customArgs,
                    workDir = workDir,
                    options = options,
                ) { pct, eta, line ->
                    _state.update { st ->
                        val nextLogs = if (line.isNotBlank()) (st.logs + line).takeLast(80) else st.logs
                        st.copy(
                            progress = (pct / 100f).coerceIn(0f, 1f),
                            etaSeconds = eta,
                            logs = nextLogs,
                        )
                    }
                }

                val savedEntries = artifact.mediaItems.map { media ->
                    val stored = MediaStorageHelper.saveDownloadedMediaDetailed(
                        context = appContext,
                        fileName = media.displayName,
                        mimeType = media.mimeType,
                        sourceFile = media.file,
                        isVideo = media.isVideo,
                    )
                    SavedMediaResult(
                        item = media,
                        savedPath = stored?.displayPath,
                        contentUriString = stored?.contentUriString,
                    )
                }

                val savedImageInfo = if (artifact.saveCoverToPictures) {
                    artifact.imageFile?.let { img ->
                        val mime = artifact.imageMimeType ?: "image/jpeg"
                        MediaStorageHelper.saveImageToPictures(appContext, img.name, mime, img)
                            ?.let { path -> img to path }
                    }
                } else {
                    null
                }
                val savedImage = savedImageInfo?.second

                val persistedCoverPath: String? = artifact.imageFile?.takeIf { it.exists() && it.length() > 0L }?.let { img ->
                    runCatching {
                        val coverDir = File(appContext.filesDir, "ytdlp_covers").apply { mkdirs() }
                        val ext = img.extension.lowercase().ifBlank { "jpg" }
                        val coverTarget = File(coverDir, "cover_${UUID.randomUUID()}.$ext")
                        img.copyTo(coverTarget, overwrite = true)
                        coverTarget.absolutePath
                    }.getOrNull()
                }

                val savedSubs = artifact.subtitleFiles.mapNotNull { sub ->
                    MediaStorageHelper.saveFileToDownloads(appContext, sub.name, "text/plain", sub)
                }

                val status = runCatching { downloader.getEngineStatus(appContext) }.getOrNull()
                val nowMs = System.currentTimeMillis()
                val sourceUrl = artifact.sourceUrl ?: PresetCommandBuilder.parseUrls(trimmedUrl).firstOrNull().orEmpty()

                val newHistoryItems = buildList {
                    savedEntries.forEach { saved ->
                        val media = saved.item
                        val meta = media.metadata ?: artifact.metadata
                        val title = meta?.title?.takeIf { it.isNotBlank() } ?: media.displayName
                        add(
                            DownloadHistoryEntry(
                                id = UUID.randomUUID().toString(),
                                title = title,
                                sourceUrl = sourceUrl,
                                savedPath = saved.savedPath ?: media.displayName,
                                contentUriString = saved.contentUriString,
                                cacheFilePath = media.file.absolutePath,
                                mimeType = media.mimeType,
                                isVideo = media.isVideo,
                                sizeBytes = media.file.length(),
                                timestampMs = nowMs,
                                coverFilePath = persistedCoverPath,
                                artist = meta?.artist.orEmpty(),
                                album = meta?.album.orEmpty(),
                                year = meta?.year.orEmpty(),
                                durationSec = media.durationSec,
                                formatLabel = media.formatLabel,
                            ),
                        )
                    }
                    if (isEmpty() && savedImageInfo != null) {
                        val (imgFile, savedImagePath) = savedImageInfo
                        add(
                            DownloadHistoryEntry(
                                id = UUID.randomUUID().toString(),
                                title = artifact.imageDisplayName ?: imgFile.name,
                                sourceUrl = sourceUrl,
                                savedPath = savedImagePath,
                                contentUriString = null,
                                cacheFilePath = imgFile.absolutePath,
                                mimeType = artifact.imageMimeType ?: "image/jpeg",
                                isVideo = false,
                                sizeBytes = imgFile.length(),
                                timestampMs = nowMs,
                                coverFilePath = persistedCoverPath ?: imgFile.absolutePath,
                                formatLabel = imgFile.extension.uppercase(),
                            ),
                        )
                    }
                }

                if (newHistoryItems.isNotEmpty()) {
                    runCatching {
                        val existingJson = prefsRepo.preferences.first().ytdlpDownloadHistoryJson
                        val existing = DownloadHistoryCodec.decode(existingJson)
                        val merged = (newHistoryItems + existing)
                            .distinctBy { "${it.savedPath}|${it.sourceUrl}" }
                            .take(DownloadHistoryCodec.MAX_ENTRIES)
                        prefsRepo.setYtdlpDownloadHistoryJson(DownloadHistoryCodec.encode(merged))
                        val keptCovers = merged.mapNotNull { it.coverFilePath }.toSet()
                        File(appContext.filesDir, "ytdlp_covers").listFiles()?.forEach { file ->
                            if (file.isFile && file.absolutePath !in keptCovers) {
                                runCatching { file.delete() }
                            }
                        }
                    }
                }

                _state.update { st ->
                    st.copy(
                        isRunning = false,
                        progress = 1f,
                        result = artifact,
                        savedAudioPath = savedEntries.firstOrNull()?.savedPath,
                        savedImagePath = savedImage,
                        savedMediaEntries = savedEntries,
                        savedSubtitlePaths = savedSubs,
                        engineStatus = status ?: st.engineStatus,
                        lastCompletedTimestampMs = nowMs,
                    )
                }
            } catch (ce: CancellationException) {
                _state.update { it.copy(isRunning = false) }
                throw ce
            } catch (t: Throwable) {
                val diag = (t as? YtDlpClassifiedException)?.diagnostic ?: run {
                    val causeMsg = t.cause?.message?.trim().orEmpty()
                    val primaryMsg = t.localizedMessage?.trim().orEmpty()
                    val detail = listOfNotNull(
                        primaryMsg.takeIf { it.isNotBlank() },
                        causeMsg.takeIf { it.isNotBlank() && it != primaryMsg },
                        t::class.java.simpleName.takeIf { it.isNotBlank() },
                    ).joinToString(" | ").ifBlank { "Download failed" }
                    YtDlpErrorClassifier.classify(detail)
                }
                _state.update { st ->
                    val nextLogs = if (diag.rawDetails.isNotBlank()) {
                        (st.logs + diag.rawDetails.lines().filter { it.isNotBlank() }).takeLast(80)
                    } else {
                        st.logs
                    }
                    st.copy(
                        isRunning = false,
                        logs = nextLogs,
                        errorDiagnostic = diag,
                        lastCompletedTimestampMs = System.currentTimeMillis(),
                    )
                }
            }
        }
    }

    override fun cancelDownload() {
        downloader.cancel()
        activeJob?.cancel()
        _state.update { it.copy(isRunning = false) }
    }

    override fun clearOutcome() {
        _state.update { st ->
            st.copy(
                result = null,
                savedAudioPath = null,
                savedImagePath = null,
                savedMediaEntries = emptyList(),
                savedSubtitlePaths = emptyList(),
                errorDiagnostic = null,
            )
        }
    }
}
