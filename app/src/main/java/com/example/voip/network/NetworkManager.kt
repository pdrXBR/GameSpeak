package com.example.voip.network

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.http.cio.websocket.Frame
import io.ktor.http.cio.websocket.readText
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.send
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.firstOrNull
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

    private var webSocketServer: io.ktor.server.engine.ApplicationEngine? = null
    private var webSocketClient: HttpClient? = null
    private var clientSession: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession? = null

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
            install(WebSockets) // no config needed
            routing {
                webSocket("/signal") { session ->
                    handleWebSocketConnection(session)
                }
            }
        }.start(wait = false)

        // Start UDP receive loop
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning.get() && isActive) {
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
            install(WebSockets)
        }
        clientSession = webSocketClient?.webSocketSession {
            url("ws://$serverIp:8080/signal")
        }

        // Send UDP port to server after connection
        clientSession?.send(Frame.Text("$udpPort"))

        // Wait for the server to respond with its UDP port
        val serverUdpPort = clientSession?.incoming?.consumeAsFlow()?.firstOrNull()?.let { frame ->
            if (frame is Frame.Text) {
                val msg = frame.readText()
                if (msg.startsWith("UDP_PORT:")) {
                    msg.substringAfter(":").toIntOrNull()
                } else null
            } else null
        }

        serverUdpPort?.let {
            serverUdpAddress = InetSocketAddress(serverIp!!, it)
        } ?: throw Exception("Server did not provide UDP port")

        // Start UDP receive loop
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning.get() && isActive) {
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

    private suspend fun handleWebSocketConnection(session: DefaultWebSocketServerSession) {
        val udpPortMsg = session.receive() as? Frame.Text ?: return
        val clientUdpPort = udpPortMsg.readText().toIntOrNull() ?: return

        // Get client IP address from the WebSocket session
        val clientAddressObj = session.call.request.origin.remoteAddress ?: return
        val clientIp = (clientAddressObj as? InetSocketAddress)?.address ?: return
        val clientAddress = InetSocketAddress(clientIp, clientUdpPort)
        val clientId = "client_${System.currentTimeMillis()}"
        clients[clientId] = clientAddress
        onClientConnected(clientId, clientAddress)

        // Send back the server's UDP port so client can send UDP
        session.send(Frame.Text("UDP_PORT:${udpPort}"))

        try {
            for (frame in session.incoming) {
                if (frame is Frame.Close) break
                // Other signaling could be handled here
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