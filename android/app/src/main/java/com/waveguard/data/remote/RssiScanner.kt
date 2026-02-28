package com.waveguard.data.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.waveguard.data.model.RssiData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RssiScanner"

/**
 * Continuously scans for nearby Wi-Fi access points and emits RSSI readings as a cold [Flow].
 *
 * Android imposes scan throttling in the background (4 scans per 2 minutes for apps targeting
 * API 28+).  This scanner uses a [BroadcastReceiver] to collect results as they arrive and a
 * coroutine loop to trigger fresh scans at ~2 Hz when throttling allows, falling back gracefully
 * when the system drops or defers scan requests.
 *
 * Collectors receive a deduplicated list of [RssiData] objects, one per visible BSSID, filtered
 * to remove hidden SSIDs and entries with obviously invalid RSSI values.
 */
@Singleton
class RssiScanner @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * Emits fresh [List<RssiData>] each time a Wi-Fi scan completes.
     * The flow terminates when the collector is cancelled.
     */
    val rssiFlow: Flow<List<RssiData>> = callbackFlow<List<RssiData>> {
        val scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (!success) {
                    Log.d(TAG, "Scan results may be stale (throttled by system)")
                }
                val results = parseResults()
                if (results.isNotEmpty()) {
                    trySend(results)
                }
            }
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(scanReceiver, filter)
        }

        // Emit connected-network RSSI immediately so the UI reflects signal before first scan
        connectedNetworkRssi()?.let { trySend(listOf(it)) }

        // Coroutine that periodically triggers new scans at ~2 Hz (500 ms).
        // Android throttles background scans; the receiver will still fire with cached results.
        val scanJob = launch {
            while (isActive) {
                @Suppress("MissingPermission")
                val started = wifiManager.startScan()
                if (!started) {
                    Log.d(TAG, "startScan() returned false – using last known results")
                    val cached = parseResults()
                    if (cached.isNotEmpty()) trySend(cached)
                }
                delay(500L)
            }
        }

        awaitClose {
            scanJob.cancel()
            try {
                context.unregisterReceiver(scanReceiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver already unregistered", e)
            }
        }
    }.conflate() // drop intermediate results if the collector is slow

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Reads the latest scan results from [WifiManager] and converts them to [RssiData].
     * Filters out hidden SSIDs and invalid RSSI values (> 0 or < −100 dBm).
     * Also merges in the currently connected network's live RSSI (covers hotspot scenarios
     * where the AP may not appear in scan results due to scan throttling).
     */
    @Suppress("MissingPermission")
    private fun parseResults(): List<RssiData> {
        val now = System.currentTimeMillis()
        val scanList = try {
            wifiManager.scanResults
                ?.filter { result ->
                    !result.SSID.isNullOrBlank() &&
                        result.level in -100..0
                }
                ?.map { result ->
                    RssiData(
                        timestamp = now,
                        bssid = result.BSSID ?: "",
                        ssid = result.SSID ?: "",
                        rssi = result.level,
                        frequency = result.frequency
                    )
                }
                ?.distinctBy { it.bssid }
                ?: emptyList()
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing location permission required for Wi-Fi scan results", e)
            emptyList()
        }

        // Merge in the connected network with its live RSSI
        val connected = connectedNetworkRssi() ?: return scanList
        val merged = scanList.toMutableList()
        val idx = merged.indexOfFirst { it.bssid == connected.bssid }
        if (idx >= 0) merged[idx] = connected else merged.add(0, connected)
        return merged
    }

    /**
     * Returns a [RssiData] entry for the currently associated Wi-Fi network, or null if the
     * device is not connected or the RSSI value is invalid.  Uses the deprecated
     * [WifiManager.connectionInfo] API which remains reliable through API 33.
     */
    @Suppress("DEPRECATION")
    private fun connectedNetworkRssi(): RssiData? = try {
        val info = wifiManager.connectionInfo ?: return null
        val rssi = info.rssi
        if (rssi <= WifiManager.RSSI_UNKNOWN || rssi < -100 || rssi > 0) return null
        val rawSsid = info.ssid ?: return null
        val ssid = rawSsid.trim('"')
        if (ssid.isBlank() || ssid == "<unknown ssid>") return null
        val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" } ?: return null
        RssiData(
            timestamp = System.currentTimeMillis(),
            bssid = bssid,
            ssid = ssid,
            rssi = rssi,
            frequency = info.frequency
        )
    } catch (e: SecurityException) {
        null
    }
}
