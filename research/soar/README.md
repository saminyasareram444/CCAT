# Soar supervisor contract

Status: design only. The original repository contains legacy Soar/JSoar integration and bundled libraries; the native ALIA mode does not currently run a Soar agent. Do not treat those legacy entry points as the new research supervisor.

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

Choose and pin a Soar/JSoar runtime when implementing this adapter. The old Maven dependencies and bundled JARs are not proof that this new supervisor is installed, compatible or active.

References: [Soar architecture](https://soar.eecs.umich.edu/soar_manual/02_TheSoarArchitecture/), [reinforcement learning tutorial](https://soar.eecs.umich.edu/tutorials/soar_tutorial/06/), [episodic memory tutorial](https://soar.eecs.umich.edu/tutorials/soar_tutorial/08/).
