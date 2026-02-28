package com.waveguard.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "csi_data")
data class CsiDataEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    /** Comma-separated amplitude values for 52 subcarriers. */
    val amplitudes: String,
    /** Comma-separated phase values for 52 subcarriers. */
    val phases: String,
    val rssi: Int,
    val noiseFloor: Int,
    val channel: Int,
    val bandwidth: Int
)
