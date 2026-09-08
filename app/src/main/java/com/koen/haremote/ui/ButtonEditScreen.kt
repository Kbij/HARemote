package com.koen.haremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.koen.haremote.data.ButtonConfig
import com.koen.haremote.data.ButtonIcon
import com.koen.haremote.data.HostConfig
import com.koen.haremote.data.HttpMethod
import com.koen.haremote.network.RestCaller
import com.koen.haremote.ui.theme.CinemaGold
import com.koen.haremote.ui.theme.CinemaMutedText
import com.koen.haremote.ui.theme.CinemaSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ButtonEditScreen(
    button: ButtonConfig,
    hosts: List<HostConfig>,
    onBack: () -> Unit,
    onSave: (ButtonConfig) -> Unit
) {
    var label by remember(button.id) { mutableStateOf(button.label) }
    var hostId by remember(button.id) { mutableStateOf(button.hostId ?: hosts.firstOrNull()?.id) }
    var path by remember(button.id) { mutableStateOf(button.path) }
    var method by remember(button.id) { mutableStateOf(button.method) }
    var body by remember(button.id) { mutableStateOf(button.body) }
    var icon by remember(button.id) { mutableStateOf(button.icon) }
    var methodMenuExpanded by remember { mutableStateOf(false) }
    var hostMenuExpanded by remember { mutableStateOf(false) }

    fun current() = button.copy(
        label = label,
        hostId = hostId,
        path = path,
        method = method,
        body = body,
        icon = icon
    )

    val selectedHost = hosts.firstOrNull { it.id == hostId }
    val previewUrl = RestCaller.buildUrl(selectedHost, path)

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Knop bewerken") },
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
                value = label,
                onValueChange = { label = it },
                label = { Text("Naam op de knop") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text("Icoon", style = MaterialTheme.typography.labelLarge, color = CinemaGold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ButtonIcon.entries) { candidate ->
                    IconChoice(
                        icon = candidate,
                        selected = candidate == icon,
                        onClick = { icon = candidate }
                    )
                }
            }

            ExposedDropdownMenuBox(
                expanded = hostMenuExpanded,
                onExpandedChange = { hostMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedHost?.name ?: "Geen host",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Host") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = hostMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = hostMenuExpanded,
                    onDismissRequest = { hostMenuExpanded = false }
                ) {
                    hosts.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = {
                                hostId = candidate.id
                                hostMenuExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                label = { Text("Pad") },
                placeholder = { Text("sfeer_tv") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                previewUrl ?: "Kies een host met een basis-URL om de volledige URL te zien",
                style = MaterialTheme.typography.bodyMedium,
                color = CinemaMutedText
            )

            ExposedDropdownMenuBox(
                expanded = methodMenuExpanded,
                onExpandedChange = { methodMenuExpanded = it }
            ) {
                OutlinedTextField(
                    value = method.name,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("HTTP methode") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodMenuExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = methodMenuExpanded,
                    onDismissRequest = { methodMenuExpanded = false }
                ) {
                    HttpMethod.entries.forEach { candidate ->
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = {
                                method = candidate
                                methodMenuExpanded = false
                            }
                        )
                    }
                }
            }

            if (method == HttpMethod.POST) {
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("JSON body (optioneel)") },
                    placeholder = { Text("""{"entity_id": "scene.film_modus"}""") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = { onSave(current()) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Opslaan")
            }
        }
    }
}

@Composable
private fun IconChoice(icon: ButtonIcon, selected: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = CircleShape,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) CinemaGold else CinemaSurface
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp)
        ) {
            Icon(
                imageVector = icon.toImageVector(),
                contentDescription = icon.name,
                tint = if (selected) Color.Black else CinemaGold
            )
        }
    }
}
