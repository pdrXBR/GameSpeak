package com.example.voip.ui

import androidx.compose.foundation.background
import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.voip.ui.components.AudioLevelIndicator
import com.example.voip.ui.components.ConnectDialog
import com.example.voip.ui.components.UserList
import com.example.voip.utils.NsdHelper

class MainActivity : ComponentActivity() {
    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            // Permissions granted
        } else {
            // Show rationale
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.ACCESS_WIFI_STATE,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )

        setContent {
            MaterialTheme {
                VoipApp()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoipScreen(networkManager: NetworkManager) {
    // Estados para guardar o que o usuário digita
    var ipAddress by remember { mutableStateOf("192.168.0.100") }
    var statusText by remember { mutableStateOf("Desconectado") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text = "GameSpeak VoIP", style = MaterialTheme.typography.headlineMedium)
        
        Spacer(modifier = Modifier.height(16.dp))

        // Campo para digitar o IP
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("IP do Servidor") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Botões de Ação
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Button(onClick = {
                statusText = "Iniciando Servidor..."
                networkManager.startServer()
                statusText = "Servidor Rodando na porta ${networkManager.udpPort}"
            }) {
                Text("Criar Sala (Host)")
            }

            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        statusText = "Conectando..."
                        networkManager.connectToServer() // Usa o ipAddress que você digitou
                        statusText = "Conectado ao Servidor!"
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusText = "Erro: ${e.localizedMessage}"
                            Toast.makeText(context, "Falha na conexão", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }) {
                Text("Entrar")
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Status e Logs
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Text(
                text = "Status: $statusText",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Button(
            onClick = { networkManager.stop(); statusText = "Parado" },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("Parar Tudo", color = Color.White)
        }
    }
}
