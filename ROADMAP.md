# VibeCall AI — Engineering Roadmap & Implementation Plan

This document outlines the technical status, what is currently completed, what remains to be built, and actionable implementation steps for the team (**NPU PULSE**) for the **iQOO Hackathon 2026**.

---

## 📌 Executive Status: Built vs. Left to Build

| Phase | Component | Status | Details |
| :--- | :--- | :---: | :--- |
| **Phase 1** | **Synchronized Mobile Capture** | ✅ **Complete** | Android app capturing mono 16 kHz audio (`UNPROCESSED` source) + 400 Hz accelerometer with monotonic clock timestamps. |
| **Phase 1** | **Hardware Feasibility Validation** | ✅ **Complete** | Validated vocal cord vibration transmission to the accelerometer: **144.53 Hz (mic)** vs **144.42 Hz (accel)**, **Δ 0.114 Hz**, **11.4× power ratio**. |
| **Phase 1** | **Automated Session Packaging** | ✅ **Complete** | One-touch recording, auto-generated `.zip`, and Android `FileProvider` export. |
| **Phase 1** | **Desktop Feasibility Visualizer** | ✅ **Complete** | Python tool (`tools/analyze_session.py`) generating synchronized 4-panel waveforms and spectrograms. |
| **Phase 2** | **Motion Bandpass & Detrending** | 🟡 **In Progress** | Isolating vocal vibrations (80–200 Hz) while rejecting hand movements and screen taps (<80 Hz). |
| **Phase 2** | **Pretrained RNNoise Integration** | ⏳ **Next** | Lightweight neural denoiser running locally without requiring model retraining. |
| **Phase 2** | **Accelerometer Energy Gating** | ⏳ **Next** | Heuristic fusion: boosting RNNoise speech output only when contact vibration confirms real speech. |
| **Phase 3** | **A/B Audio Benchmark Tool** | ⏳ **Next** | Comparative evaluation pipeline: **Original vs. Mic-Only AI vs. VibeCall Fusion** targeting $\ge 3\text{ dB}$ SNR improvement. |
| **Phase 4** | **Snapdragon NPU Deployment** | 🚀 *Stretch* | INT8 model quantization & Qualcomm QNN execution on the **iQOO 15**. |

---

## 🎯 What to Do Next (Actionable Tasks)

```
┌───────────────────────────┐      ┌───────────────────────────┐      ┌───────────────────────────┐
│     1. SENSOR FILTER      │ ---> │     2. RNNOISE + GATE     │ ---> │    3. A/B BENCHMARK       │
│  Bandpass 80–200 Hz to    │      │  Layer contact energy VAD │      │  Generate comparative audio│
│  remove hand motion (<80Hz│      │  over pretrained RNNoise  │      │  and quantify SNR gain    │
└───────────────────────────┘      └───────────────────────────┘      └───────────────────────────┘
```

### Task 1: Micro-Vibration Isolation (Bandpass Filtering)
* **Goal**: Isolate voiced speech fundamentals ($80–200\text{ Hz}$) from gross body/hand movements ($<80\text{ Hz}$).
* **Why**: Our feasibility report highlighted that holding the phone introduces low-frequency hand micro-tremors.
* **Action**:
  Apply a 4th-order Butterworth bandpass filter to the raw accelerometer stream:
  $$f_{\text{low}} = 80\text{ Hz}, \quad f_{\text{high}} = 190\text{ Hz}$$
  Compute the short-time moving root-mean-square (RMS) energy:
  $$E_{\text{accel}}(t) = \sqrt{\frac{1}{W} \sum_{k=t-W}^{t} z_{\text{filtered}}^2[k]}$$

---

### Task 2: Implement the Zero-Training RNNoise + Accel Gate
* **Goal**: Build the speech enhancement engine without spending days training deep models from scratch.
* **Architecture**:
  1. **Acoustic Denoiser**: Run standard pretrained **RNNoise** (C/Python wrapper) on the 16 kHz microphone stream to obtain an initial speech probability $P_{\text{mic}}$ and filtered frame.
  2. **Vibration Gate (Contact VAD)**: Determine contact voicing confidence from the accelerometer energy:
     $$G_{\text{accel}}(t) = \sigma\left(\frac{E_{\text{accel}}(t) - \theta_{\text{silence}}}{\tau}\right)$$
  3. **Fusion Decision**:
     - When $G_{\text{accel}} > \theta_{\text{voicing}}$: Voice confirmed by skull/cheek contact $\rightarrow$ preserve and boost speech harmonics.
     - When $G_{\text{accel}} \le \theta_{\text{voicing}}$ and $P_{\text{mic}} \approx 1$ (e.g. ambient voice / traffic noise nearby): Phone vibration does not match $\rightarrow$ aggressive noise suppression.

---

### Task 3: A/B Audio Comparison Pipeline (Hackathon Deliverable)
* **Goal**: Create the demo test bench that judges can listen to.
* **Deliverable**:
  Provide an evaluation script that takes a noisy recording and exports 3 synchronized WAV files:
  1. `input_noisy.wav` (Raw recording with ambient traffic/crowd noise)
  2. `output_mic_only.wav` (Standard mic-only RNNoise baseline)
  3. `output_vibecall_fusion.wav` (RNNoise + Accelerometer Contact Gate)
* **Success Metric**:
  - Minimum **$\ge 3\text{ dB}$ SNR improvement** over mic-only baseline.
  - Perceptible preservation of words during overlapping noise bursts.

---

### Task 4: Qualcomm Snapdragon NPU Acceleration (*Stretch Goal*)
* **Target Device**: **iQOO 15** (Snapdragon 8 Elite / 8 Gen series)
* **Steps**:
  1. Convert the audio enhancement GRU layers to TFLite / ONNX format.
  2. Apply post-training INT8 quantization using Qualcomm AI Engine Direct SDK (QNN).
  3. Benchmark real-time processing latency per $10\text{ ms}$ chunk (target: $<3\text{ ms}$ inference on NPU).

---

## 👥 Team Work Split

* **I Aditya Annamalai** (`@aditya-elite`):
  - Android data acquisition app (`app/`)
  - Sensor-audio hardware synchronization & time alignment
  - APK deployment and phone-side testing on Redmi / Moto
* **Kavin Kumar L**:
  - Python audio DSP & bandpass filtering (`80–200 Hz`)
  - Pretrained RNNoise wrapper & contact-energy gating logic
  - Fusion model evaluation & spectrogram metrics
* **Hari Krishnan M**:
  - Pitch deck refinement & presentation delivery
  - Recording the A/B listening audio samples & demo video
  - Hardware setup & latency benchmarking on iQOO hardware
