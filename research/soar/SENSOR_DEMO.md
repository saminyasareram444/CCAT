# Synthetic Soar sensor-warning lab

Update 2026-10-28: **Sensors → Live instruments** now shows real subscriptions to X-Plane's simulated AHRS, radio-altimeter and GPS navigation/status instruments. **Warning lab** remains the explicitly synthetic manual-error demonstration below. Separate GPS positions, raw IMU sensor measurements and LiDAR ranges are still unavailable. See [live experiments](LIVE_INSTRUMENTS.md).

This first executable cognitive milestone adapts the warning-preference learning idea from the [AHMIIAS Full Learning Agent with Macros](https://github.com/loonwerks/AHMIIAS/tree/8c39be86211c701bb3cbf003d73387e4d86f3a63/agents/Full%20Learning%20Agent%20with%20Macros), reviewed at commit `8c39be86211c701bb3cbf003d73387e4d86f3a63`. These are new, small Soar productions and a new host; the legacy flight operators and Java actuator bridge are never loaded. No Tcl macros are needed in this version.

## Launch

From **CCAT-soar-research**, double-click `run-soar-demo.cmd`, or run:

```powershell
.\run-soar-demo.cmd          # Offline tablet; X-Plane is not required or contacted.
.\run-soar-demo.cmd -Live    # Existing ALIA application + tablet. Select Sensors.
.\run-soar-demo.cmd -Test    # Tests using the actual pinned JSoar kernel.
.\run-soar-demo.cmd -Render  # Render tablet previews under research/local/sensor-demo/.
```

The runtime is already prepared on this machine. On another checkout, installed Maven-cache artifacts are reused when their hashes match. If libraries are missing, run `run-soar-demo.cmd -InstallDependencies` once. It downloads the explicitly listed libraries from Maven Central into ignored `research/local/deps/` and verifies SHA-256 checksums. JDK 25 is found through PATH, JAVA_HOME, or the VS Code Java extension. No global environment-variable changes are required. Default ALIA compilation and launch still need no JSoar dependencies.

`-Live` subscribes to existing native X-Plane telemetry. It does not start automatic flight. Close the other CCAT flight application first. The lab has no flight-control or destination capability; its synthetic warnings do not pause X-Plane or enter the real master-caution logic.

## Reproducible first learning experiment

1. Open the Sensors tab. Leave **Train** checked. Initially GPS is 8.5 and IMU/LiDAR are 0. The first seeded GPS choice is WARN.
2. Select **Should change** for the GPS decision. This rates the warning as inappropriate; it is not a takeover command. The GPS/low warning Q value changes from `+0.20` to `-0.16` with one update. Scroll down to see the preference bars.
3. Uncheck **Train**. The same sample is evaluated with learning and exploration disabled. GPS should now choose QUIET, whose Q value remains 0.
4. Set GPS to 10 and press **Evaluate sample**. Its choice is MANDATORY-WARNING. Pilot ratings are disabled for this fixed state, regardless of learned preferences.
5. Change a feed to DROPOUT or STALE and evaluate. Its result is UNAVAILABLE, never an invented healthy reading. The other two sensors retain their own choices.
6. **Reset session learning** restores initial preferences. A process restart also starts a fresh policy. Audit logs survive; automatic policy restoration is deliberately not implemented.

Inputs are manual samples, held until Evaluate. The panel refreshes twice a second from the worker, but this is not a physical sensor stream. Draft spinner/feed edits do not replace the applied sample until Evaluate. A new sample or mode change replaces pending decisions. Each decision has a unique session-local ID; one pilot rating can create at most one reward. Ratings expire after 30 seconds. Scroll inside the Sensors card on smaller tablets.

**Appropriate** rewards the displayed choice with +1; **Should change** gives -1. For QUIET, those mean that silence was appropriate or a warning was needed. This extends the old warning-only response path to allow explicit evaluation of either choice. Ratings reflect pilot preference, not an independently verified safety outcome.

## What the values mean

| Error proxy | State | Decision mechanism |
|---|---|---|
| 0 ≤ error < 8 | normal | Fixed QUIET |
| 8 ≤ error < 9 | low | Learned WARN vs QUIET |
| 9 ≤ error < 10 | high | Learned WARN vs QUIET |
| error ≥ 10 | safety | Fixed MANDATORY-WARNING |
| dropout or stale | unavailable | Fixed UNAVAILABLE |

The 8/9/10 bins preserve the reviewed legacy example's structure. They are dimensionless research bins, **not meters, probabilities, calibrated aircraft thresholds or verified safety limits**. A synthetic LiDAR error proxy does not establish obstacle clearance. Independent GPS receiver positions, inertial measurements and LiDAR ranges are still unavailable; the display says so. Native position, attitude, airspeed, battery and weather remain simulator measurements with their existing freshness checks.

There is one isolated JSoar agent per error channel, preventing a normal sensor from suppressing another sensor's mandatory warning or combining reward credit. Each agent has four numeric-preference RL rules: low/high × warn/quiet. JSoar performs the updates. Training uses seeded epsilon-greedy exploration (epsilon .2), learning rate .3, discount 0, eligibility decay 0. Evaluation uses epsilon 0 and learning off. Initial WARN Q is .2; QUIET is 0. This is a contextual warning-choice demonstration, not a learned temporal flight policy. Thresholds do not move.

Reward is asserted for a terminal transition and the agent halts. The host runs no extra decision cycles while waiting for a rating. A new trial initializes working memory, retaining numeric learned preferences during that process. Tests verify exactly one update, no repeated reward, positive/negative changes and frozen evaluation. Q bars show actual preference values, **not model confidence, attribution, independent sensor agreement or a safety assurance score**.

## Architecture and records

`PilotDisplay → SensorLabPanel → SensorResearch` passes only synthetic input values and correlated ratings to `SoarSensorResearch`. A bounded eight-command queue and a separate daemon worker isolate it from the 20 Hz flight loop. Immutable display snapshots carry the results back to Swing. The backend receives no NativeLink, flight controller, mission or destination reference. Its only filesystem output is the local audit journal.

`research/local/sensor-demo/session-*.jsonl` records session time, JSoar version, seeds, source hash, checkout commit, synthetic provenance, decision IDs, inputs, availability, bands/actions, mode, rewards, Q changes, expirations, ignored replies and resets. Dependencies, generated images and journals are ignored by Git. The state is session-only; do not mix a reset or a new process into the same claimed training run. The current manual demo has no injected ground-truth labels or supervised dataset.

## Verification and next milestone

The actual-kernel tests cover all 64 normal/low/high/safety combinations across the three agents, threshold edges, missing/stale inputs, preference changes, sensor credit isolation, frozen evaluation and correlated/duplicate/expired feedback. Tablet render previews cover 700×1000, 540×680 and 1000×700; short viewports scroll rather than overlap. These are offline checks, not a live ALIA flight validation.

Next, replace manual proxies with explicitly modelled GPS/IMU errors, timestamps, declared units and seeded fault scenarios. Keep truth labels in an evaluation-only channel. LiDAR requires a scene/raycast or plugin range feed before claiming range or obstacle sensing. Then connect reviewed predictor evidence and cognitive recommendations through the separate mission-supervisor contract. Energy prediction, episodic retrieval, authority negotiation, rerouting and controller changes are outside this lab.

Runtime semantics: [Soar reinforcement learning manual](https://soar.eecs.umich.edu/soar_manual/05_ReinforcementLearning/). The tests pin the demonstrated behavior to JSoar 4.1.0 rather than assuming all Soar versions behave identically.
