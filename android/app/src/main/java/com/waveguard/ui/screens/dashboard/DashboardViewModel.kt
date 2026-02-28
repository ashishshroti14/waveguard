package com.waveguard.ui.screens.dashboard

import android.content.Context
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.waveguard.data.model.ActivityType
import com.waveguard.data.model.AlertEvent
import com.waveguard.data.model.PresenceState
import com.waveguard.data.repository.AlertRepository
import com.waveguard.data.repository.SensorRepository
import com.waveguard.domain.detection.PresenceDetector
import com.waveguard.service.WaveGuardService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sensorRepository: SensorRepository,
    private val alertRepository: AlertRepository,
    private val presenceDetector: PresenceDetector
) : ViewModel() {

    private val _presenceState = MutableStateFlow(PresenceState.UNKNOWN)
    val presenceState: StateFlow<PresenceState> = _presenceState.asStateFlow()

    private val _activityType = MutableStateFlow(ActivityType.UNKNOWN)
    val activityType: StateFlow<ActivityType> = _activityType.asStateFlow()

    private val _signalStrength = MutableStateFlow(0f)
    val signalStrength: StateFlow<Float> = _signalStrength.asStateFlow()

    private val _recentAlerts = MutableStateFlow<List<AlertEvent>>(emptyList())
    val recentAlerts: StateFlow<List<AlertEvent>> = _recentAlerts.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    val calibrationProgress: StateFlow<Float> = presenceDetector.calibrationProgress

    val calibrationSampleCount: StateFlow<Int> = presenceDetector.calibrationSampleCount

    val calibrationFailed: StateFlow<Boolean> = presenceDetector.calibrationFailed

    private val _rssiHistory = MutableStateFlow<List<Float>>(emptyList())
    val rssiHistory: StateFlow<List<Float>> = _rssiHistory.asStateFlow()

    private var monitoringJob: Job? = null

    init {
        loadRecentAlerts()
    }

    private fun loadRecentAlerts() {
        viewModelScope.launch {
            // Collect alerts from the last 24 hours, take top 3 for display
            alertRepository.getRecentAlerts(hours = 24).collect { alerts ->
                _recentAlerts.value = alerts.take(3)
            }
        }
    }

    fun startMonitoring() {
        if (_isMonitoring.value) return
        _isMonitoring.value = true

        // Start the foreground service, which also wires up presenceDetector.start().
        // PresenceDetector.start() is idempotent so the ViewModel calling it here is also safe.
        val serviceIntent = WaveGuardService.startIntent(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        // Also wire detection pipeline directly so UI updates even when service hasn't started yet
        presenceDetector.start(
            rssiFlow = sensorRepository.rssiData,
            csiFlow = sensorRepository.csiData
        )

        // Collect RSSI stream and maintain rolling history of 60 samples
        monitoringJob = viewModelScope.launch {
            sensorRepository.rssiData.collect { rssiList ->
                if (rssiList.isNotEmpty()) {
                    val avgRssi = rssiList.map { it.rssi }.average().toFloat()
                    _signalStrength.value = avgRssi
                    val history = _rssiHistory.value.toMutableList()
                    history.add(avgRssi)
                    if (history.size > 60) history.removeAt(0)
                    _rssiHistory.value = history
                }
            }
        }

        viewModelScope.launch {
            presenceDetector.presenceState.collect { state ->
                _presenceState.value = state
            }
        }

        viewModelScope.launch {
            presenceDetector.activityType.collect { activity ->
                _activityType.value = activity
            }
        }

        viewModelScope.launch {
            presenceDetector.confidence.collect { conf ->
                _confidence.value = conf
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        _isMonitoring.value = false
        context.startService(WaveGuardService.stopIntent(context))
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }
}
