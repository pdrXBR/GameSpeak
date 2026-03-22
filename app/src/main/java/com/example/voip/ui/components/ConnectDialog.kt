package com.example.voip.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ConnectDialog(
    onDismiss: () -> Unit,
    onConnect: (String) -> Unit,
    discoveredServers: List<String>
) {
    var ipText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to Server") },
        text = {
            Column {
                OutlinedTextField(
                    value = ipText,
                    onValueChange = { ipText = it },
                    label = { Text("IP Address") },
                    modifier = Modifier.fillMaxWidth()
                )
                if (discoveredServers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Discovered servers:", style = MaterialTheme.typography.titleSmall)
                    LazyColumn(
                        modifier = Modifier.height(150.dp)
                    ) {
                        items(discoveredServers) { ip ->
                            TextButton(onClick = { onConnect(ip) }) {
                                Text(ip)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConnect(ipText) }) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}