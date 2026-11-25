// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.libreopus.app.LibreOpusApplication
import com.libreopus.app.R
import com.libreopus.app.data.MediaStorageHelper
import com.libreopus.app.data.PreferencesRepository
import com.libreopus.app.data.RecentFileEntry
import com.libreopus.audio.api.AudioInfo
import com.libreopus.audio.api.AudioMetadata
import com.libreopus.audio.api.ChannelMode
import com.libreopus.audio.api.ExportConfig
import com.libreopus.audio.api.ExportFormat
import com.libreopus.audio.api.LosslessOpusTrimPlan
import com.libreopus.audio.api.WaveformPyramid
import com.libreopus.audio.api.WavBitDepth
import com.libreopus.dsp.effects.ChannelOperation
import com.libreopus.dsp.effects.FadeCurve
import com.libreopus.dsp.effects.FadeProcessor
import com.libreopus.dsp.effects.FilterAndGateProcessor
import com.libreopus.dsp.effects.GainAndNormalizeProcessor
import com.libreopus.dsp.effects.EqualizerProcessor
import com.libreopus.dsp.effects.LiveDspConfig
import com.libreopus.dsp.effects.TimePitchProcessor
import com.libreopus.dsp.effects.UtilityProcessors
import com.libreopus.dsp.storage.AudioSegment
import com.libreopus.dsp.storage.DiskPcmStore
import com.libreopus.dsp.storage.EditTimeline
import com.libreopus.dsp.storage.HistorySnapshot
import com.libreopus.dsp.storage.LosslessOpusState
import com.libreopus.dsp.storage.TimelineHistory
import com.libreopus.dsp.waveform.WaveformPeakGenerator
import com.libreopus.engine.lite.AudioPreviewPlayer
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EditorUiState(
    val hasSession: Boolean = false,
    val info: AudioInfo? = null,
    val metadata: AudioMetadata = AudioMetadata(),
    val timeline: EditTimeline? = null,
    val waveform: WaveformPyramid? = null,
    val durationMs: Long = 0L,
    val selectionStartMs: Long = 0L,
    val selectionEndMs: Long = 0L,
    val playheadMs: Long = 0L,
    val isPlaying: Boolean = false,
    val loopSelection: Boolean = false,
    val playbackSpeed: Float = 1.0f,
    val peakMeter: Float = 0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val undoLabel: String? = null,
    val redoLabel: String? = null,
    val hasClipboard: Boolean = false,
    val isLosslessOpusReady: Boolean = false,
    val losslessSourceFile: File? = null,
    val losslessStartMs: Long = 0L,
    val losslessEndMs: Long = 0L,
    val busyLabel: String? = null,
    val busyProgress: Float = 0f,
)

enum class TrimMarkerTarget { START, CURSOR, END }

sealed class EditorEvent {
    data class Message(val text: String) : EditorEvent()
    data class ShareReady(val file: File, val mimeType: String) : EditorEvent()
}

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as LibreOpusApplication
    private val engine = app.audioEngine
    private val prefsRepo = PreferencesRepository(application)
    private val player = AudioPreviewPlayer()
    private val history = TimelineHistory()

    private var sessionDir: File? = null
    private var pcmStore: DiskPcmStore? = null
    private var clipboard: List<AudioSegment> = emptyList()
    private var activeTaskJob: Job? = null

    private val _state = MutableStateFlow(EditorUiState())
    val state: StateFlow<EditorUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>()
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    init {
        viewModelScope.launch {
            player.isPlaying.collect { playing ->
                _state.update { it.copy(isPlaying = playing) }
            }
        }
        viewModelScope.launch {
            player.positionMs.collect { pos ->
                _state.update { it.copy(playheadMs = pos) }
            }
        }
        viewModelScope.launch {
            player.peakLevelNormalized.collect { level ->
                _state.update { it.copy(peakMeter = level) }
            }
        }
    }

    fun openUri(uri: Uri) {
        val ctx = getApplication<Application>()
        MediaStorageHelper.tryTakePersistablePermission(ctx, uri)
        val source = MediaStorageHelper.buildAudioSource(ctx, uri)
        openSourceInternal(source, recentUriString = uri.toString(), overrideCoverBytes = null, overrideCoverMime = null)
    }

    fun openLocalFile(
        file: File,
        displayName: String,
        coverBytes: ByteArray? = null,
        coverMime: String? = null,
        overrideMetadata: AudioMetadata? = null,
    ) {
        val source = object : com.libreopus.audio.api.AudioSource {
            override val displayName: String = displayName
            override val fileSizeBytes: Long = file.length()
            override val mimeType: String? = null
            override fun openInputStream() = file.inputStream()
        }
        openSourceInternal(
            source = source,
            recentUriString = null,
            overrideCoverBytes = coverBytes,
            overrideCoverMime = coverMime,
            overrideMetadata = overrideMetadata,
        )
    }

    fun applyCoverArtBytes(bytes: ByteArray, mimeType: String) {
        val currentMeta = _state.value.metadata
        updateMetadataInSession(currentMeta.copy(coverArtBytes = bytes, coverArtMimeType = mimeType))
    }

    private fun openSourceInternal(
        source: com.libreopus.audio.api.AudioSource,
        recentUriString: String?,
        overrideCoverBytes: ByteArray?,
        overrideCoverMime: String?,
        overrideMetadata: AudioMetadata? = null,
    ) {
        player.pause()
        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            val ctx = getApplication<Application>()
            val dir = File(ctx.cacheDir, "sessions/${System.currentTimeMillis()}").apply { mkdirs() }
            sessionDir?.deleteRecursively()
            sessionDir = dir
            val store = DiskPcmStore(dir)
            pcmStore = store

            _state.update { it.copy(busyLabel = "decode", busyProgress = 0f) }
            try {
                val decoded = engine.decodeToPcm(source, dir) { p ->
                    _state.update { it.copy(busyProgress = p) }
                }
                val baseMeta = if (overrideMetadata != null) {
                    val d = decoded.metadata
                    d.copy(
                        title = d.title.ifBlank { overrideMetadata.title },
                        artist = d.artist.ifBlank { overrideMetadata.artist },
                        album = d.album.ifBlank { overrideMetadata.album },
                        albumArtist = d.albumArtist.ifBlank { overrideMetadata.albumArtist },
                        year = d.year.ifBlank { overrideMetadata.year },
                        trackNumber = d.trackNumber.ifBlank { overrideMetadata.trackNumber },
                        genre = d.genre.ifBlank { overrideMetadata.genre },
                        comment = d.comment.ifBlank { overrideMetadata.comment },
                        composer = d.composer.ifBlank { overrideMetadata.composer },
                    )
                } else {
                    decoded.metadata
                }
                val initialMeta = if (overrideCoverBytes != null && overrideCoverBytes.isNotEmpty()) {
                    baseMeta.copy(
                        coverArtBytes = overrideCoverBytes,
                        coverArtMimeType = overrideCoverMime ?: "image/jpeg",
                    )
                } else {
                    baseMeta
                }
                val timeline = EditTimeline.fromSingleFile(
                    sampleRate = decoded.info.sampleRate,
                    channels = decoded.info.channels,
                    file = decoded.pcmFile,
                    totalFrames = decoded.totalFrames,
                )
                val lossless = LosslessOpusState.initial(
                    sourceOggFile = decoded.cachedOriginalFile,
                    durationMs = timeline.durationMs,
                    supportsLossless = decoded.info.supportsLosslessOpus,
                )
                history.reset(HistorySnapshot(timeline, initialMeta, lossless, "Open"))
                clipboard = emptyList()

                if (recentUriString != null) {
                    prefsRepo.addRecentFile(
                        RecentFileEntry(
                            uriString = recentUriString,
                            displayName = decoded.info.displayName,
                            formatLabel = decoded.info.containerFormat.displayName,
                            durationMs = timeline.durationMs,
                            sampleRate = decoded.info.sampleRate,
                            bitrateKbps = decoded.info.bitrateKbps,
                        ),
                    )
                }

                _state.value = EditorUiState(
                    hasSession = true,
                    info = decoded.info,
                    metadata = initialMeta,
                    timeline = timeline,
                    waveform = decoded.waveform,
                    durationMs = timeline.durationMs,
                    selectionStartMs = 0L,
                    selectionEndMs = timeline.durationMs,
                    playheadMs = 0L,
                    isLosslessOpusReady = lossless.isLosslessEligible,
                    losslessSourceFile = lossless.sourceOggFile,
                    losslessStartMs = lossless.startMs,
                    losslessEndMs = lossless.endMs,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(t.localizedMessage ?: "Failed to decode audio"))
            }
        }
    }

    fun cancelActiveTask() {
        activeTaskJob?.cancel()
        _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
    }

    fun updateSelection(startMs: Long, endMs: Long) {
        val dur = _state.value.durationMs
        val s = startMs.coerceIn(0L, dur)
        val e = endMs.coerceIn(s, dur)
        _state.update { it.copy(selectionStartMs = s, selectionEndMs = e) }
    }

    fun selectAll() {
        val dur = _state.value.durationMs
        _state.update { it.copy(selectionStartMs = 0L, selectionEndMs = dur) }
    }

    fun seekTo(ms: Long) {
        val st = _state.value
        val clamped = ms.coerceIn(0L, st.durationMs)
        if (st.isPlaying) {
            player.seekWhilePlaying(clamped)
        } else {
            player.seekPreview(clamped)
        }
        _state.update { it.copy(playheadMs = clamped) }
    }

    fun togglePlayPause(selectionOnly: Boolean = false) {
        val st = _state.value
        val tl = st.timeline ?: return
        if (st.isPlaying) {
            player.pause()
            return
        }
        player.clearLiveDsp()
        player.setPlaybackSpeed(st.playbackSpeed)
        val start = if (selectionOnly) st.selectionStartMs else st.playheadMs.let { if (it >= st.durationMs - 30L) 0L else it }
        val end = if (selectionOnly && st.selectionEndMs > st.selectionStartMs + 20L) st.selectionEndMs else st.durationMs
        player.play(tl, start, end, st.loopSelection)
    }

    fun toggleLoop() {
        _state.update { it.copy(loopSelection = !it.loopSelection) }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        player.setPlaybackSpeed(clamped)
        _state.update { it.copy(playbackSpeed = clamped) }
    }

    fun nudgeMarker(target: TrimMarkerTarget, deltaMs: Long) {
        val st = _state.value
        val dur = st.durationMs
        if (dur <= 0L) return
        when (target) {
            TrimMarkerTarget.START -> {
                val maxStart = (st.selectionEndMs - 10L).coerceAtLeast(0L)
                val next = (st.selectionStartMs + deltaMs).coerceIn(0L, maxStart)
                _state.update { it.copy(selectionStartMs = next) }
            }
            TrimMarkerTarget.END -> {
                val minEnd = (st.selectionStartMs + 10L).coerceAtMost(dur)
                val next = (st.selectionEndMs + deltaMs).coerceIn(minEnd, dur)
                _state.update { it.copy(selectionEndMs = next) }
            }
            TrimMarkerTarget.CURSOR -> {
                seekTo(st.playheadMs + deltaMs)
            }
        }
    }

    fun updateLiveDspPreview(config: LiveDspConfig) {
        player.updateLiveDsp(config)
    }

    fun updateLiveSpeedPitchPreview(
        tempo: Float,
        pitchSemitones: Float,
        linked: Boolean,
        bypass: Boolean,
    ) {
        if (bypass) {
            player.setPlaybackSpeedAndPitch(1.0f, 0f, linked = false)
        } else {
            player.setPlaybackSpeedAndPitch(tempo, pitchSemitones, linked)
        }
    }

    fun toggleEffectPreviewPlayback() {
        val st = _state.value
        val tl = st.timeline ?: return
        if (st.isPlaying) {
            player.pause()
            return
        }
        val hasSel = st.selectionEndMs > st.selectionStartMs + 20L
        val start = if (hasSel) st.selectionStartMs else 0L
        val end = if (hasSel) st.selectionEndMs else st.durationMs
        player.play(tl, start, end, loop = true)
    }

    fun stopEffectPreview() {
        player.pause()
        player.clearLiveDsp()
        player.setPlaybackSpeed(_state.value.playbackSpeed)
    }

    fun undo() {
        player.pause()
        val snap = history.undo() ?: return
        applySnapshotToState(snap)
    }

    fun redo() {
        player.pause()
        val snap = history.redo() ?: return
        applySnapshotToState(snap)
    }

    private fun applySnapshotToState(snap: HistorySnapshot, regenerateWaveform: Boolean = true) {
        viewModelScope.launch {
            val wf = if (regenerateWaveform) {
                withContext(Dispatchers.Default) { WaveformPeakGenerator.generate(snap.timeline) }
            } else {
                _state.value.waveform
            }
            _state.update {
                it.copy(
                    timeline = snap.timeline,
                    metadata = snap.metadata,
                    waveform = wf,
                    durationMs = snap.timeline.durationMs,
                    selectionStartMs = 0L,
                    selectionEndMs = snap.timeline.durationMs,
                    playheadMs = 0L,
                    canUndo = history.canUndo,
                    canRedo = history.canRedo,
                    undoLabel = history.undoLabel,
                    redoLabel = history.redoLabel,
                    isLosslessOpusReady = snap.losslessState.isLosslessEligible,
                    losslessSourceFile = snap.losslessState.sourceOggFile,
                    losslessStartMs = snap.losslessState.startMs,
                    losslessEndMs = snap.losslessState.endMs,
                    busyLabel = null,
                    busyProgress = 0f,
                )
            }
        }
    }

    // --- Non-destructive Timeline Edits ---

    fun trimToSelection() = runTimelineEdit("Trim", losslessTransform = { ls, sMs, eMs -> ls.trimmed(sMs, eMs) }) { tl, s, e ->
        tl.trim(s, e)
    }

    fun cutSelection() = runTimelineEdit("Cut", losslessTransform = { ls, _, _ -> ls.invalidated() }) { tl, s, e ->
        tl.cut(s, e)
    }

    fun splitKeepLeft() = runTimelineEdit("Split Left", losslessTransform = { ls, _, _ ->
        val cur = _state.value.playheadMs
        ls.trimmed(0L, cur)
    }) { tl, _, _ ->
        val at = tl.msToFrame(_state.value.playheadMs)
        tl.split(at).first
    }

    fun splitKeepRight() = runTimelineEdit("Split Right", losslessTransform = { ls, _, _ ->
        val cur = _state.value.playheadMs
        ls.trimmed(cur, _state.value.durationMs)
    }) { tl, _, _ ->
        val at = tl.msToFrame(_state.value.playheadMs)
        tl.split(at).second
    }

    fun splitAndExportBothHalves(defaultFormat: ExportFormat, defaultBitrateKbps: Int) {
        val currentSnap = history.current ?: return
        val st = _state.value
        val tl = st.timeline ?: return
        val curMs = st.playheadMs
        val at = tl.msToFrame(curMs)
        if (at <= 0L || at >= tl.totalFrames) {
            viewModelScope.launch {
                _events.emit(EditorEvent.Message(app.getString(R.string.split_invalid_pos)))
            }
            return
        }

        player.pause()
        val (leftTl, rightTl) = tl.split(at)
        val leftLossless = currentSnap.losslessState.trimmed(0L, curMs)
        val rightLossless = currentSnap.losslessState.trimmed(curMs, st.durationMs)

        val effectiveFormat = if (st.isLosslessOpusReady &&
            (defaultFormat == ExportFormat.OPUS || defaultFormat == ExportFormat.OGG_OPUS)
        ) {
            defaultFormat
        } else if (st.isLosslessOpusReady) {
            ExportFormat.OPUS
        } else {
            defaultFormat
        }

        val config = ExportConfig(
            format = effectiveFormat,
            bitrateKbps = defaultBitrateKbps,
            metadata = st.metadata,
            losslessStreamCopy = st.isLosslessOpusReady,
        )

        val baseName = (st.info?.displayName ?: "libreopus_audio").substringBeforeLast('.')
        val ext = config.format.extension
        val part1Name = "${baseName}_part1.$ext"
        val part2Name = "${baseName}_part2.$ext"

        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Split", busyProgress = 0f) }
            try {
                val ctx = getApplication<Application>()
                val leftState = st.copy(
                    isLosslessOpusReady = leftLossless.isLosslessEligible,
                    losslessSourceFile = leftLossless.sourceOggFile,
                    losslessStartMs = leftLossless.startMs,
                    losslessEndMs = leftLossless.endMs,
                )
                val rightState = st.copy(
                    isLosslessOpusReady = rightLossless.isLosslessEligible,
                    losslessSourceFile = rightLossless.sourceOggFile,
                    losslessStartMs = rightLossless.startMs,
                    losslessEndMs = rightLossless.endMs,
                )

                val (uri1, stream1) = MediaStorageHelper.createMusicExportTarget(ctx, part1Name, config.format)
                stream1.buffered().use { out ->
                    runExportPipeline(leftTl, leftState, config, out, progressScale = 0.5f, progressOffset = 0f)
                }
                MediaStorageHelper.finalizeMusicExportTarget(ctx, uri1)

                val (uri2, stream2) = MediaStorageHelper.createMusicExportTarget(ctx, part2Name, config.format)
                stream2.buffered().use { out ->
                    runExportPipeline(rightTl, rightState, config, out, progressScale = 0.5f, progressOffset = 0.5f)
                }
                MediaStorageHelper.finalizeMusicExportTarget(ctx, uri2)

                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(
                    EditorEvent.Message(
                        app.getString(R.string.split_exported_both, "$part1Name, $part2Name"),
                    ),
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(t.localizedMessage ?: "Split export failed"))
            }
        }
    }

    fun silenceSelection() = runTimelineEdit("Silence", losslessTransform = { ls, _, _ -> ls.invalidated() }) { tl, s, e ->
        tl.silenceRange(s, e)
    }

    fun insertSilenceAtCursor(durationMs: Long = 1000L) = runTimelineEdit("Insert Silence", losslessTransform = { ls, _, _ -> ls.invalidated() }) { tl, _, _ ->
        val at = tl.msToFrame(_state.value.playheadMs)
        val frames = (durationMs * tl.sampleRate) / 1000L
        tl.insertSilence(at, frames)
    }

    fun copySelection() {
        val st = _state.value
        val tl = st.timeline ?: return
        val s = tl.msToFrame(st.selectionStartMs)
        val e = tl.msToFrame(st.selectionEndMs)
        clipboard = tl.extractSlice(s, e)
        _state.update { it.copy(hasClipboard = clipboard.isNotEmpty()) }
    }

    fun pasteAtCursor() {
        if (clipboard.isEmpty()) return
        runTimelineEdit("Paste", losslessTransform = { ls, _, _ -> ls.invalidated() }) { tl, _, _ ->
            val at = tl.msToFrame(_state.value.playheadMs)
            tl.pasteAt(at, clipboard)
        }
    }

    private fun runTimelineEdit(
        label: String,
        losslessTransform: (LosslessOpusState, Long, Long) -> LosslessOpusState,
        op: (EditTimeline, Long, Long) -> EditTimeline,
    ) {
        val currentSnap = history.current ?: return
        val st = _state.value
        player.pause()
        val sFrame = currentSnap.timeline.msToFrame(st.selectionStartMs)
        val eFrame = currentSnap.timeline.msToFrame(st.selectionEndMs)
        val nextTl = op(currentSnap.timeline, sFrame, eFrame)
        val nextLossless = losslessTransform(currentSnap.losslessState, st.selectionStartMs, st.selectionEndMs)
        val nextSnap = HistorySnapshot(nextTl, currentSnap.metadata, nextLossless, label)
        history.push(nextSnap)
        applySnapshotToState(nextSnap)
    }

    // --- DSP Processors ---

    fun applyGain(gainDb: Float) = runDspEffect("Gain") { tl, store, s, e, prog ->
        GainAndNormalizeProcessor.applyGain(tl, store, s, e, gainDb, prog)
    }

    fun normalizePeak(targetDbfs: Float = -1f) = runDspEffect("Peak Normalize") { tl, store, s, e, prog ->
        GainAndNormalizeProcessor.normalizePeak(tl, store, s, e, targetDbfs, prog)
    }

    fun normalizeLufs(targetLufs: Float = -14f) = runDspEffect("LUFS Normalize") { tl, store, s, e, prog ->
        GainAndNormalizeProcessor.normalizeLufs(tl, store, s, e, targetLufs, prog)
    }

    fun applyFade(fadeIn: Boolean, curve: FadeCurve) = runDspEffect(if (fadeIn) "Fade In" else "Fade Out") { tl, store, s, e, prog ->
        FadeProcessor.applyFade(tl, store, s, e, fadeIn, curve, prog)
    }

    fun applyEq(bandGainsDb: List<Float>) = runDspEffect("7-Band EQ") { tl, store, s, e, prog ->
        EqualizerProcessor.applyEq(tl, store, s, e, bandGainsDb, prog)
    }

    fun applySpeedAndPitch(tempo: Float, pitchSemitones: Float, linked: Boolean) = runDspEffect("Speed & Pitch") { tl, store, s, e, prog ->
        TimePitchProcessor.applySpeedAndPitch(tl, store, s, e, tempo, pitchSemitones, linked, prog)
    }

    fun applyFilterAndGate(
        highPassHz: Float?,
        lowPassHz: Float?,
        applyGate: Boolean,
        gateThresholdDb: Float,
    ) = runDspEffect("Filter & Gate") { tl, store, s, e, prog ->
        var cur = tl
        if (highPassHz != null || lowPassHz != null) {
            cur = FilterAndGateProcessor.applyHighLowPass(cur, store, s, e, highPassHz, lowPassHz) { p ->
                prog(if (applyGate) p * 0.5f else p)
            }
        }
        if (applyGate) {
            cur = FilterAndGateProcessor.applyNoiseGate(cur, store, s, e, thresholdDb = gateThresholdDb) { p ->
                prog(if (highPassHz != null || lowPassHz != null) 0.5f + p * 0.5f else p)
            }
        }
        cur
    }

    fun trimSilence(thresholdDb: Float, minSilenceMs: Int) = runDspEffect("Trim Silence") { tl, store, s, e, prog ->
        FilterAndGateProcessor.trimSilence(tl, store, s, e, thresholdDb, minSilenceMs, keepPaddingMs = 35, onProgress = prog)
    }

    fun reverseSelection() = runDspEffect("Reverse") { tl, store, s, e, prog ->
        UtilityProcessors.reverse(tl, store, s, e, prog)
    }

    fun applyChannelOperation(op: ChannelOperation) = runDspEffect("Channels") { tl, store, _, _, prog ->
        UtilityProcessors.applyChannelOperation(tl, store, op, prog)
    }

    fun appendAudioUri(uri: Uri) = runExternalAudioMerge("Append Audio", uri) { baseTl, overlayTl, _, prog ->
        val adapted = if (overlayTl.sampleRate != baseTl.sampleRate || overlayTl.channels != baseTl.channels) {
            val store = pcmStore ?: return@runExternalAudioMerge baseTl
            UtilityProcessors.mixOverlay(
                baseTimeline = baseTl,
                overlayReader = overlayTl,
                store = store,
                offsetFrames = baseTl.totalFrames,
                onProgress = prog,
            )
        } else {
            prog(1f)
            baseTl.append(overlayTl.segments)
        }
        adapted
    }

    fun mixOverlayUri(uri: Uri, offsetMs: Long, baseGainDb: Float, overlayGainDb: Float) =
        runExternalAudioMerge("Mix / Overlay", uri) { baseTl, overlayTl, store, prog ->
            val offsetFrames = ((offsetMs.coerceAtLeast(0L) * baseTl.sampleRate) / 1000L)
            UtilityProcessors.mixOverlay(
                baseTimeline = baseTl,
                overlayReader = overlayTl,
                store = store,
                offsetFrames = offsetFrames,
                baseGainDb = baseGainDb,
                overlayGainDb = overlayGainDb,
                onProgress = prog,
            )
        }

    private fun runExternalAudioMerge(
        label: String,
        uri: Uri,
        block: suspend (EditTimeline, EditTimeline, DiskPcmStore, (Float) -> Unit) -> EditTimeline,
    ) {
        val currentSnap = history.current ?: return
        val store = pcmStore ?: return
        val dir = sessionDir ?: return
        player.pause()
        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _state.update { it.copy(busyLabel = label, busyProgress = 0f) }
            try {
                val source = MediaStorageHelper.buildAudioSource(getApplication(), uri)
                val subDir = File(dir, "extra_${System.currentTimeMillis()}").apply { mkdirs() }
                val decoded = engine.decodeToPcm(source, subDir) { p ->
                    _state.update { it.copy(busyProgress = p * 0.5f) }
                }
                val otherTl = EditTimeline.fromSingleFile(decoded.info.sampleRate, decoded.info.channels, decoded.pcmFile, decoded.totalFrames)
                val nextTl = withContext(Dispatchers.Default) {
                    block(currentSnap.timeline, otherTl, store) { p ->
                        _state.update { it.copy(busyProgress = 0.5f + p * 0.5f) }
                    }
                }
                val nextSnap = HistorySnapshot(nextTl, currentSnap.metadata, currentSnap.losslessState.invalidated(), label)
                history.push(nextSnap)
                applySnapshotToState(nextSnap)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(t.localizedMessage ?: "Merge failed"))
            }
        }
    }

    private fun runDspEffect(
        label: String,
        block: suspend (EditTimeline, DiskPcmStore, Long, Long, (Float) -> Unit) -> EditTimeline,
    ) {
        val currentSnap = history.current ?: return
        val store = pcmStore ?: return
        val st = _state.value
        player.pause()
        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _state.update { it.copy(busyLabel = label, busyProgress = 0f) }
            try {
                val s = currentSnap.timeline.msToFrame(st.selectionStartMs)
                val e = currentSnap.timeline.msToFrame(st.selectionEndMs)
                val nextTl = withContext(Dispatchers.Default) {
                    block(currentSnap.timeline, store, s, e) { p ->
                        _state.update { it.copy(busyProgress = p) }
                    }
                }
                val nextSnap = HistorySnapshot(
                    timeline = nextTl,
                    metadata = currentSnap.metadata,
                    losslessState = currentSnap.losslessState.invalidated(),
                    actionLabel = label,
                )
                history.push(nextSnap)
                applySnapshotToState(nextSnap)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(t.localizedMessage ?: "Effect failed"))
            }
        }
    }

    // --- Metadata & Export ---

    fun updateMetadataInSession(metadata: AudioMetadata) {
        val currentSnap = history.current ?: return
        val nextSnap = currentSnap.copy(metadata = metadata, actionLabel = "Edit Tags")
        history.push(nextSnap)
        applySnapshotToState(nextSnap, regenerateWaveform = false)
    }

    fun saveTagsLosslesslyToMusic(metadata: AudioMetadata) {
        val st = _state.value
        val info = st.info ?: return
        val srcFile = st.losslessSourceFile ?: return
        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Tags", busyProgress = 0.5f) }
            try {
                val baseName = info.displayName.substringBeforeLast('.')
                val ext = info.containerFormat.extension
                val outName = "${baseName}_tagged.$ext"
                val exportFmt = if (info.containerFormat == com.libreopus.audio.api.AudioContainerFormat.FLAC) ExportFormat.FLAC else ExportFormat.OPUS
                val ctx = getApplication<Application>()
                val (uri, stream) = MediaStorageHelper.createMusicExportTarget(ctx, outName, exportFmt)
                val ok = stream.use { out ->
                    engine.rewriteMetadataLossless(srcFile, info.containerFormat, metadata, out)
                }
                MediaStorageHelper.finalizeMusicExportTarget(ctx, uri)
                updateMetadataInSession(metadata)
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                if (ok) {
                    _events.emit(EditorEvent.Message("Music/LibreOpus/$outName"))
                } else {
                    _events.emit(EditorEvent.Message("Tag rewrite unsupported for this container"))
                }
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(t.localizedMessage ?: "Tag save failed"))
            }
        }
    }

    fun exportToMusic(config: ExportConfig, shareAfter: Boolean = false) {
        val st = _state.value
        val tl = st.timeline ?: return
        val baseName = (st.info?.displayName ?: "libreopus_audio").substringBeforeLast('.')
        val outName = "${baseName}_edited.${config.format.extension}"

        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Export", busyProgress = 0f) }
            try {
                val ctx = getApplication<Application>()
                if (shareAfter) {
                    val shareDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
                    val outFile = File(shareDir, outName)
                    FileOutputStream(outFile).buffered().use { out ->
                        runExportPipeline(tl, st, config, out)
                    }
                    _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                    _events.emit(EditorEvent.ShareReady(outFile, config.format.mimeType))
                } else {
                    val (uri, stream) = MediaStorageHelper.createMusicExportTarget(ctx, outName, config.format)
                    stream.buffered().use { out ->
                        runExportPipeline(tl, st, config, out)
                    }
                    MediaStorageHelper.finalizeMusicExportTarget(ctx, uri)
                    _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                    _events.emit(EditorEvent.Message("Music/LibreOpus/$outName"))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(t.localizedMessage ?: "Export failed"))
            }
        }
    }

    fun exportToSafUri(uri: Uri, config: ExportConfig) {
        val st = _state.value
        val tl = st.timeline ?: return
        activeTaskJob?.cancel()
        activeTaskJob = viewModelScope.launch {
            _state.update { it.copy(busyLabel = "Export", busyProgress = 0f) }
            try {
                val ctx = getApplication<Application>()
                val stream = ctx.contentResolver.openOutputStream(uri)
                    ?: throw IllegalStateException("Cannot open target document")
                stream.buffered().use { out ->
                    runExportPipeline(tl, st, config, out)
                }
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(uri.lastPathSegment ?: "Saved"))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                _state.update { it.copy(busyLabel = null, busyProgress = 0f) }
                _events.emit(EditorEvent.Message(t.localizedMessage ?: "Export failed"))
            }
        }
    }

    private suspend fun runExportPipeline(
        timeline: EditTimeline,
        st: EditorUiState,
        config: ExportConfig,
        outputStream: java.io.OutputStream,
        progressScale: Float = 1f,
        progressOffset: Float = 0f,
    ) {
        val losslessFile = st.losslessSourceFile
        val canLossless = config.losslessStreamCopy &&
            st.isLosslessOpusReady &&
            losslessFile != null &&
            (config.format == ExportFormat.OPUS || config.format == ExportFormat.OGG_OPUS) &&
            config.channelMode == ChannelMode.KEEP &&
            config.normalizeTargetLufs == null

        if (canLossless) {
            engine.exportLosslessOpus(
                plan = LosslessOpusTrimPlan(
                    sourceOggFile = losslessFile,
                    startMs = st.losslessStartMs,
                    endMs = st.losslessEndMs,
                ),
                metadata = config.metadata,
                outputStream = outputStream,
            ) { p -> _state.update { it.copy(busyProgress = (progressOffset + p * progressScale).coerceIn(0f, 1f)) } }
        } else {
            val store = pcmStore
            val lufsTarget = config.normalizeTargetLufs
            val effectiveTimeline = if (lufsTarget != null && store != null) {
                GainAndNormalizeProcessor.normalizeLufs(
                    timeline = timeline,
                    store = store,
                    startFrame = 0L,
                    endFrame = timeline.totalFrames,
                    targetLufs = lufsTarget,
                ) { p -> _state.update { it.copy(busyProgress = (progressOffset + (p * 0.35f) * progressScale).coerceIn(0f, 1f)) } }
            } else {
                timeline
            }
            val startProg = if (config.normalizeTargetLufs != null) 0.35f else 0f
            engine.exportTimeline(effectiveTimeline, config, outputStream) { p ->
                val scaled = progressOffset + (startProg + p * (1f - startProg)) * progressScale
                _state.update { it.copy(busyProgress = scaled.coerceIn(0f, 1f)) }
            }
        }
    }

    override fun onCleared() {
        player.release()
        sessionDir?.deleteRecursively()
    }
}
