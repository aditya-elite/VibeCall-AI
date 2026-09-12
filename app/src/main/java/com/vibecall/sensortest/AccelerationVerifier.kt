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
    val status: AccelerationStatus,
    val reason: String,
    val availableDevices: List<NnapiDeviceInfo>,
    val selectedDeviceName: String?,
    val selectedDeviceType: String?,
    val selectedDeviceVersion: String?,
    val selectedDeviceFeatureLevel: Long,
    val deviceSelectionForced: Boolean,
    val cpuFallbackAllowed: Boolean,
    val fullGraphSupported: Boolean,
    val compilationSucceeded: Boolean,
    val warmupInferenceCount: Int,
    val measuredInferenceCount: Int,
    val failureCount: Int,
    val meanLatencyUs: Double,
    val p50LatencyUs: Long,
    val p95LatencyUs: Long,
    val maxLatencyUs: Long,
    val outputsValid: Boolean,
    val executionPlanEvidenceFile: String = "nnapi_full_log.txt"
) {
    fun toJson(): JSONObject {
        val root = JSONObject()
        root.put("status", status.name)
        root.put("reason", reason)

        val devArray = JSONArray()
        availableDevices.forEach { dev ->
            val devObj = JSONObject()
            devObj.put("name", dev.name)
            devObj.put("type", dev.type)
            devObj.put("version", dev.version)
            devObj.put("feature_level", dev.featureLevel)
            devObj.put("is_cpu", dev.isCpu)
            devArray.put(devObj)
        }
        root.put("available_nnapi_devices", devArray)

        root.put("selected_device_name", selectedDeviceName ?: JSONObject.NULL)
        root.put("selected_device_type", selectedDeviceType ?: JSONObject.NULL)
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
        return root
    }
}

object AccelerationVerifier {
    private const val TAG = "AccelerationVerifier"
    const val WARMUP_COUNT = 10
    const val BENCHMARK_COUNT = 500

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
     * Inspects execution-plan evidence to determine if the entire graph was assigned to the accelerator.
     */
    fun checkExecutionPlanEvidence(logContent: String, acceleratorName: String): Boolean {
        if (logContent.isBlank() || acceleratorName.isBlank()) return false
        val lowerLog = logContent.lowercase()
        val lowerAcc = acceleratorName.lowercase()

        // Positive patterns documented by Android NNAPI:
        // "ModelBuilder::partitionTheWork: only one best device: <device-name>"
        // "partitionTheWork: only one best device"
        val hasSingleBestDevice = lowerLog.contains("only one best device") && lowerLog.contains(lowerAcc)
        val hasWholeGraphAssigned = lowerLog.contains("partitionthework") && lowerLog.contains(lowerAcc) && !lowerLog.contains("nnapi-reference")

        return hasSingleBestDevice || hasWholeGraphAssigned
    }

    /**
     * Executes the mandatory Step 6 verification benchmark.
     */
    fun runVerification(
        context: Context,
        candidateAcceleratorName: String? = null,
        executionPlanLogFile: File? = null
    ): AccelerationVerificationResult {
        val availableDevices = NnapiDeviceInspector.getAvailableDevices()
        Log.i(TAG, "Step 6 Verification: ${availableDevices.size} NNAPI devices discovered.")

        // 1. Identify Candidate Accelerator
        val targetDevice: NnapiDeviceInfo? = if (!candidateAcceleratorName.isNullOrBlank()) {
            availableDevices.firstOrNull { it.name == candidateAcceleratorName }
                ?: NnapiDeviceInfo(candidateAcceleratorName, "ACCELERATOR", "forced", 0L, false)
        } else {
            NnapiDeviceInspector.findBestNonCpuDevice(availableDevices)
        }

        if (targetDevice == null) {
            val isAllCpu = availableDevices.isNotEmpty() && availableDevices.all { it.isCpu || it.type == "CPU" }
            val status = if (isAllCpu) AccelerationStatus.CPU_EXECUTION else AccelerationStatus.UNAVAILABLE
            val reason = if (isAllCpu) {
                "Only CPU NNAPI devices available on this platform (${availableDevices.joinToString { it.name }}). Non-CPU acceleration unavailable."
            } else {
                "No NNAPI devices exposed by Android runtime / HAL."
            }
            return AccelerationVerificationResult(
                status = status,
                reason = reason,
                availableDevices = availableDevices,
                selectedDeviceName = null,
                selectedDeviceType = null,
                selectedDeviceVersion = null,
                selectedDeviceFeatureLevel = 0L,
                deviceSelectionForced = false,
                cpuFallbackAllowed = false,
                fullGraphSupported = false,
                compilationSucceeded = false,
                warmupInferenceCount = 0,
                measuredInferenceCount = 0,
                failureCount = 0,
                meanLatencyUs = 0.0,
                p50LatencyUs = 0L,
                p95LatencyUs = 0L,
                maxLatencyUs = 0L,
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
            return AccelerationVerificationResult(
                status = AccelerationStatus.UNAVAILABLE,
                reason = "Mandatory non-CPU acceleration failed to initialize on '${targetDevice.name}' (${targetDevice.type}): $initErr",
                availableDevices = availableDevices,
                selectedDeviceName = targetDevice.name,
                selectedDeviceType = targetDevice.type,
                selectedDeviceVersion = targetDevice.version,
                selectedDeviceFeatureLevel = targetDevice.featureLevel,
                deviceSelectionForced = true,
                cpuFallbackAllowed = false,
                fullGraphSupported = false,
                compilationSucceeded = false,
                warmupInferenceCount = 0,
                measuredInferenceCount = 0,
                failureCount = 1,
                meanLatencyUs = 0.0,
                p50LatencyUs = 0L,
                p95LatencyUs = 0L,
                maxLatencyUs = 0L,
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

        // 4. Measured Benchmark Inferences (>= 500 runs)
        val latencies = ArrayList<Long>(BENCHMARK_COUNT)
        var benchmarkFailures = 0
        var allOutputsValid = true

        val testVector = BASELINE_TEST_FEATURES.copyOf()
        for (i in 0 until BENCHMARK_COUNT) {
            // Slight synthetic perturbation to ensure dynamic computation
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
        var planVerified = false
        if (executionPlanLogFile != null && executionPlanLogFile.exists()) {
            val logText = runCatching { executionPlanLogFile.readText(Charsets.UTF_8) }.getOrDefault("")
            planVerified = checkExecutionPlanEvidence(logText, targetDevice.name)
        }

        // Final Outcome Decision per Step 6 Specification:
        // Must end in exactly one truthful state:
        // VERIFIED_NON_CPU_ACCELERATOR, CPU_EXECUTION, INCONCLUSIVE, UNAVAILABLE
        // Never convert INCONCLUSIVE into VERIFIED_NON_CPU_ACCELERATOR.
        val finalStatus: AccelerationStatus
        val finalReason: String

        if (totalFailures > 0 || !allOutputsValid) {
            finalStatus = AccelerationStatus.UNAVAILABLE
            finalReason = "Inference benchmark produced $totalFailures failures or invalid outputs out of $BENCHMARK_COUNT runs."
        } else if (targetDevice.isCpu || targetDevice.type == "CPU") {
            finalStatus = AccelerationStatus.CPU_EXECUTION
            finalReason = "Device '${targetDevice.name}' is classified as CPU."
        } else if (planVerified) {
            finalStatus = AccelerationStatus.VERIFIED_NON_CPU_ACCELERATOR
            finalReason = "Forced non-CPU accelerator '${targetDevice.name}' (${targetDevice.type}) verified: CPU fallback disabled, compilation succeeded, $BENCHMARK_COUNT real inferences succeeded (mean: ${meanLatency.roundToLong()} µs), and execution-plan logs confirm entire graph assigned."
        } else {
            // Under Android 16 / Snapdragon 8 Elite, if execution-plan logs are absent or inconclusive:
            finalStatus = AccelerationStatus.INCONCLUSIVE
            finalReason = "Forced non-CPU accelerator '${targetDevice.name}' (${targetDevice.type}) compiled with CPU fallback disabled; $BENCHMARK_COUNT inferences succeeded with zero failures (mean latency: ${meanLatency.roundToLong()} µs), but driver execution-plan logcat does not provide whole-graph accelerator assignment proof. Truthfully reported as INCONCLUSIVE."
        }

        model.close()

        return AccelerationVerificationResult(
            status = finalStatus,
            reason = finalReason,
            availableDevices = availableDevices,
            selectedDeviceName = targetDevice.name,
            selectedDeviceType = targetDevice.type,
            selectedDeviceVersion = targetDevice.version,
            selectedDeviceFeatureLevel = targetDevice.featureLevel,
            deviceSelectionForced = true,
            cpuFallbackAllowed = false,
            fullGraphSupported = model.isFullGraphSupported,
            compilationSucceeded = model.isCompilationSucceeded,
            warmupInferenceCount = WARMUP_COUNT,
            measuredInferenceCount = latencies.size,
            failureCount = totalFailures,
            meanLatencyUs = meanLatency,
            p50LatencyUs = p50Latency,
            p95LatencyUs = p95Latency,
            maxLatencyUs = maxLatency,
            outputsValid = allOutputsValid
        )
    }
}
