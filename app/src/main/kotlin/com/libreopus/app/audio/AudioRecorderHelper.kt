// SPDX-License-Identifier: GPL-3.0-or-later
package com.libreopus.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class RecorderState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedMs: Long = 0L,
    val rmsLevel: Float = 0f,
    val outputFile: File? = null,
    val errorMessage: String? = null,
)

/**
 * Lightweight 48 kHz mono PCM16 WAV studio recorder using Android [AudioRecord].
 * Streams directly to disk with a 44-byte placeholder header patched cleanly on stop.
 */
class AudioRecorderHelper {

    private val _state = MutableStateFlow(RecorderState())
    val state: StateFlow<RecorderState> = _state.asStateFlow()

    private val lock = Any()

    @Volatile
    private var running = false

    @Volatile
    private var paused = false

    private var workerThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var activeFile: File? = null
    private var totalPcmBytes: Long = 0L

    @SuppressLint("MissingPermission")
    fun start(outputFile: File): Boolean {
        if (running) return false
        outputFile.parentFile?.mkdirs()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            _state.value = RecorderState(errorMessage = "AudioRecord unavailable on this device")
            return false
        }
        val bufferBytes = max(minBuf * 2, 4096)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (t: Throwable) {
            _state.value = RecorderState(errorMessage = t.localizedMessage ?: "Mic init failed")
            return false
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            _state.value = RecorderState(errorMessage = "AudioRecord failed to initialize")
            return false
        }

        val fos = try {
            FileOutputStream(outputFile).apply {
                write(ByteArray(WAV_HEADER_BYTES))
            }
        } catch (t: Throwable) {
            recorder.release()
            _state.value = RecorderState(errorMessage = t.localizedMessage ?: "Cannot create recording file")
            return false
        }

        audioRecord = recorder
        activeFile = outputFile
        totalPcmBytes = 0L
        paused = false
        running = true

        try {
            recorder.startRecording()
        } catch (t: Throwable) {
            running = false
            runCatching { fos.close() }
            runCatching { recorder.release() }
            outputFile.delete()
            _state.value = RecorderState(errorMessage = t.localizedMessage ?: "Failed to start recording")
            return false
        }

        _state.value = RecorderState(
            isRecording = true,
            isPaused = false,
            elapsedMs = 0L,
            rmsLevel = 0f,
            outputFile = outputFile,
        )

        workerThread = thread(start = true, isDaemon = true, name = "LibreOpus-Recorder") {
            val shortBuf = ShortArray(bufferBytes / 2)
            val byteBuf = ByteArray(shortBuf.size * 2)
            try {
                while (running) {
                    if (paused) {
                        _state.update { it.copy(rmsLevel = 0f) }
                        Thread.sleep(40L)
                        continue
                    }
                    val readCount = synchronized(lock) {
                        if (!running || paused) {
                            0
                        } else {
                            recorder.read(shortBuf, 0, shortBuf.size)
                        }
                    }
                    if (readCount > 0) {
                        var bi = 0
                        for (i in 0 until readCount) {
                            val s = shortBuf[i].toInt()
                            byteBuf[bi++] = (s and 0xFF).toByte()
                            byteBuf[bi++] = ((s ushr 8) and 0xFF).toByte()
                        }
                        fos.write(byteBuf, 0, bi)
                        totalPcmBytes += bi
                        val frames = totalPcmBytes / (CHANNELS * 2L)
                        val elapsedMs = (frames * 1000L) / SAMPLE_RATE
                        val rms = computeRmsNormalized(shortBuf, readCount)
                        _state.update {
                            it.copy(
                                elapsedMs = elapsedMs,
                                rmsLevel = rms,
                            )
                        }
                    } else if (readCount == 0) {
                        Thread.sleep(10L)
                    } else {
                        break
                    }
                }
            } catch (_: InterruptedException) {
                // Exiting cleanly
            } catch (t: Throwable) {
                _state.update { it.copy(errorMessage = t.localizedMessage) }
            } finally {
                runCatching { fos.flush() }
                runCatching { fos.close() }
            }
        }
        return true
    }

    fun togglePause() {
        if (!running) return
        val nextPaused = !paused
        synchronized(lock) {
            paused = nextPaused
            val rec = audioRecord
            if (rec != null) {
                runCatching {
                    if (nextPaused) {
                        rec.stop()
                    } else {
                        rec.startRecording()
                    }
                }
            }
        }
        _state.update {
            it.copy(
                isPaused = nextPaused,
                rmsLevel = if (nextPaused) 0f else it.rmsLevel,
            )
        }
    }

    fun stopAndFinalize(): File? {
        running = false
        paused = false
        synchronized(lock) {
            audioRecord?.let { rec ->
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
            audioRecord = null
        }
        runCatching { workerThread?.join(1200L) }
        workerThread = null

        val file = activeFile
        activeFile = null
        val bytes = totalPcmBytes
        totalPcmBytes = 0L

        _state.value = RecorderState()
        if (file == null || !file.exists()) return null
        if (bytes <= 0L) {
            file.delete()
            return null
        }

        val header = buildWavHeader16(CHANNELS, SAMPLE_RATE, bytes)
        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0L)
            raf.write(header)
        }
        return file
    }

    fun discard() {
        running = false
        paused = false
        synchronized(lock) {
            audioRecord?.let { rec ->
                runCatching { rec.stop() }
                runCatching { rec.release() }
            }
            audioRecord = null
        }
        runCatching { workerThread?.join(1200L) }
        workerThread = null

        val file = activeFile
        activeFile = null
        totalPcmBytes = 0L
        _state.value = RecorderState()
        file?.delete()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 1
        private const val WAV_HEADER_BYTES = 44

        fun buildWavHeader16(
            channels: Int,
            sampleRate: Int,
            dataSizeBytes: Long,
        ): ByteArray {
            val clampedCh = channels.coerceIn(1, 8)
            val clampedSr = sampleRate.coerceAtLeast(8000)
            val safeData = dataSizeBytes.coerceIn(0L, 0x7FFF_FFC0L)
            val riffSize = 36L + safeData
            val blockAlign = clampedCh * 2
            val byteRate = clampedSr * blockAlign

            val header = ByteArray(WAV_HEADER_BYTES)
            "RIFF".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 0)
            writeLeInt(header, 4, riffSize.toInt())
            "WAVE".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 8)
            "fmt ".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 12)
            writeLeInt(header, 16, 16)
            writeLeShort(header, 20, 1) // PCM
            writeLeShort(header, 22, clampedCh)
            writeLeInt(header, 24, clampedSr)
            writeLeInt(header, 28, byteRate)
            writeLeShort(header, 32, blockAlign)
            writeLeShort(header, 34, 16)
            "data".toByteArray(StandardCharsets.US_ASCII).copyInto(header, 36)
            writeLeInt(header, 40, safeData.toInt())
            return header
        }

        fun computeRmsNormalized(samples: ShortArray, count: Int): Float {
            val n = count.coerceAtMost(samples.size)
            if (n <= 0) return 0f
            var sumSq = 0.0
            for (i in 0 until n) {
                val norm = samples[i] / 32768.0
                sumSq += norm * norm
            }
            val linear = sqrt(sumSq / n).toFloat()
            return sqrt(linear.coerceIn(0f, 1f))
        }

        private fun writeLeShort(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        }

        private fun writeLeInt(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value and 0xFF).toByte()
            buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        }
    }
}
