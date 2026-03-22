package com.example.voip.network

import android.content.Context
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.http.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class NetworkManager private constructor(
    private val context: Context,
    private val mode: Mode,
    private val serverIp: String? = null,
    private val onClientConnected: (String, java.net.SocketAddress) -> Unit,
    private val onClientDisconnected: (String) -> Unit
) {
    enum class Mode { SERVER, CLIENT }

    private var udpSocket: DatagramSocket? = null
    var udpPort: Int = 0
        private set

    private var webSocketServer: ApplicationEngine? = null
    private var webSocketClient: HttpClient? = null
    private var clientSession: DefaultClientWebSocketSession? = null

    // For server: map of clientId -> UDP address
    private val clients = ConcurrentHashMap<String, InetSocketAddress>()
    // For client: server's UDP address
    private var serverUdpAddress: InetSocketAddress? = null

    private var receiveJob: Job? = null
    private var isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var onAudioReceived: ((ByteArray) -> Unit)? = null

    fun setOnAudioReceived(callback: (ByteArray) -> Unit) {
        onAudioReceived = callback
    }

    suspend fun startServer() {
        require(mode == Mode.SERVER)
        isRunning.set(true)

        // Open UDP socket on a random port
        udpSocket = DatagramSocket(0)
        udpPort = udpSocket?.localPort ?: 0

        // Start WebSocket server on port 8080
        webSocketServer = embeddedServer(Netty, port = 8080) {
            install(io.ktor.server.websocket.WebSockets)
            routing {
                webSocket("/signal") {
                    handleWebSocketConnection(this)
                }
            }
        }.start(wait = false)

        // Start UDP receive loop
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning.get() && !isActive.isCancelled) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    udpSocket?.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    val (senderId, audioData) = UdpPacket.decode(data)
                    if (senderId != null && audioData != null) {
                        // Relay to all other clients except sender
                        clients.forEach { (id, addr) ->
                            if (id != senderId) {
                                val outPacket = UdpPacket.encode(senderId, audioData)
                                udpSocket?.send(DatagramPacket(outPacket, outPacket.size, addr))
                            }
                        }
                        // Also deliver locally (for server itself? if server wants to hear, but normally server does not have audio)
                        onAudioReceived?.invoke(audioData)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) e.printStackTrace()
                }
            }
        }
    }

    suspend fun connectToServer() {
        require(mode == Mode.CLIENT)
        isRunning.set(true)

        // Open UDP socket
        udpSocket = DatagramSocket(0)
        udpPort = udpSocket?.localPort ?: 0

        // Connect WebSocket to server
        webSocketClient = HttpClient(CIO) {
            install(io.ktor.client.plugins.websocket.WebSockets)
        }
        clientSession = webSocketClient?.webSocketSession(
            request = io.ktor.client.request.HttpRequestBuilder().url("ws://$serverIp:8080/signal")
        )

        // Send UDP port to server after connection
        clientSession?.send(Frame.Text("$udpPort"))

        // Wait for the server to respond with its UDP port
        val serverUdpPort = clientSession?.incoming?.consumeAsFlow()?.firstOrNull()?.let {
            if (it is Frame.Text) {
                val msg = it.readText()
                if (msg.startsWith("UDP_PORT:")) {
                    msg.substringAfter(":").toIntOrNull()
                } else null
            } else null
        }

        serverUdpPort?.let {
            serverUdpAddress = InetSocketAddress(serverIp, it)
        } ?: throw Exception("Server did not provide UDP port")

        // Start UDP receive loop
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning.get() && !isActive.isCancelled) {
                val packet = DatagramPacket(buffer, buffer.size)
                udpSocket?.receive(packet)
                val data = packet.data.copyOf(packet.length)
                val (_, audioData) = UdpPacket.decode(data)
                if (audioData != null) {
                    onAudioReceived?.invoke(audioData)
                }
            }
        }

        // Keep WebSocket open for future signaling
        scope.launch {
            clientSession?.incoming?.consumeAsFlow()?.collect { frame ->
                if (frame is Frame.Close) {
                    stop()
                }
            }
        }
    }

    fun sendAudio(audioData: ByteArray) {
        if (mode == Mode.CLIENT && serverUdpAddress != null) {
            val packet = UdpPacket.encode(CLIENT_ID, audioData)
            udpSocket?.send(DatagramPacket(packet, packet.size, serverUdpAddress))
        }
    }

    fun sendAudioToAll(audioData: ByteArray) {
        if (mode == Mode.SERVER) {
            val packet = UdpPacket.encode(SERVER_ID, audioData)
            clients.values.forEach { addr ->
                udpSocket?.send(DatagramPacket(packet, packet.size, addr))
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        receiveJob?.cancel()
        udpSocket?.close()
        webSocketServer?.stop(1000, 1000)
        webSocketClient?.close()
        scope.cancel()
    }

    private suspend fun handleWebSocketConnection(session: WebSocketSession) {
        val udpPortMsg = session.receive() as? Frame.Text ?: return
        val clientUdpPort = udpPortMsg.readText().toIntOrNull() ?: return

        val clientIp = session.call.request.origin.remoteAddress.address
        val clientAddress = InetSocketAddress(clientIp, clientUdpPort)
        val clientId = "client_${System.currentTimeMillis()}"
        clients[clientId] = clientAddress
        onClientConnected(clientId, clientAddress)

        session.send(Frame.Text("UDP_PORT:${udpPort}"))

        try {
            for (frame in session.incoming) {
                if (frame is Frame.Close) break
            }
        } finally {
            clients.remove(clientId)
            onClientDisconnected(clientId)
        }
    }

    companion object {
        private const val CLIENT_ID = "client"
        private const val SERVER_ID = "server"

        fun createServer(
            context: Context,
            onClientConnected: (String, java.net.SocketAddress) -> Unit,
            onClientDisconnected: (String) -> Unit
        ): NetworkManager {
            return NetworkManager(context, Mode.SERVER, null, onClientConnected, onClientDisconnected)
        }

        fun createClient(
            context: Context,
            serverIp: String,
            onClientConnected: (String, java.net.SocketAddress) -> Unit,
            onClientDisconnected: (String) -> Unit
        ): NetworkManager {
            return NetworkManager(context, Mode.CLIENT, serverIp, onClientConnected, onClientDisconnected)
        }
    }
}