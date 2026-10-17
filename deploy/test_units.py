"""Tests of deploy/units.yaml and the region of the scripts (P-05 spec Section 7)."""

import re
import subprocess

import yaml
from conftest import REPOSITORY, search_path

DEPLOY = REPOSITORY / "deploy"
USERS = ["presenter", "noc-l1", "dbops-engineer", "dbops-lead", "admin"]
TEAMS = ["noc-l1", "db-ops", "vas-service-owners", "it-apps", "noc-shift-lead", "noc-management", "customer-care"]

# AR-S-13: each secret and the units that read it (complete list).
SECRET_READERS = {
    "webhook-zabbix": ["vigil-app", "lab-zabbix-provision"],
    "webhook-alertmanager": ["vigil-app", "lab-alertmanager"],
    "zabbix-api": ["vigil-app", "demo-control"],
    "zabbix-admin-password": ["lab-zabbix-provision"],
    "zabbix-db-password": ["lab-zabbix-db", "lab-zabbix-server", "lab-zabbix-web"],
    "alertmanager-password": ["vigil-app", "demo-control", "lab-prometheus"],
    "alertmanager-web-config": ["lab-alertmanager"],
    "lab-db-01-diag": ["vigil-app"],
    "lab-db-01-action": ["vigil-app"],
    "lab-db-01-diag-pub": ["lab-db-01"],
    "lab-db-01-action-pub": ["lab-db-01"],
    "lab-db-01-host-key": ["lab-db-01"],
    "jira": ["vigil-app"],
    "smtp": ["vigil-app"],
    "claude": ["vigil-app"],
    "openai": ["vigil-app"],
    **{f"teams-team-{team}": ["vigil-app"] for team in TEAMS},
    "teams-user-dbops-engineer": ["vigil-app"],
    "teams-user-dbops-lead": ["vigil-app"],
    **{f"seed-password-{user}": ["vigil-app"] for user in USERS},
    "installation-token": ["vigil-app", "demo-control"],
    "control-password": ["demo-control"],
    "faults-token": ["demo-control", "demo-faults"],
    "vigil-db-superuser": ["vigil-db"],
    "vigil-db-owner": ["vigil-db", "vigil-migrate"],
    "vigil-db-app": ["vigil-db", "vigil-app"],
}

EXPECTED_UNITS = {
    "vigil-app": (
        10001,
        ["webhook-zabbix", "webhook-alertmanager", "zabbix-api", "alertmanager-password", "lab-db-01-diag"]
        + ["lab-db-01-action", "jira", "smtp", "claude", "openai"]
        + [f"teams-team-{team}" for team in TEAMS]
        + ["teams-user-dbops-engineer", "teams-user-dbops-lead"]
        + [f"seed-password-{user}" for user in USERS]
        + ["installation-token", "vigil-db-app"],
    ),
    "vigil-migrate": (10001, ["vigil-db-owner"]),
    "demo-control": (
        10001,
        ["zabbix-api", "alertmanager-password", "installation-token", "control-password", "faults-token"],
    ),
    "lab-zabbix-provision": (10001, ["webhook-zabbix", "zabbix-admin-password"]),
    "demo-faults": (0, ["faults-token"]),
    "vigil-db": (999, ["vigil-db-superuser", "vigil-db-owner", "vigil-db-app"]),
    "lab-zabbix-db": (999, ["zabbix-db-password"]),
    "lab-zabbix-server": (1997, ["zabbix-db-password"]),
    "lab-zabbix-web": (1997, ["zabbix-db-password"]),
    "lab-prometheus": (65534, ["alertmanager-password"]),
    "lab-alertmanager": (65534, ["webhook-alertmanager", "alertmanager-web-config"]),
    "lab-db-01": (0, ["lab-db-01-diag-pub", "lab-db-01-action-pub", "lab-db-01-host-key"]),
}

MAC_SCRIPTS = [
    "deploy/edge.sh",
    "deploy/new-installation.sh",
    "deploy/build.sh",
    "deploy/deploy.sh",
    "deploy/status.sh",
    "deploy/gpu.sh",
    "deploy/models.sh",
    "tests/acceptance/p05_aws_check.sh",
]
HOST_SCRIPTS = [
    "deploy/secrets.sh",
    "deploy/main/host-fetch.sh",
    "deploy/main/host-deploy.sh",
    "deploy/main/host-status.sh",
    "deploy/gpu/host-gpu.sh",
    "deploy/gpu/host-gpu-status.sh",
    "deploy/hosts/marker.sh",
    "deploy/hosts/model-update.sh",
    "tests/acceptance/p05_host_check.sh",
]


def test_units_and_secrets():
    """AR-S-04 units.yaml lists exactly the units, users and secrets of AR-S-04 and AR-S-13."""
    document = yaml.safe_load((DEPLOY / "units.yaml").read_text())
    assert list(document) == ["units"]
    units = document["units"]
    assert list(units) == list(EXPECTED_UNITS)
    for name, (user, secrets) in EXPECTED_UNITS.items():
        assert set(units[name]) == {"user", "secrets"}
        assert units[name]["user"] == user, name
        assert units[name]["secrets"] == secrets, name
    readers = {}
    for name, unit in units.items():
        for secret in unit["secrets"]:
            readers.setdefault(secret, []).append(name)
    assert {secret: sorted(names) for secret, names in readers.items()} == {
        secret: sorted(names) for secret, names in SECRET_READERS.items()
    }


def test_region(tmp_path):
    """AR-T-23 us-east-1 only: the library and the host scripts export it, and the fake refuses other regions."""
    library = (DEPLOY / "lib" / "aws.sh").read_text()
    assert re.search(r"^export AWS_REGION=us-east-1$", library, re.MULTILINE)
    for script in MAC_SCRIPTS:
        text = (REPOSITORY / script).read_text()
        assert re.search(r"^\. deploy/lib/aws\.sh$", text, re.MULTILINE), script
    for script in HOST_SCRIPTS:
        text = (REPOSITORY / script).read_text()
        assert re.search(r"^export AWS_REGION=us-east-1$", text, re.MULTILINE), script
    for script in ("deploy/hosts/main-user-data.sh", "deploy/hosts/gpu-user-data.sh"):
        assert not re.search(r"\baws ", (REPOSITORY / script).read_text().replace("aws-cli", "")), script

    state = tmp_path / "aws-state.json"
    state.write_text("{}")
    environment = {"PATH": search_path(), "FAKE_AWS_STATE": str(state), "AWS_REGION": "eu-west-1"}
    refused = subprocess.run(
        ["aws", "sts", "get-caller-identity", "--output", "json"], env=environment, capture_output=True, text=True
    )
    assert refused.returncode == 255
    assert "fake aws: region eu-west-1 is not us-east-1" in refused.stderr
    environment["AWS_REGION"] = "us-east-1"
    accepted = subprocess.run(
        ["aws", "sts", "get-caller-identity", "--output", "json"], env=environment, capture_output=True, text=True
    )
    assert accepted.returncode == 0
