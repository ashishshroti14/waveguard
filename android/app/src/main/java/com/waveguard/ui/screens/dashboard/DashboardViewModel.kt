package com.waveguard.ui.screens.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
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

    private val _phoneSignalStrength = MutableStateFlow<Float?>(null)
    val phoneSignalStrength: StateFlow<Float?> = _phoneSignalStrength.asStateFlow()

    private val _node1SignalStrength = MutableStateFlow<Float?>(null)
    val node1SignalStrength: StateFlow<Float?> = _node1SignalStrength.asStateFlow()

    private val _node2SignalStrength = MutableStateFlow<Float?>(null)
    val node2SignalStrength: StateFlow<Float?> = _node2SignalStrength.asStateFlow()

    private val _activeSourceCount = MutableStateFlow(0)
    val activeSourceCount: StateFlow<Int> = _activeSourceCount.asStateFlow()

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

    private val _phoneRssiHistory = MutableStateFlow<List<Float>>(emptyList())
    val phoneRssiHistory: StateFlow<List<Float>> = _phoneRssiHistory.asStateFlow()

    private val _node1RssiHistory = MutableStateFlow<List<Float>>(emptyList())
    val node1RssiHistory: StateFlow<List<Float>> = _node1RssiHistory.asStateFlow()

    private val _node2RssiHistory = MutableStateFlow<List<Float>>(emptyList())
    val node2RssiHistory: StateFlow<List<Float>> = _node2RssiHistory.asStateFlow()

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

        // Soft pre-check: warn the user if we can't detect Wi-Fi, but don't block them.
        // The `isWifiConnected()` API is unreliable on some devices / Android versions,
        // so we never prevent the user from starting.  If they truly have no Wi-Fi the
        // calibration will fail after 60 s and show "Calibration Failed" instead.
        if (!isWifiConnected()) {
            _noWifiAtStart.value = true
            // Proceed anyway — don't return.
        }

        _isMonitoring.value = true

        // Apply sensitivity from user settings to the detection engine
        val prefs = context.getSharedPreferences("waveguard_settings", Context.MODE_PRIVATE)
        val sensitivity = prefs.getFloat("sensitivity_level", 0.5f)
        presenceDetector.applySensitivity(sensitivity)

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
                        // Clear the WiFi warning as soon as we get real data
                        if (_noWifiAtStart.value) _noWifiAtStart.value = false
                    }

                    val phoneReadings = mutableListOf<Float>()
                    var node1Reading: Float? = null
                    var node2Reading: Float? = null

                    rssiList.forEach { sample ->
                        when (parseRuViewNodeId(sample.bssid)) {
                            1 -> node1Reading = sample.rssi.toFloat()
                            2 -> node2Reading = sample.rssi.toFloat()
                            else -> phoneReadings.add(sample.rssi.toFloat())
                        }
                    }

                    val phoneReading = if (phoneReadings.isNotEmpty()) {
                        phoneReadings.average().toFloat()
                    } else {
                        null
                    }

                    _phoneSignalStrength.value = phoneReading
                    _node1SignalStrength.value = node1Reading
                    _node2SignalStrength.value = node2Reading

                    appendToHistory(_phoneRssiHistory, phoneReading)
                    appendToHistory(_node1RssiHistory, node1Reading)
                    appendToHistory(_node2RssiHistory, node2Reading)

                    val liveReadings = listOfNotNull(phoneReading, node1Reading, node2Reading)
                    _activeSourceCount.value = liveReadings.size
                    if (liveReadings.isNotEmpty()) {
                        val fusedRssi = liveReadings.average().toFloat()
                        _signalStrength.value = fusedRssi
                        appendToHistory(_rssiHistory, fusedRssi)
                    } else {
                        _signalStrength.value = 0f
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
        _phoneSignalStrength.value = null
        _node1SignalStrength.value = null
        _node2SignalStrength.value = null
        _activeSourceCount.value = 0
        _rssiHistory.value = emptyList()
        _phoneRssiHistory.value = emptyList()
        _node1RssiHistory.value = emptyList()
        _node2RssiHistory.value = emptyList()

        // Stop the detection engine so it can be re-started fresh
        presenceDetector.stop()
        context.startService(WaveGuardService.stopIntent(context))
    }

    /**
     * Returns true when the device has an active Wi-Fi client connection (TRANSPORT_WIFI).
     * Uses `activeNetwork` as the primary source (works on all API levels), then falls back
     * to iterating `allNetworks` (deprecated on API 31+ but still useful as a fallback),
     * then finally checks `WifiManager.connectionInfo` as a last resort.
     */
    @Suppress("DEPRECATION")
    private fun isWifiConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false

        // Primary: check activeNetwork (always available on API 23+)
        val activeNet = cm.activeNetwork
        if (activeNet != null) {
            val caps = cm.getNetworkCapabilities(activeNet)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
        }

        // Fallback: iterate allNetworks (deprecated on API 31+ but still works on many devices)
        try {
            if (cm.allNetworks.any { network ->
                    cm.getNetworkCapabilities(network)
                        ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                }) return true
        } catch (_: Exception) { /* SecurityException on some devices */ }

        // Last resort: WifiManager (works when location permission is granted)
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null && wm.isWifiEnabled) {
                val info = wm.connectionInfo
                if (info != null && info.networkId >= 0) return true
            }
        } catch (_: Exception) { }

        return false
    }

    override fun onCleared() {
        super.onCleared()
        stopMonitoring()
    }

    private fun appendToHistory(
        target: MutableStateFlow<List<Float>>,
        reading: Float?
    ) {
        if (reading == null) return
        val history = target.value.toMutableList()
        history.add(reading)
        if (history.size > MAX_HISTORY_POINTS) history.removeAt(0)
        target.value = history
    }

    private fun parseRuViewNodeId(bssid: String): Int? {
        val prefix = "ruview-node-"
        if (!bssid.startsWith(prefix)) return null
        return bssid.removePrefix(prefix).toIntOrNull()
    }

    companion object {
        private const val MAX_HISTORY_POINTS = 120
    }
}
