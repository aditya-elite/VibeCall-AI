package com.vibecall.sensortest

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class AccelSpectralConfig(
    val nominalRateHz: Double = 400.0,
    val minAnalysisFreqHz: Double = 80.0,
    val maxAnalysisFreqHz: Double = 185.0,
    val minPeakPower: Double = 1e-6,
    val minProminence: Double = 2.5,
    val maxMotionThreshold: Double = 0.50,
    val maxAlignmentLagMs: Double = 15.0,
    val minSensorReliability: Double = 0.70,
    val minSamplesInWindow: Int = 60,
    val fftSize: Int = 256
)

data class AxisSpectralResult(
    val bandRms: Double,
    val peakHz: Double,
    val peakPower: Double,
    val prominence: Double,
    val isReliable: Boolean
)

data class CompositeSpectralResult(
    val xResult: AxisSpectralResult,
    val yResult: AxisSpectralResult,
    val zResult: AxisSpectralResult,
    val bestAxis: String,
    val peakHz: Double,
    val peakPower: Double,
    val prominence: Double,
    val isReliable: Boolean
)

/**
 * Frequency-domain spectral peak analyzer for 3-axis accelerometer data.
 *
 * Analyzes a 250 ms rolling window (~100 samples at 400 Hz) to estimate
 * bone-conducted vocal resonance peaks in the 80-185 Hz fundamental band.
 *
 * NOTE: At 250 ms, the true Rayleigh physical spectral resolution limit is:
 *   delta_f = 1 / 0.25s = 4.0 Hz.
 * Parabolic interpolation across FFT bins refines peak location estimation,
 * but does NOT improve the physical separation of closely spaced frequencies.
 *
 * All thresholds are configurable and explicitly uncalibrated.
 */
class AccelSpectralAnalyzer(val config: AccelSpectralConfig = AccelSpectralConfig()) {

    private val fftSize = config.fftSize
    private val windowBuffer = DoubleArray(fftSize)
    private val realBuffer = DoubleArray(fftSize)
    private val imagBuffer = DoubleArray(fftSize)
    private val powerSpectrum = DoubleArray(fftSize / 2)

    // Precomputed twiddle factors for radix-2 in-place FFT
    private val cosTable = DoubleArray(fftSize / 2)
    private val sinTable = DoubleArray(fftSize / 2)
    private val bitRevTable = IntArray(fftSize)

    init {
        val n = fftSize
        for (i in 0 until n / 2) {
            val angle = -2.0 * Math.PI * i / n
            cosTable[i] = cos(angle)
            sinTable[i] = kotlin.math.sin(angle)
        }
        var j = 0
        for (i in 0 until n) {
            bitRevTable[i] = j
            var bit = n shr 1
            while (bit > 0 && (j and bit) != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
        }
    }

    @Synchronized
    fun analyzeWindow(
        samples: List<FilteredAccelSample>,
        phoneMotionLevel: Double,
        sensorReliability: Double,
        alignmentLagMs: Double,
        isFilterWarmedUp: Boolean
    ): CompositeSpectralResult {
        val m = samples.size
        if (m < 8) {
            val empty = AxisSpectralResult(0.0, 0.0, 0.0, 0.0, false)
            return CompositeSpectralResult(empty, empty, empty, "Z", 0.0, 0.0, 0.0, false)
        }

        // Measure sampling rate over the 250 ms window
        val firstTs = samples.first().timestampNs
        val lastTs = samples.last().timestampNs
        val durationSec = (lastTs - firstTs) / 1e9
        val effectiveFs = if (durationSec > 0) (m - 1) / durationSec else config.nominalRateHz

        val xRes = analyzeSingleAxis(
            samples = samples,
            axisSelector = { it.bpX.toDouble() },
            effectiveFs = effectiveFs,
            phoneMotionLevel = phoneMotionLevel,
            sensorReliability = sensorReliability,
            alignmentLagMs = alignmentLagMs,
            isFilterWarmedUp = isFilterWarmedUp
        )

        val yRes = analyzeSingleAxis(
            samples = samples,
            axisSelector = { it.bpY.toDouble() },
            effectiveFs = effectiveFs,
            phoneMotionLevel = phoneMotionLevel,
            sensorReliability = sensorReliability,
            alignmentLagMs = alignmentLagMs,
            isFilterWarmedUp = isFilterWarmedUp
        )

        val zRes = analyzeSingleAxis(
            samples = samples,
            axisSelector = { it.bpZ.toDouble() },
            effectiveFs = effectiveFs,
            phoneMotionLevel = phoneMotionLevel,
            sensorReliability = sensorReliability,
            alignmentLagMs = alignmentLagMs,
            isFilterWarmedUp = isFilterWarmedUp
        )

        // Select best axis based on highest peak power (falling back to band RMS)
        var bestAxis = "Z"
        var bestRes = zRes
        if (xRes.peakPower > bestRes.peakPower || (bestRes.peakPower == 0.0 && xRes.bandRms > bestRes.bandRms)) {
            bestAxis = "X"
            bestRes = xRes
        }
        if (yRes.peakPower > bestRes.peakPower || (bestRes.peakPower == 0.0 && yRes.bandRms > bestRes.bandRms)) {
            bestAxis = "Y"
            bestRes = yRes
        }

        return CompositeSpectralResult(
            xResult = xRes,
            yResult = yRes,
            zResult = zRes,
            bestAxis = bestAxis,
            peakHz = bestRes.peakHz,
            peakPower = bestRes.peakPower,
            prominence = bestRes.prominence,
            isReliable = bestRes.isReliable
        )
    }

    private fun analyzeSingleAxis(
        samples: List<FilteredAccelSample>,
        axisSelector: (FilteredAccelSample) -> Double,
        effectiveFs: Double,
        phoneMotionLevel: Double,
        sensorReliability: Double,
        alignmentLagMs: Double,
        isFilterWarmedUp: Boolean
    ): AxisSpectralResult {
        val m = samples.size

        // 1. Calculate band RMS on the axis
        var sum = 0.0
        var sumSq = 0.0
        for (i in 0 until m) {
            val v = axisSelector(samples[i])
            windowBuffer[i] = v
            sum += v
            sumSq += v * v
        }
        val bandRms = sqrt(max(0.0, sumSq / m))

        // 2. Remove mean and apply Hann window
        val mean = sum / m
        for (i in 0 until m) {
            val c = windowBuffer[i] - mean
            val hann = 0.5 * (1.0 - cos(2.0 * Math.PI * i / (m - 1)))
            realBuffer[i] = c * hann
            imagBuffer[i] = 0.0
        }
        // Zero pad to fftSize
        for (i in m until fftSize) {
            realBuffer[i] = 0.0
            imagBuffer[i] = 0.0
        }

        // 3. In-place radix-2 real FFT
        computeFft()

        // 4. Power spectrum for positive frequencies
        val numBins = fftSize / 2
        for (k in 0 until numBins) {
            val r = realBuffer[k]
            val im = imagBuffer[k]
            powerSpectrum[k] = (r * r + im * im) / (m * m)
        }

        // 5. Search frequency band [minAnalysisFreqHz, maxAnalysisFreqHz]
        val binWidthHz = effectiveFs / fftSize
        val kMin = max(1, (config.minAnalysisFreqHz / binWidthHz).toInt())
        val kMax = min(numBins - 2, ((config.maxAnalysisFreqHz / binWidthHz) + 1).toInt())

        if (kMin >= kMax) {
            return AxisSpectralResult(bandRms, 0.0, 0.0, 0.0, false)
        }

        var peakBin = -1
        var maxP = -1.0
        for (k in kMin..kMax) {
            val p = powerSpectrum[k]
            if (p > maxP) {
                maxP = p
                peakBin = k
            }
        }

        if (peakBin < 0 || maxP <= 0.0) {
            return AxisSpectralResult(bandRms, 0.0, 0.0, 0.0, false)
        }

        // 6. Parabolic peak interpolation
        var refinedK = peakBin.toDouble()
        var refinedPower = maxP
        if (peakBin > kMin && peakBin < kMax) {
            val alpha = powerSpectrum[peakBin - 1]
            val beta = powerSpectrum[peakBin]
            val gamma = powerSpectrum[peakBin + 1]
            val denom = alpha - 2.0 * beta + gamma
            if (denom != 0.0) {
                val delta = (alpha - gamma) / (2.0 * denom)
                if (delta in -1.0..1.0) {
                    refinedK = peakBin + delta
                    refinedPower = max(0.0, beta - 0.25 * (alpha - gamma) * delta)
                }
            }
        }

        val estimatedFreqHz = refinedK * binWidthHz

        // 7. Spectral Prominence: Peak power divided by mean out-of-peak power in band
        var bgSum = 0.0
        var bgCount = 0
        for (k in kMin..kMax) {
            if (Math.abs(k - peakBin) > 1) {
                bgSum += powerSpectrum[k]
                bgCount++
            }
        }
        val bgMean = if (bgCount > 0) bgSum / bgCount else 1e-12
        val prominence = refinedPower / max(1e-12, bgMean)

        // 8. Strict multi-criteria peak reliability gating:
        // Do not allow silence, high phone motion, warmup transients, or alignment lag to create a trusted peak
        val hasSufficientSamples = m >= config.minSamplesInWindow
        val hasPower = refinedPower >= config.minPeakPower
        val hasProminence = prominence >= config.minProminence
        val isQuietMotion = phoneMotionLevel <= config.maxMotionThreshold
        val isAligned = alignmentLagMs <= config.maxAlignmentLagMs
        val isSensReliable = sensorReliability >= config.minSensorReliability

        val isReliable = (isFilterWarmedUp &&
                hasSufficientSamples &&
                hasPower &&
                hasProminence &&
                isQuietMotion &&
                isAligned &&
                isSensReliable &&
                estimatedFreqHz in config.minAnalysisFreqHz..config.maxAnalysisFreqHz)

        return AxisSpectralResult(
            bandRms = bandRms,
            peakHz = if (isReliable) estimatedFreqHz else 0.0,
            peakPower = refinedPower,
            prominence = prominence,
            isReliable = isReliable
        )
    }

    private fun computeFft() {
        val n = fftSize
        // Bit reversal
        for (i in 0 until n) {
            val j = bitRevTable[i]
            if (i < j) {
                val tempR = realBuffer[i]
                realBuffer[i] = realBuffer[j]
                realBuffer[j] = tempR
                val tempI = imagBuffer[i]
                imagBuffer[i] = imagBuffer[j]
                imagBuffer[j] = tempI
            }
        }

        // Cooley-Tukey decimation-in-time radix-2
        var len = 2
        while (len <= n) {
            val half = len shr 1
            val step = n / len
            for (i in 0 until n step len) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val tr = c * realBuffer[j + half] - s * imagBuffer[j + half]
                    val ti = s * realBuffer[j + half] + c * imagBuffer[j + half]
                    realBuffer[j + half] = realBuffer[j] - tr
                    imagBuffer[j + half] = imagBuffer[j] - ti
                    realBuffer[j] += tr
                    imagBuffer[j] += ti
                    k += step
                }
            }
            len = len shl 1
        }
    }
}
