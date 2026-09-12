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

    @Test
    fun testHangoverDelaysPauseCountingAndProtectsWordEndings() {
        // Step 1: Active speech occurs
        val (gainSpeech, _) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -25.0f,
            microphonePitchReliable = 1.0f,
            modelConfidence = 0.95f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals(1.0f, gainSpeech, 0.001f)
        assertEquals(2, controller.getHangoverCounter())
        assertEquals(0, controller.getConsecutivePauseCount())

        // Step 2: Speech suddenly drops (apparent pause window 1) - quiet consonant or word ending
        val (gainHangover1, reason1) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -65.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.08f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Hangover window 1 must preserve unity gain for trailing word endings", 1.0f, gainHangover1, 0.001f)
        assertTrue(reason1.contains("Hangover active"))
        assertEquals(1, controller.getHangoverCounter())
        assertEquals(0, controller.getConsecutivePauseCount())

        // Step 3: Apparent pause window 2
        val (gainHangover2, reason2) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -68.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.05f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Hangover window 2 must preserve unity gain", 1.0f, gainHangover2, 0.001f)
        assertTrue(reason2.contains("Hangover active"))
        assertEquals(0, controller.getHangoverCounter())
        assertEquals(0, controller.getConsecutivePauseCount())

        // Step 4: Hangover has now reached 0; pause counter begins counting window 1
        val (gainPause1, reasonP1) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -70.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.04f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Pause pending window 1 must still preserve unity gain", 1.0f, gainPause1, 0.001f)
        assertTrue(reasonP1.contains("window 1 of 3 required"))
        assertEquals(1, controller.getConsecutivePauseCount())

        // Step 5: Pause window 2
        val (gainPause2, reasonP2) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -70.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.04f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Pause pending window 2 must still preserve unity gain", 1.0f, gainPause2, 0.001f)
        assertTrue(reasonP2.contains("window 2 of 3 required"))
        assertEquals(2, controller.getConsecutivePauseCount())

        // Step 6: Pause window 3 reaches minimum gain floor
        val (gainPause3, reasonP3) = controller.evaluateTargetGain(
            microphoneLogEnergyDb = -72.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.03f,
            modelReliable = true,
            sensorReliability = 1.0f,
            phoneMotionLevel = 0.02f,
            sensorAlignmentLagMs = 1.0f
        )
        assertEquals("Pause window 3 confirms sustained pause and outputs minimum gain", 0.50f, gainPause3, 0.001f)
        assertTrue(reasonP3.contains("Confirmed sustained pause"))
        assertEquals(3, controller.getConsecutivePauseCount())
    }

    @Test
    fun testUncertainConfidencePreservesUnityAndResetsPauseCount() {
        // Build up 2 pause windows
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        assertEquals(2, controller.getConsecutivePauseCount())

        // Uncertain confidence window (0.45 is in [0.20, 0.70])
        val (gain, reason) = controller.evaluateTargetGain(-70.0f, 0.0f, 0.45f, true, 1.0f, 0.02f, 1.0f)
        assertEquals("Uncertain confidence must fail safe to unity gain", 1.0f, gain, 0.001f)
        assertTrue(reason.contains("Confidence uncertain"))
        assertEquals("Pause counter must be reset to 0 on uncertainty", 0, controller.getConsecutivePauseCount())
    }

    @Test
    fun testMotionSpikeResetsPauseCount() {
        // Build up 2 pause windows
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        assertEquals(2, controller.getConsecutivePauseCount())

        // Motion spike > 0.50 m/s²
        val (gain, reason) = controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.85f, 1.0f)
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Motion guard"))
        assertEquals("Pause counter must be reset to 0 on motion", 0, controller.getConsecutivePauseCount())
    }

    @Test
    fun testAlignmentLagSpikeResetsPauseCount() {
        // Build up 2 pause windows
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        assertEquals(2, controller.getConsecutivePauseCount())

        // Alignment lag > 15.0 ms
        val (gain, reason) = controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 25.0f)
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Lag guard"))
        assertEquals("Pause counter must be reset to 0 on lag", 0, controller.getConsecutivePauseCount())
    }

    @Test
    fun testSensorReliabilityDropResetsPauseCount() {
        // Build up 2 pause windows
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        assertEquals(2, controller.getConsecutivePauseCount())

        // Sensor reliability < 0.70
        val (gain, reason) = controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 0.50f, 0.02f, 1.0f)
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Sensor guard"))
        assertEquals("Pause counter must be reset to 0 on unreliable sensors", 0, controller.getConsecutivePauseCount())
    }

    @Test
    fun testModelFailureResetsPauseCount() {
        // Build up 2 pause windows
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, true, 1.0f, 0.02f, 1.0f)
        assertEquals(2, controller.getConsecutivePauseCount())

        // Model failure
        val (gain, reason) = controller.evaluateTargetGain(-70.0f, 0.0f, 0.05f, false, 1.0f, 0.02f, 1.0f)
        assertEquals(1.0f, gain, 0.001f)
        assertTrue(reason.contains("Model guard"))
        assertEquals("Pause counter must be reset to 0 on model failure", 0, controller.getConsecutivePauseCount())
    }
}
