"""Tests of deploy/status.sh and deploy/main/host-status.sh (P-05 and P-32 spec Section 7)."""

import re
import shutil
import subprocess

from conftest import REPOSITORY, tags_json, write_lock

EMBED = "f" * 64
MARKER = "bootstrap main 1 docker 28.2.2 compose 2.40.3"


def table(harness, tags, curl_code=0):
    harness.table(
        [
            ("curl", r".", [("", curl_code)]) if curl_code else ("curl", r"^$", [("", 0)]),
            ("docker", r"^version --format", [("28.2.2\n", 0)]),
            ("docker", r"^compose version --short$", [("2.40.3\n", 0)]),
            ("docker", r"^compose -p vigil ps --all", [("NAME STATE STATUS\nsse-probe running Up 5 minutes\n", 0)]),
            ("docker", r"^ps --filter label=com\.docker\.compose\.project=vigil", [("sse-probe\nvigil-embed\n", 0)]),
            ("docker", r"^inspect --format \{\{with index", [("172.30.0.11\n", 0)]),
            ("curl", r"http://172\.30\.0\.10:8080/actuator/health$", [('{"status":"UP"}', 0)]),
            ("curl", r"/api/tags$", [(tags, 0)]),
        ]
    )


def test_stopped_main_host(harness):
    """AR-H-12 a stopped main host: status.sh prints the states only and exits 0."""
    harness.seed(instances={"i-0main": {"Name": "vigil-demo-main", "State": "stopped"}})
    result = harness.run("deploy/status.sh", "demo")
    assert result.returncode == 0
    assert result.stdout.splitlines() == ["main host: i-0main stopped", "gpu host: not created"]
    assert harness.aws_calls("ssm", "send-command") == []

    harness.seed()
    result = harness.run("deploy/status.sh", "demo")
    assert result.stdout.splitlines() == ["main host: not created", "gpu host: not created"]


def test_running_main_host(harness):
    """AR-H-12 a running main host: the parts of status in order, before and after a deployment, and the models."""
    harness.seed(
        instances={
            "i-0main": {"Name": "vigil-demo-main", "State": "running"},
            "i-0gpu": {"Name": "vigil-demo-gpu", "State": "stopped"},
        }
    )
    (harness.vigil_root / "bootstrap-done").write_text(MARKER + "\n")
    table(harness, tags_json({"embeddinggemma:latest": EMBED}))
    result = harness.run("deploy/status.sh", "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines() == [
        "main host: i-0main running",
        "gpu host: i-0gpu stopped",
        MARKER,
        "docker 28.2.2",
        "compose 2.40.3",
        "not deployed",
        "NAME STATE STATUS",
        "sse-probe running Up 5 minutes",
        "not deployed",
        'vigil-app health: {"status":"UP"}',
        "not deployed",
    ]

    deploy = harness.vigil_root / "deploy"
    deploy.mkdir()
    shutil.copy2(REPOSITORY / "deploy" / "units.yaml", deploy / "units.yaml")
    write_lock(deploy / "models.lock", EMBED, "unrecorded")
    (deploy / "deployed.yaml").write_text('release: "r1"\ndeployed_at: "2026-10-17T10:00:00Z"\nimages:\n')
    result = harness.run("deploy/status.sh", "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[5:] == [
        'release: "r1"',
        'deployed_at: "2026-10-17T10:00:00Z"',
        "NAME STATE STATUS",
        "sse-probe running Up 5 minutes",
        "no unit with secrets is running",
        'vigil-app health: {"status":"UP"}',
        f"embeddinggemma {EMBED} matches models.lock",
    ]

    write_lock(deploy / "models.lock", "unrecorded", "unrecorded")
    result = harness.run("deploy/status.sh", "demo")
    assert result.stdout.splitlines()[-1] == f"embeddinggemma {EMBED} not recorded in models.lock"
    write_lock(deploy / "models.lock", "0" * 64, "unrecorded")
    result = harness.run("deploy/status.sh", "demo")
    assert result.stdout.splitlines()[-1] == f"embeddinggemma {EMBED} differs from models.lock"

    table(harness, tags_json({}))
    result = harness.run("deploy/status.sh", "demo")
    assert result.stdout.splitlines()[-1] == "embeddinggemma: not in vigil-embed"
    table(harness, "", curl_code=7)
    result = harness.run("deploy/status.sh", "demo")
    assert result.returncode == 0
    assert result.stdout.splitlines()[-2:] == [
        "vigil-app does not answer on port 8080",
        "vigil-embed does not answer on port 11434",
    ]


HEALTH = (
    '{"status":"UP","components":{"correlation":{"status":"UP"},"db":{"status":"UP",'
    '"details":{"database":"PostgreSQL","validationQuery":"isValid()"}},"livenessState":{"status":"UP"}}}'
)


def test_health_with_components(harness):
    """AR-H-12 status.sh prints the health with its components."""
    harness.seed(instances={"i-0main": {"Name": "vigil-demo-main", "State": "running"}})
    (harness.vigil_root / "bootstrap-done").write_text(MARKER + "\n")
    harness.table(
        [
            ("docker", r"^version --format", [("28.2.2\n", 0)]),
            ("docker", r"^compose version --short$", [("2.40.3\n", 0)]),
            ("docker", r"^compose -p vigil ps --all", [("NAME STATE STATUS\nvigil-app running Up 5 minutes\n", 0)]),
            ("docker", r"^ps --filter label=com\.docker\.compose\.project=vigil", [("vigil-app\n", 0)]),
            ("curl", r"http://172\.30\.0\.10:8080/actuator/health$", [(HEALTH, 0)]),
        ]
    )
    result = harness.run("deploy/status.sh", "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    assert f"vigil-app health: {HEALTH}" in result.stdout.splitlines()
    main = (REPOSITORY / "deploy" / "main" / "compose.yaml").read_text()
    assert "--management.endpoint.health.show-details=always" in main


def test_here_strings(harness):
    """CTX-D-084 the here-string finds a name among 200,000 (each changed script)."""
    scripts = ["deploy/edge.sh", "deploy/main/host-deploy.sh", "deploy/main/host-status.sh",
               "deploy/gpu/host-gpu-status.sh"]  # fmt: skip
    for script in scripts:
        text = (REPOSITORY / script).read_text()
        assert not re.search(r"printf '%s\\n' \"\$\w+\" \| grep", text), script
        lines = re.findall(r"grep -[a-z]*x[a-z]* .*<<< \"\$\w+\"", text)
        assert lines, script
        for line in lines:
            pattern = re.search(r"grep (-[a-z]+) (.*?) <<< \"\$(\w+)\"", line)
            flags, words, variable = pattern.groups()
            needle = "name-1"
            program = (
                "set -euo pipefail\n"
                f'{variable}=$(printf "%s\\n" {needle} $(seq -f "name-%g" 2 200000))\n'
                f'if grep {flags} "{needle}" <<< "${variable}"; then echo found; else echo missing; fi\n'
            )
            result = subprocess.run(["bash", "-c", program], capture_output=True, text=True, check=True)
            assert result.stdout.splitlines()[-1] == "found", (script, line, words)

    # host-status.sh with 200,000 running containers: the units and the model are found.
    harness.seed(instances={"i-0main": {"Name": "vigil-demo-main", "State": "running"}})
    deploy = harness.vigil_root / "deploy"
    deploy.mkdir()
    shutil.copy2(REPOSITORY / "deploy" / "units.yaml", deploy / "units.yaml")
    write_lock(deploy / "models.lock", EMBED, "unrecorded")
    names = "vigil-app\nvigil-embed\n" + "\n".join(f"name-{n}" for n in range(1, 200001)) + "\n"
    harness.table(
        [
            ("docker", r"^ps --filter label=com\.docker\.compose\.project=vigil", [(names, 0)]),
            ("docker", r"^inspect --format \{\{with index", [("172.30.0.11\n", 0)]),
            ("curl", r"/api/tags$", [(tags_json({"embeddinggemma:latest": EMBED}), 0)]),
        ]
    )
    result = harness.run("deploy/main/host-status.sh")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "vigil-app: secrets readable" in result.stdout.splitlines()
    assert result.stdout.splitlines()[-1] == f"embeddinggemma {EMBED} matches models.lock"
