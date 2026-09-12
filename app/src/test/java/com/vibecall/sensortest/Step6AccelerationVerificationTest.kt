package com.vibecall.sensortest

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Step6AccelerationVerificationTest {

    @Test
    fun testNnapiReferenceIsClassifiedAsCpu() {
        val refDev = NnapiDeviceInfo(
            name = "nnapi-reference",
            type = "CPU",
            version = "1.0",
            featureLevel = 29L,
            isCpu = true
        )
        assertTrue("nnapi-reference must have isCpu = true", refDev.isCpu)
        assertEquals("CPU", refDev.type)

        val devices = listOf(refDev)
        val best = NnapiDeviceInspector.findBestNonCpuDevice(devices)
        assertEquals("findBestNonCpuDevice must never pick nnapi-reference", null, best)
    }

    @Test
    fun testGpuRemainsClassifiedAsGpu() {
        val gpuDev = NnapiDeviceInfo(
            name = "qcom-adreno-gpu",
            type = "GPU",
            version = "512.0",
            featureLevel = 31L,
            isCpu = false
        )
        assertFalse("GPU must not be classified as CPU", gpuDev.isCpu)
        assertEquals("GPU", gpuDev.type)
        assertFalse("GPU must not be relabelled as NPU", gpuDev.type == "NPU")

        val best = NnapiDeviceInspector.findBestNonCpuDevice(listOf(gpuDev))
        assertNotNull(best)
        assertEquals("GPU", best?.type)
    }

    @Test
    fun testAcceleratorDspNamesAreNotAutomaticallyRelabelledNpu() {
        val dspDev = NnapiDeviceInfo(
            name = "qti-dsp",
            type = "DSP",
            version = "v75",
            featureLevel = 31L,
            isCpu = false
        )
        assertFalse("DSP must not be classified as CPU", dspDev.isCpu)
        assertEquals("DSP must remain labelled DSP unless documented NPU", "DSP", dspDev.type)

        val accDev = NnapiDeviceInfo(
            name = "qti-accelerator",
            type = "ACCELERATOR",
            version = "v1",
            featureLevel = 31L,
            isCpu = false
        )
        assertEquals("ACCELERATOR", accDev.type)
    }

    @Test
    fun testMissingExecutionPlanEvidenceProducesInconclusive() {
        // When compilation succeeds with CPU fallback disabled and 500 inferences succeed,
        // but logcat does not contain whole-graph execution-plan proof, status must be INCONCLUSIVE.
        val emptyLog = "Some normal Android logcat output without NNAPI ExecutionPlan"
        val verified = AccelerationVerifier.checkExecutionPlanEvidence(emptyLog, "qti-dsp")
        assertFalse("Missing ExecutionPlan evidence must not verify", verified)
    }

    @Test
    fun testFullNonCpuAssignmentCanProduceVerifiedStatus() {
        // When execution plan log contains Android NNAPI proof of whole-graph assignment
        val positiveLog = "09-12 23:52:51.734 I ModelBuilder: partitionTheWork: only one best device: qti-dsp"
        val verified = AccelerationVerifier.checkExecutionPlanEvidence(positiveLog, "qti-dsp")
        assertTrue("Execution plan log showing only one best device must verify", verified)
    }

    @Test
    fun testPartialGraphSupportCannotProduceVerifiedStatus() {
        // If partitionTheWork shows fallback to nnapi-reference or partial split
        val partialLog = "09-12 23:52:51.734 I ModelBuilder: partitionTheWork: split between qti-dsp and nnapi-reference"
        val verified = AccelerationVerifier.checkExecutionPlanEvidence(partialLog, "qti-dsp")
        assertFalse("Partial delegation with nnapi-reference fallback must not verify", verified)
    }

    @Test
    fun testLatencyPercentilesCalculation() {
        // Test percentile calculations: mean, p50, p95
        val latencies = (1..100).map { it * 10L } // 10, 20, ..., 1000
        val (mean, p50, p95) = AccelerationVerifier.calculatePercentiles(latencies)

        assertEquals(505.0, mean, 0.01)
        assertEquals(510L, p50)
        assertEquals(960L, p95)
    }

    @Test
    fun testFailureProducesSafeFusionGainUnity() {
        // When model fails or is unavailable, SafeGainController must fail open to unity gain 1.0
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
        assertEquals("Unreliable model must produce unity gain 1.0 (fail-open safety)", 1.0f, targetGain, 0.0001f)
        assertTrue(reason.contains("Model guard"))
    }

    @Test
    fun testDeviceAndVerificationMetadataSerializeCorrectly() {
        val devices = listOf(
            NnapiDeviceInfo("qti-dsp", "DSP", "v75", 31L, false),
            NnapiDeviceInfo("nnapi-reference", "CPU", "1.0", 29L, true)
        )
        val result = AccelerationVerificationResult(
            status = AccelerationStatus.INCONCLUSIVE,
            reason = "Forced non-CPU accelerator qti-dsp compiled with CPU fallback disabled; 500 inferences succeeded with zero failures, but execution-plan logs absent.",
            availableDevices = devices,
            selectedDeviceName = "qti-dsp",
            selectedDeviceType = "DSP",
            selectedDeviceVersion = "v75",
            selectedDeviceFeatureLevel = 31L,
            deviceSelectionForced = true,
            cpuFallbackAllowed = false,
            fullGraphSupported = true,
            compilationSucceeded = true,
            warmupInferenceCount = 10,
            measuredInferenceCount = 500,
            failureCount = 0,
            meanLatencyUs = 78.4,
            p50LatencyUs = 75L,
            p95LatencyUs = 85L,
            maxLatencyUs = 120L,
            outputsValid = true,
            executionPlanEvidenceFile = "nnapi_full_log.txt"
        )

        val json = result.toJson()
        assertEquals("INCONCLUSIVE", json.getString("status"))
        assertEquals("qti-dsp", json.getString("selected_device_name"))
        assertEquals("DSP", json.getString("selected_device_type"))
        assertEquals("v75", json.getString("selected_device_version"))
        assertEquals(31L, json.getLong("selected_device_feature_level"))
        assertTrue(json.getBoolean("device_selection_forced"))
        assertFalse(json.getBoolean("cpu_fallback_allowed"))
        assertTrue(json.getBoolean("full_graph_supported"))
        assertTrue(json.getBoolean("compilation_succeeded"))
        assertEquals(10, json.getInt("warmup_inference_count"))
        assertEquals(500, json.getInt("measured_inference_count"))
        assertEquals(0, json.getInt("failure_count"))
        assertEquals(78L, json.getLong("mean_latency_us"))
        assertEquals(75L, json.getLong("p50_latency_us"))
        assertEquals(85L, json.getLong("p95_latency_us"))
        assertEquals(120L, json.getLong("max_latency_us"))
        assertEquals("nnapi_full_log.txt", json.getString("execution_plan_evidence_file"))

        val devsArray = json.getJSONArray("available_nnapi_devices")
        assertEquals(2, devsArray.length())
        val dev0 = devsArray.getJSONObject(0)
        assertEquals("qti-dsp", dev0.getString("name"))
        assertEquals("DSP", dev0.getString("type"))
        assertFalse(dev0.getBoolean("is_cpu"))

        val dev1 = devsArray.getJSONObject(1)
        assertEquals("nnapi-reference", dev1.getString("name"))
        assertEquals("CPU", dev1.getString("type"))
        assertTrue(dev1.getBoolean("is_cpu"))
    }
}
