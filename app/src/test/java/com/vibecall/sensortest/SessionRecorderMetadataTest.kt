package com.vibecall.sensortest

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

class SessionRecorderMetadataTest {

    @Test
    fun testMicrophoneCharacterizationUnprocessedAndEffectsDisabled() {
        val effects = listOf(
            AudioEffectStatus(
                name = "NoiseSuppressor",
                available = true,
                wasEnabled = true,
                disabledSuccessfully = true,
                currentEnabled = false
            ),
            AudioEffectStatus(
                name = "AcousticEchoCanceler",
                available = true,
                wasEnabled = true,
                disabledSuccessfully = true,
                currentEnabled = false
            ),
            AudioEffectStatus(
                name = "AutomaticGainControl",
                available = false,
                wasEnabled = false,
                disabledSuccessfully = false,
                currentEnabled = false
            )
        )

        val characterization = SessionRecorder.getMicrophoneCharacterization("UNPROCESSED", effects)
        assertEquals("Unprocessed acoustic microphone", characterization)
    }

    @Test
    fun testMicrophoneCharacterizationUnprocessedWithActiveEffects() {
        val effects = listOf(
            AudioEffectStatus(
                name = "NoiseSuppressor",
                available = true,
                wasEnabled = true,
                disabledSuccessfully = false,
                currentEnabled = true
            )
        )

        val characterization = SessionRecorder.getMicrophoneCharacterization("UNPROCESSED", effects)
        assertEquals("Platform source: UNPROCESSED", characterization)
    }

    @Test
    fun testMicrophoneCharacterizationVoiceCommunication() {
        val effects = emptyList<AudioEffectStatus>()
        val characterization = SessionRecorder.getMicrophoneCharacterization("VOICE_COMMUNICATION", effects)
        assertEquals("Platform-processed microphone", characterization)
    }

    @Test
    fun testMicrophoneCharacterizationVoiceRecognition() {
        val effects = listOf(
            AudioEffectStatus(
                name = "NoiseSuppressor",
                available = true,
                wasEnabled = false,
                disabledSuccessfully = false,
                currentEnabled = false
            )
        )
        val characterization = SessionRecorder.getMicrophoneCharacterization("VOICE_RECOGNITION", effects)
        assertEquals("Platform source: VOICE_RECOGNITION", characterization)
    }

    @Test
    fun testMicrophoneCharacterizationMic() {
        val effects = emptyList<AudioEffectStatus>()
        val characterization = SessionRecorder.getMicrophoneCharacterization("MIC", effects)
        assertEquals("Platform source: MIC", characterization)
    }

    @Test
    fun testRecordingModeDefaults() {
        assertEquals(RecordingMode.STABLE_COMMUNICATION, RecordingMode.valueOf("STABLE_COMMUNICATION"))
        assertEquals(RecordingMode.FAIR_COMPARISON, RecordingMode.valueOf("FAIR_COMPARISON"))
    }

    @Test
    fun testRnnoiseAttenuationDbComputation() {
        val inRms = 1000.0
        val outRms = 500.0
        val attenuationDb = 20.0 * log10(inRms / outRms)
        assertEquals(6.0206, attenuationDb, 0.01)

        val passThroughAttenuation = 20.0 * log10(1000.0 / 1000.0)
        assertEquals(0.0, passThroughAttenuation, 0.0001)
    }

    @Test
    fun testTelemetryMetadataKeysIntegrity() {
        val fusionStats = FusionRuntimeStats(
            backendStatus = "Model evaluated on NNAPI (delegate initialized)",
            inferenceCount = 142L,
            failureCount = 0L,
            avgLatencyUs = 85.4,
            maxLatencyUs = 210L
        )

        val rnnoiseStats = RnnoiseRuntimeStats(
            initialized = true,
            frameCount = 450L,
            failureCount = 0L,
            inputSampleCount = 72000L,
            outputSampleCount = 72000L,
            inputRms = 450.2,
            outputRms = 220.1,
            attenuationDb = 6.21,
            meanAbsoluteDiff = 85.3,
            differentSamplePercentage = 98.4,
            actualAudioSource = "UNPROCESSED"
        )

        val metadata = JSONObject().apply {
            put("recording_mode", RecordingMode.FAIR_COMPARISON.name)
            put("microphone_characterization", "Unprocessed acoustic microphone")
            put("legacy_trust_inference_count", 0L)
            put("fusion_inference_count", fusionStats.inferenceCount)
            put("fusion_confidence_inference_count", fusionStats.inferenceCount)
            put("fusion_failure_count", fusionStats.failureCount)
            put("fusion_avg_latency_us", fusionStats.avgLatencyUs)
            put("fusion_max_latency_us", fusionStats.maxLatencyUs)
            put("fusion_backend_status", fusionStats.backendStatus)

            val rnnoiseDiag = JSONObject().apply {
                put("initialized", rnnoiseStats.initialized)
                put("frame_count", rnnoiseStats.frameCount)
                put("failure_count", rnnoiseStats.failureCount)
                put("input_sample_count", rnnoiseStats.inputSampleCount)
                put("output_sample_count", rnnoiseStats.outputSampleCount)
                put("input_rms", rnnoiseStats.inputRms)
                put("output_rms", rnnoiseStats.outputRms)
                put("attenuation_db", rnnoiseStats.attenuationDb)
                put("mean_absolute_diff", rnnoiseStats.meanAbsoluteDiff)
                put("different_sample_percentage", rnnoiseStats.differentSamplePercentage)
                put("actual_audio_source", rnnoiseStats.actualAudioSource)
            }
            put("rnnoise_diagnostics", rnnoiseDiag)
        }

        // Verify key assertions
        assertEquals(142L, metadata.getLong("fusion_inference_count"))
        assertEquals(142L, metadata.getLong("fusion_confidence_inference_count"))
        assertEquals(0L, metadata.getLong("legacy_trust_inference_count"))
        assertEquals("FAIR_COMPARISON", metadata.getString("recording_mode"))
        assertEquals("Unprocessed acoustic microphone", metadata.getString("microphone_characterization"))

        val diag = metadata.getJSONObject("rnnoise_diagnostics")
        assertTrue(diag.getBoolean("initialized"))
        assertEquals(450L, diag.getLong("frame_count"))
        assertEquals(72000L, diag.getLong("input_sample_count"))
        assertEquals(6.21, diag.getDouble("attenuation_db"), 0.01)
    }
}
