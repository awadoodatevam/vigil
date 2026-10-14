"""Tests of tools/loc.sh (BP-R-05)."""

import subprocess
from pathlib import Path

LOC = Path(__file__).with_name("loc.sh")


def git(repo, *args):
    subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.com", *args], cwd=repo, check=True)


def commit_files(repo, files, message):
    for relative, lines in files.items():
        path = repo / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("".join(f"line {i}\n" for i in range(lines)), encoding="utf-8")
    git(repo, "add", "-A")
    git(repo, "commit", "-q", "-m", message)


def loc(repo, base, head):
    result = subprocess.run(["bash", str(LOC), base, head], cwd=repo, capture_output=True, text=True, check=True)
    return int(result.stdout.strip())


def test_counts_production_lines_only(tmp_path):
    """BP-R-05 production lines are counted; tests and lock files are not."""
    git(tmp_path, "init", "-q", "-b", "main")
    commit_files(tmp_path, {"README.md": 1}, "base")
    base = subprocess.run(
        ["git", "rev-parse", "HEAD"], cwd=tmp_path, capture_output=True, text=True, check=True
    ).stdout.strip()
    commit_files(
        tmp_path,
        {
            "app/src/main/java/vigil/A.java": 10,
            "web/src/main.ts": 7,
            "web/src/App.tsx": 3,
            "demo/lab/mock.py": 5,
            "deploy/run.sh": 2,
            "app/src/test/java/vigil/ATest.java": 100,
            "tools/test_x.py": 100,
            "web/src/App.test.ts": 100,
            "web/src/App.test.tsx": 100,
            "demo/lab/test_mock.py": 100,
            "web/package-lock.json": 100,
            "dev/fakes/recordings/a.py": 100,
            "tests/e2e/flow.spec.ts": 100,
            "tools/trace.py": 100,
            "app/src/main/resources/application.properties": 100,
        },
        "work",
    )
    assert loc(tmp_path, base, "HEAD") == 27
