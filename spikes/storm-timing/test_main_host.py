"""Tests of spikes/storm-timing/main-host.sh and host-spike.sh (P-32 spec Section 7): the fake AWS CLI runs the host
part locally (run_on_host), the docker fake answers for the host, and deploy/fetch.sh is a stub."""

import importlib.util
import json
import shutil
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent.parent
_spec = importlib.util.spec_from_file_location("deploy_harness", ROOT / "deploy" / "conftest.py")
deploy_harness = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(deploy_harness)

REGISTRY = deploy_harness.REGISTRY
MAIN = {"i-0main": {"Name": "vigil-demo-main", "State": "running"}}
FETCH_STUB = """#!/usr/bin/env bash
# Stub of deploy/fetch.sh: records its arguments and writes the local file.
printf '{"command": "fetch.sh", "args": [%s]}\\n' "$(printf '"%s",' "$@" | sed 's/,$//')" >> "$FAKE_LOG"
mkdir -p "$(dirname "$3")"
echo '{"passed": true}' > "$3"
"""


@pytest.fixture
def harness(tmp_path):
    harness = deploy_harness.Harness(tmp_path)
    target = harness.root / "spikes" / "storm-timing"
    target.mkdir(parents=True)
    for name in ("main-host.sh", "host-spike.sh"):
        shutil.copy2(ROOT / "spikes" / "storm-timing" / name, target / name)
    (harness.root / "deploy" / "fetch.sh").write_text(FETCH_STUB)
    deploy = harness.vigil_root / "deploy"
    deploy.mkdir()
    (deploy / ".env").write_text(f"VIGIL_REGISTRY={REGISTRY}\nCOMPOSE_PROFILES=spike\nVIGIL_LAB_PROFILE=demo\n")
    return harness


def spike_table(harness, recorder="running", states=("running 0", "exited 0")):
    ps = f"NAME\tSTATE\tSTATUS\nlab-db-01\trunning\tUp\ndev-recorder\t{recorder}\tUp 3 minutes (healthy)\n"
    harness.table(
        [
            ("docker", r"^compose -p vigil ps --all", [(ps, 0)]),
            ("docker", r"^container inspect storm-spike", [("[]", 0)]),
            ("docker", r"^inspect --format \{\{\.State\.Status\}\}", [(s + "\n", 0) for s in states]),
            ("docker", r"^logs --tail 3 storm-spike", [("run 10 standard\nrun 11 read-only\nresult written\n", 0)]),
        ]
    )


def test_starts_polls_and_fetches(harness):
    """SB-T-01 main-host.sh starts the spike on lab-net with the secrets and data, polls, and fetches the result."""
    harness.seed(instances=MAIN, images={"demo/storm-spike": ["p32-1"]})
    spike_table(harness)
    result = harness.run("spikes/storm-timing/main-host.sh", "demo", "p32-1", "--storms", "2", "--extra", "clear")
    assert result.returncode == 0, result.stdout + result.stderr
    docker = [call["args"] for call in harness.fake_calls("docker")]
    image = f"{REGISTRY}/demo/storm-spike:p32-1"
    assert ["pull", "--quiet", image] in docker
    assert ["rm", "-f", "storm-spike"] in docker
    run = next(arguments for arguments in docker if arguments[0] == "run")
    root = str(harness.vigil_root)
    out = run[-1]
    assert run[:-1] == [
        "run", "-d", "--name", "storm-spike", "--network", "lab-net", "-e", "VIGIL_LAB_PROFILE=demo",
        "-v", f"{root}/secrets/vigil-app:/run/secrets/vigil-app:ro",
        "-v", f"{root}/secrets/demo-control:/run/secrets/demo-control:ro",
        "-v", f"{root}/deploy/spike/demo-data:/demo-data:ro",
        "-v", f"{root}/spike/results:/results",
        image, "--storms", "2", "--extra", "clear", "--out",
    ]  # fmt: skip
    assert out.startswith("/results/storm-timing-") and out.endswith("Z.json")
    file = out[len("/results/") :]
    assert (harness.vigil_root / "spike" / "results").is_dir()
    states = [a for a in docker if a[:2] == ["inspect", "--format"]]
    assert len(states) == 2
    fetch = harness.fake_calls("fetch.sh")
    assert fetch == [
        {"command": "fetch.sh", "args": ["demo", f"/srv/vigil/spike/results/{file}", f"spikes/results/{file}"]}
    ]
    assert json.loads((harness.root / "spikes" / "results" / file).read_text()) == {"passed": True}
    lines = result.stdout.splitlines()
    assert any(line.endswith("state exited 0") for line in lines)
    assert "result written" in lines
    assert lines[-1] == f"result file spikes/results/{file}"


def test_spike_exit_code(harness):
    """SB-T-01 main-host.sh exits with the spike's exit code after fetching its result."""
    harness.seed(instances=MAIN)
    spike_table(harness, states=("exited 1",))
    result = harness.run("spikes/storm-timing/main-host.sh", "demo", "p32-1")
    assert result.returncode == 1
    assert len(harness.fake_calls("fetch.sh")) == 1
    run = next(c["args"] for c in harness.fake_calls("docker") if c["args"][0] == "run")
    assert run[-3:-1] == [f"{REGISTRY}/demo/storm-spike:p32-1", "--out"]


def test_refused_without_the_spike_deployment(harness):
    """SB-T-01 main-host.sh refuses without the spike deployment."""
    harness.seed(instances=MAIN)
    for recorder in ("exited", None):
        spike_table(harness, recorder=recorder or "running")
        if recorder is None:
            harness.table(
                [("docker", r"^compose -p vigil ps --all", [("NAME\tSTATE\tSTATUS\nvigil-app\trunning\tUp\n", 0)])]
            )
        harness.log_path.write_text("")
        result = harness.run("spikes/storm-timing/main-host.sh", "demo", "p32-1")
        assert result.returncode == 1
        assert result.stdout.splitlines()[-1] == (
            "dev-recorder is not running: deploy the spike first (deploy/deploy.sh demo p32-1 --spike)."
        )
        assert not [c for c in harness.fake_calls("docker") if c["args"][0] in ("run", "pull")]
    for arguments in ([], ["demo"], ["demo", "R1"], ["demo", "p32-1", "--storms"], ["demo", "p32-1", "--other", "x"]):
        result = harness.run("spikes/storm-timing/main-host.sh", *arguments)
        assert result.returncode == 2
        assert result.stdout.strip() == (
            "Usage: spikes/storm-timing/main-host.sh {installation} {release} [--storms N] [--extra LIST]"
        )
