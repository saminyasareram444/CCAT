# Learned prediction contracts and implementation

## Implemented braking pilot

`research/tools/research_pipeline.py` audits, labels eligible complete approaches, builds a frozen scenario-group split, fits standardized ridge regressions and exports Python JSON/Java properties. Features are ground speed, its square, nonnegative forward IAS, vertical speed, pitch, current lift/pusher commands and elapsed approach time. Scaling and coefficients use training data only; validation selects ridge strength and supplies empirical residual diagnostics; the test group is evaluated without fitting.

Targets are remaining geometric ground path and reported controller seconds to GS <10 m/s and forward IAS <30 kt, under `alia-airport-brake-hold-v1`. An arbitrary cruise state or a different braking maneuver is outside this target definition. The CSV transition row whose commands were calculated in CRUISE is excluded. Incomplete, time-invalid and legacy-policy records stay in the audit without fabricated stopping labels.

Whole flights and conservative route/scenario groups stay together. Near-repeat recordings are not independent test evidence. The current five fitting flights span only three scenario groups. This supports an illustrative **PILOT_ONLY** model, not validated operational uncertainty. The validation residual bands fail measured test coverage and are not presented as trustworthy bounds.

`BrakingModel.java` is pure inference over value inputs; `BrakingShadow.java` handles scope/freshness gates, optional display and asynchronous local records. Neither can send aircraft commands. Python/Java parity is checked against every exported example when local model artifacts are present. No prediction is made from a retained inactive controller trace.

## Energy predictor: next component, not implemented

- Candidate inputs: phase, trajectory definition, distance, height change, air/ground speed, commands, measured battery energy/voltage/current and supported wind observations.
- Targets: energy for a defined future trajectory and arrival reserve, with uncertainty evaluated on independent conditions.
- In the audited logs, negative voltage-current integral accompanies declining measured charge. Preserve the raw sign and validate this convention against pack energy before computing consumption labels for a new aircraft/model version.
- Historical CSV headers differ; some lack battery/airspeed fields. Journal files contain optional per-pack/weather values but also paused and retained traces. File-key presence alone does not establish fresh independent sensor measurements.
- Avoid future arrival/charge leakage, and never use pilot acceptance as proof that an energy estimate was correct.

See `research/experiments/approach-baseline.md`, `research/evaluation/README.md` and the generated local reports for definitions and limitations.
