import json
import os
import subprocess
from pathlib import Path

from data_audit_agent.state import locked_facts_from_report


PLAN_SUCCESS_EXIT_CODES = (0, 5)
CHECK_SUCCESS_EXIT_CODES = (0, 1, 5)


class CommandResult(object):
    def __init__(self, exit_code, stdout="", stderr=""):
        self.exit_code = exit_code
        self.stdout = stdout or ""
        self.stderr = stderr or ""


class ToolResult(object):
    def __init__(
        self,
        command_type,
        command,
        exit_code,
        stdout,
        stderr,
        succeeded,
        parsed_output=None,
        artifact_paths=None,
        failure_reason=None,
    ):
        self.command_type = command_type
        self.command = list(command)
        self.exit_code = exit_code
        self.stdout = stdout or ""
        self.stderr = stderr or ""
        self.succeeded = bool(succeeded)
        self.parsed_output = parsed_output
        self.artifact_paths = dict(artifact_paths or {})
        self.failure_reason = failure_reason

    def to_dict(self):
        return {
            "command_type": self.command_type,
            "command": list(self.command),
            "exit_code": self.exit_code,
            "stdout": self.stdout,
            "stderr": self.stderr,
            "succeeded": self.succeeded,
            "parsed_output": self.parsed_output,
            "artifact_paths": dict(self.artifact_paths),
            "failure_reason": self.failure_reason,
        }


class JavaCliToolRunner(object):
    def __init__(self, jar_path=None, java_bin="java", executor=None):
        self.jar_path = resolve_jar_path(jar_path)
        self.java_bin = java_bin
        self.executor = executor or default_executor

    def plan(self, state):
        result = self._invoke("plan", ["plan", "-f", state.task_path])
        parsed = parse_json_or_none(result.stdout)
        succeeded = result.exit_code in PLAN_SUCCESS_EXIT_CODES
        tool_result = ToolResult(
            command_type="plan",
            command=self._command(["plan", "-f", state.task_path]),
            exit_code=result.exit_code,
            stdout=result.stdout,
            stderr=result.stderr,
            succeeded=succeeded,
            parsed_output=parsed,
            artifact_paths={"task": state.task_path},
            failure_reason=None if succeeded else "java_cli_exit_" + str(result.exit_code),
        )
        state.add_tool_result(tool_result)
        return tool_result

    def check(self, state):
        report_path = state.report_path()
        artifact_paths = {"task": state.task_path}
        if report_path is not None:
            artifact_paths["report"] = report_path

        result = self._invoke("check", ["check", "-f", state.task_path])
        succeeded = result.exit_code in CHECK_SUCCESS_EXIT_CODES
        failure_reason = None if succeeded else "java_cli_exit_" + str(result.exit_code)

        if succeeded and report_path is not None:
            if Path(report_path).exists():
                state.set_locked_facts(locked_facts_from_report(report_path))
            else:
                succeeded = False
                failure_reason = "report_missing"

        tool_result = ToolResult(
            command_type="check",
            command=self._command(["check", "-f", state.task_path]),
            exit_code=result.exit_code,
            stdout=result.stdout,
            stderr=result.stderr,
            succeeded=succeeded,
            parsed_output=None,
            artifact_paths=artifact_paths,
            failure_reason=failure_reason,
        )
        state.add_tool_result(tool_result)
        return tool_result

    def diff(self, state, slice_key):
        report_path = state.report_path()
        artifact_paths = {"task": state.task_path, "slice": slice_key}
        if report_path is not None:
            artifact_paths["report"] = report_path

        args = ["diff", "-f", state.task_path, "--slice", slice_key]
        result = self._invoke("diff", args)
        succeeded = result.exit_code in CHECK_SUCCESS_EXIT_CODES
        if succeeded and report_path is not None and Path(report_path).exists():
            state.set_locked_facts(locked_facts_from_report(report_path))

        tool_result = ToolResult(
            command_type="diff",
            command=self._command(args),
            exit_code=result.exit_code,
            stdout=result.stdout,
            stderr=result.stderr,
            succeeded=succeeded,
            parsed_output=None,
            artifact_paths=artifact_paths,
            failure_reason=None if succeeded else "java_cli_exit_" + str(result.exit_code),
        )
        state.add_tool_result(tool_result)
        return tool_result

    def report_show(self, state, report_path=None):
        report_path = report_path or state.report_path()
        args = ["report", "show", report_path]
        result = self._invoke("report_show", args)
        parsed = parse_report_show_stdout(result.stdout)
        succeeded = result.exit_code in CHECK_SUCCESS_EXIT_CODES
        tool_result = ToolResult(
            command_type="report_show",
            command=self._command(args),
            exit_code=result.exit_code,
            stdout=result.stdout,
            stderr=result.stderr,
            succeeded=succeeded,
            parsed_output=parsed,
            artifact_paths={"report": report_path},
            failure_reason=None if succeeded else "java_cli_exit_" + str(result.exit_code),
        )
        state.add_tool_result(tool_result)
        return tool_result

    def _invoke(self, command_type, args):
        return normalize_command_result(self.executor(self._command(args)))

    def _command(self, args):
        return [self.java_bin, "-jar", self.jar_path] + list(args)


def resolve_jar_path(jar_path=None):
    if jar_path:
        return str(jar_path)
    env_value = os.environ.get("DATAAUDIT_JAR")
    if env_value:
        return env_value
    repo_root = Path(__file__).resolve().parents[2]
    candidate = repo_root / "data-audit-cli" / "target" / "data-audit.jar"
    return str(candidate)


def default_executor(command):
    process = subprocess.Popen(
        command,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        universal_newlines=True,
    )
    stdout, stderr = process.communicate()
    return CommandResult(process.returncode, stdout, stderr)


def normalize_command_result(value):
    if isinstance(value, CommandResult):
        return value
    if hasattr(value, "returncode"):
        return CommandResult(value.returncode, getattr(value, "stdout", ""), getattr(value, "stderr", ""))
    if isinstance(value, tuple) and len(value) == 3:
        return CommandResult(value[0], value[1], value[2])
    raise TypeError("executor must return CommandResult, CompletedProcess, or (exit_code, stdout, stderr)")


def parse_json_or_none(value):
    text = (value or "").strip()
    if not text:
        return None
    try:
        return json.loads(text)
    except ValueError:
        return None


def parse_report_show_stdout(value):
    parsed = {}
    for line in (value or "").splitlines():
        if "=" not in line:
            continue
        key, raw_value = line.split("=", 1)
        parsed[key.strip()] = raw_value.strip()
    return parsed
