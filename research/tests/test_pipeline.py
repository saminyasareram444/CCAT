"""Meaningful data-leakage, censoring, provenance and numerical checks; no simulator required."""
import csv
import importlib.util
import json
import math
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("research_pipeline", Path(__file__).resolve().parents[1] / "tools/research_pipeline.py")
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)


def example(flight="a", group="g", value=0):
    x = [10 + value, (10 + value) ** 2, value + 20, -1, 2, .5, 0, value]
    return dict(flight=flight, group=group, x=x, distance_m=2 * value + 5, time_s=value + 1)


class PipelineTests(unittest.TestCase):
    def test_whole_scenario_split_keeps_repeats_together(self):
        rows = [example(flight, group, i) for flight, group in (("a", "g1"), ("b", "g1"), ("c", "g2"), ("d", "g3")) for i in range(3)]
        mapping, parts = p.grouped_split(rows)
        self.assertEqual(mapping["g1"], "train")
        self.assertEqual({r["flight"] for r in parts["train"]}, {"a", "b"})
        sets = [{r["group"] for r in part} for part in parts.values()]
        self.assertTrue(all(not a.intersection(b) for i, a in enumerate(sets) for b in sets[i + 1:]))

    def test_insufficient_scenarios_refuse_row_split(self):
        with self.assertRaisesRegex(ValueError, "three distinct"):
            p.grouped_split([example() for _ in range(200)])

    def test_frozen_split_rejects_new_groups(self):
        with self.assertRaisesRegex(ValueError, "groups changed"):
            p.grouped_split([example(group=g) for g in ("a", "b", "c")], {"groups": {"a": "train", "b": "validation", "d": "test"}})

    def test_fit_statistics_use_training_only(self):
        train = [example(value=i) for i in range(12)]
        model = p.fit(train, .001)
        self.assertAlmostEqual(model["means"][0], 15.5)
        before = json.dumps(model, sort_keys=True)
        held_out = example("held-out", "other", 100000)
        p.predict(model, held_out["x"])
        self.assertEqual(before, json.dumps(model, sort_keys=True))
        self.assertTrue(p.outside_support(model, held_out["x"]))

    def test_target_changes_do_not_change_runtime_features(self):
        row = example(value=2)
        model = p.fit([example(value=i) for i in range(12)], .001)
        expected = p.predict(model, row["x"])
        row["distance_m"], row["time_s"] = 1e9, 1e9
        self.assertEqual(expected, p.predict(model, row["x"]))

    def test_regression_learns_held_out_linear_relation(self):
        model = p.fit([example(value=i) for i in range(12)], .00001)
        pred = p.predict(model, example(value=5.5)["x"])
        self.assertLess(abs(pred[0] - 16), .1)
        self.assertLess(abs(pred[1] - 6.5), .1)

    def test_solver_pivoting(self):
        x = p.solve([[0, 2], [1, 3]], [4, 7])
        self.assertAlmostEqual(x[0], 1)
        self.assertAlmostEqual(x[1], 2)

    def test_missing_features_refused(self):
        model = p.fit([example(value=i) for i in range(12)], .1)
        x = example()["x"]
        x[3] = math.nan
        with self.assertRaises(ValueError): p.predict(model, x)

    def test_repeat_flights_do_not_overweight_scenario(self):
        rows = [example("a", "g1"), example("b", "g1"), example("c", "g2")]
        self.assertEqual(p.row_weights(rows), [.25, .25, .5])

    def test_current_policy_requires_gate_and_terms(self):
        self.assertEqual(p.historical_policy({"terms": {"brakingDistance": 3000}}), "legacy-or-unidentified")
        data = {"terms": {k: 0 for k in ("approachSpeedTarget", "approachAlongRemaining", "approachHeightHold")}, "gate": "Ground speed < 10 m/s and IAS < 30 kt enables rotor capture."}
        self.assertEqual(p.historical_policy(data), p.POLICY)

    def test_journal_counts_inactive_trace_and_malformed_lines(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "xai-20261022-000000-000.jsonl"
            path.write_text(json.dumps(dict(type="sample", phase="STOPPED", computed_phase="APPROACH", aircraft_values={"sim/time/paused": 1})) + "\n{bad\n")
            result = p.audit_journal(path)
            self.assertEqual(result["inactive_or_paused_with_retained_trace"], 1)
            self.assertEqual(result["malformed_records"], 1)
            self.assertFalse(result["policy_counts"])

    def test_journal_association_rejects_different_origin(self):
        path = Path("20261022-000100-000.csv")
        journal = dict(file="xai-20261022-000000-000.jsonl", first_approach=dict(origin_lat=10, origin_lon=20))
        self.assertIsNone(p.associate_journal(path, [dict(latitude_deg="11", longitude_deg="20")], [journal]))

    def test_label_path_and_transition_row(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "fixture.csv"
            fields = ["elapsed_s", "phase", "north_m", "east_m", "vnorth_mps", "veast_mps", "vup_mps", "pitch_deg", "lift_command", "pusher_command", "airspeed_kt"]
            with path.open("w", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=fields); writer.writeheader()
                for i in range(5):
                    writer.writerow(dict(elapsed_s=i, phase="APPROACH" if i < 4 else "HOVER_CAPTURE", north_m=i * 3, east_m=i * 4, vnorth_mps=0, veast_mps=15 if i < 4 else 9, vup_mps=0, pitch_deg=2, lift_command=.5, pusher_command=0, airspeed_kt=20))
            rows = p.extract_examples(path, dict(eligible_for_pilot_braking_fit=True, scenario_group="g"), spacing=.1)
            self.assertEqual(len(rows), 3)
            self.assertEqual(rows[0]["elapsed_s"], 1)
            self.assertEqual(rows[0]["distance_m"], 15)
            self.assertEqual(rows[0]["time_s"], 3)
            self.assertEqual(rows[0]["x"][-1], 1)

    def test_incomplete_maneuver_is_not_given_fake_label(self):
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(p.extract_examples(Path(directory) / "nonexistent.csv", dict(eligible_for_pilot_braking_fit=False)), [])

    def test_audit_reports_corrupt_numeric_rows_without_fitting(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "20261022-000100-000.csv"
            path.write_text("elapsed_s,phase,north_m,east_m,vnorth_mps,veast_mps,airspeed_kt,battery_power_kw,battery_percent\nNaN,APPROACH,0,0,NaN,0,30,-200,NaN\n0.1,HOVER_CAPTURE,1,0,NaN,0,20,-200,90\n0.2,LANDED,1,0,0,0,20,-200,90\n")
            result = p.audit_flight(path, [])
            self.assertFalse(result["eligible_for_pilot_braking_fit"])
            self.assertGreater(result["invalid_values"]["vnorth_mps"], 0)
            self.assertIsNone(result["battery"])
            json.dumps(result, allow_nan=False)

    def test_malformed_sidecar_is_explicitly_excluded(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "20261022-000100-000.csv"
            path.write_text("elapsed_s,phase\n0.1,TAKEOFF\n")
            path.with_suffix(".metadata.json").write_text("{bad")
            result = p.audit_flight(path, [])
            self.assertIn("malformed provenance sidecar requires review", result["exclusion_reasons"])

    def test_collect_preserves_existing_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "logs/alia").mkdir(parents=True)
            (root / "logs/alia/a.csv").write_text("original")
            p.collect(root)
            p.collect(root)
            (root / "logs/alia/a.csv").write_text("changed")
            with self.assertRaisesRegex(ValueError, "collision"): p.collect(root)
            self.assertEqual((root / "research/local/data/alia/a.csv").read_text(), "original")


if __name__ == "__main__": unittest.main()
