package com.koen.haremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.koen.haremote.data.HostConfig

/**
 * Edits a single [HostConfig] - the base URL, base path and bearer token shared by every
 * button that points at it (see [HostConfig]'s doc for why these live here instead of being
 * repeated on every button). Deleting a host un-links it from any button that still uses it
 * rather than deleting those buttons - see `HaRemoteViewModel.deleteHost`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    host: HostConfig,
    onBack: () -> Unit,
    onSave: (HostConfig) -> Unit,
    onDelete: () -> Unit
) {
    var name by remember(host.id) { mutableStateOf(host.name) }
    var baseUrl by remember(host.id) { mutableStateOf(host.baseUrl) }
    var basePath by remember(host.id) { mutableStateOf(host.basePath) }
    var bearerToken by remember(host.id) { mutableStateOf(host.bearerToken) }

    fun current() = host.copy(
        name = name,
        baseUrl = baseUrl,
        basePath = basePath,
        bearerToken = bearerToken
    )

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Host bewerken") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                    }
                },
                actions = {
                    TextButton(onClick = { onSave(current()) }) { Text("Opslaan") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Naam") },
                placeholder = { Text("Home Assistant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Basis-URL") },
                placeholder = { Text("http://ha.lan:8123") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = basePath,
                onValueChange = { basePath = it },
                label = { Text("Basis-pad (optioneel)") },
                placeholder = { Text("api/services/script") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = bearerToken,
                onValueChange = { bearerToken = it },
                label = { Text("Bearer token (optioneel)") },
                placeholder = { Text("Long-Lived Access Token van Home Assistant") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Knoppen die deze host gebruiken hoeven enkel nog het laatste stukje van de " +
                    "URL in te vullen - basis-URL, basis-pad en bearer token komen van hier.",
                style = MaterialTheme.typography.bodyMedium
            )

            Button(onClick = { onSave(current()) }, modifier = Modifier.fillMaxWidth()) {
                Text("Opslaan")
            }
            OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Host verwijderen")
            }
        }
    }
}
