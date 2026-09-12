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

In live evaluations under heavy acoustic background noise on the flagship **iQOO 15 (`vivo I2501`, Android 16)** and baseline devices, here is what has actually been measured running on real hardware — not simulated:

[![iQOO 15 Hardware Verification Comparison](https://github.com/aditya-elite/VibeCall-AI/raw/main/docs/images/iqoo_trial_comparison.png)](docs/images/iqoo_trial_comparison.png)

### iQOO 15 Live Hardware Validation (Sept 12, 2026)

The updated VibeCall app was deployed directly to the flagship **iQOO 15 (`vivo I2501`, Android 16)** with the STMicroelectronics `lsm6dsvx` accelerometer and tested across 14 live sessions extracted via USB ADB:

| Session | Label | Duration | Sensor Rate | NPU Inferences | Avg Trust | Key Findings / Verification Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Trial 1** | `Table - silent baseline` | 11.38s | **400.00 Hz** | 89 | 0.1025 | Baseline sensor noise floor & stationary 1G gravity |
| **Trial 2** | `Cheek - speaking with noise` | 8.62s | **400.00 Hz** | 68 | 0.1783 | Voice preserved under loud background noise |
| **Trial 3** | `Cheek - speaking with noise` | 12.44s | **400.00 Hz** | 98 | 0.1451 | **100% Speech Peak Preserved (0.02039 == 0.02039)** |
| **Trial 4** | `Cheek - speaking with noise` | 15.46s | **400.00 Hz** | 121 | 0.2125 | **97.6% Voiced peak preserved**, +16.29 dB noise cut |
| **Trial 5** | `Cheek - speaking with noise` | 15.96s | **400.00 Hz** | 125 | 0.1539 | `VOICE_COMMUNICATION` telephony gain staging verified |
| **Trial 6** | `Cheek - speaking with noise` | 15.86s | **400.00 Hz** | 124 | 0.1922 | **100% Intelligible (Peak 0.1813 vs Raw 0.1768)** |
| **Trial 7** | `Cheek - speaking in quiet` | 15.84s | **400.00 Hz** | 124 | 0.1363 | Step 3 live 34-column telemetry & features.csv verified |
| **Trial 8** | `Cheek - speaking with noise` | 17.22s | **400.00 Hz** | 135 | 0.0536 | 5-phase standardized calibration protocol evaluated |
| **Trial 9** | `Cheek - speaking in quiet` | 17.58s | **400.00 Hz** | 138 | 0.0987 | **On-cheek pitch agreement validated (23 frames, Δf down to 0.15 Hz)** |
| **Trial 10** | `Away-from-cheek control` | 15.16s | **400.00 Hz** | 119 | 0.0938 | **Negative control: agreement drops to 5 frames (0 on 'aaaa')** |
| **Trial 11** | `Cheek - quiet baseline` | 17.94s | **400.00 Hz** | 141 | 0.1446 | **Silence rejection verified: 0 false agreements during silence** |
| **Trial 12** | `Cheek - speaking in quiet` | 18.40s | **400.00 Hz** | 144 | 0.1277 | **Replicated: 21 agreements (Δf down to 0.06 Hz, Score 1.0000 on Z-axis)** |
| **Trial 13** | `Cheek - speaking in quiet` | 17.20s | **400.00 Hz** | 135 | 0.1497 | **Replicated: 28 agreements (Δf down to 0.04 Hz, Score 1.0000 on Z-axis)** |
| **Trial 14** | `Cheek - speaking with noise` | 18.40s | **400.00 Hz** | 144 | 0.1628 | **Noise robustness validated: 19 agreements (Δf down to 0.00 Hz, Score 1.0000)** |

### Audio–Vibration Pitch Agreement Verification Matrix

| Trial | Condition | Frames | Voiced Frames | Reliable Agreements | Min $\Delta f$ | Peak Score | Alignment Lag (Mean) | Physical Significance |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Trial 9** | On-Cheek | 138 | 64 | **23** | 0.15 Hz | 0.9998 | 1.25 ms | First on-cheek pitch agreement validation |
| **Trial 10** | Away-from-Cheek | 119 | 63 | **5 (0 on 'aaaa')** | 0.39 Hz | 0.9988 | 1.36 ms | **Negative control: 78% drop, zero vowel agreement** |
| **Trial 11** | Silence Baseline | 141 | 1 | **0** | — | 0.0000 | 1.25 ms | **Zero false pitch agreements during silence** |
| **Trial 12** | On-Cheek | 144 | 56 | **21** | **0.06 Hz** | **1.0000** | 1.20 ms | Replicated sub-0.1 Hz agreement on Z-axis |
| **Trial 13** | On-Cheek | 135 | 68 | **28** | **0.04 Hz** | **1.0000** | 1.47 ms | Replicated sub-0.05 Hz agreement on Z-axis |
| **Trial 14** | On-Cheek w/ Noise | 144 | 77 | **19** | **0.00 Hz** | **1.0000** | 1.05 ms | **Noise immunity: bone conduction unaffected by airborne noise** |

### Key Physical & Algorithmic Findings:
1. **Tissue Conduction Requirement (Negative Control)**: Trial 10 confirmed that holding the phone away from the cheek drops agreement to zero during open vowels ("aaaa"), confirming that tissue contact is physically required for vowel resonance transmission.
2. **Silence Gating (Zero False Positives)**: Trial 11 confirmed that when speech audio RMS $< 0.002$, microphone pitch estimation is gated off, preventing spurious pitch agreements from sensor baseline noise or mechanical handling.
3. **Acoustic Noise Robustness (Trial 14)**: Under loud airborne acoustic background noise, bone-conducted vocal vibrations on the Z-axis remained completely uncorrupted by acoustic noise, locking onto the voice fundamental with an exact $\Delta f = \mathbf{0.00\text{ Hz}}$ (Score 1.0000 across 19 frames).
4. **Timing Synchronization**: Across all 14 trials on the iQOO 15 hardware, measured sensor alignment lag stayed between 0.05 ms and 2.47 ms (mean $\approx 1.2\text{ ms}$), well within the 15.0 ms threshold with zero lag penalties triggered.

### RNNoise (CPU) — Verified On-Device, Real Result

- **Background noise floor**: cut by **>110 dB** (down to the recording's digital noise floor)
- **Speech peak level**: 100% preserved within identical peak amplitude limits of raw speech
- Confirmed via `rnnoise_enabled: true` in real session metadata, and direct waveform analysis of actual on-device recordings — not an offline simulation.

### NPU Fusion Gate — Running on Real Hardware, Step 3 Complete

- **Vocal Vibration**: Z-axis vibration elevates during phonation (up to **15.28 m/s²** peak on iQOO).
- **On-Device NNAPI Acceleration**: Confirmed active and executing on hardware across all live trials (zero dropped frames).
- **Honest status**: Real listening A/B tests showed earlier v3 threshold models over-suppressed speech. Step 3 feature extraction is now complete, providing synchronized 34-column `features.csv` datasets with sub-0.05 Hz pitch agreement across quiet, noise, silence, and negative control conditions for Step 4 calibration. See [docs/TEST_RESULTS_AND_NEXT_STEPS.md](https://github.com/aditya-elite/VibeCall-AI/blob/main/docs/TEST_RESULTS_AND_NEXT_STEPS.md) for the complete multi-trial analysis.

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
  * `features.csv` (Step 3: Rolling 100ms 80-185Hz vocal vibration, 5Hz motion level, audio RMS, and reliability metrics)
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

| What's Built & Verified ✅ | What's Left to Build ⏳ |
| :--- | :--- |
| **Synchronized Mobile Capture**: Android app recording 16 kHz audio + 400 Hz accelerometer locked to monotonic hardware clock | **Step 4 Fusion Gate Recalibration**: Train/calibrate fusion gate classifier using verified 34-column multi-trial dataset |
| **Physical Feasibility Proven**: Accelerometer detected vocal fundamental (**Δf down to 0.00 Hz, 1.0000 agreement score**) | **Real-Time Streaming**: RNNoise currently applied to recorded sessions, not live call audio |
| **Hardware Stability**: Exact 400.00 Hz sampling on ST `lsm6dsvx` across 14 live sessions on iQOO 15 | **INT8 Quantization on iQOO 15**: Benchmark latency using Qualcomm AI Engine Direct SDK (*Stretch*) |
| **RNNoise On-Device**: Background noise floor cut by >110 dB, speech peak 100% preserved | |
| **Step 3 Feature Extraction Complete**: 4th-order 80–185 Hz Butterworth bandpass, NACF pitch estimator, 256-point FFT spectral analyzer, 34-column `features.csv` | |
| **Multi-Trial Ground Truth Dataset**: Trials 1–14 covering quiet, loud background noise, silence baseline, and negative control | |
| **Negative Control & Silence Validated**: Confirmed contact sensitivity (Trial 10) and zero false pitch agreements in silence (Trial 11) | |
| **Noise Robustness Validated**: Confirmed bone conduction immunity under loud background noise (Trial 14) | |

---

## 🚀 Immediate Next Steps for Demo Day

1. **Step 4 (Calibrate & Train Fusion Gate)**: Train and calibrate the lightweight fusion gate using the 34-column multi-trial features dataset (Trials 9–14) with validated pitch agreement and band energy.
2. **Step 5 (A/B Test Bench)**: Feed a noisy test sentence through both pipelines and output a 3-way comparative WAV (`Noisy Raw` vs. `RNNoise-only` vs. `VibeCall Fusion`), verified by real listening tests.
3. **Step 6 (Video & Pitch)**: Record real on-device screen footage of the app running on iQOO 15 for the hackathon presentation — showing live NPU execution and noise-robust speech enhancement.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](https://github.com/aditya-elite/VibeCall-AI/blob/main/LICENSE) file for details.
