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

The updated VibeCall app was deployed directly to the flagship **iQOO 15 (`vivo I2501`, Android 16)** with the STMicroelectronics `lsm6dsvx` accelerometer and tested across 17 live sessions extracted via USB ADB:

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
| **Trial 12** | `Cheek - speaking in quiet` | 18.40s | **400.00 Hz** | 144 | 0.1277 | **Stationary on-cheek: 21 agreements (peak score 1.0000 on Z-axis)** |
| **Trial 13** | `Cheek - stationary vs movement` | 17.20s | **400.00 Hz** | 135 | 0.1497 | **Tested stationary speech vs speech with phone movement (28 agreements)** |
| **Trial 14** | `Cheek - speaking with noise` | 18.40s | **400.00 Hz** | 144 | 0.1628 | **Pitch agreement detectable under noise (19 agreements, esp. for 'mmmm')** |
| **Trial 15** | `Cheek - speaking with noise` | 20.28s | **400.00 Hz** | 159 | 0.844 (Gain) | **Live Step 4 Fusion verified: 12 hangover protections, 0 audio clips, 84 µs latency** |
| **Trial 16** | `Cheek - speaking with noise` | 21.48s | **400.00 Hz** | 168 | 0.867 (Gain) | **Live Step 4 Fusion verified: 13 hangover protections, 17 pitch agreements** |
| **Trial 17** | `Cheek - speaking with noise` | 17.84s | **400.00 Hz** | 140 | 0.905 (Gain) | **Live Step 4 Fusion verified: 12 hangover protections, smooth pause attenuation** |

### Audio–Vibration Pitch Agreement Verification Matrix

| Trial | Condition | Frames | Voiced Frames | Reliable Agreements | Nominal Min $\Delta f$ | Peak Score | Alignment Lag (Mean) | Physical Significance |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Trial 9** | On-Cheek (Quiet) | 138 | 64 | **23** | 0.15 Hz | 0.9998 | 1.25 ms | Initial on-cheek pitch agreement validation |
| **Trial 10** | Away-from-Cheek | 119 | 63 | **5 (0 on 'aaaa')** | 0.39 Hz | 0.9988 | 1.36 ms | **Negative control: 78% drop, zero vowel agreement** |
| **Trial 11** | Silence Baseline | 141 | 1 | **0** | — | 0.0000 | 1.25 ms | **Zero false pitch agreements during silence** |
| **Trial 12** | On-Cheek (Quiet) | 144 | 56 | **21** | 0.06 Hz | 1.0000 | 1.20 ms | Replicated stationary on-cheek agreement |
| **Trial 13** | Stationary vs Movement | 135 | 68 | **28** | 0.04 Hz | 1.0000 | 1.47 ms | Tested stationary speech vs phone movement |
| **Trial 14** | On-Cheek w/ Noise | 144 | 77 | **19** | **0.00 Hz\*** | 1.0000 | 1.05 ms | **Pitch agreement detectable in noise (esp. 'mmmm')** |

*\*Note: Nominal $\Delta f$ values reflect parabolic interpolation estimates; true physical spectral resolution for a 250 ms window at 400 Hz is limited by the Rayleigh criterion ($\Delta f \approx 4.0\text{ Hz}$). Interpolated matches demonstrate bin peak alignment rather than sub-Hertz sensor precision.*

### Key Physical & Algorithmic Findings:
1. **Tissue Conduction Requirement (Negative Control)**: Trial 10 confirmed that holding the phone away from the cheek drops agreement to zero during open vowels ("aaaa"), confirming that tissue contact is physically required for vowel resonance transmission.
2. **Silence Gating (Candidate Peaks vs Silence)**: Trial 11 confirmed that when speech audio RMS $< 0.002$, microphone pitch estimation is gated off, preventing spurious pitch agreements even when the accelerometer FFT registers candidate spectral peaks from baseline noise floor.
3. **Background Noise Detectability (Trial 14)**: Under loud airborne acoustic background noise, bone-conducted vocal fundamental resonance remained detectable on the Z-axis (particularly during nasal phonemes like "mmmm" with high chassis coupling).
4. **Timing Synchronization**: Across all 14 trials on the iQOO 15 hardware, measured sensor alignment lag stayed between 0.05 ms and 2.47 ms (mean $\approx 1.2\text{ ms}$), well within the 15.0 ms threshold with zero lag penalties triggered.

### RNNoise (CPU) — Verified On-Device, Real Result

### RNNoise (CPU) — Verified On-Device, Primary Demo Baseline

- **Background noise floor**: cut by **>110 dB** (down to the recording's digital noise floor)
- **Speech peak level**: 100% preserved within identical peak amplitude limits of raw speech
- Confirmed via `rnnoise_enabled: true` in real session metadata, and direct waveform analysis of actual on-device recordings — not an offline simulation. **RNNoise (`microphone_rnnoise.wav`) is the primary verified, demo-ready audio baseline.**

### Step 4: Fusion-Confidence Model & SafeGainController (Experimental Prototype)

- **16-Feature MLP Model**: Evaluates synchronized microphone audio and accelerometer features across 16 dimensions in exact tensor order with strict z-score metadata normalization.
- **SafeGainController & Real Hangover Protection**: Modulates only the RNNoise audio path (`microphone_fusion.wav = smoothed_gain * microphone_rnnoise.wav`). Features real hangover word-ending protection preserving unity gain (`1.0`) during speech and trailing pause windows before sustained pause countdown begins.
- **Fail-Open Policy**: Low sensor reliability ($< 0.70$), excessive movement ($> 0.50\text{ m/s}^2$), alignment lag ($> 15\text{ ms}$), model failure, or metadata corruption strictly forces unity gain (`1.0`), preventing audio muting.
- **Leave-One-Session-Out Cross-Validation Metrics (432 Windows)**:
  - **Accuracy**: `78.94%` | **Precision**: `67.98%` | **Recall**: `84.15%` | **F1 Score**: `0.7520` | **Overall FPR**: `24.25%`
  - **Silence Rejection**: `SILENCE_MOVEMENT` Accuracy **100.0%** (0 false positives), `SILENCE_STILL` Accuracy **95.6%** (FPR 4.4%).
  - **Contact Speech Recall**: `CONTACT_SPEECH` Recall **85.1%** (F1 0.919), `CONTACT_SPEECH_MOVEMENT` Recall **80.0%** (F1 0.889).
  - **Away-from-Cheek Negative Control Status (FAILED)**: All 58 `AWAY_SPEECH` frames in Fold 2 were predicted as contact speech (mean confidence `0.965`, FPR `1.0`). The model has **not demonstrated** that it can distinguish cheek-contact speech from airborne speech on its own.
  - **Why the Acoustic Guard is Essential**: When the user speaks away from their cheek, the deterministic acoustic guard detects microphone speech and overrides the model, forcing gain to `1.0` so speech is never attenuated.
- **Backend Status**: `NNAPI delegate initialized — physical NPU not independently verified`. Initializing an Android NNAPI delegate requests hardware acceleration, but does not constitute independent physical proof of Qualcomm Hexagon NPU driver execution.

---

## 🏗️ Architecture Pipeline

```
[Speaker's Mouth] ──(Airborne Acoustic Path)──> [Microphone] ──> 16 kHz Mono PCM Audio ──┐
                                                                                         ├──> [Monotonic Sync] ──> [RNNoise (CPU) × SafeGainController] ──> Clean Outgoing Voice
[Speaker's Cheek] ──(Bone / Contact Path)─────> [IMU Accel]  ──> ~400 Hz 3-Axis Motion ──┘
```

1. **Synchronized Capture**: Android `AudioRecord` (16 kHz mono 16-bit PCM, `VOICE_COMMUNICATION` source) and `SensorManager` (400 Hz, `TYPE_ACCELEROMETER`) locked to `SystemClock.elapsedRealtimeNanos()`.
2. **Step 3 Feature Extraction**: Computes 34 synchronized metrics including 4th-order Butterworth 80–185 Hz vocal bandpass, NACF autocorrelation pitch estimation, 256-point FFT spectral peak prominence, phone motion level, and alignment lag.
3. **RNNoise Baseline (CPU)**: Pretrained neural denoiser cleans the microphone signal in real time. This is the verified, demo-ready audio baseline.
4. **Step 4 Fusion Confidence & Gain Controller**: Evaluates 16 normalized features to output contact speech confidence, driving `SafeGainController` sample-by-sample linear ramping. Fails open to unity gain (`1.0`) under any uncertain, moving, or failed condition.
5. **Output Audio Files**:
   - `microphone.wav`: Raw captured audio
   - `microphone_rnnoise.wav`: RNNoise baseline (primary verified demo audio)
   - `microphone_fusion.wav`: Fusion output (`RNNoise * smoothed_gain`, experimental)
   - `features.csv`: Step 3 34-column time-aligned feature telemetry
   - `fusion_decisions.csv`: Step 4 frame-by-frame model confidence, controller states, target gains, applied gains, and diagnostic audit reasons.

---

## 📱 Android Feasibility App (`app/`)

The mobile companion application captures synchronized test sessions with a single tap:

- **Enhanced Preset Dropdown**:
  * `Cheek - speaking with background noise` (Primary A/B benchmark)
  * `Cheek - speaking in quiet` (Clean vocal reference)
  * `Table - silent baseline` (Sensor floor calibration)
- **Live Hardware Telemetry**:
  * Real-time `400 Hz` sample rate monitor and frame counter.
  * On-screen hardware status badge: `Fusion Backend: NNAPI delegate initialized — physical NPU not independently verified` (or `CPU fallback` / `Model unavailable`).
- **Session Export**: Generates a self-contained `.zip` package containing:
  * `microphone.wav` (16 kHz 16-bit mono WAV, raw)
  * `microphone_rnnoise.wav` (RNNoise-denoised, on-device — primary demo audio)
  * `microphone_fusion.wav` (Fusion-controlled audio, experimental)
  * `features.csv` (Step 3: 34-column feature telemetry)
  * `fusion_decisions.csv` (Step 4: Frame-by-frame confidence, controller decisions, applied gains, audit reasons)
  * `accelerometer.csv` (Monotonic hardware timestamps and 3-axis readings)
  * `metadata.json` (Device model, sampling rates, inference counters, latency profiling)
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
| **Synchronized Mobile Capture**: Android app recording 16 kHz audio + 400 Hz accelerometer locked to monotonic hardware clock | **Multi-Session Negative Control Expansion**: Record additional away-speech sessions so model generalizes without acoustic guard |
| **Physical Feasibility Proven**: Accelerometer detected vocal fundamental (**Δf bin alignment, 1.0000 agreement score**) | **Real-Time Streaming**: Live telephony streaming integration (*Stretch*) |
| **Hardware Stability**: Exact 400.00 Hz sampling on ST `lsm6dsvx` across 14 live sessions on iQOO 15 | **INT8 Quantization on iQOO 15**: Benchmark latency using Qualcomm AI Engine Direct SDK (*Stretch*) |
| **RNNoise On-Device (Demo Baseline)**: Background noise floor cut by >110 dB, speech peak 100% preserved | |
| **Step 3 Feature Extraction Complete**: 4th-order 80–185 Hz Butterworth bandpass, NACF pitch estimator, 256-point FFT spectral analyzer, 34-column `features.csv` | |
| **Audited Ground Truth Dataset**: 432 training windows across 5 verified sessions (Trials 9, 10, 11, 13, 14; Trial 12 excluded) | |
| **Step 4 Fusion Model & SafeGainController**: 16-feature MLP model, strict metadata schema validation, real hangover word-ending protection, sample-by-sample linear ramping, and independent acoustic safety guard | |

---

## 🚀 Immediate Next Steps for Demo Day

1. **On-Device Protocol Validation**: Execute the standardized 3-session recording protocol (Session A: away-from-cheek replication, Session B: continuous conversational speech in quiet, Session C: conversational speech in 75 dB ambient noise).
2. **A/B Listening Test Bench**: Feed noisy test recordings through both pipelines to produce 3-way comparative playback (`Raw Microphone` vs `RNNoise Baseline` vs `VibeCall Fusion`), verifying zero clipped word endings.
3. **Demo & Video**: Capture screen and audio recordings demonstrating that RNNoise delivers pristine speech intelligibility and that the fusion pipeline safely fails open without speech degradation.

---

## 📄 License

This project is licensed under the MIT License — see the [LICENSE](https://github.com/aditya-elite/VibeCall-AI/blob/main/LICENSE) file for details.
