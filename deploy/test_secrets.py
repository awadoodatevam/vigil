"""Tests of deploy/secrets.sh (P-05 spec Section 7)."""

import os
import secrets
import shutil
import stat
import subprocess

import pytest
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
