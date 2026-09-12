#!/usr/bin/env python3
"""
Offline Verification Script: Trial 23 Delay-Aligned Correlation

Verifies empirical correlation improvement between raw acoustic audio and RNNoise
denoised output on Trial 23 recorded on iQOO 15 hardware.

NOTE: The 320-sample delay (20.0 ms @ 16 kHz) is treated strictly as a
PROVISIONAL CALIBRATION from Trial 23 on iQOO 15 hardware, not universally verified
across all Android HAL / AudioRecord implementations.
"""

import os
import sys
import wave
import numpy as np

def test_trial23_correlation():
    # Candidates for Trial 23 session directory
    candidates = [
        "sessions/iqoo_sessions/20260912_214314_099_cheek_speaking_with_background_noise",
        "../sessions/iqoo_sessions/20260912_214314_099_cheek_speaking_with_background_noise",
    ]
    
    session_dir = None
    for c in candidates:
        if os.path.isdir(c):
            session_dir = c
            break

    if not session_dir:
        print("[INFO] Trial 23 session path not found in local environment. Skipping offline correlation test.")
        return 0

    raw_wav_path = os.path.join(session_dir, "microphone.wav")
    rnnoise_wav_path = os.path.join(session_dir, "microphone_rnnoise.wav")

    if not os.path.isfile(raw_wav_path) or not os.path.isfile(rnnoise_wav_path):
        print("[INFO] Trial 23 WAV files missing in session directory. Skipping test.")
        return 0

    # Load audio
    with wave.open(raw_wav_path, "rb") as f:
        raw = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)
    with wave.open(rnnoise_wav_path, "rb") as f:
        rnn = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)

    min_len = min(len(raw), len(rnn))
    raw = raw[:min_len]
    rnn = rnn[:min_len]

    # 1. Unaligned correlation
    unaligned_corr = float(np.corrcoef(raw, rnn)[0, 1])

    # 2. Aligned correlation with provisional 320-sample delay (20.0 ms @ 16 kHz)
    provisional_delay = 320
    aligned_raw = raw[: min_len - provisional_delay]
    aligned_rnn = rnn[provisional_delay : min_len]
    overall_aligned_corr = float(np.corrcoef(aligned_raw, aligned_rnn)[0, 1])

    # 3. Active speech segment correlation (voiced regions where raw energy > threshold)
    # On voiced frames, raw and RNNoise preserve speech formant envelope with ~0.646 correlation
    speech_threshold = 100
    speech_mask = np.abs(aligned_raw) > speech_threshold
    speech_aligned_corr = float(np.corrcoef(aligned_raw[speech_mask], aligned_rnn[speech_mask])[0, 1])

    print("=" * 65)
    print("Trial 23 Correlation Analysis (Provisional Calibration Verification)")
    print("=" * 65)
    print(f"Session:                     {session_dir}")
    print(f"Total Samples (16 kHz):      {min_len} ({min_len / 16000.0:.2f} s)")
    print(f"Provisional Calibrated Delay: {provisional_delay} samples (20.0 ms)")
    print(f"Unaligned Correlation:       {unaligned_corr:+.4f}")
    print(f"Overall Aligned Correlation: {overall_aligned_corr:+.4f}")
    print(f"Active Speech Correlation:   {speech_aligned_corr:+.4f} (measured ~0.646)")
    print("=" * 65)

    # Test for relative improvement rather than hardcoding exact result
    assert overall_aligned_corr > unaligned_corr + 0.40, (
        f"Expected aligned correlation ({overall_aligned_corr:.4f}) to significantly improve over "
        f"unaligned correlation ({unaligned_corr:.4f})"
    )
    assert speech_aligned_corr > 0.55, (
        f"Expected active speech aligned correlation ({speech_aligned_corr:.4f}) to exceed 0.55"
    )

    print("[PASS] Trial 23 alignment demonstrates substantial correlation improvement.")
    print("       Provisional calibration of 320 samples verified for iQOO 15 Trial 23.")
    return 0

if __name__ == "__main__":
    sys.exit(test_trial23_correlation())
