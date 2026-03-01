package com.waveguard.domain.alert

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.waveguard.data.model.AlertEvent
import com.waveguard.data.model.AlertType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the "waveguard_alerts" notification channel and dispatches notifications
 * for WaveGuard alert events.
 *
 * - **Fall alerts** → high-priority heads-up notification.
 * - **Presence / movement / anomaly alerts** → default-priority notification.
 *
 * Each notification includes Acknowledge and View Details action buttons.
 *
 * Named [WaveGuardNotificationManager] to avoid clashing with [android.app.NotificationManager].
 */
@Singleton
class WaveGuardNotificationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        const val CHANNEL_ID = "waveguard_alerts"
        private const val CHANNEL_NAME = "WaveGuard Alerts"
        private const val CHANNEL_DESCRIPTION = "Security and presence detection alerts"

        private const val ACTION_ACKNOWLEDGE = "com.waveguard.ACTION_ACKNOWLEDGE"
        private const val ACTION_VIEW_DETAILS = "com.waveguard.ACTION_VIEW_DETAILS"
        private const val EXTRA_ALERT_ID = "alert_id"

        // Notification IDs (stable per type so new alerts replace old ones of the same type)
        private const val NOTIF_ID_FALL = 1001
        private const val NOTIF_ID_INTRUSION = 1002
        private const val NOTIF_ID_PRESENCE = 1003
        private const val NOTIF_ID_ANOMALY = 1004
        private const val NOTIF_ID_SYSTEM = 1005
    }

    private val systemNotificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createChannel()
    }

    // -----------------------------------------------------------------------
    // Channel setup
    // -----------------------------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESCRIPTION
            enableVibration(true)
            enableLights(true)
        }
        systemNotificationManager.createNotificationChannel(channel)
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Posts a notification for [event].  Priority is determined by [alertType].
     */
    fun sendAlert(event: AlertEvent, alertType: AlertType) {
        val notifId = notifIdFor(alertType)
        val priority = priorityFor(alertType)
        val title = titleFor(alertType)

        val acknowledgeIntent = buildPendingIntent(ACTION_ACKNOWLEDGE, event.id, notifId)
        val viewDetailsIntent = buildPendingIntent(ACTION_VIEW_DETAILS, event.id, notifId + 100)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(event.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(event.message))
            .setPriority(priority)
            .setAutoCancel(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Acknowledge",
                acknowledgeIntent
            )
            .addAction(
                android.R.drawable.ic_menu_info_details,
                "View Details",
                viewDetailsIntent
            )
            .build()

        systemNotificationManager.notify(notifId, notification)
    }

    /**
     * Cancels the notification for [alertType].
     */
    fun cancelAlert(alertType: AlertType) {
        systemNotificationManager.cancel(notifIdFor(alertType))
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun notifIdFor(type: AlertType): Int = when (type) {
        AlertType.FALL_DETECTED -> NOTIF_ID_FALL
        AlertType.INTRUSION_SUSPECTED -> NOTIF_ID_INTRUSION
        AlertType.PRESENCE_DETECTED -> NOTIF_ID_PRESENCE
        AlertType.UNUSUAL_ACTIVITY -> NOTIF_ID_ANOMALY
        AlertType.SYSTEM_STATUS -> NOTIF_ID_SYSTEM
    }

    private fun priorityFor(type: AlertType): Int = when (type) {
        AlertType.FALL_DETECTED -> NotificationCompat.PRIORITY_MAX
        AlertType.INTRUSION_SUSPECTED -> NotificationCompat.PRIORITY_HIGH
        AlertType.PRESENCE_DETECTED -> NotificationCompat.PRIORITY_DEFAULT
        AlertType.UNUSUAL_ACTIVITY -> NotificationCompat.PRIORITY_LOW
        AlertType.SYSTEM_STATUS -> NotificationCompat.PRIORITY_MIN
    }

    private fun titleFor(type: AlertType): String = when (type) {
        AlertType.FALL_DETECTED -> "⚠️ Fall Detected"
        AlertType.INTRUSION_SUSPECTED -> "🚨 Intrusion Suspected"
        AlertType.PRESENCE_DETECTED -> "👤 Presence Detected"
        AlertType.UNUSUAL_ACTIVITY -> "🕐 Unusual Activity"
        AlertType.SYSTEM_STATUS -> "ℹ️ System Status"
    }

    private fun buildPendingIntent(action: String, alertId: Long, requestCode: Int): PendingIntent {
        val intent = Intent(action).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ALERT_ID, alertId)
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
