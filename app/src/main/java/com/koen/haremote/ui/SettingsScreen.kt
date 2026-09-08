package com.koen.haremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.koen.haremote.data.AppSettings
import com.koen.haremote.data.ButtonConfig
import com.koen.haremote.data.HostConfig
import com.koen.haremote.location.BatteryDiagnostics
import com.koen.haremote.ui.theme.CinemaGold
import com.koen.haremote.ui.theme.CinemaMutedText
import com.koen.haremote.ui.theme.CinemaSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    onEditButton: (ButtonConfig) -> Unit,
    onEditHost: (HostConfig) -> Unit,
    onAddHost: () -> Unit,
    onTcpHostChanged: (String) -> Unit,
    onTcpPortChanged: (String) -> Unit,
    onGpsLoggingToggle: (Boolean) -> Unit,
    onRequestIgnoreBatteryOptimizations: () -> Unit,
    diagnosticsStatus: BatteryDiagnostics.Status,
    onRefreshDiagnostics: () -> Unit,
    onShareDiagnosticLog: () -> Unit,
    onClearDiagnosticLog: () -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit
) {
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Instellingen") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Terug")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(28.dp)
        ) {
            SectionTitle("Hosts")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.hosts.forEach { host ->
                    HostRow(host = host, onClick = { onEditHost(host) })
                }
                OutlinedButton(onClick = onAddHost, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Nieuwe host")
                }
            }

            SectionTitle("Knoppen")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.buttons.forEach { button ->
                    ButtonRow(button = button, hosts = settings.hosts, onClick = { onEditButton(button) })
                }
            }

            SectionTitle("Achtergrond GPS-logging")
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Locatie-updates gaan over een permanente TCP-verbinding met de " +
                            "HomeControl-server (onbeveiligd, zelfde LAN).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaMutedText
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = settings.tcpServerHost,
                            onValueChange = onTcpHostChanged,
                            label = { Text("Server hostnaam") },
                            placeholder = { Text("192.168.1.10") },
                            singleLine = true,
                            modifier = Modifier.weight(2f)
                        )
                        OutlinedTextField(
                            value = if (settings.tcpServerPort == 0) "" else settings.tcpServerPort.toString(),
                            onValueChange = onTcpPortChanged,
                            label = { Text("Poort") },
                            placeholder = { Text(AppSettings.DEFAULT_TCP_PORT.toString()) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Logging actief", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Blijft ook op de achtergrond en na herstart actief",
                                style = MaterialTheme.typography.bodyMedium,
                                color = CinemaMutedText
                            )
                        }
                        Switch(
                            checked = settings.gpsLoggingEnabled,
                            onCheckedChange = onGpsLoggingToggle,
                            colors = SwitchDefaults.colors(checkedThumbColor = CinemaGold)
                        )
                    }
                    OutlinedButton(
                        onClick = onRequestIgnoreBatteryOptimizations,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.BatteryChargingFull, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Batterijoptimalisatie negeren")
                    }
                    Text(
                        "Nodig zodat Android de logging-service niet stilzet. Zet dit ook aan " +
                            "in de accu-instellingen van je toestel als de fabrikant een eigen " +
                            "batterijbeheer heeft (Samsung, Xiaomi, Huawei, ...).",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaMutedText
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(onClick = onShareDiagnosticLog, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Share, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Deel logbestand")
                        }
                        OutlinedButton(onClick = onClearDiagnosticLog, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Delete, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Wis logbestand")
                        }
                    }
                    Text(
                        "Bijhoudt wanneer de service start/stopt, de TCP-verbinding op- of " +
                            "afgaat, elke ontvangen locatie-fix, en nu ook elke Home Assistant " +
                            "REST-call (knop, methode, resultaat en duur), om te helpen " +
                            "uitzoeken of Android de app stilzet, GPS zelf stokt, of een " +
                            "REST-call vasthangt. Wis eerst het logbestand voor je een nieuwe " +
                            "testperiode start, zodat je enkel de recente gebeurtenissen ziet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaMutedText
                    )
                }
            }

            SectionTitle("Diagnose")
            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaSurface),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Batterijoptimalisatie: " + if (diagnosticsStatus.ignoringBatteryOptimizations) {
                            "uitgezet (goed)"
                        } else {
                            "nog actief"
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "App-status (standby bucket): ${diagnosticsStatus.standbyBucketLabel}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        "Energiebespaarstand: " + if (diagnosticsStatus.powerSaveMode) "aan" else "uit",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaMutedText
                    )
                    Text(
                        "Dit is enkel de status die Android zelf bijhoudt — extra batterijbeheer " +
                            "van de fabrikant (bv. Motorola Battery Care) heeft geen eigen, van " +
                            "buitenaf uitleesbare status en moet je in de systeeminstellingen " +
                            "zelf nakijken.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaMutedText
                    )
                    OutlinedButton(onClick = onRefreshDiagnostics, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ververs")
                    }
                }
            }

            SectionTitle("Import / export")
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedButton(onClick = onExport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FileUpload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Exporteer JSON")
                }
                OutlinedButton(onClick = onImport, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.FileDownload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Importeer JSON")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelLarge,
        color = CinemaGold
    )
}

@Composable
private fun HostRow(host: HostConfig, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = CinemaSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(host.name.ifBlank { "Host ${host.id}" }) },
            supportingContent = {
                Text(
                    host.baseUrl.ifBlank { "Geen basis-URL ingesteld" },
                    color = CinemaMutedText,
                    maxLines = 1
                )
            },
            trailingContent = {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = CinemaMutedText)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}

@Composable
private fun ButtonRow(button: ButtonConfig, hosts: List<HostConfig>, onClick: () -> Unit) {
    val host = hosts.firstOrNull { it.id == button.hostId }
    val subtitle = when {
        host == null -> "Geen host gekoppeld"
        button.path.isBlank() -> "${host.name}: geen pad ingesteld"
        else -> "${host.name}: ${button.path}"
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = CinemaSurface),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        ListItem(
            headlineContent = { Text(button.label.ifBlank { "Knop ${button.id}" }) },
            supportingContent = { Text(subtitle, color = CinemaMutedText, maxLines = 1) },
            leadingContent = {
                Icon(button.icon.toImageVector(), contentDescription = null, tint = CinemaGold)
            },
            trailingContent = {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = CinemaMutedText)
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )
    }
}
