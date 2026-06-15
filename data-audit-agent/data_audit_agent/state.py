import json
import os
from copy import deepcopy
from pathlib import Path


LOCKED_FACT_KEYS = (
    "status",
    "proof_mode",
    "confidence",
    "root_cause",
    "suspect_slices",
    "diff",
)


class LockedFactError(ValueError):
    pass


class AgentState(object):
    def __init__(self, task_path, jar_path=None, output_dir=None, artifact_paths=None, trace_metadata=None):
        self.task_path = str(task_path)
        self.jar_path = jar_path
        self.artifact_paths = dict(artifact_paths or {})
        self.artifact_paths.setdefault("task", self.task_path)
        if output_dir is not None:
            output_dir = str(output_dir)
            self.artifact_paths.setdefault("agent_output_dir", output_dir)
            self.artifact_paths.setdefault("agent_trace", str(Path(output_dir) / "agent_trace.jsonl"))
        self.locked_facts = {}
        self.hypotheses = []
        self.tool_results = []
        self.trace_metadata = dict(trace_metadata or {})
        if "agent_trace" in self.artifact_paths:
            self.trace_metadata.setdefault("trace_path", self.artifact_paths["agent_trace"])
        self.ai_consistency_conclusion = None
        self.agent_summary = None
        self.workflow_status = "NEW"
        self.pending_node = None
        self.approval_request_id = None

    def add_tool_result(self, result):
        self.tool_results.append(result)

    def add_hypothesis(self, hypothesis):
        stored = dict(hypothesis)
        stored["authoritative"] = False
        self.hypotheses.append(stored)

    def set_locked_facts(self, facts):
        for key, value in facts.items():
            if key not in LOCKED_FACT_KEYS:
                continue
            if key in self.locked_facts and self.locked_facts[key] != value:
                raise LockedFactError("locked fact cannot be changed: " + key)
        for key, value in facts.items():
            if key in LOCKED_FACT_KEYS:
                self.locked_facts[key] = deepcopy(value)

    def report_path(self):
        if self.artifact_paths.get("report"):
            return self.artifact_paths["report"]
        inferred = infer_report_path(self.task_path)
        if inferred is not None:
            self.artifact_paths["report"] = inferred
        return inferred

    def to_dict(self):
        return {
            "task_path": self.task_path,
            "jar_path": self.jar_path,
            "artifact_paths": dict(self.artifact_paths),
            "locked_facts": deepcopy(self.locked_facts),
            "hypotheses": deepcopy(self.hypotheses),
            "tool_results": [result.to_dict() for result in self.tool_results],
            "trace_metadata": dict(self.trace_metadata),
            "ai_consistency_conclusion": self.ai_consistency_conclusion,
            "agent_summary": deepcopy(self.agent_summary),
            "workflow_status": self.workflow_status,
            "pending_node": self.pending_node,
            "approval_request_id": self.approval_request_id,
        }

    @classmethod
    def from_dict(cls, values):
        state = cls(
            task_path=values.get("task_path"),
            jar_path=values.get("jar_path"),
            artifact_paths=values.get("artifact_paths"),
            trace_metadata=values.get("trace_metadata"),
        )
        state.locked_facts = deepcopy(values.get("locked_facts") or {})
        state.hypotheses = deepcopy(values.get("hypotheses") or [])
        state.ai_consistency_conclusion = values.get("ai_consistency_conclusion")
        state.agent_summary = deepcopy(values.get("agent_summary"))
        state.workflow_status = values.get("workflow_status") or "NEW"
        state.pending_node = values.get("pending_node")
        state.approval_request_id = values.get("approval_request_id")
        return state


def locked_facts_from_report(report_path):
    with open(report_path, "r", encoding="utf-8") as handle:
        report = json.load(handle)
    result = report.get("result") or {}
    facts = {}
    for key in LOCKED_FACT_KEYS:
        if key in result:
            facts[key] = deepcopy(result[key])
    return facts


def infer_report_path(task_path):
    output_dir = infer_output_dir(task_path)
    if output_dir is None:
        return None
    return str(Path(output_dir) / "report.json")


def infer_output_dir(task_path):
    path = Path(task_path)
    if not path.exists():
        return None
    current_section = None
    with open(str(path), "r", encoding="utf-8") as handle:
        for raw_line in handle:
            line = raw_line.rstrip()
            stripped = line.strip()
            if not stripped or stripped.startswith("#"):
                continue
            if not raw_line.startswith((" ", "\t")) and stripped.endswith(":"):
                current_section = stripped[:-1]
                continue
            if current_section == "output" and stripped.startswith("dir:"):
                value = stripped.split(":", 1)[1].strip()
                value = value.strip("\"'")
                value = os.path.expandvars(os.path.expanduser(value))
                return value
    return None
