package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class FeatureExtractionTest {

    @Test
    fun testBandpassPasses140HzSignal() {
        val fs = 400.0
        val filterBank = AccelFilterBank(fs)
        filterBank.reset()

        val freq = 140.0
        var maxOutput = 0.0

        // Process 400 samples (1.0 second) of synthetic 140 Hz sine wave
        for (i in 0 until 400) {
            val t = i / fs
            val s = sin(2.0 * Math.PI * freq * t).toFloat()
            val out = filterBank.process(s, s, s)

            // Measure steady-state amplitude after filter warmup (first 100 samples)
            if (i >= 100) {
                val absBp = abs(out.bpZ.toDouble())
                if (absBp > maxOutput) {
                    maxOutput = absBp
                }
            }
        }

        // Expected theoretical gain at 140 Hz is ~0.989. Verify actual gain > 0.85 (-1.4 dB)
        assertTrue("Bandpass should pass 140 Hz vocal fundamental (measured: $maxOutput)", maxOutput > 0.85)
    }

    @Test
    fun testBandpassStronglyAttenuates10HzMotionSignal() {
        val fs = 400.0
        val filterBank = AccelFilterBank(fs)
        filterBank.reset()

        val freq = 10.0
        var maxOutput = 0.0

        for (i in 0 until 400) {
            val t = i / fs
            val s = sin(2.0 * Math.PI * freq * t).toFloat()
            val out = filterBank.process(s, s, s)

            if (i >= 100) {
                val absBp = abs(out.bpZ.toDouble())
                if (absBp > maxOutput) {
                    maxOutput = absBp
                }
            }
        }

        // Expected theoretical gain at 10 Hz is ~0.0117 (-38.6 dB). Verify actual gain < 0.05 (>26 dB rejection)
        assertTrue("Bandpass should reject 10 Hz hand motion (measured: $maxOutput)", maxOutput < 0.05)
    }

    @Test
    fun testBandpassAttenuatesOutsideTargetBand() {
        val fs = 400.0
        val filterBank = AccelFilterBank(fs)

        // 1. Check 5 Hz low-frequency drift
        filterBank.reset()
        var max5Hz = 0.0
        for (i in 0 until 400) {
            val t = i / fs
            val s = sin(2.0 * Math.PI * 5.0 * t).toFloat()
            val out = filterBank.process(s, s, s)
            if (i >= 100 && abs(out.bpZ.toDouble()) > max5Hz) {
                max5Hz = abs(out.bpZ.toDouble())
            }
        }
        assertTrue("Bandpass should reject 5 Hz (measured: $max5Hz)", max5Hz < 0.02)

        // 2. Check 195 Hz near Nyquist
        filterBank.reset()
        var max195Hz = 0.0
        for (i in 0 until 400) {
            val t = i / fs
            val s = sin(2.0 * Math.PI * 195.0 * t).toFloat()
            val out = filterBank.process(s, s, s)
            if (i >= 100 && abs(out.bpZ.toDouble()) > max195Hz) {
                max195Hz = abs(out.bpZ.toDouble())
            }
        }
        assertTrue("Bandpass should attenuate 195 Hz near Nyquist (measured: $max195Hz)", max195Hz < 0.20)
    }

    @Test
    fun testMicrophonePitchEstimatorSynthetic130Hz() {
        val estimator = PitchEstimator()
        val sampleRate = 16000.0
        val numSamples = 2048 // 128 ms
        val targetFreq = 130.0
        val audio = ShortArray(numSamples)

        for (i in 0 until numSamples) {
            val t = i / sampleRate
            val s = 0.6 * sin(2.0 * Math.PI * targetFreq * t)
            audio[i] = (s * 32767.0).toInt().toShort()
        }

        val result = estimator.estimatePitch(audio, numSamples, sampleRate)
        assertTrue("Pitch estimation must be reliable for clean 130 Hz tone", result.isReliable)
        assertTrue("Pitch strength must be high (measured: ${result.pitchStrength})", result.pitchStrength > 0.85)
        assertEquals(130.0, result.pitchHz, 2.0)
    }

    @Test
    fun testMicrophoneSilenceProducesUnreliablePitch() {
        val estimator = PitchEstimator()
        val silentAudio = ShortArray(2048) { 0 }

        val result = estimator.estimatePitch(silentAudio, silentAudio.size, 16000.0)
        assertFalse("Silent audio must be marked unreliable", result.isReliable)
        assertEquals(0.0, result.pitchHz, 0.0001)
        assertEquals(0.0, result.pitchStrength, 0.0001)
    }

    @Test
    fun testAccelerometerPeakEstimatorSynthetic134Hz() {
        val analyzer = AccelSpectralAnalyzer()
        val fs = 400.0
        val numSamples = 100 // 250 ms
        val targetFreq = 134.0

        val samples = (0 until numSamples).map { i ->
            val t = i / fs
            val s = (0.05 * sin(2.0 * Math.PI * targetFreq * t)).toFloat()
            FilteredAccelSample(
                timestampNs = (i * 2_500_000L),
                rawX = 0f, rawY = 0f, rawZ = 9.8f + s,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = 0f, bpZ = s
            )
        }

        val result = analyzer.analyzeWindow(
            samples = samples,
            phoneMotionLevel = 0.02,
            sensorReliability = 1.0,
            alignmentLagMs = 2.5,
            isFilterWarmedUp = true
        )

        assertEquals("Z", result.bestAxis)
        assertTrue("134 Hz tone on Z axis must be reliable", result.isReliable)
        assertEquals(134.0, result.peakHz, 3.0)
        assertTrue("Prominence must be high for pure tone (measured: ${result.prominence})", result.prominence > 2.5)
    }

    @Test
    fun testAccelerometerStrongestAxisSelection() {
        val analyzer = AccelSpectralAnalyzer()
        val fs = 400.0
        val numSamples = 100

        // Strong 140 Hz vibration on Y axis, small noise on X and Z
        val samples = (0 until numSamples).map { i ->
            val t = i / fs
            val sY = (0.08 * sin(2.0 * Math.PI * 140.0 * t)).toFloat()
            val sZ = (0.005 * sin(2.0 * Math.PI * 140.0 * t)).toFloat()
            FilteredAccelSample(
                timestampNs = (i * 2_500_000L),
                rawX = 0f, rawY = sY, rawZ = 9.8f + sZ,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = sY, bpZ = sZ
            )
        }

        val result = analyzer.analyzeWindow(
            samples = samples,
            phoneMotionLevel = 0.02,
            sensorReliability = 1.0,
            alignmentLagMs = 2.5,
            isFilterWarmedUp = true
        )

        assertEquals("Y", result.bestAxis)
        assertEquals("Y", result.bestAxis)
        assertEquals(140.0, result.peakHz, 3.0)
    }

    @Test
    fun testPitchAgreementMatchingTones() {
        val extractor = FeatureExtractor()
        val sampleRate = 16000.0
        val numSamples = 2048

        // Audio: 130 Hz tone
        val audio = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i / sampleRate
            audio[i] = (0.5 * sin(2.0 * Math.PI * 130.0 * t) * 32767.0).toInt().toShort()
        }

        // Accel: 134 Hz tone (4 Hz difference)
        val fs = 400.0
        val targetNs = 250_000_000L
        val accel100 = (0 until 40).map { i ->
            val t = i / fs
            val s = (0.04 * sin(2.0 * Math.PI * 134.0 * t)).toFloat()
            FilteredAccelSample(
                timestampNs = targetNs - (39 - i) * 2_500_000L,
                rawX = 0f, rawY = 0f, rawZ = 9.8f,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = 0f, bpZ = s
            )
        }
        val accel250 = (0 until 100).map { i ->
            val t = i / fs
            val s = (0.04 * sin(2.0 * Math.PI * 134.0 * t)).toFloat()
            FilteredAccelSample(
                timestampNs = targetNs - (99 - i) * 2_500_000L,
                rawX = 0f, rawY = 0f, rawZ = 9.8f,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = 0f, bpZ = s
            )
        }

        val feat = extractor.extractFeatures(
            audioSamples = audio,
            numAudioSamples = numSamples,
            audioWindowStartMs = 0.0,
            audioWindowCenterMs = 64.0,
            audioWindowEndMs = 128.0,
            targetAudioTimestampNs = targetNs,
            accelWindow100ms = accel100,
            accelWindow250ms = accel250,
            isFilterWarmedUp = true
        )

        assertEquals(1, feat.microphonePitchReliable)
        assertEquals(1, feat.accelPeakReliable)
        assertTrue("Pitch difference must be approximately 4 Hz (measured: ${feat.pitchDifferenceHz})", feat.pitchDifferenceHz < 6.0)
        assertTrue("Agreement score must be high for 4 Hz difference (measured: ${feat.pitchAgreementScore})", feat.pitchAgreementScore > 0.80)
        assertEquals(1, feat.pitchAgreementReliable)
    }

    @Test
    fun testPitchAgreementMismatchedTones() {
        val extractor = FeatureExtractor()
        val sampleRate = 16000.0
        val numSamples = 2048

        // Audio: 100 Hz tone
        val audio = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i / sampleRate
            audio[i] = (0.5 * sin(2.0 * Math.PI * 100.0 * t) * 32767.0).toInt().toShort()
        }

        // Accel: 170 Hz tone (70 Hz difference)
        val fs = 400.0
        val targetNs = 250_000_000L
        val accel100 = (0 until 40).map { i ->
            val t = i / fs
            val s = (0.04 * sin(2.0 * Math.PI * 170.0 * t)).toFloat()
            FilteredAccelSample(
                timestampNs = targetNs - (39 - i) * 2_500_000L,
                rawX = 0f, rawY = 0f, rawZ = 9.8f,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = 0f, bpZ = s
            )
        }
        val accel250 = (0 until 100).map { i ->
            val t = i / fs
            val s = (0.04 * sin(2.0 * Math.PI * 170.0 * t)).toFloat()
            FilteredAccelSample(
                timestampNs = targetNs - (99 - i) * 2_500_000L,
                rawX = 0f, rawY = 0f, rawZ = 9.8f,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = 0f, bpZ = s
            )
        }

        val feat = extractor.extractFeatures(
            audioSamples = audio,
            numAudioSamples = numSamples,
            audioWindowStartMs = 0.0,
            audioWindowCenterMs = 64.0,
            audioWindowEndMs = 128.0,
            targetAudioTimestampNs = targetNs,
            accelWindow100ms = accel100,
            accelWindow250ms = accel250,
            isFilterWarmedUp = true
        )

        assertEquals(1, feat.microphonePitchReliable)
        assertEquals(1, feat.accelPeakReliable)
        assertTrue("Pitch difference must be large (measured: ${feat.pitchDifferenceHz})", feat.pitchDifferenceHz > 60.0)
        assertTrue("Agreement score must be near zero for 70 Hz mismatch (measured: ${feat.pitchAgreementScore})", feat.pitchAgreementScore < 0.05)
        assertEquals(0, feat.pitchAgreementReliable)
    }

    @Test
    fun testWeakNoiseProducesUnreliablePeak() {
        val analyzer = AccelSpectralAnalyzer()
        val numSamples = 100

        // Very weak pseudo-random noise
        var state = 123456789L
        val samples = (0 until numSamples).map { i ->
            state = (state * 1103515245L + 12345L) and 0x7fffffffL
            val noise = ((state % 1000) / 1000.0 - 0.5) * 1e-6 // tiny noise
            FilteredAccelSample(
                timestampNs = i * 2_500_000L,
                rawX = 0f, rawY = 0f, rawZ = 9.8f,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = noise.toFloat(), bpY = noise.toFloat(), bpZ = noise.toFloat()
            )
        }

        val result = analyzer.analyzeWindow(
            samples = samples,
            phoneMotionLevel = 0.01,
            sensorReliability = 1.0,
            alignmentLagMs = 2.5,
            isFilterWarmedUp = true
        )

        assertFalse("Weak random noise must be marked unreliable", result.isReliable)
        assertEquals(0.0, result.peakHz, 0.0001)
    }

    @Test
    fun test250msRollingBufferEviction() {
        val buffer = RollingAccelBuffer(windowDurationNs = RollingAccelBuffer.DEFAULT_WINDOW_DURATION_NS) // 350 ms

        // Add 160 samples at 2.5 ms intervals (400 ms total)
        for (i in 0 until 160) {
            val ts = i * 2_500_000L
            buffer.add(
                FilteredAccelSample(
                    timestampNs = ts,
                    rawX = 0f, rawY = 0f, rawZ = 9.8f,
                    lowX = 0f, lowY = 0f, lowZ = 9.8f,
                    bpX = 0f, bpY = 0f, bpZ = 0f
                )
            )
        }

        val lastTs = 159 * 2_500_000L
        val snap100 = buffer.getSnapshot100msEndingAt(lastTs)
        val snap250 = buffer.getSnapshot250msEndingAt(lastTs)

        assertTrue("100 ms snapshot must contain ~40-41 samples", snap100.size in 40..42)
        assertTrue("250 ms snapshot must contain ~100-102 samples", snap250.size in 100..102)
        assertEquals(lastTs, snap100.last().timestampNs)
        assertEquals(lastTs, snap250.last().timestampNs)
    }

    @Test
    fun testAlignedSnapshotExcludesFutureSamples() {
        val buffer = RollingAccelBuffer()

        // Insert samples up to 200 ms
        for (i in 0 until 80) { // 80 * 2.5 ms = 200 ms
            buffer.add(
                FilteredAccelSample(
                    timestampNs = i * 2_500_000L,
                    rawX = 0f, rawY = 0f, rawZ = 9.8f,
                    lowX = 0f, lowY = 0f, lowZ = 9.8f,
                    bpX = 0f, bpY = 0f, bpZ = 0f
                )
            )
        }

        // Request snapshot ending at 120 ms (index 48)
        val targetTs = 48 * 2_500_000L // 120 ms
        val snap = buffer.getSnapshotEndingAt(targetTs, durationNs = 100_000_000L)

        assertTrue("Snapshot must not be empty", snap.isNotEmpty())
        for (s in snap) {
            assertTrue("Sample timestamp must be <= targetTimestampNs (no future samples)", s.timestampNs <= targetTs)
        }
        assertEquals(targetTs, snap.last().timestampNs)
    }

    @Test
    fun testMonotonicAudioWindowTimestamps() {
        val sampleRate = 16000.0
        val blockSize = 2048

        for (block in 0 until 5) {
            val startSample = block * blockSize
            val endSample = startSample + blockSize
            val startMs = (startSample * 1000.0) / sampleRate
            val endMs = (endSample * 1000.0) / sampleRate
            val centerMs = (startMs + endMs) / 2.0

            assertTrue("Start time must be strictly less than center time", startMs < centerMs)
            assertTrue("Center time must be strictly less than end time", centerMs < endMs)
            if (block > 0) {
                val prevEndMs = ((block * blockSize) * 1000.0) / sampleRate
                assertEquals(prevEndMs, startMs, 0.0001)
            }
        }
    }

    @Test
    fun testSensorAlignmentLagPenalty() {
        val extractor = FeatureExtractor()
        val dummyAudio = ShortArray(2048) { 100 }
        val targetNs = 200_000_000L

        // Provide samples that ended 30 ms before the target (30 ms alignment lag, above 15 ms limit)
        val staleSamples = (0 until 40).map { i ->
            FilteredAccelSample(
                timestampNs = (170_000_000L - (39 - i) * 2_500_000L),
                rawX = 0f, rawY = 0f, rawZ = 9.8f,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = 0f, bpZ = 0f
            )
        }

        val feat = extractor.extractFeatures(
            audioSamples = dummyAudio,
            numAudioSamples = dummyAudio.size,
            audioWindowStartMs = 72.0,
            audioWindowCenterMs = 136.0,
            audioWindowEndMs = 200.0,
            targetAudioTimestampNs = targetNs,
            accelWindow100ms = staleSamples,
            accelWindow250ms = staleSamples,
            isFilterWarmedUp = true
        )

        assertTrue("Alignment lag must be approximately 30 ms (measured: ${feat.sensorAlignmentLagMs})", feat.sensorAlignmentLagMs in 29.0..31.0)
        assertTrue("Sensor reliability must be penalized when alignment lag is high", feat.sensorReliability < 0.6)
    }

    @Test
    fun testReliabilityPenaltiesForShortageAndGaps() {
        val extractor = FeatureExtractor()
        val dummyAudio = ShortArray(1600)

        val samples = (0 until 40).map { i ->
            FilteredAccelSample(
                timestampNs = i * 2_500_000L,
                rawX = 0f, rawY = 0f, rawZ = 9.8f,
                lowX = 0f, lowY = 0f, lowZ = 9.8f,
                bpX = 0f, bpY = 0f, bpZ = 0f
            )
        }

        val featWarmup = extractor.extractFeatures(
            audioSamples = dummyAudio,
            numAudioSamples = dummyAudio.size,
            audioWindowStartMs = 0.0,
            audioWindowCenterMs = 50.0,
            audioWindowEndMs = 100.0,
            targetAudioTimestampNs = 100_000_000L,
            accelWindow100ms = samples,
            accelWindow250ms = samples,
            isFilterWarmedUp = false
        )
        assertTrue("Reliability must be low during warmup", featWarmup.sensorReliability <= 0.10)

        val fewSamples = samples.take(10)
        val featFew = extractor.extractFeatures(
            audioSamples = dummyAudio,
            numAudioSamples = dummyAudio.size,
            audioWindowStartMs = 0.0,
            audioWindowCenterMs = 50.0,
            audioWindowEndMs = 100.0,
            targetAudioTimestampNs = 100_000_000L,
            accelWindow100ms = fewSamples,
            accelWindow250ms = fewSamples,
            isFilterWarmedUp = true
        )
        assertTrue("Reliability must be penalized for sample shortage", featFew.sensorReliability < 0.5)

        val gapSamples = mutableListOf<FilteredAccelSample>()
        var curTs = 0L
        for (i in 0 until 35) {
            gapSamples.add(
                FilteredAccelSample(
                    timestampNs = curTs,
                    rawX = 0f, rawY = 0f, rawZ = 9.8f,
                    lowX = 0f, lowY = 0f, lowZ = 9.8f,
                    bpX = 0f, bpY = 0f, bpZ = 0f
                )
            )
            curTs += if (i == 10) 12_000_000L else 2_500_000L // 12 ms gap at sample 10
        }

        val featGap = extractor.extractFeatures(
            audioSamples = dummyAudio,
            numAudioSamples = dummyAudio.size,
            audioWindowStartMs = 0.0,
            audioWindowCenterMs = 50.0,
            audioWindowEndMs = 100.0,
            targetAudioTimestampNs = curTs,
            accelWindow100ms = gapSamples,
            accelWindow250ms = gapSamples,
            isFilterWarmedUp = true
        )
        assertTrue("Reliability must be penalized for large timestamp gaps", featGap.sensorReliability < 0.6)
    }

    @Test
    fun testFeatureCalculationsDoNotProduceNaNOrInfinity() {
        val extractor = FeatureExtractor()

        // 1. Completely empty inputs
        val emptyAudio = ShortArray(0)
        val featEmpty = extractor.extractFeatures(
            audioSamples = emptyAudio,
            numAudioSamples = 0,
            audioWindowStartMs = 0.0,
            audioWindowCenterMs = 0.0,
            audioWindowEndMs = 0.0,
            targetAudioTimestampNs = 0L,
            accelWindow100ms = emptyList(),
            accelWindow250ms = emptyList(),
            isFilterWarmedUp = true
        )
        assertFalse(featEmpty.microphoneRms.isNaN())
        assertFalse(featEmpty.microphoneLogEnergyDb.isInfinite())
        assertFalse(featEmpty.microphonePitchHz.isNaN())
        assertFalse(featEmpty.accelerometerBandEnergy.isNaN())
        assertFalse(featEmpty.phoneMotionLevel.isNaN())
        assertFalse(featEmpty.sensorReliability.isNaN())
        assertFalse(featEmpty.contactQuality.isNaN())
        assertFalse(featEmpty.accelPeakHz.isNaN())
        assertFalse(featEmpty.pitchDifferenceHz.isNaN())
        assertFalse(featEmpty.pitchAgreementScore.isNaN())

        // 2. Silence with flat stationary gravity
        val silentAudio = ShortArray(1600) { 0 }
        val stationarySamples = (0 until 40).map { i ->
            FilteredAccelSample(
                timestampNs = i * 2_500_000L,
                rawX = 0f, rawY = 0f, rawZ = 9.80665f,
                lowX = 0f, lowY = 0f, lowZ = 9.80665f,
                bpX = 0f, bpY = 0f, bpZ = 0f
            )
        }
        val featStationary = extractor.extractFeatures(
            audioSamples = silentAudio,
            numAudioSamples = silentAudio.size,
            audioWindowStartMs = 0.0,
            audioWindowCenterMs = 50.0,
            audioWindowEndMs = 100.0,
            targetAudioTimestampNs = 100_000_000L,
            accelWindow100ms = stationarySamples,
            accelWindow250ms = stationarySamples,
            isFilterWarmedUp = true
        )
        assertEquals(0.0, featStationary.microphoneRms, 0.00001)
        assertEquals(-120.0, featStationary.microphoneLogEnergyDb, 0.1)
        assertEquals(0.0, featStationary.accelerometerBandEnergy, 0.00001)
        assertEquals(0.0, featStationary.phoneMotionLevel, 0.00001)
        assertEquals(0.0, featStationary.microphonePitchHz, 0.00001)
        assertEquals(0, featStationary.microphonePitchReliable)
        assertEquals(0, featStationary.accelPeakReliable)
        assertEquals(0.0, featStationary.pitchAgreementScore, 0.00001)
        assertEquals(0, featStationary.pitchAgreementReliable)
        assertTrue("Stationary reliable sensor should have high reliability", featStationary.sensorReliability > 0.9)
    }
}
