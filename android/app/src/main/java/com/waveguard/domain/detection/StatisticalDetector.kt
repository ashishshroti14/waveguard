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
import kotlin.math.ln

/**
 * WiFi RSSI-based presence and fall detection using multi-feature statistical analysis.
 *
 * **Calibration** (60 s): records empty-room baseline per AP — mean, variance, jitter
 * (mean absolute consecutive difference), and signal entropy.
 *
 * **Detection** uses a rolling window and five complementary features:
 *   1. **Variance ratio** — elevated variance vs. baseline signals body-induced multipath
 *   2. **Mean shift** — sustained RSSI offset signals a large reflector (person) nearby
 *   3. **Jitter (rate-of-change)** — consecutive-sample differences detect motion even
 *      when variance and mean stay close to baseline (e.g. slow walking)
 *   4. **Signal entropy** — Shannon entropy of quantised RSSI bins; movement broadens
 *      the distribution → higher entropy
 *   5. **Fall heuristic** — sharp drop (>12 dB / 2 s) + flatline (var <0.5 / 5 s)
 *
 * A **weighted vote with hysteresis** prevents state flickering — the detector needs
 * more evidence to transition *into* PRESENCE than to *stay* there.
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

    private val _calibrationSampleCount = MutableStateFlow(0)
    val calibrationSampleCount: StateFlow<Int> = _calibrationSampleCount.asStateFlow()

    private val _calibrationFailed = MutableStateFlow(false)
    val calibrationFailed: StateFlow<Boolean> = _calibrationFailed.asStateFlow()

    // Baseline statistics per BSSID
    private val baselineMean = mutableMapOf<String, Float>()
    private val baselineVariance = mutableMapOf<String, Float>()
    private val baselineJitter = mutableMapOf<String, Float>()   // mean |Δ| between consecutive samples
    private val baselineEntropy = mutableMapOf<String, Float>()  // Shannon entropy of 1-dB bins

    // Calibration accumulation (guarded by calibrationLock)
    private val calibrationLock = Any()
    private val calibrationSamples = mutableMapOf<String, MutableList<Float>>()
    private var calibrationStartMs = 0L
    @Volatile private var isCalibrating = false

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        private const val CALIBRATION_WINDOW_MS = 60_000L       // 60 s

        // Rolling window: 20 samples ≈ 20 s at ~1 Hz — larger window = smoother detection
        private const val ROLLING_WINDOW_SIZE = 20

        // --- Feature thresholds (lowered for single-AP / phone-only scenarios) ---
        private const val VARIANCE_RATIO_THRESHOLD = 1.5f       // was 2.0
        private const val MEAN_SHIFT_THRESHOLD = 1.5f           // was 3.0 dB
        private const val JITTER_RATIO_THRESHOLD = 1.8f         // jitter / baselineJitter
        private const val ENTROPY_EXCESS_THRESHOLD = 0.3f       // nats above baseline

        // Weighted vote: each feature casts a score, sum is compared to thresholds
        private const val WEIGHT_VARIANCE = 1.0f
        private const val WEIGHT_MEAN_SHIFT = 1.0f
        private const val WEIGHT_JITTER = 1.2f                  // jitter is very responsive
        private const val WEIGHT_ENTROPY = 0.8f

        // Hysteresis: higher bar to *enter* PRESENCE than to *stay* in it
        private const val VOTE_THRESHOLD_ENTER = 1.8f
        private const val VOTE_THRESHOLD_STAY = 1.0f

        // Fall detection
        private const val FALL_DROP_THRESHOLD = 12f             // was 15 dB — more sensitive
        private const val FALL_DROP_WINDOW_MS = 2_000L
        private const val FALL_FLATLINE_VARIANCE = 0.5f
        private const val FALL_FLATLINE_WINDOW = 10
    }

    // -----------------------------------------------------------------------
    // Rolling state per BSSID
    // -----------------------------------------------------------------------

    private data class RollingState(
        val window: ArrayDeque<Float> = ArrayDeque(ROLLING_WINDOW_SIZE + 1),
        val recentWithTime: ArrayDeque<Pair<Long, Float>> = ArrayDeque(),
        val flatlineWindow: ArrayDeque<Float> = ArrayDeque(FALL_FLATLINE_WINDOW + 1),
        var dropDetectedAtMs: Long = 0L,
        var prevRssi: Float = Float.NaN   // for jitter computation
    )

    private val rollingState = mutableMapOf<String, RollingState>()

    // Global detection state for hysteresis
    @Volatile private var lastDetectedState = PresenceState.EMPTY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var calibrationJob: Job? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    fun startCalibration() {
        calibrationJob?.cancel()
        synchronized(calibrationLock) { calibrationSamples.clear() }
        calibrationStartMs = System.currentTimeMillis()
        isCalibrating = true
        _calibrationProgress.value = 0f
        _isCalibrated.value = false
        _calibrationSampleCount.value = 0
        _calibrationFailed.value = false
        lastDetectedState = PresenceState.EMPTY

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
        synchronized(calibrationLock) {
            for (entry in samples) {
                calibrationSamples.getOrPut(entry.bssid) { mutableListOf() }.add(entry.rssi.toFloat())
            }
            _calibrationSampleCount.value = calibrationSamples.values.sumOf { it.size }
        }
    }

    private fun finishCalibration() {
        isCalibrating = false

        synchronized(calibrationLock) {
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
                baselineVariance[bssid] = variance.coerceAtLeast(0.1f)

                // Baseline jitter: mean absolute consecutive difference
                baselineJitter[bssid] = if (values.size >= 2) {
                    values.zipWithNext { a, b -> abs(b - a) }.average().toFloat().coerceAtLeast(0.05f)
                } else 0.1f

                // Baseline entropy
                baselineEntropy[bssid] = shannonEntropy(values).coerceAtLeast(0.01f)
            }
        }
        _calibrationProgress.value = 1f
        _isCalibrated.value = true
    }

    // -----------------------------------------------------------------------
    // Detection
    // -----------------------------------------------------------------------

    private fun detect(samples: List<RssiData>, nowMs: Long): PresenceState {
        var totalScore = 0f
        var apCount = 0
        var fallVotes = 0

        for (entry in samples) {
            val bssid = entry.bssid
            val rssi = entry.rssi.toFloat()
            val bMean = baselineMean[bssid] ?: continue
            val bVar = baselineVariance[bssid] ?: continue
            val bJitter = baselineJitter[bssid] ?: 0.1f
            val bEntropy = baselineEntropy[bssid] ?: 0.01f
            val state = rollingState.getOrPut(bssid) { RollingState() }

            // Update rolling window
            state.window.addLast(rssi)
            if (state.window.size > ROLLING_WINDOW_SIZE) state.window.removeFirst()

            // Track previous RSSI for future per-sample analysis
            state.prevRssi = rssi

            // Keep recent timed samples (last 2 s)
            state.recentWithTime.addLast(nowMs to rssi)
            state.recentWithTime.removeAll { nowMs - it.first > FALL_DROP_WINDOW_MS }

            if (state.window.size < 5) continue
            apCount++

            val curMean = state.window.average().toFloat()
            val curVar = state.window.map { (it - curMean) * (it - curMean) }.average().toFloat()
            val curJitter = if (state.window.size >= 2)
                state.window.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
            else 0f
            val curEntropy = shannonEntropy(state.window.toList())

            // --- Feature 1: Variance ratio ---
            var apScore = 0f
            if (curVar > bVar * VARIANCE_RATIO_THRESHOLD) {
                apScore += WEIGHT_VARIANCE
            }

            // --- Feature 2: Mean shift ---
            if (abs(curMean - bMean) > MEAN_SHIFT_THRESHOLD) {
                apScore += WEIGHT_MEAN_SHIFT
            }

            // --- Feature 3: Jitter (rate-of-change) ---
            if (curJitter > bJitter * JITTER_RATIO_THRESHOLD) {
                apScore += WEIGHT_JITTER
            }

            // --- Feature 4: Signal entropy ---
            if (curEntropy - bEntropy > ENTROPY_EXCESS_THRESHOLD) {
                apScore += WEIGHT_ENTROPY
            }

            totalScore += apScore

            // --- Feature 5: Fall heuristic ---
            if (detectFall(state, rssi, nowMs)) fallVotes++
        }

        // Normalise by AP count so multi-AP and single-AP scenarios are comparable
        val normScore = if (apCount > 0) totalScore / apCount else 0f

        // Hysteresis: if we're currently in PRESENCE, use a lower threshold to stay
        val threshold = if (lastDetectedState == PresenceState.PRESENCE_DETECTED ||
            lastDetectedState == PresenceState.MOVEMENT_DETECTED) {
            VOTE_THRESHOLD_STAY
        } else {
            VOTE_THRESHOLD_ENTER
        }

        val result = when {
            fallVotes > 0 -> PresenceState.FALL_DETECTED
            normScore >= threshold * 1.5f -> PresenceState.MOVEMENT_DETECTED
            normScore >= threshold -> PresenceState.PRESENCE_DETECTED
            _isCalibrated.value -> PresenceState.EMPTY
            else -> PresenceState.UNKNOWN
        }
        lastDetectedState = result
        return result
    }

    private fun detectFall(state: RollingState, currentRssi: Float, nowMs: Long): Boolean {
        if (state.recentWithTime.size >= 2) {
            val oldest = state.recentWithTime.first().second
            val drop = oldest - currentRssi
            if (drop > FALL_DROP_THRESHOLD && state.dropDetectedAtMs == 0L) {
                state.dropDetectedAtMs = nowMs
                state.flatlineWindow.clear()
            }
        }

        if (state.dropDetectedAtMs > 0L) {
            state.flatlineWindow.addLast(currentRssi)
            if (state.flatlineWindow.size > FALL_FLATLINE_WINDOW) state.flatlineWindow.removeFirst()

            if (state.flatlineWindow.size == FALL_FLATLINE_WINDOW) {
                val flatMean = state.flatlineWindow.average().toFloat()
                val flatVar = state.flatlineWindow.map { (it - flatMean) * (it - flatMean) }.average().toFloat()
                if (flatVar < FALL_FLATLINE_VARIANCE) {
                    state.dropDetectedAtMs = 0L
                    state.flatlineWindow.clear()
                    return true
                }
            }

            if (nowMs - state.dropDetectedAtMs > 15_000L) {
                state.dropDetectedAtMs = 0L
                state.flatlineWindow.clear()
            }
        }

        return false
    }

    // -----------------------------------------------------------------------
    // Utility: Shannon entropy of RSSI values (quantised to 1-dB bins)
    // -----------------------------------------------------------------------

    private fun shannonEntropy(values: List<Float>): Float {
        if (values.size < 2) return 0f
        val bins = mutableMapOf<Int, Int>()
        for (v in values) {
            val bin = v.toInt()
            bins[bin] = (bins[bin] ?: 0) + 1
        }
        val n = values.size.toFloat()
        var entropy = 0f
        for ((_, count) in bins) {
            val p = count / n
            if (p > 0f) entropy -= p * ln(p)
        }
        return entropy
    }
}
