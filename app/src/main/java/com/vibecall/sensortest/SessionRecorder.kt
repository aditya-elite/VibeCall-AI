package com.vibecall.sensortest

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

data class SessionResult(
    val sessionDirectory: File,
    val zipFile: File,
    val accelerometerSamples: Long,
    val audioSamples: Long,
    val measuredSensorRateHz: Double,
    val averageTrustValue: Double = 1.0,
    val rnnoiseWavFile: File? = null,
    val featuresFile: File? = null,
    val fusionWavFile: File? = null,
    val fusionDecisionsFile: File? = null,
    val clarityWavFile: File? = null
)

data class TelemetryData(
    val measuredRateHz: Double,
    val sampleCount: Long,
    val vibrationRms: Double = 0.0,
    val motionLevel: Double = 0.0,
    val sensorReliability: Double = 1.0,
    val rollingSampleCount: Int = 0,
    val microphonePitchHz: Double = 0.0,
    val microphonePitchReliable: Boolean = false,
    val accelPeakHz: Double = 0.0,
    val accelBestAxis: String = "Z",
    val accelPeakReliable: Boolean = false,
    val pitchDifferenceHz: Double = 0.0,
    val pitchAgreementScore: Double = 0.0,
    val pitchAgreementReliable: Boolean = false,
    val fusionConfidence: Float = 0.5f,
    val appliedGain: Float = 1.0f,
    val fusionControllerState: String = "PRESERVE",
    val fusionBackendStatus: String = "Model uninitialized"
)

data class FusionDecisionRow(
    val windowIndex: Long,
    val audioWindowStartMs: Double,
    val audioWindowEndMs: Double,
    val rawConfidence: Float,
    val modelReliable: Boolean,
    val backendStatus: String,
    val inferenceLatencyUs: Long,
    val controllerState: String,
    val targetGain: Float,
    val appliedGainStart: Float,
    val appliedGainEnd: Float,
    val reason: String,
    val micEnergyDb: Double,
    val micPitchReliable: Int,
    val phoneMotionLevel: Double,
    val sensorReliability: Double,
    val alignmentLagMs: Double,
    val pitchAgreementScore: Double
) {
    fun toCsvRow(): String = buildString {
        append(windowIndex).append(',')
        append(String.format(Locale.US, "%.2f", audioWindowStartMs)).append(',')
        append(String.format(Locale.US, "%.2f", audioWindowEndMs)).append(',')
        append(String.format(Locale.US, "%.4f", rawConfidence)).append(',')
        append(if (modelReliable) 1 else 0).append(',')
        append('"').append(backendStatus.replace("\"", "\"\"")).append('"').append(',')
        append(inferenceLatencyUs).append(',')
        append(controllerState).append(',')
        append(String.format(Locale.US, "%.4f", targetGain)).append(',')
        append(String.format(Locale.US, "%.4f", appliedGainStart)).append(',')
        append(String.format(Locale.US, "%.4f", appliedGainEnd)).append(',')
        append('"').append(reason.replace("\"", "\"\"")).append('"').append(',')
        append(String.format(Locale.US, "%.2f", micEnergyDb)).append(',')
        append(micPitchReliable).append(',')
        append(String.format(Locale.US, "%.4f", phoneMotionLevel)).append(',')
        append(String.format(Locale.US, "%.4f", sensorReliability)).append(',')
        append(String.format(Locale.US, "%.2f", alignmentLagMs)).append(',')
        append(String.format(Locale.US, "%.4f", pitchAgreementScore))
    }

    companion object {
        const val CSV_HEADER = "window_index,audio_window_start_ms,audio_window_end_ms,fusion_confidence,model_reliable,backend_status,inference_latency_us,controller_state,target_gain,applied_gain_start,applied_gain_end,controller_reason,mic_energy_db,mic_pitch_reliable,phone_motion_level,sensor_reliability,sensor_alignment_lag_ms,pitch_agreement_score"
    }
}

enum class RecordingMode {
    /**
     * Standard stable communication mode utilizing platform telephony gain-staging
     * and hardware processing (VOICE_COMMUNICATION). Output is labeled as "Platform-processed microphone".
     */
    STABLE_COMMUNICATION,

    /**
     * Fair A/B benchmarking mode designed to evaluate RNNoise on untreated audio.
     * Tries UNPROCESSED -> VOICE_RECOGNITION -> MIC, and actively requests disabling
     * hardware noise suppressors, acoustic echo cancelers, and automatic gain controls.
     * Strictly avoids VOICE_COMMUNICATION.
     */
    FAIR_COMPARISON
}

data class AudioEffectStatus(
    val name: String,
    val available: Boolean,
    val wasEnabled: Boolean,
    val disabledSuccessfully: Boolean,
    val currentEnabled: Boolean,
    val error: String? = null
)

data class FusionRuntimeStats(
    val backendStatus: String,
    val inferenceCount: Long,
    val failureCount: Long,
    val avgLatencyUs: Double,
    val maxLatencyUs: Long
)

data class RnnoiseRuntimeStats(
    val initialized: Boolean,
    val frameCount: Long,
    val failureCount: Long,
    val inputSampleCount: Long,
    val outputSampleCount: Long,
    val inputRms: Double,
    val outputRms: Double,
    val attenuationDb: Double,
    val meanAbsoluteDiff: Double,
    val differentSamplePercentage: Double,
    val actualAudioSource: String,
    val unalignedCorrelation: Double = 0.0,
    val alignedCorrelation: Double = 0.0
)

class SessionRecorder(
    private val context: Context,
    private val onRateUpdate: (Double, Long) -> Unit = { _, _ -> },
    private val onTelemetryUpdate: ((TelemetryData) -> Unit)? = null
) : SensorEventListener {

    companion object {
        const val AUDIO_SAMPLE_RATE = 16_000
        const val REQUESTED_SENSOR_PERIOD_US = 2_500 // Request 400 Hz; hardware decides actual rate.

        fun getMicrophoneCharacterization(
            audioSource: String,
            effectStatuses: List<AudioEffectStatus>
        ): String {
            val anyEffectEnabled = effectStatuses.any { it.currentEnabled }
            return when {
                audioSource == "UNPROCESSED" && !anyEffectEnabled -> "Unprocessed acoustic microphone"
                audioSource == "VOICE_COMMUNICATION" -> "Platform-processed microphone"
                audioSource == "VOICE_RECOGNITION" -> "Platform source: VOICE_RECOGNITION"
                audioSource == "MIC" -> "Platform source: MIC"
                else -> "Platform source: $audioSource"
            }
        }
    }

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val accelerometer: Sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: error("This phone does not provide an accelerometer")

    private val sensorThread = HandlerThread("vibecall-sensor").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    private val audioExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var recording = false

    private var sessionDirectory: File? = null
    private var pcmFile: File? = null
    private var wavFile: File? = null
    private var sensorWriter: BufferedWriter? = null
    private var audioRecord: AudioRecord? = null
    private var audioDone = CountDownLatch(0)
    private var audioSourceName = "unknown"
    private var sessionLabel = "unknown"
    private var sessionStartElapsedNs = 0L
    private var audioStartElapsedNs = 0L
    private var sessionEndElapsedNs = 0L
    private var firstSensorTimestampNs = 0L
    private var lastSensorTimestampNs = 0L
    private val sensorSamples = AtomicLong(0)
    private val audioSamples = AtomicLong(0)
    private var audioReadError: String? = null

    // NPU Fusion Gate Model integration (kept for comparison only)
    private var fusionGateModel: FusionGateModel? = null
    private var gatedPcmFile: File? = null
    private var gatedWavFile: File? = null
    @Volatile
    private var latestAccelX: Float = 0f
    @Volatile
    private var latestAccelY: Float = 0f
    @Volatile
    private var latestAccelZ: Float = 0f
    @Volatile
    private var latestTrustValue: Float = 1.0f
    private var totalTrustValue: Double = 0.0
    private var trustInferenceCount: Long = 0L

    // RNNoise neural denoising integration
    private var rnnoiseProcessor: RnnoiseProcessor? = null
    private var rnnoisePcmFile: File? = null
    private var rnnoiseWavFile: File? = null

    // Conservative Speech-Clarity Processor
    private val clarityProcessor = ClarityAudioProcessor()
    private var clarityPcmFile: File? = null
    private var clarityWavFile: File? = null

    // Delay-compensated diagnostic FIFOs & accumulators
    private val diagnosticRawDelayFifo = RawAudioDelayFifo(ClarityAudioProcessor.RNNOISE_DELAY_SAMPLES_16K)
    private val diagnosticRawUnalignedFifo = java.util.ArrayDeque<Short>()
    private var diagnosticComparedIndex = 0L
    private var diagnosticValidAlignedCount = 0L
    private var diagSumUnalignedX = 0.0
    private var diagSumUnalignedX2 = 0.0
    private var diagSumUnalignedXY = 0.0
    private var diagSumAlignedX = 0.0
    private var diagSumAlignedX2 = 0.0
    private var diagSumAlignedXY = 0.0
    private var diagSumY = 0.0
    private var diagSumY2 = 0.0
    private var diagSumAlignedAbsDiff = 0.0
    private var diagAlignedDiffCount = 0L

    // Step 3: Rolling Accelerometer Buffer & Feature Extraction
    private val filterBank = AccelFilterBank(400.0)
    private val rollingAccelBuffer = RollingAccelBuffer(RollingAccelBuffer.DEFAULT_WINDOW_DURATION_NS) // 350 ms (retains >= 300 ms)
    private val featureExtractor = FeatureExtractor()
    private var featuresFile: File? = null
    private var featuresWriter: BufferedWriter? = null
    @Volatile
    private var latestWindowFeatures: WindowFeatures? = null

    // Step 4: Feature-Based Fusion-Confidence Model and Conservative Gain Controller
    private var fusionConfidenceModel: FusionConfidenceModel? = null
    private val safeGainController = SafeGainController()
    private var fusionPcmFile: File? = null
    private var fusionWavFile: File? = null
    private var fusionDecisionsFile: File? = null
    private var fusionDecisionsWriter: BufferedWriter? = null

    @Volatile
    private var latestFusionConfidence: Float = 0.5f
    @Volatile
    private var latestAppliedGain: Float = 1.0f
    @Volatile
    private var latestControllerState: String = "PRESERVE"
    @Volatile
    private var latestBackendStatus: String = "Model uninitialized"

    // Lag-1 history trackers
    private var prevMicEnergyDb: Double? = null
    private var prevMicPitchStrength: Double? = null
    private var prevPitchAgreementScore: Double? = null
    private var prevPhoneMotionLevel: Double? = null
    private var prevSensorReliability: Double? = null

    // Recording mode and audio effects
    private var currentRecordingMode: RecordingMode = RecordingMode.STABLE_COMMUNICATION
    private val activeAudioEffects = mutableListOf<AudioEffect>()
    private val audioEffectStatuses = mutableListOf<AudioEffectStatus>()

    // RNNoise runtime statistics accumulators
    private var rnnoiseInputSamplesCount: Long = 0L
    private var rnnoiseOutputSamplesCount: Long = 0L
    private var rnnoiseSumInputSquares: Double = 0.0
    private var rnnoiseSumOutputSquares: Double = 0.0
    private var rnnoiseSumAbsoluteDiff: Double = 0.0
    private var rnnoiseDifferentSamplesCount: Long = 0L
    private var rnnoiseComparedSamplesCount: Long = 0L

    val isRecording: Boolean
        get() = recording

    val currentAudioSource: String
        get() = audioSourceName

    val currentMode: RecordingMode
        get() = currentRecordingMode

    val currentMicrophoneCharacterization: String
        get() = getMicrophoneCharacterization(audioSourceName, audioEffectStatuses)

    val currentTrustValue: Float
        get() = latestTrustValue

    val latestRnnoiseWav: File?
        get() = rnnoiseWavFile

    val latestClarityWav: File?
        get() = clarityWavFile

    val latestFeaturesFile: File?
        get() = featuresFile

    val latestFusionWav: File?
        get() = fusionWavFile

    val latestFusionDecisionsFile: File?
        get() = fusionDecisionsFile

    val currentFeatures: WindowFeatures?
        get() = latestWindowFeatures

    val latestRawWav: File?
        get() = wavFile

    val currentFusionConfidence: Float
        get() = latestFusionConfidence

    val currentAppliedGain: Float
        get() = latestAppliedGain

    val currentBackendStatus: String
        get() = latestBackendStatus

    val currentControllerState: String
        get() = latestControllerState


    fun deviceSummary(): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Accelerometer: ${accelerometer.name}")
        appendLine("Vendor: ${accelerometer.vendor}")
        append("Reported minimum delay: ${accelerometer.minDelay} µs")
    }

    @Synchronized
    fun start(label: String, mode: RecordingMode = RecordingMode.STABLE_COMMUNICATION) {
        check(!recording) { "A session is already recording" }

        sessionLabel = label
        currentRecordingMode = mode
        val directory = createSessionDirectory(label)
        sessionDirectory = directory
        pcmFile = File(directory, "microphone.pcm")
        wavFile = File(directory, "microphone.wav")
        gatedPcmFile = File(directory, "gated_microphone.pcm")
        gatedWavFile = File(directory, "gated_microphone.wav")
        rnnoisePcmFile = File(directory, "microphone_rnnoise.pcm")
        rnnoiseWavFile = File(directory, "microphone_rnnoise.wav")
        fusionPcmFile = File(directory, "microphone_fusion.pcm")
        fusionWavFile = File(directory, "microphone_fusion.wav")
        clarityPcmFile = File(directory, "microphone_clarity.pcm")
        clarityWavFile = File(directory, "microphone_clarity.wav")

        clarityProcessor.reset()
        diagnosticRawDelayFifo.reset()
        diagnosticRawUnalignedFifo.clear()
        diagnosticComparedIndex = 0L
        diagnosticValidAlignedCount = 0L
        diagSumUnalignedX = 0.0
        diagSumUnalignedX2 = 0.0
        diagSumUnalignedXY = 0.0
        diagSumAlignedX = 0.0
        diagSumAlignedX2 = 0.0
        diagSumAlignedXY = 0.0
        diagSumY = 0.0
        diagSumY2 = 0.0
        diagSumAlignedAbsDiff = 0.0
        diagAlignedDiffCount = 0L

        sensorWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(File(directory, "accelerometer.csv")), Charsets.UTF_8),
            64 * 1024
        ).apply {
            write("sensor_timestamp_ns,relative_to_audio_start_ns,x_m_s2,y_m_s2,z_m_s2,accuracy\n")
        }

        filterBank.reset()
        rollingAccelBuffer.clear()
        latestWindowFeatures = null
        val featFile = File(directory, "features.csv")
        featuresFile = featFile
        featuresWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(featFile), Charsets.UTF_8),
            64 * 1024
        ).apply {
            write(WindowFeatures.CSV_HEADER)
            newLine()
        }

        val fDecFile = File(directory, "fusion_decisions.csv")
        fusionDecisionsFile = fDecFile
        fusionDecisionsWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(fDecFile), Charsets.UTF_8),
            64 * 1024
        ).apply {
            write(FusionDecisionRow.CSV_HEADER)
            newLine()
        }

        sensorSamples.set(0)
        audioSamples.set(0)
        firstSensorTimestampNs = 0L
        lastSensorTimestampNs = 0L
        audioReadError = null
        latestAccelX = 0f
        latestAccelY = 0f
        latestAccelZ = 0f
        latestTrustValue = 1.0f
        totalTrustValue = 0.0
        trustInferenceCount = 0L
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()

        // Reset RNNoise accumulators
        rnnoiseInputSamplesCount = 0L
        rnnoiseOutputSamplesCount = 0L
        rnnoiseSumInputSquares = 0.0
        rnnoiseSumOutputSquares = 0.0
        rnnoiseSumAbsoluteDiff = 0.0
        rnnoiseDifferentSamplesCount = 0L
        rnnoiseComparedSamplesCount = 0L

        fusionGateModel = runCatching { FusionGateModel(context) }
            .onFailure { Log.w("SessionRecorder", "Failed to initialize FusionGateModel", it) }
            .getOrNull()

        rnnoiseProcessor = runCatching { RnnoiseProcessor() }
            .onFailure { Log.w("SessionRecorder", "Failed to initialize RnnoiseProcessor", it) }
            .getOrNull()

        fusionConfidenceModel = runCatching { FusionConfidenceModel(context) }
            .onFailure { Log.w("SessionRecorder", "Failed to initialize FusionConfidenceModel", it) }
            .getOrNull()

        latestBackendStatus = fusionConfidenceModel?.getBackendStatus() ?: "Model unavailable"
        safeGainController.reset()
        prevMicEnergyDb = null
        prevMicPitchStrength = null
        prevPitchAgreementScore = null
        prevPhoneMotionLevel = null
        prevSensorReliability = null
        latestFusionConfidence = 0.5f
        latestAppliedGain = 1.0f
        latestControllerState = "PRESERVE"

        val recorder = buildAudioRecord(mode)
        audioRecord = recorder
        inspectAndConfigureAudioEffects(recorder.audioSessionId, disableForFairComparison = (mode == RecordingMode.FAIR_COMPARISON))
        recording = true

        val registered = sensorManager.registerListener(
            this,
            accelerometer,
            REQUESTED_SENSOR_PERIOD_US,
            0,
            sensorHandler
        )
        if (!registered) {
            recording = false
            activeAudioEffects.forEach { runCatching { it.release() } }
            activeAudioEffects.clear()
            recorder.release()
            sensorWriter?.close()
            featuresWriter?.close()
            fusionDecisionsWriter?.close()
            throw IllegalStateException("Android could not register the accelerometer listener")
        }

        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recording = false
            sensorManager.unregisterListener(this)
            activeAudioEffects.forEach { runCatching { it.release() } }
            activeAudioEffects.clear()
            recorder.release()
            sensorWriter?.close()
            featuresWriter?.close()
            fusionDecisionsWriter?.close()
            throw IllegalStateException("The microphone did not enter the recording state")
        }
        audioStartElapsedNs = SystemClock.elapsedRealtimeNanos()

        audioDone = CountDownLatch(1)
        val outputPcm = pcmFile ?: error("PCM output was not created")
        val outputGatedPcm = gatedPcmFile
        val outputFusionPcm = fusionPcmFile
        audioExecutor.execute {
            recordAudioLoop(recorder, outputPcm, outputGatedPcm, outputFusionPcm)
        }
    }

    @Synchronized
    fun stop(onComplete: (Result<SessionResult>) -> Unit) {
        if (!recording) {
            onComplete(Result.failure(IllegalStateException("No session is recording")))
            return
        }

        recording = false
        sessionEndElapsedNs = SystemClock.elapsedRealtimeNanos()
        sensorManager.unregisterListener(this)

        val recorder = audioRecord
        try {
            recorder?.stop()
        } catch (_: IllegalStateException) {
            // The audio loop will still finish and report any read error in metadata.
        }

        Thread({
            val result = runCatching {
                if (!audioDone.await(5, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Timed out while closing the microphone recording")
                }
                recorder?.release()
                audioRecord = null

                val sensorClosed = CountDownLatch(1)
                sensorHandler.post {
                    runCatching {
                        sensorWriter?.flush()
                        sensorWriter?.close()
                    }
                    sensorWriter = null
                    sensorClosed.countDown()
                }
                if (!sensorClosed.await(3, TimeUnit.SECONDS)) {
                    throw IllegalStateException("Timed out while closing accelerometer data")
                }

                val pcm = pcmFile ?: error("Missing PCM file")
                val wav = wavFile ?: error("Missing WAV file")
                writeWav(pcm, wav, AUDIO_SAMPLE_RATE, 1, 16)
                pcm.delete()

                val gatedPcm = gatedPcmFile
                val gatedWav = gatedWavFile
                if (gatedPcm != null && gatedWav != null && gatedPcm.exists() && gatedPcm.length() > 0) {
                    writeWav(gatedPcm, gatedWav, AUDIO_SAMPLE_RATE, 1, 16)
                    gatedPcm.delete()
                }

                val rnnoisePcm = rnnoisePcmFile
                val rnnoiseWav = rnnoiseWavFile
                if (rnnoisePcm != null && rnnoiseWav != null && rnnoisePcm.exists() && rnnoisePcm.length() > 0) {
                    writeWav(rnnoisePcm, rnnoiseWav, AUDIO_SAMPLE_RATE, 1, 16)
                    rnnoisePcm.delete()
                }

                val fusionPcm = fusionPcmFile
                val fusionWav = fusionWavFile
                if (fusionPcm != null && fusionWav != null && fusionPcm.exists() && fusionPcm.length() > 0) {
                    writeWav(fusionPcm, fusionWav, AUDIO_SAMPLE_RATE, 1, 16)
                    fusionPcm.delete()
                }

                val clarityPcm = clarityPcmFile
                val clarityWav = clarityWavFile
                if (clarityPcm != null && clarityWav != null && clarityPcm.exists() && clarityPcm.length() > 0) {
                    writeWav(clarityPcm, clarityWav, AUDIO_SAMPLE_RATE, 1, 16)
                    clarityPcm.delete()
                }

                val fusionStats = fusionConfidenceModel?.let { model ->
                    FusionRuntimeStats(
                        backendStatus = latestBackendStatus,
                        inferenceCount = model.getInferenceCount().toLong(),
                        failureCount = model.getFailureCount().toLong(),
                        avgLatencyUs = model.getAverageLatencyUs(),
                        maxLatencyUs = model.getMaxLatencyUs()
                    )
                } ?: FusionRuntimeStats(
                    backendStatus = latestBackendStatus,
                    inferenceCount = 0L,
                    failureCount = 0L,
                    avgLatencyUs = 0.0,
                    maxLatencyUs = 0L
                )

                val rnnoiseStats = computeRnnoiseRuntimeStats()
                val clarityStats = clarityProcessor.getDiagnostics()

                fusionGateModel?.close()
                fusionGateModel = null
                rnnoiseProcessor?.close()
                rnnoiseProcessor = null
                fusionConfidenceModel?.close()
                fusionConfidenceModel = null

                activeAudioEffects.forEach { runCatching { it.release() } }
                activeAudioEffects.clear()

                featuresWriter?.flush()
                featuresWriter?.close()
                featuresWriter = null

                fusionDecisionsWriter?.flush()
                fusionDecisionsWriter?.close()
                fusionDecisionsWriter = null

                val directory = sessionDirectory ?: error("Missing session directory")
                val measuredRate = measuredSensorRateHz()
                val avgTrust = if (trustInferenceCount > 0) totalTrustValue / trustInferenceCount else 1.0
                writeMetadata(directory, measuredRate, avgTrust, fusionStats, rnnoiseStats, clarityStats)
                val zip = zipSession(directory)

                SessionResult(
                    sessionDirectory = directory,
                    zipFile = zip,
                    accelerometerSamples = sensorSamples.get(),
                    audioSamples = audioSamples.get(),
                    measuredSensorRateHz = measuredRate,
                    averageTrustValue = avgTrust,
                    rnnoiseWavFile = rnnoiseWav,
                    featuresFile = featuresFile,
                    fusionWavFile = fusionWav,
                    fusionDecisionsFile = fusionDecisionsFile,
                    clarityWavFile = clarityWav
                )
            }
            onComplete(result)
        }, "vibecall-finalize").start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        // 1. Process sample through stateful filters exactly once as it arrives
        val filtered = filterBank.process(event.values[0], event.values[1], event.values[2])
        rollingAccelBuffer.add(
            FilteredAccelSample(
                timestampNs = event.timestamp,
                rawX = filtered.rawX,
                rawY = filtered.rawY,
                rawZ = filtered.rawZ,
                lowX = filtered.lowX,
                lowY = filtered.lowY,
                lowZ = filtered.lowZ,
                bpX = filtered.bpX,
                bpY = filtered.bpY,
                bpZ = filtered.bpZ
            )
        )

        latestAccelX = event.values[0]
        latestAccelY = event.values[1]
        latestAccelZ = event.values[2]

        val count = sensorSamples.incrementAndGet()
        if (firstSensorTimestampNs == 0L) firstSensorTimestampNs = event.timestamp
        lastSensorTimestampNs = event.timestamp

        sensorWriter?.apply {
            write(event.timestamp.toString())
            write(','.code)
            write((event.timestamp - audioStartElapsedNs).toString())
            write(','.code)
            write(event.values[0].toString())
            write(','.code)
            write(event.values[1].toString())
            write(','.code)
            write(event.values[2].toString())
            write(','.code)
            write(event.accuracy.toString())
            newLine()
        }

        if (count % 40L == 0L) { // update telemetry every ~100 ms
            val rate = measuredSensorRateHz()
            onRateUpdate(rate, count)
            val feat = latestWindowFeatures
            onTelemetryUpdate?.invoke(
                TelemetryData(
                    measuredRateHz = rate,
                    sampleCount = count,
                    vibrationRms = feat?.accelerometerBandRms ?: 0.0,
                    motionLevel = feat?.phoneMotionLevel ?: 0.0,
                    sensorReliability = feat?.sensorReliability ?: 1.0,
                    rollingSampleCount = rollingAccelBuffer.size(),
                    microphonePitchHz = feat?.microphonePitchHz ?: 0.0,
                    microphonePitchReliable = (feat?.microphonePitchReliable == 1),
                    accelPeakHz = feat?.accelPeakHz ?: 0.0,
                    accelBestAxis = feat?.accelBestAxis ?: "Z",
                    accelPeakReliable = (feat?.accelPeakReliable == 1),
                    pitchDifferenceHz = feat?.pitchDifferenceHz ?: 0.0,
                    pitchAgreementScore = feat?.pitchAgreementScore ?: 0.0,
                    pitchAgreementReliable = (feat?.pitchAgreementReliable == 1),
                    fusionConfidence = latestFusionConfidence,
                    appliedGain = latestAppliedGain,
                    fusionControllerState = latestControllerState,
                    fusionBackendStatus = latestBackendStatus
                )
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun close() {
        if (!recording) {
            fusionGateModel?.close()
            fusionGateModel = null
            rnnoiseProcessor?.close()
            rnnoiseProcessor = null
            fusionConfidenceModel?.close()
            fusionConfidenceModel = null
            activeAudioEffects.forEach { runCatching { it.release() } }
            activeAudioEffects.clear()
            sensorThread.quitSafely()
            audioExecutor.shutdown()
        }
    }

    private fun computeRnnoiseRuntimeStats(): RnnoiseRuntimeStats {
        val processor = rnnoiseProcessor
        val initialized = (processor != null)
        val frameCount = processor?.getFrameCount() ?: 0L
        val failureCount = processor?.getFailureCount() ?: 0L
        val inSamples = rnnoiseInputSamplesCount
        val outSamples = rnnoiseOutputSamplesCount
        val inRms = if (inSamples > 0) kotlin.math.sqrt(rnnoiseSumInputSquares / inSamples.toDouble()) else 0.0
        val outRms = if (outSamples > 0) kotlin.math.sqrt(rnnoiseSumOutputSquares / outSamples.toDouble()) else 0.0
        val attenuationDb = if (inRms > 1e-6 && outRms > 1e-6) {
            20.0 * kotlin.math.log10(inRms / outRms)
        } else {
            0.0
        }
        val n = diagnosticValidAlignedCount.toDouble()
        val unalignedCorr = if (n > 100) {
            val num = diagSumUnalignedXY - (diagSumUnalignedX * diagSumY / n)
            val denX = diagSumUnalignedX2 - (diagSumUnalignedX * diagSumUnalignedX / n)
            val denY = diagSumY2 - (diagSumY * diagSumY / n)
            if (denX > 1e-9 && denY > 1e-9) {
                (num / (kotlin.math.sqrt(denX) * kotlin.math.sqrt(denY))).coerceIn(-1.0, 1.0)
            } else 0.0
        } else 0.0

        val alignedCorr = if (n > 100) {
            val num = diagSumAlignedXY - (diagSumAlignedX * diagSumY / n)
            val denX = diagSumAlignedX2 - (diagSumAlignedX * diagSumAlignedX / n)
            val denY = diagSumY2 - (diagSumY * diagSumY / n)
            if (denX > 1e-9 && denY > 1e-9) {
                (num / (kotlin.math.sqrt(denX) * kotlin.math.sqrt(denY))).coerceIn(-1.0, 1.0)
            } else 0.0
        } else 0.0

        val meanAbsDiff = if (diagnosticValidAlignedCount > 0) {
            diagSumAlignedAbsDiff / diagnosticValidAlignedCount.toDouble()
        } else if (rnnoiseComparedSamplesCount > 0) {
            rnnoiseSumAbsoluteDiff / rnnoiseComparedSamplesCount.toDouble()
        } else {
            0.0
        }
        val diffPct = if (diagnosticValidAlignedCount > 0) {
            (diagAlignedDiffCount.toDouble() * 100.0) / diagnosticValidAlignedCount.toDouble()
        } else if (rnnoiseComparedSamplesCount > 0) {
            (rnnoiseDifferentSamplesCount.toDouble() * 100.0) / rnnoiseComparedSamplesCount.toDouble()
        } else {
            0.0
        }

        return RnnoiseRuntimeStats(
            initialized = initialized,
            frameCount = frameCount,
            failureCount = failureCount,
            inputSampleCount = inSamples,
            outputSampleCount = outSamples,
            inputRms = inRms,
            outputRms = outRms,
            attenuationDb = attenuationDb,
            meanAbsoluteDiff = meanAbsDiff,
            differentSamplePercentage = diffPct,
            actualAudioSource = audioSourceName,
            unalignedCorrelation = unalignedCorr,
            alignedCorrelation = alignedCorr
        )
    }

    private fun inspectAndConfigureAudioEffects(audioSessionId: Int, disableForFairComparison: Boolean) {
        audioEffectStatuses.clear()
        activeAudioEffects.forEach { runCatching { it.release() } }
        activeAudioEffects.clear()

        // 1. NoiseSuppressor
        try {
            val available = NoiseSuppressor.isAvailable()
            if (available) {
                val ns = NoiseSuppressor.create(audioSessionId)
                if (ns != null) {
                    val wasEnabled = ns.enabled
                    var disabledSuccess = false
                    if (disableForFairComparison && wasEnabled) {
                        val res = ns.setEnabled(false)
                        disabledSuccess = (res == AudioEffect.SUCCESS && !ns.enabled)
                    }
                    audioEffectStatuses.add(
                        AudioEffectStatus(
                            name = "NoiseSuppressor",
                            available = true,
                            wasEnabled = wasEnabled,
                            disabledSuccessfully = disabledSuccess,
                            currentEnabled = ns.enabled
                        )
                    )
                    activeAudioEffects.add(ns)
                } else {
                    audioEffectStatuses.add(
                        AudioEffectStatus(
                            name = "NoiseSuppressor",
                            available = true,
                            wasEnabled = false,
                            disabledSuccessfully = false,
                            currentEnabled = false,
                            error = "create() returned null"
                        )
                    )
                }
            } else {
                audioEffectStatuses.add(
                    AudioEffectStatus(
                        name = "NoiseSuppressor",
                        available = false,
                        wasEnabled = false,
                        disabledSuccessfully = false,
                        currentEnabled = false
                    )
                )
            }
        } catch (e: Throwable) {
            audioEffectStatuses.add(
                AudioEffectStatus(
                    name = "NoiseSuppressor",
                    available = false,
                    wasEnabled = false,
                    disabledSuccessfully = false,
                    currentEnabled = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            )
        }

        // 2. AcousticEchoCanceler
        try {
            val available = AcousticEchoCanceler.isAvailable()
            if (available) {
                val aec = AcousticEchoCanceler.create(audioSessionId)
                if (aec != null) {
                    val wasEnabled = aec.enabled
                    var disabledSuccess = false
                    if (disableForFairComparison && wasEnabled) {
                        val res = aec.setEnabled(false)
                        disabledSuccess = (res == AudioEffect.SUCCESS && !aec.enabled)
                    }
                    audioEffectStatuses.add(
                        AudioEffectStatus(
                            name = "AcousticEchoCanceler",
                            available = true,
                            wasEnabled = wasEnabled,
                            disabledSuccessfully = disabledSuccess,
                            currentEnabled = aec.enabled
                        )
                    )
                    activeAudioEffects.add(aec)
                } else {
                    audioEffectStatuses.add(
                        AudioEffectStatus(
                            name = "AcousticEchoCanceler",
                            available = true,
                            wasEnabled = false,
                            disabledSuccessfully = false,
                            currentEnabled = false,
                            error = "create() returned null"
                        )
                    )
                }
            } else {
                audioEffectStatuses.add(
                    AudioEffectStatus(
                        name = "AcousticEchoCanceler",
                        available = false,
                        wasEnabled = false,
                        disabledSuccessfully = false,
                        currentEnabled = false
                    )
                )
            }
        } catch (e: Throwable) {
            audioEffectStatuses.add(
                AudioEffectStatus(
                    name = "AcousticEchoCanceler",
                    available = false,
                    wasEnabled = false,
                    disabledSuccessfully = false,
                    currentEnabled = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            )
        }

        // 3. AutomaticGainControl
        try {
            val available = AutomaticGainControl.isAvailable()
            if (available) {
                val agc = AutomaticGainControl.create(audioSessionId)
                if (agc != null) {
                    val wasEnabled = agc.enabled
                    var disabledSuccess = false
                    if (disableForFairComparison && wasEnabled) {
                        val res = agc.setEnabled(false)
                        disabledSuccess = (res == AudioEffect.SUCCESS && !agc.enabled)
                    }
                    audioEffectStatuses.add(
                        AudioEffectStatus(
                            name = "AutomaticGainControl",
                            available = true,
                            wasEnabled = wasEnabled,
                            disabledSuccessfully = disabledSuccess,
                            currentEnabled = agc.enabled
                        )
                    )
                    activeAudioEffects.add(agc)
                } else {
                    audioEffectStatuses.add(
                        AudioEffectStatus(
                            name = "AutomaticGainControl",
                            available = true,
                            wasEnabled = false,
                            disabledSuccessfully = false,
                            currentEnabled = false,
                            error = "create() returned null"
                        )
                    )
                }
            } else {
                audioEffectStatuses.add(
                    AudioEffectStatus(
                        name = "AutomaticGainControl",
                        available = false,
                        wasEnabled = false,
                        disabledSuccessfully = false,
                        currentEnabled = false
                    )
                )
            }
        } catch (e: Throwable) {
            audioEffectStatuses.add(
                AudioEffectStatus(
                    name = "AutomaticGainControl",
                    available = false,
                    wasEnabled = false,
                    disabledSuccessfully = false,
                    currentEnabled = false,
                    error = e.message ?: e.javaClass.simpleName
                )
            )
        }
    }

    private fun buildAudioRecord(mode: RecordingMode): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AUDIO_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()

        val minimum = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        check(minimum > 0) { "This device rejected the selected microphone format" }
        val bufferSize = max(minimum * 2, 8_192)

        val candidateSources = when (mode) {
            RecordingMode.FAIR_COMPARISON -> listOf(
                "UNPROCESSED" to MediaRecorder.AudioSource.UNPROCESSED,
                "VOICE_RECOGNITION" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
                "MIC" to MediaRecorder.AudioSource.MIC
            )
            RecordingMode.STABLE_COMMUNICATION -> listOf(
                "VOICE_COMMUNICATION" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                "MIC" to MediaRecorder.AudioSource.MIC
            )
        }

        for ((name, source) in candidateSources) {
            try {
                val candidate = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    audioSourceName = name
                    Log.i("SessionRecorder", "Microphone initialized using $name (Mode: $mode)")
                    return candidate
                }
                candidate.release()
            } catch (e: Exception) {
                Log.w("SessionRecorder", "Failed initializing audio source $name in mode $mode: ${e.message}")
            }
        }

        if (mode == RecordingMode.FAIR_COMPARISON) {
            throw IllegalStateException("Could not initialize microphone using UNPROCESSED, VOICE_RECOGNITION, or MIC in Fair Comparison mode")
        } else {
            throw IllegalStateException("Android could not initialize the microphone with VOICE_COMMUNICATION or MIC")
        }
    }

    private fun recordAudioLoop(recorder: AudioRecord, outputFile: File, gatedFile: File?, fusionFile: File?) {
        val buffer = ByteArray(4_096)
        var windowIndexCounter = 0L
        try {
            val gatedStream = gatedFile?.let { FileOutputStream(it) }
            val rnnoiseStream = rnnoisePcmFile?.let { FileOutputStream(it) }
            val fusionStream = fusionFile?.let { FileOutputStream(it) }
            val clarityStream = clarityPcmFile?.let { FileOutputStream(it) }
            try {
                FileOutputStream(outputFile).use { output ->
                    while (recording) {
                        val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        when {
                            read > 0 -> {
                                // 1. Preserve original microphone PCM capture unchanged
                                output.write(buffer, 0, read)
                                val numSamples = read / 2
                                val currentAudioSampleIndex = audioSamples.get()
                                audioSamples.addAndGet(numSamples.toLong())

                                // 2. Convert buffer bytes to ShortArray for variance, gating, denoising, and features
                                val shortSamples = ShortArray(numSamples)
                                var sum = 0.0
                                var sumSq = 0.0
                                for (i in 0 until numSamples) {
                                    val low = buffer[i * 2].toInt() and 0xFF
                                    val high = buffer[i * 2 + 1].toInt()
                                    val sample = ((high shl 8) or low).toShort()
                                    shortSamples[i] = sample
                                    val norm = sample / 32768.0f
                                    sum += norm
                                    sumSq += norm * norm
                                }
                                val mean = sum / numSamples
                                val variance = max(0.0, (sumSq / numSamples) - (mean * mean)).toFloat()

                                // Push raw audio to diagnostic FIFOs
                                diagnosticRawDelayFifo.push(shortSamples, numSamples)
                                for (i in 0 until numSamples) {
                                    diagnosticRawUnalignedFifo.addLast(shortSamples[i])
                                }

                                // 3. Audio-relative window timing and aligned accelerometer snapshots
                                val startSampleIndex = currentAudioSampleIndex
                                val endSampleIndex = startSampleIndex + numSamples
                                val startMs = (startSampleIndex.toDouble() * 1000.0) / AUDIO_SAMPLE_RATE.toDouble()
                                val endMs = (endSampleIndex.toDouble() * 1000.0) / AUDIO_SAMPLE_RATE.toDouble()
                                val centerMs = (startMs + endMs) / 2.0

                                val targetAudioTimestampNs = audioStartElapsedNs + ((endSampleIndex * 1_000_000_000L) / AUDIO_SAMPLE_RATE.toLong())

                                val accel100ms = rollingAccelBuffer.getSnapshot100msEndingAt(targetAudioTimestampNs)
                                val accel250ms = rollingAccelBuffer.getSnapshot250msEndingAt(targetAudioTimestampNs)

                                val windowFeatures = featureExtractor.extractFeatures(
                                    audioSamples = shortSamples,
                                    numAudioSamples = numSamples,
                                    audioWindowStartMs = startMs,
                                    audioWindowCenterMs = centerMs,
                                    audioWindowEndMs = endMs,
                                    targetAudioTimestampNs = targetAudioTimestampNs,
                                    accelWindow100ms = accel100ms,
                                    accelWindow250ms = accel250ms,
                                    isFilterWarmedUp = filterBank.isWarmedUp
                                )
                                latestWindowFeatures = windowFeatures

                                // Write to features.csv (buffered, non-blocking)
                                featuresWriter?.apply {
                                    write(windowFeatures.toCsvRow())
                                    newLine()
                                }

                                // 4. Run legacy NPU fusion gate model (kept for diagnostic comparison only)
                                val trust = fusionGateModel?.getTrustValue(
                                    audioVariance = variance,
                                    accelX = latestAccelX,
                                    accelY = latestAccelY,
                                    accelZ = latestAccelZ
                                ) ?: 1.0f

                                latestTrustValue = trust
                                totalTrustValue += trust
                                trustInferenceCount++

                                // Scale audio by trustValue to produce gated audio output
                                if (gatedStream != null) {
                                    val gatedBuffer = ByteArray(read)
                                    for (i in 0 until numSamples) {
                                        val rawSample = shortSamples[i]
                                        val scaledSample = (rawSample * trust).toInt().coerceIn(-32768, 32767).toShort()
                                        gatedBuffer[i * 2] = (scaledSample.toInt() and 0xFF).toByte()
                                        gatedBuffer[i * 2 + 1] = ((scaledSample.toInt() shr 8) and 0xFF).toByte()
                                    }
                                    gatedStream.write(gatedBuffer, 0, read)
                                }

                                // 5. RNNoise real-time neural denoising (independent clean audio track)
                                val denoised16k = if (rnnoiseStream != null && rnnoiseProcessor != null) {
                                    val cleaned = rnnoiseProcessor?.processStream(shortSamples, numSamples)
                                    if (cleaned != null && cleaned.isNotEmpty()) {
                                        val rnnoiseBytes = ByteArray(cleaned.size * 2)
                                        for (i in cleaned.indices) {
                                            val s = cleaned[i].toInt()
                                            rnnoiseBytes[i * 2] = (s and 0xFF).toByte()
                                            rnnoiseBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                        }
                                        rnnoiseStream.write(rnnoiseBytes)

                                        // Update RNNoise runtime diagnostics
                                        rnnoiseInputSamplesCount += numSamples
                                        rnnoiseOutputSamplesCount += cleaned.size
                                        val minLen = minOf(numSamples, cleaned.size)
                                        for (i in 0 until minLen) {
                                            val x = shortSamples[i].toDouble()
                                            val y = cleaned[i].toDouble()
                                            rnnoiseSumInputSquares += x * x
                                            rnnoiseSumOutputSquares += y * y
                                            rnnoiseSumAbsoluteDiff += kotlin.math.abs(x - y)
                                            if (shortSamples[i] != cleaned[i]) {
                                                rnnoiseDifferentSamplesCount++
                                            }
                                            rnnoiseComparedSamplesCount++
                                        }
                                        for (i in minLen until numSamples) {
                                            val x = shortSamples[i].toDouble()
                                            rnnoiseSumInputSquares += x * x
                                        }
                                        for (i in minLen until cleaned.size) {
                                            val y = cleaned[i].toDouble()
                                            rnnoiseSumOutputSquares += y * y
                                        }
                                    }
                                    cleaned
                                } else {
                                    null
                                }

                                // 6. Step 4 Feature-Based Fusion-Confidence Model and Conservative Gain Controller
                                val fVector = FloatArray(16)
                                fVector[0] = windowFeatures.microphoneLogEnergyDb.toFloat()
                                fVector[1] = windowFeatures.microphonePitchStrength.toFloat()
                                fVector[2] = windowFeatures.microphonePitchReliable.toFloat()
                                fVector[3] = kotlin.math.log10(max(0.0, windowFeatures.accelPeakPower) + 1e-6).toFloat()
                                fVector[4] = windowFeatures.accelPeakProminence.toFloat()
                                fVector[5] = windowFeatures.pitchDifferenceHz.toFloat()
                                fVector[6] = windowFeatures.pitchAgreementScore.toFloat()
                                fVector[7] = windowFeatures.pitchAgreementReliable.toFloat()
                                fVector[8] = windowFeatures.phoneMotionLevel.toFloat()
                                fVector[9] = windowFeatures.sensorReliability.toFloat()
                                fVector[10] = windowFeatures.sensorAlignmentLagMs.toFloat()
                                // Lag-1 context features (fall back to current on initial window)
                                fVector[11] = (prevMicEnergyDb ?: windowFeatures.microphoneLogEnergyDb).toFloat()
                                fVector[12] = (prevMicPitchStrength ?: windowFeatures.microphonePitchStrength).toFloat()
                                fVector[13] = (prevPitchAgreementScore ?: windowFeatures.pitchAgreementScore).toFloat()
                                fVector[14] = (prevPhoneMotionLevel ?: windowFeatures.phoneMotionLevel).toFloat()
                                fVector[15] = (prevSensorReliability ?: windowFeatures.sensorReliability).toFloat()

                                prevMicEnergyDb = windowFeatures.microphoneLogEnergyDb
                                prevMicPitchStrength = windowFeatures.microphonePitchStrength
                                prevPitchAgreementScore = windowFeatures.pitchAgreementScore
                                prevPhoneMotionLevel = windowFeatures.phoneMotionLevel
                                prevSensorReliability = windowFeatures.sensorReliability

                                val inferenceResult = fusionConfidenceModel?.infer(fVector)
                                    ?: FusionInferenceResult(
                                        confidence = 0.5f,
                                        modelReliable = false,
                                        backendStatus = latestBackendStatus,
                                        inferenceLatencyUs = 0L,
                                        error = "Model not initialized"
                                    )

                                latestFusionConfidence = inferenceResult.confidence
                                latestBackendStatus = inferenceResult.backendStatus

                                val (targetGain, reason) = safeGainController.evaluateTargetGain(
                                    microphoneLogEnergyDb = windowFeatures.microphoneLogEnergyDb.toFloat(),
                                    microphonePitchReliable = windowFeatures.microphonePitchReliable.toFloat(),
                                    modelConfidence = inferenceResult.confidence,
                                    modelReliable = inferenceResult.modelReliable,
                                    sensorReliability = windowFeatures.sensorReliability.toFloat(),
                                    phoneMotionLevel = windowFeatures.phoneMotionLevel.toFloat(),
                                    sensorAlignmentLagMs = windowFeatures.sensorAlignmentLagMs.toFloat()
                                )

                                // Push raw audio and features to Clarity processor delay line
                                clarityProcessor.pushRawAudioAndFeatures(
                                    rawSamples = shortSamples,
                                    length = numSamples,
                                    microphoneLogEnergyDb = windowFeatures.microphoneLogEnergyDb.toFloat(),
                                    microphonePitchReliable = windowFeatures.microphonePitchReliable.toFloat(),
                                    modelConfidence = inferenceResult.confidence,
                                    modelReliable = inferenceResult.modelReliable,
                                    sensorReliability = windowFeatures.sensorReliability.toFloat(),
                                    phoneMotionLevel = windowFeatures.phoneMotionLevel.toFloat(),
                                    sensorAlignmentLagMs = windowFeatures.sensorAlignmentLagMs.toFloat()
                                )

                                if (denoised16k != null && denoised16k.isNotEmpty()) {
                                    // 6a. Primary Fusion Output (uses existing safeGainController)
                                    val fusionShorts = ShortArray(denoised16k.size)
                                    val decision = safeGainController.applyGainToFrame(
                                        inputShorts = denoised16k,
                                        outputShorts = fusionShorts,
                                        targetGain = targetGain,
                                        reason = reason
                                    )
                                    latestAppliedGain = decision.appliedGainEnd
                                    latestControllerState = decision.controllerState

                                    if (fusionStream != null) {
                                        val fusionBytes = ByteArray(fusionShorts.size * 2)
                                        for (i in fusionShorts.indices) {
                                            val s = fusionShorts[i].toInt()
                                            fusionBytes[i * 2] = (s and 0xFF).toByte()
                                            fusionBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                        }
                                        fusionStream.write(fusionBytes)
                                    }

                                    // 6b. Conservative Speech-Clarity Output (uses independent controller & FIFOs)
                                    val clarityShorts = ShortArray(denoised16k.size)
                                    clarityProcessor.processRnnoiseChunk(denoised16k, clarityShorts, denoised16k.size)
                                    if (clarityStream != null) {
                                        val clarityBytes = ByteArray(clarityShorts.size * 2)
                                        for (i in clarityShorts.indices) {
                                            val s = clarityShorts[i].toInt()
                                            clarityBytes[i * 2] = (s and 0xFF).toByte()
                                            clarityBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                        }
                                        clarityStream.write(clarityBytes)
                                    }

                                    // 6c. Diagnostic Correlation & Difference Accumulation with 320-sample provisional delay alignment
                                    val tempAligned = ShortArray(1)
                                    for (i in denoised16k.indices) {
                                        diagnosticRawDelayFifo.pop(1, tempAligned, 0)
                                        val xAligned = tempAligned[0].toDouble()
                                        val xUnaligned = if (diagnosticRawUnalignedFifo.isNotEmpty()) diagnosticRawUnalignedFifo.removeFirst().toDouble() else 0.0
                                        val y = denoised16k[i].toDouble()

                                        if (diagnosticComparedIndex >= ClarityAudioProcessor.RNNOISE_DELAY_SAMPLES_16K) {
                                            diagSumUnalignedX += xUnaligned
                                            diagSumUnalignedX2 += xUnaligned * xUnaligned
                                            diagSumUnalignedXY += xUnaligned * y

                                            diagSumAlignedX += xAligned
                                            diagSumAlignedX2 += xAligned * xAligned
                                            diagSumAlignedXY += xAligned * y

                                            diagSumY += y
                                            diagSumY2 += y * y

                                            diagSumAlignedAbsDiff += kotlin.math.abs(xAligned - y)
                                            if (tempAligned[0] != denoised16k[i]) {
                                                diagAlignedDiffCount++
                                            }
                                            diagnosticValidAlignedCount++
                                        }
                                        diagnosticComparedIndex++
                                    }

                                    val decisionRow = FusionDecisionRow(
                                        windowIndex = windowIndexCounter++,
                                        audioWindowStartMs = windowFeatures.audioWindowStartMs,
                                        audioWindowEndMs = windowFeatures.audioWindowEndMs,
                                        rawConfidence = inferenceResult.confidence,
                                        modelReliable = inferenceResult.modelReliable,
                                        backendStatus = inferenceResult.backendStatus,
                                        inferenceLatencyUs = inferenceResult.inferenceLatencyUs,
                                        controllerState = decision.controllerState,
                                        targetGain = decision.targetGain,
                                        appliedGainStart = decision.appliedGainStart,
                                        appliedGainEnd = decision.appliedGainEnd,
                                        reason = decision.reason,
                                        micEnergyDb = windowFeatures.microphoneLogEnergyDb,
                                        micPitchReliable = windowFeatures.microphonePitchReliable,
                                        phoneMotionLevel = windowFeatures.phoneMotionLevel,
                                        sensorReliability = windowFeatures.sensorReliability,
                                        alignmentLagMs = windowFeatures.sensorAlignmentLagMs,
                                        pitchAgreementScore = windowFeatures.pitchAgreementScore
                                    )
                                    fusionDecisionsWriter?.apply {
                                        write(decisionRow.toCsvRow())
                                        newLine()
                                    }
                                }
                            }
                            read < 0 -> {
                                if (recording) {
                                    audioReadError = "AudioRecord.read returned $read"
                                }
                                break
                            }
                        }
                    }

                    // Flush any remaining buffered audio in RNNoise accumulator and apply trailing gain
                    if (rnnoiseStream != null && rnnoiseProcessor != null) {
                        val flushed16k = rnnoiseProcessor?.flush()
                        if (flushed16k != null && flushed16k.isNotEmpty()) {
                            val rnnoiseBytes = ByteArray(flushed16k.size * 2)
                            val fusionBytes = ByteArray(flushed16k.size * 2)
                            val currentGain = safeGainController.getCurrentGain()
                            for (i in flushed16k.indices) {
                                val s = flushed16k[i].toInt()
                                rnnoiseBytes[i * 2] = (s and 0xFF).toByte()
                                rnnoiseBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()

                                val f = (s * currentGain).toInt().coerceIn(-32768, 32767)
                                fusionBytes[i * 2] = (f and 0xFF).toByte()
                                fusionBytes[i * 2 + 1] = ((f shr 8) and 0xFF).toByte()

                                val y = flushed16k[i].toDouble()
                                rnnoiseSumOutputSquares += y * y
                            }
                            rnnoiseOutputSamplesCount += flushed16k.size
                            rnnoiseStream.write(rnnoiseBytes)
                            fusionStream?.write(fusionBytes)

                            // Clarity flush: consume exactly the number of samples RNNoise returned
                            if (clarityStream != null) {
                                val clarityFlushed = ShortArray(flushed16k.size)
                                clarityProcessor.processRnnoiseChunk(flushed16k, clarityFlushed, flushed16k.size)
                                val clarityBytes = ByteArray(clarityFlushed.size * 2)
                                for (i in clarityFlushed.indices) {
                                    val s = clarityFlushed[i].toInt()
                                    clarityBytes[i * 2] = (s and 0xFF).toByte()
                                    clarityBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                }
                                clarityStream.write(clarityBytes)
                            }

                            // Diagnostic correlation for flushed samples
                            val tempAligned = ShortArray(1)
                            for (i in flushed16k.indices) {
                                diagnosticRawDelayFifo.pop(1, tempAligned, 0)
                                val xAligned = tempAligned[0].toDouble()
                                val xUnaligned = if (diagnosticRawUnalignedFifo.isNotEmpty()) diagnosticRawUnalignedFifo.removeFirst().toDouble() else 0.0
                                val y = flushed16k[i].toDouble()

                                if (diagnosticComparedIndex >= ClarityAudioProcessor.RNNOISE_DELAY_SAMPLES_16K) {
                                    diagSumUnalignedX += xUnaligned
                                    diagSumUnalignedX2 += xUnaligned * xUnaligned
                                    diagSumUnalignedXY += xUnaligned * y

                                    diagSumAlignedX += xAligned
                                    diagSumAlignedX2 += xAligned * xAligned
                                    diagSumAlignedXY += xAligned * y

                                    diagSumY += y
                                    diagSumY2 += y * y

                                    diagSumAlignedAbsDiff += kotlin.math.abs(xAligned - y)
                                    if (tempAligned[0] != flushed16k[i]) {
                                        diagAlignedDiffCount++
                                    }
                                    diagnosticValidAlignedCount++
                                }
                                diagnosticComparedIndex++
                            }
                        }
                    }
                }
            } finally {
                gatedStream?.close()
                rnnoiseStream?.close()
                fusionStream?.close()
                clarityStream?.close()
            }
        } catch (error: Exception) {
            audioReadError = error.message ?: error.javaClass.simpleName
        } finally {
            audioDone.countDown()
        }
    }


    private fun measuredSensorRateHz(): Double {
        val count = sensorSamples.get()
        val durationNs = lastSensorTimestampNs - firstSensorTimestampNs
        return if (count > 1 && durationNs > 0) {
            (count - 1).toDouble() * 1_000_000_000.0 / durationNs.toDouble()
        } else {
            0.0
        }
    }

    private fun createSessionDirectory(label: String): File {
        val safeLabel = label.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "test" }
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)
        val root = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS),
            "VibeCallSessions"
        )
        check(root.exists() || root.mkdirs()) { "Could not create the session folder" }
        return File(root, "${formatter.format(Date())}_$safeLabel").also {
            check(it.mkdirs()) { "Could not create a new session" }
        }
    }

    private fun writeMetadata(
        directory: File,
        measuredRate: Double,
        averageTrust: Double,
        fusionStats: FusionRuntimeStats,
        rnnoiseStats: RnnoiseRuntimeStats,
        clarityStats: ClarityDiagnostics
    ) {
        val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val metadata = JSONObject().apply {
            put("format_version", 1)
            put("created_utc", utcFormatter.format(Date()))
            put("test_label", sessionLabel)
            put("recording_mode", currentRecordingMode.name)
            put("microphone_characterization", currentMicrophoneCharacterization)
            put("npu_fusion_enabled", true)
            put("npu_model_name", "fusion_gate_model.tflite")
            put("npu_delegate", "NNAPI delegate initialized — physical NPU not independently verified")
            put("legacy_trust_inference_count", trustInferenceCount)
            put("average_trust_value", averageTrust)
            put("gated_audio_file", if (gatedWavFile?.exists() == true) "gated_microphone.wav" else JSONObject.NULL)

            // Audio effects
            val effectsArray = JSONArray().apply {
                audioEffectStatuses.forEach { effect ->
                    put(JSONObject().apply {
                        put("name", effect.name)
                        put("available", effect.available)
                        put("was_enabled", effect.wasEnabled)
                        put("disabled_successfully", effect.disabledSuccessfully)
                        put("current_enabled", effect.currentEnabled)
                        put("error", effect.error ?: JSONObject.NULL)
                    })
                }
            }
            put("audio_effects", effectsArray)

            // RNNoise diagnostics
            put("rnnoise_enabled", rnnoiseWavFile?.exists() == true)
            put("rnnoise_audio_file", if (rnnoiseWavFile?.exists() == true) "microphone_rnnoise.wav" else JSONObject.NULL)
            put("rnnoise_delay_samples_16k", ClarityAudioProcessor.RNNOISE_DELAY_SAMPLES_16K)
            put("rnnoise_delay_ms", ClarityAudioProcessor.RNNOISE_DELAY_MS)
            put("raw_rnnoise_unaligned_correlation", rnnoiseStats.unalignedCorrelation)
            put("raw_rnnoise_aligned_correlation", rnnoiseStats.alignedCorrelation)
            val rnnoiseDiag = JSONObject().apply {
                put("initialized", rnnoiseStats.initialized)
                put("frame_count", rnnoiseStats.frameCount)
                put("failure_count", rnnoiseStats.failureCount)
                put("input_sample_count", rnnoiseStats.inputSampleCount)
                put("output_sample_count", rnnoiseStats.outputSampleCount)
                put("input_rms", rnnoiseStats.inputRms)
                put("output_rms", rnnoiseStats.outputRms)
                put("attenuation_db", rnnoiseStats.attenuationDb)
                put("mean_absolute_diff", rnnoiseStats.meanAbsoluteDiff)
                put("different_sample_percentage", rnnoiseStats.differentSamplePercentage)
                put("actual_audio_source", rnnoiseStats.actualAudioSource)
                put("delay_samples_16k", ClarityAudioProcessor.RNNOISE_DELAY_SAMPLES_16K)
                put("delay_ms", ClarityAudioProcessor.RNNOISE_DELAY_MS)
                put("unaligned_correlation", rnnoiseStats.unalignedCorrelation)
                put("aligned_correlation", rnnoiseStats.alignedCorrelation)
            }
            put("rnnoise_diagnostics", rnnoiseDiag)

            // Clarity track
            put("clarity_enabled", clarityWavFile?.exists() == true)
            put("clarity_audio_file", if (clarityWavFile?.exists() == true) "microphone_clarity.wav" else JSONObject.NULL)
            put("clarity_dry_mix_ratio", clarityStats.dryMixRatio)
            put("clarity_presence_eq_enabled", clarityStats.presenceEqEnabled)
            put("clarity_loudness_gain_mean_db", clarityStats.loudnessGainMeanDb)
            put("clarity_loudness_gain_max_db", clarityStats.loudnessGainMaxDb)
            put("clarity_limiter_threshold_dbfs", clarityStats.limiterThresholdDbfs)
            put("clarity_observed_peak_dbfs", clarityStats.observedPeakDbfs)
            put("clarity_clipped_sample_count", clarityStats.clippedSampleCount)

            put("features_file", if (featuresFile?.exists() == true) "features.csv" else JSONObject.NULL)
            put("feature_rolling_window_ms", 100)
            put("feature_spectral_window_ms", 250)
            put("feature_buffer_retention_ms", 350)
            put("feature_bandpass_hz", "80-185")
            put("feature_pitch_search_hz", "80-190")
            put("audio_accelerometer_alignment", "Audio sample bounds mapped from audioStartElapsedNs (elapsedRealtimeNanos). Sensor snapshots query samples <= audio end timestamp, excluding future samples. Uncertainty bounded by DMA delivery buffer latency (~5-15ms).")
            put("fusion_enabled", fusionWavFile?.exists() == true)
            put("fusion_audio_file", if (fusionWavFile?.exists() == true) "microphone_fusion.wav" else JSONObject.NULL)
            put("fusion_decisions_file", if (fusionDecisionsFile?.exists() == true) "fusion_decisions.csv" else JSONObject.NULL)
            put("fusion_model_name", "fusion_confidence_model.tflite")
            put("fusion_model_metadata_file", "fusion_model_metadata.json")
            put("fusion_model_input_dimension", 16)
            put("fusion_backend_status", fusionStats.backendStatus)
            put("fusion_inference_count", fusionStats.inferenceCount)
            put("fusion_confidence_inference_count", fusionStats.inferenceCount)
            put("fusion_failure_count", fusionStats.failureCount)
            put("fusion_avg_latency_us", fusionStats.avgLatencyUs)
            put("fusion_max_latency_us", fusionStats.maxLatencyUs)
            put("fusion_controller_min_gain", safeGainController.minimumGain)
            put("fusion_controller_pause_energy_threshold_db", safeGainController.pauseEnergyThresholdDb)
            put("fusion_controller_min_pause_windows", safeGainController.minConsecutivePauseWindows)
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_release", Build.VERSION.RELEASE)
            put("android_api", Build.VERSION.SDK_INT)
            put("session_start_elapsed_ns", sessionStartElapsedNs)
            put("audio_start_elapsed_ns", audioStartElapsedNs)
            put("session_end_elapsed_ns", sessionEndElapsedNs)
            put("audio_sample_rate_hz", AUDIO_SAMPLE_RATE)
            put("audio_channels", 1)
            put("audio_encoding", "PCM_16BIT")
            put("audio_source", audioSourceName)
            put("audio_samples", audioSamples.get())
            put("audio_read_error", audioReadError ?: JSONObject.NULL)
            put("requested_accelerometer_period_us", REQUESTED_SENSOR_PERIOD_US)
            put("measured_accelerometer_rate_hz", measuredRate)
            put("accelerometer_samples", sensorSamples.get())
            put("accelerometer_name", accelerometer.name)
            put("accelerometer_vendor", accelerometer.vendor)
            put("accelerometer_version", accelerometer.version)
            put("accelerometer_min_delay_us", accelerometer.minDelay)
            put("accelerometer_max_delay_us", accelerometer.maxDelay)
            put("accelerometer_resolution_m_s2", accelerometer.resolution)
            put("accelerometer_max_range_m_s2", accelerometer.maximumRange)
        }
        File(directory, "metadata.json").writeText(metadata.toString(2), Charsets.UTF_8)
    }

    private fun zipSession(directory: File): File {
        val zipFile = File(directory.parentFile, "${directory.name}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
            directory.listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.name }
                ?.forEach { file ->
                    zip.putNextEntry(ZipEntry(file.name))
                    FileInputStream(file).use { input -> input.copyTo(zip) }
                    zip.closeEntry()
                }
        }
        return zipFile
    }

    private fun writeWav(
        pcmFile: File,
        wavFile: File,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val pcmLength = pcmFile.length()
        FileOutputStream(wavFile).use { output ->
            writeAscii(output, "RIFF")
            writeLittleEndianInt(output, (36L + pcmLength).toInt())
            writeAscii(output, "WAVE")
            writeAscii(output, "fmt ")
            writeLittleEndianInt(output, 16)
            writeLittleEndianShort(output, 1)
            writeLittleEndianShort(output, channels)
            writeLittleEndianInt(output, sampleRate)
            val byteRate = sampleRate * channels * bitsPerSample / 8
            writeLittleEndianInt(output, byteRate)
            writeLittleEndianShort(output, channels * bitsPerSample / 8)
            writeLittleEndianShort(output, bitsPerSample)
            writeAscii(output, "data")
            writeLittleEndianInt(output, pcmLength.toInt())
            FileInputStream(pcmFile).use { input -> input.copyTo(output) }
        }
    }

    private fun writeAscii(output: OutputStream, value: String) {
        output.write(value.toByteArray(Charsets.US_ASCII))
    }

    private fun writeLittleEndianInt(output: OutputStream, value: Int) {
        output.write(
            ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(value)
                .array()
        )
    }

    private fun writeLittleEndianShort(output: OutputStream, value: Int) {
        output.write(
            ByteBuffer.allocate(2)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(value.toShort())
                .array()
        )
    }
}
