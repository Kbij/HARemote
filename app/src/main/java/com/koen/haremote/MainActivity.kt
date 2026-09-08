package com.koen.haremote

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.koen.haremote.location.BatteryDiagnostics
import com.koen.haremote.location.DiagnosticLogger
import com.koen.haremote.ui.AdminMapScreen
import com.koen.haremote.ui.AdminPinScreen
import com.koen.haremote.ui.ButtonEditScreen
import com.koen.haremote.ui.HomeScreen
import com.koen.haremote.ui.HostEditScreen
import com.koen.haremote.ui.SettingsScreen
import com.koen.haremote.ui.components.CinemaBackground
import com.koen.haremote.ui.theme.HARemoteTheme
import java.io.BufferedReader
import java.io.OutputStreamWriter

class MainActivity : ComponentActivity() {

    private val viewModel: HaRemoteViewModel by viewModels { HaRemoteViewModel.factory(application) }

    // Foreground location (+ notifications on 13+) is requested first. Background location
    // has to be a separate, standalone request on Android 10+, so it's chained afterwards.
    private val requestForegroundPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val locationGranted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                results[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (locationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                requestBackgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }

    private val requestBackgroundPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op, state is read again on demand */ }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { writeExport(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { readImport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRuntimePermissions()

        setContent {
            HARemoteTheme {
                CinemaBackground {
                    val navController = rememberNavController()
                    val settings by viewModel.settings.collectAsState()
                    val lastResult by viewModel.lastResult.collectAsState()
                    val isAdminCapable by viewModel.isAdminCapable.collectAsState()
                    val adminAuthResult by viewModel.adminAuthResult.collectAsState()
                    val adminConnected by viewModel.adminConnected.collectAsState()
                    val adminClients by viewModel.adminClients.collectAsState()
                    val adminLocationHistory by viewModel.adminLocationHistory.collectAsState()
                    val tcpConnected by viewModel.tcpConnected.collectAsState()
                    val pendingButtonIds by viewModel.pendingButtonIds.collectAsState()

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                buttons = settings.buttons,
                                lastResult = lastResult,
                                onButtonPressed = viewModel::pressButton,
                                onResultConsumed = viewModel::consumeLastResult,
                                onOpenSettings = { navController.navigate("settings") },
                                tcpConnected = tcpConnected,
                                pendingButtonIds = pendingButtonIds,
                                isAdminCapable = isAdminCapable,
                                onSwipeToAdmin = {
                                    viewModel.resetAdminSession()
                                    viewModel.onAdminScreenOpened()
                                    navController.navigate("adminPin")
                                }
                            )
                        }
                        composable("adminPin") {
                            AdminPinScreen(
                                authResult = adminAuthResult,
                                connected = adminConnected,
                                onSubmit = { code -> viewModel.submitAdminCode(code) },
                                onConsumeResult = viewModel::consumeAdminAuthResult,
                                onAuthSuccess = {
                                    navController.navigate("adminMap") {
                                        popUpTo("adminPin") { inclusive = true }
                                    }
                                },
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("adminMap") {
                            AdminMapScreen(
                                clients = adminClients,
                                history = adminLocationHistory,
                                connected = adminConnected,
                                onClientSelected = { client -> viewModel.requestLocationHistory(client) },
                                onBack = {
                                    viewModel.resetAdminSession()
                                    navController.popBackStack("home", inclusive = false)
                                }
                            )
                        }
                        composable("settings") {
                            var diagnosticsStatus by remember {
                                mutableStateOf(BatteryDiagnostics.current(this@MainActivity))
                            }
                            SettingsScreen(
                                settings = settings,
                                onBack = { navController.popBackStack() },
                                onEditButton = { button -> navController.navigate("editButton/${button.id}") },
                                onEditHost = { host -> navController.navigate("editHost/${host.id}") },
                                onAddHost = {
                                    val newHost = viewModel.addHost()
                                    navController.navigate("editHost/${newHost.id}")
                                },
                                onTcpHostChanged = viewModel::setTcpServerHost,
                                onTcpPortChanged = viewModel::setTcpServerPort,
                                onGpsLoggingToggle = { enabled ->
                                    if (enabled) requestRuntimePermissions()
                                    viewModel.setGpsLoggingEnabled(enabled)
                                },
                                onRequestIgnoreBatteryOptimizations = { requestIgnoreBatteryOptimizations() },
                                diagnosticsStatus = diagnosticsStatus,
                                onRefreshDiagnostics = {
                                    diagnosticsStatus = BatteryDiagnostics.current(this@MainActivity)
                                },
                                onShareDiagnosticLog = { shareDiagnosticLog() },
                                onClearDiagnosticLog = { clearDiagnosticLog() },
                                onExport = { exportLauncher.launch("haremote-settings.json") },
                                onImport = { importLauncher.launch(arrayOf("application/json", "text/*")) }
                            )
                        }
                        composable(
                            route = "editButton/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getInt("id") ?: -1
                            val button = settings.buttons.firstOrNull { it.id == id }
                            if (button != null) {
                                ButtonEditScreen(
                                    button = button,
                                    hosts = settings.hosts,
                                    onBack = { navController.popBackStack() },
                                    onSave = { updated ->
                                        viewModel.updateButton(updated)
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }
                        composable(
                            route = "editHost/{id}",
                            arguments = listOf(navArgument("id") { type = NavType.IntType })
                        ) { backStackEntry ->
                            val id = backStackEntry.arguments?.getInt("id") ?: -1
                            val host = settings.hosts.firstOrNull { it.id == id }
                            if (host != null) {
                                HostEditScreen(
                                    host = host,
                                    onBack = { navController.popBackStack() },
                                    onSave = { updated ->
                                        viewModel.updateHost(updated)
                                        navController.popBackStack()
                                    },
                                    onDelete = {
                                        viewModel.deleteHost(host.id)
                                        navController.popBackStack()
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestRuntimePermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestForegroundPermissions.launch(permissions.toTypedArray())
    }

    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Batterijoptimalisatie staat al uit voor HARemote", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Shares DiagnosticLogger's log file via the system share sheet (email, Drive, WhatsApp,
     * ...) so it can be retrieved straight from the phone — no USB debugging / logcat needed,
     * since the device isn't permanently connected to a debug PC.
     */
    private fun shareDiagnosticLog() {
        val file = DiagnosticLogger.logFile(this)
        if (!file.exists()) {
            Toast.makeText(this, "Nog geen logbestand aanwezig", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Deel logbestand"))
    }

    /** Wipes the diagnostics log so the next test window starts clean. */
    private fun clearDiagnosticLog() {
        DiagnosticLogger.clear(this)
        Toast.makeText(this, "Logbestand gewist", Toast.LENGTH_SHORT).show()
    }

    private fun writeExport(uri: Uri) {
        runCatching {
            contentResolver.openOutputStream(uri)?.use { stream ->
                OutputStreamWriter(stream).use { it.write(viewModel.exportJson()) }
            }
        }.onSuccess {
            Toast.makeText(this, "Instellingen geëxporteerd", Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Exporteren mislukt: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun readImport(uri: Uri) {
        runCatching {
            contentResolver.openInputStream(uri)?.bufferedReader()?.use(BufferedReader::readText)
                ?: throw IllegalStateException("Bestand kon niet gelezen worden")
        }.onSuccess { text ->
            val ok = viewModel.importJson(text)
            val message = if (ok) "Instellingen geïmporteerd" else "Ongeldig JSON-bestand"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(this, "Importeren mislukt: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
