#!/usr/bin/env python3
"""
tools/train_fusion_model.py
===========================
Trains the feature-based fusion-confidence model for VibeCall-AI and exports
an official TensorFlow Lite model using the official TensorFlow Lite converter.

Input tensor specification: [1, 16] Float32 in exact order:
 [0]  microphone_log_energy_db
 [1]  microphone_pitch_strength
 [2]  microphone_pitch_reliable
 [3]  log_accel_peak_power
 [4]  accel_peak_prominence
 [5]  pitch_difference_hz
 [6]  pitch_agreement_score
 [7]  pitch_agreement_reliable
 [8]  phone_motion_level
 [9]  sensor_reliability
 [10] sensor_alignment_lag_ms
 [11] prev_microphone_log_energy_db
 [12] prev_microphone_pitch_strength
 [13] prev_pitch_agreement_score
 [14] prev_phone_motion_level
 [15] prev_sensor_reliability
"""

import os
import sys
import json
import csv
import numpy as np

# Ensure TensorFlow deterministic execution
os.environ["TF_DETERMINISTIC_OPS"] = "1"
os.environ["PYTHONHASHSEED"] = "42"
np.random.seed(42)

import tensorflow as tf
tf.random.set_seed(42)

FEATURE_COLUMNS_16 = [
    "microphone_log_energy_db",
    "microphone_pitch_strength",
    "microphone_pitch_reliable",
    "log_accel_peak_power",
    "accel_peak_prominence",
    "pitch_difference_hz",
    "pitch_agreement_score",
    "pitch_agreement_reliable",
    "phone_motion_level",
    "sensor_reliability",
    "sensor_alignment_lag_ms",
    "prev_microphone_log_energy_db",
    "prev_microphone_pitch_strength",
    "prev_pitch_agreement_score",
    "prev_phone_motion_level",
    "prev_sensor_reliability",
]


def load_dataset(csv_path: str):
    with open(csv_path, "r", encoding="utf-8") as f:
        reader = list(csv.DictReader(f))

    # Keep only windows with target >= 0.0 (exclude UNCERTAIN)
    valid_rows = [r for r in reader if float(r["training_target"]) >= 0.0]

    session_ids = [r["session_id"] for r in valid_rows]
    labels = [r["physical_label"] for r in valid_rows]
    targets = np.array([float(r["training_target"]) for r in valid_rows], dtype=np.float32)

    X_raw = np.zeros((len(valid_rows), len(FEATURE_COLUMNS_16)), dtype=np.float32)
    for i, r in enumerate(valid_rows):
        for j, col in enumerate(FEATURE_COLUMNS_16):
            X_raw[i, j] = float(r[col])

    return X_raw, targets, session_ids, labels, valid_rows


def compute_normalization(X_train: np.ndarray):
    means = np.mean(X_train, axis=0)
    stds = np.std(X_train, axis=0)
    # Avoid zero division
    stds = np.where(stds < 1e-6, 1.0, stds)
    return means, stds


def apply_normalization(X: np.ndarray, means: np.ndarray, stds: np.ndarray, clip=5.0):
    X_norm = (X - means) / stds
    return np.clip(X_norm, -clip, clip).astype(np.float32)


def build_keras_model(input_dim=16):
    model = tf.keras.Sequential([
        tf.keras.layers.Input(shape=(input_dim,)),
        tf.keras.layers.Dense(16, activation="relu", name="dense_1"),
        tf.keras.layers.Dense(8, activation="relu", name="dense_2"),
        tf.keras.layers.Dense(1, activation="sigmoid", name="confidence_output"),
    ])
    model.compile(
        optimizer=tf.keras.optimizers.Adam(learning_rate=0.005),
        loss="binary_crossentropy",
        metrics=["accuracy"],
    )
    return model


def compute_metrics(y_true, y_pred, threshold=0.5):
    y_pred_bin = (y_pred >= threshold).astype(int)
    y_true_bin = (y_true >= 0.5).astype(int)

    tp = int(np.sum((y_true_bin == 1) & (y_pred_bin == 1)))
    fp = int(np.sum((y_true_bin == 0) & (y_pred_bin == 1)))
    fn = int(np.sum((y_true_bin == 1) & (y_pred_bin == 0)))
    tn = int(np.sum((y_true_bin == 0) & (y_pred_bin == 0)))

    precision = tp / (tp + fp) if (tp + fp) > 0 else 0.0
    recall = tp / (tp + fn) if (tp + fn) > 0 else 0.0
    f1 = 2 * precision * recall / (precision + recall) if (precision + recall) > 0 else 0.0
    fpr = fp / (fp + tn) if (fp + tn) > 0 else 0.0

    return {
        "tp": tp, "fp": fp, "fn": fn, "tn": tn,
        "precision": float(precision),
        "recall": float(recall),
        "f1": float(f1),
        "false_positive_rate": float(fpr),
        "accuracy": float((tp + tn) / max(1, tp + tn + fp + fn)),
    }


def run_session_loso_evaluation(X_raw, targets, session_ids, labels):
    unique_sessions = sorted(list(set(session_ids)))
    print(f"\n--- Running Leave-One-Session-Out Cross-Validation ({len(unique_sessions)} Folds) ---")

    fold_reports = []
    all_val_preds = []
    all_val_trues = []
    all_val_labels = []

    for fold_idx, val_session in enumerate(unique_sessions):
        val_mask = np.array([s == val_session for s in session_ids])
        train_mask = ~val_mask

        X_train_raw, y_train = X_raw[train_mask], targets[train_mask]
        X_val_raw, y_val = X_raw[val_mask], targets[val_mask]
        labels_val = [labels[i] for i in range(len(labels)) if val_mask[i]]

        # Normalization derived strictly from training fold
        means, stds = compute_normalization(X_train_raw)
        X_train_norm = apply_normalization(X_train_raw, means, stds)
        X_val_norm = apply_normalization(X_val_raw, means, stds)

        # Class weights
        n_pos = np.sum(y_train == 1.0)
        n_neg = np.sum(y_train == 0.0)
        class_weight = {0: 1.0, 1: float(n_neg / max(1, n_pos))}

        model = build_keras_model(16)
        model.fit(
            X_train_norm, y_train,
            epochs=35,
            batch_size=32,
            class_weight=class_weight,
            verbose=0,
        )

        y_val_pred = model.predict(X_val_norm, verbose=0).flatten()

        metrics = compute_metrics(y_val, y_val_pred, threshold=0.5)
        fold_reports.append({
            "val_session": val_session,
            "train_samples": int(len(y_train)),
            "val_samples": int(len(y_val)),
            "metrics_at_0_5": metrics,
        })

        all_val_preds.extend(y_val_pred.tolist())
        all_val_trues.extend(y_val.tolist())
        all_val_labels.extend(labels_val)

        print(f"  Fold {fold_idx+1}/{len(unique_sessions)} ({val_session[:25]}...): "
              f"Acc={metrics['accuracy']:.3f}, P={metrics['precision']:.3f}, R={metrics['recall']:.3f}, F1={metrics['f1']:.3f}")

    all_val_preds = np.array(all_val_preds)
    all_val_trues = np.array(all_val_trues)

    overall_metrics = compute_metrics(all_val_trues, all_val_preds, threshold=0.5)
    print(f"\nOverall Cross-Validation Metrics (at threshold=0.5):")
    print(f"  Accuracy:  {overall_metrics['accuracy']:.4f}")
    print(f"  Precision: {overall_metrics['precision']:.4f}")
    print(f"  Recall:    {overall_metrics['recall']:.4f}")
    print(f"  F1 Score:  {overall_metrics['f1']:.4f}")
    print(f"  FPR:       {overall_metrics['false_positive_rate']:.4f}")
    print(f"  Matrix: TP={overall_metrics['tp']}, FP={overall_metrics['fp']}, FN={overall_metrics['fn']}, TN={overall_metrics['tn']}")

    # Breakdown by condition
    condition_breakdown = {}
    for cond in sorted(list(set(all_val_labels))):
        c_mask = np.array([lbl == cond for lbl in all_val_labels])
        sub_true = all_val_trues[c_mask]
        sub_pred = all_val_preds[c_mask]
        mean_conf = float(np.mean(sub_pred))
        p_above_70 = float(np.mean(sub_pred >= 0.70))
        p_below_20 = float(np.mean(sub_pred <= 0.20))
        condition_breakdown[cond] = {
            "samples": int(len(sub_true)),
            "mean_confidence": mean_conf,
            "pct_conf_ge_0_70": p_above_70,
            "pct_conf_le_0_20": p_below_20,
            "metrics": compute_metrics(sub_true, sub_pred, threshold=0.5),
        }
        print(f"  Condition '{cond:22s}' (N={len(sub_true):3d}): Mean Conf={mean_conf:.3f}, %Conf>=0.70={p_above_70*100:.1f}%, %Conf<=0.20={p_below_20*100:.1f}%")

    return fold_reports, overall_metrics, condition_breakdown


def train_and_export_production_model(X_raw, targets, out_tflite_path, out_meta_path):
    print("\n--- Training Production Fusion-Confidence Model on Full Step 4 Dataset ---")
    means, stds = compute_normalization(X_raw)
    X_norm = apply_normalization(X_raw, means, stds)

    n_pos = np.sum(targets == 1.0)
    n_neg = np.sum(targets == 0.0)
    class_weight = {0: 1.0, 1: float(n_neg / max(1, n_pos))}

    model = build_keras_model(16)
    history = model.fit(
        X_norm, targets,
        epochs=40,
        batch_size=32,
        class_weight=class_weight,
        verbose=1,
    )

    y_keras = model.predict(X_norm, verbose=0).flatten()

    # Export to SavedModel
    saved_model_dir = "tools/saved_model"
    model.export(saved_model_dir)
    print(f"SavedModel exported to {saved_model_dir}")

    # Official TensorFlow Lite Conversion
    print("Converting Keras model using official tf.lite.TFLiteConverter...")
    converter = tf.lite.TFLiteConverter.from_saved_model(saved_model_dir)
    converter.optimizations = [tf.lite.Optimize.DEFAULT]
    tflite_model_bytes = converter.convert()

    os.makedirs(os.path.dirname(out_tflite_path), exist_ok=True)
    with open(out_tflite_path, "wb") as f:
        f.write(tflite_model_bytes)
    print(f"Official TFLite model written to {out_tflite_path} ({len(tflite_model_bytes)} bytes)")

    # Verify Numerical Equivalence using TFLite Interpreter
    interpreter = tf.lite.Interpreter(model_path=out_tflite_path)
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()

    print("\nTFLite Tensor Details:")
    print(f"  Input Tensor:  shape={input_details[0]['shape']}, dtype={input_details[0]['dtype']}")
    print(f"  Output Tensor: shape={output_details[0]['shape']}, dtype={output_details[0]['dtype']}")

    # Test all rows
    y_tflite = np.zeros_like(y_keras)
    for i in range(len(X_norm)):
        test_vec = np.expand_dims(X_norm[i], axis=0).astype(np.float32)
        interpreter.set_tensor(input_details[0]["index"], test_vec)
        interpreter.invoke()
        y_tflite[i] = interpreter.get_tensor(output_details[0]["index"])[0, 0]

    max_abs_diff = float(np.max(np.abs(y_keras - y_tflite)))
    mean_abs_diff = float(np.mean(np.abs(y_keras - y_tflite)))
    print(f"Keras vs TFLite Equivalence Test: max_diff={max_abs_diff:.6e}, mean_diff={mean_abs_diff:.6e}")
    assert max_abs_diff < 1e-4, f"TFLite output differs significantly from Keras! max_diff={max_abs_diff}"
    print("Verification PASSED: Keras and official TFLite models are numerically equivalent.")

    # Write Model Schema & Metadata JSON
    metadata = {
        "model_name": "fusion_confidence_model.tflite",
        "format_version": "1.0.0",
        "description": "VibeCall-AI Step 4 MLP fusion-confidence model for Outgoing Speech Enhancement",
        "scientific_disclaimer": "Trained on single-speaker iQOO 15 hardware dataset. Treat as experimental prototype; do not claim universal generalization.",
        "input_tensor": {
            "name": "features_input",
            "shape": [1, 16],
            "dtype": "FLOAT32",
            "feature_order": FEATURE_COLUMNS_16,
        },
        "output_tensor": {
            "name": "confidence_output",
            "shape": [1, 1],
            "dtype": "FLOAT32",
            "range": [0.0, 1.0],
            "description": "contact_speech_confidence (higher indicates higher probability of bone-conducted speech)",
        },
        "normalization": {
            "method": "z_score",
            "means": [float(m) for m in means],
            "stds": [float(s) for s in stds],
            "clip_min": -5.0,
            "clip_max": 5.0,
        },
        "preliminary_thresholds": {
            "preservation_threshold": 0.70,
            "pause_candidate_threshold": 0.20,
            "uncertain_range": [0.20, 0.70],
            "status": "uncalibrated_preliminary",
        },
        "acoustic_guard": {
            "pause_energy_threshold_db": -55.0,
            "min_consecutive_pause_windows": 3,
            "require_mic_pitch_unreliable": True,
            "description": "Acoustic silence must be confirmed by microphone before pause attenuation is eligible.",
        },
        "gain_controller_defaults": {
            "min_gain": 0.50,
            "attenuation_ramp_ms": 300.0,
            "restore_ramp_ms": 30.0,
            "hangover_windows": 2,
        },
        "training_metadata": {
            "total_samples": int(len(targets)),
            "positive_samples": int(n_pos),
            "negative_samples": int(n_neg),
            "training_accuracy": float(history.history["accuracy"][-1]),
            "seed": 42,
        }
    }

    os.makedirs(os.path.dirname(out_meta_path), exist_ok=True)
    with open(out_meta_path, "w", encoding="utf-8") as f:
        json.dump(metadata, f, indent=2)
    print(f"Model metadata schema written to {out_meta_path}")

    return metadata


if __name__ == "__main__":
    dataset_csv = "tools/data/training_dataset.csv"
    out_tflite = "app/src/main/assets/fusion_confidence_model.tflite"
    out_metadata = "app/src/main/assets/fusion_model_metadata.json"
    out_report = "tools/data/training_report.json"

    print("Loading dataset...")
    X_raw, targets, session_ids, labels, valid_rows = load_dataset(dataset_csv)
    print(f"Loaded {len(targets)} samples across {len(set(session_ids))} unique sessions.")

    # 1. Cross-Validation
    fold_reports, overall_metrics, condition_breakdown = run_session_loso_evaluation(
        X_raw, targets, session_ids, labels
    )

    # 2. Production Model Training and Export
    model_metadata = train_and_export_production_model(X_raw, targets, out_tflite, out_metadata)

    # 3. Save Full Report
    full_report = {
        "dataset_csv": dataset_csv,
        "sample_count": len(targets),
        "sessions": sorted(list(set(session_ids))),
        "cross_validation": {
            "overall_metrics": overall_metrics,
            "condition_breakdown": condition_breakdown,
            "folds": fold_reports,
        },
        "model_metadata": model_metadata,
    }

    with open(out_report, "w", encoding="utf-8") as f:
        json.dump(full_report, f, indent=2)
    print(f"\nFull evaluation report saved to {out_report}")
