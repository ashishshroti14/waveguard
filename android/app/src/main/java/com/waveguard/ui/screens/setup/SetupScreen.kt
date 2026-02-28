package com.waveguard.ui.screens.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waveguard.ui.theme.AmberWarning
import com.waveguard.ui.theme.CardDark
import com.waveguard.ui.theme.CyanActive
import com.waveguard.ui.theme.GreenSafe
import com.waveguard.ui.theme.NavyBackground
import com.waveguard.ui.theme.RedAlert
import com.waveguard.ui.theme.SurfaceDark
import com.waveguard.ui.theme.TextPrimary
import com.waveguard.ui.theme.TextSecondary

@Composable
fun SetupScreen(
    onSetupComplete: () -> Unit,
    onSkipToPhoneOnly: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel()
) {
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val esp32Found by viewModel.esp32Found.collectAsStateWithLifecycle()
    val connectionState by viewModel.connectionState.collectAsStateWithLifecycle()

    Scaffold(containerColor = NavyBackground) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(NavyBackground)
                .padding(paddingValues)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Header
            Icon(
                imageVector = Icons.Default.Sensors,
                contentDescription = null,
                tint = CyanActive,
                modifier = Modifier.size(72.dp)
            )
            Text(
                text = "WaveGuard Setup",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                textAlign = TextAlign.Center
            )
            Text(
                text = "Choose how to run WaveGuard",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )

            // Mode cards
            ModeCard(
                icon = { Icon(Icons.Default.Bluetooth, contentDescription = null, tint = CyanActive, modifier = Modifier.size(32.dp)) },
                title = "ESP32 Sensor Mode",
                subtitle = "Best accuracy — requires WaveGuard ESP32 hardware",
                badge = "RECOMMENDED",
                badgeColor = CyanActive,
                onSelect = { viewModel.scanForEsp32() }
            )

            ModeCard(
                icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = AmberWarning, modifier = Modifier.size(32.dp)) },
                title = "Phone-Only Mode",
                subtitle = "Uses Wi-Fi RSSI only — no hardware required",
                badge = "PHASE 1",
                badgeColor = AmberWarning,
                onSelect = {
                    viewModel.selectPhoneOnlyMode()
                    onSkipToPhoneOnly()
                }
            )

            // Scan state feedback
            AnimatedContent(
                targetState = connectionState,
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                label = "setup_state"
            ) { state ->
                when (state) {
                    "scanning" -> ScanningContent()
                    "not_found" -> NotFoundContent(onRetry = { viewModel.scanForEsp32() })
                    "connecting" -> ConnectingContent()
                    "connected" -> ConnectedContent(onContinue = onSetupComplete)
                    "failed" -> FailedConnectionContent(
                        onRetry = { viewModel.scanForEsp32() },
                        onSkip = onSkipToPhoneOnly
                    )
                    else -> Spacer(modifier = Modifier.height(0.dp))
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            TextButton(
                onClick = onSkipToPhoneOnly,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("Skip Setup →", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ModeCard(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    badge: String,
    badgeColor: Color,
    onSelect: () -> Unit
) {
    Card(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardDark),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            icon()
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                    Box(
                        modifier = Modifier
                            .background(badgeColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = badge,
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeColor,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun ScanningContent() {
    val infiniteTransition = rememberInfiniteTransition(label = "scan_pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.4f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "scan_pulse_scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "scan_pulse_alpha"
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(pulse)
                    .alpha(alpha)
                    .background(CyanActive.copy(alpha = 0.3f), CircleShape)
            )
            CircularProgressIndicator(color = CyanActive, modifier = Modifier.size(48.dp), strokeWidth = 4.dp)
        }
        Text("Scanning for WaveGuard ESP32…", style = MaterialTheme.typography.bodyMedium, color = CyanActive)
    }
}

@Composable
private fun NotFoundContent(onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("ESP32 not found nearby", style = MaterialTheme.typography.bodyMedium, color = AmberWarning)
        OutlinedButton(
            onClick = onRetry,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanActive)
        ) {
            Text("Scan Again")
        }
    }
}

@Composable
private fun ConnectingContent() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CircularProgressIndicator(color = CyanActive, modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
        Text("Connecting to ESP32…", style = MaterialTheme.typography.bodyMedium, color = CyanActive)
    }
}

@Composable
private fun ConnectedContent(onContinue: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GreenSafe, modifier = Modifier.size(24.dp))
            Text("ESP32 Connected!", style = MaterialTheme.typography.bodyLarge, color = GreenSafe, fontWeight = FontWeight.SemiBold)
        }
        Button(
            onClick = onContinue,
            colors = ButtonDefaults.buttonColors(containerColor = GreenSafe, contentColor = NavyBackground)
        ) {
            Text("Continue to Calibration", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FailedConnectionContent(onRetry: () -> Unit, onSkip: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Connection failed", style = MaterialTheme.typography.bodyMedium, color = RedAlert)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onRetry,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = CyanActive)
            ) {
                Text("Retry")
            }
            TextButton(
                onClick = onSkip,
                colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
            ) {
                Text("Use Phone-Only Mode")
            }
        }
    }
}
