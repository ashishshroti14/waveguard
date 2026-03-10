package com.waveguard.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waveguard.ui.theme.AmberWarning
import com.waveguard.ui.theme.CardDark
import com.waveguard.ui.theme.CyanActive
import com.waveguard.ui.theme.NavyBackground
import com.waveguard.ui.theme.SurfaceDark
import com.waveguard.ui.theme.TextPrimary
import com.waveguard.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val detectionMode by viewModel.detectionMode.collectAsStateWithLifecycle()
    val sensitivityLevel by viewModel.sensitivityLevel.collectAsStateWithLifecycle()
    val notificationsEnabled by viewModel.notificationsEnabled.collectAsStateWithLifecycle()
    val fallAlertEnabled by viewModel.fallAlertEnabled.collectAsStateWithLifecycle()
    val presenceAlertEnabled by viewModel.presenceAlertEnabled.collectAsStateWithLifecycle()
    val remoteNodesEnabled by viewModel.remoteNodesEnabled.collectAsStateWithLifecycle()
    val remoteNodesBaseUrl by viewModel.remoteNodesBaseUrl.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NavyBackground)
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // Detection mode section
            SettingsSectionHeader(title = "Detection Mode")
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Choose the signal source for presence detection.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilterChip(
                            selected = detectionMode == DetectionMode.RSSI,
                            onClick = { viewModel.updateDetectionMode(DetectionMode.RSSI) },
                            label = { Text("Phase 1 — RSSI") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanActive,
                                selectedLabelColor = NavyBackground,
                                containerColor = SurfaceDark,
                                labelColor = TextSecondary
                            )
                        )
                        FilterChip(
                            selected = detectionMode == DetectionMode.CSI,
                            onClick = { viewModel.updateDetectionMode(DetectionMode.CSI) },
                            label = { Text("Phase 2 — CSI") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CyanActive,
                                selectedLabelColor = NavyBackground,
                                containerColor = SurfaceDark,
                                labelColor = TextSecondary
                            )
                        )
                    }
                    if (detectionMode == DetectionMode.CSI) {
                        Text(
                            text = "⚠  Requires ESP32 sensor hardware.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AmberWarning
                        )
                    }
                }
            }

            // External nodes section
            SettingsSectionHeader(title = "External Node Feed")
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    ToggleSettingRow(
                        title = "Use RuView Node Data",
                        subtitle = "Fuse node-1/node-2 telemetry with phone RSSI",
                        checked = remoteNodesEnabled,
                        onCheckedChange = { viewModel.updateRemoteNodesEnabled(it) }
                    )
                    HorizontalDivider(color = SurfaceDark, modifier = Modifier.padding(horizontal = 16.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "RuView Base URL",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (remoteNodesEnabled) TextPrimary else TextSecondary
                        )
                        OutlinedTextField(
                            modifier = Modifier.fillMaxWidth(),
                            value = remoteNodesBaseUrl,
                            onValueChange = { viewModel.updateRemoteNodesBaseUrl(it) },
                            enabled = remoteNodesEnabled,
                            singleLine = true,
                            placeholder = { Text("http://192.168.0.100:3000") }
                        )
                        Text(
                            text = "WaveGuard polls /api/v1/sensing/latest and keeps recent node telemetry.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            }

            // Sensitivity section
            SettingsSectionHeader(title = "Detection Sensitivity")
            Card(
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
                            text = "Sensitivity",
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextPrimary
                        )
                        Text(
                            text = sensitivityLabel(sensitivityLevel),
                            style = MaterialTheme.typography.labelLarge,
                            color = CyanActive
                        )
                    }
                    Slider(
                        value = sensitivityLevel,
                        onValueChange = { viewModel.updateSensitivityLevel(it) },
                        valueRange = 0f..1f,
                        colors = SliderDefaults.colors(
                            thumbColor = CyanActive,
                            activeTrackColor = CyanActive,
                            inactiveTrackColor = SurfaceDark
                        )
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Low", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                        Text("High", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                    Text(
                        text = "Higher sensitivity may increase false positives.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            // Notification section
            SettingsSectionHeader(title = "Notifications")
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    ToggleSettingRow(
                        title = "Enable Notifications",
                        subtitle = "Show alerts for detected events",
                        checked = notificationsEnabled,
                        onCheckedChange = { viewModel.updateNotificationsEnabled(it) }
                    )
                    HorizontalDivider(color = SurfaceDark, modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleSettingRow(
                        title = "Fall Alerts",
                        subtitle = "Urgent alert on fall detection",
                        checked = fallAlertEnabled,
                        onCheckedChange = { viewModel.updateFallAlertEnabled(it) },
                        enabled = notificationsEnabled
                    )
                    HorizontalDivider(color = SurfaceDark, modifier = Modifier.padding(horizontal = 16.dp))
                    ToggleSettingRow(
                        title = "Presence Alerts",
                        subtitle = "Notify when presence is detected",
                        checked = presenceAlertEnabled,
                        onCheckedChange = { viewModel.updatePresenceAlertEnabled(it) },
                        enabled = notificationsEnabled
                    )
                }
            }

            // About section
            SettingsSectionHeader(title = "About")
            Card(
                colors = CardDefaults.cardColors(containerColor = CardDark),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AboutRow(label = "App", value = "WaveGuard")
                    AboutRow(label = "Version", value = "2.0.0-alpha")
                    AboutRow(label = "Detection Engine", value = "Wi-Fi RSSI / CSI")
                    AboutRow(label = "ML Model", value = "WiFlexFormer (Phase 2)")
                    AboutRow(label = "Build", value = "v2 — Spectral + CUSUM")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = CyanActive,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun ToggleSettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) TextPrimary else TextSecondary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NavyBackground,
                checkedTrackColor = CyanActive,
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = SurfaceDark
            )
        )
    }
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}

private fun sensitivityLabel(level: Float): String = when {
    level < 0.25f -> "Low"
    level < 0.5f -> "Medium-Low"
    level < 0.75f -> "Medium"
    level < 0.9f -> "High"
    else -> "Maximum"
}
