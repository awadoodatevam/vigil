"""Tests of tools/gate_scope.py (P-42 spec Section 7).

Each test builds a temporary git repository with a main and a branch, copies tools/gate_scope.py into its tools/
folder (committed on main) and runs that copy. For ci, a fake gh first on PATH answers the check-runs requests from
the JSON file named by FAKE_GH_RUNS ({sha: [conclusion or {"conclusion", "app"}, ...]}; the key "mode" set to
"fail", "text", "list" or "no-check-runs" changes every answer) and records its calls in FAKE_GH_LOG.
"""

import importlib.util
import json
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

SCRIPT = Path(__file__).with_name("gate_scope.py")
REPOSITORY = "owner/vigil"

FAKE_GH = textwrap.dedent(
    """\
    import json
    import os
    import re
    import sys

    with open(os.environ["FAKE_GH_LOG"], "a") as log:
        log.write(json.dumps(sys.argv[1:]) + "\\n")
    with open(os.environ["FAKE_GH_RUNS"]) as file:
        runs = json.load(file)
    mode = runs.get("mode", "ok")
    if mode == "fail":
        sys.stderr.write("HTTP 502\\n")
        sys.exit(1)
    if mode == "text":
        print("Bad gateway")
        sys.exit(0)
    if mode == "list":
        print("[]")
        sys.exit(0)
    if mode == "no-check-runs":
        print(json.dumps({"total_count": 0}))
        sys.exit(0)
    sha = re.search(r"/commits/([0-9a-f]+)/check-runs", " ".join(sys.argv[1:])).group(1)
    entries = []
    for item in runs.get(sha, []):
        if isinstance(item, str):
            item = {"conclusion": item, "app": "github-actions"}
        entries.append({"name": "gate", "conclusion": item["conclusion"], "app": {"slug": item["app"]}})
    print(json.dumps({"total_count": len(entries), "check_runs": entries}))
    """
)


def git(root, *arguments):
    result = subprocess.run(
        ["git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false", *arguments],
        cwd=root,
        capture_output=True,
        text=True,
        check=True,
    )
    return result.stdout.strip()


class Repo:
    """A temporary repository: main with gate_scope.py and a few files, origin/main, and the branch feature."""

    def __init__(self, base):
        self.base = base
        self.root = base / "repository"
        self.root.mkdir()
        git(self.root, "init", "-q", "-b", "main")
        (self.root / "tools").mkdir()
        shutil.copy2(SCRIPT, self.root / "tools" / "gate_scope.py")
        self.write(
            {
                "README.md": "readme\n",
                "app/src/main/A.java": "class A {}\n",
                "docs/a.md": "a\n",
                "docs/31-demo-data/x.yaml": "x: 1\n",
                "demo/lab/moved.txt": "a file that moves\n" * 20,
                "app/old/Moved.java": "class Moved {}\n" * 20,
            }
        )
        self.base_sha = self.commit("base")
        git(self.root, "update-ref", "refs/remotes/origin/main", "main")
        git(self.root, "checkout", "-q", "-b", "feature")

    def write(self, files):
        for relative, content in files.items():
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")

    def commit(self, message, files=None):
        if files:
            self.write(files)
        git(self.root, "add", "-A")
        git(self.root, "commit", "-q", "--allow-empty", "-m", message)
        return git(self.root, "rev-parse", "HEAD")

    def move(self, source, target, message):
        (self.root / target).parent.mkdir(parents=True, exist_ok=True)
        git(self.root, "mv", source, target)
        return self.commit(message)

    def advance_main(self, files):
        """A commit on main after the branch was made; origin/main follows. Returns the new base."""
        git(self.root, "checkout", "-q", "main")
        sha = self.commit("main moves on", files)
        git(self.root, "update-ref", "refs/remotes/origin/main", "main")
        git(self.root, "checkout", "-q", "feature")
        return sha

    def merge(self, base, head):
        """The merge commit a pull request run checks out: base merged with head."""
        current = git(self.root, "rev-parse", "--abbrev-ref", "HEAD")
        git(self.root, "checkout", "-q", "--detach", base)
        git(self.root, "merge", "-q", "--no-ff", "-m", "merge", head)
        sha = git(self.root, "rev-parse", "HEAD")
        git(self.root, "checkout", "-q", current)
        return sha

    def run(self, *arguments, environment=None):
        bin_dir = self.base / "bin"
        bin_dir.mkdir(exist_ok=True)
        fake = bin_dir / "gh"
        if not fake.exists():
            fake.write_text(f"#!{sys.executable}\n" + FAKE_GH, encoding="utf-8")
            fake.chmod(0o755)
        env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
        for name in ("EVENT_NAME", "GITHUB_OUTPUT", "BASE_SHA", "HEAD_SHA", "MERGE_SHA", "GITHUB_REPOSITORY"):
            env.pop(name, None)
        env["PATH"] = os.pathsep.join([str(bin_dir), env.get("PATH", "")])
        env["FAKE_GH_LOG"] = str(self.base / "gh.log")
        env["FAKE_GH_RUNS"] = str(self.base / "runs.json")
        env.update(environment or {})
        return subprocess.run(
            [sys.executable, str(self.root / "tools" / "gate_scope.py"), *arguments],
            cwd=self.base,
            env=env,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )

    def ci(self, base, head, runs=None, merge=None):
        """Runs gate_scope.py ci for a pull request from base to head; returns (result, mode written, gh calls)."""
        (self.base / "runs.json").write_text(json.dumps(runs or {}), encoding="utf-8")
        (self.base / "gh.log").write_text("", encoding="utf-8")
        output = self.base / "github-output"
        output.write_text("", encoding="utf-8")
        result = self.run(
            "ci",
            environment={
                "EVENT_NAME": "pull_request",
                "GITHUB_OUTPUT": str(output),
                "BASE_SHA": base,
                "HEAD_SHA": head,
                "MERGE_SHA": merge or self.merge(base, head),
                "GITHUB_REPOSITORY": REPOSITORY,
            },
        )
        calls = [json.loads(line) for line in (self.base / "gh.log").read_text().splitlines()]
        return result, output.read_text(encoding="utf-8"), calls

    def lab(self):
        result = self.run("lab")
        assert result.returncode == 0, result.stderr
        return result.stdout.strip()


@pytest.fixture
def repo(tmp_path):
    return Repo(tmp_path)


def check_runs_call(sha):
    return ["api", f"repos/{REPOSITORY}/commits/{sha}/check-runs?check_name=gate&filter=latest&per_page=100"]


# --- ci ---


def test_manual_run_is_full(repo):
    """BP-W-01 a manual run (empty BASE_SHA and HEAD_SHA) runs the full gate."""
    output = repo.base / "github-output"
    output.write_text("earlier=1\n", encoding="utf-8")
    result = repo.run(
        "ci",
        environment={
            "EVENT_NAME": "workflow_dispatch",
            "GITHUB_OUTPUT": str(output),
            "BASE_SHA": "",
            "HEAD_SHA": "",
        },
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout == "gate: full (not a pull request)\n"
    assert output.read_text(encoding="utf-8") == "earlier=1\nmode=full\n"


def test_data_only_pull_request_is_light(repo):
    """BP-W-01 a pull request that changes only data paths runs the light gate without asking GitHub."""
    head = repo.commit(
        "data",
        {
            "docs/packages/P-99-report.md": "report\n",
            "spikes/results/r.json": "{}\n",
            "tests/acceptance/results/r.txt": "ok\n",
            "dev/fakes/recordings/teams/r.json": "{}\n",
        },
    )
    result, mode, calls = repo.ci(repo.base_sha, head)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout == "gate: light (data only)\n"
    assert mode == "mode=light\n"
    assert calls == []


@pytest.mark.parametrize(
    "path",
    [
        "docs/31-demo-data/x.yaml",
        "docs/tools/check.py",
        "spikes/results/run.sh",
        "docs/images/Dockerfile",
        "dev/fakes/recordings/compose.yaml",
    ],
)
def test_pull_request_with_a_path_that_is_not_data_is_full(repo, path):
    """BP-W-01 docs/31-demo-data/, .py, .sh, Dockerfile and compose.yaml files are not data paths."""
    head = repo.commit("mixed", {"docs/b.md": "b\n", path: "changed\n"})
    result, mode, _ = repo.ci(repo.base_sha, head)
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout == "gate: full (no earlier passed gate)\n"
    assert mode == "mode=full\n"


def test_only_data_since_a_passed_gate_is_light(repo):
    """BP-W-01 only data paths since the last commit with a passed gate gives the light gate."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("report", {"docs/packages/P-99-report.md": "report\n", "spikes/results/r.json": "{}\n"})
    result, mode, calls = repo.ci(repo.base_sha, head, runs={code: ["success"]})
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout == f"gate: light (only data since {code[:7]} passed)\n"
    assert mode == "mode=light\n"
    assert calls == [check_runs_call(code)]


def test_readme_since_a_passed_gate_is_full(repo):
    """BP-W-01 a data path plus README.md since the passed commit is full and names README.md."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("more", {"docs/packages/P-99-report.md": "report\n", "README.md": "changed\n"})
    result, mode, _ = repo.ci(repo.base_sha, head, runs={code: ["success"]})
    assert result.stdout == f"gate: full (README.md changed since {code[:7]} passed)\n"
    assert mode == "mode=full\n"


def test_demo_data_since_a_passed_gate_is_full(repo):
    """BP-W-01 a change under docs/31-demo-data/ since the passed commit is full."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("data", {"docs/31-demo-data/x.yaml": "x: 2\n"})
    result, mode, _ = repo.ci(repo.base_sha, head, runs={code: ["success"]})
    assert result.stdout == f"gate: full (docs/31-demo-data/x.yaml changed since {code[:7]} passed)\n"
    assert mode == "mode=full\n"


def test_file_moved_from_app_to_docs_is_full(repo):
    """BP-W-01 a file moved from app/ to docs/ names both paths, so the gate is full."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.move("app/old/Moved.java", "docs/Moved.java.txt", "move")
    result, mode, _ = repo.ci(repo.base_sha, head, runs={code: ["success"]})
    assert result.stdout == f"gate: full (app/old/Moved.java changed since {code[:7]} passed)\n"
    assert mode == "mode=full\n"


def test_moved_file_in_the_whole_pull_request_is_not_data_only(repo):
    """BP-W-01 a pull request whose only change moves a file from app/ to docs/ is not data only."""
    head = repo.move("app/old/Moved.java", "docs/Moved.java.txt", "move")
    result, mode, _ = repo.ci(repo.base_sha, head)
    assert result.stdout == "gate: full (no earlier passed gate)\n"
    assert mode == "mode=full\n"


def test_answer_without_check_runs_is_full(repo):
    """BP-W-01 an answer of gh without check_runs gives the full gate with "could not read"."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("report", {"docs/r.md": "r\n"})
    result, mode, _ = repo.ci(repo.base_sha, head, runs={"mode": "no-check-runs"})
    command = "gh " + " ".join(check_runs_call(code))
    assert result.returncode == 0
    assert result.stdout == f"gate: full (could not read {command})\n"
    assert mode == "mode=full\n"


@pytest.mark.parametrize("answer", ["fail", "text", "list"])
def test_gh_failing_or_not_answering_json_is_full(repo, answer):
    """BP-W-01 gh failing, or answering text or JSON that is not an object, gives the full gate."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("report", {"docs/r.md": "r\n"})
    result, mode, _ = repo.ci(repo.base_sha, head, runs={"mode": answer})
    command = "gh " + " ".join(check_runs_call(code))
    assert result.returncode == 0
    assert result.stdout == f"gate: full (could not read {command})\n"
    assert mode == "mode=full\n"


def test_git_failing_is_full(repo):
    """BP-W-01 a git command that fails gives the full gate with "could not read" and the command."""
    head = repo.commit("report", {"docs/r.md": "r\n"})
    missing = "0" * 40
    result, mode, _ = repo.ci(missing, head, merge=head)
    assert result.returncode == 0
    assert result.stdout == f"gate: full (could not read git diff --name-only --no-renames {missing}...{head})\n"
    assert mode == "mode=full\n"


def test_failed_and_cancelled_gates_are_skipped(repo):
    """BP-W-01 a commit with only failure or cancelled is skipped for an older one with success."""
    passed = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    failed = repo.commit("report 1", {"docs/r.md": "1\n"})
    head = repo.commit("report 2", {"docs/r.md": "2\n"})
    result, mode, calls = repo.ci(repo.base_sha, head, runs={failed: ["failure", "cancelled"], passed: ["success"]})
    assert result.stdout == f"gate: light (only data since {passed[:7]} passed)\n"
    assert mode == "mode=light\n"
    assert calls == [check_runs_call(failed), check_runs_call(passed)]


def test_success_of_another_app_does_not_count(repo):
    """BP-W-01 a success from an app other than github-actions does not count as a passed gate."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("report", {"docs/r.md": "r\n"})
    runs = {code: [{"conclusion": "success", "app": "other-app"}, {"conclusion": "failure", "app": "github-actions"}]}
    result, mode, _ = repo.ci(repo.base_sha, head, runs=runs)
    assert result.stdout == "gate: full (no earlier passed gate)\n"
    assert mode == "mode=full\n"


def test_no_passed_commit_is_full(repo):
    """BP-W-01 no earlier commit with a passed gate gives the full gate."""
    repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("report", {"docs/r.md": "r\n"})
    result, mode, calls = repo.ci(repo.base_sha, head)
    assert result.stdout == "gate: full (no earlier passed gate)\n"
    assert mode == "mode=full\n"
    assert len(calls) == 1


def test_at_most_twenty_commits_are_asked(repo):
    """BP-W-01 at most the 20 newest earlier commits are asked about, newest first, the head not."""
    oldest = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    earlier = [repo.commit(f"report {number}", {"docs/r.md": f"{number}\n"}) for number in range(21)]
    head = repo.commit("report head", {"docs/r.md": "head\n"})
    result, mode, calls = repo.ci(repo.base_sha, head, runs={earlier[0]: ["success"], oldest: ["success"]})
    assert result.stdout == "gate: full (no earlier passed gate)\n"
    assert mode == "mode=full\n"
    assert calls == [check_runs_call(sha) for sha in reversed(earlier[1:])]


def test_main_advanced_with_code_is_full(repo):
    """BP-W-01 a code change main gained after the passed commit (held by the merge commit) gives the full gate."""
    code = repo.commit("code", {"app/src/main/A.java": "class A { int a; }\n"})
    head = repo.commit("report", {"docs/r.md": "r\n"})
    base = repo.advance_main({"app/src/main/B.java": "class B {}\n"})
    result, mode, _ = repo.ci(base, head, runs={code: ["success"]})
    assert result.stdout == f"gate: full (app/src/main/B.java changed since {code[:7]} passed)\n"
    assert mode == "mode=full\n"


@pytest.mark.parametrize(
    "name",
    ["EVENT_NAME", "GITHUB_OUTPUT", "BASE_SHA", "HEAD_SHA", "MERGE_SHA", "GITHUB_REPOSITORY"],
)
@pytest.mark.parametrize("value", [None, ""])
def test_missing_variable_exits_2(repo, name, value):
    """BP-W-01 a required variable that is unset or empty prints "Missing {name}." and exits 2."""
    output = repo.base / "github-output"
    output.write_text("", encoding="utf-8")
    environment = {
        "EVENT_NAME": "pull_request",
        "GITHUB_OUTPUT": str(output),
        "BASE_SHA": repo.base_sha,
        "HEAD_SHA": repo.base_sha,
        "MERGE_SHA": repo.base_sha,
        "GITHUB_REPOSITORY": REPOSITORY,
    }
    if value is None:
        del environment[name]
    else:
        environment[name] = value
    result = repo.run("ci", environment=environment)
    assert result.returncode == 2
    assert result.stdout == f"Missing {name}.\n"
    assert output.read_text(encoding="utf-8") == ""


def test_event_name_is_checked_first(repo):
    """BP-W-01 with every variable missing, EVENT_NAME is named."""
    result = repo.run("ci")
    assert result.returncode == 2
    assert result.stdout == "Missing EVENT_NAME.\n"


# --- lab ---

LAB_CHANGES = [
    "demo/faults/x.py",
    "dev/recorder/x.py",
    "deploy/local/x.yaml",
    "deploy/local.sh",
    "deploy/images.sh",
    "deploy/values/local.yaml",
    "spikes/storm-timing/x.py",
    "docs/31-demo-data/y.yaml",
    "tools/check-all.sh",
    "tools/gate_scope.py",
    "tools/start-docker.sh",
    "tools/requirements-dev.txt",
    "pytest.ini",
    ".github/workflows/ci.yml",
]


def test_lab_change_list_covers_every_entry():
    """BP-R-12 the tested changes name each entry of LAB_PATHS."""
    spec = importlib.util.spec_from_file_location("vigil_gate_scope", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    for entry in module.LAB_PATHS:
        assert any(module.matches(path, [entry]) for path in LAB_CHANGES), entry


@pytest.mark.parametrize("path", LAB_CHANGES)
def test_lab_path_gives_lab(repo, path):
    """BP-R-12 a committed change of each entry of the lab path list gives lab."""
    target = repo.root / path
    previous = target.read_text(encoding="utf-8") if target.exists() else ""
    repo.commit("lab change", {path: previous + "# changed\n"})
    assert repo.lab() == "lab"


@pytest.mark.parametrize(
    "path",
    [
        "spikes/providers/x.py",
        "deploy/values/demo.yaml",
        "dev/fakes/recordings/x.json",
        "tools/trace.py",
        "app/src/main/x.java",
    ],
)
def test_other_path_gives_no_lab(repo, path):
    """BP-R-12 a change outside the lab path list gives no-lab."""
    repo.commit("other change", {path: "changed\n"})
    assert repo.lab() == "no-lab"


def test_file_moved_from_demo_to_docs_gives_lab(repo):
    """BP-R-12 a file moved from demo/ to docs/ names its old path, so it gives lab."""
    repo.move("demo/lab/moved.txt", "docs/moved.txt", "move")
    assert repo.lab() == "lab"


def test_untracked_file_under_demo_gives_lab(repo):
    """BP-R-12 an untracked file under demo/ gives lab."""
    repo.write({"demo/new.txt": "new\n"})
    assert repo.lab() == "lab"


def test_no_origin_main_gives_lab(repo):
    """BP-R-12 without origin/main the answer is lab."""
    git(repo.root, "update-ref", "-d", "refs/remotes/origin/main")
    assert repo.lab() == "lab"
