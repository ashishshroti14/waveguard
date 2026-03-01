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
     * Emits fresh [List<RssiData>] each time a Wi-Fi scan completes or the system reports
     * a change in Wi-Fi capabilities (including signal strength).
     *
     * Three data sources are combined for maximum reliability:
     * 1. **BroadcastReceiver** — standard scan results (throttled on Android 9+)
     * 2. **NetworkCallback** — real-time WiFi capability changes (API 21+, no location needed)
     * 3. **Periodic poll** — fallback that reads connected-network RSSI every second
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

        // NetworkCallback: real-time WiFi signal updates without location permission.
        // This is the most reliable RSSI source on Android 12+.
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(
                network: android.net.Network,
                caps: NetworkCapabilities
            ) {
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
                val rssi = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    caps.signalStrength
                } else {
                    return // signalStrength not available pre-Q
                }
                if (rssi == Int.MIN_VALUE || rssi < MIN_VALID_RSSI || rssi > MAX_VALID_RSSI) return
                val data = RssiData(
                    timestamp = System.currentTimeMillis(),
                    bssid = "active_ap",
                    ssid = "Connected",
                    rssi = rssi,
                    frequency = 0
                )
                trySend(listOf(data))
            }
        }
        val wifiRequest = android.net.NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        try {
            cm?.registerNetworkCallback(wifiRequest, networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register NetworkCallback", e)
        }

        // Periodic poll: triggers scans + emits connected-network RSSI.
        // Polling at 500 ms (~2 Hz) matches the sampling rate used in WiFi sensing
        // research papers (e.g. Wision, WiFall) for capturing human-motion-induced
        // RSSI fluctuations.  Android scan throttling limits actual scan results to
        // ~4/2 min, but connectionInfo.rssi updates on every poll.
        val scanJob = launch {
            while (isActive) {
                // Always emit the connected AP's live RSSI first (fine-grained, ~1 dB)
                connectedNetworkRssi()?.let { trySend(listOf(it)) }

                @Suppress("MissingPermission")
                val started = wifiManager.startScan()
                if (!started) {
                    Log.d(TAG, "startScan() returned false – using last known results")
                    val cached = parseResults()
                    if (cached.isNotEmpty()) {
                        trySend(cached)
                    }
                }
                delay(500L)
            }
        }

        awaitClose {
            scanJob.cancel()
            try {
                cm?.unregisterNetworkCallback(networkCallback)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister NetworkCallback", e)
            }
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
     * **Primary source**: [WifiManager.connectionInfo] — gives fine-grained (~1 dB) RSSI
     * readings that update on every poll.  This is critical for presence detection, which
     * relies on sub-dB signal fluctuations caused by human movement.
     *
     * **Fallback**: [NetworkCapabilities.getSignalStrength] — does not require location
     * permission but is coarsely quantized (only updates on ≥5 dB shifts), which produces
     * the "flat line" problem.  Used only when connectionInfo is unavailable or returns
     * a redacted/invalid result (common on Android 12+ without location permission).
     */
    @Suppress("DEPRECATION")
    private fun connectedNetworkRssi(): RssiData? {
        // Primary: WifiManager.connectionInfo gives fine-grained RSSI on every poll
        try {
            val info = wifiManager.connectionInfo
            if (info != null) {
                val rssi = info.rssi
                if (rssi != Integer.MIN_VALUE && rssi in -100..0) {
                    val rawSsid = info.ssid ?: ""
                    val ssid = rawSsid.trim('"')
                    val bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" }
                    // Use real BSSID if available (needs location on API 29+)
                    if (bssid != null && ssid.isNotBlank() && ssid != "<unknown ssid>") {
                        return RssiData(
                            timestamp = System.currentTimeMillis(),
                            bssid = bssid,
                            ssid = ssid,
                            rssi = rssi,
                            frequency = info.frequency
                        )
                    }
                    // BSSID/SSID redacted (Android 12+ without location) but RSSI is valid —
                    // still emit it with a synthetic identifier so the detector gets data.
                    return RssiData(
                        timestamp = System.currentTimeMillis(),
                        bssid = "connected_ap",
                        ssid = "Connected",
                        rssi = rssi,
                        frequency = if (info.frequency > 0) info.frequency else 0
                    )
                }
            }
        } catch (_: SecurityException) { /* location not granted — fall through */ }

        // Fallback: NetworkCapabilities (coarse but works without location permission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            networkCapabilitiesRssi()?.let { return it }
        }
        return null
    }

    /**
     * Reads the current Wi-Fi RSSI via [NetworkCapabilities.getSignalStrength] (API 29+).
     * This works without location permission, avoiding the privacy-redaction on Android 12+.
     * Uses `activeNetwork` first (reliable), then falls back to iterating `allNetworks`.
     */
    @Suppress("NewApi") // guarded by SDK_INT check in the only caller
    private fun networkCapabilitiesRssi(): RssiData? {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null

            // Primary: check activeNetwork
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val caps = cm.getNetworkCapabilities(activeNet)
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val rssi = caps.signalStrength
                    if (rssi != Int.MIN_VALUE && rssi in MIN_VALID_RSSI..MAX_VALID_RSSI) {
                        return RssiData(
                            timestamp = System.currentTimeMillis(),
                            bssid = "active_ap",
                            ssid = "Connected",
                            rssi = rssi,
                            frequency = 0
                        )
                    }
                }
            }

            // Fallback: iterate allNetworks
            try {
                val networks = cm.allNetworks
                for (network in networks) {
                    val caps = cm.getNetworkCapabilities(network) ?: continue
                    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                    val rssi = caps.signalStrength
                    if (rssi == Int.MIN_VALUE || rssi < MIN_VALID_RSSI || rssi > MAX_VALID_RSSI) continue
                    return RssiData(
                        timestamp = System.currentTimeMillis(),
                        bssid = "active_ap",
                        ssid = "Connected",
                        rssi = rssi,
                        frequency = 0
                    )
                }
            } catch (_: Exception) { /* SecurityException on some devices */ }

            null
        } catch (e: Exception) {
            null
        }
    }
}
