"""Tests of tests/acceptance/p06_log.sh (P-06 spec Sections 6.10 and 7)."""

import subprocess
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "p06_log.sh"


def run(log, *arguments):
    return subprocess.run(
        ["bash", str(SCRIPT), *arguments],
        env={"PATH": "/usr/bin:/bin", "VIGIL_P06_LOG": str(log)},
        capture_output=True,
        text=True,
        check=False,
    )


def test_output_and_exit_code_appended(tmp_path):
    """AR-H-02 p06_log.sh appends the command's output and "exit {code}" to the log and returns the command's code."""
    log = tmp_path / "P-06-gpu.txt"
    log.write_text("earlier run\nexit 3\n")
    first = run(log, "bash", "-c", "echo out; echo err >&2; exit 3")
    assert first.returncode == 3
    assert first.stdout.splitlines()[-1] == "exit 3"
    assert "out" in first.stdout and "err" in first.stdout
    second = run(log, "bash", "-c", "echo instance vigil-demo-gpu: created")
    assert second.returncode == 0
    assert second.stdout == "instance vigil-demo-gpu: created\nexit 0\n"
    lines = log.read_text().splitlines()
    assert lines[:2] == ["earlier run", "exit 3"]
    assert sorted(lines[2:4]) == ["err", "out"]
    assert lines[4:] == ["exit 3", "instance vigil-demo-gpu: created", "exit 0"]


def test_usage(tmp_path):
    """AR-H-02 p06_log.sh without a command prints its usage line and exits 2."""
    result = run(tmp_path / "log.txt")
    assert result.returncode == 2
    assert result.stdout == "Usage: tests/acceptance/p06_log.sh {command} [{argument} ...]\n"
    assert not (tmp_path / "log.txt").exists()
