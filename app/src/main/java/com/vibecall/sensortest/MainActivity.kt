package com.vibecall.sensortest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
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
    private lateinit var playDenoisedButton: Button
    private lateinit var playRawButton: Button
    private lateinit var playFusionButton: Button
    private lateinit var playbackStatusBadge: TextView
    private lateinit var playbackHelperText: TextView
    private lateinit var statusText: TextView
    private lateinit var rateText: TextView
    private lateinit var timerText: TextView
    private lateinit var statusDot: View
    private lateinit var recordingStateText: TextView

    private var latestZip: File? = null
    private var latestDenoisedWav: File? = null
    private var latestRawWav: File? = null
    private var latestFusionWav: File? = null
    private var mediaPlayer: MediaPlayer? = null

    private enum class AudioTrack { NONE, RAW, DENOISED, FUSION }
    private var currentlyPlaying = AudioTrack.NONE

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
        playDenoisedButton = findViewById(R.id.playDenoisedButton)
        playRawButton = findViewById(R.id.playRawButton)
        playFusionButton = findViewById(R.id.playFusionButton)
        playbackStatusBadge = findViewById(R.id.playbackStatusBadge)
        playbackHelperText = findViewById(R.id.playbackHelperText)
        statusText = findViewById(R.id.statusText)
        rateText = findViewById(R.id.rateText)
        timerText = findViewById(R.id.timerText)
        statusDot = findViewById(R.id.statusDot)
        recordingStateText = findViewById(R.id.recordingStateText)

        recorder = SessionRecorder(
            context = this,
            onRateUpdate = { rate, count ->
                runOnUiThread {
                    rateText.text = String.format(
                        Locale.US,
                        "Accelerometer rate: %.1f Hz  |  %,d samples",
                        rate,
                        count
                    )
                }
            },
            onTelemetryUpdate = { telemetry ->
                runOnUiThread {
                    val micStr = if (telemetry.microphonePitchReliable) {
                        String.format(Locale.US, "Mic: %.0fHz", telemetry.microphonePitchHz)
                    } else {
                        "Mic: --"
                    }
                    val vibStr = if (telemetry.accelPeakReliable) {
                        String.format(Locale.US, "Vib(%s): %.0fHz", telemetry.accelBestAxis, telemetry.accelPeakHz)
                    } else {
                        String.format(Locale.US, "Vib: %.2f", telemetry.vibrationRms)
                    }
                    val agreeStr = if (telemetry.pitchAgreementReliable) {
                        String.format(Locale.US, "Δ: %.1fHz (%.2f)", telemetry.pitchDifferenceHz, telemetry.pitchAgreementScore)
                    } else {
                        "Agree: --"
                    }
                    val fusionStr = if (telemetry.fusionControllerState != "PRESERVE") {
                        String.format(Locale.US, "Fusion: %.2f (G:%.2f)", telemetry.fusionConfidence, telemetry.appliedGain)
                    } else {
                        String.format(Locale.US, "Fusion: %.2f", telemetry.fusionConfidence)
                    }
                    rateText.text = String.format(
                        Locale.US,
                        "%.1f Hz | %s | %s | %s | %s",
                        telemetry.measuredRateHz,
                        micStr,
                        vibStr,
                        agreeStr,
                        fusionStr
                    )
                }
            }
        )

        findViewById<TextView>(R.id.deviceInfoText).text = recorder.deviceSummary()

        // Verify Fusion Model hardware acceleration on launch
        val npuText = findViewById<TextView>(R.id.npuStatusText)
        val npuIcon = findViewById<ImageView>(R.id.npuStatusIcon)
        try {
            val warmup = FusionConfidenceModel(this)
            val backend = warmup.getBackendStatus()
            warmup.close()
            npuText.text = "Fusion Backend: $backend"
            when {
                backend.contains("NNAPI") -> {
                    npuText.setTextColor(ContextCompat.getColor(this, R.color.vibe_success_green))
                    npuIcon.setImageResource(R.drawable.ic_shield_check)
                    npuIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.vibe_success_green)
                }
                backend.contains("CPU") -> {
                    npuText.setTextColor(ContextCompat.getColor(this, R.color.vibe_primary))
                    npuIcon.setImageResource(R.drawable.ic_info)
                    npuIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.vibe_primary)
                }
                else -> {
                    npuText.setTextColor(ContextCompat.getColor(this, R.color.vibe_recording_red))
                    npuIcon.setImageResource(R.drawable.ic_info)
                    npuIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.vibe_recording_red)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Fusion model init check: ${e.message}")
            npuText.text = "Fusion Backend: CPU Fallback (${e.message ?: "Check Logcat"})"
            npuText.setTextColor(ContextCompat.getColor(this, R.color.vibe_recording_red))
            npuIcon.setImageResource(R.drawable.ic_info)
            npuIcon.imageTintList = ContextCompat.getColorStateList(this, R.color.vibe_recording_red)
        }

        // Setup enhanced presets with icons, badges and descriptions
        val presets = listOf(
            PresetItem(
                "Cheek - speaking with background noise",
                "Key A/B benchmark (vocal cord vs ambient noise)",
                R.drawable.ic_wave,
                R.color.vibe_recording_red
            ),
            PresetItem(
                "Cheek - speaking in quiet",
                "Clean vocal vibration reference",
                R.drawable.ic_mic,
                R.color.vibe_accent
            ),
            PresetItem(
                "Table - silent baseline",
                "Stationary sensor noise floor",
                R.drawable.ic_sensors,
                R.color.vibe_primary
            )
        )
        val adapter = PresetAdapter(this, presets)
        testAutoCompleteTextView.setAdapter(adapter)
        testAutoCompleteTextView.setText(presets[0].title, false)

        startButton.setOnClickListener { requestPermissionAndStart() }
        stopButton.setOnClickListener { finishRecording() }
        shareButton.setOnClickListener { shareLatestSession() }

        playDenoisedButton.setOnClickListener {
            playAudio(latestDenoisedWav, AudioTrack.DENOISED)
        }
        playRawButton.setOnClickListener {
            playAudio(latestRawWav, AudioTrack.RAW)
        }
        playFusionButton.setOnClickListener {
            playAudio(latestFusionWav, AudioTrack.FUSION)
        }
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
        stopAudioPlayback()
        playDenoisedButton.isEnabled = false
        playRawButton.isEnabled = false
        playFusionButton.isEnabled = false
        playbackStatusBadge.text = "RECORDING"
        playbackStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vibe_recording_red))
        playbackHelperText.text = "Recording in progress… Speak normally and keep phone in place."

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
                updatePlaybackUi(isPlaying = false, track = AudioTrack.NONE)
            }
    }

    private fun finishRecording() {
        stopButton.isEnabled = false
        statusText.text = "Saving WAV, CSV, RNNoise denoised audio and metadata…"
        uiHandler.removeCallbacks(timerTask)

        recorder.stop { result ->
            runOnUiThread {
                result.onSuccess { session ->
                    latestZip = session.zipFile
                    latestDenoisedWav = session.rnnoiseWavFile ?: File(session.sessionDirectory, "microphone_rnnoise.wav")
                    latestRawWav = File(session.sessionDirectory, "microphone.wav")
                    latestFusionWav = session.fusionWavFile ?: File(session.sessionDirectory, "microphone_fusion.wav")
                    updatePlaybackUi(isPlaying = false, track = AudioTrack.NONE)

                    statusText.text = String.format(
                        Locale.US,
                        "Saved successfully.\nAccelerometer: %,d samples at %.1f Hz\nAudio: %,d samples\nFiles: microphone.wav, microphone_rnnoise.wav, microphone_fusion.wav\nExport: %s",
                        session.accelerometerSamples,
                        session.measuredSensorRateHz,
                        session.audioSamples,
                        session.zipFile.name
                    )
                    shareButton.isEnabled = true
                }.onFailure { error ->
                    updatePlaybackUi(isPlaying = false, track = AudioTrack.NONE)
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

    private fun playAudio(file: File?, track: AudioTrack) {
        if (file == null || !file.exists()) {
            Toast.makeText(this, "Audio file not available yet", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentlyPlaying == track) {
            stopAudioPlayback()
            return
        }

        stopAudioPlayback()

        try {
            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    stopAudioPlayback()
                }
                start()
            }
            mediaPlayer = player
            currentlyPlaying = track
            updatePlaybackUi(isPlaying = true, track = track)
        } catch (e: Exception) {
            Log.e("MainActivity", "Audio playback failed: ${e.message}", e)
            Toast.makeText(this, "Could not play audio: ${e.message}", Toast.LENGTH_SHORT).show()
            stopAudioPlayback()
        }
    }

    private fun stopAudioPlayback() {
        mediaPlayer?.runCatching {
            if (isPlaying) stop()
            release()
        }
        mediaPlayer = null
        currentlyPlaying = AudioTrack.NONE
        updatePlaybackUi(isPlaying = false, track = AudioTrack.NONE)
    }

    private fun updatePlaybackUi(isPlaying: Boolean, track: AudioTrack) {
        val hasDenoised = latestDenoisedWav?.exists() == true
        val hasRaw = latestRawWav?.exists() == true
        val hasFusion = latestFusionWav?.exists() == true

        playDenoisedButton.isEnabled = !recorder.isRecording && hasDenoised
        playRawButton.isEnabled = !recorder.isRecording && hasRaw
        playFusionButton.isEnabled = !recorder.isRecording && hasFusion

        if (isPlaying) {
            when (track) {
                AudioTrack.DENOISED -> {
                    playDenoisedButton.text = "⏹ Stop RNNoise"
                    playRawButton.text = "Play Raw Mic"
                    playFusionButton.text = "Play Fusion (Exp.)"
                    playbackStatusBadge.text = "PLAYING RNNOISE"
                    playbackStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vibe_success_green))
                    playbackHelperText.text = "Playing RNNoise baseline audio (verified neural noise suppression)."
                }
                AudioTrack.RAW -> {
                    playDenoisedButton.text = "Play RNNoise"
                    playRawButton.text = "⏹ Stop Raw"
                    playFusionButton.text = "Play Fusion (Exp.)"
                    playbackStatusBadge.text = "PLAYING RAW MIC"
                    playbackStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vibe_primary))
                    playbackHelperText.text = "Playing raw microphone audio (original environment noise)."
                }
                AudioTrack.FUSION -> {
                    playDenoisedButton.text = "Play RNNoise"
                    playRawButton.text = "Play Raw Mic"
                    playFusionButton.text = "⏹ Stop Fusion"
                    playbackStatusBadge.text = "PLAYING FUSION (EXP.)"
                    playbackStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vibe_fusion_purple))
                    playbackHelperText.text = "Playing experimental fusion audio (SafeGainController + RNNoise neural baseline)."
                }
                AudioTrack.NONE -> Unit
            }
        } else {
            playDenoisedButton.text = "Play RNNoise"
            playRawButton.text = "Play Raw Mic"
            playFusionButton.text = "Play Fusion (Exp.)"
            if (hasDenoised || hasRaw || hasFusion) {
                playbackStatusBadge.text = "READY TO AUDITION"
                playbackStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vibe_primary))
                playbackHelperText.text = "Tap to audition: Raw vs RNNoise (baseline) vs Fusion (experimental)."
            } else {
                playbackStatusBadge.text = "NO RECORDING"
                playbackStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.vibe_text_secondary))
                playbackHelperText.text = "Record a test session to audition RNNoise baseline vs raw audio vs experimental fusion."
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

    override fun onStop() {
        super.onStop()
        stopAudioPlayback()
    }

    override fun onDestroy() {
        stopAudioPlayback()
        uiHandler.removeCallbacks(timerTask)
        if (!recorder.isRecording) recorder.close()
        super.onDestroy()
    }

}

data class PresetItem(
    val title: String,
    val subtitle: String,
    val iconRes: Int,
    val tintColorRes: Int
) {
    override fun toString(): String = title
}

class PresetAdapter(
    context: Context,
    private val items: List<PresetItem>
) : ArrayAdapter<PresetItem>(context, R.layout.dropdown_menu_item, android.R.id.text1, items) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        val item = items[position]
        val icon = view.findViewById<ImageView>(R.id.itemIcon)
        val title = view.findViewById<TextView>(android.R.id.text1)
        val subtitle = view.findViewById<TextView>(R.id.itemSubtitle)

        title.text = item.title
        subtitle.text = item.subtitle
        icon.setImageResource(item.iconRes)
        icon.imageTintList = ContextCompat.getColorStateList(context, item.tintColorRes)
        return view
    }

    override fun getItem(position: Int): PresetItem = items[position]
}

