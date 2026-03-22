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
fun VoipApp(
    viewModel: VoipViewModel = viewModel()
) {
    val isServerRunning by viewModel.isServerRunning.collectAsState()
    val connectedClients by viewModel.connectedClients.collectAsState()
    val audioLevel by viewModel.audioLevel.collectAsState()
    val microphoneEnabled by viewModel.microphoneEnabled.collectAsState()

    val context = LocalContext.current
    val nsdHelper = remember { NsdHelper.getInstance() }
    var showConnectDialog by remember { mutableStateOf(false) }
    var discoveredServers by remember { mutableStateOf(listOf<String>()) }

    LaunchedEffect(showConnectDialog) {
        if (showConnectDialog) {
            nsdHelper?.discoverServices { services ->
                discoveredServers = services.mapNotNull { it.host?.hostAddress }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Chat") },
                colors = TopAppBarDefaults.mediumTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.primaryContainer,
                            MaterialTheme.colorScheme.surface
                        )
                    )
                )
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AudioLevelIndicator(level = audioLevel, modifier = Modifier.size(100.dp))

                Spacer(modifier = Modifier.height(16.dp))

                UserList(users = connectedClients, modifier = Modifier.weight(1f))

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(16.dp)
                ) {
                    if (isServerRunning) {
                        Button(onClick = { viewModel.stopServer() }) {
                            Text("Stop Server")
                        }
                    } else {
                        Button(onClick = { viewModel.startServer() }) {
                            Text("Start Server")
                        }
                    }

                    Button(onClick = { showConnectDialog = true }) {
                        Text("Connect")
                    }

                    IconButton(onClick = { viewModel.toggleMicrophone() }) {
                        Icon(
                            if (microphoneEnabled) Icons.Default.Mic else Icons.Default.MicOff,
                            contentDescription = if (microphoneEnabled) "Mic on" else "Mic off"
                        )
                    }
                }
            }
        }
    }

    if (showConnectDialog) {
        ConnectDialog(
            onDismiss = { showConnectDialog = false },
            onConnect = { ip ->
                viewModel.connectToServer(ip)
                showConnectDialog = false
            },
            discoveredServers = discoveredServers
        )
    }
}