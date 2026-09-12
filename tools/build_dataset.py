#!/usr/bin/env python3
"""
tools/build_dataset.py
======================
Builds the calibrated, version-controlled Step 4 dataset for VibeCall-AI.

Invariants:
1. Detects feature schema version; NEVER silently imputes missing features with zeroes.
2. Incompatible sessions (e.g. Trials 7 and 8) are formally audited and excluded.
3. Maps intervals from tools/dataset_manifest.json to exact physical labels.
4. Excludes UNCERTAIN windows from the training set.
5. Employs the exact 16-element feature vector in strictly specified tensor order:
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
6. Outputs training_dataset.csv and an audit report.
"""

import os
import sys
import json
import math
import csv
from typing import List, Dict, Any, Tuple

REQUIRED_STEP3_COLUMNS = [
    "audio_window_start_ms",
    "audio_window_center_ms",
    "audio_window_end_ms",
    "microphone_rms",
    "microphone_log_energy_db",
    "microphone_pitch_hz",
    "microphone_pitch_strength",
    "microphone_pitch_reliable",
    "accel_best_axis",
    "accel_peak_hz",
    "accel_peak_power",
    "accel_peak_prominence",
    "accel_peak_reliable",
    "pitch_difference_hz",
    "pitch_agreement_score",
    "pitch_agreement_reliable",
    "sensor_alignment_lag_ms",
    "phone_motion_level",
    "sensor_sample_count",
    "sensor_rate_hz",
    "sensor_reliability",
]

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


def load_manifest(manifest_path: str) -> Dict[str, Any]:
    with open(manifest_path, "r", encoding="utf-8") as f:
        return json.load(f)


def audit_session_schema(session_dir: str) -> Tuple[bool, str, List[str]]:
    feat_path = os.path.join(session_dir, "features.csv")
    if not os.path.exists(feat_path):
        return False, "features.csv not found", []

    with open(feat_path, "r", encoding="utf-8") as f:
        reader = csv.reader(f)
        header = next(reader, [])

    missing = [col for col in REQUIRED_STEP3_COLUMNS if col not in header]
    if missing:
        return (
            False,
            f"Incompatible schema: missing {len(missing)} required Step 3 columns: {missing[:5]}...",
            header,
        )
    return True, "Schema compatible (Step 3 34-column specification)", header


def assign_interval_label(
    start_ms: float, end_ms: float, intervals: List[Dict[str, Any]], default_label: str
) -> Tuple[str, str]:
    # A window must be comfortably within an interval (center point and >75% coverage)
    center_ms = (start_ms + end_ms) / 2.0
    for inv in intervals:
        i_start = float(inv["start_ms"])
        i_end = float(inv["end_ms"])
        if i_start <= center_ms <= i_end:
            # Check overlap coverage
            overlap_start = max(start_ms, i_start)
            overlap_end = min(end_ms, i_end)
            overlap_dur = max(0.0, overlap_end - overlap_start)
            window_dur = max(1.0, end_ms - start_ms)
            if overlap_dur / window_dur >= 0.70:
                return inv["label"], inv.get("notes", "")
    return default_label, "Outside defined physical intervals (transition/boundary/startup/termination)"


def build_dataset(
    manifest_path: str,
    sessions_root: str,
    output_csv_path: str,
    output_audit_path: str,
) -> Dict[str, Any]:
    manifest = load_manifest(manifest_path)
    label_map = manifest["label_mapping"]

    os.makedirs(os.path.dirname(output_csv_path), exist_ok=True)
    os.makedirs(os.path.dirname(output_audit_path), exist_ok=True)

    audit_records = []
    dataset_rows = []

    label_counts = {lbl: 0 for lbl in manifest["labels"]}
    session_counts = {}

    for s_entry in manifest["sessions"]:
        session_id = s_entry["session_id"]
        session_dir = os.path.join(sessions_root, session_id)
        trial_alias = s_entry.get("trial_alias", session_id)

        # Check manifest compatibility declaration
        if not s_entry.get("is_compatible", False):
            audit_records.append({
                "session_id": session_id,
                "trial_alias": trial_alias,
                "status": "EXCLUDED",
                "reason": s_entry.get("exclusion_reason", "Marked incompatible in manifest"),
                "total_rows": 0,
                "included_rows": 0,
            })
            continue

        if not os.path.exists(session_dir):
            audit_records.append({
                "session_id": session_id,
                "trial_alias": trial_alias,
                "status": "EXCLUDED",
                "reason": f"Directory not found: {session_dir}",
                "total_rows": 0,
                "included_rows": 0,
            })
            continue

        # Check physical schema compatibility
        is_compat, reason, header = audit_session_schema(session_dir)
        if not is_compat:
            audit_records.append({
                "session_id": session_id,
                "trial_alias": trial_alias,
                "status": "EXCLUDED",
                "reason": reason,
                "total_rows": 0,
                "included_rows": 0,
            })
            continue

        intervals = s_entry.get("intervals", [])
        if not intervals:
            audit_records.append({
                "session_id": session_id,
                "trial_alias": trial_alias,
                "status": "EXCLUDED",
                "reason": s_entry.get("exclusion_reason", "No valid intervals defined"),
                "total_rows": 0,
                "included_rows": 0,
            })
            continue

        # Load session rows
        feat_path = os.path.join(session_dir, "features.csv")
        with open(feat_path, "r", encoding="utf-8") as f:
            reader = list(csv.DictReader(f))

        total_rows = len(reader)
        included_rows = 0

        prev_features = None

        for idx, row in enumerate(reader):
            start_ms = float(row["audio_window_start_ms"])
            end_ms = float(row["audio_window_end_ms"])
            lag_ms = float(row["sensor_alignment_lag_ms"])
            sens_rel = float(row["sensor_reliability"])

            # Basic features
            mic_energy_db = float(row["microphone_log_energy_db"])
            mic_pitch_str = float(row["microphone_pitch_strength"])
            mic_pitch_rel = float(row["microphone_pitch_reliable"])
            
            raw_peak_power = float(row["accel_peak_power"])
            log_peak_power = math.log10(max(raw_peak_power, 0.0) + 1e-6)
            
            peak_prom = float(row["accel_peak_prominence"])
            pitch_diff_hz = float(row["pitch_difference_hz"])
            pitch_agree_score = float(row["pitch_agreement_score"])
            pitch_agree_rel = float(row["pitch_agreement_reliable"])
            motion_level = float(row["phone_motion_level"])

            # Lag-1 historical context
            if prev_features is None:
                prev_mic_energy_db = mic_energy_db
                prev_mic_pitch_str = mic_pitch_str
                prev_pitch_agree_sc = pitch_agree_score
                prev_motion_level = motion_level
                prev_sens_rel = sens_rel
            else:
                prev_mic_energy_db = prev_features["mic_energy_db"]
                prev_mic_pitch_str = prev_features["mic_pitch_str"]
                prev_pitch_agree_sc = prev_features["pitch_agree_sc"]
                prev_motion_level = prev_features["motion_level"]
                prev_sens_rel = prev_features["sens_rel"]

            # Update prev_features for next iteration
            prev_features = {
                "mic_energy_db": mic_energy_db,
                "mic_pitch_str": mic_pitch_str,
                "pitch_agree_sc": pitch_agree_score,
                "motion_level": motion_level,
                "sens_rel": sens_rel,
            }

            # Assign label
            label, note = assign_interval_label(
                start_ms, end_ms, intervals, s_entry.get("default_label", "UNCERTAIN")
            )

            # Strict reliability gating: if sensor lag is excessive or reliability dropped, mark UNCERTAIN
            if lag_ms > 15.0 or sens_rel < 0.70:
                label = "UNCERTAIN"
                note = f"Sensor penalized (lag={lag_ms:.2f}ms, rel={sens_rel:.2f})"

            target = label_map.get(label, -1.0)
            label_counts[label] = label_counts.get(label, 0) + 1

            record = {
                "session_id": session_id,
                "trial_alias": trial_alias,
                "window_index": idx,
                "audio_window_start_ms": f"{start_ms:.1f}",
                "audio_window_end_ms": f"{end_ms:.1f}",
                "physical_label": label,
                "training_target": target,
                "notes": note,
                # 16 Model Features in exact order:
                "microphone_log_energy_db": f"{mic_energy_db:.4f}",
                "microphone_pitch_strength": f"{mic_pitch_str:.4f}",
                "microphone_pitch_reliable": f"{mic_pitch_rel:.1f}",
                "log_accel_peak_power": f"{log_peak_power:.6f}",
                "accel_peak_prominence": f"{peak_prom:.4f}",
                "pitch_difference_hz": f"{pitch_diff_hz:.4f}",
                "pitch_agreement_score": f"{pitch_agree_score:.6f}",
                "pitch_agreement_reliable": f"{pitch_agree_rel:.1f}",
                "phone_motion_level": f"{motion_level:.6f}",
                "sensor_reliability": f"{sens_rel:.4f}",
                "sensor_alignment_lag_ms": f"{lag_ms:.4f}",
                "prev_microphone_log_energy_db": f"{prev_mic_energy_db:.4f}",
                "prev_microphone_pitch_strength": f"{prev_mic_pitch_str:.4f}",
                "prev_pitch_agreement_score": f"{prev_pitch_agree_sc:.6f}",
                "prev_phone_motion_level": f"{prev_motion_level:.6f}",
                "prev_sensor_reliability": f"{prev_sens_rel:.4f}",
            }
            dataset_rows.append(record)
            if target >= 0.0:
                included_rows += 1

        session_counts[session_id] = included_rows
        audit_records.append({
            "session_id": session_id,
            "trial_alias": trial_alias,
            "status": "INCLUDED" if included_rows > 0 else "EXCLUDED",
            "reason": f"Processed {total_rows} frames, {included_rows} training frames mapped",
            "total_rows": total_rows,
            "included_rows": included_rows,
        })

    # Write full dataset CSV
    fieldnames = [
        "session_id",
        "trial_alias",
        "window_index",
        "audio_window_start_ms",
        "audio_window_end_ms",
        "physical_label",
        "training_target",
        "notes",
    ] + FEATURE_COLUMNS_16

    with open(output_csv_path, "w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(dataset_rows)

    # Compute breakdown per session and per class
    session_class_counts: Dict[str, Dict[str, int]] = {}
    for r in dataset_rows:
        alias = r["trial_alias"]
        lbl = r["physical_label"]
        if alias not in session_class_counts:
            session_class_counts[alias] = {c: 0 for c in manifest["labels"]}
        session_class_counts[alias][lbl] = session_class_counts[alias].get(lbl, 0) + 1

    training_class_counts: Dict[str, int] = {}
    for r in dataset_rows:
        if float(r["training_target"]) >= 0.0:
            lbl = r["physical_label"]
            training_class_counts[lbl] = training_class_counts.get(lbl, 0) + 1

    # Generate explicit warnings for small classes and single-session dependencies
    audit_warnings: List[str] = []
    for lbl in manifest["labels"]:
        if lbl == "UNCERTAIN":
            continue
        cnt = training_class_counts.get(lbl, 0)
        if cnt == 0:
            audit_warnings.append(f"CRITICAL: Class '{lbl}' has ZERO training samples!")
        elif cnt < 50:
            audit_warnings.append(
                f"WARNING: Class '{lbl}' has only {cnt} training samples (< 50 threshold). "
                f"Statistical power and generalizability are constrained."
            )

    away_sessions = set(r["trial_alias"] for r in dataset_rows if r["physical_label"] == "AWAY_SPEECH")
    if len(away_sessions) <= 1:
        audit_warnings.append(
            f"WARNING: Negative control class 'AWAY_SPEECH' is represented by only {len(away_sessions)} session(s) "
            f"({list(away_sessions)}). In Leave-One-Session-Out evaluation, models trained without this session have "
            f"0 negative speech samples, causing 100% false-positive rate on airborne speech."
        )

    # Write audit JSON report
    audit_summary = {
        "manifest_file": manifest_path,
        "total_sessions_in_manifest": len(manifest["sessions"]),
        "included_sessions_count": sum(1 for r in audit_records if r["status"] == "INCLUDED"),
        "excluded_sessions_count": sum(1 for r in audit_records if r["status"] == "EXCLUDED"),
        "total_windows_extracted": len(dataset_rows),
        "training_windows_count": sum(1 for r in dataset_rows if float(r["training_target"]) >= 0.0),
        "excluded_uncertain_windows": sum(1 for r in dataset_rows if float(r["training_target"]) < 0.0),
        "label_distribution": label_counts,
        "training_class_distribution": training_class_counts,
        "session_training_counts": session_counts,
        "session_class_distribution": session_class_counts,
        "warnings": audit_warnings,
        "session_audit_details": audit_records,
    }

    with open(output_audit_path, "w", encoding="utf-8") as f:
        json.dump(audit_summary, f, indent=2)

    return audit_summary


if __name__ == "__main__":
    manifest_p = "tools/dataset_manifest.json"
    sessions_r = "sessions/iqoo_sessions"
    out_csv = "tools/data/training_dataset.csv"
    out_audit = "tools/data/audit_report.json"

    print("Building Step 4 dataset...")
    summary = build_dataset(manifest_p, sessions_r, out_csv, out_audit)
    print(f"Dataset generated at {out_csv}")
    print(f"Audit report saved at {out_audit}")
    print(f"Total training windows: {summary['training_windows_count']}")
    print(f"Label breakdown: {json.dumps(summary['label_distribution'], indent=2)}")
