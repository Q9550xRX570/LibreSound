// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.engine.lite

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import com.libreopus.audio.api.PcmTimelineReader
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AudioPreviewPlayer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var playJob: Job? = null
    private var audioTrack: AudioTrack? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private val _peakLevelNormalized = MutableStateFlow(0f)
    val peakLevelNormalized: StateFlow<Float> = _peakLevelNormalized.asStateFlow()

    @Volatile
    private var playbackSpeed: Float = 1f

    fun setPlaybackSpeed(speed: Float) {
        playbackSpeed = speed.coerceIn(0.5f, 2.0f)
        runCatching {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.playbackParams = PlaybackParams().setSpeed(playbackSpeed)
                }
            }
        }
    }

    fun play(
        reader: PcmTimelineReader,
        startMs: Long,
        endMs: Long,
        loop: Boolean,
        onComplete: () -> Unit = {},
    ) {
        stopInternal()
        if (reader.totalFrames <= 0L || reader.sampleRate <= 0) return

        val sr = reader.sampleRate
        val ch = reader.channels.coerceIn(1, 2)
        val startFrame = ((startMs.coerceAtLeast(0L) * sr) / 1000L).coerceIn(0L, reader.totalFrames)
        val endFrame = ((endMs.coerceAtLeast(startMs) * sr) / 1000L).coerceIn(startFrame, reader.totalFrames)
        if (endFrame <= startFrame) return

        val channelMask = if (ch == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuf = AudioTrack.getMinBufferSize(sr, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4096)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sr)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack = track
        _isPlaying.value = true
        _positionMs.value = startMs

        playJob = scope.launch {
            try {
                track.play()
                if (abs(playbackSpeed - 1f) > 0.01f) {
                    runCatching { track.playbackParams = PlaybackParams().setSpeed(playbackSpeed) }
                }

                val chunkFrames = 1024
                val buf = ShortArray(chunkFrames * ch)
                var cursor = startFrame

                while (isActive) {
                    val remaining = endFrame - cursor
                    if (remaining <= 0L) {
                        if (loop) {
                            cursor = startFrame
                            continue
                        } else {
                            break
                        }
                    }

                    val toRead = min(chunkFrames.toLong(), remaining).toInt()
                    val read = reader.readFrames(cursor, buf, 0, toRead)
                    if (read <= 0) break

                    var peak = 0
                    for (i in 0 until read * ch) {
                        val a = abs(buf[i].toInt())
                        if (a > peak) peak = a
                    }
                    val norm = (peak / 32768f).coerceIn(1e-4f, 1f)
                    val db = (20.0 * ln(norm.toDouble()) / ln(10.0)).toFloat()
                    _peakLevelNormalized.value = ((db + 60f) / 60f).coerceIn(0f, 1f)

                    track.write(buf, 0, read * ch)
                    cursor += read
                    _positionMs.value = (cursor * 1000L) / sr
                }
            } finally {
                _isPlaying.value = false
                _peakLevelNormalized.value = 0f
                runCatching {
                    track.pause()
                    track.flush()
                    track.stop()
                    track.release()
                }
                if (audioTrack === track) audioTrack = null
                onComplete()
            }
        }
    }

    fun pause() {
        stopInternal()
    }

    fun seekPreview(positionMs: Long) {
        _positionMs.value = positionMs.coerceAtLeast(0L)
    }

    fun release() {
        stopInternal()
    }

    private fun stopInternal() {
        playJob?.cancel()
        playJob = null
        _isPlaying.value = false
        _peakLevelNormalized.value = 0f
        audioTrack?.let { track ->
            runCatching {
                track.pause()
                track.flush()
                track.stop()
                track.release()
            }
        }
        audioTrack = null
    }
}
