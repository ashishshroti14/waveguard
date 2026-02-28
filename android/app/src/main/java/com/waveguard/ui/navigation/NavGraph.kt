package com.waveguard.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.waveguard.ui.screens.calibration.CalibrationScreen
import com.waveguard.ui.screens.dashboard.DashboardScreen
import com.waveguard.ui.screens.history.HistoryScreen
import com.waveguard.ui.screens.settings.SettingsScreen
import com.waveguard.ui.screens.setup.SetupScreen

object Routes {
    const val SETUP = "setup"
    const val DASHBOARD = "dashboard"
    const val CALIBRATION = "calibration"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

@Composable
fun WaveGuardNavGraph(
    navController: NavHostController,
    startDestination: String = Routes.SETUP
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.SETUP) {
            SetupScreen(
                onSetupComplete = {
                    navController.navigate(Routes.CALIBRATION) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                },
                onSkipToPhoneOnly = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.SETUP) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CALIBRATION) {
            CalibrationScreen(
                onCalibrationComplete = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.CALIBRATION) { inclusive = true }
                    }
                },
                onSkip = {
                    navController.navigate(Routes.DASHBOARD) {
                        popUpTo(Routes.CALIBRATION) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.DASHBOARD) {
            DashboardScreen(
                onNavigateToHistory = { navController.navigate(Routes.HISTORY) },
                onNavigateToSettings = { navController.navigate(Routes.SETTINGS) },
                onNavigateToCalibration = { navController.navigate(Routes.CALIBRATION) }
            )
        }

        composable(Routes.HISTORY) {
            HistoryScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(
                onNavigateBack = { navController.navigateUp() }
            )
        }
    }
}
