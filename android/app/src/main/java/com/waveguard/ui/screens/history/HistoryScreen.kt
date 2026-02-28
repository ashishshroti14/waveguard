package com.waveguard.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waveguard.data.model.AlertEvent
import com.waveguard.data.model.AlertType
import com.waveguard.ui.theme.AmberWarning
import com.waveguard.ui.theme.CardDark
import com.waveguard.ui.theme.CyanActive
import com.waveguard.ui.theme.GreenSafe
import com.waveguard.ui.theme.NavyBackground
import com.waveguard.ui.theme.RedAlert
import com.waveguard.ui.theme.SurfaceDark
import com.waveguard.ui.theme.TextPrimary
import com.waveguard.ui.theme.TextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onNavigateBack: () -> Unit,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val alerts by viewModel.alerts.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Alert History",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NavyBackground)
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NavyBackground)
                .padding(paddingValues)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = CyanActive
                    )
                }
                alerts.isEmpty() -> {
                    EmptyHistoryContent(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    AlertHistoryList(
                        alerts = alerts,
                        onAcknowledge = { viewModel.acknowledgeAlert(it) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AlertHistoryList(
    alerts: List<AlertEvent>,
    onAcknowledge: (Long) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(alerts, key = { it.id }) { alert ->
            AlertHistoryCard(alert = alert, onAcknowledge = { onAcknowledge(alert.id) })
        }
    }
}

@Composable
private fun AlertHistoryCard(alert: AlertEvent, onAcknowledge: () -> Unit) {
    val dateFormatter = SimpleDateFormat("MMM d, yyyy  HH:mm", Locale.getDefault())
    val alertColor = when (alert.type) {
        AlertType.FALL_DETECTED.name, AlertType.INTRUSION_SUSPECTED.name -> RedAlert
        AlertType.PRESENCE_DETECTED.name, AlertType.UNUSUAL_ACTIVITY.name -> AmberWarning
        else -> CyanActive
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (alert.isAcknowledged) SurfaceDark else CardDark
        ),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(alertColor, shape = MaterialTheme.shapes.extraSmall)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = alertTypeToLabel(alert.type),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = alertColor
                    )
                }
                if (alert.isAcknowledged) {
                    Icon(
                        imageVector = Icons.Default.CheckCircleOutline,
                        contentDescription = "Acknowledged",
                        tint = GreenSafe,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = alert.message,
                style = MaterialTheme.typography.bodyMedium,
                color = TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = dateFormatter.format(Date(alert.timestamp)),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Text(
                        text = "Activity: ${alert.activityType}  ·  ${(alert.confidence * 100).toInt()}% confidence",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                if (!alert.isAcknowledged) {
                    TextButton(
                        onClick = onAcknowledge,
                        colors = ButtonDefaults.textButtonColors(contentColor = CyanActive)
                    ) {
                        Text("Dismiss", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.NotificationsNone,
            contentDescription = null,
            tint = TextSecondary,
            modifier = Modifier.size(72.dp)
        )
        Text(
            text = "No Alerts Yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimary
        )
        Text(
            text = "When WaveGuard detects activity, alerts will appear here.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

private fun alertTypeToLabel(type: String): String = when (type) {
    AlertType.FALL_DETECTED.name -> "Fall Detected"
    AlertType.PRESENCE_DETECTED.name -> "Presence Detected"
    AlertType.INTRUSION_SUSPECTED.name -> "Intrusion Suspected"
    AlertType.UNUSUAL_ACTIVITY.name -> "Unusual Activity"
    AlertType.SYSTEM_STATUS.name -> "System Status"
    else -> type
}
