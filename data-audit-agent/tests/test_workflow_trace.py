import json

from data_audit_agent.graph import run_sidecar_workflow
from data_audit_agent.tools import CommandResult, JavaCliToolRunner


def test_workflow_writes_agent_trace_for_tool_invocations(tmpdir):
    reports_dir = tmpdir.mkdir("reports")
    task_path = tmpdir.join("task.yaml")
    task_path.write("output:\n  dir: {0}\n".format(str(reports_dir).replace("\\", "\\\\")))

    def executor(command):
        command_type = command[3]
        if command_type == "plan":
            return CommandResult(0, json.dumps({"scale_class": "SMALL"}), "")
        if command_type == "check":
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
        raise AssertionError("unexpected command: " + " ".join(command))

    runner = JavaCliToolRunner(jar_path="data-audit.jar", executor=executor)
    state, summary = run_sidecar_workflow(
        task_path=str(task_path),
        jar_path="data-audit.jar",
        output_dir=str(tmpdir.mkdir("agent")),
        runner=runner,
    )

    trace_path = state.trace_metadata["trace_path"]
    lines = [json.loads(line) for line in open(trace_path, "r").read().splitlines()]

    assert summary["deterministic"]["status"] == "CONSISTENT"
    assert [line["command_type"] for line in lines[:2]] == ["plan", "check"]
    assert lines[0]["node"] == "profile_planning"
    assert lines[1]["artifact_paths"]["report"].endswith("report.json")
    assert lines[1]["exit_code"] == 0
