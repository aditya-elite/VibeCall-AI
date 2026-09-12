package com.vibecall.sensortest

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.nnapi.NnApiDelegate
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Structured inference result returned by [FusionConfidenceModel].
 */
data class FusionInferenceResult(
    val confidence: Float,
    val modelReliable: Boolean,
    val backendStatus: String,
    val inferenceLatencyUs: Long,
    val error: String? = null
)

/**
 * Step 4 Feature-Based Fusion-Confidence Model wrapper for VibeCall-AI.
 *
 * Evaluates the calibrated 16-element feature vector in exact tensor order:
 *  [0]  microphone_log_energy_db
 *  [1]  microphone_pitch_strength
 *  [2]  microphone_pitch_reliable
 *  [3]  log_accel_peak_power
 *  [4]  accel_peak_prominence
 *  [5]  pitch_difference_hz
 *  [6]  pitch_agreement_score
 *  [7]  pitch_agreement_reliable
 *  [8]  phone_motion_level
 *  [9]  sensor_reliability
 *  [10] sensor_alignment_lag_ms
 *  [11] prev_microphone_log_energy_db
 *  [12] prev_microphone_pitch_strength
 *  [13] prev_pitch_agreement_score
 *  [14] prev_phone_motion_level
 *  [15] prev_sensor_reliability
 *
 * Normalizes features matching the exported metadata schema and executes via
 * NNAPI delegate with safe fallback to CPU. Fails safe if model or inference is invalid.
 */
class FusionConfidenceModel(
    context: Context,
    modelFilename: String = "fusion_confidence_model.tflite",
    metadataFilename: String = "fusion_model_metadata.json"
) {

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null

    private var backendStatus: String = "Model uninitialized"
    private var isModelInitialized: Boolean = false

    // Normalization constants (loaded from metadata or fallback defaults)
    private val means = FloatArray(INPUT_DIM)
    private val stds = FloatArray(INPUT_DIM) { 1.0f }
    private var clipMin: Float = -5.0f
    private var clipMax: Float = 5.0f

    // Pre-allocated reusable I/O buffers for zero-garbage streaming inference
    private val inputTensor = Array(1) { FloatArray(INPUT_DIM) }
    private val outputTensor = Array(1) { FloatArray(OUTPUT_DIM) }

    // Telemetry and profiling metrics
    private var inferenceCount: Int = 0
    private var failureCount: Int = 0
    private var totalLatencyUs: Long = 0L
    private var maxLatencyUs: Long = 0L
    private var lastError: String? = null

    init {
        loadMetadata(context, metadataFilename)
        initializeInterpreter(context, modelFilename)
    }

    private fun loadMetadata(context: Context, filename: String) {
        try {
            val jsonString = context.assets.open(filename).bufferedReader().use { it.readText() }
            val root = JSONObject(jsonString)
            val norm = root.getJSONObject("normalization")
            val jsonMeans = norm.getJSONArray("means")
            val jsonStds = norm.getJSONArray("stds")
            clipMin = norm.optDouble("clip_min", -5.0).toFloat()
            clipMax = norm.optDouble("clip_max", 5.0).toFloat()

            for (i in 0 until minOf(INPUT_DIM, jsonMeans.length())) {
                means[i] = jsonMeans.getDouble(i).toFloat()
            }
            for (i in 0 until minOf(INPUT_DIM, jsonStds.length())) {
                val s = jsonStds.getDouble(i).toFloat()
                stds[i] = if (s > 1e-6f) s else 1.0f
            }
            Log.i(TAG, "Loaded fusion model metadata ($filename) with $INPUT_DIM feature normalization constants.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $filename; using neutral fallback normalization.", e)
            lastError = "Metadata load failed: ${e.message}"
        }
    }

    private fun initializeInterpreter(context: Context, filename: String) {
        val modelBuffer: MappedByteBuffer
        try {
            modelBuffer = loadModelFile(context, filename)
        } catch (e: Exception) {
            backendStatus = "Model unavailable (file not found)"
            isModelInitialized = false
            failureCount++
            lastError = "Model file load failed: ${e.message}"
            Log.e(TAG, "Could not open $filename from assets.", e)
            return
        }

        // Try NNAPI execution first
        try {
            val delegate = NnApiDelegate()
            nnApiDelegate = delegate
            val options = Interpreter.Options().apply {
                addDelegate(delegate)
                setNumThreads(2)
            }
            interpreter = Interpreter(modelBuffer, options)
            backendStatus = "NNAPI requested/active"
            isModelInitialized = true
            Log.i(TAG, "FusionConfidenceModel initialized with NNAPI delegate.")
        } catch (e: Exception) {
            Log.w(TAG, "NNAPI delegate failed to initialize; falling back to CPU interpreter.", e)
            try {
                nnApiDelegate?.close()
                nnApiDelegate = null
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(2)
                }
                interpreter = Interpreter(modelBuffer, cpuOptions)
                backendStatus = "CPU fallback"
                isModelInitialized = true
                Log.i(TAG, "FusionConfidenceModel initialized with CPU fallback interpreter.")
            } catch (cpuEx: Exception) {
                backendStatus = "Model unavailable (CPU init failed)"
                isModelInitialized = false
                failureCount++
                lastError = "CPU init failed: ${cpuEx.message}"
                Log.e(TAG, "Failed to initialize CPU fallback interpreter for $filename.", cpuEx)
            }
        }
    }

    /**
     * Runs inference on the provided 16-element feature vector.
     *
     * @param rawFeatures16 FloatArray of length 16 in exact documented tensor order.
     * @return [FusionInferenceResult] containing confidence and reliability flag.
     */
    @Synchronized
    fun infer(rawFeatures16: FloatArray): FusionInferenceResult {
        if (!isModelInitialized || interpreter == null) {
            failureCount++
            return FusionInferenceResult(
                confidence = 0.5f,
                modelReliable = false,
                backendStatus = backendStatus,
                inferenceLatencyUs = 0L,
                error = lastError ?: "Model not initialized"
            )
        }

        if (rawFeatures16.size != INPUT_DIM) {
            failureCount++
            val err = "Invalid input vector dimension: expected $INPUT_DIM, got ${rawFeatures16.size}"
            Log.w(TAG, err)
            return FusionInferenceResult(
                confidence = 0.5f,
                modelReliable = false,
                backendStatus = backendStatus,
                inferenceLatencyUs = 0L,
                error = err
            )
        }

        // Apply z-score normalization and clipping
        val inputRow = inputTensor[0]
        for (i in 0 until INPUT_DIM) {
            val v = rawFeatures16[i]
            if (v.isNaN() || v.isInfinite()) {
                inputRow[i] = 0.0f
            } else {
                val normVal = (v - means[i]) / stds[i]
                inputRow[i] = normVal.coerceIn(clipMin, clipMax)
            }
        }

        val startNs = SystemClock.elapsedRealtimeNanos()
        return try {
            interpreter?.run(inputTensor, outputTensor)
            val endNs = SystemClock.elapsedRealtimeNanos()
            val latencyUs = (endNs - startNs) / 1000L

            val rawConf = outputTensor[0][0]
            val confidence = if (rawConf.isNaN() || rawConf.isInfinite()) 0.5f else rawConf.coerceIn(0.0f, 1.0f)

            inferenceCount++
            totalLatencyUs += latencyUs
            maxLatencyUs = maxOf(maxLatencyUs, latencyUs)

            FusionInferenceResult(
                confidence = confidence,
                modelReliable = true,
                backendStatus = backendStatus,
                inferenceLatencyUs = latencyUs,
                error = null
            )
        } catch (e: Exception) {
            failureCount++
            val err = "Inference execution error: ${e.message}"
            Log.e(TAG, err, e)
            FusionInferenceResult(
                confidence = 0.5f,
                modelReliable = false,
                backendStatus = backendStatus,
                inferenceLatencyUs = 0L,
                error = err
            )
        }
    }

    fun getAverageLatencyUs(): Double {
        return if (inferenceCount > 0) totalLatencyUs.toDouble() / inferenceCount else 0.0
    }

    fun getMaxLatencyUs(): Long = maxLatencyUs
    fun getInferenceCount(): Int = inferenceCount
    fun getFailureCount(): Int = failureCount
    fun getBackendStatus(): String = backendStatus

    fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
        interpreter = null
        nnApiDelegate = null
        isModelInitialized = false
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    companion object {
        private const val TAG = "FusionConfidenceModel"
        const val INPUT_DIM = 16
        const val OUTPUT_DIM = 1
    }
}
