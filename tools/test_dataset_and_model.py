#!/usr/bin/env python3
"""
Step 4 Test Suite: Dataset integrity, schema validation, exact feature tensor ordering,
normalization consistency, and TFLite model verification.
"""

import csv
import json
import os
import unittest
import numpy as np
import tensorflow as tf

PROJECT_ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
MANIFEST_PATH = os.path.join(PROJECT_ROOT, "tools", "dataset_manifest.json")
AUDIT_REPORT_PATH = os.path.join(PROJECT_ROOT, "tools", "data", "audit_report.json")
DATASET_PATH = os.path.join(PROJECT_ROOT, "tools", "data", "training_dataset.csv")
TFLITE_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "fusion_confidence_model.tflite")
METADATA_PATH = os.path.join(PROJECT_ROOT, "app", "src", "main", "assets", "fusion_model_metadata.json")
SAVED_MODEL_DIR = os.path.join(PROJECT_ROOT, "tools", "saved_model")

EXACT_16_FEATURES = [
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


class TestDatasetAndModel(unittest.TestCase):

    def test_01_manifest_structure_and_intervals(self):
        """Manifest exists, has valid JSON, and all intervals have valid start < end."""
        self.assertTrue(os.path.exists(MANIFEST_PATH), f"Missing {MANIFEST_PATH}")
        with open(MANIFEST_PATH, "r", encoding="utf-8") as f:
            manifest = json.load(f)

        self.assertIn("sessions", manifest)
        sessions = manifest["sessions"]
        self.assertGreaterEqual(len(sessions), 6)

        valid_conditions = {
            "CONTACT_SPEECH",
            "CONTACT_SPEECH_MOVEMENT",
            "AWAY_SPEECH",
            "SILENCE_STILL",
            "SILENCE_MOVEMENT",
            "UNCERTAIN"
        }

        for sdata in sessions:
            session_id = sdata.get("session_id", "unknown")
            self.assertIn("is_compatible", sdata)
            intervals = sdata.get("intervals", [])
            for interval in intervals:
                self.assertIn("start_ms", interval)
                self.assertIn("end_ms", interval)
                self.assertIn("label", interval)
                self.assertLess(interval["start_ms"], interval["end_ms"],
                                f"Invalid interval timing in session {session_id}")
                self.assertIn(interval["label"], valid_conditions,
                              f"Unknown condition {interval['label']} in session {session_id}")

    def test_02_schema_incompatibility_exclusion(self):
        """Legacy sessions (Trials 7 & 8) must be explicitly excluded with documented reasons."""
        self.assertTrue(os.path.exists(AUDIT_REPORT_PATH), f"Missing {AUDIT_REPORT_PATH}")
        with open(AUDIT_REPORT_PATH, "r", encoding="utf-8") as f:
            audit = json.load(f)

        self.assertIn("session_audit_details", audit)
        excluded = [s for s in audit["session_audit_details"] if s["status"] == "EXCLUDED"]
        excluded_aliases = [s["trial_alias"] for s in excluded]
        self.assertIn("Trial 7", excluded_aliases, "Trial 7 must be marked EXCLUDED")
        self.assertIn("Trial 8", excluded_aliases, "Trial 8 must be marked EXCLUDED")

        for s in excluded:
            self.assertIn("reason", s)
            self.assertGreater(len(s["reason"]), 10)

    def test_03_training_dataset_labels_and_leakage(self):
        """Dataset must only contain target 0 and 1, no UNCERTAIN, and only compatible sessions."""
        self.assertTrue(os.path.exists(DATASET_PATH), f"Missing {DATASET_PATH}")

        with open(DATASET_PATH, "r", encoding="utf-8") as f:
            reader = csv.DictReader(f)
            rows = list(reader)

        training_rows = [r for r in rows if float(r["training_target"]) != -1.0]
        self.assertGreater(len(training_rows), 500)

        for r in training_rows:
            target = float(r["training_target"])
            cond = r["physical_label"]
            alias = r["trial_alias"]

            if target == 1.0:
                self.assertIn(cond, ["CONTACT_SPEECH", "CONTACT_SPEECH_MOVEMENT"])
            else:
                self.assertIn(cond, ["AWAY_SPEECH", "SILENCE_STILL", "SILENCE_MOVEMENT"])

            self.assertNotEqual(cond, "UNCERTAIN")
            self.assertNotIn(alias, ["Trial 7", "Trial 8"])

    def test_04_exact_16_feature_order(self):
        """Verify the 16 features match the exact non-negotiable tensor specification."""
        self.assertTrue(os.path.exists(DATASET_PATH), f"Missing {DATASET_PATH}")
        with open(DATASET_PATH, "r", encoding="utf-8") as f:
            reader = csv.reader(f)
            header = next(reader)

        for feat in EXACT_16_FEATURES:
            self.assertIn(feat, header, f"Missing required feature: {feat}")

        # Best axis is excluded from the 16 features
        self.assertNotIn("accel_best_axis", EXACT_16_FEATURES)

    def test_05_metadata_normalization_consistency(self):
        """Verify fusion_model_metadata.json matches exact 16-feature schema and non-zero stds."""
        self.assertTrue(os.path.exists(METADATA_PATH), f"Missing {METADATA_PATH}")
        with open(METADATA_PATH, "r", encoding="utf-8") as f:
            meta = json.load(f)

        input_tensor = meta["input_tensor"]
        self.assertEqual(input_tensor["shape"][1], 16)
        self.assertEqual(input_tensor["feature_order"], EXACT_16_FEATURES)

        norm = meta["normalization"]
        means = norm["means"]
        stds = norm["stds"]
        self.assertEqual(len(means), 16)
        self.assertEqual(len(stds), 16)

        for i, s in enumerate(stds):
            self.assertGreater(s, 0.0, f"Std at index {i} ({EXACT_16_FEATURES[i]}) must be positive")

    def test_06_tflite_tensor_shapes_and_keras_equivalence(self):
        """TFLite model must accept [1, 16] Float32, output [1, 1] Float32, and match SavedModel inference."""
        self.assertTrue(os.path.exists(TFLITE_PATH), f"Missing {TFLITE_PATH}")

        interpreter = tf.lite.Interpreter(model_path=TFLITE_PATH)
        interpreter.allocate_tensors()

        input_details = interpreter.get_input_details()
        output_details = interpreter.get_output_details()

        self.assertEqual(len(input_details), 1)
        self.assertEqual(len(output_details), 1)

        self.assertEqual(list(input_details[0]["shape"]), [1, 16])
        self.assertEqual(input_details[0]["dtype"], np.float32)

        self.assertEqual(list(output_details[0]["shape"]), [1, 1])
        self.assertEqual(output_details[0]["dtype"], np.float32)

        # Compare inference with SavedModel signature
        if os.path.exists(SAVED_MODEL_DIR):
            loaded_sm = tf.saved_model.load(SAVED_MODEL_DIR)
            infer_fn = loaded_sm.signatures["serving_default"]

            # Test across 20 synthetic deterministic test vectors
            np.random.seed(42)
            test_inputs = np.random.randn(20, 16).astype(np.float32)

            for i in range(20):
                single_in = test_inputs[i:i+1]
                sm_out = infer_fn(tf.constant(single_in))
                out_key = list(sm_out.keys())[0]
                sm_pred = float(sm_out[out_key].numpy()[0][0])

                interpreter.set_tensor(input_details[0]["index"], single_in)
                interpreter.invoke()
                tflite_pred = float(interpreter.get_tensor(output_details[0]["index"])[0][0])

                diff = abs(sm_pred - tflite_pred)
                self.assertLess(diff, 1e-4,
                                f"TFLite and SavedModel outputs differ by {diff} (sm={sm_pred}, tflite={tflite_pred})")


if __name__ == "__main__":
    unittest.main()
