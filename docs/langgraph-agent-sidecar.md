# LangGraph Agent Sidecar Design

The Agent sidecar is a productization layer around DataAudit, not a replacement
for the Java audit engine. It shells out to Java CLI commands and exchanges only
versioned files described in `docs/artifact-contracts.md`.

## Runtime Boundary

Java owns deterministic audit facts:

- `result.status`
- `result.proof_mode`
- `result.confidence`
- `result.root_cause`
- `result.suspect_slices`
- `result.diff`

The Python sidecar may copy these fields into Agent state, but it must not
rewrite them. AI/RAG output is stored as non-authoritative hypotheses. If a
hypothesis conflicts with deterministic `report.json`, the deterministic fact
wins and the hypothesis is marked non-authoritative.

## Components

- `data_audit_agent.state`: Agent state, artifact paths, locked facts,
  hypotheses, tool results, and trace metadata.
- `data_audit_agent.tools`: wrappers for `plan`, `check`, `diff`, and
  `report show` using `java -jar data-audit.jar`.
- `data_audit_agent.graph`: LangGraph-compatible nodes for profile/planning,
  deterministic check, RAG analysis, and Agent summary. When LangGraph is not
  installed, the same nodes run sequentially.
- `data_audit_agent.checkpoint`: file-backed checkpoint persistence with task
  fingerprint validation.
- `data_audit_agent.approval`: approval request writer, approval decision
  reader, and locked-fact override validation.
- `data_audit_agent.trace`: JSONL workflow trace writer.
- `data_audit_agent.demo`: local CLI for fixture and task-file workflows.

## Tool Contract

Each wrapper records:

- command type
- full command argv
- stdout and stderr
- Java exit code
- parsed output when structured output is available
- artifact paths used by the invocation
- failure reason when the Java process did not complete as a deterministic
  audit outcome

For `check` and `diff`, Java exit codes `0`, `1`, and `5` are deterministic
outcomes. Execution/configuration errors remain failures and are not converted
into AI conclusions.

## Trace Contract

`agent_trace.jsonl` is append-only. Each line includes:

- `timestamp`
- `node`
- `event_type`
- `command_type`
- `artifact_paths`
- `exit_code`
- `succeeded`
- `failure_reason`

This gives operators a stable way to debug sidecar orchestration without
parsing internal Python objects.

## Checkpoint And Approval

High-risk or expensive execution can pause after planning and before
`deterministic_check`. The sidecar writes `agent_checkpoint.json` with the task
fingerprint, pending node, artifact paths, locked facts known so far, and Agent
state. It also writes `approval_request.json` with `approval_scope` set to
`execution_only` and `consistency_claim=false`.

`approval_decision.json` controls workflow flow:

- `decision=approved`: resume from the checkpoint and run the pending
  deterministic Java CLI tool if the current task fingerprint still matches.
- `decision=rejected`: stop the workflow and write a stopped trace event without
  running `check`.

Approval decisions are rejected if they attempt to set deterministic locked
facts such as `status`, `proof_mode`, `confidence`, `root_cause`,
`suspect_slices`, or `diff`.

## Acceptance Path

The first product acceptance path is intentionally narrow:

1. Build `data-audit-cli/target/data-audit.jar`.
2. Run `scripts/verify-local-sqlite.ps1` to create JDBC/JDBC fixture tasks.
3. Run `python -m data_audit_agent --fixture consistent_small ...`.
4. Verify the sidecar produced `agent_trace.jsonl`.
5. Verify Agent summary copies deterministic `status`, `proof_mode`,
   `confidence`, and `suspect_slices` from `report.json`.

For approval behavior, run the fixture with `--require-approval`, write
`approval_decision.json`, then resume with `--resume`. Verify the trace records
the approval request and either the resumed or stopped outcome.

Future changes can add production RAG retrieval on top of this contract without
depending on unstable JSON fields.
