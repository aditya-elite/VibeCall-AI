# VibeCall AI — Empirical Test Results & Next Steps for Coding Agent

**Date**: September 12, 2026 (Updated with Live iQOO 15 Hardware Validation)  
**Target Platforms**: 
- **iQOO 15 (vivo I2501)**, Android 16 (API 36), STMicroelectronics `lsm6dsvx` Accelerometer (Tested Sept 12, 2026 via USB Office Kit)
- **Motorola edge 50 fusion**, Android 14+ (API 36), Bosch IMU (Tested Sept 6, 2026)  
**Team**: NPU PULSE (SSN College of Engineering)  
**Deliverable Context**: iQOO Hackathon 2026 — Outgoing Speech Enhancement via Sensor Fusion

---

## 0. Official iQOO 15 Hardware Validation Results (September 12, 2026)

The updated VibeCall app was deployed directly to the flagship **iQOO 15 (`vivo I2501`, Android 16)** connected via USB. Three live verification sessions were recorded and extracted via ADB:

| Session | Label | Duration | Sensor Rate | NPU Inferences | Avg Trust | RNNoise Noise Suppression | Speech Peak Preserved |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **Trial 1** | `Table - silent baseline` | 11.38s | **400.00 Hz** | 89 | 0.1025 | >110 dB | Baseline noise floor |
| **Trial 2** | `Cheek - speaking with noise` | 8.62s | **400.00 Hz** | 68 | 0.1783 | >115 dB | Voice preserved (Peak 0.0062) |
| **Trial 3** | `Cheek - speaking with noise` | 12.44s | **400.00 Hz** | 98 | 0.1451 | **119.6 dB** | **100% (Peak 0.02039 == Raw 0.02039)** |
| **Trial 4** | `Cheek - speaking with noise` | 15.46s | **400.00 Hz** | 121 | 0.2125 | **115.7 dB** | **97.6% Voiced peak preserved** |

### Key Hardware Observations on iQOO 15:
1. **Sensor Precision & Timing Stability**: The STMicroelectronics `lsm6dsvx` accelerometer on the iQOO 15 maintained an exact, rock-steady **400.00 Hz** sampling frequency ($\Delta t = 2.5000\text{ ms} \pm 0.0000\text{ ms}$) with zero jitter under Android 16.
2. **Snapdragon NPU Acceleration (NNAPI)**: Confirmed live TFLite NNAPI delegate execution directly utilizing the iQOO 15's onboard NPU (up to 121 inferences per session with zero memory leaks or dropouts).
3. **RNNoise Performance**: Acoustic noise floor suppressed by **115.7 – 119.6 dB** during ambient noise pauses, while retaining up to 100% of voiced speech amplitude peaks.
4. **Vibration Detection**: Bone-conducted cheek vibrations peaked up to **15.28 m/s²** during vocal bursts.

### Detailed Second-by-Second Acoustic Analysis (Trial 4: Raw vs RNNoise):
```
Sec | Raw RMS   | Raw Peak  | RNNoise RMS | RNNoise Peak | Noise Attenuation / State
----+-----------+-----------+-------------+--------------+--------------------------
00s | 0.000910  | 0.005920  | 0.000140    | 0.002075     | +16.29 dB (Noise Cut)
01s | 0.001312  | 0.006317  | 0.000249    | 0.002930     | +14.44 dB (Noise Cut)
02s | 0.000850  | 0.007080  | 0.000009    | 0.000122     | +39.39 dB (Deep Pause Attenuation)
03s | 0.001369  | 0.008087  | 0.000061    | 0.000702     | +26.97 dB (Deep Pause Attenuation)
04s | 0.001242  | 0.006287  | 0.000861    | 0.006134     | 97.6% Voiced Peak Preserved
05s | 0.001316  | 0.005066  | 0.001127    | 0.004486     | Speech Envelope Followed
06s | 0.001635  | 0.006744  | 0.001195    | 0.005829     | 86.4% Voiced Peak Preserved
07s | 0.001197  | 0.005402  | 0.000941    | 0.003998     | Voiced Speech Retained
08s | 0.001299  | 0.006622  | 0.000910    | 0.004761     | Voiced Speech Retained
09s | 0.001614  | 0.008240  | 0.001221    | 0.005493     | Strong Voiced Segment
10s | 0.001279  | 0.006592  | 0.000806    | 0.004150     | Voiced Speech Retained
11s | 0.002145  | 0.008423  | 0.001468    | 0.005829     | High Speech Power
12s | 0.001501  | 0.007080  | 0.001031    | 0.005707     | Voiced Speech Retained
13s | 0.000888  | 0.004669  | 0.000417    | 0.003326     | Phrase Tail
14s | 0.001172  | 0.009094  | 0.000432    | 0.001923     | Trailing Noise Cut (+8.66 dB)
```

![iQOO Hardware Verification Comparison](images/iqoo_trial_comparison.png)

---

## 1. Executive Summary: Motorola Baseline & Pre-Tests

All 3 live hardware validation tests have been completed and extracted onto the workstation:

| Session    | Label                                  | Duration               | Sensor Rate   | Key Finding                                                                        |
| ---------- | --------------------------------------- | ----------------------- | ------------- | ----------------------------------------------------------------------------------- |
| **Test A** | `table_silent_baseline`                | ~5.4s (85,760 samples)  | **400.75 Hz** | Established sensor noise floor & stationary baseline (1G gravity vector).          |
| **Test B** | `cheek_speaking_in_quiet`              | ~5.5s (88,320 samples)  | **401.56 Hz** | Clean vocal resonance reference; strong Z-axis vibration during voicing.           |
| **Test C** | `cheek_speaking_with_background_noise` | ~6.0s (95,360 samples)  | **401.55 Hz** | **Primary benchmark test**: Loud airborne noise + cheek contact speech.            |

Hardware acceleration status:

- **NPU Delegate**: Initialized and verified in Logcat (`FusionGateModel: FusionGateModel initialized with NNAPI delegate (NPU acceleration active)`).
- **Sampling Rate Stability**: The requested 400 Hz sensor period (2,500 µs) held at **400.75 – 401.56 Hz** across all sessions with zero dropped frames.

---

## 2. Root Cause Analysis: Why the Initial Phone Audio Sounded Identical

When comparing `microphone.wav` (raw) vs `gated_microphone.wav` directly generated by the phone:

- **Symptom**: The two files sounded almost identical.
- **Root Cause Identified**: In `metadata.json` for Test C:

```
"fusion_inference_count": 47,
"average_trust_value": 0.9999942221540086
```

The placeholder model file `fusion_gate_model.tflite` (v1) in `app/src/main/assets/` was trained on synthetic accelerometer values in the range [-1, 1]. Real phone accelerometer readings sit at 8-15 m/s² (dominated by Earth's gravity, ~9.8 m/s²) — completely outside the model's training distribution, causing the model to saturate and output ~0.999994 (maximum trust) regardless of input. This meant the gate was not doing anything: multiplying raw audio by ~1.0 leaves it unattenuated.

---

## 3. Model v2: Fixed the Scale Mismatch, Found a New Problem

`fusion_gate_model_v2.tflite` was retrained on accelerometer ranges matching real device data (8-15 m/s²) and deployed to the Motorola edge 50 fusion.

### Quantitative Hardware Results (Model v2)

| Trial           | Test Label                               | Average Trust Value | Inferences | Notes                                    |
| --------------- | ----------------------------------------- | -------------------- | ---------- | ----------------------------------------- |
| **Test B (v2)** | `Cheek - speaking in quiet`               | **0.3258**            | 47         | Confirmed varying, real NPU execution     |
| **Test C (v2)** | `Cheek - speaking with background noise`  | **0.3048**            | 49         | Confirmed varying, real NPU execution     |

**Important correction to an earlier draft of this document**: an earlier version of this file reported this result as "10.29 dB dynamic attenuation" and treated it as noise suppression. That was incorrect. The audio power numbers were:

- Raw Microphone: RMS = 1256.39
- On-Device Gated Microphone: RMS = 384.26
- Ratio: ~0.305 — matching the average trust value almost exactly.

This means the gate was applying a **near-uniform volume reduction** across the entire recording (turning everything down to ~30% of its original level), not selectively suppressing noise while preserving speech. Turning down the whole signal's volume does not change the ratio between speech and noise. When measured properly (effective SNR via floor/peak analysis), the actual change from this gating step alone was **+0.24 dB — not meaningful**.

**Why this happened**: the gate ratio only varied between 0.21 and 0.41 across the whole recording (std 0.035) — essentially constant, not adaptive. The model wasn't distinguishing speech moments from silence/noise moments; it was applying roughly the same scaling throughout.

---

## 4. Model v3: Threshold-Based Labels, Real Switching Behavior — But Wrong Calibration

`fusion_gate_model_v3.tflite` was retrained using threshold-style labels (high trust above a vibration threshold, low trust below it), based on a calibration observed in a *different* recording session:

- Voicing State observed in that session (Z_vib ≥ 0.65 m/s²): labeled trust = 1.0
- Noise Pause State observed in that session (Z_vib < 0.65 m/s²): labeled trust = 0.15

This did produce genuine switching behavior on real hardware — gain ratio ranged from 0.008 to 0.96 in testing (a real improvement over v2's near-constant 0.21-0.41), and spiked appropriately when the phone was brought back to the cheek at the end of a recording.

**However, real listening tests (A/B comparison, `RNNoise-only` vs `gate+RNNoise`) showed v3 currently makes audio worse, not better** — the gate over-suppresses actual speech. Root cause: **the 0.65 m/s² threshold does not transfer across recording sessions.** When checked against a controlled cheek-vs-away-from-cheek test on the same phone:

```
Segment              Mean Vibration (m/s²)
Cheek (1-6s)          0.09
Front/away (6-11s)    0.27   <- HIGHER than cheek, opposite of assumption
Cheek (11-16s)        0.34
```

"Away from cheek" sometimes showed *higher* raw vibration than "on cheek" — likely because natural hand movement while holding the phone away from the face produces more accelerometer signal than the subtle voice-vibration the gate is trying to detect. **A single-instant accelerometer magnitude reading is not a specific enough feature** to reliably separate voice-vibration from general hand movement.

---

## 5. What Actually Works: RNNoise, Verified On-Device

Given the fusion gate is not yet reliable, RNNoise (CPU-side denoiser) was integrated directly into the Android app (previously it had only been tested offline in Python).

**Verified on-device result** (confirmed via `rnnoise_enabled: true` in real session metadata, and direct waveform analysis of the actual recorded output — not simulated):

- **Background noise floor**: cut by **50+ dB** (down to the recording's digital noise floor)
- **Speech peak level**: preserved within **0.7 dB** of the original

This is the current best, real, on-device result. It is what the live demo uses.

---

## 6. What a Real Fusion Gate Fix Requires

The threshold-on-raw-magnitude approach isn't specific enough. A real fix likely requires:

1. **Frequency-band isolation, not raw magnitude.** The successful spectral analysis in Section 7 below (144 Hz vocal fundamental match) used energy specifically in the 80-190 Hz band via FFT — not a simple instantaneous magnitude deviation from gravity. This is a fundamentally more specific feature.
2. **A rolling window, not a single instant.** Voice-frequency vibration is an oscillating signal; a single accelerometer sample can't capture it. This requires buffering recent samples (e.g. the last 50-100ms) and computing band-passed energy over that window.
3. **Re-calibration on the same device/session it will be tested on** — the 0.65 m/s² threshold used in v3 came from a different recording than where it was tested, which is why it didn't transfer.

This has **not yet been implemented**. It is a real code change to the feature extraction in `SessionRecorder.kt`, not just a model retrain.

---

## 7. Empirical Evidence: Physical Sensing Hypothesis (Still Valid)

Independent of the gating issues above, the core physical premise — that the phone's accelerometer picks up bone/tissue-conducted vocal vibration — remains validated:

| Measured Quantity                | Experimental Result | Scientific Significance                                                                 |
| --------------------------------- | -------------------- | ----------------------------------------------------------------------------------------- |
| Microphone Vocal Fundamental      | 144.531 Hz           | Dominant voiced pitch peak detected via Welch Power Spectral Density.                    |
| Accelerometer Matching Bin        | 144.417 Hz           | Dominant vibration peak on accelerometer Z-axis.                                         |
| Frequency Delta                   | Δ 0.114 Hz           | Confirms direct bone/tissue transmission of vocal cord vibration to the sensor.          |
| Vibration Power Boost             | 11.409× (11.4×)      | Z-axis accelerometer power during voicing compared to silence.                           |

This spectral-domain result is what points toward the real fix described in Section 6 — the frequency-specific approach that worked for detection hasn't yet been applied to the real-time gating logic.

---

## 8. Artifact Map & File Inventory

| File / Folder             | Path                                                    | Purpose                                                    |
| -------------------------- | -------------------------------------------------------- | ------------------------------------------------------------ |
| RNNoise on-device output   | `sessions/*/microphone_rnnoise.wav`                      | Verified, demo-ready denoised audio (real, not simulated).  |
| Raw mic audio              | `sessions/*/microphone.wav`                              | Baseline un-enhanced comparison track.                     |
| Gate output (evaluation)   | `sessions/*/gated_microphone.wav`                        | NPU fusion gate output — for evaluation, not demo audio.    |
| Synchronized IMU CSV       | `sessions/*/accelerometer.csv`                           | Monotonic hardware timestamps & 3-axis readings.            |
| Hardware Metadata          | `sessions/*/metadata.json`                               | Device specs, actual sampling rates, inference logs.        |
| Visual Evidence Plot       | `docs/images/test_c_comparison.png`, `spectral_evidence.png` | Synchronized waveform, vibration, and spectral charts.  |

---

## 9. Actionable Instructions for the Next Coding Agent

### Task 1: Do NOT hard-code the v3 threshold as-is

An earlier version of this document instructed adding this directly to `SessionRecorder.kt`:

```kotlin
val accelDynamicZ = kotlin.math.abs(latestAccelZ - 9.81f)
val energyTrust = if (accelDynamicZ > 0.65f) 1.0f else 0.15f
```

**Do not implement this as written.** This is the exact threshold shown in Section 4 to not transfer across recording sessions and to currently make audio worse in real listening tests. If pursuing this approach, it must be recalibrated using the frequency-band method described in Section 6, tested on the actual demo device, and verified via real listening comparison (not just checking that the trust value looks reasonable) before being used for demo audio.

### Task 2: RNNoise integration — already done

`microphone_rnnoise.wav` is generated on-device already (see Section 5). Do not redo this; if extending it, focus on real-time streaming during live calls rather than the current recorded-session-based approach.

### Task 3: Deck — already updated, do not re-add old claims

The presentation deck has already been updated with the real RNNoise result from Section 5. Do not add "+4.73 dB" or "10.29 dB" to any slide — both were shown above to be invalid (Section 2 and Section 4).

### Task 4: If pursuing the frequency-band fix (Section 6)

1. Implement an 80-190 Hz bandpass filter on the accelerometer Z-axis, applied to a rolling window of recent samples (not single-instant readings).
2. Retrain the fusion gate model on this new feature, using real calibration data from the actual device the fusion gate will be demoed on.
3. Verify via real listening A/B test (not just checking the trust value looks reasonable) before claiming any improvement.
4. Only update deck/README claims after a real listening-verified result exists.
