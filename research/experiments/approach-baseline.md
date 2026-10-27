# Experiment BRK-001: prediction during the existing braking approach

## Objective and boundary

Evaluate whether a learned response model predicts remaining ground travel and time to rotor capture more accurately than a fixed-deceleration comparator. During this milestone the learned model only observes. It does not change braking onset, joystick inputs, power, route, landing site, alerts, or pilot authority.

The main future scenario is an aborted approach with limited energy reserve. BRK-001 isolates braking first; energy-constrained decisions, Soar and episodic retrieval remain later components.

## Fixed reference

- Preserved baseline: `e49775e583b54355a4915ca2e7dcc09fb8a8186e`.
- Current approach contract: `alia-airport-brake-hold-v1`; version it when its procedure changes.
- Aircraft: installed BETA ALIA-250; record the installed aircraft version and X-Plane build for every new campaign.
- Existing controller: 90 kt cruise, 300 m clearance, initial hover setting 0.60, existing approach and rotor-capture procedure.
- Existing approach trigger: `max(2200, ground_speed^2 / 0.7 + ground_speed * 8 + 500)` metres. Its buffers/floor are an operational trigger, not an unbiased stopping-distance predictor.
- Capture event: ground speed below 10 m/s and forward IAS below 30 kt. This is not zero speed, touchdown, or stabilized hover.
- Prediction inputs are available at the current control step. Future capture time/distance appear only as offline labels.

## Route and landing-site checks

The copied mission specifies KMGJ, Runway 4/22 midpoint: 41.5144019018, -74.2647184898; asphalt, width 30.48 m, length about 1527 m, 3D scenery. Source: the installed `Global Scenery/Global Airports/Earth nav data/apt.dat`. These are recorded database attributes, not an independent visual/obstacle survey.

Use a saved, repeatable departure location and aircraft heading. Record its coordinates, airport/start identifier, aircraft mass/payload, battery state and source scenery. The historical first route starts near 41.500896, -74.130287. A saved start must be checked in the simulator before new trials; coordinates alone do not establish a usable takeoff surface. Verify the selected destination is actually on the visible runway and unobstructed before counting a new trial as a verified-surface experiment.

## New-run procedure

1. Select a pinned research commit and record whether the working tree is dirty. `run-alia.ps1` supplies this to each new CSV sidecar, along with the airport-controller source hash.
2. Copy `research/experiments/scenario-template.properties` to ignored `research/local/active-scenario.properties`. Fill it in for each trial. Unfilled settings remain `UNCONFIRMED`, not inferred facts. Include payload/mass in notes or the saved aircraft configuration.
3. Load the saved start in X-Plane, set 1x simulation speed, calm weather for the initial condition, a fixed payload and initial charge, engines running and all five batteries on. Set throttles idle and remove competing throttle/control assignments as in ALIA_GUIDE.md.
4. Run **one** CCAT instance from this worktree with `run-research.cmd monitor`. Connection subscribes for telemetry; it does not start automatic flight. Select the same destination/site and press Start automatic flight after the preflight review.
5. Observe the existing controller. The shadow window begins predicting only during APPROACH. The initial experiment makes no request to change the maneuver. Use the existing pause/takeover function when needed and retain the failed/interrupted run.
6. Record interventions, outcome and actual weather/configuration. The raw CSV ends on stop, so an incomplete segment is censored rather than assigned a successful stopping label. Record any simulator pause, speed change, reset or aircraft change.
7. After the run, close/stop the recorder, run `run-research.cmd collect`, then `run-research.cmd audit`. New raw snapshots are appended and never overwrite a same-named record. A new dataset/split version is required before retraining on newly collected flights.

The simulator was not flown as part of this implementation. Historical runs were audited and replayed; new live trials remain to be collected.

## Campaign design

First repeat the fixed nominal condition to validate the recording pipeline. Then vary one condition at a time: approved cruise speed, route direction, approach height/clearance and wind. Preserve the same braking procedure within a policy version. Evaluate a revised braking procedure as a separate policy/campaign. Do not inject a late-braking maneuver by bypassing controller limits; implement and separately validate a bounded scenario mechanism first.

Keep all repeats from a route/scenario together. Reserve unseen route and weather conditions for final testing. A handful of repeats is a pipeline pilot, not statistical evidence of general safety. Set the number of independent scenarios using pilot variability and a prespecified precision/power objective, rather than treating thousands of adjacent telemetry rows as independent samples.

## Data and outcomes

New runs produce `logs/alia/<run>.csv` and `<run>.metadata.json`. The sidecar records run identity, source hash, supplied commit/dirty state, operator-reported scenario settings and destination/scenery provenance. Raw CSV elapsed time is still the inherited capped wall/controller clock; it is not independently measured simulation time. Reject timing-invalid runs and collect an independent simulator clock before timing-sensitive assurance claims.

Offline targets are integrated geometric ground path and controller seconds from each sampled APPROACH state to the recorded capture gate. Skip the CRUISE-to-APPROACH transition row because its command was computed in CRUISE. Preserve the first capture row as the endpoint, not an input feature. Exclude missing/nonfinite required values and time reversals/gaps over 250 ms from fitting; retain them in the audit.

Report model MAE/RMSE, signed bias, maximum absolute error, support violations and measured residual-band coverage. Report flight outcomes separately: approach/capture duration, distance remaining at capture, along-track overshoot and reported touchdown error. Historical phase/range data is not independent confirmation of surface suitability.

The fixed comparator uses `(v^2 - 10^2)/(2 * 0.35)` metres and `(v - 10)/0.35` seconds, clipped at zero. Report the buffered controller trigger separately. All runtime estimates are conditional on the unchanged procedure; an APPROACH-only model cannot establish when arbitrary cruise flight should start braking.

## Progression criteria

The current artifact is `PILOT_ONLY`. More independent conditions, reviewed provenance and successful uncertainty evaluation are required before it can support operational recommendations. Satisfy those criteria on a fresh campaign; do not tune a model repeatedly against the already viewed v1 test group. Moving from predictions to changed flight commands also requires the authority protocol, a justified fallback and matched closed-loop experiments. See `research/authority/authority-requirements.md`.
