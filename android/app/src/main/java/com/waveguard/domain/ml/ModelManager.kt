package com.waveguard.domain.ml

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the TFLite model lifecycle for the WaveGuard application.
 *
 * Centralises asset resolution, version tracking, and lazy loading so that
 * multiple consumers can query availability without each owning an [Interpreter].
 * The actual interpreter is owned by [WiFlexFormerInference]; this class acts
 * as a lightweight coordinator.
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inference: WiFlexFormerInference
) {

    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_ASSET = "wiflexformer.tflite"
        private const val VERSION_METADATA_ASSET = "model_version.txt"
        private const val UNKNOWN_VERSION = "unknown"
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /**
     * Loads the TFLite model from assets if it is not already loaded.
     * Safe to call multiple times; subsequent calls are no-ops when the model
     * is already available.
     */
    fun loadModel() {
        if (inference.isAvailable()) return
        Log.i(TAG, "Loading WiFlexFormer model…")
        inference.loadModel()
        if (inference.isAvailable()) {
            Log.i(TAG, "Model loaded. Version: ${getModelVersion()}")
        } else {
            Log.w(TAG, "Model failed to load — running without ML inference")
        }
    }

    /** Returns `true` when the TFLite interpreter is initialised and ready for inference. */
    fun isModelAvailable(): Boolean = inference.isAvailable()

    /**
     * Returns the version string embedded in `model_version.txt` asset, or
     * `"unknown"` if the file is absent or unreadable.
     */
    fun getModelVersion(): String =
        try {
            context.assets.open(VERSION_METADATA_ASSET)
                .bufferedReader()
                .use { it.readLine()?.trim() ?: UNKNOWN_VERSION }
        } catch (e: Exception) {
            UNKNOWN_VERSION
        }

    /**
     * Releases the TFLite interpreter.  Call when the owning component is destroyed
     * (e.g., from a Service's `onDestroy`).
     */
    fun releaseModel() {
        inference.close()
        Log.i(TAG, "Model released")
    }
}
