import json

from data_audit_agent.state import AgentState
from data_audit_agent.tools import CommandResult, JavaCliToolRunner


def test_plan_tool_records_command_output_and_parsed_plan(tmpdir):
    task_path = tmpdir.join("task.yaml")
    task_path.write("task:\n  name: fixture\n")

    def executor(command):
        assert command[-3:] == ["plan", "-f", str(task_path)]
        return CommandResult(
            exit_code=0,
            stdout=json.dumps({"scale_class": "SMALL", "proof_mode": "GLOBAL_CHECKSUM"}),
            stderr="",
        )

    state = AgentState(task_path=str(task_path), jar_path="data-audit.jar")
    runner = JavaCliToolRunner(jar_path="data-audit.jar", executor=executor)

    result = runner.plan(state)

    assert result.command_type == "plan"
    assert result.exit_code == 0
    assert result.succeeded is True
    assert result.parsed_output["scale_class"] == "SMALL"
    assert state.tool_results[-1].command_type == "plan"


def test_check_failure_is_recorded_without_ai_consistency_conclusion(tmpdir):
    task_path = tmpdir.join("task.yaml")
    task_path.write("task:\n  name: fixture\n")

    def executor(command):
        assert command[-3:] == ["check", "-f", str(task_path)]
        return CommandResult(exit_code=4, stdout="", stderr="connection refused")

    state = AgentState(task_path=str(task_path), jar_path="data-audit.jar")
    runner = JavaCliToolRunner(jar_path="data-audit.jar", executor=executor)

    result = runner.check(state)

    assert result.command_type == "check"
    assert result.exit_code == 4
    assert result.succeeded is False
    assert result.failure_reason == "java_cli_exit_4"
    assert state.locked_facts == {}
    assert state.ai_consistency_conclusion is None
