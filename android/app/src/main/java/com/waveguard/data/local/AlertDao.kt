package com.waveguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.waveguard.data.model.AlertEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface AlertDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: AlertEvent): Long

    /** Emits the full alert history ordered by most-recent-first, updating on every change. */
    @Query("SELECT * FROM alert_events ORDER BY timestamp DESC")
    fun getAll(): Flow<List<AlertEvent>>

    /** Emits only alerts that have not yet been acknowledged, ordered by most-recent-first. */
    @Query("SELECT * FROM alert_events WHERE isAcknowledged = 0 ORDER BY timestamp DESC")
    fun getUnacknowledged(): Flow<List<AlertEvent>>

    /** Marks a single alert as acknowledged. */
    @Query("UPDATE alert_events SET isAcknowledged = 1 WHERE id = :id")
    suspend fun acknowledge(id: Long)

    /** Deletes all alerts whose timestamp is before [beforeTimestamp] (epoch millis). */
    @Query("DELETE FROM alert_events WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}
