# DataAudit Agent Sidecar

`data-audit-agent` is an optional Python sidecar for orchestrating DataAudit
through stable CLI and artifact contracts. The Java CLI remains the
deterministic source of truth; the Agent only records workflow state, tool
results, non-authoritative hypotheses, and `agent_trace.jsonl`.

## Install For Local Development

From the repository root:

```powershell
cd data-audit-agent
python -m pip install -e .
```

LangGraph is optional. Install it only when you want to run with the real
LangGraph runtime:

```powershell
python -m pip install -e ".[langgraph]"
```

Without LangGraph installed, the package runs the same nodes sequentially so
local tests and demos do not depend on external Agent packages.

## Locate `data-audit.jar`

The sidecar resolves the Java CLI jar in this order:

1. `--jar <path>` command argument.
2. `DATAAUDIT_JAR` environment variable.
3. `data-audit-cli/target/data-audit.jar` under the repository root.

Build the jar before running a real workflow:

```powershell
.\mvnw.cmd -q -pl data-audit-cli -am -DskipTests package
```

## Fixture Demo

Create the local SQLite fixture tasks first:

```powershell
.\scripts\verify-local-sqlite.ps1
```

Then run the sidecar against one fixture task:

```powershell
$env:PYTHONPATH = "data-audit-agent"
python -m data_audit_agent --fixture consistent_small --jar data-audit-cli\target\data-audit.jar --output-dir .tmp\agent-sidecar\consistent_small
```

The demo invokes:

- `java -jar data-audit.jar plan -f <task.yaml>`
- `java -jar data-audit.jar check -f <task.yaml>`

It reads the resulting `report.json`, copies locked deterministic facts into
Agent state, and writes `.tmp/agent-sidecar/consistent_small/agent_trace.jsonl`.

## Approval Checkpoint Demo

To pause before deterministic execution and emit approval artifacts:

```powershell
$env:PYTHONPATH = "data-audit-agent"
python -m data_audit_agent --fixture consistent_small --jar data-audit-cli\target\data-audit.jar --output-dir .tmp\agent-sidecar\approval --require-approval
```

This writes:

- `.tmp\agent-sidecar\approval\agent_checkpoint.json`
- `.tmp\agent-sidecar\approval\approval_request.json`
- `.tmp\agent-sidecar\approval\agent_trace.jsonl`

Create an approval decision:

```json
{
  "request_id": "<copy from approval_request.json>",
  "decision": "approved",
  "approver": "qa",
  "reason": "Proceed with deterministic check."
}
```

Save it as `.tmp\agent-sidecar\approval\approval_decision.json`, then resume:

```powershell
python -m data_audit_agent --resume --output-dir .tmp\agent-sidecar\approval
```

Use `"decision": "rejected"` to stop without running `check`. Approval decisions
may approve execution only; they cannot set `status`, `proof_mode`,
`confidence`, `root_cause`, `suspect_slices`, or `diff`.

## Exit Behavior

Java `check` exit codes `0`, `1`, and `5` are deterministic audit outcomes and
are recorded as successful tool completions. Java execution/configuration
failures are recorded as tool failures. The sidecar demo exits `4` if any tool
wrapper fails and still leaves trace output for debugging.

When approval is required, the demo exits `3` after writing the checkpoint and
approval request. If approval is rejected during resume, it exits `7` without
running the deterministic check.

The Agent never converts a Java CLI failure into an AI consistency conclusion.
