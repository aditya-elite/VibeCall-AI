package com.vibecall.sensortest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin
import kotlin.math.sqrt

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
                val absBp = Math.abs(out.bpZ.toDouble())
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

        // Process 400 samples of synthetic 10 Hz motion sine wave
        for (i in 0 until 400) {
            val t = i / fs
            val s = sin(2.0 * Math.PI * freq * t).toFloat()
            val out = filterBank.process(s, s, s)

            if (i >= 100) {
                val absBp = Math.abs(out.bpZ.toDouble())
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
            if (i >= 100 && Math.abs(out.bpZ.toDouble()) > max5Hz) {
                max5Hz = Math.abs(out.bpZ.toDouble())
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
            if (i >= 100 && Math.abs(out.bpZ.toDouble()) > max195Hz) {
                max195Hz = Math.abs(out.bpZ.toDouble())
            }
        }
        assertTrue("Bandpass should attenuate 195 Hz near Nyquist (measured: $max195Hz)", max195Hz < 0.20)
    }

    @Test
    fun testRollingBufferRemovesExpiredSamplesCorrectly() {
        val buffer = RollingAccelBuffer(windowDurationNs = 100_000_000L) // 100 ms

        // Add 60 samples at 2.5 ms intervals (150 ms total)
        for (i in 0 until 60) {
            val ts = i * 2_500_000L // 2.5 ms step
            buffer.add(
                FilteredAccelSample(
                    timestampNs = ts,
                    rawX = 0f, rawY = 0f, rawZ = 9.8f,
                    lowX = 0f, lowY = 0f, lowZ = 9.8f,
                    bpX = 0f, bpY = 0f, bpZ = 0f
                )
            )
        }

        val snapshot = buffer.getSnapshot()
        // With 100 ms window and 2.5 ms interval, at sample index 59 (ts = 147.5 ms),
        // samples with ts < 47.5 ms are evicted. Samples in window: indices 19 to 59 = 41 samples.
        assertTrue("Buffer should retain approximately 40-41 samples in 100 ms", snapshot.size in 40..42)
        assertEquals(59 * 2_500_000L, snapshot.last().timestampNs)
        assertTrue(snapshot.first().timestampNs >= (59 * 2_500_000L - 100_000_000L))
    }

    @Test
    fun testReliabilityPenaltiesForShortageAndGaps() {
        val extractor = FeatureExtractor()
        val dummyAudio = ShortArray(1600) // 100 ms of audio

        // 1. Warmup penalty test: filter not warmed up -> reliability penalized
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
            audioRelativeTimeMs = 100.0,
            accelWindow = samples,
            isFilterWarmedUp = false
        )
        assertTrue("Reliability must be low during warmup", featWarmup.sensorReliability <= 0.10)

        // 2. Sample shortage test: only 10 samples instead of 40
        val fewSamples = samples.take(10)
        val featFew = extractor.extractFeatures(
            audioSamples = dummyAudio,
            numAudioSamples = dummyAudio.size,
            audioRelativeTimeMs = 100.0,
            accelWindow = fewSamples,
            isFilterWarmedUp = true
        )
        assertTrue("Reliability must be penalized for sample shortage", featFew.sensorReliability < 0.5)

        // 3. Gap penalty test: sample interval has a 12 ms gap (nominal 2.5 ms)
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
            audioRelativeTimeMs = 100.0,
            accelWindow = gapSamples,
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
            audioRelativeTimeMs = 0.0,
            accelWindow = emptyList(),
            isFilterWarmedUp = true
        )
        assertFalse(featEmpty.microphoneRms.isNaN())
        assertFalse(featEmpty.microphoneLogEnergyDb.isInfinite())
        assertFalse(featEmpty.accelerometerBandEnergy.isNaN())
        assertFalse(featEmpty.phoneMotionLevel.isNaN())
        assertFalse(featEmpty.sensorReliability.isNaN())
        assertFalse(featEmpty.contactQuality.isNaN())

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
            audioRelativeTimeMs = 100.0,
            accelWindow = stationarySamples,
            isFilterWarmedUp = true
        )
        assertEquals(0.0, featStationary.microphoneRms, 0.00001)
        assertEquals(-120.0, featStationary.microphoneLogEnergyDb, 0.1) // 20*log10(1e-6) = -120 dB
        assertEquals(0.0, featStationary.accelerometerBandEnergy, 0.00001)
        assertEquals(0.0, featStationary.phoneMotionLevel, 0.00001)
        assertTrue("Stationary reliable sensor should have high reliability", featStationary.sensorReliability > 0.9)
    }
}
