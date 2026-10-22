# CCAT: ALIA-250 automatic flight extension

This extension adds two **experimental automatic mission modes** for the ALIA-250 supplied with X-Plane 12. The original local **hover-test mode**:

1. Take off vertically to 15 metres above the starting aircraft position.
2. Fly the entered waypoints with the four lift rotors running.
3. Return to the starting latitude and longitude.
4. Descend vertically, detect ground contact, and idle the motors.

The new **airport mission mode** selects a destination from installed airport scenery, captures the aircraft's actual location at Start, and runs vertical takeoff, acceleration/transition, wing-borne cruise, deceleration, destination hover capture and vertical landing.

**The full airport controller has not been live-tuned or flight-validated in X-Plane.** Offline tests verify network formats, destination geometry and phase gates, and exercise a complete airport mission in a coarse lift/drag plant. That plant is not an ALIA aircraft model and does not establish that the gains fly the actual X-Plane aircraft. Begin live tuning with a local hover test.

In hover-test mode the pusher stays idle and the aircraft returns home. Airport mode uses the separate pusher and lands at the selected airport location. Both modes lack obstacle/traffic avoidance and terrain-route planning.

## How this relates to the original CCAT

The new source is under `src/alia`. It runs independently of `main.StartAgents`, leaving the original Java/Kotlin/Soar experiment unchanged. The original Soar rules and reinforcement-learning policies are missing from this clone and from the upstream repository's main branch. This extension does not recreate those policies or perform cognitive learning.

The old controller's two-engine throttle writes and thrust-vector angle changes do not match the installed ALIA. The installed aircraft has four lift motors (indexes 0–3), one pusher motor (index 4), and a custom collective callback in:

```text
C:\X-Plane 12\Aircraft\Laminar Research\BETA Technologies Alia-250\plugins\xlua\scripts\generic\generic.lua
```

The extension uses those motor assignments. It controls pitch, roll, and yaw through joystick input datarefs, retaining X-Plane's flight model and the aircraft's own stabilization. It never sets aircraft position or velocity to make the mission appear to fly.

## Run it

1. Start **X-Plane 12** and select **BETA Technologies ALIA-250**.
2. Choose a clear, level takeoff area with room around it. Use calm weather, normal simulation speed, and the aircraft's engines-running start option.
3. Set the lift and pusher throttles to idle. ALIA's single cockpit START/power button controls all five batteries together; verify that all five read ON in telemetry.
4. For initial tuning, unassign physical throttle axes that could compete with the controller. Do not run the original CCAT agent or another flight controller at the same time.
5. Double-click **`run-alia.cmd`** in this repository.
6. The flight-controls window and the **live explainability dashboard** open together. Monitoring connects automatically and does not engage flight controls. Use **Connect / monitor** to reconnect if needed. **Raw telemetry** on the dashboard opens the detailed numeric telemetry window.
7. Wait for **LIVE** and **ALIA-250**. Review the route, then click **Start automatic flight** when ready.

The launcher finds the JDK supplied by your installed VS Code Java extension. It can also use a JDK from PATH or JAVA_HOME. It builds this mode independently; Maven, Python, the missing Soar files, and the NASA XPlaneConnect plugin are not required.

The connection is local to `127.0.0.1:49000`. The installed X-Plane preferences currently use receive port 49000. No manual Data Output row selections are necessary for RPOS/RREF requests. If connection fails, check X-Plane's Settings → Network → UDP Ports and local firewall permission for Java.

## Airport mission planner

1. Load ALIA on the ground, prepare power and idle throttles, and connect CCAT.
2. Open **Mission planner** from the flight window or dashboard. The installed `apt.dat` scenery is indexed in the background. On this installation the index contains 38,331 airports and heliports. Enabled custom scenery takes precedence over the global airport database; disabled packs are skipped.
3. Search by airport code or name. Select a result and an arrival location: a runway midpoint or a helipad with coordinates from the installed airport data.
4. Set cruise clearance in metres and cruise indicated airspeed in knots. Defaults are **300 m** above the higher endpoint and **90 kt**. The preview shows geographic coordinates, elevation, direct distance, initial course, cruise MSL altitude and nominal cruise time. This is a direct geographic route, not a terrain-cleared flight plan.
5. Click **Use airport mission — start separately**. The flight window displays the selected airport and generated route. The input is also saved to `missions/airport-plan.json` for inspection. Selecting a mission does not send flight commands.
6. Press **Start automatic flight** in the flight window. The route is rebuilt from the aircraft's position at that instant. Startup currently requires a stationary ground start; mid-air mission engagement and changing an active destination are not supported.

The airport controller's configured sequence is:

| Stage | Behavior / transition condition |
| --- | --- |
| Takeoff | Climb to 40 m above start while aligning with the initial route course. Stabilize height, heading and speed for 2 seconds. |
| Transition | Ramp up pusher power. Schedule lift-rotor support down between 40 and 70 kt indicated airspeed. Advance after at least 70 kt, height at least 35 m and vertical speed above −1 m/s for 3 seconds. |
| Cruise | Track the destination route, cruise height and requested airspeed with lift rotors commanded idle. |
| Approach | Begin deceleration at a distance based on ground speed, with a minimum of 1.6 km. Reduce pusher power and restore rotor support as airspeed falls. |
| Hover capture | Below 12 m/s ground speed and within 450 m, use rotor position control to capture the selected arrival coordinates at approximately 30 m above the airport elevation. |
| Descent | After stable capture, descend vertically. Horizontal drift pauses descent. Ground contact sustained for 1 second idles both lift and pusher groups. |

These are controller schedules and gates, not proof of aerodynamic transition or landing performance. The airspeed blend does not measure the actual fraction of lift produced by the wings. Ramp rates, trim, gains and transition thresholds need live ALIA tuning.

Airport-mode planning limits are **2.5–100 km** direct distance, **100–1000 m** cruise clearance above the higher endpoint, and **75–110 kt** cruise setting. Routes outside that envelope are rejected explicitly; they are not silently shortened. Distance and phase timeouts expand with the airport route, replacing the hover mode's 500 m planning radius and 180-second fixed stage timeout. Airport flights use geographic destination coordinates and do not return to the departure point after the route.

Cruise clearance does not inspect intervening terrain. Airport elevation and landing coordinates come from scenery metadata and may not identify a clear or obstacle-free landing surface. A low terrain-clearance guard during forward flight requests pause/takeover; it does not compute a reroute. Cruise indicated airspeed below **55 kt for 2 seconds** also requests takeover; that is an experimental controller guard, not an aircraft stall-speed certification. Reserve checks require at least **25% measured charge in every pack at airport-mission start**; below **15%** or missing charge data during an airport mission requests pause/takeover. Those thresholds do not guarantee enough energy to reach the destination.

Use **Use hover test** to return to the original rotor-only mission input. The **Hover-test speed (m/s)** field applies only to that mode; airport cruise speed is set in the planner.

## Battery live response

The dashboard now has **Battery kW** and **Charge %** tabs next to Height, Lift and Attitude. A top-level battery-charge card shows measured average remaining charge. Hover over it for remaining kWh, signed electrical power and the weakest pack. Raw telemetry lists each pack's percentage, voltage and current.

- Remaining charge is measured `battery_charge_watt_hr[i] / battery_watt_hr_max × 100`, aggregated across five packs. The weakest pack is also tracked. The installed ALIA model declares a 65,000 Wh capacity per pack; the app reads the live capacity dataref rather than assuming that constant.
- Electrical power is `sum(actual_voltage[i] × battery_amps[i]) / 1000`, in **kW**. The sign is preserved; the app does not assume every current reading represents discharge. This is electrical battery power, not motor shaft power or throttle percentage.
- Missing, invalid or stale battery instruments show **N/A**, not fabricated zeros. Battery instruments are optional for the core telemetry connection and hover test, while airport missions require fresh charge measurements for their reserve guard.
- Mission CSV and explainability JSONL logs include the added battery values; airport explanations also include pusher command, airspeed, support schedule and approach distance.

## Exact setup steps and your keyboard controls

These keys were checked against your installed `Output/preferences/X-Plane Keys.prf`, rather than assumed from the default keyboard layout.

| Action | Your saved key |
| --- | --- |
| Reduce lift-rotor power | Hold **F1** until lift motors 1–4 show 0.0% |
| Increase lift-rotor power | **F2** |
| Reduce pusher power | Hold **F4** until the pusher shows 0.0% |
| Increase pusher power | **F5** |
| Pause / unpause X-Plane | **P** |
| Toggle maximum wheel brakes | **V** |
| Forward cockpit view | **W** |
| External circle view | **Shift + 4** |
| Flight configuration | **Backtick** (the key usually below Esc) |
| Settings | **Ctrl + Backtick**, or the gear icon |

Use these keys with the **X-Plane window focused**. Some laptops require **Fn + F1**, **Fn + F4**, etc. to send function keys instead of brightness/volume actions. ALIA's lift and pusher controls are separate; reducing one does not idle the other. Do not use **F3** during setup: your saved mapping is full throttle.

To select and prepare the aircraft:

1. Launch `C:\X-Plane 12\X-Plane.exe`. On the main menu choose **New Flight**. If a flight is already running, open **Flight Configuration** from the top toolbar or with Backtick.
2. Search the aircraft list for **ALIA**, select **BETA Technologies ALIA-250**, and click the aircraft's **Customize** button. Enable the option to start with **engines running**, then return to Flight Configuration.
3. In **Location**, choose an airport, open **Customize**, and select a runway ground start. For the first test, choose somewhere visibly level with clear space above and around the aircraft. Use the hover-only route `0, 0, 15`; there is no need to travel across the airport yet.
4. In **Weather → Customize**, choose manual weather instead of downloaded real weather. Use clear skies and set wind speed, gusts, and turbulence to zero for every active wind layer. Apply the weather settings.
5. Click **Start Flight**. Leave simulation and ground-speed acceleration at their normal **1×** setting. Your shortcuts **Ctrl + T** and **Alt + T** change those speeds; avoid using them during this test.
6. Focus X-Plane and hold **F1**, then **F4**, until telemetry shows all five motor throttles at **0.0%**.
7. If telemetry shows batteries OFF, click ALIA's cockpit **START/power** button once. The aircraft's Lua script synchronizes all five batteries to that one button. Verify **Battery 1–5: ON**; do not repeatedly toggle the button after they are on.
8. If you have a joystick, gamepad, or throttle quadrant: open **Settings → Joystick**, select each connected device, move its throttle lever to identify the responding axis, and change the axis assignment to **None**. Repeat for any axis assigned to throttle, individual engine throttle, horizontal/vertical throttle, or collective. Record the original assignments so you can restore them after testing. If you use only keyboard and mouse, skip this step.
9. Close any previous CCAT flight window, original `StartAgents` run, or other external autopilot. In an IDE, stop its running process with the square Stop button. Launch just one `run-alia.cmd` instance.
10. Confirm the telemetry preflight message, unpause with **P** if necessary, and click **Start automatic flight** in CCAT. CCAT releases the wheel brake itself when the mission starts.

The raw telemetry window displays position, MSL altitude, height above terrain, ground/vertical/indicated speed, attitude, heading, actual motor throttles, battery switches, joystick overrides, mission stage, and target distance. Unknown values are shown as `--`; old data is marked **WAITING / STALE**. Its preflight line points out common setup problems. Closing telemetry does not stop the aircraft; **Show telemetry** in the flight window reopens it.

## Live explainability dashboard

Restart CCAT with `run-alia.cmd` to open the dashboard. It shares the existing aircraft connection; it does not open a second telemetry client. If you are running an automatic mission, pause X-Plane before closing the old CCAT window. The dashboard does not start a mission automatically.

The layout follows the supplied explainability examples, using measurements and controller evidence that this implementation actually has:

- **Live overview:** flight stage, height above terrain, ground speed, current target distance, route and measured path, and 120 seconds of height, lift and attitude history. The route is relative to the takeoff location and appears after a mission starts. It is a local engineering plot, not a geographic map or obstacle display.
- **Why this action:** the controller's current goal and the exact decomposition of lift power into the operator's base hover setting, vertical-speed correction and accumulated correction. Use **Explain control** to inspect lift, pitch, roll or yaw; attitude channels show proportional and angular-rate damping terms. Bars show signed percentage-point contributions to a normalized control value. Hover for term definitions.
- **What must happen next:** the actual distance, speed, altitude and dwell-time conditions used to advance the mission stage.
- **Exact controller trace:** recorded target errors, desired velocities, attitude targets, proportional and damping terms, lift limits and transition reasons. These are instrumented intermediate values from the executed control step, not an inferred explanation model. During monitoring there is no command explanation to fabricate; during pause the last trace is identified as a completed step.
- **Event timeline:** connection changes, stage transitions, runtime status changes and flight-application messages. Clear history affects the display only.
- **Operator guidance:** describes the observed condition and suggests review or pause/takeover. It does not silently change navigation sources, reroute, resume flight or alter the flight policy.

Runtime indicators have specific meanings:

| Indicator | Definition | Interpretation |
| --- | --- | --- |
| Sample age | Age of the oldest required telemetry value, including position | Above 250 ms produces caution; missing data or age over 1 s is unavailable. This is not network round-trip latency. |
| Configured-limit use | Largest fraction of the existing controller limits used: pitch/roll 35°, height +85/−5 m from start, radius 650 m | Above 75% produces caution; 100% reaches a configured guard. Before a mission starts only attitude limits can be evaluated. |
| Limit margin | `max(0, 1 − configured-limit use)` | Engineering reserve to those guards, not a probability of safety or calibrated robustness score. |
| Command mismatch | Largest absolute difference between lift/pusher commands and their actual throttle-handle positions | Shown in percentage points during automatic control; above 20 pp produces caution. These are handle positions, not measured thrust or motor RPM. The flight application's existing persistent-mismatch check remains separate. |
| Motion residual | Horizontal position change minus the previous velocity's predicted displacement | Metres of consistency residual between successive simulator samples. It is not GPS-versus-IMU/LiDAR disagreement and is not used as an autonomous sensor-switching rule. |

This integration receives one simulator position/motion stream. **Independent GPS, IMU and LiDAR feeds, GPS signal quality, learned policy confidence, calibrated anomaly probability and SHAP/LIME attributions are unavailable.** The dashboard labels those limits instead of generating illustrative confidence scores. The original Soar learning agent is still absent.

Explanations and event history are recorded at up to 5 Hz in `logs/alia/xai-*.jsonl`, with source metadata, measured aircraft values and executed controller terms. The original mission CSV logs remain available. Chart lines break across telemetry gaps rather than filling missing data with fabricated samples.

The dashboard and telemetry windows can be closed and reopened without stopping a flight. Use **Live dashboard**, **Show telemetry**, or **Pause & take over** in the flight-controls window as appropriate. Closing the main flight-controls window still performs the existing pause-and-release cleanup.

## First live test: hover and land

Replace the waypoint text with:

```text
0, 0, 15
```

This requests takeoff, a brief hold over the starting location, and landing at the same location. Use maximum speed `3` m/s and initial hover throttle `0.60` as **untuned starting settings**. The controller adjusts lift based on vertical-speed error; the correct value and gains still need flight-model validation.

Keep X-Plane's pause key available. Watch for uneven lift, increasing pitch or roll oscillation, excessive drift, or competing control inputs. If behavior diverges, use **Pause & take over** or pause X-Plane directly.

The app requests a pause and releases its joystick overrides on takeover. It leaves the latest motor settings in place so it does not deliberately cut lift while airborne. Check X-Plane is paused, establish manual controls and throttle settings, then unpause when ready. Do not resume the stopped automatic mission in mid-air; a new mission requires a stationary ground start.

## Waypoint route

Each nonempty line is:

```text
north_metres, east_metres, height_metres_above_start
```

Positive north/east move in those compass directions; negative numbers move south/west. Values are relative to the location captured when **Start automatic flight** is pressed. Height is relative to the aircraft's initial elevation, not sea level and not terrain clearance along the route.

The supplied short route is:

```text
0, 60, 15
40, 60, 15
0, 0, 15
```

It travels 60 m east, 40 m north, then returns. The program returns to the starting point and lands after the final waypoint even if the last waypoint is somewhere else. It keeps the initial aircraft heading, so some segments can be sideways or backwards under rotor power.

Limits enforced by the app:

- Waypoints within 500 m of the start.
- Waypoint heights from 10 to 60 m above the initial position.
- Requested speed from 1 to 8 m/s; use 3 m/s initially.
- Initial hover throttle from 0.25 to 0.85.
- Start only on the ground, stationary, near level, unpaused, ALIA confirmed, motors at idle, batteries on, and no existing joystick override.
- A 180-second timeout for an individual mission phase, and a 30-second no-liftoff check.
- Fault handling for missing telemetry, changed aircraft, battery-off state, persistent command/readback mismatch, and excessive attitude, height, or distance.

These limits do not detect buildings, terrain, traffic, battery endurance, or all aircraft failures. The route and landing area must be clear. For initial tests the landing point is the known starting location.

## Stop and recover

**Pause & take over** requests pause and releases the three joystick input overrides. Closing the window during flight requests the same cleanup. UDP commands are not acknowledged; verify the result in X-Plane.

If the app is forcibly terminated, use X-Plane's own pause key first. Then run:

```powershell
.\run-alia.cmd -Recover
```

This sends pause and joystick-release commands. It does not start a mission, unpause, or reset motor settings. A killed external process cannot guarantee automatic cleanup of simulator overrides.

## Logs and offline checks

Mission telemetry is written under `logs/alia/` as timestamped CSV files. Logs include mission phase, position, velocity, attitude, control commands, actual throttle readings, and distance to the target.

From a terminal in this folder:

```powershell
.\run-alia.cmd -Test       # no X-Plane required; local UDP fixture and simplified-plant tests
.\run-alia.cmd -BuildOnly  # compile only
.\run-alia.cmd -Probe      # request live telemetry; no flight-control writes
.\run-alia.cmd             # open flight controls + live explainability dashboard
.\run-alia.cmd -Telemetry  # also open raw telemetry on startup
.\run-alia.cmd -DashboardPreview # render a labelled offline UI preview; no X-Plane connection
.\run-alia.cmd -PlannerPreview # render a labelled offline airport-planner preview
.\run-alia.cmd -AirportIndexCheck # validate installed airport indexing; reads scenery only
```

The `.cmd` launcher uses an execution-policy override for its own PowerShell process only; it does not change your system execution policy.

## Technical references

The native packet formats are described in the documentation supplied with this installation:

```text
C:\X-Plane 12\Instructions\Exchanging Data with X-Plane.rtfd\TXT.rtf
C:\X-Plane 12\Resources\plugins\DataRefs.txt
C:\X-Plane 12\Resources\plugins\Commands.txt
```

- `RPOS`: double-precision geographic position, elevation, attitude, velocity, and angular rates at 20 Hz.
- `RREF`: pause, ground contact, airspeed, motor count, collective, actual throttle, joystick overrides, batteries, and ICAO code.
- `DREF`: collective, lift throttle indexes 0–3, pusher index 4 (idle in hover-test mode), joystick inputs and overrides, propeller mode, brakes, and stock autopilot disengagement.
- `CMND`: pause on takeover or a detected flight fault.

The original application's simulator connection still requires XPlaneConnect; only this new ALIA mode uses the native protocol.
