"""Tests of deploy/build.sh and deploy/deploy.sh (P-05 spec Section 7)."""

from conftest import ACCOUNT, REGISTRY

MAIN = {"i-0main": {"Name": "vigil-demo-main", "State": "running"}}
UPLOADED = [
    "compose.yaml",
    "host-deploy.sh",
    "units.yaml",
    "secrets.sh",
    "web_config.py",
    "models.lock",
    "values/demo.yaml",
]


def test_build(harness):
    """AR-H-11 build.sh refuses a bad release name and an existing tag, and builds and pushes for linux/amd64."""
    for arguments in ([], ["Bad_Name"], ["-r1"], ["r1", "r2"]):
        result = harness.run("deploy/build.sh", *arguments)
        assert result.returncode == 2
        assert result.stdout.strip() == "Usage: deploy/build.sh {release}"

    harness.seed(images={"demo/sse-probe": ["r1"]})
    result = harness.run("deploy/build.sh", "r1")
    assert result.returncode == 1
    assert result.stdout.strip() == "Release r1 already exists in demo/sse-probe."
    assert harness.fake_calls() == []

    harness.seed(images={"demo/sse-probe": []})
    result = harness.run("deploy/build.sh", "p05-1")
    assert result.returncode == 0, result.stdout + result.stderr
    tag = f"{REGISTRY}/demo/sse-probe:p05-1"
    assert result.stdout.splitlines()[-1] == f"built {tag}"
    assert [call["args"] for call in harness.fake_calls("docker")] == [
        ["login", "--username", "AWS", "--password-stdin", REGISTRY],
        [
            "buildx",
            "build",
            "--platform",
            "linux/amd64",
            "--provenance=false",
            "--tag",
            tag,
            "--push",
            "spikes/sse-probe",
        ],
        ["logout", REGISTRY],
    ]


def test_deploy_uploads(harness):
    """BP-A-21 deploy.sh uploads the seven files to the bucket; it refuses a missing image or a stopped host."""
    harness.seed(images={"demo/sse-probe": ["r1"]}, instances=MAIN)
    harness.table([("docker", r"config --quiet$", [("", 1)])])
    harness.run("deploy/deploy.sh", "demo", "r1")
    bucket = f"vigil-demo-deploy-{ACCOUNT}"
    assert sorted(harness.state()["objects"]) == sorted(f"s3://{bucket}/releases/r1/{name}" for name in UPLOADED)

    harness.seed(images={"demo/sse-probe": ["r1"]}, instances=MAIN)
    result = harness.run("deploy/deploy.sh", "demo", "r2")
    assert result.returncode == 1
    assert result.stdout.strip() == "Image demo/sse-probe:r2 is missing (deploy/build.sh)."
    assert "objects" not in harness.state()

    harness.seed(
        images={"demo/sse-probe": ["r1"]}, instances={"i-0main": {"Name": "vigil-demo-main", "State": "stopped"}}
    )
    result = harness.run("deploy/deploy.sh", "demo", "r1")
    assert result.returncode == 1
    assert result.stdout.strip() == (
        "The main host is stopped. Start it first (deploy/edge.md, or deploy/gpu.sh start for the GPU host)."
    )
    assert "objects" not in harness.state()

    harness.seed(images={"demo/sse-probe": ["r1"]})
    result = harness.run("deploy/deploy.sh", "demo", "r1")
    assert result.returncode == 1
    assert result.stdout.strip() == "The main host does not exist yet (deploy/edge.sh)."

    for arguments in (["demo"], ["unknown", "r1"], ["demo", "R1"]):
        result = harness.run("deploy/deploy.sh", *arguments)
        assert result.returncode == 2
        assert result.stdout.strip() == "Usage: deploy/deploy.sh {installation} {release}"


def test_deploy_on_the_host(harness):
    """AR-H-11 deploy.sh copies the seven files into {VIGIL_ROOT}/deploy/ on the host and runs host-deploy.sh."""
    harness.seed(images={"demo/sse-probe": ["r1"]}, instances=MAIN)
    harness.table([("docker", r"config --quiet$", [("", 1)])])
    result = harness.run("deploy/deploy.sh", "demo", "r1")
    # The compose fake rejects the file: host-deploy.sh stops at its step 2 and deploy.sh passes its exit code on.
    assert result.returncode == 1
    assert result.stdout.strip() == "The compose file is not valid for this Docker Compose (AR-T-18)."
    deploy = harness.vigil_root / "deploy"
    sources = {"compose.yaml": "main/compose.yaml", "host-deploy.sh": "main/host-deploy.sh"}
    for name in UPLOADED:
        assert (deploy / name).read_bytes() == (harness.root / "deploy" / sources.get(name, name)).read_bytes()
    assert (deploy / ".env").read_text() == f"VIGIL_REGISTRY={REGISTRY}\nVIGIL_RELEASE=r1\nVIGIL_INSTALLATION=demo\n"
    assert harness.fake_calls("docker")[0]["args"] == [
        "compose", "-p", "vigil", "-f", "compose.yaml", "--env-file", ".env", "config", "--quiet"
    ]  # fmt: skip
    command = harness.aws_calls("ssm", "send-command")[0]["args"]
    assert command[command.index("--instance-ids") + 1] == "i-0main"
    assert command[command.index("--document-name") + 1] == "AWS-RunShellScript"
    parameters = next(iter(harness.state()["commands"].values()))["Parameters"]
    assert parameters["executionTimeout"] == ["3600"]
    assert parameters["commands"][-5] == f'bash "$D/host-fetch.sh" vigil-demo-deploy-{ACCOUNT} r1 demo {REGISTRY}'
    assert not list((harness.vigil_root / "run").iterdir())
