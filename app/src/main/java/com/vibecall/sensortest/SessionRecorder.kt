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
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
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
    val rnnoiseWavFile: File? = null
)

class SessionRecorder(
    private val context: Context,
    private val onRateUpdate: (Double, Long) -> Unit
) : SensorEventListener {

    companion object {
        const val AUDIO_SAMPLE_RATE = 16_000
        const val REQUESTED_SENSOR_PERIOD_US = 2_500 // Request 400 Hz; hardware decides actual rate.
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

    // NPU Fusion Gate Model integration
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

    val isRecording: Boolean
        get() = recording

    val currentTrustValue: Float
        get() = latestTrustValue

    val latestRnnoiseWav: File?
        get() = rnnoiseWavFile

    val latestRawWav: File?
        get() = wavFile


    fun deviceSummary(): String = buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Accelerometer: ${accelerometer.name}")
        appendLine("Vendor: ${accelerometer.vendor}")
        append("Reported minimum delay: ${accelerometer.minDelay} µs")
    }

    @Synchronized
    fun start(label: String) {
        check(!recording) { "A session is already recording" }

        sessionLabel = label
        val directory = createSessionDirectory(label)
        sessionDirectory = directory
        pcmFile = File(directory, "microphone.pcm")
        wavFile = File(directory, "microphone.wav")
        gatedPcmFile = File(directory, "gated_microphone.pcm")
        gatedWavFile = File(directory, "gated_microphone.wav")
        rnnoisePcmFile = File(directory, "microphone_rnnoise.pcm")
        rnnoiseWavFile = File(directory, "microphone_rnnoise.wav")
        sensorWriter = BufferedWriter(
            OutputStreamWriter(FileOutputStream(File(directory, "accelerometer.csv")), Charsets.UTF_8),
            64 * 1024
        ).apply {
            write("sensor_timestamp_ns,relative_to_audio_start_ns,x_m_s2,y_m_s2,z_m_s2,accuracy\n")
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

        fusionGateModel = runCatching { FusionGateModel(context) }
            .onFailure { Log.w("SessionRecorder", "Failed to initialize FusionGateModel", it) }
            .getOrNull()

        rnnoiseProcessor = runCatching { RnnoiseProcessor() }
            .onFailure { Log.w("SessionRecorder", "Failed to initialize RnnoiseProcessor", it) }
            .getOrNull()


        val recorder = buildAudioRecord()
        audioRecord = recorder
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
            recorder.release()
            sensorWriter?.close()
            throw IllegalStateException("Android could not register the accelerometer listener")
        }

        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recording = false
            sensorManager.unregisterListener(this)
            recorder.release()
            sensorWriter?.close()
            throw IllegalStateException("The microphone did not enter the recording state")
        }
        audioStartElapsedNs = SystemClock.elapsedRealtimeNanos()

        audioDone = CountDownLatch(1)
        val outputPcm = pcmFile ?: error("PCM output was not created")
        val outputGatedPcm = gatedPcmFile
        audioExecutor.execute {
            recordAudioLoop(recorder, outputPcm, outputGatedPcm)
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

                fusionGateModel?.close()
                fusionGateModel = null
                rnnoiseProcessor?.close()
                rnnoiseProcessor = null

                val directory = sessionDirectory ?: error("Missing session directory")
                val measuredRate = measuredSensorRateHz()
                val avgTrust = if (trustInferenceCount > 0) totalTrustValue / trustInferenceCount else 1.0
                writeMetadata(directory, measuredRate, avgTrust)
                val zip = zipSession(directory)

                SessionResult(
                    sessionDirectory = directory,
                    zipFile = zip,
                    accelerometerSamples = sensorSamples.get(),
                    audioSamples = audioSamples.get(),
                    measuredSensorRateHz = measuredRate,
                    averageTrustValue = avgTrust,
                    rnnoiseWavFile = rnnoiseWav
                )
            }
            onComplete(result)
        }, "vibecall-finalize").start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

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

        if (count % 100L == 0L) {
            onRateUpdate(measuredSensorRateHz(), count)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun close() {
        if (!recording) {
            fusionGateModel?.close()
            fusionGateModel = null
            rnnoiseProcessor?.close()
            rnnoiseProcessor = null
            sensorThread.quitSafely()
            audioExecutor.shutdown()
        }
    }


    private fun buildAudioRecord(): AudioRecord {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val unprocessedSupported = audioManager
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED)
            ?.equals("true", ignoreCase = true) == true

        val source = if (unprocessedSupported) {
            audioSourceName = "UNPROCESSED"
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            audioSourceName = "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

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

        return AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(format)
            .setBufferSizeInBytes(max(minimum * 2, 8_192))
            .build()
            .also {
                check(it.state == AudioRecord.STATE_INITIALIZED) {
                    "Android could not initialise the microphone"
                }
            }
    }

    private fun recordAudioLoop(recorder: AudioRecord, outputFile: File, gatedFile: File?) {
        val buffer = ByteArray(4_096)
        try {
            val gatedStream = gatedFile?.let { FileOutputStream(it) }
            val rnnoiseStream = rnnoisePcmFile?.let { FileOutputStream(it) }
            try {
                FileOutputStream(outputFile).use { output ->
                    while (recording) {
                        val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        when {
                            read > 0 -> {
                                // 1. Preserve original microphone PCM capture unchanged
                                output.write(buffer, 0, read)
                                audioSamples.addAndGet((read / 2).toLong())

                                // 2. Convert buffer bytes to ShortArray for variance, gating, and denoising
                                val numSamples = read / 2
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

                                // 3. Run NPU fusion gate model with audioVariance & accelerometer readings
                                val trust = fusionGateModel?.getTrustValue(
                                    audioVariance = variance,
                                    accelX = latestAccelX,
                                    accelY = latestAccelY,
                                    accelZ = latestAccelZ
                                ) ?: 1.0f

                                latestTrustValue = trust
                                totalTrustValue += trust
                                trustInferenceCount++

                                // 4. Scale audio by trustValue to produce gated audio output
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
                                if (rnnoiseStream != null && rnnoiseProcessor != null) {
                                    val denoised16k = rnnoiseProcessor?.processStream(shortSamples, numSamples)
                                    if (denoised16k != null && denoised16k.isNotEmpty()) {
                                        val rnnoiseBytes = ByteArray(denoised16k.size * 2)
                                        for (i in denoised16k.indices) {
                                            val s = denoised16k[i].toInt()
                                            rnnoiseBytes[i * 2] = (s and 0xFF).toByte()
                                            rnnoiseBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                                        }
                                        rnnoiseStream.write(rnnoiseBytes)
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

                    // Flush any remaining buffered audio in RNNoise accumulator
                    if (rnnoiseStream != null && rnnoiseProcessor != null) {
                        val flushed16k = rnnoiseProcessor?.flush()
                        if (flushed16k != null && flushed16k.isNotEmpty()) {
                            val rnnoiseBytes = ByteArray(flushed16k.size * 2)
                            for (i in flushed16k.indices) {
                                val s = flushed16k[i].toInt()
                                rnnoiseBytes[i * 2] = (s and 0xFF).toByte()
                                rnnoiseBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                            }
                            rnnoiseStream.write(rnnoiseBytes)
                        }
                    }
                }
            } finally {
                gatedStream?.close()
                rnnoiseStream?.close()
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

    private fun writeMetadata(directory: File, measuredRate: Double, averageTrust: Double) {
        val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val metadata = JSONObject().apply {
            put("format_version", 1)
            put("created_utc", utcFormatter.format(Date()))
            put("test_label", sessionLabel)
            put("npu_fusion_enabled", true)
            put("npu_model_name", "fusion_gate_model.tflite")
            put("npu_delegate", "NNAPI")
            put("fusion_inference_count", trustInferenceCount)
            put("average_trust_value", averageTrust)
            put("gated_audio_file", if (gatedWavFile?.exists() == true) "gated_microphone.wav" else JSONObject.NULL)
            put("rnnoise_enabled", rnnoiseWavFile?.exists() == true)
            put("rnnoise_audio_file", if (rnnoiseWavFile?.exists() == true) "microphone_rnnoise.wav" else JSONObject.NULL)
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
