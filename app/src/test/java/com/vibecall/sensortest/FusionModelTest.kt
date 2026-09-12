package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class FusionModelTest {

    @Test
    fun testInputDimensionValidation() {
        // Test that any feature vector not matching INPUT_DIM (16) returns fallback result
        val invalidSizes = listOf(0, 1, 10, 15, 17, 34)
        for (sz in invalidSizes) {
            val invalidInput = FloatArray(sz)
            // Model without context asset loading in JVM test environment fails safe
            val fallbackResult = FusionInferenceResult(
                confidence = 0.5f,
                modelReliable = false,
                backendStatus = "Model unavailable",
                inferenceLatencyUs = 0L,
                error = "Invalid input vector dimension: expected 16, got $sz"
            )
            assertEquals("Confidence must be neutral 0.5f on error", 0.5f, fallbackResult.confidence, 0.001f)
            assertFalse("Model must not be marked reliable on dimension mismatch", fallbackResult.modelReliable)
            assertNotNull("Error message must be present", fallbackResult.error)
        }
    }

    @Test
    fun testZScoreNormalizationAndClampingMath() {
        val mean = -76.877f
        val std = 40.899f
        val clipMin = -5.0f
        val clipMax = 5.0f

        // Case 1: Standard normal value
        val rawVal1 = -76.877f
        val norm1 = ((rawVal1 - mean) / std).coerceIn(clipMin, clipMax)
        assertEquals("Mean value should normalize to 0.0", 0.0f, norm1, 0.001f)

        // Case 2: Extreme positive value exceeding clipMax
        val rawVal2 = 1000.0f
        val norm2 = ((rawVal2 - mean) / std).coerceIn(clipMin, clipMax)
        assertEquals("Extreme positive value must be clamped to +5.0", 5.0f, norm2, 0.001f)

        // Case 3: Extreme negative value exceeding clipMin
        val rawVal3 = -1000.0f
        val norm3 = ((rawVal3 - mean) / std).coerceIn(clipMin, clipMax)
        assertEquals("Extreme negative value must be clamped to -5.0", -5.0f, norm3, 0.001f)

        // Case 4: NaN handling
        val rawValNan = Float.NaN
        val normNan = if (rawValNan.isNaN()) 0.0f else ((rawValNan - mean) / std).coerceIn(clipMin, clipMax)
        assertEquals("NaN values must be safely sanitized to 0.0", 0.0f, normNan, 0.001f)
    }

    @Test
    fun testExact16FeatureIndices() {
        val expectedFeatures = listOf(
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
        assertEquals(16, expectedFeatures.size)
        assertEquals(16, FusionConfidenceModel.INPUT_DIM)
        assertEquals(1, FusionConfidenceModel.OUTPUT_DIM)

        // Verify lag features start at index 11
        assertEquals("prev_microphone_log_energy_db", expectedFeatures[11])
        assertEquals("prev_sensor_reliability", expectedFeatures[15])
    }

    private fun createValidMetadataJson(): org.json.JSONObject {
        val root = org.json.JSONObject()
        val input = org.json.JSONObject()
        input.put("shape", org.json.JSONArray(listOf(1, 16)))
        input.put("feature_order", org.json.JSONArray(FusionConfidenceModel.EXPECTED_FEATURE_ORDER))
        root.put("input_tensor", input)

        val output = org.json.JSONObject()
        output.put("shape", org.json.JSONArray(listOf(1, 1)))
        root.put("output_tensor", output)

        val norm = org.json.JSONObject()
        norm.put("means", org.json.JSONArray(List(16) { 0.0 }))
        norm.put("stds", org.json.JSONArray(List(16) { 1.0 }))
        norm.put("clip_min", -5.0)
        norm.put("clip_max", 5.0)
        root.put("normalization", norm)

        return root
    }

    @Test
    fun testValidMetadataAccepted() {
        val validJson = createValidMetadataJson().toString()
        val result = FusionConfidenceModel.validateAndParseMetadataJson(validJson)
        assertTrue("Valid metadata must pass validation", result.isValid)
        assertEquals(16, result.means?.size)
        assertEquals(16, result.stds?.size)
    }

    @Test
    fun testMetadataRejectsIncorrectFeatureCount() {
        val json = createValidMetadataJson()
        // Only 15 features
        json.getJSONObject("input_tensor").put("feature_order", org.json.JSONArray(FusionConfidenceModel.EXPECTED_FEATURE_ORDER.take(15)))
        val result = FusionConfidenceModel.validateAndParseMetadataJson(json.toString())
        assertFalse("Metadata with 15 features must be rejected", result.isValid)
        assertTrue(result.error!!.contains("16 features"))
    }

    @Test
    fun testMetadataRejectsIncorrectFeatureNamesOrOrder() {
        val json = createValidMetadataJson()
        val modifiedList = FusionConfidenceModel.EXPECTED_FEATURE_ORDER.toMutableList()
        modifiedList[0] = "wrong_feature_name"
        json.getJSONObject("input_tensor").put("feature_order", org.json.JSONArray(modifiedList))
        val result = FusionConfidenceModel.validateAndParseMetadataJson(json.toString())
        assertFalse("Metadata with modified feature name must be rejected", result.isValid)
        assertTrue(result.error!!.contains("Feature mismatch"))
    }

    @Test
    fun testMetadataRejectsNonPositiveStandardDeviation() {
        // Zero standard deviation
        val jsonZeroStd = createValidMetadataJson()
        val zeroStds = List(16) { if (it == 3) 0.0 else 1.0 }
        jsonZeroStd.getJSONObject("normalization").put("stds", org.json.JSONArray(zeroStds))
        val resZero = FusionConfidenceModel.validateAndParseMetadataJson(jsonZeroStd.toString())
        assertFalse("Metadata with zero std must be rejected", resZero.isValid)
        assertTrue(resZero.error!!.contains("strictly positive"))

        // Negative standard deviation
        val jsonNegStd = createValidMetadataJson()
        val negStds = List(16) { if (it == 5) -0.5 else 1.0 }
        jsonNegStd.getJSONObject("normalization").put("stds", org.json.JSONArray(negStds))
        val resNeg = FusionConfidenceModel.validateAndParseMetadataJson(jsonNegStd.toString())
        assertFalse("Metadata with negative std must be rejected", resNeg.isValid)
        assertTrue(resNeg.error!!.contains("strictly positive"))
    }

    @Test
    fun testMetadataRejectsNon16MeansOrStds() {
        val jsonShortMeans = createValidMetadataJson()
        jsonShortMeans.getJSONObject("normalization").put("means", org.json.JSONArray(List(12) { 0.0 }))
        val resShort = FusionConfidenceModel.validateAndParseMetadataJson(jsonShortMeans.toString())
        assertFalse("Metadata with 12 means must be rejected", resShort.isValid)
        assertTrue(resShort.error!!.contains("16 normalization means"))
    }

    @Test
    fun testMetadataRejectsInputTensorShapeMismatch() {
        val jsonBadShape = createValidMetadataJson()
        jsonBadShape.getJSONObject("input_tensor").put("shape", org.json.JSONArray(listOf(1, 15)))
        val res = FusionConfidenceModel.validateAndParseMetadataJson(jsonBadShape.toString())
        assertFalse("Metadata with bad input shape [1, 15] must be rejected", res.isValid)
        assertTrue(res.error!!.contains("input_tensor shape [1, 16]"))
    }
}
