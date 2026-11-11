"""Tests of .github/workflows/ci.yml (P-42 spec Section 7, BP-W-01).

PyYAML reads the key `on` as True, so the triggers are not tested here.
"""

import os
import re
import subprocess
from pathlib import Path

import pytest
import yaml

WORKFLOW = Path(__file__).resolve().parent.parent / ".github" / "workflows" / "ci.yml"
JOBS = {"scope", "light", "java", "rest", "secrets", "gate"}


def workflow():
    return yaml.safe_load(WORKFLOW.read_text(encoding="utf-8"))


def runs(job):
    return "\n".join(step.get("run", "") for step in workflow()["jobs"][job]["steps"])


def test_exactly_six_jobs():
    """BP-W-01 the workflow has exactly the jobs scope, light, java, rest, secrets and gate on ubuntu-24.04."""
    jobs = workflow()["jobs"]
    assert set(jobs) == JOBS
    for name, job in jobs.items():
        assert job["runs-on"] == "ubuntu-24.04", name
        assert job.get("name", name) == name


@pytest.mark.parametrize(
    "job, needs, condition, timeout",
    [
        ("scope", None, None, 5),
        ("light", "scope", "needs.scope.outputs.mode == 'light'", 15),
        ("java", "scope", "needs.scope.outputs.mode == 'full'", 60),
        ("rest", "scope", "needs.scope.outputs.mode == 'full'", 60),
        ("secrets", None, None, 10),
        ("gate", ["scope", "light", "java", "rest", "secrets"], "always()", 5),
    ],
)
def test_job_needs_conditions_and_timeouts(job, needs, condition, timeout):
    """BP-W-01 each job's needs, if and timeout are those of the P-42 spec Section 6.4."""
    definition = workflow()["jobs"][job]
    assert definition.get("needs") == needs
    assert definition.get("if") == condition
    assert definition["timeout-minutes"] == timeout


def test_scope_decides_with_gate_scope():
    """BP-W-01 the job scope runs tools/gate_scope.py ci with its variables and gives the output mode."""
    scope = workflow()["jobs"]["scope"]
    assert scope["outputs"] == {"mode": "${{ steps.decide.outputs.mode }}"}
    assert scope["steps"][0]["with"]["fetch-depth"] == 0
    decide = next(step for step in scope["steps"] if step.get("id") == "decide")
    assert decide["run"] == "python3 tools/gate_scope.py ci"
    assert decide["env"] == {
        "EVENT_NAME": "${{ github.event_name }}",
        "BASE_SHA": "${{ github.event.pull_request.base.sha }}",
        "HEAD_SHA": "${{ github.event.pull_request.head.sha }}",
        "MERGE_SHA": "${{ github.sha }}",
        "GH_TOKEN": "${{ github.token }}",
    }


def test_parts_of_the_gate_command():
    """BP-W-01 java runs tools/check-all.sh --part java, rest --part rest, light the data checks and trace.py."""
    assert "tools/check-all.sh --part java" in runs("java")
    assert "tools/check-all.sh --part rest" in runs("rest")
    assert "--part rest" not in runs("java")
    assert "--part java" not in runs("rest")
    assert "check_data.py docs/31-demo-data" in runs("light")
    assert "tools/trace.py --check" in runs("light")
    assert "gitleaks git --redact" in runs("secrets")
    assert "check-all.sh" not in runs("light") + runs("secrets")


def test_permissions():
    """BP-W-01 the workflow may read contents and checks only."""
    assert workflow()["permissions"] == {"contents": "read", "checks": "read"}


def test_actions_pinned_by_sha():
    """BP-W-01 every uses: is pinned to a full commit SHA."""
    uses = [step["uses"] for job in workflow()["jobs"].values() for step in job["steps"] if "uses" in step]
    assert uses
    for reference in uses:
        assert re.fullmatch(r"[\w.-]+/[\w.-]+@[0-9a-f]{40}", reference), reference


def summary(results, mode):
    step = workflow()["jobs"]["gate"]["steps"][0]
    environment = dict(os.environ)
    environment.update({f"{job.upper()}_RESULT": result for job, result in results.items()})
    environment["MODE"] = mode
    return subprocess.run(
        ["bash", "-c", step["run"]], env=environment, capture_output=True, text=True, timeout=30, check=False
    )


@pytest.mark.parametrize(
    "mode, results, passes",
    [
        ("light", {"scope": "success", "light": "success", "java": "skipped", "rest": "skipped"}, True),
        ("full", {"scope": "success", "light": "skipped", "java": "success", "rest": "success"}, True),
        ("light", {"scope": "success", "light": "failure", "java": "skipped", "rest": "skipped"}, False),
        ("full", {"scope": "success", "light": "skipped", "java": "success", "rest": "failure"}, False),
        ("full", {"scope": "success", "light": "skipped", "java": "cancelled", "rest": "success"}, False),
        ("light", {"scope": "success", "light": "skipped", "java": "success", "rest": "success"}, False),
        ("", {"scope": "failure", "light": "skipped", "java": "skipped", "rest": "skipped"}, False),
    ],
)
@pytest.mark.parametrize("secrets", ["success", "failure"])
def test_gate_passes_only_when_the_chosen_jobs_passed(mode, results, passes, secrets):
    """BP-W-01 the job gate passes only when scope, secrets and the jobs of the chosen mode succeeded."""
    result = summary({**results, "secrets": secrets}, mode)
    if passes and secrets == "success":
        assert result.returncode == 0, result.stdout
    else:
        assert result.returncode == 1
        assert f"secrets: {secrets}" in result.stdout
        assert f"scope: {results['scope']}" in result.stdout
