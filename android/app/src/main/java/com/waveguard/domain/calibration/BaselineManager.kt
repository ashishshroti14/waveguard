package com.waveguard.domain.calibration

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists per-AP baseline statistics (mean RSSI, variance) using [SharedPreferences].
 *
 * Each AP is keyed by its BSSID.  Calibration is considered valid for 24 hours;
 * [isCalibrationValid] returns `false` after that window has elapsed.
 */
@Singleton
class BaselineManager @Inject constructor(
    @ApplicationContext context: Context
) {

    companion object {
        private const val PREFS_NAME = "waveguard_baseline"
        private const val KEY_CALIBRATION_TIME = "calibration_time_ms"
        private const val VALIDITY_WINDOW_MS = 24L * 60 * 60 * 1_000  // 24 h
        private const val SUFFIX_MEAN = "_mean"
        private const val SUFFIX_VARIANCE = "_variance"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -----------------------------------------------------------------------
    // Write
    // -----------------------------------------------------------------------

    /**
     * Saves the baseline [mean] and [variance] for the given [bssid].
     * Values are stored as raw bits of a [Float] so they survive serialisation without
     * precision loss.
     */
    fun saveBaseline(bssid: String, mean: Float, variance: Float) {
        prefs.edit()
            .putFloat(bssid + SUFFIX_MEAN, mean)
            .putFloat(bssid + SUFFIX_VARIANCE, variance)
            .apply()
    }

    /**
     * Records the timestamp of the most recent successful calibration.
     * Called by [RoomCalibrator] after all baselines are saved.
     */
    fun recordCalibrationTime(timeMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_CALIBRATION_TIME, timeMs).apply()
    }

    /** Removes all stored baseline entries and resets the calibration timestamp. */
    fun clearBaseline() {
        prefs.edit().clear().apply()
    }

    // -----------------------------------------------------------------------
    // Read
    // -----------------------------------------------------------------------

    /**
     * Returns the stored (mean, variance) pair for [bssid], or `null` if no baseline
     * has been saved for that AP.
     */
    fun getBaseline(bssid: String): Pair<Float, Float>? {
        if (!prefs.contains(bssid + SUFFIX_MEAN)) return null
        val mean = prefs.getFloat(bssid + SUFFIX_MEAN, 0f)
        val variance = prefs.getFloat(bssid + SUFFIX_VARIANCE, 1f)
        return mean to variance
    }

    /**
     * Returns the epoch millisecond timestamp of the last successful calibration,
     * or `0` if calibration has never been performed.
     */
    val lastCalibrationTime: Long
        get() = prefs.getLong(KEY_CALIBRATION_TIME, 0L)

    /**
     * Returns `true` when a calibration exists and was performed within the last 24 hours.
     */
    fun isCalibrationValid(): Boolean {
        val last = lastCalibrationTime
        if (last == 0L) return false
        return System.currentTimeMillis() - last < VALIDITY_WINDOW_MS
    }

    /**
     * Returns all BSSIDs for which a baseline has been saved.
     */
    fun calibratedBssids(): Set<String> =
        prefs.all.keys
            .filter { it.endsWith(SUFFIX_MEAN) }
            .map { it.removeSuffix(SUFFIX_MEAN) }
            .toSet()
}
