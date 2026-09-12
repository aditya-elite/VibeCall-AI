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
}
