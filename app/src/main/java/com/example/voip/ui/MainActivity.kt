package com.example.voip.ui

import android.Manifest
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.voip.network.NetworkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    // Instância do NetworkManager (Certifique-se que o arquivo NetworkManager.kt existe)
    private val networkManager = NetworkManager()

    private val requestPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        if (permissions.values.all { it }) {
            // Permissões concedidas
        } else {
            Toast.makeText(this, "Permissões necessárias para o VoIP funcionar", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        requestPermissions.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE
            )
        )

        setContent {
            MaterialTheme {
                Surface(color = MaterialTheme.colorScheme.background) {
                    VoipScreen(networkManager)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoipScreen(networkManager: NetworkManager) {
    // Estados da UI
    var ipAddress by remember { mutableStateOf("127.0.0.1") }
    var roomId by remember { mutableStateOf("SALA01") }
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
        Text(text = "Modo: Relay (Termux)", style = MaterialTheme.typography.bodySmall)
        
        Spacer(modifier = Modifier.height(24.dp))

        // Campo para o IP (localhost se for o dono do Termux, ou IP do amigo)
        OutlinedTextField(
            value = ipAddress,
            onValueChange = { ipAddress = it },
            label = { Text("IP do Servidor (Termux)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Campo para o ID da Sala
        OutlinedTextField(
            value = roomId,
            onValueChange = { roomId = it },
            label = { Text("ID da Sala") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Botão de Conectar
        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        withContext(Dispatchers.Main) { statusText = "Conectando..." }
                        
                        networkManager.serverIp = ipAddress
                        networkManager.roomId = roomId
                        networkManager.start()
                        
                        withContext(Dispatchers.Main) { 
                            statusText = "Conectado à sala: $roomId"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            statusText = "Erro: ${e.localizedMessage}"
                            Toast.makeText(context, "Falha na conexão", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        ) {
            Text("Entrar na Chamada")
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Card de Status
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
        
        // Botão Desconectar
        Button(
            onClick = { 
                networkManager.stop()
                statusText = "Desconectado" 
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sair da Sala", color = Color.White)
        }
    }
}
