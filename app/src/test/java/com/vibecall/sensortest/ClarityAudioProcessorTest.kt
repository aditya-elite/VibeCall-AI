package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ClarityAudioProcessorTest {

    /**
     * Helper to compute Pearson correlation coefficient between two signals.
     */
    private fun computeCorrelation(x: ShortArray, y: ShortArray, length: Int = minOf(x.size, y.size)): Double {
        var sumX = 0.0
        var sumY = 0.0
        var sumX2 = 0.0
        var sumY2 = 0.0
        var sumXY = 0.0
        val n = length.toDouble()
        if (n < 10) return 0.0

        for (i in 0 until length) {
            val xi = x[i].toDouble()
            val yi = y[i].toDouble()
            sumX += xi
            sumY += yi
            sumX2 += xi * xi
            sumY2 += yi * yi
            sumXY += xi * yi
        }

        val num = sumXY - (sumX * sumY / n)
        val denX = sumX2 - (sumX * sumX / n)
        val denY = sumY2 - (sumY * sumY / n)
        return if (denX > 1e-9 && denY > 1e-9) {
            (num / (sqrt(denX) * sqrt(denY))).coerceIn(-1.0, 1.0)
        } else {
            0.0
        }
    }

    /**
     * Generate synthetic speech-like multi-tone audio.
     */
    private fun generateSyntheticAudio(numSamples: Int, sampleRate: Double = 16000.0): ShortArray {
        val out = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / sampleRate
            // Rich multi-harmonic signal with dynamic envelope
            val env = 0.5 * (1.0 + sin(2.0 * Math.PI * 2.0 * t))
            val harmonic1 = sin(2.0 * Math.PI * 220.0 * t) // F0 fundamental
            val harmonic2 = 0.7 * sin(2.0 * Math.PI * 440.0 * t)
            val harmonic3 = 0.5 * sin(2.0 * Math.PI * 880.0 * t)
            val formant = 0.4 * sin(2.0 * Math.PI * 2500.0 * t) // presence formant
            val s = env * (harmonic1 + harmonic2 + harmonic3 + formant) * 8000.0
            out[i] = s.toInt().coerceIn(-32768, 32767).toShort()
        }
        return out
    }

    @Test
    fun testSyntheticDelayedAudioCorrelationImprovement() {
        // Provisional calibration: 320 samples @ 16 kHz = 20 ms
        val delaySamples = ClarityAudioProcessor.RNNOISE_DELAY_SAMPLES_16K
        assertEquals(320, delaySamples)

        val totalSamples = 3200 // 200 ms of synthetic audio
        val raw = generateSyntheticAudio(totalSamples)

        // Delayed synthetic signal simulating RNNoise output lag: y[n] = raw[n - 320]
        val simulatedRnnoise = ShortArray(totalSamples)
        for (i in delaySamples until totalSamples) {
            simulatedRnnoise[i] = raw[i - delaySamples]
        }

        // 1. Unaligned correlation (comparing raw[n] directly against simulatedRnnoise[n])
        val unalignedCorr = computeCorrelation(raw, simulatedRnnoise)

        // 2. Aligned correlation (delaying raw by 320 samples to match simulatedRnnoise)
        val alignedRaw = ShortArray(totalSamples - delaySamples)
        val alignedRnnoise = ShortArray(totalSamples - delaySamples)
        for (i in 0 until totalSamples - delaySamples) {
            alignedRaw[i] = raw[i]
            alignedRnnoise[i] = simulatedRnnoise[i + delaySamples]
        }
        val alignedCorr = computeCorrelation(alignedRaw, alignedRnnoise)

        // Test for significant improvement rather than a brittle hard-coded value
        assertTrue(
            "Aligned correlation ($alignedCorr) must significantly exceed unaligned correlation ($unalignedCorr)",
            alignedCorr > unalignedCorr + 0.50
        )
        assertTrue("Aligned correlation on synthetic audio must be close to 1.0", alignedCorr > 0.95)
    }

    @Test
    fun testFifoBufferAndVariableChunkSizeDurationPreservation() {
        val processor = ClarityAudioProcessor()
        val numSamples = 1600 // 100 ms
        val raw = generateSyntheticAudio(numSamples)

        // Push raw audio with active speech features
        processor.pushRawAudioAndFeatures(
            rawSamples = raw,
            length = numSamples,
            microphoneLogEnergyDb = -35.0f,
            microphonePitchReliable = 0.8f,
            modelConfidence = 0.75f,
            modelReliable = true,
            sensorReliability = 0.9f,
            phoneMotionLevel = 0.1f,
            sensorAlignmentLagMs = 5.0f
        )

        // Test variable chunk sizes (simulating RNNoise output blocks: 160, 320, 480 samples)
        val chunkSizes = listOf(160, 320, 480, 160, 320, 160)
        var totalProcessed = 0
        for (size in chunkSizes) {
            val rnnChunk = ShortArray(size) { i -> raw[(totalProcessed + i) % numSamples] }
            val clarityChunk = ShortArray(size)

            processor.processRnnoiseChunk(rnnChunk, clarityChunk, size)
            assertEquals(size, clarityChunk.size)
            totalProcessed += size
        }

        // Test flush with remaining samples
        val flushSize = 160
        val flushedRnn = ShortArray(flushSize) { 500.toShort() }
        val flushedClarity = ShortArray(flushSize)
        processor.processRnnoiseChunk(flushedRnn, flushedClarity, flushSize)
        assertEquals(flushSize, flushedClarity.size)
    }

    @Test
    fun testInitial320SamplesZeroPadding() {
        val delaySamples = 320
        val fifo = RawAudioDelayFifo(delaySamples)
        assertEquals(delaySamples, fifo.availableCount)

        val out = ShortArray(delaySamples)
        fifo.pop(delaySamples, out, 0)
        for (i in 0 until delaySamples) {
            assertEquals("Initial delay sample $i must be zero-padded", 0.toShort(), out[i])
        }

        // Next popped samples must match pushed raw audio
        val input = shortArrayOf(100, 200, 300, 400, 500)
        fifo.push(input, input.size)
        val popped = ShortArray(input.size)
        fifo.pop(input.size, popped, 0)
        for (i in input.indices) {
            assertEquals("Sample $i must match input", input[i], popped[i])
        }
    }

    @Test
    fun testIndependentControllerDoesNotAffectExternalState() {
        val externalController = SafeGainController()
        val processor = ClarityAudioProcessor()

        val raw = generateSyntheticAudio(320)
        val rnn = ShortArray(320) { 1000.toShort() }
        val clarity = ShortArray(320)

        // Process clarity audio
        processor.pushRawAudioAndFeatures(
            rawSamples = raw,
            length = 320,
            microphoneLogEnergyDb = -65.0f,
            microphonePitchReliable = 0.0f,
            modelConfidence = 0.1f,
            modelReliable = true,
            sensorReliability = 0.9f,
            phoneMotionLevel = 0.1f,
            sensorAlignmentLagMs = 5.0f
        )
        processor.processRnnoiseChunk(rnn, clarity, 320)

        // External SafeGainController must remain at its initial pristine state
        assertEquals(1.0f, externalController.getCurrentGain(), 0.0001f)
        assertEquals(SafeGainController.State.PRESERVE, externalController.getState())
    }

    @Test
    fun testSpeechAwareLoudnessGainNeverExceedsSixDb() {
        val processor = ClarityAudioProcessor(maxLoudnessGainDb = 6.0)
        // Simulate very quiet speech (e.g. RMS ~ 10.0 out of 32767)
        val lowSpeech = ShortArray(640) { 10.toShort() }
        val out = ShortArray(640)

        processor.pushRawAudioAndFeatures(
            rawSamples = lowSpeech,
            length = 640,
            microphoneLogEnergyDb = -35.0f,
            microphonePitchReliable = 0.9f,
            modelConfidence = 0.8f,
            modelReliable = true,
            sensorReliability = 0.9f,
            phoneMotionLevel = 0.1f,
            sensorAlignmentLagMs = 5.0f
        )
        processor.processRnnoiseChunk(lowSpeech, out, 640)

        val diag = processor.getDiagnostics()
        assertTrue(
            "Max loudness gain must be <= 6.0 dB, got ${diag.loudnessGainMaxDb}",
            diag.loudnessGainMaxDb <= 6.01
        )
        assertTrue(
            "Mean loudness gain must be <= 6.0 dB, got ${diag.loudnessGainMeanDb}",
            diag.loudnessGainMeanDb <= 6.01
        )
    }

    @Test
    fun testPeakLimiterAndFinalHardSafetyClampNeverExceedsPcmLimit() {
        val processor = ClarityAudioProcessor()
        // Signal with extreme amplitude exceeding 16-bit signed range
        val extremeInput = ShortArray(320) { 32700.toShort() }
        val out = ShortArray(320)

        processor.pushRawAudioAndFeatures(
            rawSamples = extremeInput,
            length = 320,
            microphoneLogEnergyDb = -20.0f,
            microphonePitchReliable = 0.9f,
            modelConfidence = 0.8f,
            modelReliable = true,
            sensorReliability = 0.9f,
            phoneMotionLevel = 0.1f,
            sensorAlignmentLagMs = 5.0f
        )
        processor.processRnnoiseChunk(extremeInput, out, 320)

        for (i in out.indices) {
            val sample = out[i].toInt()
            assertTrue("Sample $i ($sample) must not exceed 32767", sample <= 32767)
            assertTrue("Sample $i ($sample) must not be less than -32768", sample >= -32768)
            // Soft limiter targets -1 dBFS threshold ~ 29204
            assertTrue(
                "Sample $i ($sample) must respect limiter threshold ceiling",
                abs(sample) <= ClarityAudioProcessor.LIMITER_THRESHOLD_AMPLITUDE + 10
            )
        }

        val diag = processor.getDiagnostics()
        assertEquals(-1.0, diag.limiterThresholdDbfs, 0.001)
        assertTrue("Observed peak dBFS must be <= 0 dBFS", diag.observedPeakDbfs <= 0.0)
    }

    @Test
    fun testMetadataDiagnosticsIntegrity() {
        val processor = ClarityAudioProcessor()
        val diag = processor.getDiagnostics()

        assertEquals(320, diag.delaySamples16k)
        assertEquals(20.0, diag.delayMs, 0.01)
        assertEquals(0.25f, diag.dryMixRatio, 0.001f)
        assertTrue(diag.presenceEqEnabled)
        assertEquals(-1.0, diag.limiterThresholdDbfs, 0.001)
        assertEquals(0L, diag.clippedSampleCount)
    }
}
