import json
import uuid
from copy import deepcopy
from pathlib import Path

from data_audit_agent.checkpoint import task_fingerprint, utc_now
from data_audit_agent.state import LOCKED_FACT_KEYS


FORBIDDEN_DECISION_KEYS = set(LOCKED_FACT_KEYS + ("locked_facts", "deterministic_facts"))


class ApprovalValidationError(ValueError):
    pass


class ApprovalRequest(object):
    def __init__(
        self,
        request_id,
        task_path,
        task_fingerprint_value,
        pending_node,
        strategy,
        risk,
        artifact_paths,
        created_at=None,
    ):
        self.request_id = request_id
        self.task_path = task_path
        self.task_fingerprint = task_fingerprint_value
        self.pending_node = pending_node
        self.strategy = strategy
        self.risk = risk
        self.artifact_paths = dict(artifact_paths or {})
        self.created_at = created_at or utc_now()

    def to_dict(self):
        return {
            "artifact_version": "1",
            "artifact_type": "approval_request",
            "producer": "data-audit-agent",
            "schema_version": "data-audit-approval-request-v1",
            "created_at": self.created_at,
            "request_id": self.request_id,
            "task_path": self.task_path,
            "task_fingerprint": self.task_fingerprint,
            "pending_node": self.pending_node,
            "strategy": deepcopy(self.strategy),
            "risk": deepcopy(self.risk),
            "artifact_paths": dict(self.artifact_paths),
            "requested_decision": "approve_execution",
            "approval_scope": "execution_only",
            "consistency_claim": False,
            "prompt": "Approve whether the Agent may proceed with execution. Deterministic report artifacts decide data consistency.",
        }


class ApprovalDecision(object):
    def __init__(self, request_id, decision, approver=None, reason=None, created_at=None):
        self.request_id = request_id
        self.decision = decision
        self.approver = approver
        self.reason = reason
        self.created_at = created_at

    def to_dict(self):
        return {
            "request_id": self.request_id,
            "decision": self.decision,
            "approver": self.approver,
            "reason": self.reason,
            "created_at": self.created_at,
        }


def write_approval_request(path, state, pending_node, strategy=None, risk=None):
    state.artifact_paths["approval_request"] = str(path)
    request = ApprovalRequest(
        request_id="approval-" + uuid.uuid4().hex,
        task_path=state.task_path,
        task_fingerprint_value=task_fingerprint(state.task_path),
        pending_node=pending_node,
        strategy=strategy or {"node": pending_node, "action": "run_deterministic_tool"},
        risk=risk or {"level": "high", "reason": "High-cost or high-risk deterministic execution requires approval."},
        artifact_paths=state.artifact_paths,
    )
    output = Path(str(path))
    output.parent.mkdir(parents=True, exist_ok=True)
    with open(str(output), "w", encoding="utf-8") as handle:
        json.dump(request.to_dict(), handle, indent=2, sort_keys=True)
    state.approval_request_id = request.request_id
    return request


def read_approval_decision(path):
    with open(str(path), "r", encoding="utf-8") as handle:
        values = json.load(handle)
    validate_no_locked_fact_overrides(values)
    decision = values.get("decision")
    if decision not in ("approved", "rejected"):
        raise ApprovalValidationError("approval decision must be 'approved' or 'rejected'")
    request_id = values.get("request_id")
    if not request_id:
        raise ApprovalValidationError("approval decision requires request_id")
    return ApprovalDecision(
        request_id=request_id,
        decision=decision,
        approver=values.get("approver"),
        reason=values.get("reason"),
        created_at=values.get("created_at"),
    )


def validate_no_locked_fact_overrides(value):
    if isinstance(value, dict):
        for key, nested in value.items():
            if key in FORBIDDEN_DECISION_KEYS:
                raise ApprovalValidationError("approval decision cannot set deterministic locked fact: " + key)
            validate_no_locked_fact_overrides(nested)
    elif isinstance(value, list):
        for nested in value:
            validate_no_locked_fact_overrides(nested)
