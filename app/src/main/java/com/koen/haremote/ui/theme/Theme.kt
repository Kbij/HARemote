package com.koen.haremote.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Always dark: this is meant to feel like a home-cinema remote, not a document editor.
private val HARemoteColorScheme = darkColorScheme(
    primary = CinemaGold,
    onPrimary = CinemaBlack,
    secondary = CinemaGoldMuted,
    onSecondary = CinemaBlack,
    error = CinemaRed,
    onError = CinemaOffWhite,
    background = CinemaBlack,
    onBackground = CinemaOffWhite,
    surface = CinemaSurface,
    onSurface = CinemaOffWhite,
    surfaceVariant = CinemaSurfaceElevated,
    onSurfaceVariant = CinemaMutedText,
    outline = CinemaGoldMuted
)

@Composable
fun HARemoteTheme(content: @Composable () -> Unit) {
    val colorScheme = HARemoteColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
