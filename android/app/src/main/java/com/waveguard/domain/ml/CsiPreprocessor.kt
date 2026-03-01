package com.waveguard.domain.ml

import com.waveguard.data.model.CsiData
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Preprocesses a window of [CsiData] frames into a normalised [FloatArray] suitable for
 * the TFLite WiFlexFormer model (input shape [1, 52, 351]).
 *
 * Pipeline per subcarrier amplitude series:
 * 1. **Hampel filter** – removes outliers (window=7, threshold = 3×MAD).
 * 2. **Butterworth bandpass IIR** – 4th-order approximation (0.1–50 Hz at fs=100 Hz),
 *    implemented as two cascaded biquad (second-order) sections.
 * 3. **Phase sanitisation** – unwrapping + linear detrending applied to the phase channels.
 * 4. **PCA denoising** – keep top-k components via power iteration on the amplitude matrix.
 * 5. **Normalisation** – per-subcarrier zero-mean, unit-variance.
 *
 * The output is a flat [FloatArray] of length 52 × 351 = 18,252 laid out in
 * row-major order [subcarrier, sample].
 */
@Singleton
class CsiPreprocessor @Inject constructor() {

    companion object {
        const val NUM_SUBCARRIERS = 52
        const val WINDOW_SIZE = 351
        private const val HAMPEL_HALF_WIN = 3   // ±3 → window size 7
        private const val HAMPEL_THRESHOLD = 3f // 3 × MAD
        private const val PCA_COMPONENTS = 10   // retain top-10 principal components

        // -----------------------------------------------------------------------
        // 4th-order Butterworth bandpass IIR coefficients (fs=100 Hz, 0.1–50 Hz)
        // Designed as two cascaded biquad sections (direct-form II transposed).
        // Section 1: high-pass pole pair; Section 2: low-pass pole pair.
        // Coefficients computed via bilinear transform.
        // -----------------------------------------------------------------------

        // Section 1 — high-pass @ 0.1 Hz
        private val B1 = floatArrayOf(0.99690f, -1.99380f, 0.99690f)
        private val A1 = floatArrayOf(1.00000f, -1.99379f, 0.99381f)

        // Section 2 — low-pass @ 45 Hz (near-Nyquist at fs=100)
        private val B2 = floatArrayOf(0.83780f, 1.67560f, 0.83780f)
        private val A2 = floatArrayOf(1.00000f, 1.60530f, 0.68580f)
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Processes [window] (must be exactly [WINDOW_SIZE] frames) and returns a flat
     * [FloatArray] of size [NUM_SUBCARRIERS] × [WINDOW_SIZE] ready for TFLite inference.
     *
     * If [window] is shorter than [WINDOW_SIZE] it is zero-padded on the right.
     */
    fun preprocess(window: List<CsiData>): FloatArray {
        val n = WINDOW_SIZE

        // Build subcarrier × time matrix of amplitudes
        val ampMatrix = Array(NUM_SUBCARRIERS) { sub ->
            FloatArray(n) { t -> window.getOrNull(t)?.amplitudes?.getOrNull(sub) ?: 0f }
        }

        // Build phase matrix
        val phaseMatrix = Array(NUM_SUBCARRIERS) { sub ->
            FloatArray(n) { t -> window.getOrNull(t)?.phases?.getOrNull(sub) ?: 0f }
        }

        // Step 1: Hampel filter on amplitudes
        for (sub in 0 until NUM_SUBCARRIERS) {
            ampMatrix[sub] = hampelFilter(ampMatrix[sub])
        }

        // Step 2: Butterworth bandpass on amplitudes
        for (sub in 0 until NUM_SUBCARRIERS) {
            ampMatrix[sub] = biquadCascade(ampMatrix[sub])
        }

        // Step 3: Phase sanitisation
        for (sub in 0 until NUM_SUBCARRIERS) {
            phaseMatrix[sub] = sanitisePhase(phaseMatrix[sub])
        }

        // Step 4: PCA denoising on amplitude matrix (modifies in place)
        pcaDenoise(ampMatrix, PCA_COMPONENTS)

        // Step 5: Per-subcarrier normalisation (amplitudes)
        for (sub in 0 until NUM_SUBCARRIERS) {
            ampMatrix[sub] = normalise(ampMatrix[sub])
        }

        // Flatten to row-major FloatArray [sub0_t0, sub0_t1, ..., sub51_t350]
        return FloatArray(NUM_SUBCARRIERS * n) { idx ->
            val sub = idx / n
            val t = idx % n
            ampMatrix[sub][t]
        }
    }

    // -----------------------------------------------------------------------
    // Step 1: Hampel filter
    // -----------------------------------------------------------------------

    private fun hampelFilter(signal: FloatArray): FloatArray {
        val result = signal.copyOf()
        for (i in signal.indices) {
            val lo = (i - HAMPEL_HALF_WIN).coerceAtLeast(0)
            val hi = (i + HAMPEL_HALF_WIN).coerceAtMost(signal.lastIndex)
            val window = signal.slice(lo..hi)
            val median = median(window)
            val mad = median(window.map { abs(it - median) }) * 1.4826f
            if (abs(signal[i] - median) > HAMPEL_THRESHOLD * mad) {
                result[i] = median
            }
        }
        return result
    }

    // -----------------------------------------------------------------------
    // Step 2: 4th-order Butterworth bandpass (two biquad sections)
    // -----------------------------------------------------------------------

    private fun biquadCascade(signal: FloatArray): FloatArray =
        biquadFilter(biquadFilter(signal, B1, A1), B2, A2)

    /** Direct-form II transposed biquad. */
    private fun biquadFilter(x: FloatArray, b: FloatArray, a: FloatArray): FloatArray {
        val y = FloatArray(x.size)
        var w1 = 0f
        var w2 = 0f
        for (i in x.indices) {
            val w0 = x[i] - a[1] * w1 - a[2] * w2
            y[i] = b[0] * w0 + b[1] * w1 + b[2] * w2
            w2 = w1
            w1 = w0
        }
        return y
    }

    // -----------------------------------------------------------------------
    // Step 3: Phase sanitisation
    // -----------------------------------------------------------------------

    private fun sanitisePhase(phase: FloatArray): FloatArray {
        val unwrapped = unwrapPhase(phase)
        return linearDetrend(unwrapped)
    }

    private fun unwrapPhase(phase: FloatArray): FloatArray {
        val twoPi = (2 * Math.PI).toFloat()
        val result = phase.copyOf()
        for (i in 1 until result.size) {
            val raw = result[i] - result[i - 1]
            // Wrap diff into (-π, π] using a single modulo operation
            val diff = ((raw + Math.PI.toFloat()) % twoPi + twoPi) % twoPi - Math.PI.toFloat()
            result[i] = result[i - 1] + diff
        }
        return result
    }

    private fun linearDetrend(signal: FloatArray): FloatArray {
        val n = signal.size
        if (n < 2) return signal
        val xMean = (n - 1) / 2f
        val yMean = signal.average().toFloat()
        var num = 0f
        var den = 0f
        for (i in signal.indices) {
            val dx = i - xMean
            num += dx * (signal[i] - yMean)
            den += dx * dx
        }
        val slope = if (den != 0f) num / den else 0f
        val intercept = yMean - slope * xMean
        return FloatArray(n) { i -> signal[i] - (slope * i + intercept) }
    }

    // -----------------------------------------------------------------------
    // Step 4: PCA denoising via power iteration (approximate top-k SVD)
    // -----------------------------------------------------------------------

    private fun pcaDenoise(matrix: Array<FloatArray>, k: Int) {
        val rows = matrix.size
        val cols = matrix[0].size

        // Centre each row (subcarrier) for PCA
        val means = FloatArray(rows) { r -> matrix[r].average().toFloat() }
        for (r in 0 until rows) for (c in 0 until cols) matrix[r][c] -= means[r]

        // Power iteration to get top-k components (approximate)
        val actualK = k.coerceAtMost(rows).coerceAtMost(cols)
        val components = Array(actualK) { FloatArray(cols) }
        val scores = Array(actualK) { FloatArray(rows) }

        // Work on a copy so deflation doesn't corrupt matrix before reconstruction
        val working = Array(rows) { r -> matrix[r].copyOf() }

        for (comp in 0 until actualK) {
            // Random initialisation
            var v = FloatArray(cols) { (it + 1).toFloat() / cols }
            repeat(30) { // power iteration steps
                // u = A * v  (rows × cols) × (cols,) → (rows,)
                val u = FloatArray(rows) { r -> dot(working[r], v) }
                normaliseInPlace(u)
                // v = A^T * u  (cols × rows) × (rows,) → (cols,)
                val vNew = FloatArray(cols) { c ->
                    var s = 0f; for (r in 0 until rows) s += working[r][c] * u[r]; s
                }
                normaliseInPlace(vNew)
                v = vNew
            }
            val u = FloatArray(rows) { r -> dot(working[r], v) }
            val sigma = vectorNorm(u).coerceAtLeast(1e-8f)

            components[comp] = v.copyOf()
            scores[comp] = FloatArray(rows) { r -> u[r] }

            // Deflate: subtract outer product sigma * u_normalised * v^T
            val uNorm = FloatArray(rows) { r -> u[r] / sigma }
            for (r in 0 until rows) for (c in 0 until cols) {
                working[r][c] -= sigma * uNorm[r] * v[c]
            }
        }

        // Reconstruct: matrix ≈ Σ_k (scores[k] * components[k]^T)
        for (r in 0 until rows) {
            matrix[r].fill(0f)
            for (comp in 0 until actualK) {
                for (c in 0 until cols) {
                    matrix[r][c] += scores[comp][r] * components[comp][c]
                }
            }
            // Re-add mean
            for (c in 0 until cols) matrix[r][c] += means[r]
        }
    }

    // -----------------------------------------------------------------------
    // Step 5: Per-subcarrier normalisation
    // -----------------------------------------------------------------------

    private fun normalise(signal: FloatArray): FloatArray {
        val mean = signal.average().toFloat()
        val variance = signal.map { (it - mean) * (it - mean) }.average().toFloat()
        val std = sqrt(variance).coerceAtLeast(1e-8f)
        return FloatArray(signal.size) { i -> (signal[i] - mean) / std }
    }

    // -----------------------------------------------------------------------
    // Utility helpers
    // -----------------------------------------------------------------------

    private fun median(values: List<Float>): Float {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f; for (i in a.indices) s += a[i] * b[i]; return s
    }

    private fun vectorNorm(v: FloatArray): Float = sqrt(v.fold(0f) { acc, x -> acc + x * x })

    private fun normaliseInPlace(v: FloatArray) {
        val norm = vectorNorm(v).coerceAtLeast(1e-8f)
        for (i in v.indices) v[i] /= norm
    }
}
