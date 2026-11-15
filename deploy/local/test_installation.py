"""Tests of a running local installation (P-26 spec Section 7; marker installation): run after deploy/local.sh up with
`pytest -m installation deploy/local`, with the same VIGIL_LOCAL_HOME and the project vigil-local.
"""

import json
import os
import stat
import subprocess
import urllib.error
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
    "demo-control": ["installation-token", "control-password"],
    "e2e": [*[f"seed-password-{user}" for user in USERS], "control-password"],
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
    for name in units["demo-control"]:
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


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call(method, url, body=None, headers=None):
    """(status, response headers, JSON body or None) without any proxy of the environment."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers=dict(headers or {}))
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with OPENER.open(request, timeout=30) as response:
            status, answer_headers, raw = response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        status, answer_headers, raw = error.code, error.headers, error.read()
    return status, answer_headers, json.loads(raw) if raw else None


def cookie(headers, name):
    return next(h.split(";", 1)[0] for h in headers.get_all("Set-Cookie") or [] if h.startswith(name + "="))


def test_installation_token_is_vigil_apps():
    """BP-R-26 demo-control's installation-token is vigil-app's."""
    control = (SECRETS / "demo-control" / "installation-token").read_bytes()
    assert control and control == (SECRETS / "vigil-app" / "installation-token").read_bytes()
    assert (SECRETS / "demo-control" / "control-password").read_bytes() == (
        SECRETS / "e2e" / "control-password"
    ).read_bytes()


def test_demo_control_unit():
    """AR-U-06 demo-control answers /health and is control.vigil.test on lab-net."""
    status, _headers, _body = call("GET", "http://127.0.0.1:8090/health")
    assert status == 200
    info = json.loads(subprocess.run(["docker", "inspect", "demo-control"], capture_output=True, check=True).stdout)[0]
    networks = info["NetworkSettings"]["Networks"]
    assert list(networks) == [f"{PROJECT}_lab-net"]
    assert "control.vigil.test" in (networks[f"{PROJECT}_lab-net"]["Aliases"] or [])
    assert info["Config"]["User"] == "10001"
    assert info["HostConfig"]["PortBindings"]["8090/tcp"] == [{"HostIp": "127.0.0.1", "HostPort": "8090"}]


def test_reset_is_the_control_panels():
    """SL-A3-03 deploy/local.sh reset is the control panel's reset (a new tenant; the last result Reset)."""
    vigil = "http://127.0.0.1:8080"
    host = {"Host": "demo.vigil.test:8080"}
    password = (SECRETS / "e2e" / "seed-password-presenter").read_text()
    status, headers, _ = call("POST", vigil + "/api/v1/session", {"username": "presenter", "password": password}, host)
    assert status == 200
    session = cookie(headers, "vigil_session")
    result = subprocess.run(
        [str(ROOT / "deploy" / "local.sh"), "reset"], capture_output=True, text=True, timeout=600, check=False
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1].startswith("Reset completed in ")
    status, _, _ = call("GET", vigil + "/api/v1/session", headers={**host, "Cookie": session})
    assert status == 401
    status, answer = branding()
    assert status == 200 and answer["product_display_name"] == "Techfinity" and answer["customer_name"] == "Jazz"
    control = (SECRETS / "demo-control" / "control-password").read_text()
    status, headers, _ = call(
        "POST", "http://127.0.0.1:8090/api/session", {"username": "operator", "password": control}
    )
    assert status == 204
    status, _, state = call(
        "GET", "http://127.0.0.1:8090/api/state", headers={"Cookie": cookie(headers, "control_session")}
    )
    assert status == 200
    assert state["last_result"]["operation"] == "Reset" and state["last_result"]["outcome"] == "completed"


def test_up_writes_the_lab_profile():
    """BP-R-28 up writes the lab profile it used to state/lab-profile (P-27), next to state/values-file."""
    values_file = Path((BASE / "state" / "values-file").read_text().strip())
    profile = (BASE / "state" / "lab-profile").read_text()
    used = next(
        line.split(":", 1)[1].strip().strip('"')
        for line in values_file.read_text().splitlines()
        if line.startswith("lab_profile:")
    )
    assert profile == used + "\n"
    environment = subprocess.run(
        ["docker", "inspect", "-f", "{{range .Config.Env}}{{println .}}{{end}}", "lab-db-01"],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.splitlines()
    assert f"LAB_PROFILE={used}" in environment
