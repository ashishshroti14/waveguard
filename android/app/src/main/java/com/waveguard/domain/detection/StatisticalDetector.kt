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
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * WiFi RSSI-based presence and fall detection — v2 with spectral analysis.
 *
 * Inspired by the wifi-densepose ADR-013 "Feature-Level Sensing on Commodity Gear"
 * research pipeline.  The key insight: human movement creates periodic RSSI fluctuations
 * that are invisible in simple mean/variance stats but clearly visible in the frequency
 * domain (FFT of the RSSI time series).
 *
 * **Calibration** (60 s): records empty-room baseline per AP — mean, variance, jitter,
 * Shannon entropy, spectral energy, and motion-band power.
 *
 * **Detection** uses a 60-sample rolling window (≈30 s at 2 Hz) and eight features:
 *   1. **Variance ratio** — elevated variance vs. baseline (body-induced multipath)
 *   2. **Mean shift** — sustained RSSI offset (large reflector nearby)
 *   3. **Jitter / rate-of-change** — consecutive-sample differences detect subtle motion
 *   4. **Shannon entropy** — movement broadens the RSSI distribution
 *   5. **Spectral energy** — FFT total power (excl. DC); any motion increases this
 *   6. **Motion-band power** — FFT power in 0.5–3.0 Hz (walking/gesturing band)
 *   7. **CUSUM change-points** — cumulative-sum algorithm detects abrupt level shifts
 *   8. **Fall heuristic** — sharp drop (>12 dB / 2 s) + flatline (var < 0.5 / 5 s)
 *
 * A **weighted vote with hysteresis** prevents state flickering.
 * Sensitivity is configurable via [setSensitivity] (0.0 = low, 1.0 = maximum).
 *
 * Reference: Youssef et al. (2007) — device-free passive detection using RSSI variance;
 * wifi-densepose ADR-013 — spectral features + CUSUM on commodity WiFi.
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
    private val baselineJitter = mutableMapOf<String, Float>()
    private val baselineEntropy = mutableMapOf<String, Float>()
    private val baselineSpectralEnergy = mutableMapOf<String, Float>()
    private val baselineMotionBandPower = mutableMapOf<String, Float>()

    // Calibration accumulation (guarded by calibrationLock)
    private val calibrationLock = Any()
    private val calibrationSamples = mutableMapOf<String, MutableList<Float>>()
    private var calibrationStartMs = 0L
    @Volatile private var isCalibrating = false

    // Sensitivity: 0.0 (low) → 1.0 (maximum).  Adjusts detection thresholds.
    @Volatile private var sensitivity = 0.5f

    // -----------------------------------------------------------------------
    // Constants
    // -----------------------------------------------------------------------

    companion object {
        private const val CALIBRATION_WINDOW_MS = 60_000L

        // 60-sample window ≈ 30 s at 2 Hz — needed for meaningful FFT resolution
        // Min detectable frequency ≈ 2 Hz / 60 = 0.033 Hz
        private const val ROLLING_WINDOW_SIZE = 60

        private const val SAMPLING_RATE_HZ = 2.0f

        // --- Base thresholds (before sensitivity scaling) ---
        private const val VARIANCE_RATIO_THRESHOLD = 1.3f
        private const val MEAN_SHIFT_THRESHOLD = 1.0f           // dB
        private const val JITTER_RATIO_THRESHOLD = 1.5f
        private const val ENTROPY_EXCESS_THRESHOLD = 0.2f       // nats
        private const val SPECTRAL_ENERGY_RATIO = 2.0f          // vs baseline
        private const val MOTION_BAND_RATIO = 2.0f              // vs baseline
        private const val CUSUM_THRESHOLD_SIGMA = 2.5f           // σ for change-point

        // Feature weights — spectral features are the primary differentiator on
        // commodity hardware per wifi-densepose research
        private const val W_VARIANCE = 0.10f
        private const val W_MEAN_SHIFT = 0.10f
        private const val W_JITTER = 0.12f
        private const val W_ENTROPY = 0.08f
        private const val W_SPECTRAL = 0.25f       // FFT total energy — key feature
        private const val W_MOTION_BAND = 0.20f     // 0.5–3 Hz band — walking/gesturing
        private const val W_CUSUM = 0.15f           // change-point detection

        // Hysteresis
        private const val VOTE_THRESHOLD_ENTER = 0.30f
        private const val VOTE_THRESHOLD_STAY = 0.18f
        private const val MOVEMENT_THRESHOLD_MULTIPLIER = 1.8f

        // Fall detection
        private const val FALL_DROP_THRESHOLD = 12f
        private const val FALL_DROP_WINDOW_MS = 2_000L
        private const val FALL_FLATLINE_VARIANCE = 0.5f
        private const val FALL_FLATLINE_WINDOW = 10

        // CUSUM algorithm parameters
        private const val CUSUM_DRIFT_ALLOWANCE = 0.5f   // slack per sample before accumulating

        // FFT frequency bands (Hz)
        private const val MOTION_BAND_LOW = 0.5f
        private const val MOTION_BAND_HIGH = 3.0f
    }

    // -----------------------------------------------------------------------
    // Rolling state per BSSID
    // -----------------------------------------------------------------------

    private data class RollingState(
        val window: ArrayDeque<Float> = ArrayDeque(ROLLING_WINDOW_SIZE + 1),
        val recentWithTime: ArrayDeque<Pair<Long, Float>> = ArrayDeque(),
        val flatlineWindow: ArrayDeque<Float> = ArrayDeque(FALL_FLATLINE_WINDOW + 1),
        var dropDetectedAtMs: Long = 0L,
        var prevRssi: Float = Float.NaN
    )

    private val rollingState = mutableMapOf<String, RollingState>()

    @Volatile private var lastDetectedState = PresenceState.EMPTY

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var calibrationJob: Job? = null

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Set detection sensitivity.  0.0 = low (few false positives),
     * 1.0 = maximum (catches subtle movement, more false positives).
     */
    fun setSensitivity(level: Float) {
        sensitivity = level.coerceIn(0f, 1f)
    }

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

                baselineJitter[bssid] = if (values.size >= 2) {
                    values.zipWithNext { a, b -> abs(b - a) }.average().toFloat().coerceAtLeast(0.05f)
                } else 0.1f

                baselineEntropy[bssid] = shannonEntropy(values).coerceAtLeast(0.01f)

                // Spectral baselines from calibration data
                val spectral = spectralEnergy(values)
                baselineSpectralEnergy[bssid] = spectral.coerceAtLeast(0.01f)
                baselineMotionBandPower[bssid] = bandPower(values, MOTION_BAND_LOW, MOTION_BAND_HIGH).coerceAtLeast(0.001f)
            }
        }
        _calibrationProgress.value = 1f
        _isCalibrated.value = true
    }

    // -----------------------------------------------------------------------
    // Detection (v2: spectral + statistical + change-point)
    // -----------------------------------------------------------------------

    private fun detect(samples: List<RssiData>, nowMs: Long): PresenceState {
        var totalScore = 0f
        var apCount = 0
        var fallVotes = 0

        // Sensitivity scaling: linearly maps the 0..1 slider to a threshold multiplier.
        //   sensitivity=0.0 (Low)  → threshMul=1.5 → thresholds are 50% harder to exceed
        //   sensitivity=0.5 (Med)  → threshMul=1.0 → thresholds are at their base values
        //   sensitivity=1.0 (Max)  → threshMul=0.5 → thresholds are halved (more sensitive)
        val threshMul = 1.5f - sensitivity

        for (entry in samples) {
            val bssid = entry.bssid
            val rssi = entry.rssi.toFloat()
            val bMean = baselineMean[bssid] ?: continue
            val bVar = baselineVariance[bssid] ?: continue
            val bJitter = baselineJitter[bssid] ?: 0.1f
            val bEntropy = baselineEntropy[bssid] ?: 0.01f
            val bSpectral = baselineSpectralEnergy[bssid] ?: 0.01f
            val bMotion = baselineMotionBandPower[bssid] ?: 0.001f
            val state = rollingState.getOrPut(bssid) { RollingState() }

            // Update rolling window
            state.window.addLast(rssi)
            if (state.window.size > ROLLING_WINDOW_SIZE) state.window.removeFirst()

            state.prevRssi = rssi

            // Keep recent timed samples (last 2 s for fall detection)
            state.recentWithTime.addLast(nowMs to rssi)
            state.recentWithTime.removeAll { nowMs - it.first > FALL_DROP_WINDOW_MS }

            // Need minimum samples for spectral analysis
            if (state.window.size < 10) continue
            apCount++

            val windowList = state.window.toList()
            val curMean = windowList.average().toFloat()
            val curVar = windowList.map { (it - curMean) * (it - curMean) }.average().toFloat()
            val curJitter = if (windowList.size >= 2)
                windowList.zipWithNext { a, b -> abs(b - a) }.average().toFloat()
            else 0f
            val curEntropy = shannonEntropy(windowList)
            val curSpectral = spectralEnergy(windowList)
            val curMotionPower = bandPower(windowList, MOTION_BAND_LOW, MOTION_BAND_HIGH)
            val cusumChanges = cusumChangePoints(windowList, bMean, bVar)

            // --- Feature 1: Variance ratio ---
            var apScore = 0f
            if (curVar > bVar * VARIANCE_RATIO_THRESHOLD * threshMul) {
                apScore += W_VARIANCE
            }

            // --- Feature 2: Mean shift ---
            if (abs(curMean - bMean) > MEAN_SHIFT_THRESHOLD * threshMul) {
                apScore += W_MEAN_SHIFT
            }

            // --- Feature 3: Jitter ---
            if (curJitter > bJitter * JITTER_RATIO_THRESHOLD * threshMul) {
                apScore += W_JITTER
            }

            // --- Feature 4: Entropy ---
            if (curEntropy - bEntropy > ENTROPY_EXCESS_THRESHOLD * threshMul) {
                apScore += W_ENTROPY
            }

            // --- Feature 5: Spectral energy (FFT) ---
            if (curSpectral > bSpectral * SPECTRAL_ENERGY_RATIO * threshMul) {
                apScore += W_SPECTRAL
            }

            // --- Feature 6: Motion-band power (0.5–3.0 Hz) ---
            if (curMotionPower > bMotion * MOTION_BAND_RATIO * threshMul) {
                apScore += W_MOTION_BAND
            }

            // --- Feature 7: CUSUM change-points ---
            // Compare as floats to preserve the sensitivity gradient; integer truncation
            // would collapse distinct sensitivity levels to the same count threshold.
            if (cusumChanges.toFloat() >= CUSUM_THRESHOLD_SIGMA * threshMul) {
                apScore += W_CUSUM
            }

            totalScore += apScore

            // --- Feature 8: Fall heuristic ---
            if (detectFall(state, rssi, nowMs)) fallVotes++
        }

        val normScore = if (apCount > 0) totalScore / apCount else 0f

        // Hysteresis
        val threshold = if (lastDetectedState == PresenceState.PRESENCE_DETECTED ||
            lastDetectedState == PresenceState.MOVEMENT_DETECTED) {
            VOTE_THRESHOLD_STAY
        } else {
            VOTE_THRESHOLD_ENTER
        }

        val result = when {
            fallVotes > 0 -> PresenceState.FALL_DETECTED
            normScore >= threshold * MOVEMENT_THRESHOLD_MULTIPLIER -> PresenceState.MOVEMENT_DETECTED
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
    // Spectral analysis: DFT-based feature extraction
    // -----------------------------------------------------------------------

    /**
     * Computes total spectral energy of the RSSI time series, excluding DC component.
     * Uses a simple DFT (O(n²)) which is efficient enough for windows ≤120 samples at 2 Hz.
     *
     * Ref: wifi-densepose ADR-013 — "Total spectral energy excluding DC component"
     */
    private fun spectralEnergy(values: List<Float>): Float {
        if (values.size < 4) return 0f
        val n = values.size
        val mean = values.average().toFloat()
        // Detrend: remove DC
        val detrended = FloatArray(n) { values[it] - mean }
        var totalEnergy = 0f
        // Compute magnitude² for bins 1..n/2 (skip DC at bin 0)
        for (k in 1..n / 2) {
            var re = 0f
            var im = 0f
            val angularFreq = 2.0 * Math.PI * k / n
            for (i in 0 until n) {
                val angle = angularFreq * i
                re += detrended[i] * cos(angle).toFloat()
                im -= detrended[i] * kotlin.math.sin(angle).toFloat()
            }
            totalEnergy += (re * re + im * im) / (n * n)
        }
        return totalEnergy
    }

    /**
     * Computes spectral power in a specific frequency band [lowHz, highHz].
     * This is the motion signature: human walking (1–2 Hz), gesturing (0.5–3 Hz).
     *
     * Ref: wifi-densepose ADR-013 — "_band_power(rssi, 0.5, 3.0)"
     */
    private fun bandPower(values: List<Float>, lowHz: Float, highHz: Float): Float {
        if (values.size < 4) return 0f
        val n = values.size
        val mean = values.average().toFloat()
        val detrended = FloatArray(n) { values[it] - mean }
        var power = 0f
        for (k in 1..n / 2) {
            val freq = k * SAMPLING_RATE_HZ / n
            if (freq < lowHz || freq > highHz) continue
            var re = 0f
            var im = 0f
            val angularFreq = 2.0 * Math.PI * k / n
            for (i in 0 until n) {
                val angle = angularFreq * i
                re += detrended[i] * cos(angle).toFloat()
                im -= detrended[i] * kotlin.math.sin(angle).toFloat()
            }
            power += (re * re + im * im) / (n * n)
        }
        return power
    }

    // -----------------------------------------------------------------------
    // CUSUM change-point detection
    // -----------------------------------------------------------------------

    /**
     * Counts abrupt level shifts in the window using the CUSUM (Cumulative Sum) algorithm.
     * A change-point fires when the cumulative positive or negative deviation from the
     * baseline mean exceeds [sigmaThreshold] × baseline σ.
     *
     * Ref: wifi-densepose ADR-013 — "_cusum_changes(rssi)"
     */
    private fun cusumChangePoints(
        values: List<Float>,
        baselineMean: Float,
        baselineVar: Float
    ): Int {
        if (values.size < 3) return 0
        val sigma = sqrt(baselineVar.coerceAtLeast(0.1f))
        val threshold = CUSUM_THRESHOLD_SIGMA * sigma
        var cusumPos = 0f
        var cusumNeg = 0f
        var changes = 0
        for (v in values) {
            cusumPos = maxOf(0f, cusumPos + v - baselineMean - CUSUM_DRIFT_ALLOWANCE)
            cusumNeg = maxOf(0f, cusumNeg - v + baselineMean - CUSUM_DRIFT_ALLOWANCE)
            if (cusumPos > threshold || cusumNeg > threshold) {
                changes++
                cusumPos = 0f
                cusumNeg = 0f
            }
        }
        return changes
    }

    // -----------------------------------------------------------------------
    // Shannon entropy of RSSI values (quantised to 1-dB bins)
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
