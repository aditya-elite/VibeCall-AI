package com.vibecall.sensortest

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToLong

enum class AccelerationStatus {
    VERIFIED_NON_CPU_ACCELERATOR,
    CPU_EXECUTION,
    INCONCLUSIVE,
    UNAVAILABLE
}

data class AccelerationVerificationResult(
    // 13 Required Truthful Telemetry Fields
    val requestedBackend: String = "MANDATORY_NPU",
    val actualBackend: String,
    val selectedAcceleratorName: String?,
    val selectedDeviceType: String?,
    val delegatedOperationCount: Int,
    val totalModelOperationCount: Int = 4,
    val inferenceCount: Int,
    val averageLatencyUs: Double,
    val medianLatencyUs: Long,
    val highPercentileLatencyUs: Long,
    val cpuFallbackDetected: Boolean,
    val mandatoryNpuSatisfied: Boolean,
    val failureReason: String?,

    // Status and Detailed Diagnostics
    val status: AccelerationStatus,
    val reason: String,
    val availableDevices: List<NnapiDeviceInfo>,
    val selectedDeviceVersion: String? = null,
    val selectedDeviceFeatureLevel: Long = 0L,
    val deviceSelectionForced: Boolean = true,
    val cpuFallbackAllowed: Boolean = false,
    val fullGraphSupported: Boolean = false,
    val compilationSucceeded: Boolean = false,
    val warmupInferenceCount: Int = 0,
    val measuredInferenceCount: Int = 0,
    val failureCount: Int = 0,
    val meanLatencyUs: Double = averageLatencyUs,
    val p50LatencyUs: Long = medianLatencyUs,
    val p95LatencyUs: Long = highPercentileLatencyUs,
    val maxLatencyUs: Long = 0L,
    val outputsValid: Boolean = true,
    val executionPlanEvidenceFile: String = "nnapi_full_log.txt"
) {
    val selectedDeviceName: String? get() = selectedAcceleratorName

    fun toJson(): JSONObject {
        val root = JSONObject()

        // 13 Required Truthful Telemetry Keys
        root.put("requested_backend", requestedBackend)
        root.put("actual_backend", actualBackend)
        root.put("selected_accelerator_name", selectedAcceleratorName ?: JSONObject.NULL)
        root.put("selected_device_type", selectedDeviceType ?: JSONObject.NULL)
        root.put("delegated_operation_count", delegatedOperationCount)
        root.put("total_model_operation_count", totalModelOperationCount)
        root.put("inference_count", inferenceCount)
        root.put("average_latency_us", if (averageLatencyUs.isNaN()) 0.0 else (averageLatencyUs * 100).roundToLong() / 100.0)
        root.put("median_latency_us", medianLatencyUs)
        root.put("high_percentile_latency_us", highPercentileLatencyUs)
        root.put("cpu_fallback_detected", cpuFallbackDetected)
        root.put("mandatory_npu_satisfied", mandatoryNpuSatisfied)
        root.put("failure_reason", failureReason ?: JSONObject.NULL)

        // General status & backward compatibility keys
        root.put("status", status.name)
        root.put("reason", reason)
        root.put("selected_device_version", selectedDeviceVersion ?: JSONObject.NULL)
        root.put("selected_device_feature_level", selectedDeviceFeatureLevel)
        root.put("device_selection_forced", deviceSelectionForced)
        root.put("cpu_fallback_allowed", cpuFallbackAllowed)
        root.put("full_graph_supported", fullGraphSupported)
        root.put("compilation_succeeded", compilationSucceeded)
        root.put("warmup_inference_count", warmupInferenceCount)
        root.put("measured_inference_count", measuredInferenceCount)
        root.put("failure_count", failureCount)
        root.put("mean_latency_us", if (meanLatencyUs.isNaN()) 0L else meanLatencyUs.roundToLong())
        root.put("p50_latency_us", p50LatencyUs)
        root.put("p95_latency_us", p95LatencyUs)
        root.put("max_latency_us", maxLatencyUs)
        root.put("execution_plan_evidence_file", executionPlanEvidenceFile)

        val devArray = JSONArray()
        availableDevices.forEach { dev ->
            val devObj = JSONObject()
            devObj.put("name", dev.name)
            devObj.put("type", dev.type)
            devObj.put("version", dev.version)
            devObj.put("feature_level", dev.featureLevel)
            devObj.put("is_cpu", dev.isCpu)
            devObj.put("is_selected", dev.isSelected)
            devArray.put(devObj)
        }
        root.put("available_nnapi_devices", devArray)

        return root
    }
}

data class LogcatExecutionInspection(
    val cpuFallbackDetected: Boolean,
    val delegatedOperationCount: Int,
    val totalModelOperationCount: Int,
    val isWholeGraphOnAccelerator: Boolean,
    val evidenceSummary: String
)

object AccelerationVerifier {
    private const val TAG = "AccelerationVerifier"
    const val WARMUP_COUNT = 10
    const val BENCHMARK_COUNT = 500
    const val TOTAL_MODEL_OPERATIONS = 4

    // Calibrated baseline 16-element feature vector representing active voiced speech on cheek
    val BASELINE_TEST_FEATURES = floatArrayOf(
        -35.0f,  // [0] microphone_log_energy_db
        0.85f,   // [1] microphone_pitch_strength
        1.0f,    // [2] microphone_pitch_reliable
        -18.0f,  // [3] log_accel_peak_power
        0.65f,   // [4] accel_peak_prominence
        1.2f,    // [5] pitch_difference_hz
        0.88f,   // [6] pitch_agreement_score
        1.0f,    // [7] pitch_agreement_reliable
        0.05f,   // [8] phone_motion_level
        0.95f,   // [9] sensor_reliability
        3.0f,    // [10] sensor_alignment_lag_ms
        -36.5f,  // [11] prev_microphone_log_energy_db
        0.82f,   // [12] prev_microphone_pitch_strength
        0.85f,   // [13] prev_pitch_agreement_score
        0.04f,   // [14] prev_phone_motion_level
        0.95f    // [15] prev_sensor_reliability
    )

    fun calculatePercentiles(latencies: List<Long>): Triple<Double, Long, Long> {
        if (latencies.isEmpty()) return Triple(0.0, 0L, 0L)
        val sorted = latencies.sorted()
        val mean = latencies.average()
        val p50 = sorted[(sorted.size * 0.50).toInt().coerceAtMost(sorted.size - 1)]
        val p95 = sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)]
        return Triple(mean, p50, p95)
    }

    /**
     * Inspects execution-plan evidence in logcat.
     * Detects whether TfLiteXNNPackDelegate substituted operations (CPU fallback)
     * and whether the entire graph was assigned to the accelerator.
     */
    fun inspectExecutionPlanEvidence(logContent: String, acceleratorName: String?): LogcatExecutionInspection {
        if (logContent.isBlank()) {
            return LogcatExecutionInspection(
                cpuFallbackDetected = false,
                delegatedOperationCount = 0,
                totalModelOperationCount = TOTAL_MODEL_OPERATIONS,
                isWholeGraphOnAccelerator = false,
                evidenceSummary = "Logcat content is empty or unavailable"
            )
        }
        val lowerLog = logContent.lowercase()
        val isXnnpack4Of4 = lowerLog.contains("replacing 4 out of 4 node(s) with delegate (tflitexnnpackdelegate)")
        val hasXnnpack = lowerLog.contains("tflitexnnpackdelegate") || lowerLog.contains("xnnpack")

        val isWholeGraph = if (!acceleratorName.isNullOrBlank()) {
            val lowerAcc = acceleratorName.lowercase()
            (lowerLog.contains("only one best device") && lowerLog.contains(lowerAcc)) ||
                (lowerLog.contains("partitionthework") && lowerLog.contains(lowerAcc) && !lowerLog.contains("nnapi-reference")) ||
                (lowerLog.contains("replacing 4 out of 4 node(s) with delegate") && lowerLog.contains(lowerAcc))
        } else false

        val delegatedOps = if (isWholeGraph) TOTAL_MODEL_OPERATIONS else 0

        val summary = when {
            isXnnpack4Of4 -> "Current evidence shows 4/4 operations assigned to TfLiteXNNPackDelegate (CPU)."
            hasXnnpack -> "CPU fallback detected: operations assigned to TfLiteXNNPackDelegate."
            isWholeGraph -> "All $TOTAL_MODEL_OPERATIONS operations assigned to accelerator '$acceleratorName'."
            else -> "No accelerator execution-plan assignment evidence found."
        }

        return LogcatExecutionInspection(
            cpuFallbackDetected = hasXnnpack || isXnnpack4Of4,
            delegatedOperationCount = delegatedOps,
            totalModelOperationCount = TOTAL_MODEL_OPERATIONS,
            isWholeGraphOnAccelerator = isWholeGraph,
            evidenceSummary = summary
        )
    }

    /**
     * Backward-compatible helper for existing unit tests.
     */
    fun checkExecutionPlanEvidence(logContent: String, acceleratorName: String): Boolean {
        return inspectExecutionPlanEvidence(logContent, acceleratorName).isWholeGraphOnAccelerator
    }

    /**
     * Executes the mandatory Step 6 verification benchmark.
     */
    fun runVerification(
        context: Context,
        candidateAcceleratorName: String? = null,
        executionPlanLogFile: File? = null
    ): AccelerationVerificationResult {
        val rawDevices = NnapiDeviceInspector.getAvailableDevices()
        Log.i(TAG, "Step 6 Verification: ${rawDevices.size} NNAPI devices discovered via NDK.")

        // 1. Identify Candidate Accelerator
        val targetDevice: NnapiDeviceInfo? = if (!candidateAcceleratorName.isNullOrBlank()) {
            rawDevices.firstOrNull { it.name == candidateAcceleratorName }
                ?: NnapiDeviceInfo(candidateAcceleratorName, "ACCELERATOR", "forced", 0L, false)
        } else {
            NnapiDeviceInspector.searchQualcommNonCpuAccelerator(rawDevices)
        }

        val availableDevices = NnapiDeviceInspector.logAndTagDevices(rawDevices, targetDevice?.name)

        if (targetDevice == null) {
            val isAllCpu = availableDevices.isNotEmpty() && availableDevices.all { it.isCpu || it.type == "CPU" }
            val status = if (isAllCpu) AccelerationStatus.CPU_EXECUTION else AccelerationStatus.UNAVAILABLE
            val devListStr = if (availableDevices.isEmpty()) "none" else availableDevices.joinToString { "${it.name} (${it.type})" }
            val failureReason = if (isAllCpu) {
                "No compatible non-CPU Qualcomm accelerator found. Only CPU NNAPI device available ($devListStr). XNNPACK / CPU execution prohibited in mandatory-NPU mode."
            } else {
                "No NNAPI devices exposed by Android runtime / HAL."
            }

            Log.w(TAG, "Mandatory NPU verification: $failureReason. Backend reported as 'NPU unavailable'.")

            return AccelerationVerificationResult(
                requestedBackend = "MANDATORY_NPU",
                actualBackend = "NPU unavailable",
                selectedAcceleratorName = null,
                selectedDeviceType = null,
                delegatedOperationCount = 0,
                totalModelOperationCount = TOTAL_MODEL_OPERATIONS,
                inferenceCount = 0,
                averageLatencyUs = 0.0,
                medianLatencyUs = 0L,
                highPercentileLatencyUs = 0L,
                maxLatencyUs = 0L,
                cpuFallbackDetected = true,
                mandatoryNpuSatisfied = false,
                failureReason = failureReason,
                status = status,
                reason = failureReason,
                availableDevices = availableDevices,
                selectedDeviceVersion = null,
                selectedDeviceFeatureLevel = 0L,
                deviceSelectionForced = false,
                cpuFallbackAllowed = false,
                fullGraphSupported = false,
                compilationSucceeded = false,
                warmupInferenceCount = 0,
                measuredInferenceCount = 0,
                failureCount = 0,
                outputsValid = false
            )
        }

        // 2. Instantiate FusionConfidenceModel with mandatory hardware mode
        val model = FusionConfidenceModel(
            context = context,
            requireNonCpuAcceleration = true,
            forcedAcceleratorName = targetDevice.name,
            allowCpuFallback = false
        )

        if (!model.isModelReady()) {
            val initErr = model.initializationError ?: "Model failed to initialize"
            model.close()

            val failureReason = "Mandatory non-CPU acceleration failed on '${targetDevice.name}' (${targetDevice.type}): $initErr"
            Log.w(TAG, failureReason)

            return AccelerationVerificationResult(
                requestedBackend = "MANDATORY_NPU",
                actualBackend = "NPU unavailable",
                selectedAcceleratorName = targetDevice.name,
                selectedDeviceType = targetDevice.type,
                delegatedOperationCount = 0,
                totalModelOperationCount = TOTAL_MODEL_OPERATIONS,
                inferenceCount = 0,
                averageLatencyUs = 0.0,
                medianLatencyUs = 0L,
                highPercentileLatencyUs = 0L,
                maxLatencyUs = 0L,
                cpuFallbackDetected = true,
                mandatoryNpuSatisfied = false,
                failureReason = failureReason,
                status = AccelerationStatus.UNAVAILABLE,
                reason = failureReason,
                availableDevices = availableDevices,
                selectedDeviceVersion = targetDevice.version,
                selectedDeviceFeatureLevel = targetDevice.featureLevel,
                deviceSelectionForced = true,
                cpuFallbackAllowed = false,
                fullGraphSupported = false,
                compilationSucceeded = false,
                warmupInferenceCount = 0,
                measuredInferenceCount = 0,
                failureCount = 1,
                outputsValid = false
            )
        }

        // 3. Warm-up Inferences (10 runs)
        var warmupFailures = 0
        for (i in 0 until WARMUP_COUNT) {
            val res = model.infer(BASELINE_TEST_FEATURES)
            if (!res.modelReliable || res.error != null) {
                warmupFailures++
            }
        }

        // 4. Measured Benchmark Inferences (500 runs)
        val latencies = ArrayList<Long>(BENCHMARK_COUNT)
        var benchmarkFailures = 0
        var allOutputsValid = true

        val testVector = BASELINE_TEST_FEATURES.copyOf()
        for (i in 0 until BENCHMARK_COUNT) {
            testVector[0] = BASELINE_TEST_FEATURES[0] + (i % 7 - 3) * 0.5f
            testVector[6] = (BASELINE_TEST_FEATURES[6] + (i % 5 - 2) * 0.02f).coerceIn(0f, 1f)

            val res = model.infer(testVector)
            if (!res.modelReliable || res.error != null) {
                benchmarkFailures++
            } else {
                latencies.add(res.inferenceLatencyUs)
                if (res.confidence < 0.0f || res.confidence > 1.0f || res.confidence.isNaN()) {
                    allOutputsValid = false
                }
            }
        }

        val totalFailures = warmupFailures + benchmarkFailures
        val (meanLatency, p50Latency, p95Latency) = calculatePercentiles(latencies)
        val maxLatency = latencies.maxOrNull() ?: 0L

        // 5. Evaluate Execution-Plan Evidence
        var planInspection = LogcatExecutionInspection(
            cpuFallbackDetected = false,
            delegatedOperationCount = 0,
            totalModelOperationCount = TOTAL_MODEL_OPERATIONS,
            isWholeGraphOnAccelerator = false,
            evidenceSummary = "Log file not provided"
        )
        if (executionPlanLogFile != null && executionPlanLogFile.exists()) {
            val logText = runCatching { executionPlanLogFile.readText(Charsets.UTF_8) }.getOrDefault("")
            planInspection = inspectExecutionPlanEvidence(logText, targetDevice.name)
        }

        // Final Outcome Decision
        val finalStatus: AccelerationStatus
        val finalReason: String
        val actualBackend: String
        val cpuFallbackDetected: Boolean
        val mandatoryNpuSatisfied: Boolean
        val failureReason: String?
        val delegatedOps: Int

        if (totalFailures > 0 || !allOutputsValid) {
            finalStatus = AccelerationStatus.UNAVAILABLE
            failureReason = "Inference benchmark produced $totalFailures failures or invalid outputs out of $BENCHMARK_COUNT runs."
            finalReason = failureReason
            actualBackend = "NPU unavailable"
            cpuFallbackDetected = true
            mandatoryNpuSatisfied = false
            delegatedOps = 0
        } else if (targetDevice.isCpu || targetDevice.type == "CPU") {
            finalStatus = AccelerationStatus.CPU_EXECUTION
            failureReason = "Device '${targetDevice.name}' is classified as CPU."
            finalReason = failureReason
            actualBackend = "CPU (${targetDevice.name})"
            cpuFallbackDetected = true
            mandatoryNpuSatisfied = false
            delegatedOps = 0
        } else if (planInspection.cpuFallbackDetected && !planInspection.isWholeGraphOnAccelerator) {
            finalStatus = AccelerationStatus.CPU_EXECUTION
            failureReason = "Logcat evidence shows operations assigned to TfLiteXNNPackDelegate (CPU fallback)."
            finalReason = failureReason
            actualBackend = "TfLiteXNNPackDelegate (CPU)"
            cpuFallbackDetected = true
            mandatoryNpuSatisfied = false
            delegatedOps = planInspection.delegatedOperationCount
        } else if (planInspection.isWholeGraphOnAccelerator) {
            finalStatus = AccelerationStatus.VERIFIED_NON_CPU_ACCELERATOR
            finalReason = "Forced non-CPU accelerator '${targetDevice.name}' (${targetDevice.type}) verified: CPU fallback disabled, compilation succeeded, $BENCHMARK_COUNT real inferences succeeded (mean: ${meanLatency.roundToLong()} µs), and execution-plan logs confirm all $TOTAL_MODEL_OPERATIONS operations assigned."
            actualBackend = targetDevice.name
            cpuFallbackDetected = false
            mandatoryNpuSatisfied = true
            failureReason = null
            delegatedOps = TOTAL_MODEL_OPERATIONS
        } else {
            // Android 16 platform without direct whole-graph assignment logs
            finalStatus = AccelerationStatus.INCONCLUSIVE
            failureReason = "Accelerator '${targetDevice.name}' compiled with CPU fallback disabled; $BENCHMARK_COUNT inferences succeeded with zero failures, but driver execution-plan logcat does not provide whole-graph accelerator assignment proof."
            finalReason = failureReason
            actualBackend = targetDevice.name
            cpuFallbackDetected = false
            mandatoryNpuSatisfied = false
            delegatedOps = 0
        }

        model.close()

        return AccelerationVerificationResult(
            requestedBackend = "MANDATORY_NPU",
            actualBackend = actualBackend,
            selectedAcceleratorName = targetDevice.name,
            selectedDeviceType = targetDevice.type,
            delegatedOperationCount = delegatedOps,
            totalModelOperationCount = TOTAL_MODEL_OPERATIONS,
            inferenceCount = latencies.size,
            averageLatencyUs = meanLatency,
            medianLatencyUs = p50Latency,
            highPercentileLatencyUs = p95Latency,
            maxLatencyUs = maxLatency,
            cpuFallbackDetected = cpuFallbackDetected,
            mandatoryNpuSatisfied = mandatoryNpuSatisfied,
            failureReason = failureReason,
            status = finalStatus,
            reason = finalReason,
            availableDevices = availableDevices,
            selectedDeviceVersion = targetDevice.version,
            selectedDeviceFeatureLevel = targetDevice.featureLevel,
            deviceSelectionForced = true,
            cpuFallbackAllowed = false,
            fullGraphSupported = model.isFullGraphSupported,
            compilationSucceeded = model.isCompilationSucceeded,
            warmupInferenceCount = WARMUP_COUNT,
            measuredInferenceCount = latencies.size,
            failureCount = totalFailures,
            outputsValid = allOutputsValid,
            executionPlanEvidenceFile = executionPlanLogFile?.name ?: "nnapi_full_log.txt"
        )
    }
}
