package com.example.voip.network

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.*

class NetworkManager {
    private var socket: DatagramSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO)

    // Configurações da Sala
    var serverIp: String = "127.0.0.1" // IP do celular que está com o Termux
    var roomId: String = "SALA01"      // ID da sala (máx 10 caracteres)

    fun start() {
        socket = DatagramSocket()
        isRunning = true
        
        // Loop para receber áudio dos outros
        scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket?.receive(packet)
                    
                    // O servidor manda [ID_SALA(10 bytes)][AUDIO]
                    // Pulamos os 10 primeiros bytes para pegar só o áudio
                    val audioData = packet.data.copyOfRange(10, packet.length)
                    onAudioReceived?.invoke(audioData)
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
    }

    var onAudioReceived: ((ByteArray) -> Unit)? = null

    fun sendAudioFrame(audioData: ByteArray) {
        if (!isRunning) return
        
        scope.launch {
            try {
                // Prepara o Header da Sala (exatamente 10 bytes)
                val header = roomId.padEnd(10, ' ').toByteArray().take(10).toByteArray()
                val fullPacket = header + audioData
                
                val address = InetAddress.getByName(serverIp)
                val packet = DatagramPacket(fullPacket, fullPacket.size, address, 5000)
                socket?.send(packet)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
    }
}
