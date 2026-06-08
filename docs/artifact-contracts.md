# DataAudit Artifact Contracts

This document defines the product artifact boundary between the deterministic
Java CLI, AI Copilot sidecars, and the future LangGraph Agent sidecar.

## Trust Boundary

DataAudit deterministic artifacts are the source of truth. AI and Agent
artifacts can explain, route, recommend, and render, but they must not rewrite
deterministic audit facts.

Locked deterministic facts:

- `result.status`
- `result.proof_mode`
- `result.confidence`
- `result.root_cause`
- `result.suspect_slices`
- `result.diff`

If an AI sidecar or Agent hypothesis conflicts with a locked fact, the locked
fact wins.

## Deterministic Artifacts

`report.json` is the primary acceptance artifact. New reports include additive
artifact metadata while preserving the existing report shape:

```json
{
  "artifact_version": "1",
  "artifact_type": "report",
  "producer": "data-audit-cli",
  "schema_version": "data-audit-report-v1",
  "run_id": "run-id",
  "task_name": "orders_reconcile",
  "created_at": "2026-06-08T00:00:00Z",
  "plan": {},
  "result": {},
  "evidence": {}
}
```

Compatibility rule: legacy reports without artifact metadata must remain
readable by `data-audit report show`.

Other deterministic files remain part of the audit run output:

- `manifest.json`
- `suspect_slices.csv`
- `row_diff_sample.csv`
- `state.db`

## AI Sidecar Artifacts

AI sidecars are auxiliary. They do not replace `report.json` and do not change
the deterministic process exit code.

JSON sidecars include artifact metadata with `producer=data-audit-ai`:

- `table_profile.json` -> `artifact_type=table_profile`
- `ai_audit_plan.json` -> `artifact_type=ai_audit_plan`
- `root_cause_analysis.json` -> `artifact_type=root_cause_analysis`
- `repair_plan.json` -> `artifact_type=repair_plan`
- `answer.json` -> `artifact_type=answer`

Markdown sidecars are human-readable only:

- `profile_review.md`
- `ai_audit_report_<template>.md`

## Future Agent Artifacts

The LangGraph sidecar should exchange files through this contract rather than
reading internal Java objects.

Reserved Agent artifact names:

- `agent_state.json`
- `approval_request.json`
- `approval_decision.json`
- `tool_invocation.json`
- `tool_result.json`
- `agent_trace.jsonl`

Agent rules:

- The Agent may read deterministic locked facts.
- The Agent may store hypotheses and routing decisions separately.
- The Agent must not overwrite deterministic locked facts.
- Human approval can approve execution strategy, not data consistency.

## Fixture Set

Contract fixtures live under `examples/artifact-contracts/`:

- `jdbc-jdbc`
- `jdbc-iceberg`
- `iceberg-jdbc`
- `trino-query-plane`

Tests parse these fixtures to verify metadata, locked facts, and legacy report
compatibility.
