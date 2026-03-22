package com.example.voip.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voip.service.VoipService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VoipViewModel(application: Application) : AndroidViewModel(application) {
    
    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _microphoneEnabled = MutableStateFlow(true)
    val microphoneEnabled: StateFlow<Boolean> = _microphoneEnabled.asStateFlow()

    private var voipService: VoipService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as VoipService.LocalBinder
            voipService = localBinder.getService()
            isBound = true

            // Observa os fluxos do Service e atualiza a UI
            viewModelScope.launch {
                voipService?.isConnected?.collect { _isConnected.value = it }
            }
            viewModelScope.launch {
                voipService?.audioLevel?.collect { _audioLevel.value = it }
            }
            viewModelScope.launch {
                voipService?.microphoneMuted?.collect { muted ->
                    _microphoneEnabled.value = !muted
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            voipService = null
            isBound = false
            _isConnected.value = false
        }
    }

    fun joinRoom(ip: String, roomId: String) {
        val intent = Intent(getApplication(), VoipService::class.java).apply {
            putExtra("SERVER_IP", ip)
            putExtra("ROOM_ID", roomId)
        }
        getApplication<Application>().startForegroundService(intent)
        getApplication<Application>().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    fun leaveRoom() {
        val intent = Intent(getApplication(), VoipService::class.java)
        getApplication<Application>().stopService(intent)
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
            isBound = false
        }
        voipService = null
        _isConnected.value = false
    }

    fun toggleMicrophone() {
        voipService?.toggleMicrophone()
    }

    override fun onCleared() {
        super.onCleared()
        if (isBound) {
            getApplication<Application>().unbindService(serviceConnection)
        }
    }
}
