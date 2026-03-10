package com.waveguard.data.repository

import android.util.Log
import com.waveguard.data.local.CsiDao
import com.waveguard.data.local.CsiDataEntity
import com.waveguard.data.model.CsiData
import com.waveguard.data.model.RssiData
import com.waveguard.data.model.RuViewTelemetryData
import com.waveguard.data.remote.EspCsiReceiver
import com.waveguard.data.remote.RssiScanner
import com.waveguard.data.remote.RuViewNodeReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unified repository that aggregates CSI data from the ESP32 BLE sensor and Wi-Fi RSSI
 * readings from the device's Wi-Fi radio.
 *
 * CSI packets are persisted to the local Room database as they arrive.  Old records (older
 * than 24 hours) are pruned on each new insertion to keep storage bounded.
 */
private const val TAG = "SensorRepository"

@Singleton
class SensorRepository @Inject constructor(
    private val espCsiReceiver: EspCsiReceiver,
    private val rssiScanner: RssiScanner,
    private val ruViewNodeReceiver: RuViewNodeReceiver,
    private val csiDao: CsiDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ------------------------------------------------------------------
    // ESP32 connection state
    // ------------------------------------------------------------------

    /** `true` when a GATT connection to the ESP32 is active. */
    val isEsp32Connected: StateFlow<Boolean> = espCsiReceiver.isConnected

    // ------------------------------------------------------------------
    // CSI stream
    // ------------------------------------------------------------------

    /**
     * Hot shared flow of [CsiData] packets from the ESP32.  Each packet is also persisted to
     * Room and old records are pruned asynchronously.  Subscribers receive packets only while
     * the flow is being collected; if the ESP32 is not connected this flow does not emit.
     */
    val csiData: Flow<CsiData> = espCsiReceiver.csiFlow
        .onEach { csiData -> persistAndPrune(csiData) }
        .catch { e -> Log.e(TAG, "CSI flow error", e) }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000))

    // ------------------------------------------------------------------
    // RSSI stream
    // ------------------------------------------------------------------

    /**
     * Hot shared flow of Wi-Fi RSSI scan results.  Each emission contains all visible APs from
     * the most recent scan, fused with external RuView node feeds (if enabled).
     */
    val rssiData: Flow<List<RssiData>> = combine(
        rssiScanner.rssiFlow.onStart { emit(emptyList()) },
        ruViewNodeReceiver.nodeRssiFlow.onStart { emit(emptyList()) }
    ) { phoneRssi, nodeRssi ->
        mergeRssiSources(phoneRssi, nodeRssi)
    }
        .catch { e -> Log.e(TAG, "RSSI flow error", e) }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000))

    /**
     * Latest RuView feature/classification telemetry for dashboard visualization.
     */
    val ruViewTelemetry: Flow<RuViewTelemetryData?> = ruViewNodeReceiver.telemetryFlow
        .onStart { emit(null) }
        .catch { e ->
            Log.e(TAG, "RuView telemetry flow error", e)
            emit(null)
        }
        .shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000))

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun persistAndPrune(data: CsiData) {
        scope.launch {
            csiDao.insert(data.toEntity())
            // Keep only the last 24 hours of CSI data
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            csiDao.deleteOlderThan(cutoff)
        }
    }

    private fun mergeRssiSources(
        phoneRssi: List<RssiData>,
        nodeRssi: List<RssiData>
    ): List<RssiData> {
        val merged = linkedMapOf<String, RssiData>()
        phoneRssi.forEach { merged[it.bssid] = it }
        nodeRssi.forEach { merged[it.bssid] = it }
        return merged.values.toList()
    }

    private fun CsiData.toEntity() = CsiDataEntity(
        timestamp = timestamp,
        amplitudes = amplitudes.joinToString(","),
        phases = phases.joinToString(","),
        rssi = rssi,
        noiseFloor = noiseFloor,
        channel = channel,
        bandwidth = bandwidth
    )

    companion object {
        private const val RETENTION_MS = 24L * 60 * 60 * 1_000 // 24 hours
    }
}
