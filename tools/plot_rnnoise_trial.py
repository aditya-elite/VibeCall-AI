"""Generate publication-quality comparative verification plot for the live on-device RNNoise + NPU Fusion trial."""

from __future__ import annotations

import csv
import json
import wave
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from scipy import signal


def load_wav(path: Path) -> tuple[int, np.ndarray]:
    with wave.open(str(path), "rb") as w:
        sr = w.getframerate()
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
    return sr, data


def load_accel(path: Path) -> tuple[np.ndarray, np.ndarray]:
    times: list[float] = []
    xyz: list[list[float]] = []
    with path.open("r", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            times.append(float(row["relative_to_audio_start_ns"]) / 1e9)
            xyz.append([float(row["x_m_s2"]), float(row["y_m_s2"]), float(row["z_m_s2"])])
    times_arr = np.array(times)
    xyz_arr = np.array(xyz)
    valid = times_arr >= 0
    return times_arr[valid], xyz_arr[valid]


def main():
    sess_dir = Path("sessions/20260906_122721_632_cheek_speaking_with_background_noise")
    meta = json.loads((sess_dir / "metadata.json").read_text(encoding="utf-8"))

    sr, raw_audio = load_wav(sess_dir / "microphone.wav")
    _, gated_audio = load_wav(sess_dir / "gated_microphone.wav")
    _, rnnoise_audio = load_wav(sess_dir / "microphone_rnnoise.wav")
    acc_time, acc_xyz = load_accel(sess_dir / "accelerometer.csv")

    # Time vectors
    audio_time = np.arange(len(raw_audio)) / sr
    acc_mag = np.linalg.norm(acc_xyz, axis=1)
    acc_detrend = signal.detrend(acc_mag, type="linear")

    # Align duration
    max_t = min(audio_time[-1], acc_time[-1])
    audio_mask = audio_time <= max_t
    audio_time = audio_time[audio_mask]
    raw_audio = raw_audio[audio_mask]
    gated_audio = gated_audio[audio_mask]
    rnnoise_audio = rnnoise_audio[audio_mask]

    acc_mask = acc_time <= max_t
    acc_time = acc_time[acc_mask]
    acc_detrend = acc_detrend[acc_mask]

    # Metrics
    rms_raw = np.sqrt(np.mean(raw_audio**2))
    rms_gated = np.sqrt(np.mean(gated_audio**2))
    rms_rnnoise = np.sqrt(np.mean(rnnoise_audio**2))
    gated_att = 20 * np.log10(rms_raw / (rms_gated + 1e-12))
    rnnoise_att = 20 * np.log10(rms_raw / (rms_rnnoise + 1e-12))
    avg_trust = meta.get("average_trust_value", 0.0)

    # Plot styling
    plt.rcParams["font.sans-serif"] = "DejaVu Sans"
    plt.rcParams["font.family"] = "sans-serif"
    fig, axes = plt.subplots(4, 1, figsize=(14, 11), sharex=True, constrained_layout=True)

    fig.suptitle(
        f"VibeCall Multimodal Neural Verification: Live Hardware Trial\n"
        f"{meta.get('manufacturer', '').capitalize()} {meta.get('model', '')} | "
        f"Snapdragon NPU (NNAPI: {meta.get('fusion_inference_count', 0)} inf) + On-Device RNNoise GRU | "
        f"Attenuation: {gated_att:.2f} dB",
        fontsize=14,
        fontweight="bold",
        color="#111827",
    )

    # 1. Raw Microphone
    axes[0].plot(audio_time, raw_audio, color="#DC2626", linewidth=0.55, alpha=0.9, label="Raw Mic (Heavy Noise + Voice)")
    axes[0].set_title("1. Acoustic Input: Raw Microphone (Loud Background Noise + Speech)", fontsize=11, fontweight="semibold", loc="left")
    axes[0].set_ylabel("Amplitude", fontsize=10)
    axes[0].set_ylim(-0.4, 0.4)
    axes[0].grid(True, linestyle="--", alpha=0.35)
    axes[0].legend(loc="upper right", framealpha=0.85)

    # 2. Accelerometer
    axes[1].plot(acc_time, acc_detrend, color="#059669", linewidth=0.85, label=f"ICM-4x6xx IMU Bone-Conduction ({meta.get('measured_accelerometer_rate_hz', 0):.1f} Hz)")
    axes[1].set_title("2. Kinematic Reference: Cheek IMU Vocal Vibration (Immune to Acoustic Ambient Noise)", fontsize=11, fontweight="semibold", loc="left")
    axes[1].set_ylabel("Detrended m/s²", fontsize=10)
    axes[1].set_ylim(-1.5, 1.5)
    axes[1].grid(True, linestyle="--", alpha=0.35)
    axes[1].legend(loc="upper right", framealpha=0.85)

    # 3. NPU Fusion Gate
    axes[2].plot(audio_time, gated_audio, color="#2563EB", linewidth=0.55, alpha=0.95, label=f"NPU Gated Output (Avg Trust: {avg_trust:.3f}, -{gated_att:.1f} dB)")
    axes[2].set_title(f"3. Multimodal NPU Gating: Dynamic Spatial-Kinematic Noise Suppressor (-{gated_att:.2f} dB)", fontsize=11, fontweight="semibold", loc="left")
    axes[2].set_ylabel("Amplitude", fontsize=10)
    axes[2].set_ylim(-0.4, 0.4)
    axes[2].grid(True, linestyle="--", alpha=0.35)
    axes[2].legend(loc="upper right", framealpha=0.85)

    # 4. RNNoise Neural Denoised
    axes[3].plot(audio_time, rnnoise_audio, color="#7C3AED", linewidth=0.55, alpha=0.95, label="RNNoise Neural Denoised (Real-time Recurrent GRU)")
    axes[3].set_title("4. Neural Post-Processing: Real-Time On-Device RNNoise Output", fontsize=11, fontweight="semibold", loc="left")
    axes[3].set_xlabel("Time (seconds)", fontsize=10)
    axes[3].set_ylabel("Amplitude", fontsize=10)
    axes[3].set_ylim(-0.4, 0.4)
    axes[3].grid(True, linestyle="--", alpha=0.35)
    axes[3].legend(loc="upper right", framealpha=0.85)

    # Save to docs/images and sessions
    out_img = Path("docs/images/test_c_rnnoise_comparison.png")
    fig.savefig(out_img, dpi=180)
    fig.savefig("sessions/test_c_rnnoise_comparison.png", dpi=180)
    plt.close(fig)
    print(f"Generated comparison figure at {out_img.resolve()}")


if __name__ == "__main__":
    main()
