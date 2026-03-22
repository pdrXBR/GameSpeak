package com.example.voip.ui

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.voip.service.ClientInfo
import com.example.voip.service.VoipService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class VoipViewModel(application: Application) : AndroidViewModel(application) {
    private val _isServerRunning = MutableStateFlow(false)
    val isServerRunning: StateFlow<Boolean> = _isServerRunning.asStateFlow()

    private val _connectedClients = MutableStateFlow<List<ClientInfo>>(emptyList())
    val connectedClients: StateFlow<List<ClientInfo>> = _connectedClients.asStateFlow()

    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _microphoneEnabled = MutableStateFlow(true)
    val microphoneEnabled: StateFlow<Boolean> = _microphoneEnabled.asStateFlow()

    private var service: VoipService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as VoipService.LocalBinder).getService()
            bound = true
            viewModelScope.launch {
                service?.isServerRunning?.collect { _isServerRunning.value = it }
            }
            viewModelScope.launch {
                service?.connectedClients?.collect { _connectedClients.value = it }
            }
            viewModelScope.launch {
                service?.audioLevel?.collect { _audioLevel.value = it }
            }
            viewModelScope.launch {
                service?.microphoneMuted?.collect { muted ->
                    _microphoneEnabled.value = !muted
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            service = null
        }
    }

    fun startServer() {
        val intent = Intent(getApplication(), VoipService::class.java).apply {
            putExtra("MODE", "SERVER")
        }
        getApplication<Application>().startForegroundService(intent)
        getApplication<Application>().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun stopServer() {
        val intent = Intent(getApplication(), VoipService::class.java)
        getApplication<Application>().stopService(intent)
        if (bound) {
            getApplication<Application>().unbindService(connection)
            bound = false
        }
        service = null
    }

    fun connectToServer(ip: String) {
        val intent = Intent(getApplication(), VoipService::class.java).apply {
            putExtra("MODE", "CLIENT")
            putExtra("SERVER_IP", ip)
        }
        getApplication<Application>().startForegroundService(intent)
        getApplication<Application>().bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun disconnect() {
        stopServer()
    }

    fun toggleMicrophone() {
        service?.toggleMicrophone()
        _microphoneEnabled.value = !_microphoneEnabled.value
    }

    override fun onCleared() {
        super.onCleared()
        if (bound) {
            getApplication<Application>().unbindService(connection)
        }
    }
}