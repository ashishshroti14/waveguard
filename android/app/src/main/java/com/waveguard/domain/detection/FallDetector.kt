package com.waveguard.domain.detection

import com.waveguard.data.model.ActivityType
import com.waveguard.data.model.CsiData
import com.waveguard.data.model.PresenceState
import com.waveguard.data.model.RssiData
import com.waveguard.domain.ml.CsiPreprocessor
import com.waveguard.domain.ml.WiFlexFormerInference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Dual-mode fall detector that combines:
 * - **Statistical path** (RSSI): sharp variance spike followed by silence.
 * - **ML path** (CSI, optional): WiFlexFormer class index 3 = FALLING.
 *
 * A cooldown of [COOLDOWN_MS] (30 s) is enforced between consecutive alerts.
 * [isFallDetected] emits `true` when a fall is confirmed, paired with a confidence score.
 */
@Singleton
class FallDetector @Inject constructor(
    private val preprocessor: CsiPreprocessor,
    private val inference: WiFlexFormerInference
) {

    companion object {
        private const val COOLDOWN_MS = 30_000L          // 30 s between alerts
        private const val RSSI_SPIKE_THRESHOLD = 5.0f    // variance spike to flag a drop event
        private const val RSSI_SILENCE_VARIANCE = 0.5f   // flatline threshold
        private const val RSSI_SILENCE_WINDOW = 10       // samples (~5 s at 2 Hz)
        private const val ML_FALL_CLASS_INDEX = 3        // WiFlexFormer output index for fall
        private const val ML_CONFIDENCE_THRESHOLD = 0.55f
        private const val CSI_WINDOW_SIZE = 351
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private val _isFallDetected = MutableStateFlow(false)
    val isFallDetected: StateFlow<Boolean> = _isFallDetected.asStateFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    private var lastAlertMs = 0L

    // Rolling RSSI state for statistical detection
    private val rssiWindow = ArrayDeque<Float>(RSSI_SILENCE_WINDOW + 1)
    private var inSpikePhase = false
    private val postSpikeWindow = ArrayDeque<Float>(RSSI_SILENCE_WINDOW + 1)

    // CSI buffer for ML path
    private val csiBuffer = ArrayDeque<CsiData>(CSI_WINDOW_SIZE + 1)

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Monitors [rssiFlow] for statistical fall signatures and [csiFlow] for ML-based fall
     * classification.  Emits combined detection results via [isFallDetected] and [confidence].
     */
    fun monitor(
        rssiFlow: Flow<List<RssiData>>,
        csiFlow: Flow<CsiData>
    ): Flow<Boolean> = channelFlow {

        // Statistical path (RSSI)
        launch {
            rssiFlow
                .onEach { samples -> processRssi(samples) }
                .collect()
        }

        // ML path (CSI)
        launch {
            csiFlow
                .onEach { frame -> processCsiFrame(frame) }
                .collect()
        }

        // Re-emit isFallDetected as the channel output
        isFallDetected
            .onEach { send(it) }
            .collect()
    }

    // -----------------------------------------------------------------------
    // Statistical path
    // -----------------------------------------------------------------------

    private suspend fun processRssi(samples: List<RssiData>) {
        if (samples.isEmpty()) return
        // Aggregate RSSI across all APs (simple mean)
        val meanRssi = samples.map { it.rssi.toFloat() }.average().toFloat()
        rssiWindow.addLast(meanRssi)
        if (rssiWindow.size > RSSI_SILENCE_WINDOW) rssiWindow.removeFirst()

        if (rssiWindow.size < 3) return
        val curVar = rssiWindow.variance()

        if (!inSpikePhase && curVar > RSSI_SPIKE_THRESHOLD) {
            inSpikePhase = true
            postSpikeWindow.clear()
            return
        }

        if (inSpikePhase) {
            postSpikeWindow.addLast(meanRssi)
            if (postSpikeWindow.size > RSSI_SILENCE_WINDOW) postSpikeWindow.removeFirst()

            if (postSpikeWindow.size == RSSI_SILENCE_WINDOW) {
                val silenceVar = postSpikeWindow.variance()
                if (silenceVar < RSSI_SILENCE_VARIANCE) {
                    inSpikePhase = false
                    postSpikeWindow.clear()
                    triggerFall(confidence = 0.70f)
                } else if (curVar > RSSI_SPIKE_THRESHOLD * 2) {
                    // Continued activity — not a fall
                    inSpikePhase = false
                    postSpikeWindow.clear()
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // ML path
    // -----------------------------------------------------------------------

    private suspend fun processCsiFrame(frame: CsiData) {
        csiBuffer.addLast(frame)
        if (csiBuffer.size < CSI_WINDOW_SIZE) return

        val window = csiBuffer.toList()
        csiBuffer.clear()

        if (!inference.isAvailable()) return

        val input = preprocessor.preprocess(window)
        val probabilities = inference.interpretRaw(input)
        if (probabilities.size > ML_FALL_CLASS_INDEX) {
            val fallConf = probabilities[ML_FALL_CLASS_INDEX]
            if (fallConf >= ML_CONFIDENCE_THRESHOLD) {
                triggerFall(confidence = fallConf)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Alert emission
    // -----------------------------------------------------------------------

    private suspend fun triggerFall(confidence: Float) {
        val now = System.currentTimeMillis()
        if (now - lastAlertMs < COOLDOWN_MS) return
        lastAlertMs = now
        _confidence.value = confidence
        _isFallDetected.value = true
        // Auto-reset after one cycle so observers receive false again
        _isFallDetected.value = false
    }

    // -----------------------------------------------------------------------
    // Utility
    // -----------------------------------------------------------------------

    private fun ArrayDeque<Float>.variance(): Float {
        if (size < 2) return 0f
        val mean = average().toFloat()
        return map { (it - mean) * (it - mean) }.average().toFloat()
    }
}
