package com.waveguard.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.waveguard.data.model.PresenceState
import com.waveguard.ui.theme.AmberWarning
import com.waveguard.ui.theme.GreenSafe
import com.waveguard.ui.theme.RedAlert
import com.waveguard.ui.theme.TextSecondary

/**
 * Animated pulsing circle that reflects the current [PresenceState].
 *
 * Colors:
 *  - EMPTY            → green
 *  - PRESENCE/MOVEMENT → amber
 *  - FALL             → red
 *  - UNKNOWN          → neutral
 *
 * Pulse animation is only active when [isActive] is true.
 */
@Composable
fun PresenceIndicator(
    presenceState: PresenceState,
    modifier: Modifier = Modifier,
    size: Dp = 160.dp,
    isActive: Boolean = true,
    animationDurationMs: Int = 1200
) {
    val indicatorColor = presenceState.toIndicatorColor()

    val infiniteTransition = rememberInfiniteTransition(label = "presence_pulse")

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.25f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDurationMs, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = if (isActive) 0.05f else 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDurationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val outerRingAlpha by infiniteTransition.animateFloat(
        initialValue = 0.2f,
        targetValue = if (isActive) 0.0f else 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDurationMs * 2, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "outer_ring_alpha"
    )

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // Outermost ripple ring
        Box(
            modifier = Modifier
                .size(size)
                .scale(pulseScale * 1.15f)
                .alpha(outerRingAlpha)
                .background(indicatorColor.copy(alpha = 0.15f), CircleShape)
        )

        // Middle pulse ring
        Box(
            modifier = Modifier
                .size(size * 0.85f)
                .scale(pulseScale)
                .alpha(pulseAlpha)
                .background(indicatorColor.copy(alpha = 0.25f), CircleShape)
        )

        // Inner solid circle
        Box(
            modifier = Modifier
                .size(size * 0.65f)
                .background(indicatorColor.copy(alpha = 0.85f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = presenceState.toIcon(),
                fontSize = (size.value * 0.22f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

private fun PresenceState.toIndicatorColor(): Color = when (this) {
    PresenceState.EMPTY -> GreenSafe
    PresenceState.PRESENCE_DETECTED -> AmberWarning
    PresenceState.MOVEMENT_DETECTED -> AmberWarning
    PresenceState.FALL_DETECTED -> RedAlert
    PresenceState.UNKNOWN -> TextSecondary
}

private fun PresenceState.toIcon(): String = when (this) {
    PresenceState.EMPTY -> "🏠"
    PresenceState.PRESENCE_DETECTED -> "🧍"
    PresenceState.MOVEMENT_DETECTED -> "🚶"
    PresenceState.FALL_DETECTED -> "⚠️"
    PresenceState.UNKNOWN -> "〜"
}
