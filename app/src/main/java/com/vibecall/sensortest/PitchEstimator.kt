package com.vibecall.sensortest

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PitchConfig(
    val minPitchHz: Double = 80.0,
    val maxPitchHz: Double = 190.0,
    val minRmsEnergyFloor: Double = 0.002,
    val minPitchStrength: Double = 0.50
)

data class PitchResult(
    val pitchHz: Double,
    val pitchStrength: Double,
    val isReliable: Boolean
)

/**
 * Lightweight normalized autocorrelation pitch estimator designed for Android.
 * Analyzes speech fundamental frequencies in the 80-190 Hz range.
 *
 * Pre-allocates working buffers to guarantee zero per-frame garbage collection
 * overhead during audio streaming.
 */
class PitchEstimator(val config: PitchConfig = PitchConfig()) {

    companion object {
        private const val MAX_BUFFER_SIZE = 4096
    }

    private val centered = DoubleArray(MAX_BUFFER_SIZE)

    /**
     * Estimates fundamental frequency from a window of 16-bit PCM audio samples.
     */
    @Synchronized
    fun estimatePitch(
        audioSamples: ShortArray,
        numSamples: Int,
        sampleRateHz: Double = 16000.0,
        precomputedRms: Double? = null
    ): PitchResult {
        val n = min(numSamples, min(audioSamples.size, MAX_BUFFER_SIZE))
        if (n < 256) {
            return PitchResult(0.0, 0.0, false)
        }

        // 1. Energy floor check: silence must never produce a fake pitch
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until n) {
            val s = audioSamples[i] / 32768.0
            centered[i] = s
            sum += s
            sumSq += s * s
        }

        val rms = precomputedRms ?: sqrt(sumSq / n)
        if (rms < config.minRmsEnergyFloor) {
            return PitchResult(0.0, 0.0, false)
        }

        // 2. Remove audio window DC mean
        val mean = sum / n
        var centeredE0 = 0.0
        for (i in 0 until n) {
            val c = centered[i] - mean
            centered[i] = c
            centeredE0 += c * c
        }

        if (centeredE0 <= 1e-12) {
            return PitchResult(0.0, 0.0, false)
        }

        // 3. Define lag bounds for 80 - 190 Hz
        val tauMin = max(1, (sampleRateHz / config.maxPitchHz).toInt()) // ~84 @ 16kHz
        val tauMax = min(n / 2, ((sampleRateHz / config.minPitchHz) + 1).toInt()) // ~200 @ 16kHz

        if (tauMin >= tauMax || tauMax >= n) {
            return PitchResult(0.0, 0.0, false)
        }

        val corrLength = n - tauMax // correlation window length

        // Compute base energy for correlation window
        var baseEnergy = 0.0
        for (i in 0 until corrLength) {
            baseEnergy += centered[i] * centered[i]
        }

        if (baseEnergy <= 1e-12) {
            return PitchResult(0.0, 0.0, false)
        }

        var bestTau = -1
        var maxNacf = -1.0
        val nacfValues = DoubleArray(tauMax - tauMin + 1)

        for (tau in tauMin..tauMax) {
            var crossSum = 0.0
            var lagEnergy = 0.0
            for (i in 0 until corrLength) {
                val s0 = centered[i]
                val sTau = centered[i + tau]
                crossSum += s0 * sTau
                lagEnergy += sTau * sTau
            }

            val denom = sqrt(baseEnergy * lagEnergy) + 1e-12
            val nacf = crossSum / denom
            val idx = tau - tauMin
            nacfValues[idx] = nacf

            if (nacf > maxNacf) {
                maxNacf = nacf
                bestTau = tau
            }
        }

        if (bestTau <= 0) {
            return PitchResult(0.0, 0.0, false)
        }

        // 4. Parabolic interpolation around peak to refine fractional lag
        var refinedTau = bestTau.toDouble()
        val bestIdx = bestTau - tauMin
        if (bestIdx > 0 && bestIdx < nacfValues.size - 1) {
            val alpha = nacfValues[bestIdx - 1]
            val beta = nacfValues[bestIdx]
            val gamma = nacfValues[bestIdx + 1]
            val denom = alpha - 2.0 * beta + gamma
            if (denom != 0.0) {
                val delta = (alpha - gamma) / (2.0 * denom)
                if (delta in -1.0..1.0) {
                    refinedTau += delta
                }
            }
        }

        val estimatedPitchHz = if (refinedTau > 0.0) sampleRateHz / refinedTau else 0.0
        val strength = maxNacf.coerceIn(0.0, 1.0)
        val isReliable = (strength >= config.minPitchStrength &&
                estimatedPitchHz in config.minPitchHz..config.maxPitchHz)

        return PitchResult(
            pitchHz = if (isReliable) estimatedPitchHz else 0.0,
            pitchStrength = strength,
            isReliable = isReliable
        )
    }
}
