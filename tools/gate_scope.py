#!/usr/bin/env python3
"""Chooses what the gate runs (P-42, CTX-D-067).

lab: prints lab or no-lab, the lab path decision of tools/check-all.sh step (3) (BP-R-12, CTX-D-067 (3)).
ci:  the choice of the light or the full gate of the ci.yml job scope (BP-W-01, CTX-D-067 (1), (4)); appends
     mode=light or mode=full to the file named by GITHUB_OUTPUT and prints gate: {mode} ({reason}).
"""

import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
USAGE = "Usage: tools/gate_scope.py ci|lab"

# An entry ending with / matches every path under it; any other entry matches that path exactly.
LAB_PATHS = (
    "demo/",
    "dev/recorder/",
    "deploy/local/",
    "deploy/local.sh",
    "deploy/images.sh",
    "deploy/values/local.yaml",
    "spikes/storm-timing/",
    "docs/31-demo-data/",
    "tools/check-all.sh",
    "tools/gate_scope.py",
    "tools/start-docker.sh",
    "tools/requirements-dev.txt",
    "pytest.ini",
    ".github/workflows/ci.yml",
)
DATA_PATHS = ("docs/", "spikes/results/", "tests/acceptance/results/", "dev/fakes/recordings/")
DATA_EXCLUDED = ("docs/31-demo-data/",)
NOT_DATA_SUFFIXES = (".py", ".sh")
NOT_DATA_NAMES = ("Dockerfile", "compose.yaml")
MAX_EARLIER_COMMITS = 20


class CommandFailed(Exception):
    """A git or gh command that failed or gave an answer that cannot be read."""

    def __init__(self, command, stderr=""):
        super().__init__(command)
        self.command = command
        self.stderr = stderr


def matches(path, entries):
    return any(path.startswith(entry) if entry.endswith("/") else path == entry for entry in entries)


def is_data_path(path):
    return (
        matches(path, DATA_PATHS)
        and not matches(path, DATA_EXCLUDED)
        and not path.endswith(NOT_DATA_SUFFIXES)
        and path.rsplit("/", 1)[-1] not in NOT_DATA_NAMES
    )


def run(arguments):
    """Runs a command from the repository root; returns its standard output or raises CommandFailed."""
    command = " ".join(arguments)
    try:
        result = subprocess.run(arguments, cwd=ROOT, capture_output=True, text=True, check=False)
    except OSError as error:
        raise CommandFailed(command, str(error)) from error
    if result.returncode != 0:
        raise CommandFailed(command, result.stderr)
    return result.stdout


def lines(output):
    return [line for line in output.splitlines() if line]


def lab():
    """BP-R-12 step (3): lab when the branch changes a path of LAB_PATHS or origin/main does not exist."""
    has_main = subprocess.run(
        ["git", "rev-parse", "--verify", "--quiet", "origin/main"], cwd=ROOT, capture_output=True, check=False
    )
    if has_main.returncode != 0:
        print("lab")
        return 0
    paths = []
    for arguments in (
        ["git", "diff", "--name-only", "--no-renames", "origin/main"],
        ["git", "ls-files", "--others", "--exclude-standard"],
    ):
        try:
            paths += lines(run(arguments))
        except CommandFailed as failure:
            sys.stderr.write(failure.stderr)
            print("lab")
            return 0
    print("lab" if any(matches(path, LAB_PATHS) for path in paths) else "no-lab")
    return 0


def has_passed_gate(repository, sha):
    """True when the latest check run named gate of the commit, made by GitHub Actions, succeeded."""
    arguments = ["gh", "api", f"repos/{repository}/commits/{sha}/check-runs?check_name=gate&filter=latest&per_page=100"]
    command = " ".join(arguments)
    output = run(arguments)
    try:
        answer = json.loads(output)
    except ValueError as error:
        raise CommandFailed(command) from error
    if not isinstance(answer, dict) or not isinstance(answer.get("check_runs"), list):
        raise CommandFailed(command)
    for entry in answer["check_runs"]:
        if not isinstance(entry, dict):
            continue
        app = entry.get("app")
        if isinstance(app, dict) and app.get("slug") == "github-actions" and entry.get("conclusion") == "success":
            return True
    return False


def decide(event_name, environment):
    """The first rule of P-42 Section 6.1 that applies: (mode, reason)."""
    if event_name != "pull_request":
        return "full", "not a pull request"
    base, head = environment["BASE_SHA"], environment["HEAD_SHA"]
    merge, repository = environment["MERGE_SHA"], environment["GITHUB_REPOSITORY"]
    try:
        changed = lines(run(["git", "diff", "--name-only", "--no-renames", f"{base}...{head}"]))
        if changed and all(is_data_path(path) for path in changed):
            return "light", "data only"
        earlier = lines(run(["git", "rev-list", "--first-parent", f"{base}..{head}"]))
        earlier = [sha for sha in earlier if sha != head][:MAX_EARLIER_COMMITS]
        passed = next((sha for sha in earlier if has_passed_gate(repository, sha)), None)
        if passed is None:
            return "full", "no earlier passed gate"
        since = lines(run(["git", "diff", "--name-only", "--no-renames", passed, merge]))
    except CommandFailed as failure:
        return "full", f"could not read {failure.command}"
    not_data = [path for path in since if not is_data_path(path)]
    if not not_data:
        return "light", f"only data since {passed[:7]} passed"
    return "full", f"{not_data[0]} changed since {passed[:7]} passed"


def required(environment, name):
    value = environment.get(name, "")
    if not value:
        print(f"Missing {name}.")
        sys.exit(2)
    return value


def ci(environment):
    event_name = required(environment, "EVENT_NAME")
    output = required(environment, "GITHUB_OUTPUT")
    if event_name == "pull_request":
        for name in ("BASE_SHA", "HEAD_SHA", "MERGE_SHA", "GITHUB_REPOSITORY"):
            required(environment, name)
    mode, reason = decide(event_name, environment)
    with open(output, "a", encoding="utf-8") as file:
        file.write(f"mode={mode}\n")
    print(f"gate: {mode} ({reason})")
    return 0


def main(arguments):
    if arguments == ["lab"]:
        return lab()
    if arguments == ["ci"]:
        return ci(os.environ)
    print(USAGE)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
