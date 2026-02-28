package com.waveguard.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waveguard.data.model.PresenceState
import com.waveguard.ui.theme.AmberWarning
import com.waveguard.ui.theme.CardDark
import com.waveguard.ui.theme.GreenSafe
import com.waveguard.ui.theme.NavyBackground
import com.waveguard.ui.theme.RedAlert
import com.waveguard.ui.theme.SurfaceDark
import com.waveguard.ui.theme.TextPrimary
import com.waveguard.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Card showing the room name, current presence status, and last-updated timestamp.
 * The card background animates between states via [animateColorAsState].
 */
@Composable
fun RoomStatusCard(
    roomName: String,
    presenceState: PresenceState,
    lastUpdated: Long,
    modifier: Modifier = Modifier
) {
    val targetCardColor = presenceState.toCardBackgroundColor()
    val animatedCardColor by animateColorAsState(
        targetValue = targetCardColor,
        animationSpec = tween(durationMillis = 600),
        label = "room_card_color"
    )

    val statusColor = presenceState.toStatusColor()
    val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = animatedCardColor),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column {
                    Text(
                        text = roomName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = presenceState.toStatusLabel(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }
                Text(
                    text = presenceState.toEmoji(),
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Last updated: ${timeFormatter.format(Date(lastUpdated))}",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary
            )
        }
    }
}

private fun PresenceState.toCardBackgroundColor(): Color = when (this) {
    PresenceState.EMPTY -> CardDark
    PresenceState.PRESENCE_DETECTED -> AmberWarning.copy(alpha = 0.12f).compositeOver(CardDark)
    PresenceState.MOVEMENT_DETECTED -> AmberWarning.copy(alpha = 0.18f).compositeOver(CardDark)
    PresenceState.FALL_DETECTED -> RedAlert.copy(alpha = 0.22f).compositeOver(CardDark)
    PresenceState.UNKNOWN -> SurfaceDark
}

private fun PresenceState.toStatusColor(): Color = when (this) {
    PresenceState.EMPTY -> GreenSafe
    PresenceState.PRESENCE_DETECTED -> AmberWarning
    PresenceState.MOVEMENT_DETECTED -> AmberWarning
    PresenceState.FALL_DETECTED -> RedAlert
    PresenceState.UNKNOWN -> TextSecondary
}

private fun PresenceState.toStatusLabel(): String = when (this) {
    PresenceState.EMPTY -> "Empty"
    PresenceState.PRESENCE_DETECTED -> "Presence Detected"
    PresenceState.MOVEMENT_DETECTED -> "Movement Detected"
    PresenceState.FALL_DETECTED -> "FALL DETECTED"
    PresenceState.UNKNOWN -> "Unknown"
}

private fun PresenceState.toEmoji(): String = when (this) {
    PresenceState.EMPTY -> "🏠"
    PresenceState.PRESENCE_DETECTED -> "🧍"
    PresenceState.MOVEMENT_DETECTED -> "🚶"
    PresenceState.FALL_DETECTED -> "⚠️"
    PresenceState.UNKNOWN -> "❓"
}

/**
 * Simple porter-duff "over" composite for tinting a base color.
 * Returns [other] blended with this color on top.
 */
private fun Color.compositeOver(other: Color): Color {
    val a = this.alpha
    return Color(
        red = (this.red * a + other.red * (1f - a)).coerceIn(0f, 1f),
        green = (this.green * a + other.green * (1f - a)).coerceIn(0f, 1f),
        blue = (this.blue * a + other.blue * (1f - a)).coerceIn(0f, 1f),
        alpha = (a + other.alpha * (1f - a)).coerceIn(0f, 1f)
    )
}
