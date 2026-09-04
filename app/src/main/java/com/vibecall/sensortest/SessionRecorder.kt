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
    val measuredSensorRateHz: Double
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

    val isRecording: Boolean
        get() = recording

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
        sessionStartElapsedNs = SystemClock.elapsedRealtimeNanos()

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
        audioExecutor.execute {
            recordAudioLoop(recorder, outputPcm)
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

                val directory = sessionDirectory ?: error("Missing session directory")
                val measuredRate = measuredSensorRateHz()
                writeMetadata(directory, measuredRate)
                val zip = zipSession(directory)

                SessionResult(
                    sessionDirectory = directory,
                    zipFile = zip,
                    accelerometerSamples = sensorSamples.get(),
                    audioSamples = audioSamples.get(),
                    measuredSensorRateHz = measuredRate
                )
            }
            onComplete(result)
        }, "vibecall-finalize").start()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!recording || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

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

    private fun recordAudioLoop(recorder: AudioRecord, outputFile: File) {
        val buffer = ByteArray(4_096)
        try {
            FileOutputStream(outputFile).use { output ->
                while (recording) {
                    val read = recorder.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                    when {
                        read > 0 -> {
                            output.write(buffer, 0, read)
                            audioSamples.addAndGet((read / 2).toLong())
                        }
                        read < 0 -> {
                            if (recording) {
                                audioReadError = "AudioRecord.read returned $read"
                            }
                            break
                        }
                    }
                }
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

    private fun writeMetadata(directory: File, measuredRate: Double) {
        val utcFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val metadata = JSONObject().apply {
            put("format_version", 1)
            put("created_utc", utcFormatter.format(Date()))
            put("test_label", sessionLabel)
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
