package com.vibecall.sensortest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.android.material.textfield.TextInputLayout
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var recorder: SessionRecorder
    private lateinit var testInputLayout: TextInputLayout
    private lateinit var testAutoCompleteTextView: AutoCompleteTextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var shareButton: Button
    private lateinit var statusText: TextView
    private lateinit var rateText: TextView
    private lateinit var timerText: TextView
    private lateinit var statusDot: View
    private lateinit var recordingStateText: TextView

    private var latestZip: File? = null
    private var recordingStartedMs = 0L
    private val uiHandler = Handler(Looper.getMainLooper())

    private val timerTask = object : Runnable {
        override fun run() {
            if (!recorder.isRecording) return
            val elapsed = SystemClock.elapsedRealtime() - recordingStartedMs
            val minutes = elapsed / 60_000
            val seconds = (elapsed % 60_000) / 1_000
            val tenths = (elapsed % 1_000) / 100
            timerText.text = String.format(Locale.US, "%02d:%02d.%d", minutes, seconds, tenths)
            uiHandler.postDelayed(this, 100)
        }
    }

    private val microphonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            beginRecording()
        } else {
            statusText.text = "Microphone permission is required to record a test session."
            Toast.makeText(this, "Microphone permission was not granted", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        testInputLayout = findViewById(R.id.testInputLayout)
        testAutoCompleteTextView = findViewById(R.id.testAutoCompleteTextView)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)
        shareButton = findViewById(R.id.shareButton)
        statusText = findViewById(R.id.statusText)
        rateText = findViewById(R.id.rateText)
        timerText = findViewById(R.id.timerText)
        statusDot = findViewById(R.id.statusDot)
        recordingStateText = findViewById(R.id.recordingStateText)

        recorder = SessionRecorder(this) { rate, count ->
            runOnUiThread {
                rateText.text = String.format(
                    Locale.US,
                    "Accelerometer rate: %.1f Hz  |  %,d samples",
                    rate,
                    count
                )
            }
        }

        findViewById<TextView>(R.id.deviceInfoText).text = recorder.deviceSummary()

        val tests = listOf(
            "Table - silent baseline",
            "Cheek - speaking in quiet",
            "Cheek - speaking with background noise"
        )
        val adapter = ArrayAdapter(
            this,
            R.layout.dropdown_menu_item,
            tests
        )
        testAutoCompleteTextView.setAdapter(adapter)
        testAutoCompleteTextView.setText(tests[0], false)

        startButton.setOnClickListener { requestPermissionAndStart() }
        stopButton.setOnClickListener { finishRecording() }
        shareButton.setOnClickListener { shareLatestSession() }
    }

    private fun requestPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            beginRecording()
        } else {
            microphonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginRecording() {
        val label = testAutoCompleteTextView.text.toString()
        runCatching { recorder.start(label) }
            .onSuccess {
                recordingStartedMs = SystemClock.elapsedRealtime()
                timerText.text = "00:00.0"
                rateText.text = "Accelerometer rate: measuring…"
                statusText.text = "Recording: $label\nSpeak normally and keep the phone in the stated position."

                statusDot.setBackgroundResource(R.drawable.recording_dot_active)
                recordingStateText.text = getString(R.string.recording_status_active)
                recordingStateText.setTextColor(ContextCompat.getColor(this, R.color.vibe_recording_red))

                startButton.isEnabled = false
                stopButton.isEnabled = true
                shareButton.isEnabled = false
                testInputLayout.isEnabled = false
                testAutoCompleteTextView.isEnabled = false
                uiHandler.post(timerTask)
            }
            .onFailure { error ->
                statusText.text = "Could not start recording: ${error.message}"
                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
            }
    }

    private fun finishRecording() {
        stopButton.isEnabled = false
        statusText.text = "Saving WAV, CSV and metadata…"
        uiHandler.removeCallbacks(timerTask)

        recorder.stop { result ->
            runOnUiThread {
                result.onSuccess { session ->
                    latestZip = session.zipFile
                    statusText.text = String.format(
                        Locale.US,
                        "Saved successfully.\nAccelerometer: %,d samples at %.1f Hz\nAudio: %,d samples\nFile: %s",
                        session.accelerometerSamples,
                        session.measuredSensorRateHz,
                        session.audioSamples,
                        session.zipFile.name
                    )
                    shareButton.isEnabled = true
                }.onFailure { error ->
                    statusText.text = "Could not save the session: ${error.message}"
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }

                statusDot.setBackgroundResource(R.drawable.recording_dot_idle)
                recordingStateText.text = getString(R.string.recording_status_idle)
                recordingStateText.setTextColor(ContextCompat.getColor(this, R.color.vibe_text_secondary))

                startButton.isEnabled = true
                stopButton.isEnabled = false
                testInputLayout.isEnabled = true
                testAutoCompleteTextView.isEnabled = true
            }
        }
    }

    private fun shareLatestSession() {
        val file = latestZip ?: return
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.files",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export VibeCall session"))
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (recorder.isRecording) {
            Toast.makeText(this, "Stop and save the recording before leaving", Toast.LENGTH_SHORT).show()
            return
        }
        super.onBackPressed()
    }

    override fun onDestroy() {
        uiHandler.removeCallbacks(timerTask)
        if (!recorder.isRecording) recorder.close()
        super.onDestroy()
    }
}
