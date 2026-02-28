package com.waveguard.data.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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
private const val MIN_VALID_RSSI = -100
private const val MAX_VALID_RSSI = 0

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
     * device is not connected or the RSSI value is invalid.
     *
     * On Android 10+ (API 29) the primary source is [NetworkCapabilities.getSignalStrength],
     * which does **not** require location permission and is immune to the BSSID/SSID
     * privacy-redaction introduced in Android 12.  The deprecated [WifiManager.connectionInfo]
     * API is kept as a fallback for older devices.
     */
    @Suppress("DEPRECATION")
    private fun connectedNetworkRssi(): RssiData? {
        // Android 10+: prefer NetworkCapabilities – no location permission needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            networkCapabilitiesRssi()?.let { return it }
        }
        return try {
            val info = wifiManager.connectionInfo ?: return null
            val rssi = info.rssi
            // RSSI value of Integer.MIN_VALUE means "no signal / not connected"
            if (rssi == Integer.MIN_VALUE || rssi < -100 || rssi > 0) return null
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

    /**
     * Reads the current Wi-Fi RSSI via [NetworkCapabilities.getSignalStrength] (API 29+).
     * This works without location permission, avoiding the privacy-redaction on Android 12+.
     */
    @Suppress("NewApi") // guarded by SDK_INT check in the only caller
    private fun networkCapabilitiesRssi(): RssiData? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return null
            val caps = cm.getNetworkCapabilities(network) ?: return null
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
            val rssi = caps.signalStrength
            if (rssi == Int.MIN_VALUE || rssi < MIN_VALID_RSSI || rssi > MAX_VALID_RSSI) return null
            RssiData(
                timestamp = System.currentTimeMillis(),
                bssid = "active_ap",
                ssid = "Connected",
                rssi = rssi,
                frequency = 0
            )
        } catch (e: Exception) {
            null
        }
    }
}
