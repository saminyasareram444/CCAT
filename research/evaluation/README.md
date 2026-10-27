# Evaluation protocol

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
