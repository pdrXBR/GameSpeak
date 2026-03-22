package com.example.voip.network

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.url
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.server.websocket.DefaultWebSocketServerSession // IMPORTANTE: Adicione este especificamente
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.firstOrNull
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress // Adicione este para ajudar na ambiguidade
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
    private var clientSession: DefaultClientWebSocketSession? = null

    private val clients = ConcurrentHashMap<String, InetSocketAddress>()
    private var serverUdpAddress: InetSocketAddress? = null

    private var receiveJob: Job? = null
    private var isRunning = AtomicBoolean(false)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var onAudioReceived: ((ByteArray) -> Unit)? = null

    fun setOnAudioReceived(callback: (ByteArray) -> Unit) {
        onAudioReceived = callback
    }

    fun startServer() {
        require(mode == Mode.SERVER)
        isRunning.set(true)

        udpSocket = DatagramSocket(0)
        udpPort = udpSocket?.localPort ?: 0

        webSocketServer = embeddedServer(Netty, port = 8080) {
            install(ServerWebSockets) 
            routing {
                webSocket("/signal") {
                    handleWebSocketConnection(this)
                }
            }
        }.start(wait = false)

        startUdpReceiveLoop()
    }

    suspend fun connectToServer() {
        require(mode == Mode.CLIENT)
        isRunning.set(true)

        udpSocket = DatagramSocket(0)
        udpPort = udpSocket?.localPort ?: 0

        webSocketClient = HttpClient(CIO) {
            install(ClientWebSockets)
        }

        clientSession = webSocketClient?.webSocketSession {
            url("ws://$serverIp:8080/signal")
        }

        clientSession?.send(Frame.Text("$udpPort"))

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

        startUdpReceiveLoop()

        scope.launch {
            clientSession?.incoming?.consumeAsFlow()?.collect { frame ->
                if (frame is Frame.Close) {
                    stop()
                }
            }
        }
    }

    private fun startUdpReceiveLoop() {
        receiveJob = scope.launch {
            val buffer = ByteArray(4096)
            while (isRunning.get() && isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    val data = packet.data.copyOf(packet.length)
                    
                    // Nota: Certifique-se que a classe UdpPacket existe no seu projeto
                    val decoded = UdpPacket.decode(data)
                    val senderId = decoded.first
                    val audioData = decoded.second

                    if (audioData != null) {
                        if (mode == Mode.SERVER && senderId != null) {
                            clients.forEach { (id, addr) ->
                                if (id != senderId) {
                                    val outPacket = UdpPacket.encode(senderId, audioData)
                                    udpSocket?.send(DatagramPacket(outPacket, outPacket.size, addr))
                                }
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

    fun sendAudio(audioData: ByteArray) {
        scope.launch {
            if (mode == Mode.CLIENT && serverUdpAddress != null) {
                val packetData = UdpPacket.encode(CLIENT_ID, audioData)
                udpSocket?.send(DatagramPacket(packetData, packetData.size, serverUdpAddress))
            }
        }
    }

    fun sendAudioToAll(audioData: ByteArray) {
        scope.launch {
            if (mode == Mode.SERVER) {
                val packetData = UdpPacket.encode(SERVER_ID, audioData)
                clients.values.forEach { addr ->
                    udpSocket?.send(DatagramPacket(packetData, packetData.size, addr))
                }
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
        val frame = session.incoming.receive()
        if (frame !is Frame.Text) return
        val clientUdpPort = frame.readText().toIntOrNull() ?: return
    
        // Pegamos o host como String explicitamente
        val clientHost: String = session.call.request.origin.remoteHost
        
        // Agora o Kotlin sabe que deve usar InetSocketAddress(String, Int)
        val clientAddress = InetSocketAddress(clientHost, clientUdpPort)
        
        val clientId = "client_${System.currentTimeMillis()}"
        // ... restante do código
}

        
        clients[clientId] = clientAddress
        onClientConnected(clientId, clientAddress)

        session.send(Frame.Text("UDP_PORT:${udpPort}"))

        try {
            for (f in session.incoming) {
                if (f is Frame.Close) break
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
        ): NetworkManager = NetworkManager(context, Mode.SERVER, null, onClientConnected, onClientDisconnected)

        fun createClient(
            context: Context,
            serverIp: String,
            onClientConnected: (String, java.net.SocketAddress) -> Unit,
            onClientDisconnected: (String) -> Unit
        ): NetworkManager = NetworkManager(context, Mode.CLIENT, serverIp, onClientConnected, onClientDisconnected)
    }
}
