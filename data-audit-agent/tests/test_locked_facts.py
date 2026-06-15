import json

import pytest

from data_audit_agent.graph import rag_analysis_node, report_node
from data_audit_agent.state import AgentState, LockedFactError, locked_facts_from_report


def test_locked_facts_are_loaded_from_report_json(tmpdir):
    report_path = tmpdir.join("report.json")
    report_path.write(json.dumps({
        "result": {
            "status": "DIFF_FOUND",
            "proof_mode": "EXACT_DIFF",
            "confidence": "EXACT",
            "root_cause": "value_mismatch",
            "suspect_slices": [{"slice_key": "dt=2026-03-10"}],
            "diff": {"consistent": False},
        }
    }))

    facts = locked_facts_from_report(str(report_path))

    assert facts["status"] == "DIFF_FOUND"
    assert facts["proof_mode"] == "EXACT_DIFF"
    assert facts["confidence"] == "EXACT"
    assert facts["suspect_slices"][0]["slice_key"] == "dt=2026-03-10"


def test_locked_facts_cannot_be_rewritten():
    state = AgentState(task_path="task.yaml")
    state.set_locked_facts({
        "status": "DIFF_FOUND",
        "proof_mode": "EXACT_DIFF",
        "confidence": "EXACT",
        "suspect_slices": [],
    })

    with pytest.raises(LockedFactError):
        state.set_locked_facts({"status": "CONSISTENT"})


def test_report_node_keeps_conflicting_hypothesis_non_authoritative():
    state = AgentState(task_path="task.yaml")
    state.set_locked_facts({
        "status": "DIFF_FOUND",
        "proof_mode": "EXACT_DIFF",
        "confidence": "EXACT",
        "suspect_slices": [{"slice_key": "dt=2026-03-10"}],
    })
    state.add_hypothesis({
        "kind": "rag_analysis",
        "status": "CONSISTENT",
        "summary": "No mismatch found in retrieved cases.",
    })

    rag_analysis_node(state)
    summary = report_node(state)

    assert summary["deterministic"]["status"] == "DIFF_FOUND"
    assert summary["deterministic"]["proof_mode"] == "EXACT_DIFF"
    assert summary["deterministic"]["confidence"] == "EXACT"
    assert summary["hypotheses"][0]["authoritative"] is False
    assert state.locked_facts["status"] == "DIFF_FOUND"
