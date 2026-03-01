package com.waveguard.ui.screens.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Detection mode identifier constants. */
object DetectionMode {
    const val RSSI = "RSSI"
    const val CSI = "CSI"
}

private const val PREFS_NAME = "waveguard_settings"
private const val KEY_DETECTION_MODE = "detection_mode"
private const val KEY_SENSITIVITY = "sensitivity_level"
private const val KEY_NOTIFICATIONS = "notifications_enabled"
private const val KEY_FALL_ALERT = "fall_alert_enabled"
private const val KEY_PRESENCE_ALERT = "presence_alert_enabled"

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext context: Context
) : ViewModel() {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _detectionMode = MutableStateFlow(prefs.getString(KEY_DETECTION_MODE, DetectionMode.RSSI) ?: DetectionMode.RSSI)
    val detectionMode: StateFlow<String> = _detectionMode.asStateFlow()

    private val _sensitivityLevel = MutableStateFlow(prefs.getFloat(KEY_SENSITIVITY, 0.5f))
    val sensitivityLevel: StateFlow<Float> = _sensitivityLevel.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATIONS, true))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _fallAlertEnabled = MutableStateFlow(prefs.getBoolean(KEY_FALL_ALERT, true))
    val fallAlertEnabled: StateFlow<Boolean> = _fallAlertEnabled.asStateFlow()

    private val _presenceAlertEnabled = MutableStateFlow(prefs.getBoolean(KEY_PRESENCE_ALERT, true))
    val presenceAlertEnabled: StateFlow<Boolean> = _presenceAlertEnabled.asStateFlow()

    fun updateDetectionMode(mode: String) {
        viewModelScope.launch {
            _detectionMode.value = mode
            prefs.edit().putString(KEY_DETECTION_MODE, mode).apply()
        }
    }

    fun updateSensitivityLevel(level: Float) {
        viewModelScope.launch {
            val clamped = level.coerceIn(0f, 1f)
            _sensitivityLevel.value = clamped
            prefs.edit().putFloat(KEY_SENSITIVITY, clamped).apply()
        }
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _notificationsEnabled.value = enabled
            prefs.edit().putBoolean(KEY_NOTIFICATIONS, enabled).apply()
        }
    }

    fun updateFallAlertEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _fallAlertEnabled.value = enabled
            prefs.edit().putBoolean(KEY_FALL_ALERT, enabled).apply()
        }
    }

    fun updatePresenceAlertEnabled(enabled: Boolean) {
        viewModelScope.launch {
            _presenceAlertEnabled.value = enabled
            prefs.edit().putBoolean(KEY_PRESENCE_ALERT, enabled).apply()
        }
    }
}
