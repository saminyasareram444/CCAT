# Learned prediction contracts

No predictor is implemented or trained in this version.

## Energy prediction

- Candidate inputs: flight phase, route distance, altitude change, air/ground speed, lift/pusher commands, measured battery energy/voltage/current, and available wind measurements.
- Outputs: energy required for the defined remaining trajectory and predicted arrival charge, with uncertainty evaluated on held-out flights.
- Derive phase energy from timestamped electrical measurements and verify the current sign convention. Never assume a negative current necessarily means charging without checking the installed simulator model.
- Avoid leaking the eventual arrival state or future measurements into current-time inputs.

## Braking prediction

- Candidate inputs: current motion/attitude, phase, recent commands and response, altitude, and available wind.
- Outputs: time and distance to reach a defined low-speed/capture condition **under a specified braking procedure**.
- The stopping outcome depends on the control maneuver. A model trained on one policy does not automatically predict the outcome under a different policy.
- Keep failed, interrupted and incomplete maneuvers explicitly labelled rather than assigning a fabricated stopping distance.

## Data audit

The existing CSV and JSONL formats have changed during development. Inspect headers and source metadata instead of assuming every flight has weather, battery measurements, control history or the same controller configuration. Missing independent GPS/IMU/LiDAR feeds are not real sensor observations. Existing controller demonstrations can be used to learn response models, but cloning their commands mainly imitates the existing policy.

Start with transparent regression baselines. Compare any neural sequence model against them before adding complexity. Fit preprocessing on training flights only and split by whole flight / scenario.
