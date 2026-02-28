package com.waveguard.domain.detection

import com.waveguard.data.model.ActivityType
import com.waveguard.data.model.CsiData
import com.waveguard.data.model.PresenceState
import com.waveguard.domain.ml.CsiPreprocessor
import com.waveguard.domain.ml.WiFlexFormerInference
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects presence and activity type using the WiFlexFormer ML model on batched CSI windows.
 *
 * Incoming [CsiData] is buffered until a full window of [CSI_WINDOW_SIZE] (351) samples is
 * available.  Each completed window is preprocessed by [CsiPreprocessor] and fed into
 * [WiFlexFormerInference].  The resulting activity classification is translated into
 * [PresenceState] and [ActivityType] signals.
 */
@Singleton
class CsiPresenceDetector @Inject constructor(
    private val preprocessor: CsiPreprocessor,
    private val inference: WiFlexFormerInference
) {

    companion object {
        /** Window size expected by WiFlexFormer: 351 CSI frames. */
        const val CSI_WINDOW_SIZE = 351
    }

    // -----------------------------------------------------------------------
    // Output flows
    // -----------------------------------------------------------------------

    private val _presenceState = MutableSharedFlow<PresenceState>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val presenceState: Flow<PresenceState> = _presenceState.asSharedFlow()

    private val _activityType = MutableSharedFlow<ActivityType>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val activityType: Flow<ActivityType> = _activityType.asSharedFlow()

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Subscribes to [csiFlow] and emits [presenceState] / [activityType] updates as each
     * complete CSI window is processed.  Collect the returned [Flow] to start processing.
     */
    fun process(csiFlow: Flow<CsiData>): Flow<Unit> = channelFlow {
        val buffer = ArrayDeque<CsiData>(CSI_WINDOW_SIZE + 1)

        csiFlow
            .onEach { frame ->
                buffer.addLast(frame)
                if (buffer.size >= CSI_WINDOW_SIZE) {
                    val window = buffer.toList()
                    buffer.clear()
                    classifyWindow(window)
                }
            }
            .collect()
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    private suspend fun classifyWindow(window: List<CsiData>) {
        val input = preprocessor.preprocess(window)
        val (activity, confidence) = inference.interpret(input)

        val presence = mapToPresenceState(activity, confidence)
        _presenceState.emit(presence)
        _activityType.emit(activity)
    }

    /**
     * Maps a [WiFlexFormerInference] activity classification to a [PresenceState].
     * A confidence floor of 0.45 is applied; below that the state is UNKNOWN.
     */
    private fun mapToPresenceState(activity: ActivityType, confidence: Float): PresenceState {
        if (confidence < 0.45f) return PresenceState.UNKNOWN
        return when (activity) {
            ActivityType.EMPTY -> PresenceState.EMPTY
            ActivityType.FALLING -> PresenceState.FALL_DETECTED
            ActivityType.SITTING,
            ActivityType.STANDING -> PresenceState.PRESENCE_DETECTED
            ActivityType.WALKING -> PresenceState.MOVEMENT_DETECTED
            ActivityType.UNKNOWN -> PresenceState.UNKNOWN
        }
    }
}
