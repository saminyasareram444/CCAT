# Evaluation protocol

## Implemented BRK-001 pilot

`run-research.cmd all` audits the immutable local raw snapshot, builds complete current-policy approach examples, applies the frozen route/scenario split, fits/validates the predictor and replays it. `run-research.cmd test` exercises leakage boundaries, censoring, raw-data collision protection, scope gates, recording failure isolation and Python/Java numerical parity, alongside the inherited controller suite.

The split is recorded in `research/config/braking-split-v1.json`. It has one scenario group per partition; repeated flights remain grouped. This small pilot cannot establish broad generalization. Scalers and coefficients use training only. Validation selects regularization and residual diagnostics. Test outcomes are not available to runtime inference. Do not retune repeatedly on the already observed test group; collect/version a new campaign for later model development.

The checked-in reports summarize the local artifact run. Detailed JSON reports group/flight-balanced MAE, RMSE, bias, maximum error, per-feature support violations and empirical band coverage. All raw logs/models stay ignored. The validation residual bands failed test coverage, so uncertainty is unqualified and no model promotion is authorized. Predictions are conditional on the current approach procedure and rotor-capture target; they are not a proven improved braking policy.

## Baseline configurations

1. Existing controller / fixed recommendations.
2. Learned numerical predictions without Soar memory.
3. Soar using the same predictions but no episodic retrieval.
4. Soar using the same predictions and prior-flight episodic retrieval.
5. Optional learned operator preferences, evaluated separately.

Use the preserved baseline commit as the reference. Record scenario seeds, aircraft/configuration, simulator version, controller/model versions, data validity and interventions.

## Measures

- Predictions: held-out energy and stopping-distance/time error; uncertainty calibration where implemented.
- Recommendations: correctness under a predefined evaluation criterion, late or unnecessary diversions, reserve violations, decision latency and pilot interventions.
- Flight outcomes when the evaluation actually changes control: overshoot, touchdown error, energy use and completion rate.
- Explanations: agreement with recorded decision evidence, unsupported claims, and pilot comprehension / response time in a properly designed user study.

## Separation and tests

Split entire flights/scenarios, not adjacent telemetry rows. Keep held-out outcomes out of memory retrieval. Use matched scenarios across configurations and report uncertainty across runs. Audit existing logs for missing fields and controller revisions.

The inherited offline controller suite runs with `run-alia.cmd -Test`. It verifies the existing software and simplified fixtures, not the proposed cognitive/ML system or actual X-Plane flight performance. Add tests appropriate to the new components when implemented.
