"""Create a first-pass VibeCall feasibility report from one exported session."""

from __future__ import annotations

import argparse
import csv
import json
import tempfile
import wave
import zipfile
from pathlib import Path

import matplotlib.pyplot as plt
import numpy as np
from scipy import signal


def prepare_session(input_path: Path, temporary_root: Path) -> Path:
    if input_path.is_dir():
        return input_path
    if input_path.suffix.lower() != ".zip":
        raise ValueError("Input must be a VibeCall session directory or .zip file")
    with zipfile.ZipFile(input_path) as archive:
        archive.extractall(temporary_root)
    return temporary_root


def load_accelerometer(path: Path) -> tuple[np.ndarray, np.ndarray]:
    times: list[float] = []
    xyz: list[list[float]] = []
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        for row in reader:
            times.append(float(row["relative_to_audio_start_ns"]) / 1_000_000_000.0)
            xyz.append(
                [
                    float(row["x_m_s2"]),
                    float(row["y_m_s2"]),
                    float(row["z_m_s2"]),
                ]
            )
    if len(times) < 10:
        raise ValueError("The session has too few accelerometer samples")
    return np.asarray(times), np.asarray(xyz)


def load_wav(path: Path) -> tuple[int, np.ndarray]:
    with wave.open(str(path), "rb") as audio_file:
        if audio_file.getnchannels() != 1 or audio_file.getsampwidth() != 2:
            raise ValueError("Expected mono 16-bit PCM WAV")
        sample_rate = audio_file.getframerate()
        audio = np.frombuffer(audio_file.readframes(audio_file.getnframes()), dtype="<i2")
    return sample_rate, audio.astype(np.float32) / 32768.0


def estimate_rate(times: np.ndarray) -> float:
    positive_deltas = np.diff(times)
    positive_deltas = positive_deltas[positive_deltas > 0]
    if positive_deltas.size == 0:
        return 0.0
    return float(1.0 / np.median(positive_deltas))


def safe_spectrogram(values: np.ndarray, sample_rate: float):
    segment = min(256, max(16, len(values) // 8))
    return signal.spectrogram(
        values,
        fs=sample_rate,
        nperseg=segment,
        noverlap=segment // 2,
        scaling="spectrum",
    )


def analyse(session_dir: Path, output_path: Path) -> None:
    metadata = json.loads((session_dir / "metadata.json").read_text(encoding="utf-8"))
    acc_time, xyz = load_accelerometer(session_dir / "accelerometer.csv")
    audio_rate, audio = load_wav(session_dir / "microphone.wav")

    valid = acc_time >= 0
    acc_time = acc_time[valid]
    xyz = xyz[valid]
    if len(acc_time) < 10:
        raise ValueError("Too few accelerometer samples overlap the microphone recording")

    acc_rate = estimate_rate(acc_time)
    magnitude = np.linalg.norm(xyz, axis=1)
    vibration = signal.detrend(magnitude, type="linear")
    audio_time = np.arange(len(audio), dtype=np.float64) / audio_rate

    audio_f, audio_t, audio_s = safe_spectrogram(audio, audio_rate)
    acc_f, acc_t, acc_s = safe_spectrogram(vibration, acc_rate)

    fig, axes = plt.subplots(4, 1, figsize=(13, 12), constrained_layout=True)
    fig.suptitle(
        f"VibeCall feasibility session: {metadata.get('test_label', 'unknown')}\n"
        f"{metadata.get('manufacturer', '')} {metadata.get('model', '')} | "
        f"accelerometer {acc_rate:.1f} Hz | microphone {audio_rate} Hz",
        fontsize=14,
    )

    axes[0].plot(audio_time, audio, linewidth=0.6, color="#3157D5")
    axes[0].set(title="Microphone waveform", xlabel="Time (s)", ylabel="Amplitude")
    axes[0].grid(alpha=0.2)

    audio_db = 10.0 * np.log10(audio_s + 1e-12)
    axes[1].pcolormesh(audio_t, audio_f, audio_db, shading="auto", cmap="magma")
    axes[1].set(title="Microphone spectrogram", xlabel="Time (s)", ylabel="Frequency (Hz)")
    axes[1].set_ylim(0, min(4_000, audio_rate / 2))

    axes[2].plot(acc_time, vibration, linewidth=0.8, color="#00A98F")
    axes[2].set(
        title="Accelerometer vibration magnitude (gravity/motion trend removed)",
        xlabel="Time relative to microphone start (s)",
        ylabel="m/s²",
    )
    axes[2].grid(alpha=0.2)

    acc_db = 10.0 * np.log10(acc_s + 1e-12)
    axes[3].pcolormesh(acc_t, acc_f, acc_db, shading="auto", cmap="viridis")
    axes[3].set(title="Accelerometer spectrogram", xlabel="Time (s)", ylabel="Frequency (Hz)")
    axes[3].set_ylim(0, acc_rate / 2)

    fig.savefig(output_path, dpi=160)
    plt.close(fig)

    print(f"Session: {metadata.get('test_label')}")
    print(f"Accelerometer samples: {len(acc_time):,}")
    print(f"Estimated accelerometer rate: {acc_rate:.2f} Hz")
    print(f"Audio samples: {len(audio):,} at {audio_rate} Hz")
    print(f"Audio duration: {len(audio) / audio_rate:.2f} seconds")
    print(f"Accelerometer vibration RMS: {np.sqrt(np.mean(vibration ** 2)):.6f} m/s²")
    print(f"Report written to: {output_path.resolve()}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("session", type=Path, help="Exported session .zip or extracted directory")
    parser.add_argument("--output", type=Path, default=Path("vibecall_report.png"))
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="vibecall_") as temporary:
        session_dir = prepare_session(args.session.resolve(), Path(temporary))
        analyse(session_dir, args.output.resolve())


if __name__ == "__main__":
    main()
