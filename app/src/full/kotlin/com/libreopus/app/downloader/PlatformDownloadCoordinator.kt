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

                val savedImage = artifact.imageFile?.let { img ->
                    val mime = artifact.imageMimeType ?: "image/jpeg"
                    MediaStorageHelper.saveImageToPictures(appContext, img.name, mime, img)
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
                        val title = media.metadata?.title?.takeIf { it.isNotBlank() } ?: media.displayName
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
                            ),
                        )
                    }
                    if (isEmpty() && savedImage != null && artifact.imageFile != null) {
                        add(
                            DownloadHistoryEntry(
                                id = UUID.randomUUID().toString(),
                                title = artifact.imageDisplayName ?: artifact.imageFile.name,
                                sourceUrl = sourceUrl,
                                savedPath = savedImage,
                                contentUriString = null,
                                cacheFilePath = artifact.imageFile.absolutePath,
                                mimeType = artifact.imageMimeType ?: "image/jpeg",
                                isVideo = false,
                                sizeBytes = artifact.imageFile.length(),
                                timestampMs = nowMs,
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
                val diag = (t as? YtDlpClassifiedException)?.diagnostic
                    ?: YtDlpErrorClassifier.classify(t.localizedMessage ?: "Download failed")
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
