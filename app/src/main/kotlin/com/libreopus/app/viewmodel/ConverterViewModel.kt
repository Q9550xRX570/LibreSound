// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreopus.app.LibreOpusApplication
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.audio.api.ChannelMode
import com.libreopus.audio.api.ExportConfig
import com.libreopus.audio.api.ExportFormat
import com.libreopus.audio.api.WavBitDepth
import com.libreopus.dsp.effects.GainAndNormalizeProcessor
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class QueueItemStatus { PENDING, PROCESSING, DONE, ERROR }

data class BatchItem(
    val uri: Uri,
    val displayName: String,
    val status: QueueItemStatus = QueueItemStatus.PENDING,
    val progress: Float = 0f,
    val outputName: String? = null,
    val error: String? = null,
)

data class ConverterUiState(
    val queue: List<BatchItem> = emptyList(),
    val targetFormat: ExportFormat = ExportFormat.OPUS,
    val bitrateKbps: Int = 128,
    val sampleRateHz: Int = 0,
    val channelMode: ChannelMode = ChannelMode.KEEP,
    val wavBitDepth: WavBitDepth = WavBitDepth.PCM_16,
    val normalizeLufs: Boolean = false,
    val targetLufs: Float = -16f,
    val isRunning: Boolean = false,
)

class ConverterViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LibreOpusApplication
    private val engine = app.audioEngine
    private var batchJob: Job? = null

    private val _state = MutableStateFlow(ConverterUiState())
    val state: StateFlow<ConverterUiState> = _state.asStateFlow()

    fun addFiles(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        val existing = _state.value.queue.map { it.uri }.toSet()
        val added = uris.filterNot { it in existing }.map { uri ->
            MediaStorageHelper.tryTakePersistablePermission(ctx, uri)
            val src = MediaStorageHelper.buildAudioSource(ctx, uri)
            BatchItem(uri = uri, displayName = src.displayName)
        }
        _state.update { it.copy(queue = it.queue + added) }
    }

    fun removeItem(uri: Uri) {
        if (_state.value.isRunning) return
        _state.update { it.copy(queue = it.queue.filterNot { item -> item.uri == uri }) }
    }

    fun clearQueue() {
        if (_state.value.isRunning) return
        _state.update { it.copy(queue = emptyList()) }
    }

    fun updateConfig(
        format: ExportFormat = _state.value.targetFormat,
        bitrateKbps: Int = _state.value.bitrateKbps,
        channelMode: ChannelMode = _state.value.channelMode,
        wavBitDepth: WavBitDepth = _state.value.wavBitDepth,
        normalizeLufs: Boolean = _state.value.normalizeLufs,
    ) {
        _state.update {
            it.copy(
                targetFormat = format,
                bitrateKbps = bitrateKbps,
                channelMode = channelMode,
                wavBitDepth = wavBitDepth,
                normalizeLufs = normalizeLufs,
            )
        }
    }

    fun startBatch() {
        if (_state.value.isRunning || _state.value.queue.isEmpty()) return
        batchJob = viewModelScope.launch {
            _state.update { it.copy(isRunning = true) }
            val ctx = getApplication<Application>()
            val workRoot = File(ctx.cacheDir, "batch_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val snap = _state.value
                for (idx in snap.queue.indices) {
                    val item = _state.value.queue[idx]
                    if (item.status == QueueItemStatus.DONE) continue
                    updateItem(idx) { it.copy(status = QueueItemStatus.PROCESSING, progress = 0f, error = null) }

                    val itemDir = File(workRoot, "item_$idx").apply { mkdirs() }
                    try {
                        val src = MediaStorageHelper.buildAudioSource(ctx, item.uri)
                        val decoded = engine.decodeToPcm(src, itemDir) { p ->
                            updateItem(idx) { it.copy(progress = p * 0.45f) }
                        }
                        var timeline = EditTimeline.fromSingleFile(
                            decoded.info.sampleRate,
                            decoded.info.channels,
                            decoded.pcmFile,
                            decoded.totalFrames,
                        )
                        if (snap.normalizeLufs) {
                            timeline = GainAndNormalizeProcessor.normalizeLufs(
                                timeline = timeline,
                                store = DiskPcmStore(itemDir),
                                startFrame = 0L,
                                endFrame = timeline.totalFrames,
                                targetLufs = snap.targetLufs,
                            ) { p ->
                                updateItem(idx) { it.copy(progress = 0.45f + p * 0.2f) }
                            }
                        }

                        val baseName = item.displayName.substringBeforeLast('.')
                        val outName = "${baseName}_converted.${snap.targetFormat.extension}"
                        val (outUri, outStream) = MediaStorageHelper.createMusicExportTarget(ctx, outName, snap.targetFormat)
                        val encStart = if (snap.normalizeLufs) 0.65f else 0.45f
                        outStream.buffered().use { out ->
                            engine.exportTimeline(
                                reader = timeline,
                                config = ExportConfig(
                                    format = snap.targetFormat,
                                    bitrateKbps = snap.bitrateKbps,
                                    sampleRateHz = snap.sampleRateHz,
                                    channelMode = snap.channelMode,
                                    wavBitDepth = snap.wavBitDepth,
                                    metadata = decoded.metadata,
                                ),
                                outputStream = out,
                            ) { p ->
                                updateItem(idx) { it.copy(progress = encStart + p * (1f - encStart)) }
                            }
                        }
                        MediaStorageHelper.finalizeMusicExportTarget(ctx, outUri)
                        updateItem(idx) {
                            it.copy(status = QueueItemStatus.DONE, progress = 1f, outputName = "Music/LibreOpus/$outName")
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        updateItem(idx) {
                            it.copy(status = QueueItemStatus.ERROR, error = t.localizedMessage ?: "Failed")
                        }
                    } finally {
                        itemDir.deleteRecursively()
                    }
                }
            } finally {
                workRoot.deleteRecursively()
                _state.update { it.copy(isRunning = false) }
            }
        }
    }

    fun cancelBatch() {
        batchJob?.cancel()
        _state.update { it.copy(isRunning = false) }
    }

    private fun updateItem(index: Int, transform: (BatchItem) -> BatchItem) {
        _state.update { st ->
            val list = st.queue.toMutableList()
            if (index in list.indices) list[index] = transform(list[index])
            st.copy(queue = list)
        }
    }
}
