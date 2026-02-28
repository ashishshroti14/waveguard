package com.waveguard.domain.detection

import com.waveguard.data.model.PresenceState
import com.waveguard.data.model.RssiData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects human presence and falls using RSSI statistics.
 *
 * Calibration phase (60 s): records empty-room baseline mean and variance per AP.
 * Detection phase: uses rolling 5-second windows (~10 samples at 2 Hz) to compute
 * variance ratio and mean shift against the baseline.
 *
 * Fall heuristic: a sharp RSSI drop (>15 dB in <2 s) followed by a flatline
 * (variance < 0.5 for 5 s) is classified as FALL_DETECTED.
 */
@Singleton
class StatisticalDetector @Inject constructor() {

    // -----------------------------------------------------------------------
    // Calibration state
    // -----------------------------------------------------------------------

    private val _isCalibrated = MutableStateFlow(false)
    val isCalibrated: StateFlow<Boolean> = _isCalibrated.asStateFlow()

    private val _calibrationProgress = MutableStateFlow(0f)
    val calibrationProgress: StateFlow<Float> = _calibrationProgress.asStateFlow()

    /** Number of RSSI samples collected so far during calibration. */
    private val _calibrationSampleCount = MutableStateFlow(0)
    val calibrationSampleCount: StateFlow<Int> = _calibrationSampleCount.asStateFlow()

    /** True when calibration completed but no Wi-Fi data was received. */
    private val _calibrationFailed = MutableStateFlow(false)
    val calibrationFailed: StateFlow<Boolean> = _calibrationFailed.asStateFlow()

    // Baseline statistics: bssid → (mean, variance)
    private val baselineMean = mutableMapOf<String, Float>()
    private val baselineVariance = mutableMapOf<String, Float>()

    // Calibration accumulation: bssid → list of RSSI samples during the 60-s window
    private val calibrationSamples = mutableMapOf<String, MutableList<Float>>()
    private var calibrationStartMs = 0L
    @Volatile private var isCalibrating = false

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        private const val CALIBRATION_WINDOW_MS = 60_000L       // 60 s
        private const val ROLLING_WINDOW_SIZE = 10              // ~5 s at 2 Hz
        private const val VARIANCE_RATIO_THRESHOLD = 2.0f
        private const val MEAN_SHIFT_THRESHOLD = 3.0f           // dB
        private const val FALL_DROP_THRESHOLD = 15f             // dB
        private const val FALL_DROP_WINDOW_MS = 2_000L          // 2 s
        private const val FALL_FLATLINE_VARIANCE = 0.5f
        private const val FALL_FLATLINE_WINDOW = 10             // ~5 s at 2 Hz
    }

    // -----------------------------------------------------------------------
    // Rolling state per BSSID
    // -----------------------------------------------------------------------

    private data class RollingState(
        val window: ArrayDeque<Float> = ArrayDeque(ROLLING_WINDOW_SIZE + 1),
        // Timestamps paired with RSSI for fall-drop detection
        val recentWithTime: ArrayDeque<Pair<Long, Float>> = ArrayDeque(),
        // Flatline window after a detected drop
        val flatlineWindow: ArrayDeque<Float> = ArrayDeque(FALL_FLATLINE_WINDOW + 1),
        var dropDetectedAtMs: Long = 0L
    )

    private val rollingState = mutableMapOf<String, RollingState>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var calibrationJob: Job? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts collecting calibration samples for 60 s.  Progress is emitted via
     * [calibrationProgress].  After 60 s the baseline is locked and [isCalibrated]
     * transitions to `true`.
     */
    fun startCalibration() {
        calibrationJob?.cancel()
        calibrationSamples.clear()
        calibrationStartMs = System.currentTimeMillis()
        isCalibrating = true
        _calibrationProgress.value = 0f
        _isCalibrated.value = false
        _calibrationSampleCount.value = 0
        _calibrationFailed.value = false

        // Drive progress every second so the UI always advances even when Android
        // throttles Wi-Fi scans and no RSSI data arrives.
        calibrationJob = scope.launch {
            val start = calibrationStartMs
            while (isActive) {
                delay(1_000L)
                val elapsed = System.currentTimeMillis() - start
                _calibrationProgress.value = (elapsed.toFloat() / CALIBRATION_WINDOW_MS).coerceIn(0f, 1f)
                if (elapsed >= CALIBRATION_WINDOW_MS) {
                    finishCalibration()
                    break
                }
            }
        }
    }

    /**
     * Processes a stream of RSSI scan lists and emits [PresenceState] decisions.
     * Also drives calibration progress when [startCalibration] has been called.
     */
    fun analyze(rssiFlow: Flow<List<RssiData>>): Flow<PresenceState> =
        rssiFlow
            .runningFold(emptyList<RssiData>() to PresenceState.UNKNOWN) { (_, _), samples ->
                val nowMs = System.currentTimeMillis()
                if (isCalibrating) {
                    feedCalibration(samples)
                }
                val state = if (_isCalibrated.value) detect(samples, nowMs) else PresenceState.UNKNOWN
                samples to state
            }
            .map { (_, state) -> state }

    // -----------------------------------------------------------------------
    // Calibration helpers
    // -----------------------------------------------------------------------

    private fun feedCalibration(samples: List<RssiData>) {
        for (entry in samples) {
            calibrationSamples.getOrPut(entry.bssid) { mutableListOf() }.add(entry.rssi.toFloat())
        }
        _calibrationSampleCount.value = calibrationSamples.values.sumOf { it.size }
    }

    private fun finishCalibration() {
        isCalibrating = false

        // If no RSSI samples were collected, calibration cannot succeed — the device
        // likely has no Wi-Fi connection.  Signal this to the UI instead of pretending.
        if (calibrationSamples.isEmpty()) {
            _calibrationProgress.value = 1f
            _calibrationFailed.value = true
            return
        }

        for ((bssid, values) in calibrationSamples) {
            if (values.size < 2) continue
            val mean = values.average().toFloat()
            val variance = values.map { (it - mean) * (it - mean) }.average().toFloat()
            baselineMean[bssid] = mean
            baselineVariance[bssid] = variance.coerceAtLeast(0.1f) // avoid zero-division
        }
        _calibrationProgress.value = 1f
        _isCalibrated.value = true
    }

    // -----------------------------------------------------------------------
    // Detection helpers
    // -----------------------------------------------------------------------

    private fun detect(samples: List<RssiData>, nowMs: Long): PresenceState {
        var presenceVotes = 0
        var fallVotes = 0

        for (entry in samples) {
            val bssid = entry.bssid
            val rssi = entry.rssi.toFloat()
            val bMean = baselineMean[bssid] ?: continue
            val bVar = baselineVariance[bssid] ?: continue
            val state = rollingState.getOrPut(bssid) { RollingState() }

            // Update rolling window
            state.window.addLast(rssi)
            if (state.window.size > ROLLING_WINDOW_SIZE) state.window.removeFirst()

            // Keep recent timed samples (last 2 s)
            state.recentWithTime.addLast(nowMs to rssi)
            state.recentWithTime.removeAll { nowMs - it.first > FALL_DROP_WINDOW_MS }

            if (state.window.size < 3) continue

            val curMean = state.window.average().toFloat()
            val curVar = state.window.map { (it - curMean) * (it - curMean) }.average().toFloat()

            // --- Variance ratio ---
            if (curVar > bVar * VARIANCE_RATIO_THRESHOLD) presenceVotes++

            // --- Mean shift ---
            if (abs(curMean - bMean) > MEAN_SHIFT_THRESHOLD) presenceVotes++

            // --- Fall heuristic ---
            if (detectFall(state, rssi, nowMs)) fallVotes++
        }

        return when {
            fallVotes > 0 -> PresenceState.FALL_DETECTED
            presenceVotes > 0 -> PresenceState.PRESENCE_DETECTED
            _isCalibrated.value -> PresenceState.EMPTY
            else -> PresenceState.UNKNOWN
        }
    }

    private fun detectFall(state: RollingState, currentRssi: Float, nowMs: Long): Boolean {
        // Step 1: check for a sharp RSSI drop within the last 2 s
        if (state.recentWithTime.size >= 2) {
            val oldest = state.recentWithTime.first().second
            val drop = oldest - currentRssi // positive = drop
            if (drop > FALL_DROP_THRESHOLD && state.dropDetectedAtMs == 0L) {
                state.dropDetectedAtMs = nowMs
                state.flatlineWindow.clear()
            }
        }

        // Step 2: after a drop, accumulate flatline window
        if (state.dropDetectedAtMs > 0L) {
            state.flatlineWindow.addLast(currentRssi)
            if (state.flatlineWindow.size > FALL_FLATLINE_WINDOW) state.flatlineWindow.removeFirst()

            if (state.flatlineWindow.size == FALL_FLATLINE_WINDOW) {
                val flatMean = state.flatlineWindow.average().toFloat()
                val flatVar = state.flatlineWindow.map { (it - flatMean) * (it - flatMean) }.average().toFloat()
                if (flatVar < FALL_FLATLINE_VARIANCE) {
                    // Fall confirmed — reset so we don't re-trigger indefinitely
                    state.dropDetectedAtMs = 0L
                    state.flatlineWindow.clear()
                    return true
                }
            }

            // Timeout: if drop was long ago without confirming flatline, reset
            if (nowMs - state.dropDetectedAtMs > 15_000L) {
                state.dropDetectedAtMs = 0L
                state.flatlineWindow.clear()
            }
        }

        return false
    }

    // -----------------------------------------------------------------------
    // Utility: variance of a list
    // -----------------------------------------------------------------------

    @Suppress("unused")
    private fun List<Float>.variance(): Float {
        if (size < 2) return 0f
        val mean = average().toFloat()
        return map { (it - mean) * (it - mean) }.average().toFloat()
    }
}
