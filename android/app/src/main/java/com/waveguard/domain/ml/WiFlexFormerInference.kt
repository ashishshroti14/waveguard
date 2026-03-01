package com.waveguard.domain.ml

import android.content.Context
import android.util.Log
import com.waveguard.data.model.ActivityType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe TFLite wrapper for the WiFlexFormer model.
 *
 * Model asset: `wiflexformer.tflite`
 * Input shape:  [1, 52, 351]  (batch, subcarriers, window samples)
 * Output shape: [1, 4]        class probabilities — [empty, presence, movement, fall]
 *
 * If the model asset is missing the class degrades gracefully: [isAvailable] returns `false`
 * and [interpret] / [interpretRaw] return an UNKNOWN result.
 */
@Singleton
class WiFlexFormerInference @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WiFlexFormerInference"
        private const val MODEL_ASSET = "wiflexformer.tflite"

        const val NUM_SUBCARRIERS = 52
        const val WINDOW_SIZE = 351
        const val NUM_CLASSES = 4

        // Output class indices
        private const val CLASS_EMPTY = 0
        private const val CLASS_PRESENCE = 1
        private const val CLASS_MOVEMENT = 2
        private const val CLASS_FALL = 3

        private const val CONFIDENCE_FLOOR = 0.40f
    }

    private val mutex = Mutex()
    private var interpreter: Interpreter? = null

    init {
        loadModel()
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /** Loads the TFLite model from assets.  Safe to call multiple times. */
    fun loadModel() {
        try {
            val modelBuffer = loadModelBuffer() ?: return
            val options = Interpreter.Options().apply {
                numThreads = 2
            }
            interpreter = Interpreter(modelBuffer, options)
            Log.i(TAG, "WiFlexFormer model loaded successfully")
        } catch (e: Exception) {
            Log.w(TAG, "WiFlexFormer model not available: ${e.message}")
            interpreter = null
        }
    }

    /** Returns `true` if the TFLite interpreter is initialised and ready. */
    fun isAvailable(): Boolean = interpreter != null

    /** Closes the TFLite interpreter and releases native resources. */
    fun close() {
        interpreter?.close()
        interpreter = null
    }

    // -----------------------------------------------------------------------
    // Inference API
    // -----------------------------------------------------------------------

    /**
     * Classifies a preprocessed CSI window and returns the top activity class with confidence.
     *
     * @param inputData flat [FloatArray] of size [NUM_SUBCARRIERS] × [WINDOW_SIZE] (18 252 elements)
     *                  produced by [CsiPreprocessor].
     * @return [Pair] of ([ActivityType], confidence 0–1).  Returns (UNKNOWN, 0) when model is
     *         unavailable or input is malformed.
     */
    suspend fun interpret(inputData: FloatArray): Pair<ActivityType, Float> {
        val raw = interpretRaw(inputData)
        if (raw.isEmpty()) return ActivityType.UNKNOWN to 0f
        val maxIdx = raw.indices.maxByOrNull { raw[it] } ?: return ActivityType.UNKNOWN to 0f
        val confidence = raw[maxIdx]
        if (confidence < CONFIDENCE_FLOOR) return ActivityType.UNKNOWN to confidence
        return mapIndexToActivity(maxIdx) to confidence
    }

    /**
     * Runs inference and returns the raw output probability array (length = [NUM_CLASSES]).
     * Returns an empty array when the model is unavailable.
     */
    suspend fun interpretRaw(inputData: FloatArray): FloatArray = mutex.withLock {
        val interp = interpreter ?: return@withLock floatArrayOf()
        if (inputData.size != NUM_SUBCARRIERS * WINDOW_SIZE) {
            Log.w(TAG, "Unexpected input size: ${inputData.size}, expected ${NUM_SUBCARRIERS * WINDOW_SIZE}")
            return@withLock floatArrayOf()
        }

        // Build input tensor [1, 52, 351]
        val inputBuffer = ByteBuffer.allocateDirect(4 * NUM_SUBCARRIERS * WINDOW_SIZE)
            .order(ByteOrder.nativeOrder())
        inputData.forEach { inputBuffer.putFloat(it) }
        inputBuffer.rewind()

        // Build output tensor [1, 4]
        val outputBuffer = Array(1) { FloatArray(NUM_CLASSES) }

        return@withLock try {
            interp.run(inputBuffer, outputBuffer)
            outputBuffer[0]
        } catch (e: Exception) {
            Log.e(TAG, "Inference failed: ${e.message}")
            floatArrayOf()
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun loadModelBuffer(): MappedByteBuffer? =
        try {
            val afd = context.assets.openFd(MODEL_ASSET)
            FileInputStream(afd.fileDescriptor).channel.map(
                FileChannel.MapMode.READ_ONLY,
                afd.startOffset,
                afd.declaredLength
            )
        } catch (e: Exception) {
            Log.w(TAG, "Model asset '$MODEL_ASSET' not found in assets: ${e.message}")
            null
        }

    private fun mapIndexToActivity(index: Int): ActivityType = when (index) {
        CLASS_EMPTY -> ActivityType.EMPTY
        CLASS_PRESENCE -> ActivityType.SITTING      // generic presence → sitting as default
        CLASS_MOVEMENT -> ActivityType.WALKING
        CLASS_FALL -> ActivityType.FALLING
        else -> ActivityType.UNKNOWN
    }
}
