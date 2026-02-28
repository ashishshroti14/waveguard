package com.waveguard.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "alert_events")
data class AlertEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val type: String,           // AlertType enum name
    val presenceState: String,
    val activityType: String,
    val message: String,
    val confidence: Float,
    val isAcknowledged: Boolean = false
)

enum class AlertType {
    FALL_DETECTED,
    PRESENCE_DETECTED,
    INTRUSION_SUSPECTED,
    UNUSUAL_ACTIVITY,
    SYSTEM_STATUS
}
