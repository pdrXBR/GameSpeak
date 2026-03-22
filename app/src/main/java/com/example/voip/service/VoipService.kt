package com.example.voip.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.voip.R
import com.example.voip.audio.AudioCapture
import com.example.voip.audio.AudioPlayback
import com.example.voip.network.NetworkManager
import com.example.voip.ui.MainActivity
import com.example.voip.utils.NsdHelper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.SocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class VoipService : LifecycleService() {

    private val binder = LocalBinder()
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var wakeLock: PowerManager.WakeLock

    private var networkManager: NetworkManager? = null
    private var audioCapture: AudioCapture? = null
    private var audioPlayback: AudioPlayback? = null

    // State flows
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val connectedClients: StateFlow<List<ClientInfo>> = _connectedClients.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _microphoneMuted = MutableStateFlow(false)
    val microphoneMuted: StateFlow<Boolean> = _microphoneMuted.asStateFlow()

    private var serverJob: Job? = null
    private var clientJob: Job? = null
    private val isShuttingDown = AtomicBoolean(false)

    // For server: map of clientId -> UDP address
    private val clientAddresses = ConcurrentHashMap<String, SocketAddress>()

    inner class LocalBinder : Binder() {
        fun getService(): VoipService = this@VoipService
    }

    override fun onCreate() {
        super.onCreate()
        // Acquire locks to prevent sleep and Wi-Fi throttling
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Voip::WakeLock")
        wakeLock.acquire()

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Voip::WifiLock")
        wifiLock.acquire()

        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val mode = intent?.getStringExtra("MODE") ?: return START_NOT_STICKY
        when (mode) {
            "SERVER" -> startServer()
            "CLIENT" -> {
                val serverIp = intent.getStringExtra("SERVER_IP") ?: return START_NOT_STICKY
                startClient(serverIp)
            }
        }

        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun startServer() {
        if (_isServerRunning.value) return
        _isServerRunning.value = true

        serverJob = lifecycleScope.launch(Dispatchers.IO) {
            // Start network manager in server mode
            networkManager = NetworkManager.createServer(
                context = this@VoipService,
                onClientConnected = { clientId, address ->
                    clientAddresses[clientId] = address
                    updateClientList()
                },
                onClientDisconnected = { clientId ->
                    clientAddresses.remove(clientId)
                    updateClientList()
                }
            )
            networkManager?.startServer()

            // Register this server via NSD
            val udpPort = networkManager?.udpPort ?: 0
            NsdHelper.getInstance()?.registerService(
                serviceName = "VoipServer-${android.os.Build.MODEL}",
                serviceType = "_voip._udp",
                port = udpPort
            ) { success ->
                if (success) {
                    // Notify UI if needed
                }
            }

            // Start audio capture (mute status checked inside)
            audioCapture = AudioCapture(
                onAudioData = { audioData ->
                    if (!_microphoneMuted.value) {
                        networkManager?.sendAudioToAll(audioData)
                    }
                },
                onAudioLevel = { level -> _audioLevel.value = level }
            )
            audioCapture?.start()

            // Start audio playback with jitter buffer
            audioPlayback = AudioPlayback()
            audioPlayback?.start()

            // Handle incoming audio from network
            networkManager?.setOnAudioReceived { audioData ->
                audioPlayback?.playAudio(audioData)
            }
        }
    }

    private fun startClient(serverIp: String) {
        clientJob = lifecycleScope.launch(Dispatchers.IO) {
            networkManager = NetworkManager.createClient(
                context = this@VoipService,
                serverIp = serverIp,
                onClientConnected = { _, _ -> },
                onClientDisconnected = { _ -> }
            )
            networkManager?.connectToServer()

            audioCapture = AudioCapture(
                onAudioData = { audioData ->
                    if (!_microphoneMuted.value) {
                        networkManager?.sendAudio(audioData)
                    }
                },
                onAudioLevel = { level -> _audioLevel.value = level }
            )
            audioCapture?.start()

            audioPlayback = AudioPlayback()
            audioPlayback?.start()

            networkManager?.setOnAudioReceived { audioData ->
                audioPlayback?.playAudio(audioData)
            }
        }
    }

    private fun updateClientList() {
        val clients = clientAddresses.keys.map { ClientInfo(it) }
        _connectedClients.value = clients
    }

    fun toggleMicrophone() {
        _microphoneMuted.value = !_microphoneMuted.value
    }

    override fun onDestroy() {
        isShuttingDown.set(true)
        audioCapture?.stop()
        audioPlayback?.stop()
        networkManager?.stop()
        serverJob?.cancel()
        clientJob?.cancel()
        NsdHelper.getInstance()?.unregisterService()
        wifiLock.release()
        wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder = binder

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Chat Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Voice chat service is running"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(this, VoipService::class.java).apply {
            action = "STOP_SERVICE"
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Voice Chat")
            .setContentText(if (_isServerRunning.value) "Server is running" else "Connected to server")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openPendingIntent)
            .addAction(R.drawable.ic_stop, "Stop", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voip_service_channel"
    }
}

data class ClientInfo(val name: String)