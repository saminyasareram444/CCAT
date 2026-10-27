# Episodic-memory design

Status: no episode store or retrieval code is implemented yet.

Each proposed episode should include a mission/scenario ID, timestamped context, available measurements and validity, model versions, candidate actions, selected recommendation, pilot response and measured outcome. Distinguish simulator observations from inferred predictions and human labels.

Retrieve using decision-relevant similarity: flight phase, motion/energy state, approach context and available wind. Record why the episode was retrieved and whether its conditions differ from the current flight. A similar past success is evidence to evaluate, not a guarantee of the current outcome.

Training/test isolation applies to memory too: never populate retrieval memory with future outcomes from the held-out test flight. Compare a no-memory supervisor with the same supervisor using a fixed, documented prior-flight memory bank.

Store runtime episodes locally under `research/local/episodes/`. The copied historical logs are raw data, not automatically valid cognitive episodes.
