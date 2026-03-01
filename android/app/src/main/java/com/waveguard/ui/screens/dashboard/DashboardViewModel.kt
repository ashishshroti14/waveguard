package com.waveguard.ui.screens.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    /** True when the user tapped Start but no Wi-Fi connection was detected. */
    private val _noWifiAtStart = MutableStateFlow(false)
    val noWifiAtStart: StateFlow<Boolean> = _noWifiAtStart.asStateFlow()

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
        _noWifiAtStart.value = false

        // Pre-check: is the device connected to Wi-Fi?
        // When the phone IS a hotspot (AP mode), it is NOT a Wi-Fi client, so no RSSI data
        // will ever arrive and calibration will fail.  Give immediate feedback instead.
        if (!isWifiConnected()) {
            _noWifiAtStart.value = true
            return
        }

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

        // Collect RSSI stream and maintain rolling history of 60 samples.
        // All monitoring coroutines are children of monitoringJob so they are
        // cancelled together when stopMonitoring() is called.
        monitoringJob = viewModelScope.launch {
            launch {
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

            launch {
                presenceDetector.presenceState.collect { state ->
                    _presenceState.value = state
                }
            }

            launch {
                presenceDetector.activityType.collect { activity ->
                    _activityType.value = activity
                }
            }

            launch {
                presenceDetector.confidence.collect { conf ->
                    _confidence.value = conf
                }
            }
        }
    }

    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        _isMonitoring.value = false
        _noWifiAtStart.value = false

        // Reset UI-visible state so the next start is clean
        _presenceState.value = PresenceState.UNKNOWN
        _activityType.value = ActivityType.UNKNOWN
        _confidence.value = 0f
        _signalStrength.value = 0f
        _rssiHistory.value = emptyList()

        // Stop the detection engine so it can be re-started fresh
        presenceDetector.stop()
        context.startService(WaveGuardService.stopIntent(context))
    }

    /**
     * Returns true when the device has an active Wi-Fi client connection (TRANSPORT_WIFI).
     * Checks all networks because the system may prefer cellular as default even when
     * a Wi-Fi connection exists.
     */
    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }
}
