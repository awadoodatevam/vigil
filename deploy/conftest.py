"""Test support of the deploy/ tests (P-05 spec Section 7).

Each test gets a Harness: a temporary repository root with copies of the scripts (so no test changes a file of the
repository), a temporary VIGIL_ROOT, the state of the fake AWS CLI, the table and log of the docker and curl fakes,
and a run() that starts a script with bash, VIGIL_TEST_FAST=1 and PATH starting with deploy/tests/bin (then, on
machines without ssh-keygen, deploy/tests/keygen-bin) and the gate's Python tools.
"""

import json
import os
import shutil
import subprocess
from pathlib import Path

import pytest

REPOSITORY = Path(__file__).resolve().parent.parent
FAKES = REPOSITORY / "deploy" / "tests" / "bin"
KEYGEN_FAKE = REPOSITORY / "deploy" / "tests" / "keygen-bin"
TOOLS = Path(os.environ.get("VIGIL_TOOLS", "/opt/vigil-tools")) / "bin"
ACCOUNT = "123456789012"
REGISTRY = f"{ACCOUNT}.dkr.ecr.us-east-1.amazonaws.com"
COPIED = [
    "deploy/lib",
    "deploy/hosts",
    "deploy/main",
    "deploy/gpu",
    "deploy/values",
    "deploy/models.lock",
    "deploy/units.yaml",
    "deploy/secrets.sh",
    "deploy/web_config.py",
    "deploy/edge.sh",
    "deploy/new-installation.sh",
    "deploy/build.sh",
    "deploy/deploy.sh",
    "deploy/status.sh",
    "deploy/gpu.sh",
    "deploy/models.sh",
]


def search_path():
    """PATH for the scripts: the fakes, ssh-keygen's fake where the machine has none, the gate's tools, the rest."""
    parts = [str(FAKES)]
    if shutil.which("ssh-keygen") is None:
        parts.append(str(KEYGEN_FAKE))
    parts.append(str(TOOLS))
    return os.pathsep.join(parts + [os.environ.get("PATH", "")])


def keygen():
    """The ssh-keygen the tests themselves use: the machine's own, or the fake (which needs the lab image)."""
    return shutil.which("ssh-keygen") or str(KEYGEN_FAKE / "ssh-keygen")


class Harness:
    def __init__(self, base):
        self.base = base
        self.root = base / "repository"
        self.vigil_root = base / "vigil"
        self.state_path = base / "aws-state.json"
        self.log_path = base / "fake.log"
        self.table_path = base / "fake-table.json"
        for item in COPIED:
            source = REPOSITORY / item
            target = self.root / item
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)
        self.vigil_root.mkdir()
        self.log_path.write_text("")
        self.seed()
        self.table([])

    def seed(self, **state):
        state.setdefault("account", ACCOUNT)
        self.state_path.write_text(json.dumps(state))

    def state(self):
        return json.loads(self.state_path.read_text())

    def update_state(self, **changes):
        state = self.state()
        state.update(changes)
        self.state_path.write_text(json.dumps(state))

    def table(self, entries):
        """Seeds the docker and curl fakes: (command, pattern, [(out, code), ...]) per entry."""
        rows = [
            {"command": command, "pattern": pattern, "answers": [{"out": out, "code": code} for out, code in answers]}
            for command, pattern, answers in entries
        ]
        self.table_path.write_text(json.dumps(rows))
        Path(str(self.table_path) + ".counts").unlink(missing_ok=True)

    def environment(self, **extra):
        environment = {
            key: value for key, value in os.environ.items() if not key.startswith(("AWS_", "VIGIL_ROOT", "FAKE_"))
        }
        environment.update(
            PATH=search_path(),
            VIGIL_TEST_FAST="1",
            VIGIL_ROOT=str(self.vigil_root),
            FAKE_AWS_STATE=str(self.state_path),
            FAKE_LOG=str(self.log_path),
            FAKE_TABLE=str(self.table_path),
        )
        environment.update(extra)
        return environment

    def run(self, script, *arguments, **extra):
        """Runs a script (a path relative to the temporary repository root, or an absolute path) with bash."""
        path = Path(script) if Path(script).is_absolute() else self.root / script
        return subprocess.run(
            ["bash", str(path), *arguments],
            cwd=self.root,
            env=self.environment(**extra),
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=600,
            check=False,
        )

    def fake_calls(self, command=None):
        """The calls of the docker and curl fakes (and of stubs that write the same log), in order."""
        calls = [json.loads(line) for line in self.log_path.read_text().splitlines() if line.strip()]
        return [call for call in calls if command is None or call["command"] == command]

    def aws_calls(self, service=None, operation=None):
        calls = self.state().get("calls", [])
        return [
            call
            for call in calls
            if (service is None or call["args"][0] == service) and (operation is None or call["args"][1] == operation)
        ]


@pytest.fixture
def harness(tmp_path):
    return Harness(tmp_path)


def tags_json(entries):
    """An /api/tags answer of Ollama with the given {name: digest} entries."""
    return json.dumps({"models": [{"name": name, "model": name, "digest": digest} for name, digest in entries.items()]})


def write_lock(path, embedding_digest, llm_digest):
    path.write_text(
        f"# models.lock of a test\nembeddinggemma vigil-embed {embedding_digest}\ngemma4:12b vigil-llm {llm_digest}\n"
    )
