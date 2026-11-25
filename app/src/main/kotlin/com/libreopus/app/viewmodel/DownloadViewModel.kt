// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.app.downloader.DownloadPreset
import com.libreopus.app.downloader.DownloadedArtifact
import com.libreopus.app.downloader.PlatformDownloader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DownloadUiState(
    val url: String = "",
    val preset: DownloadPreset = DownloadPreset.BEST_AUDIO,
    val customArgs: String = "-f bestaudio --no-playlist",
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val etaSeconds: Long = -1L,
    val logs: List<String> = emptyList(),
    val result: DownloadedArtifact? = null,
    val savedAudioPath: String? = null,
    val savedImagePath: String? = null,
    val error: String? = null,
)

class DownloadViewModel(application: Application) : AndroidViewModel(application) {
    private val downloader = PlatformDownloader.create()
    private var downloadJob: Job? = null
    private var activeWorkDir: File? = null

    private val _state = MutableStateFlow(DownloadUiState())
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    fun setUrl(url: String) {
        _state.update { it.copy(url = url, error = null) }
    }

    fun selectPreset(preset: DownloadPreset) {
        _state.update { it.copy(preset = preset) }
    }

    fun setCustomArgs(args: String) {
        _state.update { it.copy(customArgs = args) }
    }

    fun startDownload() {
        val current = _state.value
        val trimmedUrl = current.url.trim()
        if (trimmedUrl.isBlank() || current.isRunning) return

        val ctx = getApplication<Application>()
        val dir = File(ctx.cacheDir, "ytdlp/${System.currentTimeMillis()}").apply { mkdirs() }
        activeWorkDir?.deleteRecursively()
        activeWorkDir = dir

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isRunning = true,
                    progress = 0f,
                    etaSeconds = -1L,
                    logs = emptyList(),
                    result = null,
                    savedAudioPath = null,
                    savedImagePath = null,
                    error = null,
                )
            }
            try {
                val artifact = downloader.execute(
                    context = ctx,
                    url = trimmedUrl,
                    preset = current.preset,
                    customArgs = current.customArgs,
                    workDir = dir,
                ) { pct, eta, line ->
                    _state.update { st ->
                        val nextLogs = if (line.isNotBlank()) (st.logs + line).takeLast(40) else st.logs
                        st.copy(
                            progress = (pct / 100f).coerceIn(0f, 1f),
                            etaSeconds = eta,
                            logs = nextLogs,
                        )
                    }
                }

                var savedAudio: String? = null
                var savedImage: String? = null
                artifact.audioFile?.let { af ->
                    val ext = af.extension.lowercase()
                    val mime = when (ext) {
                        "opus" -> "audio/opus"
                        "ogg" -> "audio/ogg"
                        "webm" -> "audio/webm"
                        "m4a", "aac" -> "audio/mp4"
                        "mp3" -> "audio/mpeg"
                        "flac" -> "audio/flac"
                        "wav" -> "audio/wav"
                        else -> "audio/*"
                    }
                    MediaStorageHelper.saveAudioFileToMusic(ctx, af.name, mime, af)
                    savedAudio = "Music/LibreOpus/${af.name}"
                }
                artifact.imageFile?.let { img ->
                    val mime = artifact.imageMimeType ?: "image/jpeg"
                    MediaStorageHelper.saveImageToPictures(ctx, img.name, mime, img)
                    savedImage = "Pictures/LibreOpus/${img.name}"
                }

                _state.update {
                    it.copy(
                        isRunning = false,
                        progress = 1f,
                        result = artifact,
                        savedAudioPath = savedAudio,
                        savedImagePath = savedImage,
                    )
                }
            } catch (ce: CancellationException) {
                _state.update { it.copy(isRunning = false) }
                throw ce
            } catch (t: Throwable) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        error = t.localizedMessage ?: "Download failed",
                    )
                }
            }
        }
    }

    fun cancelDownload() {
        downloader.cancel()
        downloadJob?.cancel()
        _state.update { it.copy(isRunning = false) }
    }

    override fun onCleared() {
        downloader.cancel()
        activeWorkDir?.deleteRecursively()
    }
}
