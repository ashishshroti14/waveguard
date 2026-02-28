package com.waveguard.domain.detection

import com.waveguard.data.model.ActivityType
import com.waveguard.data.model.CsiData
import com.waveguard.data.model.PresenceState
import com.waveguard.data.model.RssiData
import com.waveguard.domain.ml.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core presence detection engine that orchestrates all detection sub-systems.
 *
 * **Phase 1** (statistical only, before CSI ML model is available):
 *   Delegates to [StatisticalDetector] using RSSI data alone.
 *
 * **Phase 2** (full pipeline, when ML model is loaded):
 *   Runs [CsiPresenceDetector], [FallDetector], and [AnomalyDetector] in parallel.
 *   Combines their results using configurable weights.
 *
 * Outputs:
 * - [presenceState]: combined [PresenceState] decision
 * - [activityType]: fine-grained activity from ML path (Phase 2 only)
 * - [confidence]: 0.0–1.0 weighted confidence score
 */
@Singleton
class PresenceDetector @Inject constructor(
    private val statisticalDetector: StatisticalDetector,
    private val csiPresenceDetector: CsiPresenceDetector,
    private val fallDetector: FallDetector,
    private val anomalyDetector: AnomalyDetector,
    private val modelManager: ModelManager
) {

    companion object {
        // Combination weights for Phase 2
        private const val WEIGHT_ML = 0.60f
        private const val WEIGHT_STATISTICAL = 0.25f
        private const val WEIGHT_ANOMALY = 0.15f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var isStarted = false

    // -----------------------------------------------------------------------
    // Output flows
    // -----------------------------------------------------------------------

    private val _presenceState = MutableSharedFlow<PresenceState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val presenceState: SharedFlow<PresenceState> = _presenceState.asSharedFlow()

    private val _activityType = MutableSharedFlow<ActivityType>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val activityType: SharedFlow<ActivityType> = _activityType.asSharedFlow()

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence.asStateFlow()

    // Latest values from each detector (updated by coroutines below)
    @Volatile private var latestStatistical = PresenceState.UNKNOWN
    @Volatile private var latestMl = PresenceState.UNKNOWN
    @Volatile private var latestActivity = ActivityType.UNKNOWN
    @Volatile private var latestFall = false
    @Volatile private var latestFallConf = 0f
    @Volatile private var latestAnomaly = false

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts the detection engine.  Safe to call multiple times — subsequent calls are no-ops.
     * Data flows are supplied by the [SensorRepository] via the caller.
     */
    fun start(
        rssiFlow: Flow<List<RssiData>>,
        csiFlow: Flow<CsiData>
    ) {
        if (isStarted) return
        isStarted = true

        // Phase 1: Statistical detector (always active)
        scope.launch {
            statisticalDetector.analyze(rssiFlow)
                .onEach { state ->
                    latestStatistical = state
                    if (!modelManager.isModelAvailable()) {
                        _presenceState.emit(state)
                        _confidence.value = if (state != PresenceState.UNKNOWN) 0.65f else 0f
                    } else {
                        combine()
                    }
                }
                .catch { /* log silently */ }
                .collect()
        }

        // Phase 2 only: CSI-ML presence
        scope.launch {
            csiPresenceDetector.presenceState
                .onEach { state ->
                    latestMl = state
                    combine()
                }
                .catch { }
                .collect()
        }

        scope.launch {
            csiPresenceDetector.activityType
                .onEach { activity ->
                    latestActivity = activity
                    _activityType.emit(activity)
                }
                .catch { }
                .collect()
        }

        // Phase 2: CSI processing pipeline
        scope.launch {
            csiPresenceDetector.process(csiFlow)
                .catch { }
                .collect()
        }

        // Phase 2: Fall detection
        scope.launch {
            fallDetector.monitor(rssiFlow, csiFlow)
                .onEach { isFall ->
                    latestFall = isFall
                    latestFallConf = fallDetector.confidence.value
                    if (isFall) {
                        _presenceState.emit(PresenceState.FALL_DETECTED)
                        _confidence.value = latestFallConf
                    }
                }
                .catch { }
                .collect()
        }

        // Phase 2: Anomaly detection
        scope.launch {
            anomalyDetector.monitor(presenceState)
                .onEach { isAnomaly -> latestAnomaly = isAnomaly }
                .catch { }
                .collect()
        }
    }

    // -----------------------------------------------------------------------
    // Combination logic
    // -----------------------------------------------------------------------

    private suspend fun combine() {
        // Fall always wins
        if (latestFall) {
            _presenceState.emit(PresenceState.FALL_DETECTED)
            _confidence.value = latestFallConf
            return
        }

        val mlScore = encodeStateScore(latestMl)
        val statScore = encodeStateScore(latestStatistical)
        val anomalyBoost = if (latestAnomaly) 0.1f else 0f

        val weightedScore = WEIGHT_ML * mlScore + WEIGHT_STATISTICAL * statScore + anomalyBoost
        val combinedState = decodeScore(weightedScore, latestMl, latestStatistical)

        _presenceState.emit(combinedState)
        _confidence.value = computeConfidence(weightedScore, combinedState)
    }

    private fun encodeStateScore(state: PresenceState): Float = when (state) {
        PresenceState.EMPTY -> 0f
        PresenceState.UNKNOWN -> 0f
        PresenceState.PRESENCE_DETECTED -> 0.5f
        PresenceState.MOVEMENT_DETECTED -> 0.75f
        PresenceState.FALL_DETECTED -> 1f
    }

    private fun decodeScore(
        score: Float,
        mlState: PresenceState,
        statState: PresenceState
    ): PresenceState = when {
        score >= 0.85f -> PresenceState.FALL_DETECTED
        score >= 0.60f -> PresenceState.MOVEMENT_DETECTED
        score >= 0.35f -> PresenceState.PRESENCE_DETECTED
        // Both detectors agree on empty
        mlState == PresenceState.EMPTY && statState == PresenceState.EMPTY -> PresenceState.EMPTY
        score < 0.15f -> PresenceState.EMPTY
        else -> PresenceState.UNKNOWN
    }

    private fun computeConfidence(weightedScore: Float, state: PresenceState): Float {
        if (state == PresenceState.UNKNOWN) return 0f
        // Confidence is higher when both detectors agree
        val agreement = if (latestMl == latestStatistical) 0.15f else 0f
        return (weightedScore + agreement).coerceIn(0f, 1f)
    }
}
