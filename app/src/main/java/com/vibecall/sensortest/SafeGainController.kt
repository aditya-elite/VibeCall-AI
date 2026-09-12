package com.vibecall.sensortest

import kotlin.math.max
import kotlin.math.min

/**
 * Audit record of the decision made by [SafeGainController] for an audio window.
 */
data class ControllerDecision(
    val controllerState: String,
    val targetGain: Float,
    val appliedGainStart: Float,
    val appliedGainEnd: Float,
    val reason: String
)

/**
 * Step 4 Conservative Audio Gain Controller for VibeCall-AI.
 *
 * Implements an independent acoustic-speech safety guard and stateful gain modulation.
 *
 * Core Safety Invariants:
 * 1. Low contact confidence alone NEVER attenuates audio.
 * 2. Audible microphone speech ALWAYS produces unity gain (1.0).
 * 3. Away-from-cheek speech ALWAYS produces unity gain (1.0).
 * 4. Movement (> 0.50 m/s²), excessive lag (> 15 ms), or sensor unreliability (< 0.70) produces unity gain (1.0).
 * 5. Invalid model inference (modelReliable == false) produces unity gain (1.0).
 * 6. Attenuation down to minimum gain (0.5) requires BOTH:
 *    - Confirmed sustained acoustic pause (mic energy < pauseThreshold, no mic pitch, >= 3 consecutive windows)
 *    - Valid, healthy model reporting confidence <= 0.20.
 * 7. Gain is linearly ramped sample-by-sample across the audio frame to eliminate discontinuities.
 */
class SafeGainController(
    val minimumGain: Float = DEFAULT_MIN_GAIN,
    val pauseEnergyThresholdDb: Float = DEFAULT_PAUSE_ENERGY_THRESHOLD_DB,
    val minConsecutivePauseWindows: Int = DEFAULT_MIN_PAUSE_WINDOWS,
    val hangoverWindows: Int = DEFAULT_HANGOVER_WINDOWS,
    val maxAttenuationStepPerFrame: Float = DEFAULT_MAX_ATTENUATION_STEP,
    val maxRestoreStepPerFrame: Float = DEFAULT_MAX_RESTORE_STEP
) {

    enum class State {
        PRESERVE,
        PAUSE_PENDING,
        ATTENUATING,
        RESTORE_RAMP,
        HANGOVER
    }

    private var currentState: State = State.PRESERVE
    private var currentSmoothedGain: Float = 1.0f
    private var consecutivePauseCount: Int = 0
    private var hangoverCounter: Int = 0

    /**
     * Evaluates frame conditions and computes the target gain and reason.
     *
     * @param microphoneLogEnergyDb Mic log energy in dB.
     * @param microphonePitchReliable 1.0f if pitch detected in mic, 0.0f otherwise.
     * @param modelConfidence Model confidence in [0.0, 1.0].
     * @param modelReliable True if TFLite inference was valid, false on error/fallback.
     * @param sensorReliability Sensor reliability score [0.0, 1.0].
     * @param phoneMotionLevel Phone lowpass movement level in m/s².
     * @param sensorAlignmentLagMs Alignment lag in ms.
     * @return Pair of target gain in [minimumGain, 1.0] and diagnostic reason string.
     */
    fun evaluateTargetGain(
        microphoneLogEnergyDb: Float,
        microphonePitchReliable: Float,
        modelConfidence: Float,
        modelReliable: Boolean,
        sensorReliability: Float,
        phoneMotionLevel: Float,
        sensorAlignmentLagMs: Float
    ): Pair<Float, String> {

        // 1. Independent Acoustic-Speech Safety Guard
        val isAcousticSpeechPresent = (microphoneLogEnergyDb >= pauseEnergyThresholdDb) || (microphonePitchReliable > 0.5f)
        if (isAcousticSpeechPresent) {
            consecutivePauseCount = 0
            hangoverCounter = hangoverWindows
            return 1.0f to "Acoustic guard: audible speech detected in microphone"
        }

        // 2. Model Reliability Guard
        if (!modelReliable) {
            consecutivePauseCount = 0
            return 1.0f to "Model guard: inference failed or model unavailable"
        }

        // 3. Sensor Health & Synchronization Guard
        if (sensorReliability < SENSOR_RELIABILITY_THRESHOLD) {
            consecutivePauseCount = 0
            return 1.0f to "Sensor guard: sensor reliability low (${String.format("%.2f", sensorReliability)})"
        }

        if (phoneMotionLevel > PHONE_MOTION_THRESHOLD) {
            consecutivePauseCount = 0
            return 1.0f to "Motion guard: excessive phone movement (${String.format("%.2f", phoneMotionLevel)} m/s²)"
        }

        if (sensorAlignmentLagMs > ALIGNMENT_LAG_THRESHOLD_MS) {
            consecutivePauseCount = 0
            return 1.0f to "Lag guard: excessive alignment lag (${String.format("%.1f", sensorAlignmentLagMs)} ms)"
        }

        // 4. Contact Speech Confidence Evaluation
        if (modelConfidence >= CONFIDENCE_PRESERVE_THRESHOLD) {
            consecutivePauseCount = 0
            hangoverCounter = hangoverWindows
            return 1.0f to "Contact speech confirmed (confidence ${String.format("%.2f", modelConfidence)} >= $CONFIDENCE_PRESERVE_THRESHOLD)"
        }

        if (modelConfidence > CONFIDENCE_PAUSE_CANDIDATE_THRESHOLD) {
            consecutivePauseCount = 0
            return 1.0f to "Confidence uncertain (${String.format("%.2f", modelConfidence)} in [$CONFIDENCE_PAUSE_CANDIDATE_THRESHOLD, $CONFIDENCE_PRESERVE_THRESHOLD])"
        }

        // 5. Candidate Pause: Acoustic pause confirmed AND model confidence <= 0.20
        consecutivePauseCount++
        if (consecutivePauseCount < minConsecutivePauseWindows) {
            return 1.0f to "Pause pending: window $consecutivePauseCount of $minConsecutivePauseWindows required"
        }

        return minimumGain to "Confirmed sustained pause ($consecutivePauseCount consecutive windows, confidence ${String.format("%.2f", modelConfidence)})"
    }

    /**
     * Applies sample-by-sample linear interpolation from the current smoothed gain to the target gain,
     * enforcing slew-rate limits and preventing clicks.
     *
     * @param inputShorts Raw or RNNoise audio samples (16-bit PCM).
     * @param outputShorts Destination buffer for modulated audio samples.
     * @param targetGain Evaluated target gain from [evaluateTargetGain].
     * @param reason Diagnostic reason string.
     * @return [ControllerDecision] record for session auditing.
     */
    @Synchronized
    fun applyGainToFrame(
        inputShorts: ShortArray,
        outputShorts: ShortArray,
        targetGain: Float,
        reason: String
    ): ControllerDecision {
        val boundedTarget = targetGain.coerceIn(minimumGain, 1.0f)
        val startGain = currentSmoothedGain

        // Determine next end gain based on slew rate limits
        val endGain = if (boundedTarget > startGain) {
            currentState = State.RESTORE_RAMP
            min(boundedTarget, startGain + maxRestoreStepPerFrame)
        } else if (boundedTarget < startGain) {
            currentState = State.ATTENUATING
            max(boundedTarget, startGain - maxAttenuationStepPerFrame)
        } else {
            currentState = if (boundedTarget < 0.99f) State.ATTENUATING else State.PRESERVE
            boundedTarget
        }

        // Sample-by-sample linear ramping across the frame
        val frameSize = minOf(inputShorts.size, outputShorts.size)
        val gainDelta = endGain - startGain

        for (i in 0 until frameSize) {
            val progress = if (frameSize > 1) i.toFloat() / (frameSize - 1) else 1.0f
            val sampleGain = startGain + progress * gainDelta
            val scaledSample = (inputShorts[i] * sampleGain).toInt()
            outputShorts[i] = scaledSample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }

        currentSmoothedGain = endGain

        return ControllerDecision(
            controllerState = currentState.name,
            targetGain = boundedTarget,
            appliedGainStart = startGain,
            appliedGainEnd = endGain,
            reason = reason
        )
    }

    fun reset() {
        currentState = State.PRESERVE
        currentSmoothedGain = 1.0f
        consecutivePauseCount = 0
        hangoverCounter = 0
    }

    fun getCurrentGain(): Float = currentSmoothedGain
    fun getState(): State = currentState

    companion object {
        const val DEFAULT_MIN_GAIN = 0.50f
        const val DEFAULT_PAUSE_ENERGY_THRESHOLD_DB = -55.0f
        const val DEFAULT_MIN_PAUSE_WINDOWS = 3
        const val DEFAULT_HANGOVER_WINDOWS = 2

        // Slew rates:
        // Down: ~300 ms across 128 ms hops -> ~3 hops for 0.50 delta -> 0.16 per frame
        const val DEFAULT_MAX_ATTENUATION_STEP = 0.16f
        // Up: ~30 ms across 128 ms hops -> restores within 1 frame -> 0.60 per frame
        const val DEFAULT_MAX_RESTORE_STEP = 0.60f

        const val CONFIDENCE_PRESERVE_THRESHOLD = 0.70f
        const val CONFIDENCE_PAUSE_CANDIDATE_THRESHOLD = 0.20f
        const val SENSOR_RELIABILITY_THRESHOLD = 0.70f
        const val PHONE_MOTION_THRESHOLD = 0.50f
        const val ALIGNMENT_LAG_THRESHOLD_MS = 15.0f
    }
}
