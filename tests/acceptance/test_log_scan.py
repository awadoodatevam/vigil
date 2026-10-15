"""Tests of tests/acceptance/log_scan.py (AR-Q-07)."""

import json
import secrets
import subprocess
import sys
from pathlib import Path

SCRIPT = Path(__file__).with_name("log_scan.py")


def run_scan(log: Path, secrets_dir: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--log", str(log), "--secrets-dir", str(secrets_dir)],
        capture_output=True,
        text=True,
        check=False,
    )


def write(path: Path, content: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def test_secret_in_log_is_reported_by_path(tmp_path):
    """AR-Q-07 a log containing a secret exits 1 and prints the file's relative path, not the value."""
    value = secrets.token_urlsafe(18)
    write(tmp_path / "secrets" / "vigil-app" / "vigil-db-app", value)
    write(tmp_path / "secrets" / "vigil-app" / "other", secrets.token_urlsafe(18))
    log = write(tmp_path / "app.log", f'{{"message":"connected with {value}"}}\n')

    result = run_scan(log, tmp_path / "secrets")

    assert result.returncode == 1
    assert result.stdout.splitlines() == ["vigil-app/vigil-db-app"]
    assert value not in result.stdout
    assert value not in result.stderr


def test_secret_inside_json_object_file_is_found(tmp_path):
    """AR-Q-07 a string value inside a JSON-object secret file is a secret value."""
    password = secrets.token_urlsafe(18)
    write(tmp_path / "secrets" / "jira", json.dumps({"user": "ops", "token": {"value": password}}))
    log = write(tmp_path / "app.log", f"token {password} sent\n")

    result = run_scan(log, tmp_path / "secrets")

    assert result.returncode == 1
    assert result.stdout.splitlines() == ["jira"]
    assert password not in result.stdout


def test_clean_log_exits_zero(tmp_path):
    """AR-Q-07 a log without any secret value exits 0 and prints nothing."""
    write(tmp_path / "secrets" / "vigil-app" / "vigil-db-app", secrets.token_urlsafe(18))
    log = write(tmp_path / "app.log", '{"message":"restart step 7 done"}\n')

    result = run_scan(log, tmp_path / "secrets")

    assert result.returncode == 0
    assert result.stdout == ""


def test_values_under_eight_characters_are_ignored(tmp_path):
    """AR-Q-07 values shorter than 8 characters give no match."""
    write(tmp_path / "secrets" / "short", "abc1234")
    write(tmp_path / "secrets" / "object", json.dumps({"user": "ops", "role": "admin"}))
    log = write(tmp_path / "app.log", "user ops admin abc1234\n")

    result = run_scan(log, tmp_path / "secrets")

    assert result.returncode == 0
    assert result.stdout == ""


def test_sub_folders_are_read(tmp_path):
    """AR-Q-07 secret files in nested sub-folders are read."""
    value = secrets.token_urlsafe(18)
    write(tmp_path / "secrets" / "a" / "b" / "c" / "deep", value)
    log = write(tmp_path / "app.log", f"x{value}x\n")

    result = run_scan(log, tmp_path / "secrets")

    assert result.returncode == 1
    assert result.stdout.splitlines() == ["a/b/c/deep"]
