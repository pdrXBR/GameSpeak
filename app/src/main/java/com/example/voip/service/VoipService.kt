package com.example.voip.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.example.voip.R
import com.example.voip.audio.AudioCapture
import com.example.voip.audio.AudioPlayback
import com.example.voip.network.NetworkManager
import com.example.voip.ui.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicBoolean

class VoipService : LifecycleService() {

    private val binder = LocalBinder()
    private lateinit var wifiLock: WifiManager.WifiLock
    private lateinit var wakeLock: PowerManager.WakeLock

    private var networkManager: NetworkManager = NetworkManager()
    private var audioCapture: AudioCapture? = null
    private var audioPlayback: AudioPlayback? = null

    // Estados para a UI observar
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _microphoneMuted = MutableStateFlow(false)
    val microphoneMuted: StateFlow<Boolean> = _microphoneMuted.asStateFlow()

    private var connectionJob: Job? = null
    private val isShuttingDown = AtomicBoolean(false)

    inner class LocalBinder : Binder() {
        fun getService(): VoipService = this@VoipService
    }

    override fun onCreate() {
        super.onCreate()
        // Mantém o celular acordado e o Wi-Fi em alta performance
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

        val serverIp = intent?.getStringExtra("SERVER_IP") ?: "127.0.0.1"
        val roomId = intent?.getStringExtra("ROOM_ID") ?: "SALA01"

        startVoip(serverIp, roomId)

        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    private fun startVoip(serverIp: String, roomId: String) {
        if (_isConnected.value) return
        
        connectionJob = lifecycleScope.launch(Dispatchers.IO) {
            // Configura o NetworkManager para o modo Relay
            networkManager.serverIp = serverIp
            networkManager.roomId = roomId
            networkManager.start()
            
            _isConnected.value = true

            // Configura a Captura de Áudio (Microfone)
            audioCapture = AudioCapture(
                onAudioData = { audioData ->
                    if (!_microphoneMuted.value) {
                        networkManager.sendAudioFrame(audioData)
                    }
                },
                onAudioLevel = { level -> _audioLevel.value = level }
            )
            audioCapture?.start()

            // Configura a Reprodução (Alto-falante)
            audioPlayback = AudioPlayback()
            audioPlayback?.start()

            // Quando chegar áudio do servidor, toca no alto-falante
            networkManager.onAudioReceived = { audioData ->
                audioPlayback?.playAudio(audioData)
            }
        }
    }

    fun toggleMicrophone() {
        _microphoneMuted.value = !_microphoneMuted.value
    }

    override fun onDestroy() {
        isShuttingDown.set(true)
        audioCapture?.stop()
        audioPlayback?.stop()
        networkManager.stop()
        connectionJob?.cancel()
        
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    // --- Notificações e Canais (Obrigatório para rodar em background) ---

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "GameSpeak Voice",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameSpeak Ativo")
            .setContentText("Conectado na sala ${networkManager.roomId}")
            .setSmallIcon(android.R.drawable.presence_audio_online) // Ícone padrão do Android
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "voip_service_channel"
    }
}
