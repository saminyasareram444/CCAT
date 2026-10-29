# ALIA research milestones

**Status:** offline audit, pilot braking predictor, scenario-group evaluation, replay, optional live shadow observer, and a synthetic sensor-warning learning demonstration using real JSoar 4.1.0 are implemented. No learned component can change controls or a destination. Mission-level cognitive supervision, physical independent sensor feeds, energy prediction, episodic retrieval and formal authority verification remain future milestones.

## Run

From this research checkout:

```powershell
.\run-research.cmd all      # Audit, train/evaluate using the frozen split, then replay. Offline.
.\run-research.cmd test     # Python pipeline tests + inherited Java suite + observer/parity checks.
.\run-research.cmd monitor  # Open existing flight controls/pilot display plus braking shadow window.
.\run-research.cmd sensor-demo # Offline tablet sensor-warning lab; no simulator connection.
.\run-research.cmd sensor-live # Same lab with the existing ALIA controls/pilot and native telemetry.
.\run-research.cmd sensor-test # Separate real-JSoar integration tests.
```

Monitoring subscribes to simulator telemetry; it does not press Start automatic flight. Predictions appear only during the existing airport APPROACH procedure. Missing/stale/paused data, a different phase or policy, and unsupported input features are labelled. The observer has no command interface. Restart the application after retraining to load a new model.

The optional sensor lab is a separate demonstration. Its GPS, IMU and LiDAR values are explicitly synthetic, dimensionless **error proxies**, not positions, inertial measurements or obstacle ranges. Pilot ratings adjust Soar numeric warning preferences; fixed normal, mandatory-warning and unavailable states cannot be trained away. See [the sensor demo guide](soar/SENSOR_DEMO.md) for launch instructions, a reproducible learning example, and limitations. The original `run-alia.cmd` remains dependency-free and does not start these agents.

**Live simulator milestone, 2026-10-28:** the Sensors tab now has a separate **Live instruments** page for AHRS pairs, radio altimeters, GPS navigation/status and labelled simulator truth. Two bounded hover/landing trials were flown and recorded. The frozen Soar fault-copy replay passed 588 functional cases after scripted preference training. These are repeatability/pipeline demonstrations; they do not establish physical sensor independence or general fault-detection accuracy. See [results](reports/live-hover-sensor-pilot.md) and [commands](soar/LIVE_INSTRUMENTS.md).

Commands `audit`, `train`, `replay` and `collect` can be run separately. `collect` appends snapshots from this checkout's `logs/alia/`; it never overwrites a differing raw record. New scenarios require a deliberately versioned dataset and split. The v1 split is intentionally frozen rather than silently reallocating new flights into an already viewed test set.

Python 3.10+ and the existing JDK are sufficient; the Python pipeline uses only the standard library. The launcher detects the Python already available locally. If necessary, point `CCAT_RESEARCH_PYTHON` at another installed Python executable. No third-party ML service or simulator plugin is used by the offline pipeline.

## Results and their limits

- 25 flight CSVs and 15 journals were audited. Six complete approach-to-capture sequences exist, but only five match the present approach trace contract.
- Those five flights form three route/scenario groups; repeat routes stay in the same partition. Two training flights share one group, one validation flight forms another group, and two test flights share the third group.
- The fitted model is a small standardized ridge regression for remaining ground travel and controller seconds to **rotor capture**, conditional on the unchanged procedure. It does not predict a full stop, touchdown, arbitrary cruise braking or a new maneuver.
- The held-out pilot result improves the point-prediction comparator, but its validation-derived residual bands fail held-out coverage. The observer displays uncertainty as unavailable. This model remains **PILOT_ONLY**.
- Raw data, models, detailed audit/evaluation JSON and journals stay under ignored `research/local/`. Aggregate snapshot reports are in `research/reports/`.

## Read next

- [Geographic basemaps and charcoal pilot theme](reports/maps-and-tablet-theme.md)

- [Data-readiness summary](reports/data-readiness-summary.md)
- [Pilot experiment results](reports/braking-pilot-results.md)
- [Repeatable baseline protocol](experiments/approach-baseline.md)
- [Scenario settings template](experiments/scenario-template.properties)
- [Pilot authority requirements](authority/authority-requirements.md)
- [Frozen v1 split](config/braking-split-v1.json)
- [Runtime/model configuration](config/experiment.json)
- [Synthetic Soar warning lab](soar/SENSOR_DEMO.md)

Detailed local outputs:

| Artifact | Location |
|---|---|
| Full audit and inventory | `research/local/audit/audit.json`, `data-readiness.md` |
| Training examples and provenance | `research/local/datasets/` |
| Model export (Python and Java) | `research/local/models/braking.json`, `braking.properties` |
| Evaluation and measured coverage | `research/local/evaluation/` |
| Offline predictions with observed labels | `research/local/replay/braking-shadow.jsonl` |
| Optional live prediction journal | `research/local/shadow/` |
| New flight records and sidecars | `logs/alia/` |

## Later milestones

1. Collect a broader, reviewed campaign and evaluate uncertainty on genuinely new scenarios.
2. Train an energy predictor with a checked electrical sign convention and future-free trajectory definition.
3. Build Soar recommendation logic with structured evidence and explicit pilot responses.
4. Add prior-flight episodic retrieval; keep held-out future outcomes outside the memory bank.
5. Verify the authority/communication protocol and evaluate approved execution with a validated fallback.

The original source version remains separately preserved. See `VERSIONING.md`.
