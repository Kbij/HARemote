package com.koen.haremote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.koen.haremote.ui.theme.CinemaGold
import com.koen.haremote.ui.theme.CinemaMutedText
import com.koen.haremote.ui.theme.CinemaRed

/**
 * Reached by swiping left from [HomeScreen] (only offered when the server has pushed
 * AdminCapability{isAdmin=true} for this device - see HaRemoteViewModel.isAdminCapable).
 * The PIN is only ever validated server-side (AdminAuthRequest / AdminAuthResult); this screen
 * just collects it and reacts to the result.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminPinScreen(
    authResult: Boolean?,
    connected: Boolean,
    onSubmit: (String) -> Unit,
    onConsumeResult: () -> Unit,
    onAuthSuccess: () -> Unit,
    onBack: () -> Unit
) {
    var code by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    LaunchedEffect(authResult) {
        when (authResult) {
            true -> {
                onConsumeResult()
                onAuthSuccess()
            }
            false -> {
                showError = true
                code = ""
                onConsumeResult()
            }
            null -> Unit
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                title = { Text("Admin") },
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
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Voer de admin-code in",
                style = MaterialTheme.typography.titleMedium,
                color = CinemaGold
            )
            Text(
                text = if (connected) "Verbonden met server" else "Niet verbonden met server",
                style = MaterialTheme.typography.bodySmall,
                color = if (connected) CinemaMutedText else CinemaRed,
                modifier = Modifier.padding(top = 4.dp, bottom = 24.dp)
            )

            OutlinedTextField(
                value = code,
                onValueChange = {
                    code = it.filter(Char::isDigit)
                    showError = false
                },
                label = { Text("Code") },
                singleLine = true,
                isError = showError,
                supportingText = if (showError) {
                    { Text("Onjuiste code") }
                } else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = CinemaGold,
                    cursorColor = CinemaGold
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Button(
                onClick = { if (code.isNotEmpty()) onSubmit(code) },
                enabled = code.isNotEmpty() && connected,
                colors = ButtonDefaults.buttonColors(containerColor = CinemaGold),
                contentPadding = PaddingValues(vertical = 14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp)
            ) {
                Text("Bevestigen", color = Color.Black)
            }
        }
    }
}
