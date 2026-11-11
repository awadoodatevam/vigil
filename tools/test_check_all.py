"""Tests of the parts of tools/check-all.sh (P-42 spec Section 7, BP-R-12).

tools/check-all.sh is copied into a temporary git tree with stubs that record their calls: ./mvnw, deploy/images.sh,
shellcheck and docker on PATH, and the Python of VIGIL_TOOLS, whose answer to tools/gate_scope.py lab comes from
STUB_LAB. The tree is not a cloud session (CLAUDE_CODE_REMOTE is removed), so steps (0) and (1) do not run.
"""

import json
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
USAGE = "Usage: tools/check-all.sh [--part java|rest]\n"

STUB = textwrap.dedent(
    """\
    import json
    import os
    import sys

    name = os.path.basename(sys.argv[0])
    with open(os.environ["STUB_LOG"], "a") as log:
        log.write(json.dumps([name, *sys.argv[1:]]) + "\\n")
    if name == "python" and sys.argv[1:] == ["tools/gate_scope.py", "lab"]:
        print(os.environ["STUB_LAB"])
    """
)


class Tree:
    def __init__(self, base):
        self.base = base
        self.root = base / "tree"
        self.log = base / "calls.log"
        self.bin = base / "bin"
        self.tools = base / "venv"
        (self.root / "tools").mkdir(parents=True)
        shutil.copy2(ROOT / "tools" / "check-all.sh", self.root / "tools" / "check-all.sh")
        stub = f"#!{sys.executable}\n" + STUB
        for path in (
            self.root / "mvnw",
            self.root / "deploy" / "images.sh",
            self.bin / "shellcheck",
            self.bin / "docker",
            self.tools / "bin" / "python",
        ):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(stub, encoding="utf-8")
            path.chmod(0o755)
        (self.root / "deploy" / "local").mkdir()
        (self.root / "deploy" / "local" / "compose.yaml").write_text("services: {}\n", encoding="utf-8")
        git = ["git", "-c", "user.name=t", "-c", "user.email=t@example.com", "-c", "commit.gpgsign=false"]
        subprocess.run([*git, "init", "-q", "-b", "main"], cwd=self.root, check=True)
        subprocess.run([*git, "add", "-A"], cwd=self.root, check=True)
        subprocess.run([*git, "commit", "-q", "-m", "tree"], cwd=self.root, check=True)

    def run(self, *arguments, lab="lab"):
        self.log.write_text("", encoding="utf-8")
        environment = {key: value for key, value in os.environ.items() if key != "CLAUDE_CODE_REMOTE"}
        environment.update(
            PATH=os.pathsep.join([str(self.bin), environment.get("PATH", "")]),
            VIGIL_TOOLS=str(self.tools),
            STUB_LOG=str(self.log),
            STUB_LAB=lab,
        )
        result = subprocess.run(
            ["bash", str(self.root / "tools" / "check-all.sh"), *arguments],
            cwd=self.base,
            env=environment,
            capture_output=True,
            text=True,
            timeout=120,
            check=False,
        )
        calls = [json.loads(line) for line in self.log.read_text(encoding="utf-8").splitlines()]
        return result, calls


@pytest.fixture
def tree(tmp_path):
    return Tree(tmp_path)


IMAGES = [["images.sh", "--test"]]
JAVA = [["mvnw", "-B", "verify"]]


def rest(markers):
    return [
        ["python", "-m", "ruff", "check", "."],
        ["python", "-m", "ruff", "format", "--check", "."],
        ["python", "tools/gate_scope.py", "lab"],
        ["python", "-m", "pytest", "-m", markers, "tools", "deploy"],
        ["shellcheck", "deploy/images.sh", "tools/check-all.sh"],
        ["python", "docs/31-demo-data/tools/check_data.py", "docs/31-demo-data"],
        ["docker", "compose", "-f", "deploy/local/compose.yaml", "config", "--quiet"],
        ["python", "tools/trace.py", "--check"],
    ]


def test_without_arguments_every_step_runs(tree):
    """BP-R-12 without arguments every step runs in order."""
    result, calls = tree.run()
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls == IMAGES + JAVA + rest("not installation")
    assert result.stdout.endswith("== all checks passed\n")


def test_part_java(tree):
    """BP-R-12 --part java runs only deploy/images.sh --test --java and ./mvnw -B verify."""
    result, calls = tree.run("--part", "java")
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls == [["images.sh", "--test", "--java"]] + JAVA
    assert result.stdout.endswith("== all checks passed\n")


def test_part_rest(tree):
    """BP-R-12 --part rest runs only deploy/images.sh --test and steps (3) to (7)."""
    result, calls = tree.run("--part", "rest")
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls == IMAGES + rest("not installation")
    assert "== step 2" not in result.stdout
    assert result.stdout.endswith("== all checks passed\n")


def test_no_lab_excludes_the_lab_tests(tree):
    """BP-R-12 no-lab from tools/gate_scope.py lab adds "and not lab" to the markers and says so."""
    result, calls = tree.run("--part", "rest", lab="no-lab")
    assert result.returncode == 0, result.stdout + result.stderr
    assert calls == IMAGES + rest("not installation and not lab")
    assert "lab tests skipped: no change to the lab (BP-R-12 step (3))\n" in result.stdout


@pytest.mark.parametrize(
    "arguments",
    [["--part", "web"], ["--part"], ["--part", "java", "x"], ["java"], ["--part", "rest", "--part", "java"]],
)
def test_wrong_arguments_print_usage(tree, arguments):
    """BP-R-12 other arguments print the usage line and exit 2 before any call."""
    result, calls = tree.run(*arguments)
    assert result.returncode == 2
    assert result.stdout == USAGE
    assert calls == []


@pytest.mark.parametrize("arguments", [[], ["ci", "lab"], ["web"]])
def test_gate_scope_wrong_arguments(arguments):
    """BP-R-12 tools/gate_scope.py without ci or lab prints its usage line and exits 2."""
    result = subprocess.run(
        [sys.executable, str(ROOT / "tools" / "gate_scope.py"), *arguments],
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )
    assert result.returncode == 2
    assert result.stdout == "Usage: tools/gate_scope.py ci|lab\n"
