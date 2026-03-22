package com.example.voip.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioCapture(
    private val onAudioData: (ByteArray) -> Unit,
    private val onAudioLevel: (Float) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    fun start() {
        if (isRecording.get()) return

        bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioCapture", "Failed to initialize AudioRecord")
            return
        }

        audioRecord?.startRecording()
        isRecording.set(true)

        recordingJob = scope.launch {
            val buffer = ByteArray(bufferSize)
            while (isRecording.get() && !isActive.isCancelled) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val audioData = buffer.copyOf(read)
                    onAudioData(audioData)

                    // Compute RMS audio level (0-1)
                    val level = calculateRmsLevel(audioData)
                    onAudioLevel(level)
                }
                yield()
            }
        }
    }

    fun stop() {
        isRecording.set(false)
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun calculateRmsLevel(data: ByteArray): Float {
        var sum = 0L
        for (i in data.indices step 2) {
            val sample = (data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)
            sum += (sample * sample).toLong()
        }
        val rms = Math.sqrt(sum.toDouble() / (data.size / 2))
        return (rms / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}