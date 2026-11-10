"""Tests of deploy/secrets.sh (P-05 spec Section 7; the jira expiry check: P-06 spec Section 7)."""

import datetime
import json
import os
import secrets
import shutil
import stat
import subprocess

import pytest
import yaml
from conftest import REPOSITORY, keygen, search_path

ROOT = os.geteuid() == 0
SERVICES = ["sse-probe", "vigil-embed", "lab-zabbix-provision", "demo-control"]


def host_key(tmp_path):
    """A new ed25519 host key as Parameter Store holds it (no trailing newline) and its SHA-256 fingerprint."""
    folder = tmp_path / "host-key"
    folder.mkdir()
    environment = dict(os.environ, PATH=search_path())
    subprocess.run(
        [keygen(), "-q", "-t", "ed25519", "-N", "", "-C", "lab-db-01", "-f", str(folder / "key")],
        env=environment,
        check=True,
    )
    fingerprint = subprocess.run(
        [keygen(), "-l", "-E", "sha256", "-f", str(folder / "key")], env=environment, capture_output=True, text=True
    ).stdout.split()[1]
    return (folder / "key").read_text().rstrip("\n"), fingerprint


def prepare(harness, tmp_path, services):
    """The deployed files of {VIGIL_ROOT}/deploy/, the parameters and a compose fake listing the services."""
    deploy = harness.vigil_root / "deploy"
    deploy.mkdir()
    for name in ("secrets.sh", "web_config.py", "units.yaml"):
        shutil.copy2(REPOSITORY / "deploy" / name, deploy / name)
    (deploy / "compose.yaml").write_text("name: vigil\n")
    (deploy / ".env").write_text("VIGIL_RELEASE=r1\n")
    key, fingerprint = host_key(tmp_path)
    values = {
        name: secrets.token_urlsafe(16)
        for name in (
            "webhook-zabbix",
            "zabbix-admin-password",
            "alertmanager-password",
            "installation-token",
            "control-password",
            "faults-token",
            "webhook-alertmanager",
        )
    }
    values["lab-db-01-host-key"] = key
    harness.seed(
        parameters={f"/vigil/demo/{name}": {"Value": value, "Type": "SecureString"} for name, value in values.items()}
    )
    harness.table(
        [
            (
                "docker",
                r"^compose -p vigil -f \S+/compose\.yaml --env-file \S+/\.env config --services$",
                [("\n".join(services) + "\n", 0)],
            )
        ]
    )
    return deploy, values, fingerprint


def mode(path):
    return stat.S_IMODE(path.stat().st_mode)


def test_secret_files(harness, tmp_path):
    """AR-S-04 folders and files of the served units, zabbix-api only with --generated, derived.yaml, missing secret."""
    deploy, values, fingerprint = prepare(harness, tmp_path, SERVICES)
    secrets_root = harness.vigil_root / "secrets"
    control = secrets_root / "demo-control"
    control.mkdir(parents=True)
    (control / "stale").write_text("old")
    (control / "zabbix-api").write_text("previous-token")

    result = harness.run(str(deploy / "secrets.sh"), "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    lines = [line for line in result.stdout.splitlines() if not line.startswith("Not root")]
    assert lines == ["demo-control: 4 secrets", "lab-zabbix-provision: 2 secrets"]
    assert ("Not root: secret files keep the current user as owner." in result.stdout) is not ROOT
    assert sorted(path.name for path in secrets_root.iterdir()) == ["demo-control", "lab-zabbix-provision"]
    expected = {
        "lab-zabbix-provision": ["webhook-zabbix", "zabbix-admin-password"],
        "demo-control": ["alertmanager-password", "installation-token", "control-password", "faults-token"],
    }
    for unit, names in expected.items():
        folder = secrets_root / unit
        assert mode(folder) == 0o500
        if ROOT:
            assert folder.stat().st_uid == 10001
        for name in names:
            assert (folder / name).read_text() == values[name]
            assert mode(folder / name) == 0o400
            if ROOT:
                assert (folder / name).stat().st_uid == 10001
    assert sorted(path.name for path in control.iterdir()) == sorted(expected["demo-control"] + ["zabbix-api"])
    assert (control / "zabbix-api").read_text() == "previous-token"
    generated = harness.vigil_root / "generated"
    assert mode(generated) == 0o700
    if ROOT:
        assert generated.stat().st_uid == 10001
    for value in values.values():
        assert value.splitlines()[0] not in result.stdout + result.stderr

    result = harness.run(str(deploy / "secrets.sh"), "demo", "--generated")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "No generated zabbix-api yet; left unchanged." in result.stdout
    assert (control / "zabbix-api").read_text() == "previous-token"
    assert result.stdout.splitlines()[-1] == "derived.yaml written"
    derived = harness.vigil_root / "values" / "derived.yaml"
    assert derived.read_text() == f'lab_db_host_key_sha256: "{fingerprint}"\n'
    assert mode(derived) == 0o644

    token = secrets.token_urlsafe(24)
    (generated / "zabbix-api").write_text(token)
    result = harness.run(str(deploy / "secrets.sh"), "demo", "--generated")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "demo-control: zabbix-api copied" in result.stdout.splitlines()
    assert (control / "zabbix-api").read_text() == token
    assert mode(control / "zabbix-api") == 0o400
    assert mode(control) == 0o500
    assert token not in result.stdout

    state = harness.state()
    del state["parameters"]["/vigil/demo/control-password"]
    harness.seed(**state)
    result = harness.run(str(deploy / "secrets.sh"), "demo")
    assert result.returncode == 1
    assert "Missing secret /vigil/demo/control-password (AR-S-13)." in result.stdout


def test_alertmanager_web_config(harness, tmp_path):
    """AR-S-04 the derived alertmanager-web-config holds a bcrypt hash of alertmanager-password."""
    bcrypt = pytest.importorskip("bcrypt", reason="the gate's Python tools have no bcrypt; the host has python3-bcrypt")
    deploy, values, _ = prepare(harness, tmp_path, SERVICES + ["lab-alertmanager"])
    result = harness.run(str(deploy / "secrets.sh"), "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    assert "lab-alertmanager: 2 secrets" in result.stdout.splitlines()
    folder = harness.vigil_root / "secrets" / "lab-alertmanager"
    content = (folder / "alertmanager-web-config").read_text()
    assert content.startswith("basic_auth_users:\n  vigil: ") and content.endswith("\n")
    hashed = content.split("vigil: ", 1)[1].strip()
    assert bcrypt.checkpw(values["alertmanager-password"].encode(), hashed.encode())
    assert (folder / "webhook-alertmanager").read_text() == values["webhook-alertmanager"]


def prepare_jira(harness, tmp_path, jira_value):
    """vigil-app served, with every secret it reads seeded; the secret jira holds jira_value as it is."""
    deploy, _, _ = prepare(harness, tmp_path, ["vigil-app"])
    with open(deploy / "units.yaml") as file:
        names = yaml.safe_load(file)["units"]["vigil-app"]["secrets"]
    values = {name: secrets.token_urlsafe(16) for name in names if name != "zabbix-api"}
    values["jira"] = jira_value
    harness.update_state(
        parameters={f"/vigil/demo/{name}": {"Value": value, "Type": "SecureString"} for name, value in values.items()}
    )
    return values


def jira_secret(expires_on, **extra):
    value = {"email": "operator@example.invalid", "api_token": secrets.token_urlsafe(24), "expires_on": expires_on}
    value.update(extra)
    return json.dumps(value)


def today():
    return datetime.datetime.now(datetime.timezone.utc).date()


def test_jira_expiry_valid(harness, tmp_path):
    """AR-H-07 a valid expires_on prints the expiry line and the secret jira is written unchanged."""
    expires = today() + datetime.timedelta(days=200)
    value = jira_secret(expires.isoformat())
    prepare_jira(harness, tmp_path, value)
    result = harness.run(str(harness.vigil_root / "deploy" / "secrets.sh"), "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    assert f"jira token expires on {expires.isoformat()} (200 days left)" in result.stdout.splitlines()
    assert "Renew the Jira API token" not in result.stdout
    assert result.stdout.count("jira token expires on") == 1
    assert (harness.vigil_root / "secrets" / "vigil-app" / "jira").read_text() == value
    assert json.loads(value)["api_token"] not in result.stdout + result.stderr


def test_jira_secret_unchanged(harness, tmp_path):
    """AR-S-13 the secret jira with expires_on is written to vigil-app's folder unchanged, all three keys."""
    expires = today() + datetime.timedelta(days=29)
    value = jira_secret(expires.isoformat())
    prepare_jira(harness, tmp_path, value)
    result = harness.run(str(harness.vigil_root / "deploy" / "secrets.sh"), "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    lines = result.stdout.splitlines()
    index = lines.index(f"jira token expires on {expires.isoformat()} (29 days left)")
    assert lines[index + 1] == f"Renew the Jira API token before {expires.isoformat()} (AR-H-07)."
    written = harness.vigil_root / "secrets" / "vigil-app" / "jira"
    assert written.read_text() == value
    assert sorted(json.loads(written.read_text())) == ["api_token", "email", "expires_on"]
    assert mode(written) == 0o400

    expires = today()
    value = jira_secret(expires.isoformat())
    state = harness.state()
    state["parameters"]["/vigil/demo/jira"]["Value"] = value
    harness.seed(**state)
    result = harness.run(str(harness.vigil_root / "deploy" / "secrets.sh"), "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    assert f"jira token expires on {expires.isoformat()} (0 days left)" in result.stdout.splitlines()
    assert written.read_text() == value


@pytest.mark.parametrize(
    "value, message",
    [
        (lambda: jira_secret((today() - datetime.timedelta(days=1)).isoformat()), "expired"),
        (lambda: json.dumps({"email": "operator@example.invalid", "api_token": "x" * 24}), "invalid"),
        (lambda: jira_secret(20271105), "invalid"),
        (lambda: jira_secret("2027-02-30"), "invalid"),
        (lambda: jira_secret("5 Nov 2027"), "invalid"),
        (lambda: jira_secret("20271105"), "invalid"),
        (lambda: json.dumps(["2027-11-05"]), "invalid"),
        (lambda: "not json at all", "invalid"),
    ],
)
def test_jira_expiry_refused(harness, tmp_path, value, message):
    """AR-H-07 a past, missing or invalid expires_on, or a value that is no JSON object, exits 1 before writing jira."""
    secret = value()
    prepare_jira(harness, tmp_path, secret)
    result = harness.run(str(harness.vigil_root / "deploy" / "secrets.sh"), "demo")
    assert result.returncode == 1
    last = result.stdout.splitlines()[-1]
    if message == "expired":
        date = (today() - datetime.timedelta(days=1)).isoformat()
        assert last == f"The Jira API token expired on {date}; renew it (AR-H-07)."
    else:
        assert last == "The jira secret has no valid expires_on (AR-S-13)."
    assert "Traceback" not in result.stdout + result.stderr
    assert not (harness.vigil_root / "secrets" / "vigil-app" / "jira").exists()
    assert secret not in result.stdout + result.stderr
