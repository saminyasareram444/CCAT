# CCAT: Soar, learned flight predictions and episodic memory

This is the isolated **research experiment** branch. It starts from the saved ALIA pilot-display version at `e49775e583b54355a4915ca2e7dcc09fb8a8186e`.

**Status: experiment workspace prepared; no learned predictors or new cognitive supervisor are active.** The existing ALIA flight controller, telemetry and dashboards are inherited unchanged. Launching `run-alia.cmd` or `run-pilot.cmd` still runs that baseline controller.

The preserved version is available on branch `baseline/alia-pilot-2026-10-26` and tag `alia-pilot-baseline-2026-10-26`. See [VERSIONING.md](VERSIONING.md) for local folders, snapshots and verification.

## Proposed research

Study whether a Soar supervisor with learned energy/braking predictions and episodic memory improves pilot recommendations under changing flight conditions.

1. Learn route-energy requirements and stopping distance from recorded state–action–response data.
2. Represent the mission, predictions, data validity and alternatives in Soar working memory.
3. Retrieve relevant flight episodes and outcomes.
4. Compare recommendations such as continue, begin approach earlier, review an alternate, or request pilot verification.
5. Evaluate the recommendation and its explanation against the existing baseline.

The first integration should be **shadow / recommendation only**. The alternate-route preview continues to leave the active mission unchanged. This document does not implement a new flight-control path.

## Where to start

- [Research plan](research/README.md)
- [Prediction data and targets](research/prediction/README.md)
- [Soar integration contract](research/soar/README.md)
- [Episodic-memory design](research/memory/README.md)
- [Evaluation protocol](research/evaluation/README.md)
- [Experiment configuration](research/config/experiment.json)
- [Existing ALIA guide](ALIA_GUIDE.md)

Recorded flight data and future trained models belong under ignored `research/local/`. A local copy of the existing flight logs is supplied there; those logs are not uploaded to GitHub.

## Run the inherited baseline

```powershell
.\run-pilot.cmd
.\run-alia.cmd -Test
```

Run only one CCAT controller instance connected to X-Plane at a time. The original checkout remains available separately. No Soar/ML installation is required merely to keep using the inherited baseline; research dependencies will be chosen when their adapters are implemented.
