package com.example.voip.network

import java.nio.ByteBuffer

object UdpPacket {
    private const val HEADER_SIZE = 8 // 4 bytes for ID length, 4 bytes for timestamp

    fun encode(senderId: String, audioData: ByteArray): ByteArray {
        val idBytes = senderId.toByteArray(Charsets.UTF_8)
        val timestamp = System.currentTimeMillis()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + idBytes.size + audioData.size)
        buffer.putInt(idBytes.size)
        buffer.put(idBytes)
        buffer.putLong(timestamp)
        buffer.put(audioData)
        return buffer.array()
    }

    fun decode(data: ByteArray): Pair<String?, ByteArray?> {
        if (data.size < HEADER_SIZE) return null to null
        val buffer = ByteBuffer.wrap(data)
        val idLen = buffer.int
        if (data.size < HEADER_SIZE + idLen) return null to null
        val idBytes = ByteArray(idLen)
        buffer.get(idBytes)
        val senderId = String(idBytes, Charsets.UTF_8)
        val timestamp = buffer.long
        val audioData = ByteArray(data.size - buffer.position())
        buffer.get(audioData)
        return senderId to audioData
    }
}