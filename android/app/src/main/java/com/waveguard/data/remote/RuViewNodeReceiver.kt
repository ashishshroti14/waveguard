package com.waveguard.data.remote

import android.content.Context
import android.util.Log
import com.waveguard.data.model.RssiData
import com.waveguard.data.model.RuViewTelemetryData
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
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

    private data class PollPayload(
        val nodeSample: NodeSample?,
        val telemetry: RuViewTelemetryData?
    )

    private data class SnapshotPayload(
        val nodeSamples: List<NodeSample>,
        val telemetry: RuViewTelemetryData?
    )

    /**
     * Polls RuView's `/api/v1/sensing/latest` endpoint and keeps a short-lived cache for:
     * - per-node RSSI samples (Node 1/Node 2...)
     * - current RuView feature/classification telemetry
     *
     * Preferences:
     * - waveguard_settings.remote_nodes_enabled (Boolean, default=true)
     * - waveguard_settings.remote_nodes_base_url (String, default=http://192.168.0.100:3000)
     */
    private val snapshotFlow: Flow<SnapshotPayload> = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val latestByNode = linkedMapOf<Int, NodeSample>()
        var latestTelemetry: RuViewTelemetryData? = null

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
                    latestTelemetry = null
                    trySend(SnapshotPayload(nodeSamples = emptyList(), telemetry = null))
                    delay(POLL_DISABLED_INTERVAL_MS)
                    continue
                }

                fetchLatestPayload(baseUrl)?.let { payload ->
                    payload.nodeSample?.let { sample ->
                        latestByNode[sample.nodeId] = sample.copy(timestamp = now)
                    }
                    payload.telemetry?.let { telemetry ->
                        latestTelemetry = telemetry.copy(timestamp = now)
                    }
                }

                latestByNode.entries.removeAll { (_, sample) ->
                    now - sample.timestamp > NODE_STALE_MS
                }

                val telemetrySnapshot = latestTelemetry
                if (telemetrySnapshot != null && now - telemetrySnapshot.timestamp > TELEMETRY_STALE_MS) {
                    latestTelemetry = null
                }

                trySend(
                    SnapshotPayload(
                        nodeSamples = latestByNode.values.sortedBy { it.nodeId },
                        telemetry = latestTelemetry
                    )
                )
                delay(POLL_INTERVAL_MS)
            }
        }

        awaitClose { pollJob.cancel() }
    }.conflate()

    /**
     * Latest per-node RSSI values emitted as synthetic [RssiData] items.
     */
    val nodeRssiFlow: Flow<List<RssiData>> = snapshotFlow.map { snapshot ->
        val now = System.currentTimeMillis()
        snapshot.nodeSamples.map { sample ->
            RssiData(
                timestamp = now,
                bssid = "ruview-node-${sample.nodeId}",
                ssid = "RuView Node ${sample.nodeId}",
                rssi = sample.rssiDbm,
                frequency = sample.frequency
            )
        }
    }.conflate()

    /**
     * Latest RuView feature/classification payload for dashboard visualization.
     */
    val telemetryFlow: Flow<RuViewTelemetryData?> = snapshotFlow
        .map { it.telemetry }
        .conflate()

    private fun fetchLatestPayload(baseUrl: String): PollPayload? {
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
            PollPayload(
                nodeSample = parseNodeSample(root),
                telemetry = parseTelemetry(root)
            )
        } catch (e: Exception) {
            Log.d(TAG, "RuView poll failed: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseNodeSample(root: JSONObject): NodeSample? {
        val node = root.optJSONArray("nodes")?.optJSONObject(0) ?: return null
        val nodeId = node.optInt("node_id", -1)
        if (nodeId < 0) return null

        val rssiDbm = parseRssiDbm(root, node) ?: return null
        val frequency = node.optInt("freq_mhz", DEFAULT_NODE_FREQUENCY_MHZ)
            .takeIf { it > 0 } ?: DEFAULT_NODE_FREQUENCY_MHZ

        return NodeSample(
            nodeId = nodeId,
            rssiDbm = rssiDbm,
            frequency = frequency,
            timestamp = System.currentTimeMillis()
        )
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

    private fun parseTelemetry(root: JSONObject): RuViewTelemetryData {
        val features = root.optJSONObject("features")
        val classification = root.optJSONObject("classification")
        val source = root.optString("source", DEFAULT_TELEMETRY_SOURCE)
            .ifBlank { DEFAULT_TELEMETRY_SOURCE }

        return RuViewTelemetryData(
            source = source,
            meanRssi = parseOptionalFloat(features?.optDouble("mean_rssi", Double.NaN) ?: Double.NaN),
            variance = parseOptionalFloat(features?.optDouble("variance", Double.NaN) ?: Double.NaN),
            motionBandPower = parseOptionalFloat(
                features?.optDouble("motion_band_power", Double.NaN) ?: Double.NaN
            ),
            breathingBandPower = parseOptionalFloat(
                features?.optDouble("breathing_band_power", Double.NaN) ?: Double.NaN
            ),
            spectralPower = parseOptionalFloat(features?.optDouble("spectral_power", Double.NaN) ?: Double.NaN),
            dominantFreqHz = parseOptionalFloat(
                features?.optDouble("dominant_freq_hz", Double.NaN) ?: Double.NaN
            ),
            changePoints = if (features?.has("change_points") == true) {
                features.optInt("change_points")
            } else {
                null
            },
            motionLevel = classification?.optString("motion_level", null),
            confidence = parseOptionalFloat(
                classification?.optDouble("confidence", Double.NaN) ?: Double.NaN
            ),
            timestamp = System.currentTimeMillis()
        )
    }

    private fun parseOptionalFloat(value: Double): Float? {
        if (value.isNaN()) return null
        return value.toFloat()
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
        private const val TELEMETRY_STALE_MS = 5_000L

        private const val MIN_RSSI_DBM = -100
        private const val MAX_RSSI_DBM = 0
        private const val DEFAULT_TELEMETRY_SOURCE = "live"
    }
}
