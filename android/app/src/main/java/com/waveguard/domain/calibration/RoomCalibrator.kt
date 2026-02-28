package com.waveguard.domain.calibration

import com.waveguard.data.model.RssiData
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

// -----------------------------------------------------------------------
// Calibration state model
// -----------------------------------------------------------------------

sealed class CalibrationState {
    /** Calibrator is waiting to be started. */
    object Idle : CalibrationState()

    /** Actively collecting empty-room RSSI samples ([progress] 0.0–1.0). */
    data class CollectingEmpty(val progress: Float) : CalibrationState()

    /** Crunching collected samples into baseline statistics. */
    object Calibrating : CalibrationState()

    /** Baseline has been computed and saved; calibration is complete. */
    data class Complete(val baselineCount: Int) : CalibrationState()

    /** Calibration failed due to insufficient data or an exception. */
    data class Failed(val reason: String) : CalibrationState()
}

/**
 * Guided calibration wizard that collects 60 seconds of empty-room RSSI data,
 * computes per-AP baseline statistics, and persists them via [BaselineManager].
 *
 * Usage:
 * ```kotlin
 * calibrator.calibrate(rssiFlow).collect { state -> updateUi(state) }
 * ```
 */
@Singleton
class RoomCalibrator @Inject constructor(
    private val baselineManager: BaselineManager
) {

    companion object {
        private const val COLLECTION_WINDOW_MS = 60_000L // 60 s
        private const val MIN_SAMPLES_PER_AP = 5
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts the calibration sequence and returns a [Flow] of [CalibrationState] updates.
     *
     * The caller must ensure the room is empty before collecting the flow.
     * Collection automatically terminates once [CalibrationState.Complete] or
     * [CalibrationState.Failed] is emitted.
     */
    fun calibrate(rssiFlow: Flow<List<RssiData>>): Flow<CalibrationState> = channelFlow {
        send(CalibrationState.Idle)

        val startMs = System.currentTimeMillis()
        val samples = mutableMapOf<String, MutableList<Float>>() // bssid → RSSI list

        // Collect RSSI samples for COLLECTION_WINDOW_MS
        val collectJob = launch {
            rssiFlow
                .onEach { readings ->
                    val elapsed = System.currentTimeMillis() - startMs
                    val progress = (elapsed.toFloat() / COLLECTION_WINDOW_MS).coerceIn(0f, 1f)
                    send(CalibrationState.CollectingEmpty(progress))

                    for (reading in readings) {
                        samples.getOrPut(reading.bssid) { mutableListOf() }
                            .add(reading.rssi.toFloat())
                    }
                }
                .catch { e -> send(CalibrationState.Failed("RSSI stream error: ${e.message}")) }
                .collect()
        }

        // Wait for collection window to expire
        delay(COLLECTION_WINDOW_MS)
        collectJob.cancel()

        // Move to computing state
        send(CalibrationState.Calibrating)

        val validAps = samples.filterValues { it.size >= MIN_SAMPLES_PER_AP }
        if (validAps.isEmpty()) {
            send(CalibrationState.Failed("No AP data collected — ensure Wi-Fi is enabled"))
            return@channelFlow
        }

        // Compute and persist baseline statistics
        try {
            for ((bssid, values) in validAps) {
                val mean = values.average().toFloat()
                val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
                baselineManager.saveBaseline(bssid, mean, variance.coerceAtLeast(0.1f))
            }
            baselineManager.recordCalibrationTime()
            send(CalibrationState.Complete(validAps.size))
        } catch (e: Exception) {
            send(CalibrationState.Failed("Failed to save baseline: ${e.message}"))
        }
    }
}
