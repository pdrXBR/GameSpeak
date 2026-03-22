package com.example.voip.network

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

class JitterBuffer(
    private val targetDelayMs: Long = 100,
    private val maxDelayMs: Long = 200
) {
    private val queue = ConcurrentLinkedQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private var playbackJob: Job? = null
    private val _audioFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 10)
    val audioFlow = _audioFlow.asSharedFlow()

    fun start() {
        if (isRunning.getAndSet(true)) return
        playbackJob = CoroutineScope(Dispatchers.IO).launch {
            while (isRunning.get()) {
                val data = queue.poll()
                if (data != null) {
                    _audioFlow.emit(data)
                } else {
                    delay(5)
                }
            }
        }
    }

    fun offer(data: ByteArray) {
        queue.offer(data)
    }

    fun stop() {
        isRunning.set(false)
        playbackJob?.cancel()
        queue.clear()
    }
}