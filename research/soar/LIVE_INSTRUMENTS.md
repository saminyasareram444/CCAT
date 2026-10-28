# Live simulator instruments and experiments

The user confirmed that "physical independent sensor feeds" meant **X-Plane simulated sensors**, and explicitly authorized simulator control and experiments. The original CCAT checkout remains unchanged. Two short hover-and-land trials were completed using the existing `HoverMission` equations; the learned components did not fly the aircraft.

## View live readings

From CCAT-soar-research, run `run-soar-demo.cmd -Live`. The tablet opens **Sensors → Live instruments** once fresh telemetry arrives. **Warning lab** is the separate manual synthetic-error demonstration. Regular `run-pilot.cmd` also shows a compact instrument summary in Sensors.

| Feed | What is available | What it does not establish |
|---|---|---|
| AHRS pilot/copilot | Indicated pitch, roll and magnetic heading | Raw inertial measurements or independent noise/error processes |
| Radio altimeters | Pilot/copilot indicated height in feet | LiDAR ranges, terrain raycasts or obstacle clearance |
| GPS 1/2 | Fault state, course deviation; GPS 1 DME status/distance | Separate GPS receiver position or measured GPS position accuracy |
| Simulator truth | Aircraft position, P/Q/R and local accelerations | An independent GPS/IMU measurement stream |
| Battery | Measured charge, volts, amps and signed power | A calibrated real-aircraft energy model |

All are simulator-generated. Source datarefs come from the installed `Resources/plugins/DataRefs.txt`. `SimulatorSensors.Key` records the subscribed references and units. Added instruments are optional: missing, stale or nonfinite values display UNAVAILABLE and do not invalidate the existing 22-field core control connection. The fixed master-caution layout and recovery latch are preserved.

No XPlaneConnect or extra sensor plugin is required for these native UDP subscriptions. No LiDAR plugin is installed. In both nominal flights, the two AHRS outputs and two radio-altimeter outputs were identical. Treat these as separate simulator instrument channels; independence has not been demonstrated.

## Repeat a bounded flight

```powershell
.\run-soar-demo.cmd -Preflight    # Read-only checks, no arming or unpause
.\run-soar-demo.cmd -HoverTrial   # EXPLICIT FLIGHT COMMAND: starts the bounded trial
```

Use one active controller. Start stationary on level ground, unpaused, engines running, all five batteries on, lift/pusher idle and joystick overrides released. Initial conditions require calm wind below 1 m/s, 1x speed, achieved speed near real time and measured reserve above 25% in every pack. The experimental wrapper commands only the inherited hover procedure: 15 m takeoff, one home waypoint, return and landing, with speed setting 1 m/s. It adds stricter 15 m horizontal-radius, 25 m height, 15 degree attitude, 5 m/s vertical-speed, 6 m/s ground-speed and 180-second wall-time limits. Loss of telemetry, control readback mismatch, a pilot pause or scheduling delay aborts with pause/release requests. A successful landing idles the motors, applies the parking brake and releases the overrides. UDP commands are not an acknowledged authority-transfer protocol.

The first trial required unpausing the idle ground aircraft after interrupted monitoring. The source wrapper also has an explicit `--unpause-ground` preparation argument, guarded by fresh ALIA identity, stationary ground contact, idle throttles and no owned overrides. The normal preflight/flight launcher does not unpause automatically. No flight commands were sent by the rejected preflight attempts; their outcome records remain local.

Files are under `research/local/live-trials/<UTC timestamp>/`: measured flight CSV, metadata and outcome. The simulation clock is recorded independently of the wall/controller clock. The wrapper's `--execute` flag is mandatory for flight commands. Soar and replay have no control or destination reference.

## Recorder, summaries and replay

`research/tools/simulator_feed.py` is a read-only Python UDP recorder with approximately 75 native measurements, availability/age and declared synthetic-simulator provenance. It has no DREF or CMND path. Example, using the installed Python or another Python 3.10+:

```powershell
python research/tools/simulator_feed.py --seconds 30 --output research/local/sensor-demo/my-ground-record.jsonl
python research/tools/analyze_live_trials.py
.\run-soar-demo.cmd -ReplayFlights "research/local/live-trials/<completed flight>/flight.csv"
.\run-soar-demo.cmd -RenderLive   # Read-only screenshots using actual simulator telemetry
```

The recorder uses RREF only, preserving the controller's RPOS subscription. RREF values are float32 on the wire; its aircraft latitude/longitude are truth positions, not GPS measurements, and have that transport precision. The flight controller still uses double-precision RPOS coordinates. Some response packets have a nonzero fifth padding byte; the recorder validates the four-character tag, length, sender and finite values.

For multiple replay files, invoke `run-soar-demo.ps1 -ReplayFlights @('first.csv','second.csv')` from a PowerShell process started with `-ExecutionPolicy Bypass`, matching the existing CMD launchers' process-local setup. No global execution-policy change is needed.

The recorded AHRS channels are copied for the replay. Only the copied pilot pitch receives seeded synthetic bias; dropout and stale cases mark that copy unavailable. Truth labels stay in the experiment harness and are not input to the agent. The demonstration maps absolute pitch disagreement to a dimensionless proxy using `2 × degrees`; this is an explicit experimental convention, not a calibrated safety limit. Training uses a scripted preference oracle: quiet for low-band decisions, warn for high-band decisions. It is not human feedback. Evaluation freezes learning and checks nominal, low bias, high bias, mandatory bias, dropout and stale cases. No faults are written to the aircraft.

Completed runs and file hashes are summarized by `analyze_live_trials.py` in ignored `research/local/live-trials/campaign-results.json`. New trials also save execution-class hashes and measured pack capacity in `execution-provenance.json`. Raw records, generated screenshots, detailed replay and trained session preferences remain local. [The aggregate report](../reports/live-hover-sensor-pilot.md) contains measured results and limits.
