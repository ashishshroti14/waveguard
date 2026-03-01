package com.waveguard.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.waveguard.data.model.ActivityType
import com.waveguard.ui.theme.AmberWarning
import com.waveguard.ui.theme.CyanActive
import com.waveguard.ui.theme.GreenSafe
import com.waveguard.ui.theme.RedAlert
import com.waveguard.ui.theme.SurfaceDark
import com.waveguard.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TimelineEvent(
    val activityType: ActivityType,
    val timestamp: Long,
    val note: String? = null
)

/**
 * Vertical timeline of recent activity events.
 * Each item shows a color-coded dot connected by a line, activity name, and timestamp.
 */
@Composable
fun ActivityTimeline(
    events: List<TimelineEvent>,
    modifier: Modifier = Modifier
) {
    val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        events.forEachIndexed { index, event ->
            TimelineItem(
                event = event,
                isLast = index == events.lastIndex,
                timeFormatter = timeFormatter
            )
        }
    }
}

@Composable
private fun TimelineItem(
    event: TimelineEvent,
    isLast: Boolean,
    timeFormatter: SimpleDateFormat
) {
    val dotColor = event.activityType.toTimelineColor()

    Row(modifier = Modifier.fillMaxWidth()) {
        // Left column: dot + connector line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(20.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(40.dp)
                        .background(SurfaceDark)
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Right column: event details
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 0.dp else 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = event.activityType.toEmoji(),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = event.activityType.displayName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = dotColor
                    )
                }
                Text(
                    text = timeFormatter.format(Date(event.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            if (event.note != null) {
                Text(
                    text = event.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

private fun ActivityType.toTimelineColor(): Color = when (this) {
    ActivityType.EMPTY -> GreenSafe
    ActivityType.SITTING -> CyanActive
    ActivityType.STANDING -> CyanActive
    ActivityType.WALKING -> AmberWarning
    ActivityType.FALLING -> RedAlert
    ActivityType.UNKNOWN -> TextSecondary
}

private fun ActivityType.toEmoji(): String = when (this) {
    ActivityType.EMPTY -> "🏠"
    ActivityType.SITTING -> "🪑"
    ActivityType.STANDING -> "🧍"
    ActivityType.WALKING -> "🚶"
    ActivityType.FALLING -> "⚠️"
    ActivityType.UNKNOWN -> "❓"
}
