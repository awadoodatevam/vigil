"""Tests of tests/acceptance/storm/run.sh (P-27 spec Sections 6.6 and 6.8): a fake docker first on PATH.

run.sh runs from a temporary copy of the repository; the fake records every call with the variables the compose call
needs, and answers as the test's variables say: STUB_NO_IMAGE (image inspect fails), STUB_DRIVER_EXIT (the exit code of
the compose run), STUB_LOG (the text the compose logs call prints).
"""

import json
import os
import secrets
import shutil
import stat
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent.parent.parent
USAGE = (
    "Usage: tests/acceptance/storm/run.sh [--mode immediate|script] [--deliveries fakes|checklist] [--project NAME]\n"
)
COMPOSE = ["compose", "-f", "deploy/local/compose.yaml", "-p"]
STUB = textwrap.dedent(
    """\
    import json
    import os
    import sys

    arguments = sys.argv[1:]
    names = ["VIGIL_LOCAL_SECRETS", "VIGIL_LOCAL_STATE", "VIGIL_LOCAL_UID", "VIGIL_LOCAL_GID", "VIGIL_LAB_PROFILE",
             "VIGIL_STORM_RESULTS"]
    with open(os.environ["STUB_CALLS"], "a") as log:
        log.write(json.dumps({"arguments": arguments, "environment": {n: os.environ.get(n) for n in names}}) + "\\n")
    if arguments[:2] == ["image", "inspect"]:
        sys.exit(1 if os.environ.get("STUB_NO_IMAGE") else 0)
    if "run" in arguments:
        print("driver output")
        sys.exit(int(os.environ.get("STUB_DRIVER_EXIT", "0")))
    if "logs" in arguments:
        sys.stdout.write(os.environ.get("STUB_LOG", ""))
    sys.exit(0)
    """
)


@pytest.fixture
def script(tmp_path):
    root = tmp_path / "repository"
    for relative in ["tests/acceptance/storm/run.sh", "tests/acceptance/log_scan.py", "deploy/local/compose.yaml"]:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
    stubs = tmp_path / "bin"
    stubs.mkdir()
    (stubs / "docker").write_text(f"#!{sys.executable}\n" + STUB, encoding="utf-8")
    (stubs / "docker").chmod(0o755)
    home = tmp_path / "home"
    calls = tmp_path / "calls.log"
    caller = tmp_path / "caller"
    caller.mkdir()

    def installed(project="vigil-local", profile=None):
        state = home / project / "state"
        state.mkdir(parents=True, exist_ok=True)
        (state / "values-file").write_text("/x/local.yaml\n")
        if profile:
            (state / "lab-profile").write_text(profile + "\n")
        folder = home / project / "secrets" / "vigil-app"
        folder.mkdir(parents=True, exist_ok=True)
        return home / project / "secrets"

    def run(*arguments, **variables):
        calls.write_text("", encoding="utf-8")
        environment = {k: v for k, v in os.environ.items() if not k.startswith(("STUB_", "VIGIL_"))}
        environment.update(
            PATH=os.pathsep.join([str(stubs), environment.get("PATH", "")]),
            STUB_CALLS=str(calls),
            VIGIL_LOCAL_HOME=str(home),
            VIGIL_TOOLS=str(Path(sys.executable).parent.parent),
            **variables,
        )
        result = subprocess.run(
            ["bash", str(root / "tests" / "acceptance" / "storm" / "run.sh"), *arguments],
            cwd=caller,
            env=environment,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        made = [json.loads(line) for line in calls.read_text(encoding="utf-8").splitlines() if line.strip()]
        return result, made

    run.installed = installed
    run.root = root
    run.home = home
    run.caller = caller
    return run


@pytest.mark.parametrize(
    "arguments",
    [
        ["--mode"],
        ["--mode", "fast"],
        ["--deliveries", "email"],
        ["--project"],
        ["--project", ""],
        ["extra"],
    ],
)
def test_usage(script, arguments):
    """AR-Q-07 refused arguments print the usage line and exit 2 before any docker call."""
    result, calls = script(*arguments)
    assert result.returncode == 2
    assert result.stdout == USAGE
    assert calls == []


def test_needs_the_installation(script):
    """AR-Q-07 without state/values-file run.sh asks for deploy/local.sh up first."""
    result, calls = script()
    assert result.returncode == 1
    assert result.stdout == "Run deploy/local.sh up first.\n"
    assert calls == []


def test_needs_the_image(script):
    """AR-Q-07 without the image vigil/storm-driver:local run.sh asks for deploy/local.sh up first."""
    script.installed()
    result, calls = script(STUB_NO_IMAGE="1")
    assert result.returncode == 1
    assert result.stdout == "Run deploy/local.sh up first (it builds vigil/storm-driver:local).\n"
    assert [c["arguments"][:2] for c in calls] == [["image", "inspect"]]


def test_compose_call_and_clean_log(script):
    """AR-Q-07 the driver runs in the profile acceptance with the mode; a log without secrets passes the scan."""
    secrets_dir = script.installed(profile="demo")
    (secrets_dir / "vigil-app" / "installation-token").write_text(secrets.token_urlsafe(32))
    result, calls = script("--mode", "script", "--deliveries", "checklist", STUB_LOG='{"message":"started"}\n')
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1] == "AR-Q-07 passed: no secret of the installation in the log of this run."
    run = calls[1]
    assert run["arguments"] == [
        *COMPOSE,
        "vigil-local",
        "--profile",
        "acceptance",
        "run",
        "--rm",
        "-T",
        "storm-driver",
        "--mode",
        "script",
        "--deliveries",
        "checklist",
    ]
    environment = run["environment"]
    assert environment["VIGIL_LOCAL_SECRETS"] == str(secrets_dir)
    assert environment["VIGIL_LOCAL_STATE"] == str(script.home / "vigil-local" / "state")
    assert environment["VIGIL_LOCAL_UID"] == str(os.getuid()) and environment["VIGIL_LOCAL_GID"] == str(os.getgid())
    assert environment["VIGIL_LAB_PROFILE"] == "demo"
    assert environment["VIGIL_STORM_RESULTS"] == str(script.root / "tests" / "acceptance" / "results")
    assert calls[2]["arguments"] == [
        *COMPOSE,
        "vigil-local",
        "logs",
        "--no-color",
        "--no-log-prefix",
        "vigil-app",
        "vigil-migrate",
    ]
    folder = script.home / "vigil-local" / "acceptance"
    logs = list(folder.glob("vigil-*.log"))
    assert len(logs) == 1 and stat.S_IMODE(folder.stat().st_mode) == 0o700
    assert stat.S_IMODE(logs[0].stat().st_mode) == 0o600


def test_defaults_and_relative_results(script):
    """AR-Q-07 defaults immediate, fakes and vigil-local; profile test without state/lab-profile; a relative results
    folder is read from the caller's folder and created."""
    script.installed(project="other")
    result, calls = script("--project", "other", VIGIL_STORM_RESULTS="out/storm")
    assert result.returncode == 0, result.stdout + result.stderr
    run = calls[1]
    assert run["arguments"][4] == "other" and run["arguments"][-4:] == ["--mode", "immediate", "--deliveries", "fakes"]
    assert run["environment"]["VIGIL_LAB_PROFILE"] == "test"
    assert run["environment"]["VIGIL_STORM_RESULTS"] == str(script.caller / "out" / "storm")
    assert (script.caller / "out" / "storm").is_dir()


def test_secret_in_the_log_fails(script):
    """AR-Q-07 a secret value of the project's secrets folder in the log: the file name is printed, never the value,
    and the exit code is 1."""
    secrets_dir = script.installed()
    value = secrets.token_urlsafe(32)
    (secrets_dir / "vigil-app" / "installation-token").write_text(value)
    (secrets_dir / "vigil-app" / "lab-db-01-diag").write_text(
        json.dumps({"username": "vigil-diag", "private_key": "k" * 40})
    )
    log = f'{{"logger":"vigil-diagnostics","message":"token {value}"}}\n'
    result, _ = script(STUB_LOG=log)
    assert result.returncode == 1
    assert result.stdout.splitlines()[-2:] == ["vigil-app/installation-token", "AR-Q-07 failed"]
    assert value not in result.stdout and value not in result.stderr


def test_driver_failure_exits_one(script):
    """AR-Q-07 the log is scanned also after a failed driver run, and the exit code is 1."""
    script.installed()
    result, calls = script(STUB_DRIVER_EXIT="1", STUB_LOG="clean\n")
    assert result.returncode == 1
    assert "AR-Q-07 passed: no secret of the installation in the log of this run." in result.stdout
    assert "logs" in calls[-1]["arguments"]
