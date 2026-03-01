package com.waveguard.ui.navigation

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.waveguard.ui.screens.calibration.CalibrationScreen
import com.waveguard.ui.screens.dashboard.DashboardScreen
import com.waveguard.ui.screens.history.HistoryScreen
import com.waveguard.ui.screens.onboarding.OnboardingScreen
import com.waveguard.ui.screens.settings.SettingsScreen
import com.waveguard.ui.screens.setup.SetupScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val SETUP = "setup"
    const val DASHBOARD = "dashboard"
    const val CALIBRATION = "calibration"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
}

private const val PREFS_NAME = "waveguard_prefs"
private const val KEY_ONBOARDING_DONE = "onboarding_complete"

/** Entry-point composable used by [com.waveguard.MainActivity]. */
@Composable
fun NavGraph() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val startDestination = remember { if (prefs.getBoolean(KEY_ONBOARDING_DONE, false)) Routes.SETUP else Routes.ONBOARDING }
    WaveGuardNavGraph(
        navController = rememberNavController(),
        startDestination = startDestination,
        onOnboardingComplete = { prefs.edit().putBoolean(KEY_ONBOARDING_DONE, true).commit() }
    )
}

@Composable
fun WaveGuardNavGraph(
    navController: NavHostController,
    startDestination: String = Routes.SETUP,
    onOnboardingComplete: () -> Unit = {}
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                onOnboardingComplete = {
                    onOnboardingComplete()
                    navController.navigate(Routes.SETUP) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                }
            )
        }

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
