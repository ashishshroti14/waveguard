package com.waveguard.ui.screens.calibration

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.waveguard.domain.calibration.CalibrationState
import com.waveguard.ui.theme.AmberWarning
import com.waveguard.ui.theme.CardDark
import com.waveguard.ui.theme.CyanActive
import com.waveguard.ui.theme.GreenSafe
import com.waveguard.ui.theme.NavyBackground
import com.waveguard.ui.theme.RedAlert
import com.waveguard.ui.theme.SurfaceDark
import com.waveguard.ui.theme.TextPrimary
import com.waveguard.ui.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationScreen(
    onCalibrationComplete: () -> Unit,
    onSkip: () -> Unit,
    viewModel: CalibrationViewModel = hiltViewModel()
) {
    val calibrationState by viewModel.calibrationState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = NavyBackground,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Room Calibration",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onSkip) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NavyBackground
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NavyBackground)
                .padding(paddingValues)
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = calibrationState,
                transitionSpec = {
                    fadeIn(animationSpec = tween(400)) togetherWith
                        fadeOut(animationSpec = tween(400))
                },
                label = "calibration_state"
            ) { state ->
                when (state) {
                    is CalibrationState.Idle -> IdleContent(
                        onStart = { viewModel.startCalibration() },
                        onSkip = onSkip
                    )
                    is CalibrationState.CollectingEmpty -> CollectingContent(progress = state.progress)
                    is CalibrationState.Calibrating -> ProcessingContent()
                    is CalibrationState.Complete -> CompleteContent(
                        baselineCount = state.baselineCount,
                        onContinue = onCalibrationComplete
                    )
                    is CalibrationState.Failed -> FailedContent(
                        reason = state.reason,
                        onRetry = { viewModel.resetCalibration() },
                        onSkip = onSkip
                    )
                }
            }
        }
    }
}

@Composable
private fun IdleContent(onStart: () -> Unit, onSkip: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Sensors,
            contentDescription = null,
            tint = CyanActive,
            modifier = Modifier.size(80.dp)
        )
        Text(
            text = "Calibrate Your Room",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = MaterialTheme.shapes.large
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                InstructionItem(step = "1", text = "Make sure the room is completely empty")
                InstructionItem(step = "2", text = "Place your device in the center of the room")
                InstructionItem(step = "3", text = "Keep the room still for 60 seconds")
                InstructionItem(step = "4", text = "WaveGuard will learn the baseline signal pattern")
            }
        }
        Text(
            text = "Calibration improves detection accuracy significantly.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CyanActive, contentColor = NavyBackground)
        ) {
            Text("Start Calibration", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text("Skip for Now")
        }
    }
}

@Composable
private fun CollectingContent(progress: Float) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(500),
        label = "calibration_progress"
    )
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(80.dp),
            color = CyanActive,
            strokeWidth = 6.dp,
            progress = { animatedProgress }
        )
        Text(
            text = "Collecting Baseline…",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = "${(progress * 100).toInt()}% complete",
            style = MaterialTheme.typography.bodyLarge,
            color = CyanActive
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "⚠  Keep the room empty",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AmberWarning
                )
                Text(
                    text = "Do not move around during calibration",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = CyanActive,
                    trackColor = SurfaceDark,
                    progress = { animatedProgress }
                )
            }
        }
    }
}

@Composable
private fun ProcessingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(80.dp),
            color = CyanActive,
            strokeWidth = 6.dp
        )
        Text(
            text = "Computing Baseline",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Analyzing signal patterns…",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
    }
}

@Composable
private fun CompleteContent(baselineCount: Int, onContinue: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = GreenSafe,
            modifier = Modifier.size(80.dp)
        )
        Text(
            text = "Calibration Complete!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = GreenSafe,
            textAlign = TextAlign.Center
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "$baselineCount",
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanActive
                )
                Text(
                    text = "Access points calibrated",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
            }
        }
        Text(
            text = "WaveGuard is now optimized for your room.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = GreenSafe, contentColor = NavyBackground)
        ) {
            Text("Start Monitoring", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

@Composable
private fun FailedContent(reason: String, onRetry: () -> Unit, onSkip: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = RedAlert,
            modifier = Modifier.size(80.dp)
        )
        Text(
            text = "Calibration Failed",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = RedAlert,
            textAlign = TextAlign.Center
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = CardDark),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                modifier = Modifier.padding(20.dp),
                textAlign = TextAlign.Center
            )
        }
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = CyanActive, contentColor = NavyBackground)
        ) {
            Text("Try Again", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
        }
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text("Skip Calibration")
        }
    }
}

@Composable
private fun InstructionItem(step: String, text: String) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .background(SurfaceDark, shape = MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = step,
                style = MaterialTheme.typography.labelMedium,
                color = CyanActive,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary,
            modifier = Modifier.weight(1f)
        )
    }
}
