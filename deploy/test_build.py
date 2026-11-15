"""Tests of deploy/build.sh and deploy/deploy.sh (P-05 and P-32 spec Section 7)."""

from conftest import ACCOUNT, REGISTRY

MAIN = {"i-0main": {"Name": "vigil-demo-main", "State": "running"}}
IMAGES = [
    ("vigil-app", "app"),
    ("demo-control", "demo/control"),
    ("demo-lab", "demo/lab"),
    ("lab-zabbix-provision", "demo/monitoring/provision"),
    ("demo-faults", "demo/faults"),
    ("dev-recorder", "dev/recorder"),
    ("storm-spike", "spikes/storm-timing"),
]
VIGIL_IMAGES = ["vigil-app", "demo-lab", "lab-zabbix-provision", "demo-faults", "demo-control"]
SPIKE_IMAGES = ["vigil-app", "demo-lab", "lab-zabbix-provision", "demo-faults", "dev-recorder"]
UPLOADED = [
    "compose.yaml",
    "host-deploy.sh",
    "units.yaml",
    "secrets.sh",
    "web_config.py",
    "models.lock",
    "values/demo.yaml",
    "db/init/10-vigil-roles.sh",
    "monitoring/prometheus/prometheus.yml",
    "monitoring/prometheus/lab-rules.yml",
    "monitoring/alertmanager/alertmanager.yml",
]
SPIKE_DATA = [
    "README.md",
    "01-tenant-identity.yaml",
    "02-service-map.yaml",
    "03-monitoring.yaml",
    "04-operations-actions-recovery.yaml",
    "05-routing-escalation.yaml",
    "06-workflow.yaml",
    "07-settings.yaml",
    "08-runbooks.md",
    "09-past-incidents.csv",
    "10-lab-and-faults.yaml",
]
SOURCES = {
    "compose.yaml": "deploy/main/compose.yaml",
    "host-deploy.sh": "deploy/main/host-deploy.sh",
    "units.yaml": "deploy/units.yaml",
    "secrets.sh": "deploy/secrets.sh",
    "web_config.py": "deploy/web_config.py",
    "models.lock": "deploy/models.lock",
    "values/demo.yaml": "deploy/values/demo.yaml",
    "db/init/10-vigil-roles.sh": "deploy/db/init/10-vigil-roles.sh",
    "monitoring/prometheus/prometheus.yml": "demo/monitoring/prometheus/prometheus.yml",
    "monitoring/prometheus/lab-rules.yml": "demo/monitoring/prometheus/lab-rules.yml",
    "monitoring/alertmanager/alertmanager.yml": "demo/monitoring/alertmanager/alertmanager.yml",
}


def images(names, tags):
    return {f"demo/{name}": list(tags) for name in names}


def test_build_refusals(harness):
    """AR-H-11 build.sh refuses a bad release name and a release that exists in any of the seven repositories."""
    for arguments in ([], ["Bad_Name"], ["-r1"], ["r1", "r2"]):
        result = harness.run("deploy/build.sh", *arguments)
        assert result.returncode == 2
        assert result.stdout.strip() == "Usage: deploy/build.sh {release}"

    for name, _ in IMAGES:
        harness.seed(images={**images([n for n, _ in IMAGES], []), f"demo/{name}": ["r1"]})
        result = harness.run("deploy/build.sh", "r1")
        assert result.returncode == 1
        assert result.stdout.strip() == f"Release r1 already exists in demo/{name}."
        assert harness.fake_calls() == []


def test_build(harness):
    """BP-W-03 build.sh builds the JARs, then the seven images for linux/amd64."""
    harness.seed(images=images([n for n, _ in IMAGES], []))
    result = harness.run("deploy/build.sh", "p32-1", VIGIL_UBUNTU_MIRROR="")
    assert result.returncode == 0, result.stdout + result.stderr
    calls = harness.fake_calls()
    assert calls[0] == {"command": "mvnw", "args": ["-B", "-q", "-pl", "app,demo/control", "-DskipTests", "package"]}
    expected = []
    for name, folder in IMAGES:
        tag = f"{REGISTRY}/demo/{name}:p32-1"
        expected += [
            ["login", "--username", "AWS", "--password-stdin", REGISTRY],
            ["buildx", "build", "--platform", "linux/amd64", "--provenance=false", "--tag", tag, "--push", folder],
            ["logout", REGISTRY],
        ]
    assert [call["args"] for call in calls[1:]] == expected
    assert [line for line in result.stdout.splitlines() if line.startswith("built ")] == [
        f"built {REGISTRY}/demo/{name}:p32-1" for name, _ in IMAGES
    ]
    assert "sse-probe" not in (harness.root / "deploy" / "build.sh").read_text()


def test_build_mirror(harness):
    """BP-R-04 the Ubuntu mirror reaches every image build."""
    harness.seed(images=images([n for n, _ in IMAGES], []))
    mirror = "http://azure.archive.ubuntu.com/ubuntu/"
    result = harness.run("deploy/build.sh", "p32-2", VIGIL_UBUNTU_MIRROR=mirror)
    assert result.returncode == 0, result.stdout + result.stderr
    builds = [call["args"] for call in harness.fake_calls("docker") if call["args"][0] == "buildx"]
    assert len(builds) == 7
    for arguments in builds:
        assert arguments[-3:-1] == ["--build-arg", f"UBUNTU_MIRROR={mirror}"]
        assert arguments[2:4] == ["--platform", "linux/amd64"]


def test_deploy_uploads(harness):
    """BP-A-21 deploy.sh uploads the deployment files of P-32; --spike adds the data."""
    bucket = f"vigil-demo-deploy-{ACCOUNT}"
    harness.seed(images=images(VIGIL_IMAGES, ["r1"]), instances=MAIN)
    harness.table([("docker", r"config --quiet$", [("", 1)])])
    harness.run("deploy/deploy.sh", "demo", "r1")
    objects = harness.state()["objects"]
    assert sorted(objects) == sorted(f"s3://{bucket}/releases/r1/{name}" for name in UPLOADED)
    deploy = harness.vigil_root / "deploy"
    for name, source in SOURCES.items():
        assert (deploy / name).read_bytes() == (harness.root / source).read_bytes(), name

    harness.seed(images=images(SPIKE_IMAGES, ["r2"]), instances=MAIN)
    harness.run("deploy/deploy.sh", "demo", "r2", "--spike")
    objects = harness.state()["objects"]
    spike = [f"spike/demo-data/{name}" for name in SPIKE_DATA]
    assert sorted(objects) == sorted(f"s3://{bucket}/releases/r2/{name}" for name in UPLOADED + spike)
    for name in SPIKE_DATA:
        content = (deploy / "spike" / "demo-data" / name).read_bytes()
        assert content == (harness.root / "docs" / "31-demo-data" / name).read_bytes()

    for arguments in (["demo"], ["unknown", "r1"], ["demo", "R1"], ["demo", "r1", "--other"], ["demo", "r1", "x", "y"]):
        result = harness.run("deploy/deploy.sh", *arguments)
        assert result.returncode == 2
        assert result.stdout.strip() == "Usage: deploy/deploy.sh {installation} {release} [--spike]"


def test_deploy_images(harness):
    """AR-H-11 deploy.sh checks the images of the chosen profile."""
    harness.seed(images=images(VIGIL_IMAGES, ["r1"]) | images(["dev-recorder"], []), instances=MAIN)
    harness.table([("docker", r"config --quiet$", [("", 1)])])
    result = harness.run("deploy/deploy.sh", "demo", "r1", "--spike")
    assert result.returncode == 1
    assert result.stdout.strip() == "Image demo/dev-recorder:r1 is missing (deploy/build.sh)."
    assert "objects" not in harness.state()
    checked = [c["args"][c["args"].index("--repository-name") + 1] for c in harness.aws_calls("ecr", "describe-images")]
    assert checked == [f"demo/{name}" for name in SPIKE_IMAGES]

    harness.seed(images=images(SPIKE_IMAGES, ["r1"]), instances=MAIN)
    result = harness.run("deploy/deploy.sh", "demo", "r1")
    assert result.returncode == 1
    assert result.stdout.strip() == "Image demo/demo-control:r1 is missing (deploy/build.sh)."
    checked = [c["args"][c["args"].index("--repository-name") + 1] for c in harness.aws_calls("ecr", "describe-images")]
    assert checked == [f"demo/{name}" for name in VIGIL_IMAGES]

    harness.seed(images=images(VIGIL_IMAGES, ["r1"]), instances=MAIN)
    harness.table([("docker", r"config --quiet$", [("", 1)])])
    result = harness.run("deploy/deploy.sh", "demo", "r1")
    assert result.stdout.strip() == "The compose file is not valid for this Docker Compose (AR-T-18)."

    harness.seed(
        images=images(VIGIL_IMAGES, ["r1"]), instances={"i-0main": {"Name": "vigil-demo-main", "State": "stopped"}}
    )
    result = harness.run("deploy/deploy.sh", "demo", "r1")
    assert result.returncode == 1
    assert result.stdout.strip() == (
        "The main host is stopped. Start it first (deploy/edge.md, or deploy/gpu.sh start for the GPU host)."
    )
    assert "objects" not in harness.state()

    harness.seed(images=images(VIGIL_IMAGES, ["r1"]))
    result = harness.run("deploy/deploy.sh", "demo", "r1")
    assert result.returncode == 1
    assert result.stdout.strip() == "The main host does not exist yet (deploy/edge.sh)."


def test_deploy_on_the_host(harness):
    """AR-H-11 deploy.sh copies the files into {VIGIL_ROOT}/deploy/ on the host and runs host-deploy.sh."""
    for arguments, profile in ((["demo", "r1"], "vigil"), (["demo", "r1", "--spike"], "spike")):
        harness.seed(images=images(VIGIL_IMAGES + SPIKE_IMAGES, ["r1"]), instances=MAIN)
        harness.table([("docker", r"config --quiet$", [("", 1)])])
        result = harness.run("deploy/deploy.sh", *arguments)
        # The compose fake rejects the file: host-deploy.sh stops at its step 2 and deploy.sh passes its exit code on.
        assert result.returncode == 1
        assert result.stdout.strip() == "The compose file is not valid for this Docker Compose (AR-T-18)."
        deploy = harness.vigil_root / "deploy"
        env = (deploy / ".env").read_text().splitlines()
        assert env[:4] == [f"VIGIL_REGISTRY={REGISTRY}", "VIGIL_RELEASE=r1", "VIGIL_INSTALLATION=demo",
                           f"COMPOSE_PROFILES={profile}"]  # fmt: skip
        command = harness.aws_calls("ssm", "send-command")[0]["args"]
        assert command[command.index("--instance-ids") + 1] == "i-0main"
        assert command[command.index("--document-name") + 1] == "AWS-RunShellScript"
        parameters = next(iter(harness.state()["commands"].values()))["Parameters"]
        assert parameters["executionTimeout"] == ["3600"]
        assert parameters["commands"][-5] == (
            f'bash "$D/host-fetch.sh" vigil-demo-deploy-{ACCOUNT} r1 demo {REGISTRY} {profile}'
        )
        assert not list((harness.vigil_root / "run").iterdir())
