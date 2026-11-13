"""Tests of deploy/local.sh that need no Docker (P-26 spec Section 6.8): a docker stub first on PATH.

deploy/local.sh runs from a temporary copy of the repository; the stub records every call and answers as the
environment variables of a test say: STUB_LIST (the output of a Playwright --list run), STUB_FAIL (a Playwright run
whose arguments contain this text exits 1).
"""

import json
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
USAGE = """Usage: deploy/local.sh up [--profile test|demo] [--values FILE] [--no-build] [--project NAME]
       deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]
       deploy/local.sh down [--volumes] [--project NAME]
       deploy/local.sh reset [--project NAME]
       deploy/local.sh logs [--no-follow] [SERVICE...] [--project NAME]
       deploy/local.sh e2e [SUITE] [--project NAME]
"""
COMPOSE = ["compose", "-f", "deploy/local/compose.yaml", "-p", "vigil-local"]
PROFILES = [
    "--profile",
    "lab-only",
    "--profile",
    "spike",
    "--profile",
    "embed",
    "--profile",
    "e2e",
    "--profile",
    "acceptance",
]
PW = [
    *COMPOSE,
    "--profile",
    "e2e",
    "run",
    "--rm",
    "e2e",
    "/e2e/node_modules/.bin/playwright",
    "test",
    "-c",
    "/e2e/tests/playwright.config.ts",
]
STUB = textwrap.dedent(
    """\
    import json
    import os
    import sys

    arguments = sys.argv[1:]
    with open(os.environ["STUB_LOG"], "a") as log:
        log.write(json.dumps(arguments) + "\\n")
    if "--list" in arguments:
        print(os.environ.get("STUB_LIST", ""))
        sys.exit(0 if "Total:" in os.environ.get("STUB_LIST", "") else 1)
    if "run" in arguments and os.environ.get("STUB_FAIL") and os.environ["STUB_FAIL"] in arguments:
        sys.exit(1)
    sys.exit(0)
    """
)


@pytest.fixture
def local(tmp_path):
    root = tmp_path / "repository"
    for relative in ["deploy/local.sh", "deploy/local/compose.yaml", "deploy/values/local.yaml"]:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    stubs = tmp_path / "bin"
    stubs.mkdir()
    (stubs / "docker").write_text(f"#!{sys.executable}\n" + STUB, encoding="utf-8")
    (stubs / "docker").chmod(0o755)
    log = tmp_path / "docker.log"
    home = tmp_path / "home"

    def run(*arguments, **variables):
        log.write_text("", encoding="utf-8")
        environment = {k: v for k, v in os.environ.items() if not k.startswith(("STUB_", "VIGIL_LOCAL"))}
        environment.update(
            PATH=os.pathsep.join([str(stubs), environment.get("PATH", "")]),
            STUB_LOG=str(log),
            VIGIL_LOCAL_HOME=str(home),
            **variables,
        )
        result = subprocess.run(
            ["bash", str(root / "deploy" / "local.sh"), *arguments],
            cwd=tmp_path,
            env=environment,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        calls = [json.loads(line) for line in log.read_text(encoding="utf-8").splitlines() if line.strip()]
        return result, calls

    run.home = home
    return run


def test_one_script_for_every_host():
    """BP-R-28 one script for every host; local.yaml tracked, local-real.yaml ignored."""
    script = (ROOT / "deploy" / "local.sh").read_text()
    assert "COMPOSE_FILE=deploy/local/compose.yaml" in script
    assert "uname" not in script and "darwin" not in script.lower()
    tracked = subprocess.run(
        ["git", "ls-files", "--error-unmatch", "deploy/values/local.yaml"], cwd=ROOT, capture_output=True, check=False
    )
    assert tracked.returncode == 0
    ignored = subprocess.run(
        ["git", "check-ignore", "-q", "--no-index", "deploy/values/local-real.yaml"], cwd=ROOT, check=False
    )
    assert ignored.returncode == 0


@pytest.mark.parametrize(
    "arguments",
    [
        [],
        ["start"],
        ["up", "--profile", "large"],
        ["up", "--values"],
        ["up", "--lab-only", "--values", "x.yaml"],
        ["up", "--lab-only", "--no-build"],
        ["up", "--other"],
        ["down", "--other"],
        ["reset", "extra"],
        ["logs", "--follow"],
        ["e2e", "smoke", "other"],
        ["e2e", "--project"],
    ],
)
def test_usage(local, arguments):
    """BP-R-28 refused arguments print the usage lines and exit 2 before any docker call."""
    result, calls = local(*arguments)
    assert result.returncode == 2
    assert result.stdout == USAGE
    assert calls == []


def playwright_runs(calls):
    return [call[len(PW) :] for call in calls if call[: len(PW)] == PW]


def stop_start(calls):
    return [call[len(COMPOSE) :] for call in calls if call[len(COMPOSE) : len(COMPOSE) + 1] in (["stop"], ["start"])]


def test_e2e_stops_the_ai_fakes(local):
    """BP-R-28 e2e stops claude-fake and openai-fake around the @ai-down run and starts them again."""
    result, calls = local("e2e", STUB_LIST="  [chromium] › a.spec.ts:3:1 › x @ai-down\nTotal: 1 test in 1 file")
    assert result.returncode == 0, result.stdout + result.stderr
    assert playwright_runs(calls) == [
        ["--grep-invert", "@ai-down|@storm"],
        ["--list", "--grep", "@ai-down"],
        ["--grep", "@ai-down"],
    ]
    assert stop_start(calls) == [["stop", "claude-fake", "openai-fake"], ["start", "claude-fake", "openai-fake"]]
    stop = calls.index([*COMPOSE, "stop", "claude-fake", "openai-fake"])
    run = calls.index([*PW, "--grep", "@ai-down"])
    start = calls.index([*COMPOSE, "start", "claude-fake", "openai-fake"])
    assert stop < run < start
    assert (local.home / "vigil-local" / "e2e").is_dir()


def test_e2e_restarts_after_a_failure(local):
    """BP-R-28 the AI fakes are started again when the @ai-down run fails, and the exit status is 1."""
    result, calls = local("e2e", STUB_LIST="Total: 3 tests in 2 files", STUB_FAIL="@ai-down")
    assert result.returncode == 1
    assert stop_start(calls) == [["stop", "claude-fake", "openai-fake"], ["start", "claude-fake", "openai-fake"]]


def test_e2e_first_run_failure(local):
    """BP-R-28 a failed first run gives exit status 1 and the @ai-down run still runs."""
    result, calls = local("e2e", STUB_LIST="Total: 1 test in 1 file", STUB_FAIL="--grep-invert")
    assert result.returncode == 1
    assert ["--grep", "@ai-down"] in playwright_runs(calls)


def test_e2e_without_ai_down_tests(local):
    """BP-R-28 the AI fakes are not stopped when the listing shows no @ai-down test."""
    result, calls = local("e2e", STUB_LIST="Error: No tests found")
    assert result.returncode == 0, result.stdout + result.stderr
    assert playwright_runs(calls) == [["--grep-invert", "@ai-down|@storm"], ["--list", "--grep", "@ai-down"]]
    assert stop_start(calls) == []


def test_e2e_suite(local):
    """BP-R-28 with a suite, the first run greps the suite and the @ai-down run uses one pattern."""
    result, calls = local("e2e", "smoke", "--project", "vigil-local", STUB_LIST="Total: 1 test in 1 file")
    assert result.returncode == 0, result.stdout + result.stderr
    pattern = "(?=.*@ai-down)(?=.*@smoke)"
    assert playwright_runs(calls) == [
        ["--grep-invert", "@ai-down", "--grep", "@smoke"],
        ["--list", "--grep", pattern],
        ["--grep", pattern],
    ]


def test_e2e_storm_suite(local):
    """BP-W-02 the suite storm runs the tests tagged @storm, which the run without a suite leaves out (P-28)."""
    result, calls = local("e2e", "storm", STUB_LIST="Error: No tests found")
    assert result.returncode == 0, result.stdout + result.stderr
    assert playwright_runs(calls) == [
        ["--grep-invert", "@ai-down", "--grep", "@storm"],
        ["--list", "--grep", "(?=.*@ai-down)(?=.*@storm)"],
    ]


def test_e2e_makes_the_node_modules_mount_point(local):
    """BP-W-02 e2e makes tests/e2e/node_modules, the mount point of the container's tmpfs (P-28)."""
    root = local.home.parent / "repository"
    assert not (root / "tests" / "e2e" / "node_modules").exists()
    result, _calls = local("e2e", STUB_LIST="Error: No tests found")
    assert result.returncode == 0, result.stdout + result.stderr
    assert (root / "tests" / "e2e" / "node_modules").is_dir()
    compose = (ROOT / "deploy" / "local" / "compose.yaml").read_text(encoding="utf-8")
    assert "type: tmpfs\n        target: /e2e/tests/node_modules" in compose


def test_logs(local):
    """BP-R-28 logs follows by default, with the profile acceptance (P-27); --no-follow leaves --follow out; services
    are passed on."""
    profiles = PROFILES
    result, calls = local("logs", "--no-follow", "vigil-app", "jira-fake")
    assert result.returncode == 0
    assert calls == [[*COMPOSE, *profiles, "logs", "--tail", "200", "vigil-app", "jira-fake"]]
    result, calls = local("logs", "--project", "other")
    assert result.returncode == 0
    assert calls == [[*COMPOSE[:-1], "other", *profiles, "logs", "--tail", "200", "--follow"]]


def test_down_with_the_acceptance_profile(local):
    """BP-R-28 down stops the services of every profile, the storm driver's acceptance included (P-27)."""
    result, calls = local("down")
    assert result.returncode == 0
    assert calls == [[*COMPOSE, *PROFILES, "down", "--remove-orphans"]]
    result, calls = local("down", "--volumes", "--project", "other")
    assert result.returncode == 0
    assert calls == [[*COMPOSE[:-1], "other", *PROFILES, "down", "--remove-orphans", "--volumes"]]


def test_reset_needs_up(local):
    """BP-R-28 reset without state/values-file is refused before any docker call."""
    result, calls = local("reset")
    assert result.returncode == 1
    assert result.stdout == "Run deploy/local.sh up first.\n"
    assert calls == []


def test_third_party_images_leave_out_the_optional_image(tmp_path):
    """BP-R-28 third_party_images lists the pinned images except the line ending with # optional."""
    script = (ROOT / "deploy" / "local.sh").read_text()
    function = script[script.index("third_party_images() {") :]
    function = function[: function.index("\n}\n") + 3]
    result = subprocess.run(
        ["bash", "-c", f"set -euo pipefail\n{function}\nthird_party_images"],
        cwd=ROOT,
        env=dict(os.environ, COMPOSE_FILE="deploy/local/compose.yaml"),
        capture_output=True,
        text=True,
        check=True,
    )
    images = result.stdout.split()
    compose = (ROOT / "deploy" / "local" / "compose.yaml").read_text()
    assert any(image.startswith("pgvector/pgvector:") for image in images)
    assert any(image.startswith("axllent/mailpit:") for image in images)
    assert not any(image.startswith("ollama/ollama:") for image in images)
    assert "ollama/ollama:" in compose
    assert all("@sha256:" in image for image in images)
