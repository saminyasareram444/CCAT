# Pilot authority specification, draft v1

## Current implementation boundary

The braking research observer has **no flight-control authority**. It receives value-only observations and has no NativeLink, controller handle, destination setter or actuator API. Its live output is a conditional point prediction, not an instruction. Its logger runs on a bounded asynchronous queue; recording errors stay within the observer. Soar, learned energy decisions, episodic retrieval and automatic rerouting are not implemented in this milestone.

The inherited controller still follows its existing approved mission/start workflow. The current Pause & take over function requests simulator pause and joystick-override release through UDP. It is not an acknowledged, uninterrupted in-flight transfer protocol. Do not equate sending those packets with confirmed pilot ownership.

## Proposed roles

| Mode | Automated responsibility | Pilot responsibility | Status |
|---|---|---|---|
| Observation | Monitor, record and show experimental predictions | Conduct/stop the existing mission | Implemented for braking |
| Recommendation | Explain checked choices with uncertainty and alternatives | Accept, reject, defer or request alternatives | Future Soar milestone |
| Approved execution | Execute exactly the selected, checked plan | Approve a particular plan; supervise | Future milestone |
| Bounded contingency execution | Apply an explicitly authorized, validated fallback within its envelope | Define delegation and retain specified override | Not enabled |

Selecting a mission option and manually controlling the aircraft are different responsibilities. While a pilot considers a decision, a validated flight function must continue appropriate control. No generic hover/hold action is assumed feasible: energy, height, speed, terrain and procedure constraints determine whether a candidate fallback is usable.

## Required decision record

Each future proposal needs: proposal ID and version, mission ID, situation timestamp, measured evidence IDs/validity, prediction/model IDs and support status, episode IDs if any, candidate plans and constraints, selected recommendation, expiry/response deadline and its basis, pilot response and timestamp, authorization used, command ID, acknowledgement/observed completion, current owner and resulting outcome.

Approval applies to the exact proposal/route version. An acknowledgement of an alert is not approval to execute a changed destination. A rejected proposal stays rejected until a new proposal is explicitly considered. No response is a separate event, not implicit agreement.

## Requirements and planned verification

| ID | Requirement | Evidence / status |
|---|---|---|
| AUTH-01 | Research observer outputs cannot write controls or change a mission. | Value-only interface, source review, local UDP fixture verifies zero writes/commands during observer checks. Implemented. |
| AUTH-02 | A destination/procedure change requires unexpired approval for its exact version, or a separately enabled applicable prior authorization. | Future authority-state-machine/model-checker property. No such learned change path exists now. |
| AUTH-03 | Learned preferences cannot suppress mandatory critical warnings or modify fixed authority constraints. | Future Soar/alert properties; no learned alert suppression currently integrated. |
| AUTH-04 | Reject, defer and no-response events have explicit state transitions; no repeated command is issued as a new action while acknowledgement is pending. | Future timed protocol and duplicate/delay/reordering tests. |
| AUTH-05 | The display distinguishes proposal, pilot approval, command sent, observed execution and completion; it shows current control ownership. | Future HMI and communication integration. |
| AUTH-06 | Missing/stale/out-of-support predictions cannot be presented as qualified evidence. | Implemented observer gates stale/paused/phase/policy/nonfinite/support; all uncertainty remains explicitly unqualified. Range checks alone do not establish model competence. |
| AUTH-07 | Pilot rejection or no response cannot create broader autonomous permission. | Future protocol invariant; contingency delegation remains disabled. |
| AUTH-08 | Manual takeover has a specified acknowledgement and maximum transfer latency, and autonomous actuator writes stop according to that protocol. | Future implementation. Current pause/release requests are not this guarantee. |
| AUTH-09 | Observer/model/disk failures do not become controller faults or cause an autonomous maneuver. | Observer exception boundary, bounded log queue and disk-failure regression test. Implemented. |
| AUTH-10 | New learned behavior/model versions are evaluated, versioned and reviewed before authority increases. | Pilot model ID, frozen split, source hashes and explicit qualification flag implemented; promotion procedure remains future work. |

## Timing and nonresponse

Derive response deadlines from the particular contingency, aircraft dynamics, communication latency and fallback envelope. Do not copy a universal five-second timeout from a different sensor-switching scenario. Model prompt, late, conflicting and absent pilot responses, including loss of communication. A timeout should execute only its defined previously authorized behavior; otherwise it escalates and follows the established baseline/fallback procedure without inventing a new authorization.

## Formal-assurance plan

Model the human, HMI, supervisor, authority manager, transport and flight controller as separate components. Use AADL/AGREE contracts and a supported Soar-to-nuXmv model when implemented. Keep the implementation interfaces and formal properties synchronized. Check reachable triggers and counterexamples so that an unreachable condition does not make a property appear to prove useful behavior. Include network delay and missing acknowledgements explicitly.

Formal authority/protocol properties are conditional on stated component and environment assumptions. Prediction error, uncertainty coverage, fallback dynamics and human response require separate measured evidence. No new formal proofs, full-vehicle safety guarantee or certification claim are supplied by this milestone.

## Research lineage

- [Bhattacharyya et al., 2021](https://loonwerks.com/publications/pdf/bhattacharyya2021fmas.pdf): human-machine role allocation, architectural contracts and Soar verification.
- [Narayan et al., 2023](https://loonwerks.com/publications/pdf/narayan2023syscon.pdf): learning-enabled preferences and agent assurance.
- [Ganeriwala et al., 2025](https://arxiv.org/abs/2501.18506): learning-aware HMI and adaptive autonomy.

This specification is a project proposal informed by those papers; it does not imply their verified properties automatically apply to ALIA or this controller.
