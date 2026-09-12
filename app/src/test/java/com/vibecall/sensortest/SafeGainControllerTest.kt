package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class SafeGainControllerTest {

    private lateinit var controller: SafeGainController

    @Before
    fun setUp() {
        controller = SafeGainController(
            minimumGain = 0.50f,
            pauseEnergyThresholdDb = -55.0f,
            minConsecutivePauseWindows = 3,
            hangoverWindows = 2,
            maxAttenuationStepPerFrame = 0.16f,
            maxRestoreStepPerFrame = 0.60f
        )
        controller.reset()
    }

    @Test
    fun testAcousticGuardAudibleSpeechForcesUnityGain() {
        // Even if model confidence is low (0.05), microphone speech MUST preserve full audio
        val (gain, reason) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -35.0f,
            microphonePitchReliable = 1.0f,
            modelConfidence = 0.05f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.5f
        )
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Acoustic guard"))
    }

    @Test
    fun testAcousticGuardProtectsAwayFromCheekSpeech() {
        // Core safety invariant: away-from-cheek speech must NEVER be attenuated
        val (gain, reason) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -30.0f,
            microphonePitchReliable = 1.0f,
            modelConfidence = 0.02f, // model correctly reports very low contact confidence
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.01f,
            sensorAlignmentLagMs = 0.5f
        )
        assertEquals("Away speech must never be attenuated", 1.0f, gain, 0.001f)
        assertTrue(reason.contains("Acoustic guard"))
    }

    @Test
    fun testModelFailureGuardFailsSafeToUnity() {
        // If TFLite throws or is unavailable, gain must fail open to 1.0
        val (gain, reason) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -75.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.10f,
            modelReliable = false, // Model inference failed
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Model guard"))
    }

    @Test
    fun testMotionGuardPreventsAttenuationDuringPhoneMovement() {
        // Walking or phone movement creates sensor artifacts; fail open to 1.0
        val (gain, reason) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -70.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.10f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.85f, // Excessive movement > 0.50 m/s²
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Motion guard"))
    }

    @Test
    fun testSensorReliabilityGuardPreventsAttenuationOnHardwareGaps() {
        // Missing accelerometer samples or low rate must fail open to 1.0
        val (gain, reason) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -70.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.10f,
            modelReliable = true,
            sensorReliability = 0.55f, // Unreliable < 0.70
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Sensor guard"))
    }

    @Test
    fun testAlignmentLagGuardPreventsAttenuationWhenOutOfSync() {
        // Audio/sensor timestamp desynchronization > 15ms must fail open
        val (gain, reason) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -70.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.10f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 22.5f // Lag > 15.0 ms
        )
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Lag guard"))
    }

    @Test
    fun testConfirmedPauseRequires3ConsecutiveWindowsBeforeAttenuation() {
        // Window 1 of acoustic silence + low contact confidence
        val (gain1, reason1) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -70.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.10f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Window 1 must not attenuate", 1.0f, gain1, 0.001f)
        assertTrue(reason1.contains("window 1 of 3"))

        // Window 2
        val (gain2, reason2) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -72.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.12f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Window 2 must not attenuate", 1.0f, gain2, 0.001f)
        assertTrue(reason2.contains("window 2 of 3"))

        // Window 3 (confirmed sustained pause)
        val (gain3, reason3) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -71.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.08f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Window 3 reaches minimum gain floor", 0.50f, gain3, 0.001f)
        assertTrue(reason3.contains("Confirmed sustained pause"))
    }

    @Test
    fun testSampleBySampleLinearRampingContinuity() {
        val frameSize = 2048
        val input = ShortArray(frameSize) { 10000 }
        val output = ShortArray(frameSize)

        // Apply gain transition from 1.0 down towards 0.5
        val decision = controller.applyGainToFrame(
            inputShorts = input,
            outputShorts = output,
            targetGain = 0.50f,
            reason = "Test attenuation ramp"
        )

        val expectedEndGain = 1.0f - 0.16f // Slew-rate clamped to -0.16 per frame
        assertEquals(1.0f, decision.appliedGainStart, 0.001f)
        assertEquals(expectedEndGain, decision.appliedGainEnd, 0.001f)

        // Verify start sample has exactly startGain scaling
        assertEquals(10000, output[0].toInt())

        // Verify end sample has exactly endGain scaling
        val expectedEndVal = (10000 * expectedEndGain).toInt()
        assertEquals(expectedEndVal, output[frameSize - 1].toInt())

        // Verify smooth, strictly monotonic transition across all samples without steps
        for (i in 1 until frameSize) {
            assertTrue("Samples must transition monotonically downward", output[i] <= output[i - 1])
        }
    }

    @Test
    fun testFastSpeechRestorationRampsToUnity() {
        // Force controller into attenuated state (0.50)
        val dummyIn = ShortArray(16) { 1000 }
        val dummyOut = ShortArray(16)
        // Ramp down several frames to reach 0.50
        for (i in 0 until 5) {
            controller.applyGainToFrame(dummyIn, dummyOut, 0.50f, "Force down")
        }
        assertEquals(0.50f, controller.getCurrentGain(), 0.001f)

        // Speech returns: target gain is 1.0f
        val restoreDecision = controller.applyGainToFrame(dummyIn, dummyOut, 1.0f, "Speech returned")

        // Max restore step is 0.60f, so 0.50 + 0.60 = 1.10 coerced to 1.0f in a single frame
        assertEquals(1.0f, restoreDecision.appliedGainEnd, 0.001f)
        assertEquals(1.0f, controller.getCurrentGain(), 0.001f)
    }

    @Test
    fun testGainFloorNeverDropsBelowMinimumGain() {
        val dummyIn = ShortArray(16) { 1000 }
        val dummyOut = ShortArray(16)

        // Even with extreme target gain 0.0, bounded target must never drop below 0.50
        for (i in 0 until 10) {
            controller.applyGainToFrame(dummyIn, dummyOut, 0.0f, "Zero target test")
        }
        assertEquals(0.50f, controller.getCurrentGain(), 0.001f)
    }
}
