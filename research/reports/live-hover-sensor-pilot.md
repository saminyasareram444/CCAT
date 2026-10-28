# Live hover and simulated-sensor pilot — 2026-10-28

Two operator-authorized flights were executed in **X-Plane 12.4.3-r2, build 124311**, installed BETA ALIA-250, on the paved KSWF Runway 27 ground start. Conditions: calm measured wind, 1x simulation speed, idle initial throttles, all five batteries on, no competing joystick overrides. Installed mass was about 2,857 kg. Visual inspection confirmed paved runway surroundings before flight. No airport cruise or destination mission was attempted.

The inherited `HoverMission` used one waypoint `(north=0, east=0, height=15 m)`, initial hover .60 and speed setting 1 m/s. A small explicit-execution wrapper added stricter abort limits and instrument recording; controller equations and gates were unchanged. Two earlier paused/freshness preflight rejections are retained locally. A read-only check then passed after preparing the stationary ground aircraft. The successful flight traces include TAKEOFF → WAYPOINTS → RETURN → DESCEND → LANDED.

| Measured result | Trial 1 | Trial 2 |
|---|---:|---:|
| Wall/controller duration | 48.303 s | 48.153 s |
| Simulator-clock elapsed | 48.351 s | 48.222 s |
| Recorded control rows | 967 | 964 |
| Maximum height above start | 14.994 m | 14.994 m |
| Touchdown horizontal offset | 1.692 m | 1.697 m |
| Maximum absolute pitch | 2.440° | 2.440° |
| Maximum absolute roll | 0.039° | 0.087° |
| Largest sample gap | 0.077 s | 0.074 s |
| Simulator-clock reversals | 0 | 0 |
| Start / end average charge | 99.999 / 96.815% | 96.811 / 93.636% |
| Signed integrated V×I energy | −10.347 kWh | −10.318 kWh |
| Maximum absolute electrical power | 942.6 kW | 945.3 kW |

Both flights landed without a configured abort. Independent postflight telemetry confirmed ground contact, idle lift/pusher and released joystick overrides after the first landing; the second flight also completed its landing/release path. Final ground state is checked again before leaving the monitoring display. These are two repeats of one condition, not independently varied test scenarios or validation of a full aircraft/control envelope. The roughly 1.7 m offset is within the inherited return/landing gates and is not centimetre-level precision.

Final read-only telemetry confirmed stationary ground contact, all five throttles at zero and all three joystick overrides released (`research/local/live-trials/final-ground-state.json`). Software validation passed 8,005 Java checks, 265 actual-JSoar integration checks and 20 Python tests. Live tablet views were rendered and visually inspected in portrait, compact and landscape sizes. These tests supplement the two measured flights; they do not extend their operating envelope.

## What the sensor inspection established

- Actual simulator subscriptions supplied pilot/copilot AHRS pitch, roll and magnetic heading, both radio altimeters, GPS navigation/status, battery measurements and simulator truth rates/accelerations. The live tablet now shows them separately from the manual warning lab.
- Maximum AHRS pitch/roll pair difference was **0.0°** in both flights. Radio-altimeter pair difference was also **0.0 ft**. Separate channel names did not establish independently failing/noisy sensors.
- GPS fault codes remained zero. No independent GPS receiver latitude/longitude feed was found. The aircraft's truth coordinates are not relabeled as GPS observations.
- Radio altimeters were identified correctly; there is no installed LiDAR range stream and no obstacle/terrain clearance claim.
- V×I power was negative while measured charge fell in both runs. Raw signs were retained. This supports checking the negative-current discharge convention for this installed model before fitting energy targets; it is not evidence of a calibrated real-vehicle model.

## Actual-JSoar counterfactual replay

After recording, every twentieth sample from both flights formed 98 nominal observation points. Six cases were replayed per point: nominal, +4.1° pilot-pitch bias, +4.6° bias, +5.5° bias, dropout and stale. Small seeded noise was applied to copied biased readings. The live aircraft and raw records were not modified. Absolute copied-channel pitch disagreement was normalized as `2 × degrees`, explicitly an uncalibrated research convention matching the legacy low/high bands.

JSoar learned over 100 scripted-preference episodes, then evaluation learning was frozen. The script preferred silence in the low band and a warning in the high band. It was an oracle for a functional learning demonstration, **not a human pilot or verified safety-outcome label**. Different bias magnitudes were used for the replay, but the fixed band mapping means this is not a generalization study.

| Case | Cases matching scripted functional expectation |
|---|---:|
| Nominal → QUIET | 98 / 98 |
| Low bias → learned QUIET | 98 / 98 |
| High bias → learned WARN | 98 / 98 |
| Mandatory bias → MANDATORY-WARNING | 98 / 98 |
| Dropout → UNAVAILABLE | 98 / 98 |
| Stale → UNAVAILABLE | 98 / 98 |

All **588 functional checks** matched. Learned and untrained choices differed on 98 low-band cases. Mandatory warning and unavailable-data handling remained fixed. Learned preferences were: low WARN −.9308 / QUIET +1.0000; high WARN +1.0000 / QUIET −.5100. These are Q preferences, not confidence or detection accuracy.

## Artifacts and next experiment

Raw trials: `research/local/live-trials/2026-10-28T22-50-25.739283700Z/` and `2026-10-28T22-52-13.024179Z/`. Each contains `flight.csv`, `metadata.json`, `outcome.json`. SHA-256 inventories and summaries: `research/local/live-trials/campaign-results.json`. Detailed replay: `research/local/sensor-experiments/1793228207583/`. Earlier read-only ground recording and tablet screenshots: `research/local/sensor-demo/`. Raw data are ignored by Git; code and this aggregate report are published.

Next, define sensor models with explicit timing, units, independent bias/drift processes and physically justified detection targets; add truth labels only to the evaluator. Expand to independent conditions before a frozen generalization test. Actual pilot accept/reject sessions are needed to study human preference or workload. A genuine range/raycast feed is needed for LiDAR research. Mission-level Soar, energy decisions, episodic retrieval, autonomous rerouting and cruise/braking qualification remain separate future milestones.
