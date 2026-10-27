# Pilot braking experiment

Model `7d5c7cb5092e568f`; status **PILOT_ONLY / NO CONTROL AUTHORITY**.

Targets: remaining ground path and reported controller seconds to rotor capture (GS <10 m/s and forward IAS <30 kt), conditional on the unchanged current approach procedure. This does not predict a full stop, touchdown, arbitrary cruise braking, or an alternative maneuver.

Only three route/scenario groups exist. Near-repeat flights stay together. Training uses one group; validation one; test one. The model and feature scaling are fit on training only. Ridge selection and residual bands use validation only. Test outcomes are used solely for final evaluation.

## Results

| Partition | Flights/groups | Learned distance MAE (m) | Kinematic distance MAE (m) | Learned time MAE (s) | Kinematic time MAE (s) | Phase-clock time MAE (s) | OOD rows |
|---|---|---:|---:|---:|---:|---:|---:|
| train | 2/1 | 7.72 | 333.66 | 0.08 | 11.47 | 0.00 | 0/73 |
| validation | 1/1 | 25.75 | 327.14 | 1.18 | 11.33 | 1.20 | 1/36 |
| test | 2/1 | 54.23 | 323.67 | 2.60 | 11.37 | 2.65 | 0/70 |

The kinematic comparator uses (v^2 - 10^2)/(2 * 0.35) metres and (v - 10)/0.35 seconds. It shares the existing controller's deceleration assumption but is not the entire controller trigger. The actual trigger max(2200, v^2/0.7 + 8v + 500) includes buffers and a floor; treating its difference from observed travel as a model error would conflate a trigger policy with a stopping prediction.

The post-hoc phase-clock comparator uses mean training approach duration minus current approach age. It was added to check whether the time model mainly learns the repeated procedure's timing, and it is not used to choose model coefficients or hyperparameters. The learned time estimate does not demonstrate an advantage over this simple timing comparator on the pilot test group. These retrospective results are exploratory, not a preregistered confirmatory study.

Validation residual bands: +/- 34.71 m, +/- 1.29 s. These are empirical residual bands from one validation scenario, **not calibrated probabilities**. Evaluation JSON reports measured test coverage, OOD rate, bias and maximum error.

**Qualification failed:** on the test group, distance residual-band coverage is 2.9%, and time coverage is 0.0%. Point predictions may be inspected experimentally, but these bands must not be displayed as trusted safety bounds. Being inside per-feature training ranges is not evidence of correct uncertainty.

No flight-performance improvement is established by this offline prediction comparison. Replay uses observed execution of the original procedure. Fresh scenario collection and shadow validation are required before recommendations influence control.
