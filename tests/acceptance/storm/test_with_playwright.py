"""Tests of tests/acceptance/storm/with-playwright.sh (P-28 spec Section 6.11): fake deploy/local.sh, run.sh and docker.

The script runs from a temporary copy of the repository. Every fake appends one JSON line to STUB_CALLS. The fake
deploy/local.sh (the suite) writes the marker after STUB_SUITE_READY seconds (none when it is empty), then sleeps
STUB_SUITE_SLEEP seconds and exits STUB_SUITE_EXIT; the fake run.sh (the driver) exits STUB_DRIVER_EXIT; the fake docker
lists two containers for `ps`.
"""

import json
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent.parent.parent
SCRIPT = "tests/acceptance/storm/with-playwright.sh"
USAGE = "Usage: tests/acceptance/storm/with-playwright.sh [--mode immediate|script] [--project NAME]\n"

RECORD = textwrap.dedent(
    """\
    import json
    import os
    import sys
    import time

    def record(name, **extra):
        with open(os.environ["STUB_CALLS"], "a") as log:
            log.write(json.dumps({"name": name, "arguments": sys.argv[1:], "cwd": os.getcwd(), **extra}) + "\\n")
    """
)
SUITE = RECORD + textwrap.dedent(
    """\
    project = sys.argv[sys.argv.index("--project") + 1]
    mode_file = os.path.join(os.environ["VIGIL_LOCAL_HOME"], project, "e2e", "storm-mode")
    mode = open(mode_file).read() if os.path.exists(mode_file) else None
    record("suite", mode=mode)
    ready = os.environ.get("STUB_SUITE_READY", "")
    if ready != "":
        time.sleep(float(ready))
        folder = os.path.join(os.environ["VIGIL_LOCAL_HOME"], project, "e2e")
        os.makedirs(folder, exist_ok=True)
        open(os.path.join(folder, "storm-ready"), "w").close()
        record("marker")
    time.sleep(float(os.environ.get("STUB_SUITE_SLEEP", "0")))
    record("suite finished")
    sys.exit(int(os.environ.get("STUB_SUITE_EXIT", "0")))
    """
)
DRIVER = RECORD + textwrap.dedent(
    """\
    record("driver", results=os.environ.get("VIGIL_STORM_RESULTS"))
    sys.exit(int(os.environ.get("STUB_DRIVER_EXIT", "0")))
    """
)
DOCKER = RECORD + textwrap.dedent(
    """\
    record("docker")
    if sys.argv[1:2] == ["ps"]:
        print("c1")
        print("c2")
    """
)


def executable(path, text):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(f"#!{sys.executable}\n" + text, encoding="utf-8")
    path.chmod(0o755)


@pytest.fixture
def script(tmp_path):
    root = tmp_path / "repository"
    (root / SCRIPT).parent.mkdir(parents=True)
    shutil.copy2(ROOT / SCRIPT, root / SCRIPT)
    executable(root / "deploy" / "local.sh", SUITE)
    executable(root / "tests" / "acceptance" / "storm" / "run.sh", DRIVER)
    stubs = tmp_path / "bin"
    executable(stubs / "docker", DOCKER)
    home = tmp_path / "home"
    calls = tmp_path / "calls.log"
    caller = tmp_path / "caller"
    caller.mkdir()

    def installed(project="vigil-local"):
        state = home / project / "state"
        state.mkdir(parents=True, exist_ok=True)
        (state / "values-file").write_text("/x/local.yaml\n")

    def run(*arguments, **variables):
        calls.write_text("", encoding="utf-8")
        environment = {k: v for k, v in os.environ.items() if not k.startswith(("STUB_", "VIGIL_"))}
        environment.update(
            PATH=os.pathsep.join([str(stubs), environment.get("PATH", "")]),
            STUB_CALLS=str(calls),
            VIGIL_LOCAL_HOME=str(home),
            VIGIL_STORM_READY_WAIT="3",
            **variables,
        )
        result = subprocess.run(
            ["bash", str(root / SCRIPT), *arguments],
            cwd=caller,
            env=environment,
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        lines = [json.loads(line) for line in calls.read_text(encoding="utf-8").splitlines() if line.strip()]
        return result, lines

    run.installed = installed
    run.root = root
    run.home = home
    run.caller = caller
    return run


def names(calls):
    return [call["name"] for call in calls]


def stopped(calls):
    docker = [call["arguments"] for call in calls if call["name"] == "docker"]
    return docker == [
        [
            "ps",
            "-aq",
            "--filter",
            "label=com.docker.compose.project=vigil-local",
            "--filter",
            "label=com.docker.compose.service=e2e",
        ],
        ["rm", "-f", "c1", "c2"],
    ]


@pytest.mark.parametrize("arguments", [["--mode"], ["--mode", "fast"], ["--project"], ["--other"]])
def test_usage(script, arguments):
    """BP-W-02 refused arguments print the usage line and exit 2."""
    result, calls = script(*arguments)
    assert result.returncode == 2
    assert result.stdout == USAGE
    assert calls == []


def test_needs_the_installation(script):
    """BP-W-02 without the values-file of deploy/local.sh up the script stops before the suite."""
    result, calls = script()
    assert result.returncode == 1
    assert result.stdout == "Run deploy/local.sh up first.\n"
    assert calls == []


def test_order_and_success(script):
    """BP-W-02 the suite starts, the marker is awaited, then the driver runs; exit 0 when both pass."""
    script.installed("p2")
    marker = script.home / "p2" / "e2e" / "storm-ready"
    marker.parent.mkdir(parents=True)
    marker.write_text("an earlier run")
    result, calls = script("--mode", "script", "--project", "p2", STUB_SUITE_READY="1", STUB_SUITE_SLEEP="3")
    assert result.returncode == 0, result.stdout + result.stderr
    assert names(calls) == ["suite", "marker", "driver", "suite finished"]
    assert calls[0]["arguments"] == ["e2e", "storm", "--project", "p2"]
    assert calls[2]["arguments"] == ["--mode", "script", "--acts", "driver", "--project", "p2"]
    assert calls[0]["cwd"] == str(script.root)
    assert not any(call["name"] == "docker" for call in calls)


def test_default_mode_is_immediate(script):
    """BP-W-02 without --mode the driver runs in mode immediate on the project vigil-local."""
    script.installed()
    result, calls = script(STUB_SUITE_READY="0")
    assert result.returncode == 0, result.stdout + result.stderr
    driver = [call for call in calls if call["name"] == "driver"]
    assert driver[0]["arguments"] == ["--mode", "immediate", "--acts", "pages", "--project", "vigil-local"]


def test_marker_never_comes(script):
    """BP-W-02 exit 1 and the suite stopped when the marker does not come in time."""
    script.installed()
    result, calls = script(STUB_SUITE_SLEEP="30")
    assert result.returncode == 1
    assert result.stdout.endswith("The storm suite did not start.\n")
    assert "driver" not in names(calls)
    assert "suite finished" not in names(calls)
    assert stopped(calls)


def test_suite_ends_first(script):
    """BP-W-02 exit 1 and the stop when the suite ends before its marker."""
    script.installed()
    result, calls = script(STUB_SUITE_EXIT="1")
    assert result.returncode == 1
    assert result.stdout.endswith("The storm suite did not start.\n")
    assert "driver" not in names(calls)
    assert stopped(calls)


def test_driver_fails(script):
    """BP-W-02 exit 1 and the suite stopped when the driver fails."""
    script.installed()
    result, calls = script(STUB_SUITE_READY="0", STUB_SUITE_SLEEP="30", STUB_DRIVER_EXIT="1")
    assert result.returncode == 1
    assert names(calls)[:3] == ["suite", "marker", "driver"]
    assert "suite finished" not in names(calls)
    assert stopped(calls)


def test_suite_fails_after_the_driver(script):
    """BP-W-02 exit 1 when the suite fails after the driver passed."""
    script.installed()
    result, calls = script(STUB_SUITE_READY="0", STUB_SUITE_SLEEP="3", STUB_SUITE_EXIT="1")
    assert result.returncode == 1
    assert names(calls) == ["suite", "marker", "driver", "suite finished"]
    assert not any(call["name"] == "docker" for call in calls)


def test_relative_results_folder(script):
    """BP-W-02 a relative VIGIL_STORM_RESULTS is made absolute against the caller's folder."""
    script.installed()
    result, calls = script(STUB_SUITE_READY="0", VIGIL_STORM_RESULTS="storm-results")
    assert result.returncode == 0, result.stdout + result.stderr
    driver = [call for call in calls if call["name"] == "driver"]
    assert driver[0]["results"] == str(script.caller / "storm-results")
    absolute, calls = script(STUB_SUITE_READY="0", VIGIL_STORM_RESULTS="/x/storm")
    assert [call for call in calls if call["name"] == "driver"][0]["results"] == "/x/storm"


def test_mode_written_before_the_suite(script):
    """BP-W-02 the mode is written to storm-mode before the suite starts."""
    script.installed("p3")
    old = script.home / "p3" / "e2e" / "storm-mode"
    result, calls = script("--mode", "script", "--project", "p3", STUB_SUITE_READY="0")
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls[0]["name"] == "suite"
    assert calls[0]["mode"] == "script\n"
    assert old.read_text(encoding="utf-8") == "script\n"
    result, calls = script("--project", "p3", STUB_SUITE_READY="0")
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls[0]["mode"] == "immediate\n"


def test_acts_by_mode(script):
    """BP-W-02 the pages act in mode immediate and the driver in mode script."""
    script.installed("p4")
    acts = {}
    for mode in ("immediate", "script"):
        result, calls = script("--mode", mode, "--project", "p4", STUB_SUITE_READY="0")
        assert result.returncode == 0, result.stdout + result.stderr
        driver = [call for call in calls if call["name"] == "driver"]
        assert driver[0]["arguments"][:2] == ["--mode", mode]
        acts[mode] = driver[0]["arguments"][2:4]
    assert acts == {"immediate": ["--acts", "pages"], "script": ["--acts", "driver"]}
