import json

import pytest

from data_audit_agent.approval import ApprovalValidationError, read_approval_decision
from data_audit_agent.checkpoint import FileCheckpointStore, StaleCheckpointError, task_fingerprint
from data_audit_agent.graph import resume_sidecar_workflow, run_sidecar_workflow
from data_audit_agent.state import AgentState
from data_audit_agent.tools import CommandResult, JavaCliToolRunner


def test_checkpoint_store_writes_task_fingerprint_and_pending_node(tmpdir):
    task_path = tmpdir.join("task.yaml")
    task_path.write("task:\n  name: approval_fixture\n")
    checkpoint_path = tmpdir.join("agent_checkpoint.json")
    state = AgentState(task_path=str(task_path), output_dir=str(tmpdir))
    state.set_locked_facts({"status": "UNSTABLE_BOUNDARY"})

    checkpoint = FileCheckpointStore(str(checkpoint_path)).save(state, pending_node="deterministic_check")

    raw = json.loads(checkpoint_path.read())
    assert checkpoint.task_fingerprint == task_fingerprint(str(task_path))
    assert raw["pending_node"] == "deterministic_check"
    assert raw["task_fingerprint"] == task_fingerprint(str(task_path))
    assert raw["state"]["locked_facts"]["status"] == "UNSTABLE_BOUNDARY"


def test_checkpoint_resume_rejects_stale_task_fingerprint(tmpdir):
    task_path = tmpdir.join("task.yaml")
    task_path.write("task:\n  name: before\n")
    store = FileCheckpointStore(str(tmpdir.join("agent_checkpoint.json")))
    state = AgentState(task_path=str(task_path), output_dir=str(tmpdir))
    store.save(state, pending_node="deterministic_check")

    task_path.write("task:\n  name: after\n")

    with pytest.raises(StaleCheckpointError):
        store.load_for_task(str(task_path))


def test_approval_decision_rejects_locked_fact_overrides(tmpdir):
    decision_path = tmpdir.join("approval_decision.json")
    decision_path.write(json.dumps({
        "request_id": "req-1",
        "decision": "approved",
        "status": "CONSISTENT",
    }))

    with pytest.raises(ApprovalValidationError):
        read_approval_decision(str(decision_path))


def test_workflow_pauses_and_writes_approval_request_before_check(tmpdir):
    reports_dir = tmpdir.mkdir("reports")
    output_dir = tmpdir.mkdir("agent")
    task_path = tmpdir.join("task.yaml")
    task_path.write("output:\n  dir: {0}\n".format(str(reports_dir).replace("\\", "\\\\")))
    commands = []

    def executor(command):
        commands.append(command[3])
        if command[3] == "plan":
            return CommandResult(0, json.dumps({"scale_class": "LARGE"}), "")
        raise AssertionError("approval gate should pause before check")

    runner = JavaCliToolRunner(jar_path="data-audit.jar", executor=executor)
    state, summary = run_sidecar_workflow(
        task_path=str(task_path),
        jar_path="data-audit.jar",
        output_dir=str(output_dir),
        runner=runner,
        require_approval=True,
    )

    request_path = output_dir.join("approval_request.json")
    request = json.loads(request_path.read())

    assert commands == ["plan"]
    assert summary["status"] == "WAITING_FOR_APPROVAL"
    assert state.workflow_status == "WAITING_FOR_APPROVAL"
    assert output_dir.join("agent_checkpoint.json").check()
    assert request["approval_scope"] == "execution_only"
    assert request["consistency_claim"] is False
    assert request["pending_node"] == "deterministic_check"


def test_resume_with_approved_decision_runs_deterministic_check(tmpdir):
    reports_dir = tmpdir.mkdir("reports")
    output_dir = tmpdir.mkdir("agent")
    task_path = tmpdir.join("task.yaml")
    task_path.write("output:\n  dir: {0}\n".format(str(reports_dir).replace("\\", "\\\\")))
    commands = []

    def plan_executor(command):
        return CommandResult(0, json.dumps({"scale_class": "LARGE"}), "")

    run_sidecar_workflow(
        task_path=str(task_path),
        jar_path="data-audit.jar",
        output_dir=str(output_dir),
        runner=JavaCliToolRunner(jar_path="data-audit.jar", executor=plan_executor),
        require_approval=True,
    )

    request = json.loads(output_dir.join("approval_request.json").read())
    output_dir.join("approval_decision.json").write(json.dumps({
        "request_id": request["request_id"],
        "decision": "approved",
        "approver": "qa",
        "reason": "Run deterministic check.",
    }))

    def resume_executor(command):
        commands.append(command[3])
        if command[3] == "check":
            reports_dir.join("report.json").write(json.dumps({
                "result": {
                    "status": "CONSISTENT",
                    "proof_mode": "GLOBAL_CHECKSUM",
                    "confidence": "HIGH",
                    "suspect_slices": [],
                    "diff": {"consistent": True},
                }
            }))
            return CommandResult(0, "status=CONSISTENT\n", "")
        raise AssertionError("resume should continue at deterministic check")

    state, summary = resume_sidecar_workflow(
        checkpoint_path=str(output_dir.join("agent_checkpoint.json")),
        approval_decision_path=str(output_dir.join("approval_decision.json")),
        runner=JavaCliToolRunner(jar_path="data-audit.jar", executor=resume_executor),
    )

    trace_lines = [
        json.loads(line)
        for line in open(str(output_dir.join("agent_trace.jsonl")), "r").read().splitlines()
    ]

    assert commands == ["check"]
    assert state.workflow_status == "COMPLETED"
    assert summary["deterministic"]["status"] == "CONSISTENT"
    assert any(line.get("decision") == "approved" and line.get("outcome") == "resumed" for line in trace_lines)


def test_resume_with_rejected_decision_stops_without_check(tmpdir):
    output_dir = tmpdir.mkdir("agent")
    task_path = tmpdir.join("task.yaml")
    task_path.write("task:\n  name: rejected\n")

    def plan_executor(command):
        return CommandResult(0, json.dumps({"scale_class": "LARGE"}), "")

    run_sidecar_workflow(
        task_path=str(task_path),
        jar_path="data-audit.jar",
        output_dir=str(output_dir),
        runner=JavaCliToolRunner(jar_path="data-audit.jar", executor=plan_executor),
        require_approval=True,
    )
    request = json.loads(output_dir.join("approval_request.json").read())
    output_dir.join("approval_decision.json").write(json.dumps({
        "request_id": request["request_id"],
        "decision": "rejected",
        "approver": "qa",
        "reason": "Too expensive for this run.",
    }))

    def reject_executor(command):
        raise AssertionError("rejected approval must not invoke Java CLI")

    state, summary = resume_sidecar_workflow(
        checkpoint_path=str(output_dir.join("agent_checkpoint.json")),
        approval_decision_path=str(output_dir.join("approval_decision.json")),
        runner=JavaCliToolRunner(jar_path="data-audit.jar", executor=reject_executor),
    )

    assert state.workflow_status == "STOPPED_BY_APPROVAL"
    assert summary["status"] == "STOPPED_BY_APPROVAL"
    assert not any(result.command_type == "check" for result in state.tool_results)
