package com.koen.haremote.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import com.koen.haremote.data.ButtonIcon

fun ButtonIcon.toImageVector(): ImageVector = when (this) {
    ButtonIcon.POWER -> Icons.Filled.PowerSettingsNew
    ButtonIcon.LIGHTBULB -> Icons.Filled.Lightbulb
    ButtonIcon.MOVIE -> Icons.Filled.Movie
    ButtonIcon.VOLUME -> Icons.Filled.VolumeUp
    ButtonIcon.PLAY -> Icons.Filled.PlayArrow
    ButtonIcon.TV -> Icons.Filled.Tv
}
