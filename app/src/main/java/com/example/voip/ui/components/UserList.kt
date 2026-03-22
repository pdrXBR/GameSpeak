package com.example.voip.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.voip.service.ClientInfo

@Composable
fun UserList(users: List<ClientInfo>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier) {
        items(users) { user ->
            UserItem(user)
        }
    }
}

@Composable
fun UserItem(user: ClientInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
    ) {
        Text(text = user.name, style = MaterialTheme.typography.bodyLarge)
    }
}