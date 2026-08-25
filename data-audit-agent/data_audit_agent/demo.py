# SPDX-License-Identifier: Apache-2.0
import argparse
import json
import sys
from pathlib import Path

from data_audit_agent.graph import resume_sidecar_workflow, run_sidecar_workflow


def main(argv=None):
    parser = argparse.ArgumentParser(description="Run the optional DataAudit Agent sidecar.")
    parser.add_argument("--task", help="task.yaml path")
    parser.add_argument("--fixture", help="use .tmp/verify-local/<fixture>/task.yaml, for example consistent_small")
    parser.add_argument("--jar", help="data-audit.jar path; defaults to DATAAUDIT_JAR or data-audit-cli/target/data-audit.jar")
    parser.add_argument("--output-dir", default=".tmp/agent-sidecar", help="directory for agent_trace.jsonl")
    parser.add_argument("--require-approval", action="store_true", help="pause after planning and write approval_request.json")
    parser.add_argument("--resume", action="store_true", help="resume from agent_checkpoint.json and approval_decision.json")
    parser.add_argument("--checkpoint", help="checkpoint path for resume or custom pause output")
    parser.add_argument("--approval-decision", help="approval_decision.json path for resume")
    args = parser.parse_args(argv)

    if args.resume:
        checkpoint_path = args.checkpoint or str(Path(args.output_dir) / "agent_checkpoint.json")
        decision_path = args.approval_decision or str(Path(args.output_dir) / "approval_decision.json")
        state, summary = resume_sidecar_workflow(
            checkpoint_path=checkpoint_path,
            approval_decision_path=decision_path,
        )
        print_summary(state, summary)
        return exit_code_for_state(state)

    task_path = resolve_task_path(args.task, args.fixture)
    if task_path is None:
        parser.error("specify --task or --fixture")
    if not task_path.exists():
        sys.stderr.write(
            "Task file not found: {0}\n"
            "For fixture mode, run scripts\\verify-local-sqlite.ps1 first.\n".format(task_path)
        )
        return 2

    state, summary = run_sidecar_workflow(
        task_path=str(task_path),
        jar_path=args.jar,
        output_dir=args.output_dir,
        require_approval=args.require_approval,
        checkpoint_path=args.checkpoint,
    )
    print_summary(state, summary)
    return exit_code_for_state(state)


def print_summary(state, summary):
    print(json.dumps({
        "summary": summary,
        "trace_path": state.trace_metadata.get("trace_path"),
        "artifact_paths": state.artifact_paths,
    }, indent=2, sort_keys=True))


def exit_code_for_state(state):
    if state.workflow_status == "WAITING_FOR_APPROVAL":
        return 3
    if state.workflow_status == "STOPPED_BY_APPROVAL":
        return 7
    return 0 if all(result.succeeded for result in state.tool_results) else 4


def resolve_task_path(task, fixture):
    if task:
        return Path(task)
    if fixture:
        return Path(".tmp") / "verify-local" / fixture / "task.yaml"
    return None
