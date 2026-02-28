package com.waveguard.domain.detection

import com.waveguard.data.model.PresenceState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Self-learning anomaly detector based on hourly temporal patterns.
 *
 * Records a per-hour-of-day (0–23) activity baseline using an exponential moving average (EMA).
 * On each incoming [PresenceState] observation the activity level is encoded as a float and
 * compared against the current hour's baseline via z-score.  A z-score exceeding
 * [Z_SCORE_THRESHOLD] (3.0) is flagged as an anomaly.
 *
 * The description emitted with each anomaly identifies the affected hour so callers can surface
 * "Unusual activity for this time of day" messages.
 */
@Singleton
class AnomalyDetector @Inject constructor() {

    companion object {
        private const val HOURS_IN_DAY = 24
        private const val Z_SCORE_THRESHOLD = 3.0f
        /** EMA smoothing factor α: smaller = slower adaptation. */
        private const val EMA_ALPHA = 0.05f
        /** Minimum samples per slot before anomaly detection is trusted. */
        private const val MIN_SAMPLES_FOR_DETECTION = 10
    }

    // -----------------------------------------------------------------------
    // Per-hour baseline state
    // -----------------------------------------------------------------------

    /** EMA of activity level per hour slot. */
    private val slotMean = FloatArray(HOURS_IN_DAY) { 0f }

    /** EMA of squared deviation (surrogate variance) per hour slot. */
    private val slotVariance = FloatArray(HOURS_IN_DAY) { 1f } // initialise to 1 to avoid /0

    /** Number of observations per slot (used to gate early detections). */
    private val slotCount = IntArray(HOURS_IN_DAY) { 0 }

    // -----------------------------------------------------------------------
    // Output state
    // -----------------------------------------------------------------------

    private val _anomalyDescription = MutableStateFlow<String?>(null)
    /** Non-null when the most recent observation was anomalous; null otherwise. */
    val anomalyDescription: StateFlow<String?> = _anomalyDescription.asStateFlow()

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Monitors [presenceFlow] and emits `true` whenever an anomaly is detected.
     * [anomalyDescription] is updated with a human-readable description at the same time.
     */
    fun monitor(presenceFlow: Flow<PresenceState>): Flow<Boolean> = channelFlow {
        presenceFlow
            .onEach { state ->
                val hour = currentHour()
                val level = encodeActivityLevel(state)
                val isAnomaly = evaluate(hour, level)
                // Always update baseline with the new observation
                updateBaseline(hour, level)
                _anomalyDescription.value = if (isAnomaly) {
                    buildDescription(hour, state)
                } else {
                    null
                }
                send(isAnomaly)
            }
            .collect()
    }

    // -----------------------------------------------------------------------
    // Baseline update (EMA)
    // -----------------------------------------------------------------------

    private fun updateBaseline(hour: Int, level: Float) {
        val prevMean = slotMean[hour]
        val newMean = EMA_ALPHA * level + (1f - EMA_ALPHA) * prevMean
        slotMean[hour] = newMean

        val deviation = level - prevMean
        slotVariance[hour] = EMA_ALPHA * deviation * deviation + (1f - EMA_ALPHA) * slotVariance[hour]
        slotCount[hour] = (slotCount[hour] + 1).coerceAtMost(Int.MAX_VALUE)
    }

    // -----------------------------------------------------------------------
    // Anomaly evaluation
    // -----------------------------------------------------------------------

    private fun evaluate(hour: Int, level: Float): Boolean {
        if (slotCount[hour] < MIN_SAMPLES_FOR_DETECTION) return false
        val mean = slotMean[hour]
        val std = sqrt(slotVariance[hour]).coerceAtLeast(0.01f)
        val zScore = abs((level - mean) / std)
        return zScore > Z_SCORE_THRESHOLD
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Encodes [PresenceState] as a numeric activity level (0 = empty, 3 = fall). */
    private fun encodeActivityLevel(state: PresenceState): Float = when (state) {
        PresenceState.EMPTY -> 0f
        PresenceState.UNKNOWN -> 0f
        PresenceState.PRESENCE_DETECTED -> 1f
        PresenceState.MOVEMENT_DETECTED -> 2f
        PresenceState.FALL_DETECTED -> 3f
    }

    private fun buildDescription(hour: Int, state: PresenceState): String {
        val period = when {
            hour < 6 -> "night"
            hour < 12 -> "morning"
            hour < 18 -> "afternoon"
            else -> "evening"
        }
        return "Unusual activity (${state.name}) detected during ${period} hours ($hour:00)"
    }

    private fun currentHour(): Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
}
