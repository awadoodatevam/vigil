"""Tests of deploy/new-installation.sh (P-05 spec Section 7)."""

import json
import os
import re
import subprocess

from conftest import keygen, search_path

TOKENS = ["webhook-zabbix", "webhook-alertmanager", "installation-token", "faults-token"]
PASSWORDS_20 = [
    "zabbix-admin-password",
    "zabbix-db-password",
    "alertmanager-password",
    "control-password",
    "seed-password-presenter",
    "seed-password-noc-l1",
    "seed-password-dbops-engineer",
    "seed-password-dbops-lead",
    "seed-password-admin",
]
PASSWORDS_24 = ["vigil-db-superuser", "vigil-db-owner", "vigil-db-app"]
KEYS = ["lab-db-01-diag", "lab-db-01-action", "lab-db-01-diag-pub", "lab-db-01-action-pub", "lab-db-01-host-key"]
ALL = TOKENS + PASSWORDS_20 + PASSWORDS_24 + KEYS


def public_key(tmp_path, private_key):
    """The type and base64 fields of the public key of an OpenSSH private key (ssh-keygen -y on a 0600 copy)."""
    path = tmp_path / "key-copy" / "key"
    path.parent.mkdir(exist_ok=True)
    path.write_text(private_key if private_key.endswith("\n") else private_key + "\n")
    os.chmod(path, 0o600)
    environment = dict(os.environ, PATH=search_path())
    output = subprocess.run(
        [keygen(), "-y", "-f", str(path)], env=environment, capture_output=True, text=True, check=True
    ).stdout
    path.unlink()
    return output.split()[:2]


def test_first_and_second_run(harness, tmp_path):
    """AR-S-13 the 21 secrets are made once as SecureString parameters, by file://, and never printed or changed."""
    result = harness.run("deploy/new-installation.sh", "demo")
    assert result.returncode == 0, result.stdout + result.stderr
    assert sorted(result.stdout.splitlines()) == sorted(f"{name}: created" for name in ALL)

    parameters = harness.state()["parameters"]
    assert sorted(parameters) == sorted(f"/vigil/demo/{name}" for name in ALL)
    assert all(parameter["Type"] == "SecureString" for parameter in parameters.values())
    assert all(
        parameter["Tier"] == "Standard" and parameter["KeyId"] == "alias/aws/ssm" for parameter in parameters.values()
    )
    values = {name.rsplit("/", 1)[1]: parameter["Value"] for name, parameter in parameters.items()}
    for name in TOKENS:
        assert re.fullmatch(r"[A-Za-z0-9_-]{43}", values[name]), name
    for name in PASSWORDS_20:
        assert re.fullmatch(r"[A-Za-z0-9]{20}", values[name]), name
    for name in PASSWORDS_24:
        assert re.fullmatch(r"[A-Za-z0-9]{24}", values[name]), name
    assert len(set(values.values())) == len(values)

    for kind in ("diag", "action"):
        secret = json.loads(values[f"lab-db-01-{kind}"])
        assert list(secret) == ["username", "private_key"]
        assert secret["username"] == f"vigil-{kind}"
        assert secret["private_key"].startswith("-----BEGIN OPENSSH PRIVATE KEY-----\n")
        public_line = values[f"lab-db-01-{kind}-pub"]
        assert not public_line.endswith("\n")
        assert public_line.startswith("ssh-ed25519 ")
        assert public_line.endswith(f" vigil-{kind}")
        assert public_key(tmp_path, secret["private_key"]) == public_line.split()[:2]
    host_key = values["lab-db-01-host-key"]
    assert host_key.startswith("-----BEGIN OPENSSH PRIVATE KEY-----\n")
    assert host_key.endswith("-----END OPENSSH PRIVATE KEY-----")

    calls = harness.aws_calls()
    puts = harness.aws_calls("ssm", "put-parameter")
    assert len(puts) == 21
    assert all("--overwrite" not in call["args"] for call in puts)
    assert all(any(argument.startswith("file://") for argument in call["args"]) for call in puts)
    for value in values.values():
        assert all(value not in argument for call in calls for argument in call["args"])
        for line in value.splitlines():
            if len(line) >= 8:
                assert line not in result.stdout + result.stderr
    assert all(call["region"] == "us-east-1" for call in calls)

    second = harness.run("deploy/new-installation.sh", "demo")
    assert second.returncode == 0, second.stdout + second.stderr
    assert sorted(second.stdout.splitlines()) == sorted(f"{name}: kept" for name in ALL)
    assert len(harness.aws_calls("ssm", "put-parameter")) == 21
    assert {name.rsplit("/", 1)[1]: p["Value"] for name, p in harness.state()["parameters"].items()} == values


def test_incomplete_pair(harness):
    """AR-S-13 a key pair with only one of its two parameters stops the script before it creates anything."""
    harness.seed(parameters={"/vigil/demo/lab-db-01-diag": {"Value": "kept", "Type": "SecureString"}})
    result = harness.run("deploy/new-installation.sh", "demo")
    assert result.returncode == 1
    assert result.stdout.strip() == (
        "Secret pair incomplete: /vigil/demo/lab-db-01-diag exists without /vigil/demo/lab-db-01-diag-pub."
    )
    assert harness.aws_calls("ssm", "put-parameter") == []
    assert list(harness.state()["parameters"]) == ["/vigil/demo/lab-db-01-diag"]


def test_usage_and_sign_in(harness):
    """AR-S-13 a wrong installation prints the usage line; a failed sign-in check stops the script."""
    for arguments in ([], ["Demo"], ["unknown"], ["demo", "extra"]):
        result = harness.run("deploy/new-installation.sh", *arguments)
        assert result.returncode == 2
        assert result.stdout.strip() == "Usage: deploy/new-installation.sh {installation}"
    harness.seed(errors={"sts get-caller-identity": "ExpiredToken"})
    result = harness.run("deploy/new-installation.sh", "demo")
    assert result.returncode == 1
    assert result.stdout.strip() == (
        "Sign in first: aws login --profile vigil, then export AWS_PROFILE=vigil (deploy/edge.md)."
    )
