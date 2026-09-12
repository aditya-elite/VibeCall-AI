package com.vibecall.sensortest

import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
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
    val maxAlignmentLagMs: Double = 15.0, // ms
    val minPitchRmsFloor: Double = 0.002,
    val minPitchStrength: Double = 0.50,
    val minAccelPeakPower: Double = 1e-6,
    val minAccelProminence: Double = 2.5,
    val maxMotionForPeak: Double = 0.50,
    val agreementSigmaHz: Double = 8.0,
    val maxAgreementDeltaHz: Double = 10.0,
    val experimentalVibScale: Double = 0.5,
    val experimentalMotionScale: Double = 1.5
)

data class WindowFeatures(
    val audioRelativeTimeMs: Double,
    val audioWindowStartMs: Double,
    val audioWindowCenterMs: Double,
    val audioWindowEndMs: Double,
    val sensorAlignmentLagMs: Double,
    val microphoneRms: Double,
    val microphoneLogEnergyDb: Double,
    val microphonePitchHz: Double,
    val microphonePitchStrength: Double,
    val microphonePitchReliable: Int,
    val accelerometerBandEnergy: Double,
    val accelerometerBandRms: Double,
    val phoneMotionLevel: Double,
    val sensorSampleCount: Int,
    val sensorRateHz: Double,
    val sensorReliability: Double,
    val contactQuality: Double,
    val accelXBandRms: Double,
    val accelYBandRms: Double,
    val accelZBandRms: Double,
    val accelXPeakHz: Double,
    val accelYPeakHz: Double,
    val accelZPeakHz: Double,
    val accelXPeakPower: Double,
    val accelYPeakPower: Double,
    val accelZPeakPower: Double,
    val accelBestAxis: String,
    val accelPeakHz: Double,
    val accelPeakPower: Double,
    val accelPeakProminence: Double,
    val accelPeakReliable: Int,
    val pitchDifferenceHz: Double,
    val pitchAgreementScore: Double,
    val pitchAgreementReliable: Int
) {
    fun toCsvRow(): String {
        return String.format(
            Locale.US,
            "%.2f,%.2f,%.2f,%.2f,%.2f,%.6f,%.2f,%.2f,%.4f,%d,%.6f,%.6f,%.6f,%d,%.2f,%.4f,%.4f,%.6f,%.6f,%.6f,%.2f,%.2f,%.2f,%.6e,%.6e,%.6e,%s,%.2f,%.6e,%.2f,%d,%.2f,%.4f,%d",
            audioRelativeTimeMs,
            audioWindowStartMs,
            audioWindowCenterMs,
            audioWindowEndMs,
            sensorAlignmentLagMs,
            microphoneRms,
            microphoneLogEnergyDb,
            microphonePitchHz,
            microphonePitchStrength,
            microphonePitchReliable,
            accelerometerBandEnergy,
            accelerometerBandRms,
            phoneMotionLevel,
            sensorSampleCount,
            sensorRateHz,
            sensorReliability,
            contactQuality,
            accelXBandRms,
            accelYBandRms,
            accelZBandRms,
            accelXPeakHz,
            accelYPeakHz,
            accelZPeakHz,
            accelXPeakPower,
            accelYPeakPower,
            accelZPeakPower,
            accelBestAxis,
            accelPeakHz,
            accelPeakPower,
            accelPeakProminence,
            accelPeakReliable,
            pitchDifferenceHz,
            pitchAgreementScore,
            pitchAgreementReliable
        )
    }

    companion object {
        const val CSV_HEADER = "audio_relative_time_ms,audio_window_start_ms,audio_window_center_ms,audio_window_end_ms,sensor_alignment_lag_ms,microphone_rms,microphone_log_energy_db,microphone_pitch_hz,microphone_pitch_strength,microphone_pitch_reliable,accelerometer_band_energy,accelerometer_band_rms,phone_motion_level,sensor_sample_count,sensor_rate_hz,sensor_reliability,contact_quality,accel_x_band_rms,accel_y_band_rms,accel_z_band_rms,accel_x_peak_hz,accel_y_peak_hz,accel_z_peak_hz,accel_x_peak_power,accel_y_peak_power,accel_z_peak_power,accel_best_axis,accel_peak_hz,accel_peak_power,accel_peak_prominence,accel_peak_reliable,pitch_difference_hz,pitch_agreement_score,pitch_agreement_reliable"
    }
}

/**
 * Feature extractor computing synchronized audio, 100 ms RMS/motion features,
 * 250 ms spectral peak features, pitch estimation, and audio-vibration agreement.
 * All computations are mathematically bounded and verified against NaN/Infinity.
 */
class FeatureExtractor(val config: FeatureConfig = FeatureConfig()) {

    val pitchEstimator = PitchEstimator(
        PitchConfig(
            minPitchHz = 80.0,
            maxPitchHz = 190.0,
            minRmsEnergyFloor = config.minPitchRmsFloor,
            minPitchStrength = config.minPitchStrength
        )
    )

    val spectralAnalyzer = AccelSpectralAnalyzer(
        AccelSpectralConfig(
            nominalRateHz = config.nominalSensorRateHz,
            minAnalysisFreqHz = 80.0,
            maxAnalysisFreqHz = 185.0,
            minPeakPower = config.minAccelPeakPower,
            minProminence = config.minAccelProminence,
            maxMotionThreshold = config.maxMotionForPeak,
            maxAlignmentLagMs = config.maxAlignmentLagMs
        )
    )

    fun extractFeatures(
        audioSamples: ShortArray,
        numAudioSamples: Int,
        audioWindowStartMs: Double,
        audioWindowCenterMs: Double,
        audioWindowEndMs: Double,
        targetAudioTimestampNs: Long,
        accelWindow100ms: List<FilteredAccelSample>,
        accelWindow250ms: List<FilteredAccelSample>,
        isFilterWarmedUp: Boolean
    ): WindowFeatures {
        // 1. Microphone Features (RMS & Log Energy)
        var micSumSq = 0.0
        val nAudio = max(1, numAudioSamples)
        for (i in 0 until min(numAudioSamples, audioSamples.size)) {
            val norm = audioSamples[i] / 32768.0
            micSumSq += norm * norm
        }
        val micRms = safeValue(sqrt(micSumSq / nAudio))
        val micLogEnergyDb = safeValue(20.0 * log10(max(1e-6, micRms)))

        // 2. Microphone Pitch Estimation (80 - 190 Hz)
        val pitchResult = pitchEstimator.estimatePitch(
            audioSamples = audioSamples,
            numSamples = numAudioSamples,
            sampleRateHz = 16000.0,
            precomputedRms = micRms
        )

        // 3. Accelerometer Timing, Rate & Alignment Lag
        val m100 = accelWindow100ms.size
        var sensorRateHz = 0.0
        var maxGapSec = 0.0
        var alignmentLagMs = 0.0

        if (m100 >= 2) {
            val firstTs = accelWindow100ms.first().timestampNs
            val lastTs = accelWindow100ms.last().timestampNs
            val durationSec = (lastTs - firstTs) / 1e9
            if (durationSec > 0) {
                sensorRateHz = safeValue((m100 - 1).toDouble() / durationSec)
            }
            for (i in 1 until m100) {
                val gapSec = (accelWindow100ms[i].timestampNs - accelWindow100ms[i - 1].timestampNs) / 1e9
                if (gapSec > maxGapSec) {
                    maxGapSec = gapSec
                }
            }
            if (targetAudioTimestampNs > 0L) {
                alignmentLagMs = safeValue(max(0.0, (targetAudioTimestampNs - lastTs) / 1e6))
            }
        } else if (m100 == 1 && targetAudioTimestampNs > 0L) {
            alignmentLagMs = safeValue(max(0.0, (targetAudioTimestampNs - accelWindow100ms.last().timestampNs) / 1e6))
        } else if (targetAudioTimestampNs > 0L) {
            alignmentLagMs = 999.0
        }

        // 4. Accelerometer Bandpass Energy & RMS (100 ms window)
        var bpEnergySum = 0.0
        var lowMagSum = 0.0
        var lowMagSqSum = 0.0

        if (m100 > 0) {
            for (i in 0 until m100) {
                val s = accelWindow100ms[i]
                val bpEnergy = (s.bpX * s.bpX) + (s.bpY * s.bpY) + (s.bpZ * s.bpZ)
                bpEnergySum += bpEnergy

                val lowMag = sqrt((s.lowX * s.lowX) + (s.lowY * s.lowY) + (s.lowZ * s.lowZ).toDouble())
                lowMagSum += lowMag
                lowMagSqSum += lowMag * lowMag
            }
        }

        val accelBandEnergy = if (m100 > 0) safeValue(bpEnergySum / m100) else 0.0
        val accelBandRms = safeValue(sqrt(accelBandEnergy))

        // 5. Phone Motion Level (100 ms lowpass std dev)
        var phoneMotionLevel = 0.0
        if (m100 > 1) {
            val meanLow = lowMagSum / m100
            val varLow = max(0.0, (lowMagSqSum / m100) - (meanLow * meanLow))
            phoneMotionLevel = safeValue(sqrt(varLow))
        }

        // 6. Sensor Reliability Calculation
        var reliability = 1.0

        // Penalty A: Filter startup warmup transient exclusion
        if (!isFilterWarmedUp) {
            reliability *= 0.1
        }

        // Penalty B: Sample shortage in 100 ms window
        if (m100 < config.minReliableSamples) {
            val sampleFactor = (m100.toDouble() / config.minReliableSamples.toDouble()).coerceIn(0.0, 1.0)
            reliability *= sampleFactor
        }

        // Penalty C: Excessive timing gaps
        if (maxGapSec > config.maxAllowedGapSec) {
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
        } else if (m100 > 0) {
            reliability *= 0.5
        } else {
            reliability = 0.0
        }

        // Penalty E: High phone motion disrupts contact vibration confidence
        if (phoneMotionLevel > config.motionPenaltyThreshold) {
            val motionFactor = max(0.0, 1.0 - (phoneMotionLevel - config.motionPenaltyThreshold) / 2.0)
            reliability *= motionFactor
        }

        // Penalty F: Sensor alignment lag penalty
        if (alignmentLagMs > config.maxAlignmentLagMs) {
            val lagPenalty = max(0.0, 1.0 - (alignmentLagMs - config.maxAlignmentLagMs) / 20.0).coerceIn(0.0, 1.0)
            reliability *= lagPenalty
        }

        val safeReliability = safeValue(reliability.coerceIn(0.0, 1.0))

        // 7. Contact Quality Heuristic (Explicitly Experimental)
        val normVib = (accelBandRms / config.experimentalVibScale).coerceIn(0.0, 1.0)
        val motionQuietness = (1.0 - (phoneMotionLevel / config.experimentalMotionScale)).coerceIn(0.0, 1.0)
        val contactQuality = safeValue((safeReliability * normVib * motionQuietness).coerceIn(0.0, 1.0))

        // 8. Accelerometer Spectral Analysis (250 ms window)
        val spectralResult = spectralAnalyzer.analyzeWindow(
            samples = accelWindow250ms,
            phoneMotionLevel = phoneMotionLevel,
            sensorReliability = safeReliability,
            alignmentLagMs = alignmentLagMs,
            isFilterWarmedUp = isFilterWarmedUp
        )

        // 9. Audio-Vibration Agreement Calculation
        var pitchDiffHz = 0.0
        var agreementScore = 0.0
        var agreementReliable = 0

        if (pitchResult.isReliable && spectralResult.isReliable) {
            val diff = abs(pitchResult.pitchHz - spectralResult.peakHz)
            pitchDiffHz = safeValue(diff)
            val score = exp(-(diff * diff) / (2.0 * config.agreementSigmaHz * config.agreementSigmaHz))
            agreementScore = safeValue(score.coerceIn(0.0, 1.0))
            if (diff <= config.maxAgreementDeltaHz) {
                agreementReliable = 1
            }
        }

        return WindowFeatures(
            audioRelativeTimeMs = safeValue(audioWindowStartMs),
            audioWindowStartMs = safeValue(audioWindowStartMs),
            audioWindowCenterMs = safeValue(audioWindowCenterMs),
            audioWindowEndMs = safeValue(audioWindowEndMs),
            sensorAlignmentLagMs = safeValue(alignmentLagMs),
            microphoneRms = micRms,
            microphoneLogEnergyDb = micLogEnergyDb,
            microphonePitchHz = safeValue(pitchResult.pitchHz),
            microphonePitchStrength = safeValue(pitchResult.pitchStrength),
            microphonePitchReliable = if (pitchResult.isReliable) 1 else 0,
            accelerometerBandEnergy = accelBandEnergy,
            accelerometerBandRms = accelBandRms,
            phoneMotionLevel = phoneMotionLevel,
            sensorSampleCount = m100,
            sensorRateHz = sensorRateHz,
            sensorReliability = safeReliability,
            contactQuality = contactQuality,
            accelXBandRms = safeValue(spectralResult.xResult.bandRms),
            accelYBandRms = safeValue(spectralResult.yResult.bandRms),
            accelZBandRms = safeValue(spectralResult.zResult.bandRms),
            accelXPeakHz = safeValue(spectralResult.xResult.peakHz),
            accelYPeakHz = safeValue(spectralResult.yResult.peakHz),
            accelZPeakHz = safeValue(spectralResult.zResult.peakHz),
            accelXPeakPower = safeValue(spectralResult.xResult.peakPower),
            accelYPeakPower = safeValue(spectralResult.yResult.peakPower),
            accelZPeakPower = safeValue(spectralResult.zResult.peakPower),
            accelBestAxis = spectralResult.bestAxis,
            accelPeakHz = safeValue(spectralResult.peakHz),
            accelPeakPower = safeValue(spectralResult.peakPower),
            accelPeakProminence = safeValue(spectralResult.prominence),
            accelPeakReliable = if (spectralResult.isReliable) 1 else 0,
            pitchDifferenceHz = pitchDiffHz,
            pitchAgreementScore = agreementScore,
            pitchAgreementReliable = agreementReliable
        )
    }

    private fun safeValue(value: Double): Double {
        return if (value.isNaN() || value.isInfinite()) 0.0 else value
    }
}
