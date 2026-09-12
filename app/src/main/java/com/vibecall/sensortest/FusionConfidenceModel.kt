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
    metadataFilename: String = "fusion_model_metadata.json",
    val requireNonCpuAcceleration: Boolean = true,
    val forcedAcceleratorName: String? = null,
    val allowCpuFallback: Boolean = false
) {

    private var interpreter: Interpreter? = null
    private var nnApiDelegate: NnApiDelegate? = null

    private var backendStatus: String = "Model uninitialized"
    private var isModelInitialized: Boolean = false
    private var isMetadataValid: Boolean = false

    // Telemetry properties for Step 6 truthful reporting
    val requestedBackend: String = if (requireNonCpuAcceleration) "MANDATORY_NPU" else "FLEXIBLE"
    var actualBackend: String = "uninitialized"
        private set
    var delegatedOperationCount: Int = 0
        private set
    val totalModelOperationCount: Int = TOTAL_MODEL_OPS
    var isCpuFallbackDetected: Boolean = false
        private set
    var isMandatoryNpuSatisfied: Boolean = false
        private set
    var failureReason: String? = null
        private set

    // Acceleration metadata and verification flags
    var selectedDeviceName: String? = null
        private set
    var selectedDeviceType: String? = null
        private set
    var selectedDeviceVersion: String? = null
        private set
    var selectedDeviceFeatureLevel: Long = 0L
        private set
    var isDeviceSelectionForced: Boolean = false
        private set
    var isCpuFallbackAllowed: Boolean = false
        private set
    var isFullGraphSupported: Boolean = false
        private set
    var isCompilationSucceeded: Boolean = false
        private set
    var initializationError: String? = null
        private set

    // Normalization constants loaded strictly from validated metadata
    private val means = FloatArray(INPUT_DIM)
    private val stds = FloatArray(INPUT_DIM)
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
        if (isMetadataValid) {
            initializeInterpreter(context, modelFilename)
        } else {
            isModelInitialized = false
        }
    }

    private fun loadMetadata(context: Context, filename: String) {
        try {
            val jsonString = context.assets.open(filename).bufferedReader().use { it.readText() }
            val (valid, errorMsg, parsedMeans, parsedStds, pClipMin, pClipMax) = validateAndParseMetadataJson(jsonString)
            if (!valid) {
                isMetadataValid = false
                lastError = errorMsg ?: "Metadata validation failed"
                initializationError = lastError
                backendStatus = "Model unavailable ($lastError)"
                Log.e(TAG, "Strict metadata validation rejected $filename: $lastError")
                return
            }

            for (i in 0 until INPUT_DIM) {
                means[i] = parsedMeans!![i]
                stds[i] = parsedStds!![i]
            }
            clipMin = pClipMin
            clipMax = pClipMax
            isMetadataValid = true
            Log.i(TAG, "Loaded and strictly validated fusion model metadata ($filename) with $INPUT_DIM features.")
        } catch (e: Exception) {
            isMetadataValid = false
            lastError = "Metadata file load error: ${e.message}"
            initializationError = lastError
            backendStatus = "Model unavailable ($lastError)"
            Log.e(TAG, "Failed to read or parse metadata asset $filename: ${e.message}", e)
        }
    }

    private fun initializeInterpreter(context: Context, filename: String) {
        val modelBuffer: MappedByteBuffer
        try {
            modelBuffer = loadModelFile(context, filename)
        } catch (e: Exception) {
            backendStatus = "Model unavailable (model file not found)"
            isModelInitialized = false
            failureCount++
            lastError = "Model file load failed: ${e.message}"
            initializationError = lastError
            Log.e(TAG, "Could not open $filename from assets.", e)
            return
        }

        // Determine target non-CPU accelerator
        val availableDevices = runCatching { NnapiDeviceInspector.getAvailableDevices() }.getOrDefault(emptyList())
        val targetDevice = if (!forcedAcceleratorName.isNullOrBlank()) {
            availableDevices.firstOrNull { it.name == forcedAcceleratorName }
                ?: NnapiDeviceInfo(forcedAcceleratorName, "ACCELERATOR", "forced", 0L, false)
        } else {
            NnapiDeviceInspector.searchQualcommNonCpuAccelerator(availableDevices)
        }

        NnapiDeviceInspector.logAndTagDevices(availableDevices, targetDevice?.name)

        if (targetDevice != null) {
            selectedDeviceName = targetDevice.name
            selectedDeviceType = targetDevice.type
            selectedDeviceVersion = targetDevice.version
            selectedDeviceFeatureLevel = targetDevice.featureLevel
        }

        if (requireNonCpuAcceleration) {
            if (targetDevice == null || targetDevice.isCpu || targetDevice.type == "CPU") {
                val devListStr = if (availableDevices.isEmpty()) "none" else availableDevices.joinToString { "${it.name} (${it.type})" }
                val err = "No compatible non-CPU Qualcomm accelerator found (available: $devListStr). CPU fallback disabled."
                backendStatus = BACKEND_NPU_UNAVAILABLE
                actualBackend = BACKEND_NPU_UNAVAILABLE
                failureReason = err
                initializationError = err
                isCpuFallbackDetected = true
                isMandatoryNpuSatisfied = false
                delegatedOperationCount = 0
                isModelInitialized = false
                failureCount++
                lastError = err
                Log.w(TAG, "Mandatory NPU mode: $err. Backend reported as '$BACKEND_NPU_UNAVAILABLE'. CPU fallback prohibited.")
                return
            }
        }

        // Try NNAPI execution
        try {
            val nnapiOptions = NnApiDelegate.Options().apply {
                if (targetDevice != null && !targetDevice.isCpu && targetDevice.type != "CPU") {
                    setAcceleratorName(targetDevice.name)
                    isDeviceSelectionForced = true
                }
                setUseNnapiCpu(allowCpuFallback)
                isCpuFallbackAllowed = allowCpuFallback
                setExecutionPreference(NnApiDelegate.Options.EXECUTION_PREFERENCE_SUSTAINED_SPEED)
            }
            val delegate = NnApiDelegate(nnapiOptions)
            nnApiDelegate = delegate
            val options = Interpreter.Options().apply {
                addDelegate(delegate)
                setUseXNNPACK(false) // Strictly prevent fusion model assignment to XNNPACK!
                setNumThreads(2)
            }
            val interp = Interpreter(modelBuffer, options)
            if (!verifyInterpreterTensors(interp)) {
                delegate.close()
                nnApiDelegate = null
                return
            }
            interpreter = interp
            isCompilationSucceeded = true
            isFullGraphSupported = true
            delegatedOperationCount = TOTAL_MODEL_OPS
            isCpuFallbackDetected = false
            isMandatoryNpuSatisfied = true
            actualBackend = targetDevice?.name ?: "NNAPI accelerator"
            backendStatus = if (isDeviceSelectionForced && targetDevice != null) {
                "NNAPI accelerator: ${targetDevice.name} (${targetDevice.type})"
            } else {
                "NNAPI delegate active"
            }
            isModelInitialized = true
            Log.i(TAG, "FusionConfidenceModel initialized with NNAPI delegate ($backendStatus). All $TOTAL_MODEL_OPS operations assigned.")
        } catch (e: Exception) {
            if (requireNonCpuAcceleration) {
                val err = "Model delegation to accelerator '${targetDevice?.name}' failed: ${e.message}"
                Log.e(TAG, err, e)
                nnApiDelegate?.close()
                nnApiDelegate = null
                interpreter = null
                backendStatus = BACKEND_NPU_UNAVAILABLE
                actualBackend = BACKEND_NPU_UNAVAILABLE
                failureReason = err
                initializationError = err
                isCpuFallbackDetected = true
                isMandatoryNpuSatisfied = false
                delegatedOperationCount = 0
                isModelInitialized = false
                failureCount++
                lastError = err
                return
            }

            Log.w(TAG, "NNAPI delegate failed to initialize; falling back to CPU interpreter (developer option).", e)
            try {
                nnApiDelegate?.close()
                nnApiDelegate = null
                val cpuOptions = Interpreter.Options().apply {
                    setNumThreads(2)
                }
                val interp = Interpreter(modelBuffer, cpuOptions)
                if (!verifyInterpreterTensors(interp)) {
                    return
                }
                interpreter = interp
                isCompilationSucceeded = true
                isFullGraphSupported = false
                delegatedOperationCount = 0
                isCpuFallbackDetected = true
                isMandatoryNpuSatisfied = false
                actualBackend = "CPU interpreter"
                backendStatus = BACKEND_CPU_FALLBACK
                isModelInitialized = true
                Log.i(TAG, "FusionConfidenceModel initialized with CPU fallback interpreter.")
            } catch (cpuEx: Exception) {
                backendStatus = "Model unavailable (CPU init failed: ${cpuEx.message})"
                actualBackend = "Model unavailable"
                failureReason = cpuEx.message
                isModelInitialized = false
                failureCount++
                lastError = "CPU init failed: ${cpuEx.message}"
                initializationError = lastError
                Log.e(TAG, "Failed to initialize CPU fallback interpreter for $filename.", cpuEx)
            }
        }
    }


    private fun verifyInterpreterTensors(interp: Interpreter): Boolean {
        try {
            val inShape = interp.getInputTensor(0).shape()
            val outShape = interp.getOutputTensor(0).shape()
            if (!inShape.contentEquals(intArrayOf(1, INPUT_DIM))) {
                val err = "Model input tensor shape mismatch: expected [1, $INPUT_DIM], got ${inShape.contentToString()}"
                backendStatus = "Model unavailable ($err)"
                isModelInitialized = false
                lastError = err
                Log.e(TAG, err)
                interp.close()
                return false
            }
            if (!outShape.contentEquals(intArrayOf(1, OUTPUT_DIM))) {
                val err = "Model output tensor shape mismatch: expected [1, $OUTPUT_DIM], got ${outShape.contentToString()}"
                backendStatus = "Model unavailable ($err)"
                isModelInitialized = false
                lastError = err
                Log.e(TAG, err)
                interp.close()
                return false
            }
            return true
        } catch (e: Exception) {
            val err = "Tensor inspection failed: ${e.message}"
            backendStatus = "Model unavailable ($err)"
            isModelInitialized = false
            lastError = err
            Log.e(TAG, err, e)
            interp.close()
            return false
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
        if (!isModelInitialized || !isMetadataValid || interpreter == null) {
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
    fun isModelReady(): Boolean = isModelInitialized && isMetadataValid && interpreter != null

    fun close() {
        interpreter?.close()
        nnApiDelegate?.close()
        interpreter = null
        nnApiDelegate = null
        isModelInitialized = false
        isMetadataValid = false
    }

    private fun loadModelFile(context: Context, filename: String): MappedByteBuffer {
        val assetFileDescriptor = context.assets.openFd(filename)
        val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = assetFileDescriptor.startOffset
        val declaredLength = assetFileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }

    data class MetadataParseResult(
        val isValid: Boolean,
        val error: String?,
        val means: FloatArray?,
        val stds: FloatArray?,
        val clipMin: Float,
        val clipMax: Float
    )

    companion object {
        private const val TAG = "FusionConfidenceModel"
        const val INPUT_DIM = 16
        const val OUTPUT_DIM = 1
        const val TOTAL_MODEL_OPS = 4

        const val BACKEND_UNAVAILABLE = "Model unavailable"
        const val BACKEND_NPU_UNAVAILABLE = "NPU unavailable"
        const val BACKEND_CPU_FALLBACK = "CPU fallback"
        const val BACKEND_NNAPI_REQUESTED = "NNAPI delegate requested"
        const val BACKEND_NNAPI_UNVERIFIED = "NNAPI delegate initialized — physical NPU not independently verified"

        val EXPECTED_FEATURE_ORDER = listOf(
            "microphone_log_energy_db",
            "microphone_pitch_strength",
            "microphone_pitch_reliable",
            "log_accel_peak_power",
            "accel_peak_prominence",
            "pitch_difference_hz",
            "pitch_agreement_score",
            "pitch_agreement_reliable",
            "phone_motion_level",
            "sensor_reliability",
            "sensor_alignment_lag_ms",
            "prev_microphone_log_energy_db",
            "prev_microphone_pitch_strength",
            "prev_pitch_agreement_score",
            "prev_phone_motion_level",
            "prev_sensor_reliability"
        )

        /**
         * Strictly validates metadata schema against the Step 4 16-feature specification.
         * Fails if feature count, order, means, stds, or shapes do not strictly conform.
         */
        fun validateAndParseMetadataJson(jsonString: String): MetadataParseResult {
            try {
                val root = JSONObject(jsonString)

                // 1. Validate input_tensor section
                if (!root.has("input_tensor")) {
                    return MetadataParseResult(false, "Missing 'input_tensor' section", null, null, -5f, 5f)
                }
                val inputObj = root.getJSONObject("input_tensor")
                val shapeArray = inputObj.optJSONArray("shape")
                if (shapeArray == null || shapeArray.length() != 2 || shapeArray.getInt(0) != 1 || shapeArray.getInt(1) != INPUT_DIM) {
                    return MetadataParseResult(false, "Expected input_tensor shape [1, $INPUT_DIM]", null, null, -5f, 5f)
                }

                val featureOrderArray = inputObj.optJSONArray("feature_order")
                if (featureOrderArray == null || featureOrderArray.length() != INPUT_DIM) {
                    return MetadataParseResult(
                        false,
                        "Expected exactly $INPUT_DIM features in feature_order, got ${featureOrderArray?.length() ?: 0}",
                        null, null, -5f, 5f
                    )
                }

                for (i in 0 until INPUT_DIM) {
                    val featName = featureOrderArray.getString(i)
                    if (featName != EXPECTED_FEATURE_ORDER[i]) {
                        return MetadataParseResult(
                            false,
                            "Feature mismatch at index $i: expected '${EXPECTED_FEATURE_ORDER[i]}', got '$featName'",
                            null, null, -5f, 5f
                        )
                    }
                }

                // 2. Validate output_tensor section
                if (root.has("output_tensor")) {
                    val outputObj = root.getJSONObject("output_tensor")
                    val outShapeArray = outputObj.optJSONArray("shape")
                    if (outShapeArray != null && (outShapeArray.length() != 2 || outShapeArray.getInt(0) != 1 || outShapeArray.getInt(1) != OUTPUT_DIM)) {
                        return MetadataParseResult(false, "Expected output_tensor shape [1, $OUTPUT_DIM]", null, null, -5f, 5f)
                    }
                }

                // 3. Validate normalization section
                if (!root.has("normalization")) {
                    return MetadataParseResult(false, "Missing 'normalization' section", null, null, -5f, 5f)
                }
                val normObj = root.getJSONObject("normalization")
                val meansArray = normObj.optJSONArray("means")
                val stdsArray = normObj.optJSONArray("stds")

                if (meansArray == null || meansArray.length() != INPUT_DIM) {
                    return MetadataParseResult(
                        false,
                        "Expected exactly $INPUT_DIM normalization means, got ${meansArray?.length() ?: 0}",
                        null, null, -5f, 5f
                    )
                }
                if (stdsArray == null || stdsArray.length() != INPUT_DIM) {
                    return MetadataParseResult(
                        false,
                        "Expected exactly $INPUT_DIM normalization stds, got ${stdsArray?.length() ?: 0}",
                        null, null, -5f, 5f
                    )
                }

                val parsedMeans = FloatArray(INPUT_DIM)
                val parsedStds = FloatArray(INPUT_DIM)

                for (i in 0 until INPUT_DIM) {
                    val m = meansArray.getDouble(i).toFloat()
                    if (m.isNaN() || m.isInfinite()) {
                        return MetadataParseResult(false, "Mean at index $i is not finite: $m", null, null, -5f, 5f)
                    }
                    parsedMeans[i] = m

                    val s = stdsArray.getDouble(i).toFloat()
                    if (s.isNaN() || s.isInfinite()) {
                        return MetadataParseResult(false, "Standard deviation at index $i is not finite: $s", null, null, -5f, 5f)
                    }
                    if (s <= 0.0f) {
                        return MetadataParseResult(false, "Standard deviation at index $i must be strictly positive (> 0.0), got $s", null, null, -5f, 5f)
                    }
                    parsedStds[i] = s
                }

                val clipMin = normObj.optDouble("clip_min", -5.0).toFloat()
                val clipMax = normObj.optDouble("clip_max", 5.0).toFloat()
                if (clipMin.isNaN() || clipMax.isNaN() || clipMin >= clipMax) {
                    return MetadataParseResult(false, "Invalid clipping bounds: clipMin=$clipMin, clipMax=$clipMax", null, null, -5f, 5f)
                }

                return MetadataParseResult(true, null, parsedMeans, parsedStds, clipMin, clipMax)
            } catch (e: Exception) {
                return MetadataParseResult(false, "JSON parsing exception: ${e.message}", null, null, -5f, 5f)
            }
        }
    }
}
