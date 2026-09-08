package com.koen.haremote.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.koen.haremote.data.ButtonConfig
import com.koen.haremote.ui.theme.CinemaGold
import com.koen.haremote.ui.theme.CinemaGreen
import com.koen.haremote.ui.theme.CinemaMutedText
import com.koen.haremote.ui.theme.CinemaRed
import com.koen.haremote.ui.theme.CinemaSurface

/** How far (in dp) a leftward drag has to travel before it counts as "swipe to admin". Chosen
 *  to comfortably clear normal button-press jitter while still feeling deliberate, not
 *  accidental - this is a real navigation, not a subtle affordance. */
private val ADMIN_SWIPE_THRESHOLD_DP = 96.dp

private const val BUTTONS_PER_ROW = 2

@Composable
fun HomeScreen(
    buttons: List<ButtonConfig>,
    lastResult: String?,
    onButtonPressed: (ButtonConfig) -> Unit,
    onResultConsumed: () -> Unit,
    onOpenSettings: () -> Unit,
    tcpConnected: Boolean = false,
    pendingButtonIds: Set<Int> = emptySet(),
    isAdminCapable: Boolean = false,
    onSwipeToAdmin: () -> Unit = {}
) {
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(lastResult) {
        lastResult?.let {
            snackbarHostState.showSnackbar(it)
            onResultConsumed()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
                // Only wired up at all when the server has granted this device admin
                // capability (see HaRemoteViewModel.isAdminCapable) - re-installed whenever
                // that flag flips, so it can never fire from a stale "used to be admin" state.
                .pointerInput(isAdminCapable) {
                    if (!isAdminCapable) return@pointerInput
                    val thresholdPx = ADMIN_SWIPE_THRESHOLD_DP.toPx()
                    var totalDragPx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { totalDragPx = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            totalDragPx += dragAmount
                            change.consume()
                        },
                        onDragEnd = {
                            if (totalDragPx <= -thresholdPx) {
                                onSwipeToAdmin()
                            }
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "HAREMOTE",
                        style = MaterialTheme.typography.headlineLarge,
                        color = CinemaGold
                    )
                    Text(
                        text = "sferen · verlichting · tv",
                        style = MaterialTheme.typography.bodyMedium,
                        color = CinemaMutedText,
                        letterSpacing = 1.sp
                    )
                }
                ConnectionLed(connected = tcpConnected)
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Instellingen",
                        tint = CinemaMutedText
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                buttons.chunked(BUTTONS_PER_ROW).forEach { rowButtons ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        rowButtons.forEach { button ->
                            RemoteButton(
                                button = button,
                                isPending = button.id in pendingButtonIds,
                                modifier = Modifier.weight(1f),
                                onClick = { onButtonPressed(button) }
                            )
                        }
                        // An odd number of buttons leaves the last row incomplete. Without
                        // this, that lone button's own Modifier.weight(1f) would claim the
                        // *entire* row width (instead of the usual half), and since its
                        // height is width-driven (aspectRatio(1.15f)), it would then demand
                        // roughly double the height too - overflowing right past this row's
                        // share of the screen. Invisible spacers keep every button the same
                        // width (and therefore the same height) no matter how a row fills up.
                        repeat(BUTTONS_PER_ROW - rowButtons.size) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Small status dot next to the settings gear: green while the primary (GPS-logging) TCP
 *  connection is up, red otherwise. Deliberately just [tcpConnected] (LocationLoggingService's
 *  connection, via HaRemoteViewModel/TcpConnectionStatus) - the separate admin connection has
 *  its own "Niet verbonden met server" text on the admin screens, this is about the connection
 *  that matters for the app's main purpose. */
@Composable
private fun ConnectionLed(connected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(end = 4.dp)
            .size(10.dp)
            .background(
                color = if (connected) CinemaGreen else CinemaRed,
                shape = CircleShape
            )
    )
}

/** Idle border alpha, matching the value this project used before the pending-pulse existed. */
private const val IDLE_BORDER_ALPHA = 0.55f
private const val PENDING_BORDER_ALPHA_LOW = 0.30f
private const val PENDING_PULSE_HALF_CYCLE_MS = 550

@Composable
private fun RemoteButton(
    button: ButtonConfig,
    isPending: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    // A looping Animatable driven by a LaunchedEffect(isPending) rather than
    // rememberInfiniteTransition: the loop coroutine only exists (and only keeps
    // recomposing this button) while the call is actually in flight - six idle buttons
    // don't sit there animating forever, and toggling isPending back to false cancels the
    // loop automatically (LaunchedEffect restarts/cancels on key change) and eases back to
    // the normal resting alpha instead of snapping.
    val borderAlpha = remember { Animatable(IDLE_BORDER_ALPHA) }
    LaunchedEffect(isPending) {
        if (isPending) {
            while (true) {
                borderAlpha.animateTo(
                    1f,
                    animationSpec = tween(PENDING_PULSE_HALF_CYCLE_MS, easing = FastOutSlowInEasing)
                )
                borderAlpha.animateTo(
                    PENDING_BORDER_ALPHA_LOW,
                    animationSpec = tween(PENDING_PULSE_HALF_CYCLE_MS, easing = FastOutSlowInEasing)
                )
            }
        } else {
            borderAlpha.animateTo(IDLE_BORDER_ALPHA, animationSpec = tween(200))
        }
    }

    OutlinedButton(
        onClick = onClick,
        enabled = !isPending,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1.15f),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = CinemaSurface,
            disabledContainerColor = CinemaSurface
        ),
        border = BorderStroke(if (isPending) 1.5.dp else 1.dp, CinemaGold.copy(alpha = borderAlpha.value)),
        contentPadding = PaddingValues(8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = button.icon.toImageVector(),
                contentDescription = button.label,
                tint = CinemaGold,
                modifier = Modifier
            )
            Text(
                text = button.label.ifBlank { "Knop ${button.id}" },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                modifier = Modifier.padding(top = 8.dp),
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
