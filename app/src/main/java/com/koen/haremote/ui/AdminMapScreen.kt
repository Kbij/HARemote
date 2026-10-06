package com.koen.haremote.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import com.koen.haremote.location.DiagnosticLogger
import com.koen.haremote.network.tcp.LocationHistoryResponseMessage
import com.koen.haremote.ui.theme.CinemaGold
import com.koen.haremote.ui.theme.CinemaMutedText
import com.koen.haremote.ui.theme.CinemaSurface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG_ADMIN_MAP = "AdminMode"

private val timestampFormat = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())

private fun formatTimestamp(millis: Long): String =
    if (millis <= 0L) "onbekend" else timestampFormat.format(Date(millis))

/**
 * The actual admin screen, reached after a successful [AdminPinScreen] PIN check: a client
 * picker (populated from ClientList, sent by the server right after auth succeeds) and a map
 * showing the last hour of that client's GPS fixes as a polyline, the last fix as a red marker,
 * and - when the client currently has an active stationary geofence - a circle around it.
 *
 * Re-requests history (see [onClientSelected]) whenever the selected client changes - there is
 * no caching here, this always reflects the server's current answer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminMapScreen(
    clients: List<String>,
    history: LocationHistoryResponseMessage?,
    connected: Boolean,
    onClientSelected: (String) -> Unit,
    onBack: () -> Unit
) {
    var selectedClient by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var mapLoaded by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Coarse-grained trail for diagnosing a crash on this screen (see HaRemoteApp's global
    // uncaught-exception logging for the actual stack trace) - at minimum this shows how far
    // rendering got: composed at all vs. GoogleMap actually finished loading.
    LaunchedEffect(Unit) {
        DiagnosticLogger.log(context, TAG_ADMIN_MAP, "AdminMapScreen samengesteld")
    }

    // Auto-select the first client as soon as the list arrives.
    LaunchedEffect(clients) {
        if (selectedClient == null && clients.isNotEmpty()) {
            selectedClient = clients.first()
            onClientSelected(clients.first())
        }
    }

    val lastPoint = history?.points?.lastOrNull()
    val geofence = history?.geofence

    // "Current position" for the marker/text/camera. Koen (10 sept 2026, after the first cut
    // of this fallback): don't reason about whether a fresh GPS fix landed within the
    // requested time window at all - while a geofence is active essentially nothing new comes
    // in (GPS/network polling is fully paused, see "locatie-polling pauzeren", 8 sept), and
    // the geofence is only ever cleared the moment the client actually moves. So as long as
    // the server still reports it as active, its own coordinates ARE the current position,
    // guaranteed - not merely a fallback for whenever `points` happens to be empty, but the
    // authoritative answer whenever it's present, ahead of whatever `points` says (which can
    // lag behind a renewal, or simply be empty if the window is shorter than the 30-min
    // renewal interval).
    val markerLatLng = when {
        geofence != null -> LatLng(geofence.latitude, geofence.longitude)
        lastPoint != null -> LatLng(lastPoint.latitude, lastPoint.longitude)
        else -> null
    }

    val cameraPositionState = rememberCameraPositionState()

    // The server now pushes a fresh LocationHistoryResponse live (whenever the watched client
    // reports a new fix or geofence change - see AdminController::notifyLocationWatchers
    // server-side), not just once per client selection. Re-fitting the camera on every single
    // one of those pushes would fight anyone who's manually panned/zoomed to look somewhere
    // specific, so the auto-fit only runs once per client selection (first data after picking
    // it) - later live updates move the marker/polyline/circle but leave the camera alone.
    var hasFitCameraForClient by remember { mutableStateOf(false) }
    LaunchedEffect(selectedClient) {
        hasFitCameraForClient = false
    }

    LaunchedEffect(history, mapLoaded) {
        val points = history?.points.orEmpty()
        if (!mapLoaded || hasFitCameraForClient) return@LaunchedEffect
        if (points.isEmpty() && markerLatLng == null) return@LaunchedEffect
        hasFitCameraForClient = true
        runCatching {
            if (points.size >= 2) {
                val boundsBuilder = LatLngBounds.builder()
                points.forEach { boundsBuilder.include(LatLng(it.latitude, it.longitude)) }
                cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(boundsBuilder.build(), 120))
            } else if (markerLatLng != null) {
                // Either a single GPS point, or no points at all but a geofence to fall back
                // on (see markerLatLng above) - either way there's exactly one location to
                // center on, not a trajectory to fit bounds around.
                cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(markerLatLng, 16f))
            }
        }.onFailure { e ->
            DiagnosticLogger.log(context, TAG_ADMIN_MAP, "Camera-animatie mislukt: ${e.message}")
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Admin - locatie") },
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
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it }
                ) {
                    OutlinedTextField(
                        value = selectedClient ?: if (clients.isEmpty()) "Geen clients" else "Kies een client",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Client") },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null) },
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = CinemaGold),
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        clients.forEach { client ->
                            DropdownMenuItem(
                                text = { Text(client) },
                                onClick = {
                                    expanded = false
                                    selectedClient = client
                                    onClientSelected(client)
                                }
                            )
                        }
                    }
                }

                if (!connected) {
                    Text(
                        text = "Niet verbonden met server",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMutedText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else if (history != null && history.points.isEmpty()) {
                    Text(
                        text = "Geen locaties in het laatste uur voor deze client",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMutedText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                if (geofence != null) {
                    // An active geofence is authoritative for "current location" (see
                    // markerLatLng above) - always show it here, even if `points` happens to
                    // also contain a (necessarily older-or-equal) recent fix.
                    Text(
                        text = "Laatste locatie: geofence (bevestigd ${formatTimestamp(geofence.updatedAtMillis)})",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMutedText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else if (lastPoint != null) {
                    Text(
                        text = "Laatste locatie: ${formatTimestamp(lastPoint.timestampMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMutedText,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                // Distinct from "Laatste locatie" above: this is when the server last heard
                // anything at all from the client (including keepalives), so it stays live even
                // while the client is stationary and not reporting fresh GPS fixes.
                if (history != null) {
                    Text(
                        text = "Laatste connectie: ${formatTimestamp(history.lastConnectionMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMutedText,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                if (geofence != null) {
                    Text(
                        text = "Geofence: radius ${geofence.radiusMeters.toInt()}m",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMutedText,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    Text(
                        text = "Aangemaakt: ${formatTimestamp(geofence.createdAtMillis)} - " +
                            "laatst bevestigd: ${formatTimestamp(geofence.updatedAtMillis)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = CinemaMutedText,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = CinemaSurface),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    GoogleMap(
                        modifier = Modifier.fillMaxSize(),
                        cameraPositionState = cameraPositionState,
                        properties = MapProperties(mapType = MapType.NORMAL),
                        onMapLoaded = {
                            DiagnosticLogger.log(context, TAG_ADMIN_MAP, "GoogleMap geladen")
                            mapLoaded = true
                        }
                    ) {
                        val points = history?.points.orEmpty()
                        if (points.isNotEmpty()) {
                            val latLngs = points.map { LatLng(it.latitude, it.longitude) }
                            Polyline(points = latLngs, color = CinemaGold, width = 8f)
                        }

                        // The red marker always tracks markerLatLng (last GPS fix, or the
                        // geofence's own coordinates when there is no fresher fix in the
                        // requested window - see markerLatLng above) rather than being gated on
                        // `points` being non-empty. Previously this block lived inside the
                        // `points.isNotEmpty()` check above, so a client sitting quietly inside
                        // an active geofence with no recent fix in the requested window got no
                        // marker at all - nothing on the map showed where it actually was, even
                        // though the geofence circle below made clear one was active there.
                        if (markerLatLng != null) {
                            // Keyed by what's actually driving the position, so a fresh
                            // MarkerState (and thus an updated marker position) is created
                            // whenever new history comes in - rememberMarkerState's `position`
                            // param is only an *initial* value, it doesn't track later changes
                            // on its own. Geofence takes priority here too, matching
                            // markerLatLng's own priority above.
                            val markerKey = if (geofence != null) "geofence-${geofence.updatedAtMillis}"
                                else lastPoint?.timestampMillis?.toString()
                            Marker(
                                state = rememberMarkerState(key = markerKey, position = markerLatLng),
                                title = selectedClient,
                                snippet = if (geofence != null)
                                    "Stilstaand (geofence), bevestigd ${formatTimestamp(geofence.updatedAtMillis)}"
                                else
                                    "Locatie om ${formatTimestamp(lastPoint?.timestampMillis ?: 0L)}"
                            )
                        }

                        if (geofence != null) {
                            Circle(
                                center = LatLng(geofence.latitude, geofence.longitude),
                                radius = geofence.radiusMeters,
                                strokeColor = CinemaGold,
                                strokeWidth = 4f,
                                fillColor = CinemaGold.copy(alpha = 0.18f)
                            )
                        }
                    }

                    if (selectedClient != null && history == null) {
                        CircularProgressIndicator(
                            color = CinemaGold,
                            modifier = Modifier.align(Alignment.Center)
                        )
                    }
                }
            }
        }
    }
}
