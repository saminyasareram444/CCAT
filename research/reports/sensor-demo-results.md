# Synthetic sensor-warning demonstration validation

Date: 2026-10-28. Runtime: JSoar 4.1.0, JDK 25. Source: research branch, `sensor-warning.soar` v1. No X-Plane session was started for these checks.

| Check | Result |
|---|---|
| Actual JSoar warning-learning suite | 265 checks passed |
| Existing Java flight, dashboard, protocol and braking-shadow suite | 7,996 checks passed |
| Existing Python research pipeline | 17 tests passed |
| Tablet visual previews | Input and preference sections rendered at 700×1000, 540×680, 1000×700 |

The JSoar tests evaluate all 64 combinations of normal/low/high/safety across three isolated agents. They also cover threshold boundaries, unavailable inputs, positive and negative reward, no duplicate credit, frozen evaluation, working-memory resets retaining preferences, per-sensor isolation, invalid input, and correlated/expired pilot replies. They do not measure detection accuracy, pilot workload, operational safety or physical sensor fidelity.

An exact seeded example is checked: GPS/low WARN starts at Q=.20; one negative pilot rating produces Q=-.16 with one update (alpha .3, gamma 0); frozen evaluation then chooses QUIET (Q=0). A positive rating in a fresh high-band trial increases WARN Q from .20 to .44. A safety-band choice remains MANDATORY-WARNING regardless of preferences or pilot ratings.

One inherited regression test initially failed because it reused a timestamped fake-aircraft snapshot after UI rendering exceeded the one-second freshness window. The test now reads the UDP fixture's latest snapshot before the airport/battery checks. The complete Java suite then passed; the simulator freshness check and controller behavior were not altered.

The research implementation is an optional, synthetic, session-only warning-choice lab. Real independent GPS/IMU/LiDAR feeds, a mission-level cognitive supervisor, learned energy decisions and episodic retrieval are not implemented by this milestone. See [the demo guide](../soar/SENSOR_DEMO.md).
