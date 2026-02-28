package com.waveguard.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.waveguard.data.model.AlertEvent

@Database(
    entities = [CsiDataEntity::class, AlertEvent::class],
    version = 1,
    exportSchema = false
)
abstract class WaveGuardDatabase : RoomDatabase() {

    abstract fun csiDao(): CsiDao
    abstract fun alertDao(): AlertDao

    companion object {
        private const val DB_NAME = "waveguard.db"

        @Volatile
        private var INSTANCE: WaveGuardDatabase? = null

        fun getInstance(context: Context): WaveGuardDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WaveGuardDatabase::class.java,
                    DB_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
