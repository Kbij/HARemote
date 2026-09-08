package com.koen.haremote.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import com.koen.haremote.ui.theme.CinemaBlack
import com.koen.haremote.ui.theme.CinemaGold
import com.koen.haremote.ui.theme.CinemaSurface
import androidx.compose.ui.unit.dp

/**
 * A dark, cinema-style backdrop: a soft vignette plus two faint film-strip sprocket
 * rails along the edges, so the app reads as "home theater remote" rather than a
 * generic settings form.
 */
@Composable
fun CinemaBackground(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(CinemaSurface, CinemaBlack),
                    radius = 1400f
                )
            )
    ) {
        FilmStripRail(modifier = Modifier.align(Alignment.CenterStart))
        FilmStripRail(modifier = Modifier.align(Alignment.CenterEnd))
        content()
    }
}

@Composable
private fun FilmStripRail(modifier: Modifier = Modifier) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .width(18.dp)
    ) {
        val holeRadius = 4.dp.toPx()
        val spacing = 28.dp.toPx()
        val x = size.width / 2f
        var y = spacing / 2f
        while (y < size.height) {
            drawCircle(
                color = CinemaGold.copy(alpha = 0.12f),
                radius = holeRadius,
                center = Offset(x, y)
            )
            y += spacing
        }
    }
}
