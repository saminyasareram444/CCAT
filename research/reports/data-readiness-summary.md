# Data-readiness snapshot: braking pilot v1

This aggregate report accompanies the first implemented research milestone. Full inventories, hashes, field-level validity, journal events and raw measurements remain local. Reproduce the audit with `run-research.cmd audit`.

## Inventory

| Item | Count |
|---|---:|
| Flight CSV files | 25 |
| CSV control-step records | 129478 |
| Dashboard journals | 15 |
| Journal sample records | 70892 |
| Flights ending with reported LANDED | 16 |
| Complete approach-to-capture sequences | 6 |
| Current-policy pilot-fit flights | 5 |
| Conservative route/scenario groups in fitting data | 3 |

## Findings

- Five early CSVs have 21 columns; twenty have 26. The early schema lacks the later airspeed, commanded-pusher and battery columns. Field availability is audited per file rather than imputed from newer records.
- Sixteen files report a landed outcome. Nine end earlier and retain an incomplete/review-required label. Journal sessions contain airspeed faults and manual-pause/takeover events, but historical run identifiers are absent, so session events are not silently assigned as a definitive cause to every CSV.
- One complete airport approach uses the legacy procedure and is excluded from the current-policy fit. Five complete approaches have a matching current trace gate and approach fields. This is compatibility evidence, not proof of an identical controller build.
- Repeat routes are grouped: two flights in training, one in validation, and two in test. There is only one route/scenario group in each partition. Many rows do not create many independent scenarios.
- 40910 journal samples contain a retained computed trace while paused or inactive. They are not used as fresh independent control examples. Training labels come from contiguous CSV control steps.
- Five journal files include aircraft weather keys, but CSVs do not record weather/configuration. Optional-field freshness and run provenance remain limitations; these records are not independent GPS/IMU/LiDAR observations.
- The geometric-path versus velocity-integral ratios for the five fitted approaches are about 0.998. This is a consistency check, not an independently recorded simulator clock.
- Negative signed voltage-current integral accompanies declining measured charge. The five fitting flights lose approximately 35.7, 35.7, 35.4, 35.9 and 35.8 percentage points, respectively. Their signed VI integrals agree approximately with the recorded pack-capacity/charge change. This supports a depletion sign convention for these logs only.
- Historical logs lack explicit controller commit, simulator/aircraft version, complete scenario settings and pilot/intervention identity. New-run sidecars now record source/commit provenance and explicitly unconfirmed operator settings. Live scenario collection is still required.

## Current baseline approach outcomes

These are descriptive outcomes under the inherited controller, not outcomes obtained by the learned predictor. Capture means the rotor-entry speed gate, not stabilized hover. Landing error is the logged controller range, not an independently surveyed surface result.

| Flight | Approach to capture (s) | Distance to destination at capture (m) | Maximum along-track overshoot (m) | Reported touchdown error (m) |
|---|---:|---:|---:|---:|
| 20261022-182752-257.csv | 72.40 | 2444.9 | 0.95 | 1.707 |
| 20261023-103732-617.csv | 72.40 | 2444.6 | 0.97 | 1.714 |
| 20261023-111455-746.csv | 71.20 | 2386.9 | 2.68 | 1.714 |
| 20261023-113943-894.csv | 69.75 | 2329.2 | 0.96 | 1.714 |
| 20261026-185034-267.csv | 69.75 | 2326.7 | 0.95 | 1.717 |

The current controller reaches rotor capture roughly 2.3-2.4 km before the destination in these records. That helps explain the long rotor-powered final segment. The observer does not change this behavior, and a predictor conditioned on the present approach cannot by itself justify a later cruise-to-approach trigger.

## Readiness decision

**PILOT_DATA_ONLY.** The dataset supports building and checking the end-to-end research pipeline. It does not support calibrated operational uncertainty, a learned takeover policy, a validated arbitrary braking maneuver, or a whole-aircraft safety claim. The initial model improves a fixed-deceleration point comparator, but its empirical residual bands fail held-out coverage. See [pilot results](braking-pilot-results.md).

Next data collection should follow the [baseline protocol](../experiments/approach-baseline.md), preserve failures as censored outcomes, record the configuration and include new route/wind/speed conditions. Use a newly versioned split before subsequent model development, because the v1 test group has now been examined.
