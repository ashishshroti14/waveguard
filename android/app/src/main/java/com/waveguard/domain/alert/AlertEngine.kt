package com.waveguard.domain.alert

import com.waveguard.data.model.ActivityType
import com.waveguard.data.model.AlertEvent
import com.waveguard.data.model.AlertType
import com.waveguard.data.model.PresenceState
import com.waveguard.data.repository.AlertRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Generates, throttles, and persists [AlertEvent]s based on [PresenceState] and
 * [ActivityType] streams.
 *
 * Alert priority (highest → lowest): FALL > INTRUSION > PRESENCE > ANOMALY.
 * Throttling: minimum [THROTTLE_MS] (30 s) between consecutive alerts of the same type.
 *
 * Side effects:
 * - Forwards high-priority alerts to [WaveGuardNotificationManager].
 * - Persists every alert via [AlertRepository].
 */
@Singleton
class AlertEngine @Inject constructor(
    private val notificationManager: WaveGuardNotificationManager,
    private val alertRepository: AlertRepository
) {

    companion object {
        private const val TAG = "AlertEngine"
        private const val THROTTLE_MS = 30_000L // 30 s between same-type alerts
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Last emission timestamp per AlertType
    private val lastEmitMs = mutableMapOf<AlertType, Long>()

    private val _alerts = MutableSharedFlow<AlertEvent>(
        replay = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val alerts: SharedFlow<AlertEvent> = _alerts.asSharedFlow()

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Starts monitoring [presenceFlow] and [activityFlow].  Emits [AlertEvent]s via [alerts],
     * sends notifications, and persists to the database.
     *
     * @param confidenceFlow optional confidence score; defaults to 1.0 when null
     */
    fun start(
        presenceFlow: Flow<PresenceState>,
        activityFlow: Flow<ActivityType>,
        confidenceFlow: Flow<Float>? = null
    ) {
        scope.launch {
            presenceFlow
                .combine(activityFlow) { presence, activity -> presence to activity }
                .onEach { (presence, activity) ->
                    evaluate(presence, activity, confidence = 1f)
                }
                .catch { e -> Log.e(TAG, "Alert evaluation stream failed", e) }
                .collect()
        }
    }

    // -----------------------------------------------------------------------
    // Evaluation
    // -----------------------------------------------------------------------

    private suspend fun evaluate(
        presence: PresenceState,
        activity: ActivityType,
        confidence: Float
    ) {
        val (type, message) = resolveAlert(presence, activity) ?: return

        if (!isThrottled(type)) {
            val event = AlertEvent(
                timestamp = System.currentTimeMillis(),
                type = type.name,
                presenceState = presence.name,
                activityType = activity.name,
                message = message,
                confidence = confidence
            )
            emit(event)
        }
    }

    /** Returns the highest-priority alert for the given state/activity, or null if none. */
    private fun resolveAlert(
        presence: PresenceState,
        activity: ActivityType
    ): Pair<AlertType, String>? = when {
        presence == PresenceState.FALL_DETECTED ||
                activity == ActivityType.FALLING ->
            AlertType.FALL_DETECTED to "Fall detected! Immediate assistance may be needed."

        presence == PresenceState.MOVEMENT_DETECTED ->
            AlertType.INTRUSION_SUSPECTED to "Movement detected — possible intrusion."

        presence == PresenceState.PRESENCE_DETECTED ->
            AlertType.PRESENCE_DETECTED to "Presence detected in the monitored area."

        else -> null
    }

    private fun isThrottled(type: AlertType): Boolean {
        val now = System.currentTimeMillis()
        val last = lastEmitMs[type] ?: 0L
        return now - last < THROTTLE_MS
    }

    private suspend fun emit(event: AlertEvent) {
        lastEmitMs[AlertType.valueOf(event.type)] = System.currentTimeMillis()
        _alerts.emit(event)

        // Persist asynchronously
        scope.launch { alertRepository.saveAlert(event) }

        // Send notification
        val alertType = AlertType.valueOf(event.type)
        notificationManager.sendAlert(event, alertType)
    }
}
