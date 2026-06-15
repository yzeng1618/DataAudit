from pathlib import Path

from data_audit_agent.approval import ApprovalValidationError, read_approval_decision, write_approval_request
from data_audit_agent.checkpoint import FileCheckpointStore
from data_audit_agent.state import AgentState
from data_audit_agent.tools import JavaCliToolRunner
from data_audit_agent.trace import JsonlTraceWriter


try:
    from langgraph.graph import END, StateGraph
except Exception:
    END = None
    StateGraph = None


def profile_planning_node(state, runner=None, trace_writer=None):
    runner = runner or JavaCliToolRunner(jar_path=state.jar_path)
    result = runner.plan(state)
    if trace_writer is not None:
        trace_writer.write_tool_result("profile_planning", result)
    return state


def deterministic_check_node(state, runner=None, trace_writer=None):
    runner = runner or JavaCliToolRunner(jar_path=state.jar_path)
    result = runner.check(state)
    if trace_writer is not None:
        trace_writer.write_tool_result("deterministic_check", result)
    return state


def rag_analysis_node(state):
    if not state.hypotheses:
        status = state.locked_facts.get("status", "UNKNOWN")
        state.add_hypothesis({
            "kind": "rag_analysis",
            "summary": "No external RAG provider is configured; analysis is limited to deterministic artifacts.",
            "observed_status": status,
        })
    else:
        state.hypotheses = [as_non_authoritative(hypothesis) for hypothesis in state.hypotheses]
    return state


def report_node(state):
    deterministic = {
        "status": state.locked_facts.get("status"),
        "proof_mode": state.locked_facts.get("proof_mode"),
        "confidence": state.locked_facts.get("confidence"),
        "suspect_slices": state.locked_facts.get("suspect_slices", []),
    }
    hypotheses = []
    for hypothesis in state.hypotheses:
        stored = as_non_authoritative(hypothesis)
        if stored.get("status") and deterministic.get("status") and stored.get("status") != deterministic.get("status"):
            stored["conflicts_with"] = "deterministic.status"
        hypotheses.append(stored)
    state.agent_summary = {
        "deterministic": deterministic,
        "hypotheses": hypotheses,
    }
    return state.agent_summary


def run_sidecar_workflow(
    task_path,
    jar_path=None,
    output_dir=None,
    runner=None,
    require_approval=False,
    checkpoint_path=None,
    approval_request_path=None,
):
    state = AgentState(task_path=task_path, jar_path=jar_path, output_dir=output_dir)
    state.workflow_status = "RUNNING"
    configure_approval_paths(state, output_dir, checkpoint_path, approval_request_path)
    trace_path = state.trace_metadata.get("trace_path")
    trace_writer = JsonlTraceWriter(trace_path) if trace_path else None
    runner = runner or JavaCliToolRunner(jar_path=jar_path)

    profile_planning_node(state, runner=runner, trace_writer=trace_writer)
    if require_approval and state.tool_results[-1].succeeded:
        summary = pause_for_approval(state, trace_writer=trace_writer)
        return state, summary
    if state.tool_results[-1].succeeded:
        deterministic_check_node(state, runner=runner, trace_writer=trace_writer)
    elif trace_writer is not None:
        trace_writer.write_event(
            node="deterministic_check",
            command_type="check",
            artifact_paths=state.artifact_paths,
            exit_code=None,
            event_type="skipped",
            extra={"reason": "plan_failed"},
        )
    rag_analysis_node(state)
    summary = report_node(state)
    state.workflow_status = "COMPLETED"
    if trace_writer is not None:
        trace_writer.write_event(
            node="report",
            command_type="agent_report",
            artifact_paths=state.artifact_paths,
            exit_code=None,
            event_type="node",
            extra={"locked_fact_count": len(state.locked_facts)},
        )
    return state, summary


def resume_sidecar_workflow(checkpoint_path, approval_decision_path, runner=None):
    store = FileCheckpointStore(checkpoint_path)
    raw_checkpoint = store.load()
    task_path = raw_checkpoint.state.get("task_path")
    checkpoint = store.load_for_task(task_path)
    state = checkpoint.to_state()
    state.artifact_paths["checkpoint"] = checkpoint_path
    state.artifact_paths["approval_decision"] = approval_decision_path
    trace_path = state.trace_metadata.get("trace_path")
    trace_writer = JsonlTraceWriter(trace_path) if trace_path else None
    decision = read_approval_decision(approval_decision_path)
    if state.approval_request_id and decision.request_id != state.approval_request_id:
        raise ApprovalValidationError("approval decision request_id does not match checkpoint")

    if decision.decision == "rejected":
        state.workflow_status = "STOPPED_BY_APPROVAL"
        summary = {
            "status": "STOPPED_BY_APPROVAL",
            "reason": decision.reason,
            "approval_request_id": decision.request_id,
        }
        state.agent_summary = summary
        if trace_writer is not None:
            trace_writer.write_event(
                node="approval_gate",
                command_type="approval_decision",
                artifact_paths=state.artifact_paths,
                exit_code=None,
                event_type="approval",
                extra={
                    "request_id": decision.request_id,
                    "decision": decision.decision,
                    "outcome": "stopped",
                    "reason": decision.reason,
                },
            )
        return state, summary

    if trace_writer is not None:
        trace_writer.write_event(
            node="approval_gate",
            command_type="approval_decision",
            artifact_paths=state.artifact_paths,
            exit_code=None,
            event_type="approval",
            extra={
                "request_id": decision.request_id,
                "decision": decision.decision,
                "outcome": "resumed",
                "reason": decision.reason,
            },
        )

    runner = runner or JavaCliToolRunner(jar_path=state.jar_path)
    if checkpoint.pending_node != "deterministic_check":
        raise ApprovalValidationError("unsupported pending node: " + str(checkpoint.pending_node))
    deterministic_check_node(state, runner=runner, trace_writer=trace_writer)
    rag_analysis_node(state)
    summary = report_node(state)
    state.workflow_status = "COMPLETED"
    if trace_writer is not None:
        trace_writer.write_event(
            node="report",
            command_type="agent_report",
            artifact_paths=state.artifact_paths,
            exit_code=None,
            event_type="node",
            extra={"locked_fact_count": len(state.locked_facts)},
        )
    return state, summary


def pause_for_approval(state, trace_writer=None):
    pending_node = "deterministic_check"
    request_path = state.artifact_paths["approval_request"]
    request = write_approval_request(request_path, state, pending_node)
    checkpoint = FileCheckpointStore(state.artifact_paths["checkpoint"]).save(state, pending_node)
    state.workflow_status = "WAITING_FOR_APPROVAL"
    summary = {
        "status": "WAITING_FOR_APPROVAL",
        "approval_request": request.to_dict(),
        "checkpoint": checkpoint.to_dict(),
    }
    state.agent_summary = summary
    if trace_writer is not None:
        trace_writer.write_event(
            node="approval_gate",
            command_type="approval_request",
            artifact_paths=state.artifact_paths,
            exit_code=None,
            event_type="approval",
            extra={
                "request_id": request.request_id,
                "decision": "pending",
                "outcome": "paused",
            },
        )
    return summary


def configure_approval_paths(state, output_dir=None, checkpoint_path=None, approval_request_path=None):
    base_dir = output_dir or state.artifact_paths.get("agent_output_dir") or ".tmp/agent-sidecar"
    if checkpoint_path is None:
        checkpoint_path = Path(base_dir) / "agent_checkpoint.json"
    if approval_request_path is None:
        approval_request_path = Path(base_dir) / "approval_request.json"
    state.artifact_paths.setdefault("checkpoint", str(checkpoint_path))
    state.artifact_paths.setdefault("approval_request", str(approval_request_path))


def build_langgraph_workflow(runner=None):
    if StateGraph is None:
        return None

    def wrap_node(func):
        def wrapped(values):
            state = AgentState.from_dict(values)
            result = func(state, runner=runner) if func in (profile_planning_node, deterministic_check_node) else func(state)
            if isinstance(result, AgentState):
                return result.to_dict()
            return state.to_dict()
        return wrapped

    graph = StateGraph(dict)
    graph.add_node("profile_planning", wrap_node(profile_planning_node))
    graph.add_node("deterministic_check", wrap_node(deterministic_check_node))
    graph.add_node("rag_analysis", wrap_node(rag_analysis_node))
    graph.add_node("report", wrap_node(report_node))
    graph.set_entry_point("profile_planning")
    graph.add_edge("profile_planning", "deterministic_check")
    graph.add_edge("deterministic_check", "rag_analysis")
    graph.add_edge("rag_analysis", "report")
    graph.add_edge("report", END)
    return graph.compile()


def as_non_authoritative(hypothesis):
    stored = dict(hypothesis)
    stored["authoritative"] = False
    return stored
