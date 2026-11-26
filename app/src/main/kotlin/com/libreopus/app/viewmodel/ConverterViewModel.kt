// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreopus.app.LibreOpusApplication
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.app.data.StudioHelpers
import com.libreopus.audio.api.ChannelMode
import com.libreopus.audio.api.ExportConfig
import com.libreopus.audio.api.ExportFormat
import com.libreopus.audio.api.WavBitDepth
import com.libreopus.dsp.effects.GainAndNormalizeProcessor
import com.libreopus.dsp.effects.UtilityProcessors
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class QueueItemStatus { PENDING, PROCESSING, DONE, ERROR }

data class BatchItem(
    val uri: Uri,
    val displayName: String,
    val status: QueueItemStatus = QueueItemStatus.PENDING,
    val progress: Float = 0f,
    val outputName: String? = null,
    val outputContentUri: Uri? = null,
    val outputMimeType: String? = null,
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
    val mergeMode: Boolean = false,
    val crossfadeMs: Long = 0L,
    val mergedOutputName: String = "",
    val mergedProgress: Float = 0f,
    val mergedResultPath: String? = null,
    val mergedResultUri: Uri? = null,
    val mergedResultMimeType: String? = null,
    val mergedError: String? = null,
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
        _state.update {
            it.copy(
                queue = it.queue + added,
                mergedResultPath = null,
                mergedResultUri = null,
                mergedError = null,
            )
        }
    }

    fun removeItem(uri: Uri) {
        if (_state.value.isRunning) return
        _state.update {
            it.copy(
                queue = it.queue.filterNot { item -> item.uri == uri },
                mergedResultPath = null,
                mergedResultUri = null,
                mergedError = null,
            )
        }
    }

    fun moveItem(index: Int, delta: Int) {
        if (_state.value.isRunning) return
        _state.update { st ->
            st.copy(
                queue = StudioHelpers.moveListItem(st.queue, index, delta),
                mergedResultPath = null,
                mergedResultUri = null,
                mergedError = null,
            )
        }
    }

    fun clearQueue() {
        if (_state.value.isRunning) return
        _state.update {
            it.copy(
                queue = emptyList(),
                mergedResultPath = null,
                mergedResultUri = null,
                mergedError = null,
                mergedProgress = 0f,
            )
        }
    }

    fun updateConfig(
        format: ExportFormat = _state.value.targetFormat,
        bitrateKbps: Int = _state.value.bitrateKbps,
        sampleRateHz: Int = _state.value.sampleRateHz,
        channelMode: ChannelMode = _state.value.channelMode,
        wavBitDepth: WavBitDepth = _state.value.wavBitDepth,
        normalizeLufs: Boolean = _state.value.normalizeLufs,
        mergeMode: Boolean = _state.value.mergeMode,
        crossfadeMs: Long = _state.value.crossfadeMs,
        mergedOutputName: String = _state.value.mergedOutputName,
    ) {
        _state.update {
            it.copy(
                targetFormat = format,
                bitrateKbps = bitrateKbps,
                sampleRateHz = sampleRateHz,
                channelMode = channelMode,
                wavBitDepth = wavBitDepth,
                normalizeLufs = normalizeLufs,
                mergeMode = mergeMode,
                crossfadeMs = StudioHelpers.clampCrossfadeMs(crossfadeMs),
                mergedOutputName = mergedOutputName,
            )
        }
    }

    fun startAction() {
        if (_state.value.mergeMode) {
            startMerge()
        } else {
            startBatch()
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
                        val decoded = engine.decodeToPcm(src, itemDir, generateWaveform = false) { p ->
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
                            it.copy(
                                status = QueueItemStatus.DONE,
                                progress = 1f,
                                outputName = "Music/LibreOpus/$outName",
                                outputContentUri = outUri,
                                outputMimeType = snap.targetFormat.mimeType,
                            )
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

    fun startMerge() {
        val snap = _state.value
        if (snap.isRunning || snap.queue.size < 2) return
        batchJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isRunning = true,
                    mergedProgress = 0f,
                    mergedResultPath = null,
                    mergedResultUri = null,
                    mergedResultMimeType = null,
                    mergedError = null,
                )
            }
            val ctx = getApplication<Application>()
            val workRoot = File(ctx.cacheDir, "batch_merge_${System.currentTimeMillis()}").apply { mkdirs() }
            try {
                val timelines = ArrayList<EditTimeline>(snap.queue.size)
                val count = snap.queue.size.coerceAtLeast(1)
                var firstMeta = com.libreopus.audio.api.AudioMetadata()

                for (idx in snap.queue.indices) {
                    val item = snap.queue[idx]
                    updateItem(idx) { it.copy(status = QueueItemStatus.PROCESSING, progress = 0f, error = null) }
                    val itemDir = File(workRoot, "src_$idx").apply { mkdirs() }
                    val src = MediaStorageHelper.buildAudioSource(ctx, item.uri)
                    val decoded = engine.decodeToPcm(src, itemDir, generateWaveform = false) { p ->
                        updateItem(idx) { it.copy(progress = p) }
                        _state.update { st -> st.copy(mergedProgress = ((idx + p) / count) * 0.45f) }
                    }
                    if (idx == 0) {
                        firstMeta = decoded.metadata
                    }
                    timelines.add(
                        EditTimeline.fromSingleFile(
                            decoded.info.sampleRate,
                            decoded.info.channels,
                            decoded.pcmFile,
                            decoded.totalFrames,
                        ),
                    )
                    updateItem(idx) { it.copy(status = QueueItemStatus.DONE, progress = 1f) }
                }

                val targetSr = when {
                    snap.targetFormat == ExportFormat.OPUS || snap.targetFormat == ExportFormat.OGG_OPUS -> 48_000
                    snap.sampleRateHz > 0 -> snap.sampleRateHz
                    else -> timelines.first().sampleRate
                }
                val targetCh = when (snap.channelMode) {
                    ChannelMode.MONO -> 1
                    ChannelMode.STEREO -> 2
                    ChannelMode.KEEP -> timelines.maxOf { it.channels }.coerceIn(1, 2)
                }

                val store = DiskPcmStore(workRoot)
                var mergedTimeline = withContext(Dispatchers.Default) {
                    UtilityProcessors.concatenateWithCrossfade(
                        readers = timelines,
                        store = store,
                        targetSampleRate = targetSr,
                        targetChannels = targetCh,
                        crossfadeMs = snap.crossfadeMs,
                    ) { p ->
                        _state.update { it.copy(mergedProgress = 0.45f + p * 0.30f) }
                    }
                }

                if (snap.normalizeLufs) {
                    mergedTimeline = withContext(Dispatchers.Default) {
                        GainAndNormalizeProcessor.normalizeLufs(
                            timeline = mergedTimeline,
                            store = store,
                            startFrame = 0L,
                            endFrame = mergedTimeline.totalFrames,
                            targetLufs = snap.targetLufs,
                        ) { p ->
                            _state.update { it.copy(mergedProgress = 0.75f + p * 0.10f) }
                        }
                    }
                }

                val outName = StudioHelpers.buildMergedOutputFileName(
                    customName = snap.mergedOutputName,
                    firstQueueName = snap.queue.firstOrNull()?.displayName,
                    extension = snap.targetFormat.extension,
                )
                val encStart = if (snap.normalizeLufs) 0.85f else 0.75f
                val outTitle = outName.substringBeforeLast('.')
                val mergeMeta = firstMeta.copy(title = firstMeta.title.ifBlank { outTitle })

                val (outUri, outStream) = MediaStorageHelper.createAudioExportTarget(
                    context = ctx,
                    fileName = outName,
                    format = snap.targetFormat,
                    targetType = com.libreopus.app.data.ExportTargetType.MUSIC,
                    title = outTitle,
                )
                outStream.buffered().use { out ->
                    engine.exportTimeline(
                        reader = mergedTimeline,
                        config = ExportConfig(
                            format = snap.targetFormat,
                            bitrateKbps = snap.bitrateKbps,
                            sampleRateHz = targetSr,
                            channelMode = snap.channelMode,
                            wavBitDepth = snap.wavBitDepth,
                            metadata = mergeMeta,
                        ),
                        outputStream = out,
                    ) { p ->
                        _state.update { it.copy(mergedProgress = encStart + p * (1f - encStart)) }
                    }
                }
                MediaStorageHelper.finalizeMusicExportTarget(ctx, outUri)

                _state.update {
                    it.copy(
                        mergedProgress = 1f,
                        mergedResultPath = "Music/LibreOpus/$outName",
                        mergedResultUri = outUri,
                        mergedResultMimeType = snap.targetFormat.mimeType,
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(mergedError = t.localizedMessage ?: "Merge failed") }
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
