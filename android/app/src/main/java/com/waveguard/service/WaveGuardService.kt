package com.waveguard.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.waveguard.MainActivity
import com.waveguard.R
import com.waveguard.data.repository.SensorRepository
import com.waveguard.domain.alert.AlertEngine
import com.waveguard.domain.detection.PresenceDetector
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class WaveGuardService : Service() {

    companion object {
        const val ACTION_START = "com.waveguard.ACTION_START"
        const val ACTION_STOP = "com.waveguard.ACTION_STOP"

        private const val FOREGROUND_NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "waveguard_service"
        private const val CHANNEL_NAME = "WaveGuard Monitoring"

        fun startIntent(context: Context) =
            Intent(context, WaveGuardService::class.java).apply { action = ACTION_START }

        fun stopIntent(context: Context) =
            Intent(context, WaveGuardService::class.java).apply { action = ACTION_STOP }
    }

    @Inject lateinit var presenceDetector: PresenceDetector
    @Inject lateinit var alertEngine: AlertEngine
    @Inject lateinit var sensorRepository: SensorRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startMonitoring()
            ACTION_STOP -> stopSelf()
        }
        return START_STICKY
    }

    private fun startMonitoring() {
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification())

        serviceScope.launch {
            presenceDetector.start(
                rssiFlow = sensorRepository.rssiData,
                csiFlow = sensorRepository.csiData
            )
            alertEngine.start(
                presenceFlow = presenceDetector.presenceState,
                activityFlow = presenceDetector.activityType,
                confidenceFlow = presenceDetector.confidence
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    // -----------------------------------------------------------------------
    // Foreground notification
    // -----------------------------------------------------------------------

    private fun buildForegroundNotification(): Notification {
        createServiceChannel()

        val openAppIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this,
            0,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_waveguard)
            .setContentTitle(getString(R.string.service_notification_title))
            .setContentText(getString(R.string.service_notification_text))
            .setContentIntent(openAppIntent)
            .addAction(R.drawable.ic_waveguard, getString(R.string.action_stop), stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun createServiceChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Persistent notification while WaveGuard is monitoring"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}
