"""Offline ALIA research pipeline. Python standard library only; never connects to X-Plane.

Commands: audit, train, replay, all. Artifacts and raw records remain under research/local/.
The small historical dataset supports a pilot experiment, not calibrated safety claims.
"""
from __future__ import annotations

import argparse
import bisect
import collections
import csv
import datetime as dt
import hashlib
import json
import math
from pathlib import Path
import statistics
import shutil

SCHEMA = 1
POLICY = "alia-airport-brake-hold-v1"
FEATURES = ("ground_speed_mps", "ground_speed_squared", "forward_ias_kt",
            "vertical_speed_mps", "pitch_deg", "lift_command", "pusher_command", "approach_age_s")
REQUIRED = ("elapsed_s", "phase", "north_m", "east_m", "vnorth_mps", "veast_mps",
            "vup_mps", "pitch_deg", "lift_command", "pusher_command", "airspeed_kt")
FEATURE_PADDING = (0.5, 25.0, 1.0, 0.2, 0.5, 0.02, 0.02, 0.5)


def number(value):
    try:
        result = float(value)
        return result if math.isfinite(result) else None
    except (TypeError, ValueError):
        return None


def dump(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=True, allow_nan=False) + "\n", encoding="utf-8")


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_lines(path):
    with path.open(encoding="utf-8-sig") as stream:
        yield from stream


def csv_rows(path):
    with path.open(encoding="utf-8-sig", newline="") as stream:
        reader = csv.DictReader(stream)
        fields = reader.fieldnames or []
        rows = list(reader)
    return fields, rows


def stamp(path):
    try:
        return dt.datetime.strptime(path.stem.removeprefix("xai-"), "%Y%m%d-%H%M%S-%f")
    except ValueError:
        return None


def speed(row):
    return math.hypot(float(row["vnorth_mps"]), float(row["veast_mps"]))


def historical_policy(record):
    terms = record.get("terms", {})
    gate = record.get("gate", "")
    if all(k in terms for k in ("approachSpeedTarget", "approachAlongRemaining", "approachHeightHold")) and "Ground speed < 10 m/s and IAS < 30 kt" in gate:
        return POLICY
    return "legacy-or-unidentified"


def audit_journal(path):
    counts = collections.Counter()
    phases = collections.Counter()
    policy_counts = collections.Counter()
    first_approach = None
    events = []
    keysets = set()
    nonfinite_values = collections.Counter()
    stale_trace = paused = malformed = 0
    first_time = last_time = None
    for line_number, line in enumerate(read_lines(path), 1):
        try:
            data = json.loads(line)
            if not isinstance(data, dict):
                raise ValueError("record must be an object")
        except (json.JSONDecodeError, ValueError):
            malformed += 1
            continue
        counts[data.get("type", "unknown")] += 1
        if data.get("type") == "event":
            if any(word in (data.get("title", "") + " " + data.get("detail", "")).lower()
                   for word in ("fault", "takeover", "take over", "paused", "landed")):
                events.append({k: data.get(k) for k in ("time", "level", "title", "detail")})
        if data.get("type") != "sample":
            continue
        phases[data.get("phase", "unknown")] += 1
        first_time = first_time or data.get("time")
        last_time = data.get("time")
        refs = data.get("aircraft_values", {})
        keysets.update(refs)
        nonfinite_values.update(k for k, v in refs.items() if number(v) is None)
        is_paused = refs.get("sim/time/paused") == 1
        paused += int(is_paused)
        if data.get("computed_phase") and (is_paused or data.get("phase") in ("STOPPED", "LANDED", "IDLE")):
            stale_trace += 1
        if data.get("computed_phase") == "APPROACH" and data.get("phase") == "APPROACH" and not is_paused:
            policy_counts[historical_policy(data)] += 1
            if first_approach is None:
                first_approach = {k: data.get(k) for k in
                    ("time", "destination", "origin_lat", "origin_lon", "origin_elevation_m",
                     "target_north_m", "target_east_m", "gate", "terms")}
                first_approach["battery_capacity_wh_per_pack"] = refs.get("sim/aircraft/electrical/battery_watt_hr_max")
    return dict(file=path.name, sha256=digest(path), bytes=path.stat().st_size,
                counts=dict(counts), phase_counts=dict(phases), malformed_records=malformed,
                first_time=first_time, last_time=last_time, paused_samples=paused,
                inactive_or_paused_with_retained_trace=stale_trace, aircraft_keys=sorted(keysets),
                missing_or_nonfinite_aircraft_values=dict(nonfinite_values),
                policy_counts=dict(policy_counts), first_approach=first_approach, events=events)


def associate_journal(path, rows, journals):
    """Historical association is evidence, not recorded run identity. Refuse mismatched origins."""
    if not rows or stamp(path) is None:
        return None
    candidates = [j for j in journals if stamp(Path(j["file"])) is not None
                  and 0 <= (stamp(path) - stamp(Path(j["file"]))).total_seconds() <= 3600]
    if not candidates:
        return None
    candidate = max(candidates, key=lambda j: stamp(Path(j["file"])))
    approach = candidate.get("first_approach")
    if approach:
        try:
            if abs(float(rows[0]["latitude_deg"]) - approach["origin_lat"]) > 0.0001 or abs(float(rows[0]["longitude_deg"]) - approach["origin_lon"]) > 0.0001:
                return None
        except (TypeError, ValueError, KeyError):
            return None
    return candidate


def audit_flight(path, journals):
    fields, rows = csv_rows(path)
    phases = []
    invalid = collections.Counter()
    malformed = 0
    deltas = []
    positions_distance = velocity_distance = 0.0
    previous = None
    for row in rows:
        malformed += int(None in row or any(value is None for value in row.values()))
        invalid.update(k for k in fields if k != "phase" and number(row.get(k)) is None)
        if not phases or phases[-1]["phase"] != row.get("phase"):
            phases.append(dict(phase=row.get("phase"), elapsed_s=number(row.get("elapsed_s"))))
        if previous:
            try:
                delta = float(row["elapsed_s"]) - float(previous["elapsed_s"])
                if math.isfinite(delta):
                    deltas.append(delta)
                if math.isfinite(delta) and 0 < delta <= .25:
                    if any(number(r.get(k)) is None for r in (row, previous) for k in ("north_m", "east_m", "vnorth_mps", "veast_mps")):
                        raise ValueError("invalid motion sample")
                    positions_distance += math.hypot(float(row["north_m"]) - float(previous["north_m"]), float(row["east_m"]) - float(previous["east_m"]))
                    velocity_distance += (speed(row) + speed(previous)) * .5 * delta
            except (KeyError, TypeError, ValueError):
                pass
        previous = row
    journal = associate_journal(path, rows, journals)
    approach = (journal or {}).get("first_approach") or {}
    current_policy = bool(journal and journal["policy_counts"].get(POLICY) and not journal["policy_counts"].get("legacy-or-unidentified"))
    policy = POLICY if current_policy else "legacy-or-unidentified"
    completed = bool(rows and rows[-1].get("phase") == "LANDED")
    has_approach = any(p["phase"] == "APPROACH" for p in phases)
    capture = next((row for row in rows if row.get("phase") == "HOVER_CAPTURE"), None)
    first_approach = next((row for row in rows if row.get("phase") == "APPROACH"), None)
    target = None
    if current_policy:
        target = [approach.get("target_north_m"), approach.get("target_east_m")]
    grouping = None
    if rows and target and all(number(v) is not None for v in target):
        grouping = [round(float(rows[0]["latitude_deg"]), 3), round(float(rows[0]["longitude_deg"]), 3),
                    round(float(approach["origin_lat"]) + math.degrees(target[0] / 6371000), 3),
                    round(float(approach["origin_lon"]) + math.degrees(target[1] / (6371000 * math.cos(math.radians(float(approach["origin_lat"]))))), 3),
                    policy, approach.get("terms", {}).get("targetAirspeedKnots")]
    group = hashlib.sha256(json.dumps(grouping).encode()).hexdigest()[:12] if grouping else None
    reasons = []
    if not completed: reasons.append("ends_before_reported_landing; outcome/intervention requires review")
    if not current_policy: reasons.append("no matching current braking-policy trace")
    if not has_approach or capture is None: reasons.append("no complete approach-to-capture segment")
    if any(k not in fields or invalid[k] for k in REQUIRED if k != "phase"): reasons.append("missing/nonfinite required values")
    if malformed: reasons.append("malformed CSV rows")
    if any(d <= 0 or d > .25 for d in deltas): reasons.append("nonmonotonic time or data gap above 0.25 s")
    if capture and (any(number(capture.get(k)) is None for k in ("vnorth_mps", "veast_mps", "airspeed_kt")) or speed(capture) >= 10 or max(0, float(capture["airspeed_kt"])) >= 30): reasons.append("capture row does not satisfy current capture gate")
    energy = None
    if rows and all(number(row.get(k)) is not None for row in rows for k in ("battery_power_kw", "battery_percent", "elapsed_s")):
        signed_kwh = sum(.5 * (float(a["battery_power_kw"]) + float(b["battery_power_kw"])) * (float(b["elapsed_s"]) - float(a["elapsed_s"])) / 3600 for a, b in zip(rows, rows[1:]) if 0 < float(b["elapsed_s"]) - float(a["elapsed_s"]) <= .25)
        delta_pct = float(rows[0]["battery_percent"]) - float(rows[-1]["battery_percent"])
        capacity = number(approach.get("battery_capacity_wh_per_pack"))
        charge_kwh = delta_pct / 100 * capacity * 5 / 1000 if capacity else None
        energy = dict(signed_vi_integral_kwh=signed_kwh, charge_drop_percentage_points=delta_pct,
                      charge_drop_kwh=charge_kwh, depletion_sign_evidence="negative VI with declining charge" if signed_kwh < 0 and delta_pct > 0 else "inconclusive")
    outcomes = {}
    if first_approach and capture and number(capture.get("elapsed_s")) is not None and number(first_approach.get("elapsed_s")) is not None:
        outcomes["approach_to_capture_s"] = float(capture["elapsed_s"]) - float(first_approach["elapsed_s"])
        outcomes["capture_distance_to_destination_m"] = number(capture.get("distance_m"))
    if completed:
        outcomes["reported_touchdown_error_m"] = number(rows[-1].get("distance_m"))
    if target and first_approach and all(number(r.get(k)) is not None for r in rows for k in ("north_m", "east_m")):
        dx, dy = target[0] - float(first_approach["north_m"]), target[1] - float(first_approach["east_m"])
        norm = math.hypot(dx, dy)
        if norm > 1:
            outcomes["maximum_along_track_overshoot_m"] = max(0, max(-((target[0] - float(r["north_m"])) * dx + (target[1] - float(r["east_m"])) * dy) / norm for r in rows if r["phase"] in ("APPROACH", "HOVER_CAPTURE", "DESCEND", "LANDED")))
    sidecar = path.with_suffix(".metadata.json")
    recorded_metadata = None
    if sidecar.exists():
        try:
            recorded_metadata = json.loads(sidecar.read_text(encoding="utf-8-sig"))
            if not isinstance(recorded_metadata, dict): raise ValueError("metadata must be an object")
        except (json.JSONDecodeError, ValueError):
            reasons.append("malformed provenance sidecar requires review")
            recorded_metadata = None
    return dict(file=path.name, sha256=digest(path), bytes=path.stat().st_size, rows=len(rows), fields=fields,
                phases=phases, reported_outcome="LANDED" if completed else "INCOMPLETE_REVIEW_REQUIRED",
                duration_s=number(rows[-1].get("elapsed_s")) if rows else 0,
                invalid_values=dict(invalid), malformed_rows=malformed,
                nonpositive_time_steps=sum(d <= 0 for d in deltas), gaps_over_250ms=sum(d > .25 for d in deltas),
                median_step_s=statistics.median(deltas) if deltas else None,
                position_vs_velocity_path_ratio=positions_distance / velocity_distance if velocity_distance > 1 else None,
                journal=(journal or {}).get("file"), journal_association="filename window and origin; historical inference" if journal else None,
                recorded_run_metadata=recorded_metadata, controller_commit_recorded=bool(recorded_metadata and recorded_metadata.get("controller_commit") not in (None, "", "UNKNOWN")),
                policy=policy, policy_evidence="matching trace fields and gate, not proof of identical controller build" if current_policy else "unknown or legacy",
                scenario_group=group, scenario_group_key=grouping, target_local_m=target,
                eligible_for_pilot_braking_fit=not reasons, exclusion_reasons=reasons,
                battery=energy, outcomes=outcomes)


def audit(root):
    data = root / "research/local/data/alia"
    journals = [audit_journal(path) for path in sorted(data.glob("*.jsonl"))]
    flights = [audit_flight(path, journals) for path in sorted(data.glob("*.csv"))]
    eligible = [f for f in flights if f["eligible_for_pilot_braking_fit"]]
    summary = dict(schema_version=SCHEMA, csv_files=len(flights), journal_files=len(journals),
                   csv_rows=sum(f["rows"] for f in flights), journal_samples=sum(j["counts"].get("sample", 0) for j in journals),
                   reported_completed_flights=sum(f["reported_outcome"] == "LANDED" for f in flights),
                   complete_approach_flights=sum(bool(f["outcomes"].get("approach_to_capture_s")) for f in flights),
                   pilot_fit_flights=len(eligible), pilot_fit_scenario_groups=len({f["scenario_group"] for f in eligible}),
                   learned_control_authorized=False, readiness="PILOT_DATA_ONLY", flights=flights, journals=journals,
                   limitations=["Historical files do not record controller commit, simulator version, weather preset or pilot identity.",
                       "Scenario grouping uses rounded origin/destination and trace policy; repeated route trials stay together.",
                       "Three scenario groups cannot establish calibrated uncertainty or broad operational generalization.",
                       "Historical elapsed_s is capped wall/controller time, not an independently logged simulator clock.",
                       "A reported LANDED phase is not independent confirmation of landing-surface suitability.",
                       "Journal files include inactive/paused retained traces and must not be treated as independent flights."])
    out = root / "research/local/audit"
    dump(out / "audit.json", summary)
    text = ["# ALIA data-readiness audit", "", "Generated by `run-research.cmd audit`. Raw logs are unchanged.", "",
            f"- Flight CSVs: {len(flights)}; dashboard journals: {len(journals)}.",
            f"- Reported landed flights: {summary['reported_completed_flights']}; complete approach segments: {summary['complete_approach_flights']}.",
            f"- Current-policy pilot-fit flights: {len(eligible)} across **{summary['pilot_fit_scenario_groups']} route/scenario groups**.",
            "- Readiness: **pilot experiment only; no qualified learned controller or calibrated uncertainty**.", "",
            "## Flight inventory", "", "| Flight | Rows | End | Policy | Fit/group | Review |", "|---|---:|---|---|---|---|"]
    for f in flights:
        text.append(f"| {f['file']} | {f['rows']} | {f['reported_outcome']} | {f['policy']} | {f['scenario_group'] or 'excluded'} | {'; '.join(f['exclusion_reasons']) or 'historical provenance incomplete'} |")
    text += ["", "## Battery evidence", "", "The signed voltage-current integral is retained. In flights with declining measured charge, negative VI accompanies depletion. This is an empirical convention for these logs, not an assumption about every simulator model.", "",
             "| Flight | Charge drop (pp) | Signed VI (kWh) | Capacity-based drop (kWh) |", "|---|---:|---:|---:|"]
    for f in flights:
        b = f["battery"]
        if b:
            text.append(f"| {f['file']} | {b['charge_drop_percentage_points']:.1f} | {b['signed_vi_integral_kwh']:.3f} | {b['charge_drop_kwh'] if b['charge_drop_kwh'] is not None else 'unavailable'} |")
    text += ["", "## Readiness limitations", ""] + ["- " + item for item in summary["limitations"]]
    text += ["", "## Required new data", "", "Collect distinct initial speeds, route directions, winds, heights and braking start distances under a pinned controller and explicit scenario manifest. Preserve failures/interventions as censored outcomes. Obtain enough independent scenarios before estimating interval coverage. See research/experiments/approach-baseline.md.", ""]
    (out / "data-readiness.md").write_text("\n".join(text), encoding="utf-8")
    print(f"AUDIT: {len(flights)} CSV + {len(journals)} journals; {len(eligible)} eligible flights / {summary['pilot_fit_scenario_groups']} scenario groups. PILOT_DATA_ONLY")
    return summary


def extract_examples(path, flight, spacing=2.0):
    if not flight["eligible_for_pilot_braking_fit"]:
        return []
    _, rows = csv_rows(path)
    start = next(i for i, row in enumerate(rows) if row["phase"] == "APPROACH")
    end = next(i for i in range(start + 1, len(rows)) if rows[i]["phase"] == "HOVER_CAPTURE")
    # Output phase changes before the next control calculation. Skip the CRUISE->APPROACH row.
    start += 1
    if start >= end:
        return []
    clock_origin = float(rows[start - 1]["elapsed_s"])
    remaining = [0.0] * (end + 1)
    for i in range(end - 1, start - 1, -1):
        remaining[i] = remaining[i + 1] + math.hypot(float(rows[i + 1]["north_m"]) - float(rows[i]["north_m"]), float(rows[i + 1]["east_m"]) - float(rows[i]["east_m"]))
    result = []
    last_time = -math.inf
    for i in range(start, end):
        row = rows[i]
        now = float(row["elapsed_s"])
        if now - last_time < spacing:
            continue
        if row["phase"] != "APPROACH":
            raise ValueError("Noncontiguous approach")
        gs = speed(row)
        x = [gs, gs * gs, max(0, float(row["airspeed_kt"])), float(row["vup_mps"]), float(row["pitch_deg"]), float(row["lift_command"]), float(row["pusher_command"]), now - clock_origin]
        result.append(dict(flight=path.name, group=flight["scenario_group"], elapsed_s=now,
                           x=x, distance_m=remaining[i], time_s=float(rows[end]["elapsed_s"]) - now,
                           capture_elapsed_s=float(rows[end]["elapsed_s"]),
                           kinematic_distance_m=max(0, gs * gs - 100) / .7,
                           kinematic_time_s=max(0, gs - 10) / .35,
                           controller_gate_m=max(2200, gs * gs / .7 + gs * 8 + 500)))
        last_time = now
    return result


def grouped_split(examples, frozen=None):
    groups = sorted({row["group"] for row in examples}, key=lambda g: min(row["flight"] for row in examples if row["group"] == g))
    if len(groups) < 3:
        raise ValueError("Need at least three distinct scenario groups for train/validation/test; refusing a row split")
    if frozen:
        mapping = frozen["groups"]
        if set(groups) != set(mapping):
            raise ValueError("Dataset groups changed. Create a new versioned split after review; do not silently change the holdout")
    else:
        # Freeze chronological scenario groups. A repeated route stays in its original split.
        mapping = {g: "train" for g in groups[:-2]}
        mapping[groups[-2]] = "validation"
        mapping[groups[-1]] = "test"
    partitions = {name: [r for r in examples if mapping[r["group"]] == name] for name in ("train", "validation", "test")}
    if any(not rows for rows in partitions.values()):
        raise ValueError("Empty partition")
    return mapping, partitions


def solve(matrix, rhs):
    a = [list(row) + [value] for row, value in zip(matrix, rhs)]
    n = len(rhs)
    for col in range(n):
        pivot = max(range(col, n), key=lambda row: abs(a[row][col]))
        a[col], a[pivot] = a[pivot], a[col]
        if abs(a[col][col]) < 1e-12:
            raise ValueError("Singular fit")
        divisor = a[col][col]
        a[col] = [value / divisor for value in a[col]]
        for row in range(n):
            if row != col:
                multiplier = a[row][col]
                a[row] = [v - multiplier * w for v, w in zip(a[row], a[col])]
    return [row[-1] for row in a]


def row_weights(rows):
    by_flight = collections.Counter(r["flight"] for r in rows)
    by_group = collections.defaultdict(set)
    for row in rows: by_group[row["group"]].add(row["flight"])
    return [1 / (len(by_group) * len(by_group[r["group"]]) * by_flight[r["flight"]]) for r in rows]


def fit(rows, ridge):
    weights = row_weights(rows)
    means = [sum(w * r["x"][i] for w, r in zip(weights, rows)) for i in range(len(FEATURES))]
    scales = [max(1e-8, math.sqrt(sum(w * (r["x"][i] - means[i]) ** 2 for w, r in zip(weights, rows)))) for i in range(len(FEATURES))]
    design = [[1.0] + [(value - mean) / scale for value, mean, scale in zip(r["x"], means, scales)] for r in rows]
    n = len(FEATURES) + 1
    matrix = [[sum(w * x[i] * x[j] for w, x in zip(weights, design)) + (ridge if i == j and i > 0 else 0) for j in range(n)] for i in range(n)]
    model = dict(schema_version=SCHEMA, policy=POLICY, qualification="PILOT_ONLY", features=list(FEATURES), means=means, scales=scales, ridge=ridge,
                 minimum=[min(r["x"][i] for r in rows) for i in range(len(FEATURES))],
                 maximum=[max(r["x"][i] for r in rows) for i in range(len(FEATURES))], padding=list(FEATURE_PADDING),
                 target="remaining ground path and controller seconds to GS <10 m/s and forward IAS <30 kt under unchanged approach procedure")
    for target in ("distance_m", "time_s"):
        rhs = [sum(w * x[i] * r[target] for w, x, r in zip(weights, design, rows)) for i in range(n)]
        model[target + "_coefficients"] = solve(matrix, rhs)
    return model


def predict(model, x):
    if len(x) != len(FEATURES) or any(number(v) is None for v in x):
        raise ValueError("Missing or nonfinite model feature")
    z = [1] + [(v - m) / s for v, m, s in zip(x, model["means"], model["scales"])]
    return [max(0, sum(a * b for a, b in zip(z, model[key + "_coefficients"]))) for key in ("distance_m", "time_s")]


def outside_support(model, x):
    return [name for name, v, low, high, padding in zip(FEATURES, x, model["minimum"], model["maximum"], model["padding"]) if v < low - padding or v > high + padding]


def metrics(rows, predictions):
    weights = row_weights(rows)
    result = dict(rows=len(rows), flights=len({r["flight"] for r in rows}), groups=len({r["group"] for r in rows}))
    for i, target in enumerate(("distance_m", "time_s")):
        errors = [pred[i] - row[target] for row, pred in zip(rows, predictions)]
        result[target + "_mae"] = sum(w * abs(e) for w, e in zip(weights, errors))
        result[target + "_rmse"] = math.sqrt(sum(w * e * e for w, e in zip(weights, errors)))
        result[target + "_bias"] = sum(w * e for w, e in zip(weights, errors))
        result[target + "_max_abs_error"] = max(map(abs, errors))
    return result


def quantile(values, fraction):
    values = sorted(values)
    return values[max(0, min(len(values) - 1, math.ceil(len(values) * fraction) - 1))]


def train(root, summary=None):
    summary = summary or json.loads((root / "research/local/audit/audit.json").read_text())
    for journal in summary["journals"]:
        if digest(root / "research/local/data/alia" / journal["file"]) != journal["sha256"]:
            raise ValueError("Journal changed since audit; run audit again before training")
    examples = []
    for flight in summary["flights"]:
        if digest(root / "research/local/data/alia" / flight["file"]) != flight["sha256"]:
            raise ValueError("Raw CSV changed since audit; run audit again before training")
        examples += extract_examples(root / "research/local/data/alia" / flight["file"], flight)
    split_path = root / "research/config/braking-split-v1.json"
    frozen = json.loads(split_path.read_text()) if split_path.exists() else None
    mapping, parts = grouped_split(examples, frozen)
    split = dict(schema_version=SCHEMA, split_id="braking-scenario-v1", method="frozen chronological route/scenario groups", groups=mapping,
                 flights={name: sorted({r["flight"] for r in rows}) for name, rows in parts.items()},
                 warning="Only three independent historical scenario groups; pilot experiment, not a generalization claim.")
    dump(root / "research/local/datasets/braking-split-v1.json", split)
    if frozen and frozen.get("flights") != split["flights"]:
        raise ValueError("Flights in frozen split changed; explicitly version the dataset before retraining")
    candidates = []
    for ridge in (.001, .01, .1, 1.0, 10.0):
        model = fit(parts["train"], ridge)
        score = metrics(parts["validation"], [predict(model, r["x"]) for r in parts["validation"]])
        candidates.append((score["distance_m_rmse"] / 1000 + score["time_s_rmse"] / 60, model, score))
    _, model, _ = min(candidates, key=lambda item: item[0])
    residuals = [[abs(p - row[target]) for row in parts["validation"] for p in [predict(model, row["x"])[i]]] for i, target in enumerate(("distance_m", "time_s"))]
    model["validation_residual_band_m"] = quantile(residuals[0], .9)
    model["validation_residual_band_s"] = quantile(residuals[1], .9)
    model["band_interpretation"] = "empirical 90th percentile absolute residual on one validation scenario; NOT a calibrated 90% prediction interval"
    model["train_flights"] = split["flights"]["train"]
    model["validation_flights"] = split["flights"]["validation"]
    model["test_flights"] = split["flights"]["test"]
    model["source_hashes"] = {f["file"]: f["sha256"] for f in summary["flights"] if f["eligible_for_pilot_braking_fit"]}
    model["training_scenario_groups"] = len({r["group"] for r in parts["train"]})
    model["flight_control_authorized"] = False
    model["uncertainty_qualified"] = False
    model["model_id"] = hashlib.sha256(json.dumps(model, sort_keys=True).encode()).hexdigest()[:16]
    evaluation = dict(schema_version=SCHEMA, model_id=model["model_id"], qualification="PILOT_ONLY", splits=split, candidates=[dict(ridge=m["ridge"], validation=s) for _, m, s in candidates], partitions={})
    clock_duration = sum(w * (row["time_s"] + row["x"][-1]) for w, row in zip(row_weights(parts["train"]), parts["train"]))
    evaluation["phase_clock_comparator"] = dict(training_mean_capture_duration_s=clock_duration,
        definition="max(0, mean training approach duration - current approach age); post-hoc pilot diagnostic, not used to select the learned model")
    for name, rows in parts.items():
        preds = [predict(model, row["x"]) for row in rows]
        learned = metrics(rows, preds)
        baseline = metrics(rows, [[r["kinematic_distance_m"], r["kinematic_time_s"]] for r in rows])
        support = [not outside_support(model, r["x"]) for r in rows]
        learned["outside_training_support_rows"] = support.count(False)
        learned["empirical_distance_band_coverage"] = sum(w * (abs(pred[0] - r["distance_m"]) <= model["validation_residual_band_m"]) for w, r, pred in zip(row_weights(rows), rows, preds))
        learned["empirical_time_band_coverage"] = sum(w * (abs(pred[1] - r["time_s"]) <= model["validation_residual_band_s"]) for w, r, pred in zip(row_weights(rows), rows, preds))
        evaluation["partitions"][name] = dict(learned=learned, kinematic_comparator=baseline,
            phase_clock_time_mae_s=sum(w * abs(max(0, clock_duration - r["x"][-1]) - r["time_s"]) for w, r in zip(row_weights(rows), rows)),
            in_support_learned=metrics([r for r, ok in zip(rows, support) if ok], [p for p, ok in zip(preds, support) if ok]) if any(support) else None)
    dump(root / "research/local/models/braking.json", model)
    dump(root / "research/local/evaluation/braking-evaluation.json", evaluation)
    props = dict(schema_version=SCHEMA, policy=POLICY, qualification="PILOT_ONLY", model_id=model["model_id"], features=",".join(FEATURES), flight_control_authorized="false", uncertainty_qualified="false")
    for key in ("means", "scales", "minimum", "maximum", "padding", "distance_m_coefficients", "time_s_coefficients"):
        props[key] = ",".join(format(v, ".17g") for v in model[key])
    props["validation_residual_band_m"] = model["validation_residual_band_m"]
    props["validation_residual_band_s"] = model["validation_residual_band_s"]
    (root / "research/local/models/braking.properties").write_text("# Generated pilot model. No flight-control authority.\n" + "\n".join(f"{k}={v}" for k, v in props.items()) + "\n", encoding="ascii")
    dataset = root / "research/local/datasets/braking-examples.jsonl"
    dataset.parent.mkdir(parents=True, exist_ok=True)
    dataset.write_text("".join(json.dumps(dict(row, split=mapping[row["group"]]), allow_nan=False) + "\n" for row in examples), encoding="utf-8")
    with (root / "research/local/models/braking-parity.csv").open("w", encoding="ascii", newline="") as stream:
        writer = csv.writer(stream)
        writer.writerow(list(FEATURES) + ["predicted_distance_m", "predicted_time_s"])
        for row in examples:
            writer.writerow(row["x"] + predict(model, row["x"]))
    lines = ["# Pilot braking experiment", "", f"Model `{model['model_id']}`; status **PILOT_ONLY / NO CONTROL AUTHORITY**.", "",
             "Targets: remaining ground path and reported controller seconds to rotor capture (GS <10 m/s and forward IAS <30 kt), conditional on the unchanged current approach procedure. This does not predict a full stop, touchdown, arbitrary cruise braking, or an alternative maneuver.", "",
             "Only three route/scenario groups exist. Near-repeat flights stay together. Training uses one group; validation one; test one. The model and feature scaling are fit on training only. Ridge selection and residual bands use validation only. Test outcomes are used solely for final evaluation.", "",
             "## Results", "", "| Partition | Flights/groups | Learned distance MAE (m) | Kinematic distance MAE (m) | Learned time MAE (s) | Kinematic time MAE (s) | Phase-clock time MAE (s) | OOD rows |", "|---|---|---:|---:|---:|---:|---:|---:|"]
    for name, result in evaluation["partitions"].items():
        a, b = result["learned"], result["kinematic_comparator"]
        lines.append(f"| {name} | {a['flights']}/{a['groups']} | {a['distance_m_mae']:.2f} | {b['distance_m_mae']:.2f} | {a['time_s_mae']:.2f} | {b['time_s_mae']:.2f} | {result['phase_clock_time_mae_s']:.2f} | {a['outside_training_support_rows']}/{a['rows']} |")
    test = evaluation["partitions"]["test"]["learned"]
    evaluation["release_gate"] = "BLOCKED: insufficient independent scenarios; uncertainty is not qualified"
    lines += ["", "The kinematic comparator uses (v^2 - 10^2)/(2 * 0.35) metres and (v - 10)/0.35 seconds. It shares the existing controller's deceleration assumption but is not the entire controller trigger. The actual trigger max(2200, v^2/0.7 + 8v + 500) includes buffers and a floor; treating its difference from observed travel as a model error would conflate a trigger policy with a stopping prediction.", "",
              "The post-hoc phase-clock comparator uses mean training approach duration minus current approach age. It was added to check whether the time model mainly learns the repeated procedure's timing, and it is not used to choose model coefficients or hyperparameters. The learned time estimate does not demonstrate an advantage over this simple timing comparator on the pilot test group. These retrospective results are exploratory, not a preregistered confirmatory study.", "",
              f"Validation residual bands: +/- {model['validation_residual_band_m']:.2f} m, +/- {model['validation_residual_band_s']:.2f} s. These are empirical residual bands from one validation scenario, **not calibrated probabilities**. Evaluation JSON reports measured test coverage, OOD rate, bias and maximum error.", "",
              f"**Qualification failed:** on the test group, distance residual-band coverage is {test['empirical_distance_band_coverage']:.1%}, and time coverage is {test['empirical_time_band_coverage']:.1%}. Point predictions may be inspected experimentally, but these bands must not be displayed as trusted safety bounds. Being inside per-feature training ranges is not evidence of correct uncertainty.", "",
              "No flight-performance improvement is established by this offline prediction comparison. Replay uses observed execution of the original procedure. Fresh scenario collection and shadow validation are required before recommendations influence control.", ""]
    dump(root / "research/local/evaluation/braking-evaluation.json", evaluation)
    report = root / "research/local/evaluation/braking-results.md"
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text("\n".join(lines), encoding="utf-8")
    print(f"TRAIN: model {model['model_id']}, ridge={model['ridge']}; {len(examples)} examples. PILOT_ONLY, control disabled")
    return model, evaluation


def replay(root):
    model = json.loads((root / "research/local/models/braking.json").read_text())
    output = root / "research/local/replay/braking-shadow.jsonl"
    output.parent.mkdir(parents=True, exist_ok=True)
    counts = collections.Counter()
    with output.open("w", encoding="utf-8") as stream:
        for line in read_lines(root / "research/local/datasets/braking-examples.jsonl"):
            row = json.loads(line)
            pred = predict(model, row["x"])
            unsupported = outside_support(model, row["x"])
            status = "OUTSIDE_TRAINING_SUPPORT" if unsupported else "PILOT_SHADOW"
            counts[status] += 1
            stream.write(json.dumps(dict(schema_version=SCHEMA, kind="offline_shadow_replay", model_id=model["model_id"], flight=row["flight"], scenario_group=row["group"], split=row["split"], elapsed_s=row["elapsed_s"],
                status=status, unsupported_features=unsupported, predicted_remaining_distance_m=pred[0], predicted_remaining_time_s=pred[1],
                empirical_residual_band_m=model["validation_residual_band_m"], empirical_residual_band_s=model["validation_residual_band_s"],
                observed_remaining_distance_m=row["distance_m"], observed_remaining_time_s=row["time_s"],
                outcome_fields_available_online=False, flight_control_authorized=False)) + "\n")
    print("REPLAY: " + json.dumps(dict(counts)) + "; zero simulator connections/writes")
    return counts


def collect(root):
    """Append immutable snapshots of newly recorded logs, never overwrite an existing raw record."""
    source, destination = root / "logs/alia", root / "research/local/data/alia"
    destination.mkdir(parents=True, exist_ok=True)
    files = sorted(p for p in source.glob("*") if p.is_file() and (p.suffix in (".csv", ".jsonl") or p.name.endswith(".metadata.json")))
    count = 0
    for path in files:
        target = destination / path.name
        if target.exists():
            if digest(path) != digest(target):
                raise ValueError(f"Raw snapshot collision for {path.name}; existing record is preserved")
        else:
            shutil.copy2(path, target)
            count += 1
    dump(root / "research/local/data/collection-manifest.json", dict(schema_version=SCHEMA, files=[dict(file=p.name, sha256=digest(destination / p.name)) for p in files]))
    print(f"COLLECT: {count} new raw files copied; existing raw snapshots preserved. New scenarios require a new reviewed dataset/split version.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("audit", "train", "replay", "all", "collect"))
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[2])
    args = parser.parse_args()
    root = args.root.resolve()
    if args.command == "collect": collect(root)
    if args.command in ("audit", "all"): audit(root)
    if args.command in ("train", "all"): train(root)
    if args.command in ("replay", "all"): replay(root)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError) as error:
        raise SystemExit("Research command refused: " + str(error))
