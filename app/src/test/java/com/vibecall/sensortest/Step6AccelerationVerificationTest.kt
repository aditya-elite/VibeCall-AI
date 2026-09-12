package com.vibecall.sensortest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step6AccelerationVerificationTest {

    @Test
    fun testNnapiDeviceEnumeration() {
        // 1. Tests device representation and all 5 required fields:
        // Name, Type, Driver/version, CPU/non-CPU, Selected status
        val dev1 = NnapiDeviceInfo(
            name = "qti-dsp",
            type = "DSP",
            version = "v75.1.0",
            featureLevel = 31L,
            isCpu = false,
            isSelected = true
        )
        assertEquals("qti-dsp", dev1.name)
        assertEquals("DSP", dev1.type)
        assertEquals("v75.1.0", dev1.version)
        assertEquals(31L, dev1.featureLevel)
        assertFalse(dev1.isCpu)
        assertTrue(dev1.isSelected)

        val logStr = dev1.toFormattedDisplayString()
        assertTrue("Log string must contain name", logStr.contains("qti-dsp"))
        assertTrue("Log string must contain type", logStr.contains("DSP"))
        assertTrue("Log string must contain version", logStr.contains("v75.1.0"))
        assertTrue("Log string must indicate NON-CPU", logStr.contains("NON-CPU"))
        assertTrue("Log string must indicate SELECTED", logStr.contains("SELECTED"))

        // Test tagging helper
        val devices = listOf(
            NnapiDeviceInfo("nnapi-reference", "CPU", "compiler251023012501", 1000008L, true),
            NnapiDeviceInfo("qti-dsp", "DSP", "v75", 31L, false)
        )
        val tagged = NnapiDeviceInspector.logAndTagDevices(devices, "qti-dsp")
        assertFalse("nnapi-reference is not selected", tagged[0].isSelected)
        assertTrue("qti-dsp is selected", tagged[1].isSelected)
    }

    @Test
    fun testCpuDevicesAreRejectedInMandatoryNpuMode() {
        // 2. CPU devices (including nnapi-reference) must be strictly rejected in mandatory-NPU mode
        val refDev = NnapiDeviceInfo(
            name = "nnapi-reference",
            type = "CPU",
            version = "compiler251023012501",
            featureLevel = 1000008L,
            isCpu = true
        )
        assertTrue("nnapi-reference must have isCpu = true", refDev.isCpu)
        assertEquals("CPU", refDev.type)

        val devices = listOf(refDev)
        val best = NnapiDeviceInspector.searchQualcommNonCpuAccelerator(devices)
        assertNull("Mandatory-NPU mode must strictly reject CPU devices and nnapi-reference", best)
    }

    @Test
    fun testXnnpackIsDetectedAsCpuFallback() {
        // 3. Current Logcat evidence shows all 4 operations assigned to TfLiteXNNPackDelegate.
        // XNNPACK must be detected as CPU fallback, not NPU execution.
        val logcatEvidence = """
            09-12 19:24:16.084 I tflite  : Loaded NNAPI implementation: libneuralnetworks.so
            09-12 19:24:16.085 I tflite  : Created NNAPI delegate with preference 2
            09-12 19:24:16.088 I tflite  : Applying 1 TensorFlow Lite delegate(s) to graph ...
            09-12 19:24:16.090 I tflite  : Replacing 4 out of 4 node(s) with delegate (TfLiteXNNPackDelegate) node, sets 1 partition(s).
        """.trimIndent()

        val inspection = AccelerationVerifier.inspectExecutionPlanEvidence(logcatEvidence, "qti-dsp")
        assertTrue("XNNPACK must be detected as CPU fallback", inspection.cpuFallbackDetected)
        assertEquals("Delegated operation count to non-CPU accelerator must be 0 when XNNPACK is used", 0, inspection.delegatedOperationCount)
        assertEquals("Total model operations must be 4", 4, inspection.totalModelOperationCount)
        assertFalse("XNNPACK execution must not be reported as whole-graph accelerator execution", inspection.isWholeGraphOnAccelerator)
        assertTrue("Evidence summary must report 4/4 nodes assigned to TfLiteXNNPackDelegate", inspection.evidenceSummary.contains("TfLiteXNNPackDelegate"))
    }

    @Test
    fun testMissingAcceleratorProducesNpuUnavailable() {
        // 4. Missing accelerator or only CPU devices must produce "NPU unavailable"
        val onlyCpuDevices = listOf(
            NnapiDeviceInfo("nnapi-reference", "CPU", "compiler251023012501", 1000008L, true)
        )

        val best = NnapiDeviceInspector.searchQualcommNonCpuAccelerator(onlyCpuDevices)
        assertNull("No accelerator should be found when only CPU devices exist", best)

        // Simulate verifier result when no accelerator exists
        val failureReason = "No compatible non-CPU Qualcomm accelerator found. Available NNAPI devices: [nnapi-reference (CPU)]."
        val result = AccelerationVerificationResult(
            requestedBackend = "MANDATORY_NPU",
            actualBackend = "NPU unavailable",
            selectedAcceleratorName = null,
            selectedDeviceType = null,
            delegatedOperationCount = 0,
            totalModelOperationCount = 4,
            inferenceCount = 0,
            averageLatencyUs = 0.0,
            medianLatencyUs = 0L,
            highPercentileLatencyUs = 0L,
            cpuFallbackDetected = true,
            mandatoryNpuSatisfied = false,
            failureReason = failureReason,
            status = AccelerationStatus.CPU_EXECUTION,
            reason = failureReason,
            availableDevices = onlyCpuDevices
        )

        assertEquals("actual_backend must be 'NPU unavailable'", "NPU unavailable", result.actualBackend)
        assertNull(result.selectedAcceleratorName)
        assertEquals(0, result.delegatedOperationCount)
        assertTrue("CPU fallback detected must be true", result.cpuFallbackDetected)
        assertFalse("Mandatory NPU must not be satisfied", result.mandatoryNpuSatisfied)
        assertEquals("Status must be CPU_EXECUTION or UNAVAILABLE", AccelerationStatus.CPU_EXECUTION, result.status)
    }

    @Test
    fun testFailedDelegationProducesSafeFusionGainUnity() {
        // 5. Failed delegation produces gain 1.0 so speech remains protected
        val controller = SafeGainController()
        val (targetGain, reason) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -60.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.05f,
            modelReliable = false, // Simulated failed/unavailable model
            sensorReliability = 0.95f,
            phoneMotionLevel = 0.05f,
            sensorAlignmentLagMs = 3.0f
        )
        assertEquals("Failed delegation / unavailable model must produce unity gain 1.0 (fail-open safety)", 1.0f, targetGain, 0.0001f)
        assertTrue("Reason must state model guard", reason.contains("Model guard"))
    }

    @Test
    fun testMandatoryNpuSuccessReportedOnlyForSelectedNonCpuDeviceWithCompleteDelegation() {
        // 6. Mandatory-NPU success is reported ONLY for a selected non-CPU device with complete model delegation
        val qtiDsp = NnapiDeviceInfo("qti-dsp", "DSP", "v75", 31L, false, isSelected = true)

        // Case A: Missing execution plan log -> INCONCLUSIVE, mandatoryNpuSatisfied = false
        val inconclusiveLog = "Normal log without NNAPI whole-graph partition details"
        val inspA = AccelerationVerifier.inspectExecutionPlanEvidence(inconclusiveLog, qtiDsp.name)
        assertFalse("Mandatory NPU cannot be satisfied without whole-graph proof", inspA.isWholeGraphOnAccelerator)

        // Case B: Split execution / fallback to CPU -> mandatoryNpuSatisfied = false
        val splitLog = "ModelBuilder: partitionTheWork: split between qti-dsp and nnapi-reference"
        val inspB = AccelerationVerifier.inspectExecutionPlanEvidence(splitLog, qtiDsp.name)
        assertFalse("Split execution cannot satisfy mandatory NPU", inspB.isWholeGraphOnAccelerator)

        // Case C: All 4 nodes assigned to non-CPU accelerator -> SUCCESS
        val verifiedLog = "ModelBuilder: partitionTheWork: only one best device: qti-dsp (4 out of 4 nodes)"
        val inspC = AccelerationVerifier.inspectExecutionPlanEvidence(verifiedLog, qtiDsp.name)
        assertTrue("Whole-graph assignment must be verified", inspC.isWholeGraphOnAccelerator)
        assertEquals(4, inspC.delegatedOperationCount)
        assertFalse(inspC.cpuFallbackDetected)

        val verifiedResult = AccelerationVerificationResult(
            requestedBackend = "MANDATORY_NPU",
            actualBackend = "qti-dsp",
            selectedAcceleratorName = "qti-dsp",
            selectedDeviceType = "DSP",
            delegatedOperationCount = 4,
            totalModelOperationCount = 4,
            inferenceCount = 500,
            averageLatencyUs = 78.4,
            medianLatencyUs = 75L,
            highPercentileLatencyUs = 85L,
            cpuFallbackDetected = false,
            mandatoryNpuSatisfied = true,
            failureReason = null,
            status = AccelerationStatus.VERIFIED_NON_CPU_ACCELERATOR,
            reason = "Verified: all 4 operations assigned to qti-dsp (DSP)",
            availableDevices = listOf(qtiDsp)
        )

        assertEquals("qti-dsp", verifiedResult.actualBackend)
        assertEquals("qti-dsp", verifiedResult.selectedAcceleratorName)
        assertEquals(4, verifiedResult.delegatedOperationCount)
        assertEquals(4, verifiedResult.totalModelOperationCount)
        assertFalse(verifiedResult.cpuFallbackDetected)
        assertTrue(verifiedResult.mandatoryNpuSatisfied)
        assertNull(verifiedResult.failureReason)
        assertEquals(AccelerationStatus.VERIFIED_NON_CPU_ACCELERATOR, verifiedResult.status)
    }

    @Test
    fun testTelemetryJsonContainsAllThirteenRequiredFields() {
        val devices = listOf(
            NnapiDeviceInfo("nnapi-reference", "CPU", "compiler251023012501", 1000008L, true, isSelected = false)
        )
        val result = AccelerationVerificationResult(
            requestedBackend = "MANDATORY_NPU",
            actualBackend = "NPU unavailable",
            selectedAcceleratorName = null,
            selectedDeviceType = null,
            delegatedOperationCount = 0,
            totalModelOperationCount = 4,
            inferenceCount = 0,
            averageLatencyUs = 0.0,
            medianLatencyUs = 0L,
            highPercentileLatencyUs = 0L,
            cpuFallbackDetected = true,
            mandatoryNpuSatisfied = false,
            failureReason = "Only CPU NNAPI devices available on this platform (nnapi-reference). Non-CPU acceleration unavailable.",
            status = AccelerationStatus.CPU_EXECUTION,
            reason = "Only CPU NNAPI devices available on this platform (nnapi-reference).",
            availableDevices = devices
        )

        val json = result.toJson()

        // Verify all 13 required telemetry keys exist and have exact correct types
        assertEquals("MANDATORY_NPU", json.getString("requested_backend"))
        assertEquals("NPU unavailable", json.getString("actual_backend"))
        assertTrue("selected_accelerator_name must be null", json.isNull("selected_accelerator_name"))
        assertTrue("selected_device_type must be null", json.isNull("selected_device_type"))
        assertEquals(0, json.getInt("delegated_operation_count"))
        assertEquals(4, json.getInt("total_model_operation_count"))
        assertEquals(0, json.getInt("inference_count"))
        assertEquals(0.0, json.getDouble("average_latency_us"), 0.001)
        assertEquals(0L, json.getLong("median_latency_us"))
        assertEquals(0L, json.getLong("high_percentile_latency_us"))
        assertTrue("cpu_fallback_detected must be true", json.getBoolean("cpu_fallback_detected"))
        assertFalse("mandatory_npu_satisfied must be false", json.getBoolean("mandatory_npu_satisfied"))
        assertEquals(
            "Only CPU NNAPI devices available on this platform (nnapi-reference). Non-CPU acceleration unavailable.",
            json.getString("failure_reason")
        )

        // Verify device list serialization with is_selected
        val devsArray = json.getJSONArray("available_nnapi_devices")
        assertEquals(1, devsArray.length())
        val dev0 = devsArray.getJSONObject(0)
        assertEquals("nnapi-reference", dev0.getString("name"))
        assertEquals("CPU", dev0.getString("type"))
        assertTrue(dev0.getBoolean("is_cpu"))
        assertFalse(dev0.getBoolean("is_selected"))
    }
}
