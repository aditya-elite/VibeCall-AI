package com.vibecall.sensortest

import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Diagnostic statistics tracked during Clarity audio processing.
 */
data class ClarityDiagnostics(
    val delaySamples16k: Int,
    val delayMs: Double,
    val dryMixRatio: Float,
    val presenceEqEnabled: Boolean,
    val loudnessGainMeanDb: Double,
    val loudnessGainMaxDb: Double,
    val limiterThresholdDbfs: Double,
    val observedPeakDbfs: Double,
    val clippedSampleCount: Long
)

/**
 * Direct Form I Biquad Filter implementation for 16 kHz audio.
 */
class BiquadFilter(
    b0: Double, b1: Double, b2: Double,
    a0: Double, a1: Double, a2: Double
) {
    private val nb0 = b0 / a0
    private val nb1 = b1 / a0
    private val nb2 = b2 / a0
    private val na1 = a1 / a0
    private val na2 = a2 / a0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun process(x: Double): Double {
        val y = nb0 * x + nb1 * x1 + nb2 * x2 - na1 * y1 - na2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }

    companion object {
        /**
         * 2nd-order Butterworth High-Pass Filter.
         */
        fun createHighPass(cutoffHz: Double, sampleRate: Double = 16000.0, q: Double = 0.70710678): BiquadFilter {
            val w0 = 2.0 * Math.PI * cutoffHz / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)

            val b0 = (1.0 + cosW0) / 2.0
            val b1 = -(1.0 + cosW0)
            val b2 = (1.0 + cosW0) / 2.0
            val a0 = 1.0 + alpha
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha

            return BiquadFilter(b0, b1, b2, a0, a1, a2)
        }

        /**
         * Peaking Presence EQ Filter.
         */
        fun createPeakingEq(centerHz: Double, gainDb: Double, q: Double = 0.85, sampleRate: Double = 16000.0): BiquadFilter {
            val w0 = 2.0 * Math.PI * centerHz / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)
            val a = Math.pow(10.0, gainDb / 40.0)

            val b0 = 1.0 + alpha * a
            val b1 = -2.0 * cosW0
            val b2 = 1.0 - alpha * a
            val a0 = 1.0 + alpha / a
            val a1 = -2.0 * cosW0
            val a2 = 1.0 - alpha / a

            return BiquadFilter(b0, b1, b2, a0, a1, a2)
        }
    }
}

/**
 * Streaming FIFO buffer for delaying raw audio samples.
 *
 * Pre-filled with [delaySamples] zero samples so that the initial output is zero-padded
 * and subsequent output corresponds to raw[n - delaySamples].
 */
class RawAudioDelayFifo(val delaySamples: Int) {
    private val fifo = ArrayDeque<Short>()

    init {
        reset()
    }

    fun reset() {
        fifo.clear()
        for (i in 0 until delaySamples) {
            fifo.addLast(0)
        }
    }

    fun push(input: ShortArray, length: Int = input.size) {
        for (i in 0 until length) {
            fifo.addLast(input[i])
        }
    }

    fun pop(count: Int, output: ShortArray, outOffset: Int = 0): Int {
        var popped = 0
        while (popped < count) {
            val sample = if (fifo.isNotEmpty()) fifo.removeFirst() else 0.toShort()
            output[outOffset + popped] = sample
            popped++
        }
        return popped
    }

    val availableCount: Int
        get() = fifo.size
}

/**
 * Control state associated with a raw audio window/sample.
 */
data class ClarityControlState(
    val targetDryMix: Float,
    val targetPauseGain: Float,
    val isSpeechActive: Boolean
)

/**
 * Streaming FIFO buffer for delaying speech/pause control state.
 */
class ClarityControlDelayFifo(val delaySamples: Int) {
    private val fifo = ArrayDeque<ClarityControlState>()
    private val defaultInitialState = ClarityControlState(
        targetDryMix = 0.0f,
        targetPauseGain = 1.0f,
        isSpeechActive = false
    )

    init {
        reset()
    }

    fun reset() {
        fifo.clear()
        for (i in 0 until delaySamples) {
            fifo.addLast(defaultInitialState)
        }
    }

    fun push(state: ClarityControlState, count: Int) {
        for (i in 0 until count) {
            fifo.addLast(state)
        }
    }

    fun pop(count: Int, output: Array<ClarityControlState>, outOffset: Int = 0): Int {
        var popped = 0
        while (popped < count) {
            val state = if (fifo.isNotEmpty()) fifo.removeFirst() else defaultInitialState
            output[outOffset + popped] = state
            popped++
        }
        return popped
    }
}

/**
 * Conservative Speech-Clarity Processor for VibeCall-AI.
 *
 * Restores consonant presence (1–3.5 kHz) and compensates for RNNoise speech attenuation
 * using:
 * 1. 320-sample raw-audio delay alignment (calibrated from Trial 23 on iQOO 15 hardware).
 * 2. Smooth dry/wet mixing (75% RNNoise / 25% delayed raw during speech, 100% RNNoise during pauses).
 * 3. 80 Hz high-pass rumble filter and +1.5 dB presence peaking EQ (2.5 kHz).
 * 4. Speech-aware loudness gain capped at +6 dB.
 * 5. Peak limiter targeting -1 dBFS with hard 16-bit PCM safety clamping.
 *
 * Maintains an independent [SafeGainController] so primary Fusion state is invariant.
 */
class ClarityAudioProcessor(
    val delaySamples: Int = RNNOISE_DELAY_SAMPLES_16K,
    val speechDryMixRatio: Float = DEFAULT_DRY_MIX_RATIO,
    val presenceEqEnabled: Boolean = true,
    val presenceEqCenterHz: Double = 2500.0,
    val presenceEqGainDb: Double = 1.5,
    val presenceEqQ: Double = 0.85,
    val hpfEnabled: Boolean = true,
    val hpfCutoffHz: Double = 80.0,
    val maxLoudnessGainDb: Double = MAX_LOUDNESS_GAIN_DB,
    val limiterThresholdDbfs: Double = LIMITER_THRESHOLD_DBFS
) {
    companion object {
        /**
         * Provisional delay calibration measured from Trial 23 on iQOO 15 hardware.
         * 320 samples @ 16 kHz represents approximately 20.0 ms.
         */
        const val RNNOISE_DELAY_SAMPLES_16K = 320
        const val RNNOISE_DELAY_MS = 20.0

        const val DEFAULT_DRY_MIX_RATIO = 0.25f
        const val MIN_DRY_MIX_RATIO = 0.0f
        const val MAX_DRY_MIX_RATIO = 0.35f

        const val MAX_LOUDNESS_GAIN_DB = 6.0
        const val LIMITER_THRESHOLD_DBFS = -1.0

        // Linear threshold for -1.0 dBFS on 16-bit signed scale: 32767 * 10^(-1/20) ~= 29204
        val LIMITER_THRESHOLD_AMPLITUDE = (32767.0 * Math.pow(10.0, LIMITER_THRESHOLD_DBFS / 20.0)).roundToInt() // 29204
    }

    private val safeDryMix = speechDryMixRatio.coerceIn(MIN_DRY_MIX_RATIO, MAX_DRY_MIX_RATIO)

    // Streaming FIFOs
    private val rawDelayFifo = RawAudioDelayFifo(delaySamples)
    private val controlDelayFifo = ClarityControlDelayFifo(delaySamples)

    // Independent gain controller instance to prevent affecting primary Fusion state
    private val independentGainController = SafeGainController()

    // Filters
    private val hpf = BiquadFilter.createHighPass(hpfCutoffHz)
    private val presenceEq = BiquadFilter.createPeakingEq(presenceEqCenterHz, presenceEqGainDb, presenceEqQ)

    // Dynamic smoothing states
    private var currentSmoothedDryMix: Float = 0.0f
    private var currentSmoothedPauseGain: Float = 1.0f
    private var currentLoudnessGainLinear: Double = 1.0

    // Diagnostic tracking
    private var totalProcessedSamples: Long = 0L
    private var sumLoudnessGainDb: Double = 0.0
    private var maxLoudnessGainDbObserved: Double = 0.0
    private var maxObservedPeakMagnitude: Int = 0
    private var clippedSamplesCount: Long = 0L

    // Reusable buffers
    private var alignedRawBuffer = ShortArray(4096)
    @Suppress("UNCHECKED_CAST")
    private var controlStateBuffer: Array<ClarityControlState> = Array(4096) {
        ClarityControlState(0f, 1f, false)
    }

    fun reset() {
        rawDelayFifo.reset()
        controlDelayFifo.reset()
        independentGainController.reset()
        hpf.reset()
        presenceEq.reset()
        currentSmoothedDryMix = 0.0f
        currentSmoothedPauseGain = 1.0f
        currentLoudnessGainLinear = 1.0
        totalProcessedSamples = 0L
        sumLoudnessGainDb = 0.0
        maxLoudnessGainDbObserved = 0.0
        maxObservedPeakMagnitude = 0
        clippedSamplesCount = 0L
    }

    /**
     * Push raw audio samples and associated feature evaluations into the delay buffer.
     */
    @Synchronized
    fun pushRawAudioAndFeatures(
        rawSamples: ShortArray,
        length: Int,
        microphoneLogEnergyDb: Float,
        microphonePitchReliable: Float,
        modelConfidence: Float,
        modelReliable: Boolean,
        sensorReliability: Float,
        phoneMotionLevel: Float,
        sensorAlignmentLagMs: Float
    ) {
        if (length <= 0) return

        // Push raw audio to delay line
        rawDelayFifo.push(rawSamples, length)

        // Evaluate independent clarity gain controller
        val (targetGain, reason) = independentGainController.evaluateTargetGain(
            microphoneLogEnergyDb = microphoneLogEnergyDb,
            microphonePitchReliable = microphonePitchReliable,
            modelConfidence = modelConfidence,
            modelReliable = modelReliable,
            sensorReliability = sensorReliability,
            phoneMotionLevel = phoneMotionLevel,
            sensorAlignmentLagMs = sensorAlignmentLagMs
        )

        // Determine speech vs pause state
        // Speech protected if acoustic guard active, hangover active, contact speech confirmed, or uncertain
        val isAcousticSpeech = (microphoneLogEnergyDb >= independentGainController.pauseEnergyThresholdDb) ||
                (microphonePitchReliable > 0.5f)
        val isHangover = reason.startsWith("Hangover active")
        val isConfirmedSpeech = modelConfidence >= SafeGainController.CONFIDENCE_PRESERVE_THRESHOLD
        val isUncertainOrGuard = !modelReliable || sensorReliability < SafeGainController.SENSOR_RELIABILITY_THRESHOLD ||
                (modelConfidence > SafeGainController.CONFIDENCE_PAUSE_CANDIDATE_THRESHOLD)

        val isSpeechActive = isAcousticSpeech || isHangover || isConfirmedSpeech || isUncertainOrGuard

        val targetDryMix = if (isSpeechActive) safeDryMix else 0.0f
        val state = ClarityControlState(
            targetDryMix = targetDryMix,
            targetPauseGain = targetGain,
            isSpeechActive = isSpeechActive
        )

        controlDelayFifo.push(state, length)
    }

    /**
     * Process RNNoise output samples and produce corresponding Clarity output.
     *
     * Consumes exactly [rnnoiseSamples.size] samples from the delay FIFO to guarantee
     * identical duration.
     */
    @Synchronized
    fun processRnnoiseChunk(
        rnnoiseSamples: ShortArray,
        outputClarity: ShortArray,
        length: Int = rnnoiseSamples.size
    ) {
        if (length <= 0) return

        if (alignedRawBuffer.size < length) {
            alignedRawBuffer = ShortArray(length)
        }
        if (controlStateBuffer.size < length) {
            @Suppress("UNCHECKED_CAST")
            controlStateBuffer = Array(length) { ClarityControlState(0f, 1f, false) }
        }

        // 1. Pop exactly `length` aligned raw samples and control states
        rawDelayFifo.pop(length, alignedRawBuffer, 0)
        controlDelayFifo.pop(length, controlStateBuffer, 0)

        // 2. Measure speech-active RMS in current chunk for loudness compensation
        var speechSumSq = 0.0
        var speechSampleCount = 0
        for (i in 0 until length) {
            if (controlStateBuffer[i].isSpeechActive) {
                val s = rnnoiseSamples[i].toDouble()
                speechSumSq += s * s
                speechSampleCount++
            }
        }

        val targetLoudnessGain = if (speechSampleCount > 32) {
            val speechRms = sqrt(speechSumSq / speechSampleCount.toDouble())
            // If RNNoise speech level is low (e.g. around -56 dBFS), apply conservative boost up to +6dB
            // Reference nominal conversational speech RMS at -50 dBFS is ~103.6 out of 32767
            if (speechRms > 1.0) {
                val nominalRms = 100.0
                val ratio = nominalRms / speechRms
                ratio.coerceIn(1.0, Math.pow(10.0, maxLoudnessGainDb / 20.0))
            } else {
                1.0
            }
        } else {
            1.0 // Silence/pause: never boost noise independently
        }

        val maxDryMixStepPerSample = 0.001f // Smooth transitions between 0.0 and 0.25 over ~250 samples (~15ms)
        val maxGainStepPerSample = 0.0005f
        val maxLoudnessStepPerSample = 0.0002

        for (i in 0 until length) {
            val ctrl = controlStateBuffer[i]

            // Slew dry mix
            if (ctrl.targetDryMix > currentSmoothedDryMix) {
                currentSmoothedDryMix = min(ctrl.targetDryMix, currentSmoothedDryMix + maxDryMixStepPerSample)
            } else if (ctrl.targetDryMix < currentSmoothedDryMix) {
                currentSmoothedDryMix = max(ctrl.targetDryMix, currentSmoothedDryMix - maxDryMixStepPerSample)
            }

            // Slew pause gain
            if (ctrl.targetPauseGain > currentSmoothedPauseGain) {
                currentSmoothedPauseGain = min(ctrl.targetPauseGain, currentSmoothedPauseGain + maxGainStepPerSample)
            } else if (ctrl.targetPauseGain < currentSmoothedPauseGain) {
                currentSmoothedPauseGain = max(ctrl.targetPauseGain, currentSmoothedPauseGain - maxGainStepPerSample)
            }

            // Slew loudness gain
            if (targetLoudnessGain > currentLoudnessGainLinear) {
                currentLoudnessGainLinear = min(targetLoudnessGain, currentLoudnessGainLinear + maxLoudnessStepPerSample)
            } else if (targetLoudnessGain < currentLoudnessGainLinear) {
                currentLoudnessGainLinear = max(targetLoudnessGain, currentLoudnessGainLinear - maxLoudnessStepPerSample)
            }

            val rn = rnnoiseSamples[i].toDouble()
            val raw = alignedRawBuffer[i].toDouble()

            // 1. Dry/Wet Mix
            var sample = (1.0 - currentSmoothedDryMix.toDouble()) * rn + currentSmoothedDryMix.toDouble() * raw

            // Apply pause attenuation if in confirmed pause
            sample *= currentSmoothedPauseGain.toDouble()

            // 2. High-Pass Filter (80 Hz)
            if (hpfEnabled) {
                sample = hpf.process(sample)
            }

            // 3. Gentle Presence EQ (2.5 kHz, +1.5 dB)
            if (presenceEqEnabled) {
                sample = presenceEq.process(sample)
            }

            // 4. Speech-Aware Loudness Gain
            sample *= currentLoudnessGainLinear

            // 5. Peak Limiter (-1.0 dBFS ceiling = 29204)
            val threshold = LIMITER_THRESHOLD_AMPLITUDE.toDouble()
            val absSample = abs(sample)
            val limitedSample = if (absSample <= threshold) {
                sample
            } else {
                // Soft-knee compression asymptotic to threshold
                val sign = if (sample >= 0) 1.0 else -1.0
                val excess = absSample - threshold
                sign * (threshold + (32767.0 - threshold) * 0.1 * tanh(excess / ((32767.0 - threshold) * 0.1)))
            }

            // Final hard safety clamp to guaranteed -1.0 dBFS and 16-bit PCM bounds
            val clampedDouble = limitedSample.coerceIn(-threshold, threshold)
            val intSample = clampedDouble.roundToInt()

            // Record diagnostics
            val peakMagnitude = abs(intSample)
            if (peakMagnitude > maxObservedPeakMagnitude) {
                maxObservedPeakMagnitude = peakMagnitude
            }
            if (abs(sample) > 32767.0) {
                clippedSamplesCount++
            }

            outputClarity[i] = intSample.coerceIn(-32768, 32767).toShort()

            // Track loudness metrics
            val gainDb = 20.0 * log10(max(1.0, currentLoudnessGainLinear))
            sumLoudnessGainDb += gainDb
            if (gainDb > maxLoudnessGainDbObserved) {
                maxLoudnessGainDbObserved = gainDb
            }
            totalProcessedSamples++
        }
    }

    /**
     * Compute summary diagnostics for metadata.json.
     */
    fun getDiagnostics(): ClarityDiagnostics {
        val meanGainDb = if (totalProcessedSamples > 0) sumLoudnessGainDb / totalProcessedSamples.toDouble() else 0.0
        val observedPeakDbfs = if (maxObservedPeakMagnitude > 0) {
            20.0 * log10(maxObservedPeakMagnitude.toDouble() / 32767.0)
        } else {
            -96.0
        }

        return ClarityDiagnostics(
            delaySamples16k = delaySamples,
            delayMs = (delaySamples.toDouble() * 1000.0) / 16000.0,
            dryMixRatio = safeDryMix,
            presenceEqEnabled = presenceEqEnabled,
            loudnessGainMeanDb = meanGainDb,
            loudnessGainMaxDb = maxLoudnessGainDbObserved,
            limiterThresholdDbfs = limiterThresholdDbfs,
            observedPeakDbfs = observedPeakDbfs,
            clippedSampleCount = clippedSamplesCount
        )
    }
}
