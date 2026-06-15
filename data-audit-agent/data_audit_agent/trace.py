import json
from datetime import datetime
from pathlib import Path


class JsonlTraceWriter(object):
    def __init__(self, trace_path):
        self.trace_path = str(trace_path)

    def write_event(self, node, command_type=None, artifact_paths=None, exit_code=None, event_type="event", extra=None):
        event = {
            "timestamp": datetime.utcnow().replace(microsecond=0).isoformat() + "Z",
            "node": node,
            "event_type": event_type,
            "command_type": command_type,
            "artifact_paths": dict(artifact_paths or {}),
            "exit_code": exit_code,
        }
        if extra:
            event.update(extra)
        path = Path(self.trace_path)
        if path.parent:
            path.parent.mkdir(parents=True, exist_ok=True)
        with open(str(path), "a", encoding="utf-8") as handle:
            handle.write(json.dumps(event, sort_keys=True) + "\n")

    def write_tool_result(self, node, result):
        self.write_event(
            node=node,
            command_type=result.command_type,
            artifact_paths=result.artifact_paths,
            exit_code=result.exit_code,
            event_type="tool",
            extra={"succeeded": result.succeeded, "failure_reason": result.failure_reason},
        )
