package com.waveguard.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface CsiDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: CsiDataEntity): Long

    /** Returns the [limit] most recent CSI records ordered by descending timestamp. */
    @Query("SELECT * FROM csi_data ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<CsiDataEntity>

    /** Deletes all CSI records older than [beforeTimestamp] (epoch millis). */
    @Query("DELETE FROM csi_data WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long)
}
