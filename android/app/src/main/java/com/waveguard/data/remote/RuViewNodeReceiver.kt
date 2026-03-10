package com.waveguard.data.remote

import android.content.Context
import android.util.Log
import com.waveguard.data.model.RssiData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

private const val TAG = "RuViewNodeReceiver"

@Singleton
class RuViewNodeReceiver @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private data class NodeSample(
        val nodeId: Int,
        val rssiDbm: Int,
        val frequency: Int,
        val timestamp: Long
    )

    /**
     * Polls RuView's `/api/v1/sensing/latest` endpoint and exposes the latest per-node RSSI
     * values as synthetic [RssiData] entries so the existing detector can fuse them with phone RSSI.
     *
     * Preferences:
     * - waveguard_settings.remote_nodes_enabled (Boolean, default=true)
     * - waveguard_settings.remote_nodes_base_url (String, default=http://192.168.0.100:3000)
     */
    val nodeRssiFlow: Flow<List<RssiData>> = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latestByNode = linkedMapOf<Int, NodeSample>()

        val pollJob = launch(Dispatchers.IO) {
            while (isActive) {
                val enabled = prefs.getBoolean(KEY_REMOTE_NODES_ENABLED, true)
                val baseUrl = prefs.getString(KEY_REMOTE_NODES_BASE_URL, DEFAULT_REMOTE_NODES_BASE_URL)
                    ?.trim()
                    ?.ifBlank { DEFAULT_REMOTE_NODES_BASE_URL }
                    ?: DEFAULT_REMOTE_NODES_BASE_URL

                val now = System.currentTimeMillis()

                if (!enabled) {
                    if (latestByNode.isNotEmpty()) {
                        latestByNode.clear()
                    }
                    trySend(emptyList())
                    delay(POLL_DISABLED_INTERVAL_MS)
                    continue
                }

                fetchLatestNodeSample(baseUrl)?.let { sample ->
                    latestByNode[sample.nodeId] = sample.copy(timestamp = now)
                }

                latestByNode.entries.removeAll { (_, sample) ->
                    now - sample.timestamp > NODE_STALE_MS
                }

                val payload = latestByNode.values
                    .sortedBy { it.nodeId }
                    .map { sample ->
                        RssiData(
                            timestamp = now,
                            bssid = "ruview-node-${sample.nodeId}",
                            ssid = "RuView Node ${sample.nodeId}",
                            rssi = sample.rssiDbm,
                            frequency = sample.frequency
                        )
                    }

                trySend(payload)
                delay(POLL_INTERVAL_MS)
            }
        }

        awaitClose { pollJob.cancel() }
    }.conflate()

    private fun fetchLatestNodeSample(baseUrl: String): NodeSample? {
        val normalizedBase = if (baseUrl.endsWith("/")) baseUrl.dropLast(1) else baseUrl
        val endpoint = "$normalizedBase/api/v1/sensing/latest"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = REQUEST_TIMEOUT_MS
                readTimeout = REQUEST_TIMEOUT_MS
                doInput = true
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "RuView poll non-200: ${conn.responseCode}")
                return null
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val node = root.optJSONArray("nodes")?.optJSONObject(0) ?: return null
            val nodeId = node.optInt("node_id", -1)
            if (nodeId < 0) return null

            val rssiDbm = parseRssiDbm(root, node) ?: return null
            val frequency = node.optInt("freq_mhz", DEFAULT_NODE_FREQUENCY_MHZ)
                .takeIf { it > 0 } ?: DEFAULT_NODE_FREQUENCY_MHZ

            NodeSample(
                nodeId = nodeId,
                rssiDbm = rssiDbm,
                frequency = frequency,
                timestamp = System.currentTimeMillis()
            )
        } catch (e: Exception) {
            Log.d(TAG, "RuView poll failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseRssiDbm(root: JSONObject, node: JSONObject): Int? {
        val nodeRssi = node.optDouble("rssi_dbm", Double.NaN)
        val featureRssi = root.optJSONObject("features")?.optDouble("mean_rssi", Double.NaN) ?: Double.NaN
        val chosen = when {
            !nodeRssi.isNaN() -> nodeRssi
            !featureRssi.isNaN() -> featureRssi
            else -> return null
        }
        return chosen.roundToInt().coerceIn(MIN_RSSI_DBM, MAX_RSSI_DBM)
    }

    companion object {
        private const val PREFS_NAME = "waveguard_settings"
        private const val KEY_REMOTE_NODES_ENABLED = "remote_nodes_enabled"
        private const val KEY_REMOTE_NODES_BASE_URL = "remote_nodes_base_url"

        private const val DEFAULT_REMOTE_NODES_BASE_URL = "http://192.168.0.100:3000"
        private const val DEFAULT_NODE_FREQUENCY_MHZ = 2412
        private const val REQUEST_TIMEOUT_MS = 1_500
        private const val POLL_INTERVAL_MS = 500L
        private const val POLL_DISABLED_INTERVAL_MS = 1_000L
        private const val NODE_STALE_MS = 5_000L

        private const val MIN_RSSI_DBM = -100
        private const val MAX_RSSI_DBM = 0
    }
}
