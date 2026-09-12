package com.vibecall.sensortest

import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class FeatureConfig(
    val nominalSensorRateHz: Double = 400.0,
    val expectedWindowSamples: Int = 40,
    val minReliableSamples: Int = 25,
    val maxAllowedGapSec: Double = 0.006, // 6 ms (nominal 2.5 ms)
    val motionPenaltyThreshold: Double = 1.0, // m/s^2
    val experimentalVibScale: Double = 0.5, // experimental heuristic vibration scaling
    val experimentalMotionScale: Double = 1.5 // experimental heuristic motion scaling
)

data class WindowFeatures(
    val audioRelativeTimeMs: Double,
    val microphoneRms: Double,
    val microphoneLogEnergyDb: Double,
    val accelerometerBandEnergy: Double,
    val accelerometerBandRms: Double,
    val phoneMotionLevel: Double,
    val sensorSampleCount: Int,
    val sensorRateHz: Double,
    val sensorReliability: Double,
    val contactQuality: Double
) {
    fun toCsvRow(): String {
        return String.format(
            Locale.US,
            "%.2f,%.6f,%.2f,%.6f,%.6f,%.6f,%d,%.2f,%.4f,%.4f",
            audioRelativeTimeMs,
            microphoneRms,
            microphoneLogEnergyDb,
            accelerometerBandEnergy,
            accelerometerBandRms,
            phoneMotionLevel,
            sensorSampleCount,
            sensorRateHz,
            sensorReliability,
            contactQuality
        )
    }

    companion object {
        const val CSV_HEADER = "audio_relative_time_ms,microphone_rms,microphone_log_energy_db,accelerometer_band_energy,accelerometer_band_rms,phone_motion_level,sensor_sample_count,sensor_rate_hz,sensor_reliability,contact_quality"
    }
}

/**
 * Feature extractor computing synchronized audio and rolling accelerometer features.
 * All computations are mathematically bounded and verified against NaN/Infinity.
 */
class FeatureExtractor(val config: FeatureConfig = FeatureConfig()) {

    fun extractFeatures(
        audioSamples: ShortArray,
        numAudioSamples: Int,
        audioRelativeTimeMs: Double,
        accelWindow: List<FilteredAccelSample>,
        isFilterWarmedUp: Boolean
    ): WindowFeatures {
        // 1. Microphone Features
        var micSumSq = 0.0
        val nAudio = max(1, numAudioSamples)
        for (i in 0 until min(numAudioSamples, audioSamples.size)) {
            val norm = audioSamples[i] / 32768.0
            micSumSq += norm * norm
        }
        val micRms = safeValue(sqrt(micSumSq / nAudio))
        val micLogEnergyDb = safeValue(20.0 * log10(max(1e-6, micRms)))

        // 2. Accelerometer Sample Count & Timing Features
        val m = accelWindow.size
        var sensorRateHz = 0.0
        var maxGapSec = 0.0

        if (m >= 2) {
            val firstTs = accelWindow.first().timestampNs
            val lastTs = accelWindow.last().timestampNs
            val durationSec = (lastTs - firstTs) / 1e9
            if (durationSec > 0) {
                sensorRateHz = safeValue((m - 1).toDouble() / durationSec)
            }
            for (i in 1 until m) {
                val gapSec = (accelWindow[i].timestampNs - accelWindow[i - 1].timestampNs) / 1e9
                if (gapSec > maxGapSec) {
                    maxGapSec = gapSec
                }
            }
        }

        // 3. Accelerometer Bandpass Energy & RMS (Vocal Vibration)
        var bpEnergySum = 0.0
        var lowMagSum = 0.0
        var lowMagSqSum = 0.0

        if (m > 0) {
            for (i in 0 until m) {
                val s = accelWindow[i]
                // Vibration band energy
                val bpEnergy = (s.bpX * s.bpX) + (s.bpY * s.bpY) + (s.bpZ * s.bpZ)
                bpEnergySum += bpEnergy

                // Low-frequency motion magnitude (gravity + slow hand motion)
                val lowMag = sqrt((s.lowX * s.lowX) + (s.lowY * s.lowY) + (s.lowZ * s.lowZ).toDouble())
                lowMagSum += lowMag
                lowMagSqSum += lowMag * lowMag
            }
        }

        val accelBandEnergy = if (m > 0) safeValue(bpEnergySum / m) else 0.0
        val accelBandRms = safeValue(sqrt(accelBandEnergy))

        // 4. Phone Motion Level: standard deviation of low-frequency acceleration magnitude
        var phoneMotionLevel = 0.0
        if (m > 1) {
            val meanLow = lowMagSum / m
            val varLow = max(0.0, (lowMagSqSum / m) - (meanLow * meanLow))
            phoneMotionLevel = safeValue(sqrt(varLow))
        }

        // 5. Sensor Reliability Calculation
        var reliability = 1.0

        // Penalty A: Filter startup warmup transient exclusion
        if (!isFilterWarmedUp) {
            reliability *= 0.1
        }

        // Penalty B: Sample shortage in 100 ms window
        if (m < config.minReliableSamples) {
            val sampleFactor = (m.toDouble() / config.minReliableSamples.toDouble()).coerceIn(0.0, 1.0)
            reliability *= sampleFactor
        }

        // Penalty C: Excessive timing gaps
        if (maxGapSec > config.maxAllowedGapSec) {
            // Drops to 0 if gap exceeds 15 ms
            val gapPenalty = (1.0 - (maxGapSec - config.maxAllowedGapSec) / 0.009).coerceIn(0.0, 1.0)
            reliability *= gapPenalty
        }

        // Penalty D: Rate deviation from nominal
        if (sensorRateHz > 0) {
            val rateRatio = sensorRateHz / config.nominalSensorRateHz
            if (rateRatio < 0.85 || rateRatio > 1.15) {
                val ratePenalty = max(0.0, 1.0 - Math.abs(1.0 - rateRatio) * 2.0)
                reliability *= ratePenalty
            }
        } else if (m > 0) {
            reliability *= 0.5
        } else {
            reliability = 0.0
        }

        // Penalty E: High phone motion disrupts contact vibration confidence
        if (phoneMotionLevel > config.motionPenaltyThreshold) {
            val motionFactor = max(0.0, 1.0 - (phoneMotionLevel - config.motionPenaltyThreshold) / 2.0)
            reliability *= motionFactor
        }

        val safeReliability = safeValue(reliability.coerceIn(0.0, 1.0))

        // 6. Contact Quality Heuristic (Explicitly Experimental)
        val normVib = (accelBandRms / config.experimentalVibScale).coerceIn(0.0, 1.0)
        val motionQuietness = (1.0 - (phoneMotionLevel / config.experimentalMotionScale)).coerceIn(0.0, 1.0)
        val contactQuality = safeValue((safeReliability * normVib * motionQuietness).coerceIn(0.0, 1.0))

        return WindowFeatures(
            audioRelativeTimeMs = safeValue(audioRelativeTimeMs),
            microphoneRms = micRms,
            microphoneLogEnergyDb = micLogEnergyDb,
            accelerometerBandEnergy = accelBandEnergy,
            accelerometerBandRms = accelBandRms,
            phoneMotionLevel = phoneMotionLevel,
            sensorSampleCount = m,
            sensorRateHz = sensorRateHz,
            sensorReliability = safeReliability,
            contactQuality = contactQuality
        )
    }

    private fun safeValue(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }
}
