# VibeCall AI — Outgoing Speech Enhancement via Sensor Fusion

[![iQOO Hackathon 2026](https://img.shields.io/badge/iQOO_Hackathon-2026-yellow.svg)](https://github.com/aditya-elite/VibeCall-AI)
[![Team](https://img.shields.io/badge/Team-NPU_PULSE-blue.svg)](https://github.com/aditya-elite/VibeCall-AI)
[![Platform](https://img.shields.io/badge/Platform-Android_14%2B-green.svg)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](LICENSE)

> **"Noise cancellation for the voice you send — no extra wearable required."**

VibeCall AI is a phone-native outgoing speech enhancement system. It fuses ordinary microphone audio with cheek-contact vibrations captured by the smartphone's built-in accelerometer to isolate the caller's voice in severe public acoustic noise (traffic, crowds, transit).

---

## 👥 Team: NPU PULSE
*SSN College of Engineering, Chennai*

* **I Aditya Annamalai** — Android Sensing, High-Speed IMU Data Acquisition & Pipeline Engineering
* **Kavin Kumar L** — Multimodal Sensor Fusion, Audio Processing & ML Architecture
* **Hari Krishnan M** — Evaluation, Latency Benchmarking & Pitch Lead

---

## 🎯 Core Problem & Scientific Hypothesis

* **The Problem**: Conventional noise cancellation (earbuds ANC) only cancels what the user *hears*. When calling from noisy environments, the phone's microphone receives both the user's speech and ambient sound through the exact same airborne path.
* **The Solution**: When holding a phone to the cheek during a call, the chassis naturally contacts the user's face. The phone's internal high-speed accelerometer functions as a **contact microphone**, capturing bone/tissue-conducted vocal vibrations ($100–400\text{ Hz}$) completely immune to ambient airborne noise.

---

## 📊 Feasibility Study & Validation Results

Conducted using high-rate synchronized acquisition on a smartphone:

| Metric | Measured Value | Significance |
| :--- | :--- | :--- |
| **Accelerometer Sampling Rate** | **`~400.8 Hz`** | Requested 400 Hz via `HIGH_SAMPLING_RATE_SENSORS`; ultra-stable hardware rate held across all sessions. |
| **Microphone Vocal Fundamental** | **`144.53 Hz`** | Dominant voiced pitch peak detected via Welch PSD. |
| **Accelerometer Frequency Bin** | **`144.42 Hz`** | Dominant vibration peak on accelerometer Z-axis. |
| **Fundamental Frequency Delta** | **`Δ 0.114 Hz`** | **Decisive physical match**: confirms direct bone/tissue transmission of vocal cord vibration. |
| **Vibration Power Boost** | **`11.409×` (11.4×)** | Accelerometer Z-axis power during voicing vs. silence. |

---

## 🏗️ Architecture Pipeline

```
[Speaker's Mouth] ──(Airborne Path)────> [Microphone] ──> 16 kHz Mono PCM Audio ──┐
                                                                                  ├──> [Monotonic Sync] ──> [RNNoise + Accel Gate] ──> Clean Outgoing Voice
[Speaker's Cheek] ──(Bone Conduction)──> [IMU Accel]  ──> ~400 Hz 3-Axis Motion ──┘
```

1. **Synchronized Capture**: Android `AudioRecord` (16 kHz mono 16-bit PCM, `UNPROCESSED` source) and `SensorManager` (400 Hz, `TYPE_ACCELEROMETER`) locked to `SystemClock.elapsedRealtimeNanos()`.
2. **Feature Extraction & Alignment**: Aligns time axes, subtracts static $1\text{G}$ gravity tilt, and computes vibration power in the vocal fundamental band ($80–200\text{ Hz}$).
3. **No-Training Denoising Gate**: Pretrained **RNNoise** denoiser dynamically boosted and gated by accelerometer contact energy to preserve true speech while eliminating overlapping background noise.
4. **On-Device Target**: Designed for Snapdragon NPU acceleration on devices such as the iQOO 15.

---

## 📱 Android Feasibility App (`app/`)

The Android application records synchronized sessions with one touch:

- **Presets**:
  - `Table - silent baseline`: Sensor noise floor and stationary calibration.
  - `Cheek - speaking in quiet`: Natural contact calibration.
  - `Cheek - speaking with background noise`: Realistic acoustic interference validation.
- **Export**: Generates a shareable `.zip` containing:
  - `microphone.wav` (16 kHz 16-bit mono WAV)
  - `accelerometer.csv` (monotonic hardware timestamps and 3-axis readings)
  - `metadata.json` (device model, vendor, sensor delay, sample rates)
- **Direct Share**: Built-in Android `FileProvider` export to laptop or cloud with one tap.

### Building & Running
1. Open the project in **Android Studio**.
2. Ensure **Gradle JDK** is set to **JDK 17** or **JDK 21** (`Settings -> Build Tools -> Gradle`).
3. Connect your Android phone with **USB Debugging** enabled.
4. Click **Run (▶)** and grant microphone permissions.

---

## 💻 Desktop Analysis Tool (`tools/`)

To analyze and plot an exported session ZIP:

```powershell
# 1. Set up Python virtual environment
py -m venv .venv
.venv\Scripts\Activate.ps1

# 2. Install dependencies
pip install numpy scipy matplotlib

# 3. Generate analysis report
python tools\analyze_session.py path\to\session.zip --output report.png
```

The tool produces a synchronized 4-panel analysis:
1. Microphone waveform
2. Microphone spectrogram ($0–4\text{ kHz}$)
3. Accelerometer vibration magnitude ($m/s^2$)
4. Accelerometer spectrogram ($0–f_s/2$)

---

## 🗺️ Project Roadmap
- [x] **Milestone 1**: Feasibility sensor acquisition app & ground-truth validation (Current)
- [x] **Milestone 2**: Frequency correlation & proof of cheek-conducted acoustic resonance
- [ ] **Milestone 3**: RNNoise + accelerometer energy gating integration
- [ ] **Milestone 4**: Objective A/B audio benchmark ($\ge 3\text{ dB}$ SNR improvement)
- [ ] **Milestone 5**: Qualcomm Snapdragon NPU quantization & latency optimization for iQOO 15
