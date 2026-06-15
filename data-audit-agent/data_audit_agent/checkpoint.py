import hashlib
import json
from copy import deepcopy
from datetime import datetime
from pathlib import Path

from data_audit_agent.state import AgentState


class StaleCheckpointError(ValueError):
    pass


class AgentCheckpoint(object):
    def __init__(self, task_fingerprint, pending_node, state, created_at=None):
        self.task_fingerprint = task_fingerprint
        self.pending_node = pending_node
        self.state = deepcopy(state)
        self.created_at = created_at or utc_now()

    def to_dict(self):
        return {
            "artifact_version": "1",
            "artifact_type": "agent_checkpoint",
            "producer": "data-audit-agent",
            "schema_version": "data-audit-agent-checkpoint-v1",
            "created_at": self.created_at,
            "task_fingerprint": self.task_fingerprint,
            "pending_node": self.pending_node,
            "state": deepcopy(self.state),
        }

    @classmethod
    def from_dict(cls, values):
        return cls(
            task_fingerprint=values.get("task_fingerprint"),
            pending_node=values.get("pending_node"),
            state=values.get("state") or {},
            created_at=values.get("created_at"),
        )

    def to_state(self):
        state = AgentState.from_dict(self.state)
        state.pending_node = self.pending_node
        return state


class CheckpointStore(object):
    def save(self, state, pending_node):
        raise NotImplementedError

    def load(self):
        raise NotImplementedError

    def load_for_task(self, task_path):
        checkpoint = self.load()
        expected = task_fingerprint(task_path)
        if checkpoint.task_fingerprint != expected:
            raise StaleCheckpointError("checkpoint task fingerprint does not match current task")
        return checkpoint


class FileCheckpointStore(CheckpointStore):
    def __init__(self, checkpoint_path):
        self.checkpoint_path = str(checkpoint_path)

    def save(self, state, pending_node):
        state.artifact_paths["checkpoint"] = self.checkpoint_path
        state.pending_node = pending_node
        checkpoint = AgentCheckpoint(
            task_fingerprint=task_fingerprint(state.task_path),
            pending_node=pending_node,
            state=state.to_dict(),
        )
        path = Path(self.checkpoint_path)
        path.parent.mkdir(parents=True, exist_ok=True)
        with open(str(path), "w", encoding="utf-8") as handle:
            json.dump(checkpoint.to_dict(), handle, indent=2, sort_keys=True)
        return checkpoint

    def load(self):
        with open(self.checkpoint_path, "r", encoding="utf-8") as handle:
            return AgentCheckpoint.from_dict(json.load(handle))


def task_fingerprint(task_path):
    digest = hashlib.sha256()
    with open(str(task_path), "rb") as handle:
        while True:
            chunk = handle.read(1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
    return "sha256:" + digest.hexdigest()


def utc_now():
    return datetime.utcnow().replace(microsecond=0).isoformat() + "Z"
