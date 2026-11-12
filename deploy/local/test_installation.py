"""Tests of a running local installation (P-26 spec Section 7; marker installation): run after deploy/local.sh up with
`pytest -m installation deploy/local`, with the same VIGIL_LOCAL_HOME and the project vigil-local.
"""

import json
import os
import stat
import subprocess
import urllib.request
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent.parent
PROJECT = "vigil-local"
BASE = Path(os.environ.get("VIGIL_LOCAL_HOME", Path.home() / ".vigil-local")) / PROJECT
SECRETS = BASE / "secrets"
USERS = ["presenter", "noc-l1", "dbops-engineer", "dbops-lead", "admin"]
TEAMS = ["noc-l1", "db-ops", "vas-service-owners", "it-apps", "noc-shift-lead", "noc-management", "customer-care"]
# The secrets of Section 6.3 step 3 written by the full up, per unit folder (complete list).
VIGIL_SECRETS = {
    "vigil-app": [
        "vigil-db-app",
        *[f"seed-password-{user}" for user in USERS],
        "installation-token",
        "jira",
        "smtp",
        "claude",
        "openai",
        *[f"teams-team-{team}" for team in TEAMS],
        "teams-user-dbops-engineer",
        "teams-user-dbops-lead",
    ],
    "vigil-migrate": ["vigil-db-owner"],
    "vigil-db": ["vigil-db-superuser", "vigil-db-owner", "vigil-db-app"],
    "e2e": [f"seed-password-{user}" for user in USERS],
}
LAB_UNITS = [
    "lab-db-01",
    "vigil-app",
    "lab-zabbix-provision",
    "lab-alertmanager",
    "demo-control",
    "lab-prometheus",
    "lab-zabbix-db",
    "lab-zabbix-server",
    "lab-zabbix-web",
    "demo-faults",
]
NOT_YET = {"demo-control": {"installation-token", "control-password"}}

pytestmark = pytest.mark.installation


def units_yaml():
    """The unit secrets of deploy/units.yaml: {unit: [names]} (its simple layout, read without a YAML library)."""
    units, unit, in_secrets = {}, None, False
    for line in (ROOT / "deploy" / "units.yaml").read_text().splitlines():
        if line.startswith("  ") and not line.startswith("    ") and line.strip().endswith(":"):
            unit, in_secrets = line.strip()[:-1], False
            units[unit] = []
        elif line.strip() == "secrets:":
            in_secrets = True
        elif in_secrets and line.strip().startswith("- "):
            units[unit].append(line.strip()[2:])
    return units


def test_secret_folders_and_files():
    """BP-R-26 every unit folder and secret file of a local installation: folders 0555, files 0444, the user's."""
    for unit in LAB_UNITS + list(VIGIL_SECRETS):
        folder = SECRETS / unit
        assert folder.is_dir(), unit
        assert stat.S_IMODE(folder.stat().st_mode) == 0o555 and folder.stat().st_uid == os.getuid(), unit
        for path in folder.iterdir():
            assert stat.S_IMODE(path.stat().st_mode) == 0o444 and path.stat().st_uid == os.getuid(), path
    for unit, names in VIGIL_SECRETS.items():
        for name in names:
            path = SECRETS / unit / name
            assert path.is_file(), path
            assert path.read_text() and not path.read_text().endswith("\n"), path
    assert sorted(p.name for p in (SECRETS / "e2e").iterdir()) == sorted(VIGIL_SECRETS["e2e"])


def test_one_value_per_name():
    """AR-S-13 each secret name has one value in every folder that holds it, and every unit has its secrets."""
    values = {}
    for folder in SECRETS.iterdir():
        for path in folder.iterdir():
            values.setdefault(path.name, set()).add(path.read_bytes())
    assert {name: len(found) for name, found in values.items() if len(found) != 1} == {}
    units = units_yaml()
    for unit in ["vigil-app", "vigil-migrate", "vigil-db", *[u for u in units if u.startswith("lab-")], "demo-faults"]:
        for name in units[unit]:
            assert (SECRETS / unit / name).is_file(), (unit, name)
    for name in set(units["demo-control"]) - NOT_YET["demo-control"]:
        assert (SECRETS / "demo-control" / name).is_file(), name
    for name in VIGIL_SECRETS["e2e"]:
        assert (SECRETS / "e2e" / name).is_file(), name
    assert (SECRETS / "vigil-app" / "teams-team-noc-l1").read_text() == "http://teams.fakes:8000/teams-team-noc-l1"


def branding():
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    request = urllib.request.Request(
        "http://127.0.0.1:8080/api/v1/public/branding", headers={"Host": "demo.vigil.test:8080"}
    )
    with opener.open(request, timeout=10) as response:
        return response.status, json.loads(response.read())


def test_reset_makes_a_new_tenant():
    """AR-B-04 reset makes a new tenant and moves the alias; the branding still answers."""
    before = json.loads((BASE / "state" / "tenant.json").read_text())["tenant_id"]
    result = subprocess.run(
        [str(ROOT / "deploy" / "local.sh"), "reset"], capture_output=True, text=True, timeout=600, check=False
    )
    assert result.returncode == 0, result.stdout + result.stderr
    after = json.loads((BASE / "state" / "tenant.json").read_text())
    assert after["tenant_id"] != before and after["host_name"] == "demo.vigil.test"
    assert json.loads((BASE / "state" / "seed-import.json").read_text())["tenant_id"] == after["tenant_id"]
    status, answer = branding()
    assert status == 200 and answer["product_display_name"] == "Techfinity" and answer["customer_name"] == "Jazz"
