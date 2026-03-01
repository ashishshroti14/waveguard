package com.waveguard.data.repository

import com.waveguard.data.local.AlertDao
import com.waveguard.data.model.AlertEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for persisting and querying [AlertEvent] records via the Room [AlertDao].
 */
@Singleton
class AlertRepository @Inject constructor(
    private val alertDao: AlertDao
) {
    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    /** Emits the full list of alerts, ordered by most-recent-first, updating on every change. */
    fun getAllAlerts(): Flow<List<AlertEvent>> = alertDao.getAll()

    /**
     * Emits alerts whose timestamp falls within the last [hours] hours, ordered by
     * most-recent-first.  The flow updates whenever the underlying table changes.
     */
    fun getRecentAlerts(hours: Int): Flow<List<AlertEvent>> {
        val cutoff = System.currentTimeMillis() - hours.toLong() * 60 * 60 * 1_000
        return alertDao.getAll().map { alerts ->
            alerts.filter { it.timestamp >= cutoff }
        }
    }

    /** Emits only alerts that the user has not yet acknowledged. */
    fun getUnacknowledgedAlerts(): Flow<List<AlertEvent>> = alertDao.getUnacknowledged()

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    /** Persists [alert] to the database and returns the generated row id. */
    suspend fun saveAlert(alert: AlertEvent): Long = alertDao.insert(alert)

    /** Marks the alert identified by [id] as acknowledged. */
    suspend fun acknowledgeAlert(id: Long) = alertDao.acknowledge(id)

    /**
     * Deletes alerts older than [hours] hours.  Useful for housekeeping; call from a
     * background WorkManager task rather than on the main thread.
     */
    suspend fun deleteAlertsOlderThan(hours: Int) {
        val cutoff = System.currentTimeMillis() - hours.toLong() * 60 * 60 * 1_000
        alertDao.deleteOlderThan(cutoff)
    }
}
