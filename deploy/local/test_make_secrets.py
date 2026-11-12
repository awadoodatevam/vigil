"""Tests of make_secrets.py (P-26 spec Sections 6.3 and 6.8; AR-S-04, AR-S-13)."""

import json
import re
import stat

import make_secrets
import pytest


def made(tmp_path, kind, name="secret"):
    path = tmp_path / name
    assert make_secrets.main([kind, str(path)]) == 0
    assert stat.S_IMODE(path.stat().st_mode) == 0o600
    text = path.read_text()
    assert not text.endswith("\n")
    return text


@pytest.mark.parametrize("kind, length", [("password24", 24), ("password20", 20)])
def test_passwords(tmp_path, kind, length):
    """BP-R-26 passwords are random characters of a-z A-Z 0-9 without a trailing newline."""
    first, second = made(tmp_path, kind, "a"), made(tmp_path, kind, "b")
    assert re.fullmatch(f"[a-zA-Z0-9]{{{length}}}", first) and first != second


@pytest.mark.parametrize("kind", ["token", "api-key"])
def test_tokens(tmp_path, kind):
    """BP-R-26 tokens and API keys are 32 random bytes, base64url."""
    assert re.fullmatch(r"[A-Za-z0-9_-]{43}", made(tmp_path, kind))


def test_jira(tmp_path):
    """BP-R-26 the jira secret is the JSON object of AR-S-13 with a token."""
    value = json.loads(made(tmp_path, "jira"))
    assert value["email"] == "vigil@vigil.test" and value["expires_on"] == "2099-12-31"
    assert re.fullmatch(r"[A-Za-z0-9_-]{43}", value["api_token"])
    assert list(value) == ["email", "api_token", "expires_on"]


def test_smtp(tmp_path):
    """BP-R-26 the smtp secret is {username, password} with a 20-character password."""
    value = json.loads(made(tmp_path, "smtp"))
    assert value["username"] == "vigil" and re.fullmatch(r"[a-zA-Z0-9]{20}", value["password"])


def test_teams(tmp_path):
    """BP-R-26 a Teams secret is the teams fake's address with the secret's own name as the channel key."""
    assert made(tmp_path, "teams", "teams-team-noc-l1") == "http://teams.fakes:8000/teams-team-noc-l1"


@pytest.mark.parametrize("arguments", [[], ["token"], ["other", "file"], ["token", "a", "b"]])
def test_usage(tmp_path, arguments, capsys):
    """BP-R-26 wrong arguments print the usage line, exit 2 and write nothing."""
    assert make_secrets.main(arguments) == 2
    assert capsys.readouterr().out.startswith("Usage: make_secrets.py")
    assert list(tmp_path.iterdir()) == []
