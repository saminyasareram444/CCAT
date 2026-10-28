# Soar supervisor contract

Status: the **mission supervisor** below remains design only. A separate, runnable [synthetic sensor-warning demonstration](SENSOR_DEMO.md) now uses real JSoar 4.1.0 RL with pilot ratings in the tablet Sensors tab. It has no mission, predictor, destination or flight-control interface. The native ALIA mode runs it only through the explicit research launcher. Do not treat the original legacy agents as the new research supervisor.

## Proposed working memory

- Mission identifier, phase, goal and candidate landing sites.
- Measured aircraft state with timestamps, units and data-validity flags.
- Learned energy/braking predictions, model versions and evaluated uncertainty.
- Relevant retrieved episodes with similarity, outcomes and provenance.
- Pilot response and recommendation history.

## Proposed operators

`recommend-continue`, `recommend-early-approach`, `recommend-review-alternate`, `request-pilot-verification`.

Initially every output is a structured recommendation record: operator, evidence IDs, prediction versions, alternatives considered and a reason trace. It must not write motor/joystick datarefs or change the active destination. The pilot's current alternate-route preview remains a preview.

## Learning and memory

Soar uses production knowledge. Re-encoding existing rules is not a data-driven policy by itself. Begin with explicit task knowledge and learned numerical predictions; add episodic retrieval, then optionally reinforcement learning of operator preferences after defining outcomes/rewards and evaluation splits.

The warning demo pins JSoar 4.1.0 and support-library SHA-256 checksums in `../config/soar-runtime-lock.json`. The mission supervisor still needs its own implementation and validation. Its future inputs must preserve source, timestamp, unit, and validity boundaries.

References: [Soar architecture](https://soar.eecs.umich.edu/soar_manual/02_TheSoarArchitecture/), [reinforcement learning tutorial](https://soar.eecs.umich.edu/tutorials/soar_tutorial/06/), [episodic memory tutorial](https://soar.eecs.umich.edu/tutorials/soar_tutorial/08/).
