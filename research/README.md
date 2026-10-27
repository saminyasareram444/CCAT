# Research plan

**Question:** Does combining learned energy/braking predictions with Soar episodic reasoning improve the quality and explanation of ALIA flight recommendations on unseen missions?

## Proposed components

| Component | Responsibility | Initial status |
| --- | --- | --- |
| Telemetry / existing controller | Supply measured state, actions and outcomes | Inherited baseline |
| Energy predictor | Estimate route/phase energy and arrival reserve with evaluated uncertainty | Not implemented / not trained |
| Braking predictor | Estimate stopping distance/time for a specified approach/control context | Not implemented / not trained |
| Soar supervisor | Compare high-level recommendations using goals, predictions and memory | Not integrated |
| Episodic memory | Store/retrieve prior flight situations, recommendations and measured outcomes | Contract/design only |
| Pilot display | Present recommendation, evidence and alternatives | Existing display; cognitive recommendation integration pending |

## Stages

1. Audit and label the local flight logs; distinguish valid operations, manual intervention, faults and aborted flights.
2. Establish reproducible whole-flight training/validation/test splits and baseline metrics.
3. Train separate energy and braking predictors; compare against simple baselines.
4. Build a Soar adapter in shadow mode with structured state and output records.
5. Add episodic-memory retrieval and evaluate its incremental effect.
6. Optionally learn operator preferences using measured outcomes. Episodic retrieval alone does not constitute a learned control policy.
7. Integrate recommendation evidence into the pilot display and conduct the planned human evaluation if appropriate.

The current experiment is prepared for these stages; creating this branch has not completed them. Do not present prediction uncertainty, retrieved cases or learned recommendations as available before their implementations and evaluation exist.

## Files and local artifacts

`research/config/experiment.json` records the intended setup. All raw data copies, dataset exports, trained models and runtime memory files should stay under ignored `research/local/`. Commit schemas, training/evaluation code and reproducible configuration when developed; do not commit generated flight logs accidentally.

Use the [evaluation protocol](evaluation/README.md) to separate the effect of predictions from the effect of cognitive memory.
