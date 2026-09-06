# VibeCall AI — Outgoing Speech Enhancement via Sensor Fusion

[![iQOO Hackathon 2026](https://img.shields.io/badge/iQOO_Hackathon-2026-yellow.svg)](https://github.com/aditya-elite/VibeCall-AI) [![Team](https://img.shields.io/badge/Team-NPU_PULSE-blue.svg)](https://github.com/aditya-elite/VibeCall-AI) [![Platform](https://img.shields.io/badge/Platform-Android_14%2B-green.svg)](https://developer.android.com) [![License](https://img.shields.io/badge/License-MIT-lightgrey.svg)](https://github.com/aditya-elite/VibeCall-AI/blob/main/LICENSE) [![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-purple.svg)](https://kotlinlang.org)

> **"Noise cancellation for the voice you send — no extra wearable required."**

**VibeCall AI** is a phone-native outgoing speech enhancement system. It fuses standard smartphone microphone audio with cheek-contact vibrations captured by the built-in accelerometer to cleanly isolate the caller's voice in severe public acoustic noise (traffic, crowds, transit).

---

## 👥 Team: NPU PULSE

*SSN College of Engineering, Chennai*

- **I Aditya Annamalai** — Android Sensing, High-Speed IMU Data Acquisition & Pipeline Engineering
- **Kavin Kumar L** — Multimodal Sensor Fusion, Audio Processing & ML Architecture
- **Hari Krishnan M** — Evaluation, Latency Benchmarking & Pitch Lead

---

## 💡 The Core Insight & Competitive Edge

- **The Gap**: Traditional active noise cancellation (ANC in earbuds) only cancels what the listener hears. When you make an outgoing call from a noisy street, the microphone receives your voice and background chaos through the exact same acoustic path.
- **The Physical Solution**: The phone already touches your cheek during a call. That physical contact point becomes a **second speech path**.
- **Bone Conduction via Phone Hardware**: The phone's internal high-speed accelerometer functions as a **contact microphone**, capturing bone/tissue-conducted vocal resonance (100–400 Hz) that is physically immune to airborne acoustic noise.
- **Sensor fusion for voice isn't new** — bone-conduction accelerometers exist in earbuds (Samsung's Voice Pickup Unit, Apple's patents). VibeCall's contribution is doing this with the phone's own generic sensor — no dedicated hardware, works handheld, on speaker, or with any wired headset.

### Comparison Against Existing Approaches

| Approach                       | Extra Hardware Needed?       | Works Without Wearables? | Where Does Fusion Happen?                                |
| ------------------------------ | ---------------------------- | ------------------------ | -------------------------------------------------------- |
| **Mic-Only AI** (e.g. Krisp)   | ❌ No                         | ✅ Yes                    | Acoustic audio only (guesses when voice & noise overlap) |
| **Cloud Noise Filters**        | ❌ No                         | ✅ Yes                    | Server-side (adds latency, privacy concerns)             |
| **Earbud VPU** (Samsung/Apple) | ⚠️ **Yes** (specific earbud) | ❌ No                     | Inside the proprietary earbud                            |
| **VibeCall AI** (Our Approach) | ❌ **None**                   | ✅ **Yes**                | **Phone-native on-device (CPU / NPU)**                   |

---

## 📊 Feasibility Study & Empirical Evidence

Our hardware feasibility experiment on an Android smartphone validated the core physical premise:

[![VibeCall AI Spectral Validation Evidence](https://github.com/aditya-elite/VibeCall-AI/raw/main/docs/images/spectral_evidence.png)](/aditya-elite/VibeCall-AI/blob/main/docs/images/spectral_evidence.png)

### Quantitative Validation

| Measured Quantity                | Experimental Result    | Scientific Significance                                                                                      |
| -------------------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------------ |
| **Accelerometer Sampling Rate**  | **`400.8 – 401.6 Hz`** | Requested 400 Hz via `HIGH_SAMPLING_RATE_SENSORS`; ultra-stable hardware rate held across all sessions.      |
| **Microphone Vocal Fundamental** | **`144.531 Hz`**       | Dominant voiced pitch peak detected via Welch Power Spectral Density.                                        |
| **Accelerometer Matching Bin**   | **`144.417 Hz`**       | Dominant vibration peak on accelerometer Z-axis.                                                             |
| **Frequency Delta**              | **`Δ 0.114 Hz`**       | **Decisive physical match**: confirms direct bone/tissue transmission of vocal cord vibration to the sensor. |
| **Vibration Power Boost**        | **`11.409×` (11.4×)**  | Z-axis accelerometer power during voicing compared to silence.                                               |

---

## 🔬 Verified On-Device Results

In live evaluations under heavy acoustic background noise, here is what has actually been measured running on real hardware — not simulated:

[![VibeCall AI Multimodal Evidence and Noise Suppression](https://github.com/aditya-elite/VibeCall-AI/raw/main/docs/images/test_c_comparison.png)](/aditya-elite/VibeCall-AI/blob/main/docs/images/test_c_comparison.png)

### RNNoise (CPU) — Verified On-Device, Real Result

- **Background noise floor**: cut by **50+ dB** (down to the recording's digital noise floor)
- **Speech peak level**: preserved within **0.7 dB** of the original
- Confirmed via `rnnoise_enabled: true` in real session metadata, and direct waveform analysis of actual on-device recordings — not an offline simulation.

### NPU Fusion Gate — Running on Real Hardware, Audio Quality Still Being Tuned

- **Vocal Vibration (0.0–2.0s)**: Z-axis vibration elevates to **1.01–2.69 m/s²** during phonation.
- **Ambient Noise Pause (2.0–4.0s)**: While the microphone is flooded with loud ambient noise, accelerometer vibration drops to **0.35–0.53 m/s²**.
- **Model v2 On-Device Gating**: `fusion_gate_model_v2.tflite` achieved a live on-device NNAPI average trust of **0.305**, confirmed executing on real Snapdragon NPU hardware (49 inferences, zero dropouts).
- **Honest status**: real listening A/B tests show RNNoise-alone currently sounds better than gate+RNNoise combined — the gate over-suppresses speech in its current calibration. The live demo uses RNNoise-only audio while gate recalibration continues. See [docs/TEST_RESULTS_AND_NEXT_STEPS.md](https://github.com/aditya-elite/VibeCall-AI/blob/main/docs/TEST_RESULTS_AND_NEXT_STEPS.md) for the full root-cause analysis and what a real fix requires.

---

## 🏗️ Architecture Pipeline

```
[Speaker's Mouth] ──(Airborne Acoustic Path)──> [Microphone] ──> 16 kHz Mono PCM Audio ──┐
                                                                                         ├──> [Monotonic Sync] ──> [RNNoise (CPU) + NPU Fusion Gate] ──> Clean Outgoing Voice
[Speaker's Cheek] ──(Bone / Contact Path)─────> [IMU Accel]  ──> ~401 Hz 3-Axis Motion ──┘
```

1. **Synchronized Capture**: Android `AudioRecord` (16 kHz mono 16-bit PCM, `UNPROCESSED` source) and `SensorManager` (400 Hz, `TYPE_ACCELEROMETER`) locked to `SystemClock.elapsedRealtimeNanos()`.
2. **Feature Extraction & Alignment**: Aligns time axes, subtracts static 1G gravity tilt, and isolates vibration energy in the speech fundamental band (80–200 Hz).
3. **RNNoise (CPU)**: Pretrained neural denoiser cleans the microphone signal in real time. This is the verified, demo-ready audio path.
4. **NPU Fusion Gate**: A lightweight MLP evaluates real-time vocal cord resonance against acoustic energy, running on the Snapdragon NPU via NNAPI. Currently used for NPU hardware validation; not yet driving the final demo audio (see Verified On-Device Results above).
5. **On-Device Target**: Qualcomm Snapdragon NPU execution (via NNAPI / QNN Direct SDK) optimized for high-performance phones such as the iQOO 15.

---

## 📱 Android Feasibility App (`app/`)

The mobile companion application captures synchronized test sessions with a single tap:

- **Enhanced Preset Dropdown**:
  * `Cheek - speaking with background noise` (Primary A/B benchmark)
  * `Cheek - speaking in quiet` (Clean vocal reference)
  * `Table - silent baseline` (Sensor floor calibration)
- **Live Hardware Telemetry**:
  * Real-time `400 Hz` sample rate monitor and frame counter.
  * On-screen hardware status badge: `⚡ NPU Hardware Acceleration: Active (NNAPI)`.
- **Session Export**: Generates a self-contained `.zip` package containing:
  * `microphone.wav` (16 kHz 16-bit mono WAV, raw)
  * `microphone_rnnoise.wav` (RNNoise-denoised, on-device — this is the demo audio)
  * `gated_microphone.wav` (NPU fusion gate output, for evaluation)
  * `accelerometer.csv` (Monotonic hardware timestamps and 3-axis readings)
  * `metadata.json` (Device model, sampling rates, inference counters)
- **Direct Share**: Built-in Android `FileProvider` export for one-tap sharing.

### Building & Running

1. Open the project in **Android Studio**.
2. Ensure **Gradle JDK** is set to **JDK 17** or **JDK 21** (`Settings -> Build Tools -> Gradle`).
3. Connect your Android phone with **USB Debugging** enabled.
4. Click **Run (▶)** and grant microphone permissions.

---

## 💻 Desktop Analysis Tool (`tools/`)

To analyze and plot an exported session ZIP:

```
# 1. Set up Python virtual environment
py -m venv .venv
.venv\Scripts\Activate.ps1

# 2. Install dependencies
pip install numpy matplotlib

# 3. Generate analysis report
python tools\analyze_session.py path\to\session.zip --output report.png
```

The tool produces a synchronized 4-panel analysis:

1. Microphone waveform
2. Microphone spectrogram (0–4 kHz)
3. Accelerometer vibration magnitude (m/s²)
4. Accelerometer spectrogram (0–fs/2)

---

## 🗺️ Project Roadmap & Engineering Status

> 🚀 **Latest Update**: RNNoise verified on-device (50+ dB noise floor reduction, 0.7 dB speech preservation). See [docs/TEST_RESULTS_AND_NEXT_STEPS.md](https://github.com/aditya-elite/VibeCall-AI/blob/main/docs/TEST_RESULTS_AND_NEXT_STEPS.md) for full test history, root-cause analysis of earlier miscalibrated results, and current engineering status.

See [ROADMAP.md](https://github.com/aditya-elite/VibeCall-AI/blob/main/ROADMAP.md) for full mathematical formulation, task ownership, and implementation guides.

### Status Check: What's Built vs. What's Left

| What's Built & Verified ✅                                                                                                     | What's Left to Build ⏳                                                                                                         |
| ----------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| **Synchronized Mobile Capture**: Android app recording 16 kHz audio + 400 Hz accelerometer                                    | **Fusion Gate Recalibration**: real A/B listening tests show RNNoise alone currently outperforms gate+RNNoise (gate over-suppresses speech). Root cause: single-instant accelerometer magnitude isn't specific enough — needs voice-frequency-band (80–190 Hz) energy instead |
| **Physical Feasibility Proven**: Accelerometer detected vocal fundamental (**144.53 Hz vs 144.42 Hz**, **11.4× power ratio**) | **Real-Time Streaming**: RNNoise currently applied to recorded sessions, not live call audio                                   |
| **Hardware Stability**: Held ~401.5 Hz across quiet speech, background noise, and baseline                                    | **Bandpass Filter**: 80–200 Hz digital filter to eliminate hand movement artifacts (<80 Hz), needed for gate recalibration      |
| **RNNoise On-Device (verified, real hardware)**: background noise floor cut by 50+ dB, speech peak preserved within 0.7 dB    | **INT8 Quantization on iQOO 15**: Benchmark latency using Qualcomm AI Engine Direct SDK (*Stretch*)                            |
| **NPU Fusion Gate**: confirmed executing on real Snapdragon NPU hardware (NNAPI), responds to real sensor data                |                                                                                                                                  |
| **Automated Data Packaging**: One-touch session ZIP creation and Android FileProvider sharing                                 |                                                                                                                                  |
| **Desktop Analysis Tool**: Automated waveform, PSD, and spectrogram generation                                                |                                                                                                                                  |

---

## 🚀 Immediate Next Steps for Demo Day

1. **Step 1 (Bandpass Filtering)**: Implement an 80–200 Hz Butterworth bandpass on the accelerometer Z-axis to isolate vocal cord vibrations from general hand-movement noise — this is the real fix needed for the fusion gate to work correctly.
2. **Step 2 (Recalibrate Gate)**: Retrain the fusion gate model using this properly filtered feature, tested against real cheek-vs-away-from-cheek recordings on the actual demo device.
3. **Step 3 (A/B Test Bench)**: Feed a noisy test sentence through both pipelines and output a 3-way comparative WAV (`Noisy` vs. `RNNoise-only` vs. `VibeCall Fusion`), verified by real listening tests before claiming any result.
4. **Step 4 (Video & Pitch)**: Record real on-device screen footage of the app running for the hackathon presentation — showing the actual working RNNoise result live.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](https://github.com/aditya-elite/VibeCall-AI/blob/main/LICENSE) file for details.
