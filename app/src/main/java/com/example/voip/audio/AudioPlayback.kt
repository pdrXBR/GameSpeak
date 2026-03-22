package com.example.voip.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.voip.network.JitterBuffer
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class AudioPlayback {
    private var audioTrack: AudioTrack? = null
    private val isPlaying = AtomicBoolean(false)
    private var playbackJob: Job? = null
    private val jitterBuffer = JitterBuffer()

    private val sampleRate = 48000
    private val channelConfig = AudioFormat.CHANNEL_OUT_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private var bufferSize = 0

    fun start() {
        if (isPlaying.getAndSet(true)) return

        bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(audioFormat)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        audioTrack?.play()

        jitterBuffer.start()
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            jitterBuffer.audioFlow.collect { audioData ->
                audioTrack?.write(audioData, 0, audioData.size)
            }
        }
    }

    fun playAudio(data: ByteArray) {
        jitterBuffer.offer(data)
    }

    fun stop() {
        isPlaying.set(false)
        playbackJob?.cancel()
        jitterBuffer.stop()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
    }
}