# CCAT research: Soar, learned predictions and episodic memory

This isolated research branch starts from the saved ALIA pilot-display version at `e49775e583b54355a4915ca2e7dcc09fb8a8186e`. The preserved source remains on `baseline/alia-pilot-2026-10-26` and tag `alia-pilot-baseline-2026-10-26`. See [VERSIONING.md](VERSIONING.md).

**Implemented:** offline flight-log audit, a pilot braking predictor, frozen scenario-group evaluation, shadow replay, an optional live prediction window and per-run provenance. **No learned component can change flight commands.** Energy prediction, the new Soar supervisor and episodic retrieval remain future milestones.

## Start here

```powershell
.\run-research.cmd all      # Offline audit, pilot fit/evaluation and replay
.\run-research.cmd test     # Research and inherited controller checks
.\run-research.cmd monitor  # Existing flight controls + pilot display + braking shadow window
```

`monitor` does not start automatic flight. Start a reviewed mission separately in Flight controls. The shadow observer predicts only during the existing airport approach and has no actuator or destination-writing interface. It shows point estimates to rotor capture, not touchdown. Uncertainty is explicitly unqualified; validation residual bands failed held-out coverage. No new X-Plane flight was performed for this milestone.

The existing `run-pilot.cmd` and `run-alia.cmd` still use the baseline controller. The research checkout adds logging/provenance and observer access; approach-control equations and gates are unchanged. Run only one controller instance against the simulator.

## Documentation

- [Research commands and status](research/README.md)
- [Data-readiness findings](research/reports/data-readiness-summary.md)
- [Braking pilot results and limitations](research/reports/braking-pilot-results.md)
- [Repeatable approach experiment](research/experiments/approach-baseline.md)
- [Pilot authority specification](research/authority/authority-requirements.md)
- [Prediction contracts](research/prediction/README.md)
- [Soar integration plan](research/soar/README.md)
- [Episodic-memory plan](research/memory/README.md)
- [Evaluation protocol](research/evaluation/README.md)
- [Existing ALIA guide](ALIA_GUIDE.md)

Python 3.10+ and the existing JDK are sufficient; the offline pipeline needs no third-party Python packages. The launcher detects the Python already installed locally. Raw flight data, trained models, runtime episodes and generated journals stay under ignored `research/local/`; only code, configuration and aggregate reports are published. A fresh clone needs locally supplied data before training. The configuration records intended research status and does not enable autonomous authority.
