package com.waveguard.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
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

@HiltViewModel
class SettingsViewModel @Inject constructor() : ViewModel() {

    private val _detectionMode = MutableStateFlow(DetectionMode.RSSI)
    val detectionMode: StateFlow<String> = _detectionMode.asStateFlow()

    private val _sensitivityLevel = MutableStateFlow(0.5f)
    val sensitivityLevel: StateFlow<Float> = _sensitivityLevel.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(true)
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _fallAlertEnabled = MutableStateFlow(true)
    val fallAlertEnabled: StateFlow<Boolean> = _fallAlertEnabled.asStateFlow()

    private val _presenceAlertEnabled = MutableStateFlow(true)
    val presenceAlertEnabled: StateFlow<Boolean> = _presenceAlertEnabled.asStateFlow()

    fun updateDetectionMode(mode: String) {
        viewModelScope.launch { _detectionMode.value = mode }
    }

    fun updateSensitivityLevel(level: Float) {
        viewModelScope.launch { _sensitivityLevel.value = level.coerceIn(0f, 1f) }
    }

    fun updateNotificationsEnabled(enabled: Boolean) {
        viewModelScope.launch { _notificationsEnabled.value = enabled }
    }

    fun updateFallAlertEnabled(enabled: Boolean) {
        viewModelScope.launch { _fallAlertEnabled.value = enabled }
    }

    fun updatePresenceAlertEnabled(enabled: Boolean) {
        viewModelScope.launch { _presenceAlertEnabled.value = enabled }
    }
}
