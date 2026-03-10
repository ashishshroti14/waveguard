package com.waveguard.ui.screens.dashboard

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waveguard.data.model.ActivityType
import com.waveguard.data.model.AlertEvent
import com.waveguard.data.model.AlertType
import com.waveguard.data.model.PresenceState
import com.waveguard.ui.components.PresenceIndicator
import com.waveguard.ui.components.SignalSeries
import com.waveguard.ui.components.SignalStrengthGraph
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
fun DashboardScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCalibration: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val presenceState by viewModel.presenceState.collectAsStateWithLifecycle()
    val activityType by viewModel.activityType.collectAsStateWithLifecycle()
    val signalStrength by viewModel.signalStrength.collectAsStateWithLifecycle()
    val phoneSignalStrength by viewModel.phoneSignalStrength.collectAsStateWithLifecycle()
    val node1SignalStrength by viewModel.node1SignalStrength.collectAsStateWithLifecycle()
    val node2SignalStrength by viewModel.node2SignalStrength.collectAsStateWithLifecycle()
    val activeSourceCount by viewModel.activeSourceCount.collectAsStateWithLifecycle()
    val recentAlerts by viewModel.recentAlerts.collectAsStateWithLifecycle()
    val confidence by viewModel.confidence.collectAsStateWithLifecycle()
    val isMonitoring by viewModel.isMonitoring.collectAsStateWithLifecycle()
    val rssiHistory by viewModel.rssiHistory.collectAsStateWithLifecycle()
    val phoneRssiHistory by viewModel.phoneRssiHistory.collectAsStateWithLifecycle()
    val node1RssiHistory by viewModel.node1RssiHistory.collectAsStateWithLifecycle()
    val node2RssiHistory by viewModel.node2RssiHistory.collectAsStateWithLifecycle()
    val calibrationProgress by viewModel.calibrationProgress.collectAsStateWithLifecycle()
    val calibrationSampleCount by viewModel.calibrationSampleCount.collectAsStateWithLifecycle()
    val calibrationFailed by viewModel.calibrationFailed.collectAsStateWithLifecycle()
    val noWifiAtStart by viewModel.noWifiAtStart.collectAsStateWithLifecycle()

    // Request location permission before starting monitoring — required for Wi-Fi scanning.
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        // Whether or not permissions were granted, try to start monitoring.
        // networkCapabilitiesRssi() works without location, so the app can still
        // attempt to function.  The WiFi pre-check in the ViewModel will catch the
        // no-WiFi case.
        viewModel.startMonitoring()
    }

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "WaveGuard",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = CyanActive
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NavyBackground,
                    titleContentColor = CyanActive
                ),
                actions = {
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(
                            imageVector = Icons.Default.History,
                            contentDescription = "Alert History",
                            tint = TextSecondary
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = TextSecondary
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    if (isMonitoring) {
                        viewModel.stopMonitoring()
                    } else {
                        // Request location + notification permissions, then start monitoring
                        val perms = mutableListOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    }
                },
                containerColor = if (isMonitoring) RedAlert else CyanActive,
                contentColor = NavyBackground,
                icon = {
                    Icon(
                        imageVector = if (isMonitoring) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isMonitoring) "Stop Monitoring" else "Start Monitoring"
                    )
                },
                text = {
                    Text(
                        text = if (isMonitoring) "Stop" else "Start Monitoring",
                        fontWeight = FontWeight.SemiBold
                    )
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(NavyBackground)
                .padding(paddingValues),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Presence indicator
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    PresenceIndicator(
                        presenceState = presenceState,
                        size = 180.dp,
                        isActive = isMonitoring
                    )
                }
            }

            // Presence label + confidence
            item {
                val isCalibrating = isMonitoring &&
                    presenceState == PresenceState.UNKNOWN &&
                    calibrationProgress < 1f
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = when {
                            noWifiAtStart && !isMonitoring -> "No Wi-Fi Detected"
                            presenceState == PresenceState.UNKNOWN && !isMonitoring -> "Ready"
                            calibrationFailed -> "Calibration Failed"
                            isCalibrating ->
                                "Calibrating… ${(calibrationProgress * 100).toInt()}%"
                            else -> presenceState.toDisplayName()
                        },
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            noWifiAtStart && !isMonitoring -> AmberWarning
                            calibrationFailed -> RedAlert
                            else -> presenceState.toColor()
                        }
                    )
                    if (noWifiAtStart && !isMonitoring) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Connect to a Wi-Fi network first. Creating a hotspot is not enough — the phone must be connected to a Wi-Fi network as a client.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RedAlert
                        )
                    } else if (noWifiAtStart && isMonitoring) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Wi-Fi not detected — monitoring started anyway. If calibration fails, check your Wi-Fi connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AmberWarning
                        )
                    } else if (!isMonitoring && presenceState == PresenceState.UNKNOWN) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap Start Monitoring to begin",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    } else if (calibrationFailed) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "No Wi-Fi data received. Connect to a Wi-Fi network and restart.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = RedAlert
                        )
                    } else if (isCalibrating) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (calibrationSampleCount > 0)
                                "Collecting data… $calibrationSampleCount samples · Keep room empty"
                            else
                                "Waiting for Wi-Fi data…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    } else if (isMonitoring && confidence > 0f) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Confidence: ${(confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Room status + activity
            item {
                RoomStatusRow(
                    presenceState = presenceState,
                    activityType = activityType,
                    signalStrength = signalStrength,
                    activeSourceCount = activeSourceCount,
                    onRecalibrate = onNavigateToCalibration
                )
            }

            // Live source telemetry
            item {
                LiveSourceStatusCard(
                    isMonitoring = isMonitoring,
                    phoneSignalStrength = phoneSignalStrength,
                    node1SignalStrength = node1SignalStrength,
                    node2SignalStrength = node2SignalStrength,
                    activeSourceCount = activeSourceCount
                )
            }

            // RSSI signal graph
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = CardDark),
                    shape = MaterialTheme.shapes.large
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Live Sensing Visualization",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary
                            )
                            Text(
                                text = if (signalStrength < 0f) "${signalStrength.toInt()} dBm" else "-- dBm",
                                style = MaterialTheme.typography.labelLarge,
                                color = CyanActive
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        SignalStrengthGraph(
                            rssiHistory = rssiHistory,
                            extraSeries = listOf(
                                SignalSeries(
                                    name = "Phone",
                                    values = phoneRssiHistory,
                                    color = CyanActive
                                ),
                                SignalSeries(
                                    name = "Node 1",
                                    values = node1RssiHistory,
                                    color = GreenSafe
                                ),
                                SignalSeries(
                                    name = "Node 2",
                                    values = node2RssiHistory,
                                    color = AmberWarning
                                )
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        SourceLegendRow(
                            phoneSignalStrength = phoneSignalStrength,
                            node1SignalStrength = node1SignalStrength,
                            node2SignalStrength = node2SignalStrength
                        )
                    }
                }
            }

            // Recent alerts
            item {
                Text(
                    text = "Recent Alerts",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (recentAlerts.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = CardDark),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No recent alerts",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } else {
                items(recentAlerts) { alert ->
                    AlertRow(alert = alert)
                }
            }

            // Bottom padding for FAB
            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }
}

@Composable
private fun RoomStatusRow(
    presenceState: PresenceState,
    activityType: ActivityType,
    signalStrength: Float,
    activeSourceCount: Int,
    onRecalibrate: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Activity card
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Activity",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = activityType.toEmoji(),
                    fontSize = 28.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = activityType.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // Signal card
        Card(
            modifier = Modifier.weight(1f),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            shape = MaterialTheme.shapes.large
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Wi-Fi Signal",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📶",
                    fontSize = 28.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (signalStrength < 0f) "${signalStrength.toInt()} dBm" else "-- dBm",
                    style = MaterialTheme.typography.bodySmall,
                    color = CyanActive,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "$activeSourceCount / 3 sources",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun LiveSourceStatusCard(
    isMonitoring: Boolean,
    phoneSignalStrength: Float?,
    node1SignalStrength: Float?,
    node2SignalStrength: Float?,
    activeSourceCount: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Live Source Health",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextPrimary
                )
                Text(
                    text = if (isMonitoring) "$activeSourceCount / 3 online" else "Monitoring off",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isMonitoring && activeSourceCount > 0) GreenSafe else TextSecondary
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SourceSignalTile(
                    modifier = Modifier.weight(1f),
                    title = "Phone",
                    emoji = "\uD83D\uDCF1",
                    color = CyanActive,
                    reading = phoneSignalStrength
                )
                SourceSignalTile(
                    modifier = Modifier.weight(1f),
                    title = "Node 1",
                    emoji = "1️⃣",
                    color = GreenSafe,
                    reading = node1SignalStrength
                )
                SourceSignalTile(
                    modifier = Modifier.weight(1f),
                    title = "Node 2",
                    emoji = "2️⃣",
                    color = AmberWarning,
                    reading = node2SignalStrength
                )
            }
        }
    }
}

@Composable
private fun SourceSignalTile(
    modifier: Modifier = Modifier,
    title: String,
    emoji: String,
    color: Color,
    reading: Float?
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = emoji, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = reading?.let { "${it.toInt()} dBm" } ?: "-- dBm",
                style = MaterialTheme.typography.bodySmall,
                color = if (reading != null) color else TextSecondary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .background(
                            color = if (reading != null) color else TextSecondary.copy(alpha = 0.5f),
                            shape = MaterialTheme.shapes.extraSmall
                        )
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (reading != null) "Live" else "Waiting",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun SourceLegendRow(
    phoneSignalStrength: Float?,
    node1SignalStrength: Float?,
    node2SignalStrength: Float?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        LegendItem("Phone", CyanActive, phoneSignalStrength)
        LegendItem("Node 1", GreenSafe, node1SignalStrength)
        LegendItem("Node 2", AmberWarning, node2SignalStrength)
    }
}

@Composable
private fun LegendItem(
    label: String,
    color: Color,
    reading: Float?
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = MaterialTheme.shapes.extraSmall)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "$label: ${reading?.let { "${it.toInt()} dBm" } ?: "--"}",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun AlertRow(alert: AlertEvent) {
    val alertColor = alertTypeToColor(alert.type)
    val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())
    val dateFormatter = SimpleDateFormat("MMM d", Locale.getDefault())

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(alertColor, shape = MaterialTheme.shapes.extraSmall)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = alert.message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary,
                    maxLines = 1
                )
                Text(
                    text = alert.activityType,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = timeFormatter.format(Date(alert.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                Text(
                    text = dateFormatter.format(Date(alert.timestamp)),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
        }
    }
}

private fun PresenceState.toDisplayName(): String = when (this) {
    PresenceState.UNKNOWN -> "Initializing…"
    PresenceState.EMPTY -> "Room Empty"
    PresenceState.PRESENCE_DETECTED -> "Presence Detected"
    PresenceState.MOVEMENT_DETECTED -> "Movement Detected"
    PresenceState.FALL_DETECTED -> "Fall Detected!"
}

private fun PresenceState.toColor(): Color = when (this) {
    PresenceState.UNKNOWN -> TextSecondary
    PresenceState.EMPTY -> GreenSafe
    PresenceState.PRESENCE_DETECTED -> AmberWarning
    PresenceState.MOVEMENT_DETECTED -> AmberWarning
    PresenceState.FALL_DETECTED -> RedAlert
}

private fun ActivityType.toEmoji(): String = when (this) {
    ActivityType.EMPTY -> "🏠"
    ActivityType.SITTING -> "🪑"
    ActivityType.STANDING -> "🧍"
    ActivityType.WALKING -> "🚶"
    ActivityType.FALLING -> "⚠️"
    ActivityType.UNKNOWN -> "❓"
}

private fun alertTypeToColor(type: String): Color = when (type) {
    AlertType.FALL_DETECTED.name -> RedAlert
    AlertType.INTRUSION_SUSPECTED.name -> RedAlert
    AlertType.PRESENCE_DETECTED.name -> AmberWarning
    AlertType.UNUSUAL_ACTIVITY.name -> AmberWarning
    else -> CyanActive
}
