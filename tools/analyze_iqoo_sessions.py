"""Analyze and generate comparative report + plots for iQOO phone hardware trials."""

import csv
import json
import wave
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from scipy import signal

def load_wav(path: Path):
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return sr, data

def load_accel(path: Path):
    times = []
    xyz = []
    with path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            times.append(float(row["relative_to_audio_start_ns"]) / 1e9)
            xyz.append([float(row["x_m_s2"]), float(row["y_m_s2"]), float(row["z_m_s2"])])
    times_arr = np.array(times)
    xyz_arr = np.array(xyz)
    valid = times_arr >= 0
    return times_arr[valid], xyz_arr[valid]

def calc_stats(audio):
    rms = np.sqrt(np.mean(audio**2))
    peak = np.max(np.abs(audio))
    return rms, peak

def main():
    base_dir = Path("sessions/iqoo_sessions")
    sessions = sorted([d for d in base_dir.iterdir() if d.is_dir()])
    
    print("=" * 70)
    print("iQOO HARDWARE TRIAL ANALYSIS REPORT")
    print("=" * 70)
    
    report_data = []

    for s in sessions:
        meta = json.loads((s / "metadata.json").read_text(encoding="utf-8"))
        sr_raw, raw_audio = load_wav(s / "microphone.wav")
        _, gated_audio = load_wav(s / "gated_microphone.wav")
        _, rnnoise_audio = load_wav(s / "microphone_rnnoise.wav")
        acc_t, acc_xyz = load_accel(s / "accelerometer.csv")
        
        duration = len(raw_audio) / sr_raw
        raw_rms, raw_peak = calc_stats(raw_audio)
        gated_rms, gated_peak = calc_stats(gated_audio)
        rn_rms, rn_peak = calc_stats(rnnoise_audio)
        
        # Noise floor estimation: 10th percentile of frame RMS (20ms frames)
        frame_len = int(0.02 * sr_raw)
        num_frames = len(raw_audio) // frame_len
        raw_frames_rms = [np.sqrt(np.mean(raw_audio[i*frame_len:(i+1)*frame_len]**2)) for i in range(num_frames)]
        rn_frames_rms = [np.sqrt(np.mean(rnnoise_audio[i*frame_len:(i+1)*frame_len]**2)) for i in range(num_frames)]
        
        raw_noise_floor = np.percentile(raw_frames_rms, 10) + 1e-9
        rn_noise_floor = np.percentile(rn_frames_rms, 10) + 1e-9
        noise_suppression_db = 20 * np.log10(raw_noise_floor / rn_noise_floor)
        
        acc_mag = np.linalg.norm(acc_xyz, axis=1)
        acc_vib = np.abs(signal.detrend(acc_mag))
        
        info = {
            "folder": s.name,
            "label": meta.get("test_label"),
            "manufacturer": meta.get("manufacturer"),
            "model": meta.get("model"),
            "android_release": meta.get("android_release"),
            "accelerometer_name": meta.get("accelerometer_name"),
            "sensor_rate": meta.get("measured_accelerometer_rate_hz"),
            "duration_s": duration,
            "raw_rms": raw_rms,
            "raw_peak": raw_peak,
            "gated_rms": gated_rms,
            "gated_peak": gated_peak,
            "rn_rms": rn_rms,
            "rn_peak": rn_peak,
            "avg_trust": meta.get("average_trust_value"),
            "inferences": meta.get("fusion_inference_count"),
            "noise_suppression_db": noise_suppression_db,
            "acc_vib_mean": np.mean(acc_vib),
            "acc_vib_max": np.max(acc_vib)
        }
        report_data.append(info)
        
        print(f"\nSession: {s.name}")
        print(f"  Label: {info['label']}")
        print(f"  Device: {info['manufacturer']} {info['model']} (Android {info['android_release']})")
        print(f"  Sensor: {info['accelerometer_name']} @ {info['sensor_rate']:.2f} Hz")
        print(f"  Duration: {info['duration_s']:.2f}s")
        print(f"  Raw Audio: RMS={raw_rms:.5f}, Peak={raw_peak:.5f}")
        print(f"  Gated Audio: RMS={gated_rms:.5f}, Peak={gated_peak:.5f}")
        print(f"  RNNoise Audio: RMS={rn_rms:.5f}, Peak={rn_peak:.5f}")
        print(f"  Noise Floor Suppression (RNNoise): {noise_suppression_db:.2f} dB")
        print(f"  NPU Fusion Gate: Avg Trust={info['avg_trust']:.4f} across {info['inferences']} inferences")
        print(f"  Vibration (detrended): Mean={np.mean(acc_vib):.4f} m/s^2, Max={np.max(acc_vib):.4f} m/s^2")

    # Plot the primary benchmark session: 20260912_121625_892_cheek_speaking_with_background_noise
    best_noisy_session = base_dir / "20260912_121625_892_cheek_speaking_with_background_noise"
    sr, raw_audio = load_wav(best_noisy_session / "microphone.wav")
    _, gated_audio = load_wav(best_noisy_session / "gated_microphone.wav")
    _, rnnoise_audio = load_wav(best_noisy_session / "microphone_rnnoise.wav")
    acc_t, acc_xyz = load_accel(best_noisy_session / "accelerometer.csv")

    t_audio = np.arange(len(raw_audio)) / sr
    max_t = min(t_audio[-1], acc_t[-1])
    
    mask_a = t_audio <= max_t
    t_audio = t_audio[mask_a]
    raw_audio = raw_audio[mask_a]
    gated_audio = gated_audio[mask_a]
    rnnoise_audio = rnnoise_audio[mask_a]
    
    mask_s = acc_t <= max_t
    acc_t = acc_t[mask_s]
    acc_mag = np.linalg.norm(acc_xyz[mask_s], axis=1)
    acc_vib = np.abs(signal.detrend(acc_mag))

    plt.style.use("seaborn-v0_8-darkgrid" if "seaborn-v0_8-darkgrid" in plt.style.available else "default")
    fig, axes = plt.subplots(4, 1, figsize=(13, 10), sharex=True)
    
    fig.suptitle("iQOO Hardware Verification Trial (vivo I2501, ST LSM6DSV16X @ 400Hz)\nOut-going Speech Enhancement: Raw vs NPU Fusion vs RNNoise", fontsize=13, fontweight='bold')

    # Panel 1: Raw Mic
    axes[0].plot(t_audio, raw_audio, color='#d62728', alpha=0.8, lw=0.6)
    axes[0].set_ylabel("Raw Mic\n(Normalized)", fontsize=9, fontweight='bold')
    axes[0].set_ylim(-1.05, 1.05)
    axes[0].grid(True, alpha=0.3)

    # Panel 2: NPU Gated Mic
    axes[1].plot(t_audio, gated_audio, color='#ff7f0e', alpha=0.8, lw=0.6)
    axes[1].set_ylabel("NPU Gated\n(NNAPI Delegate)", fontsize=9, fontweight='bold')
    axes[1].set_ylim(-1.05, 1.05)
    axes[1].grid(True, alpha=0.3)

    # Panel 3: RNNoise Denoised
    axes[2].plot(t_audio, rnnoise_audio, color='#2ca02c', alpha=0.8, lw=0.6)
    axes[2].set_ylabel("RNNoise\n(On-Device Denoised)", fontsize=9, fontweight='bold')
    axes[2].set_ylim(-1.05, 1.05)
    axes[2].grid(True, alpha=0.3)

    # Panel 4: Accelerometer Vibration
    axes[3].plot(acc_t, acc_vib, color='#1f77b4', lw=0.8)
    axes[3].set_ylabel("Vibration Accel\n|detrend(norm)| (m/s²)", fontsize=9, fontweight='bold')
    axes[3].set_xlabel("Time (seconds)", fontsize=10, fontweight='bold')
    axes[3].grid(True, alpha=0.3)

    plt.tight_layout()
    out_plot = Path("docs/images/iqoo_trial_comparison.png")
    out_plot.parent.mkdir(parents=True, exist_ok=True)
    plt.savefig(out_plot, dpi=180)
    print(f"\nGenerated comparative plot saved to: {out_plot}")
    
    with open("docs/iqoo_trial_summary.json", "w", encoding="utf-8") as f:
        json.dump(report_data, f, indent=2, default=lambda x: float(x) if isinstance(x, (np.floating, np.integer)) else str(x))
    print("Saved summary JSON to docs/iqoo_trial_summary.json")

if __name__ == "__main__":
    main()
