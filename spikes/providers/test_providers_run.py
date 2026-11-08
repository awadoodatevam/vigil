"""Tests of spikes/providers/run.sh and host-address.sh (P-06 spec Sections 6.9 and 7).

Each test runs a copy of spikes/providers/, deploy/lib/aws.sh, deploy/values/demo.yaml and docs/31-demo-data/ in a
temporary root, with a .venv/bin/python there that is the gate's Python, the fake AWS CLI and Docker of deploy/tests/bin
and fakes for python3.12 and session-manager-plugin on PATH.
"""

import json
import os
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

import spike_fakes
import test_local_model_spike
import test_similarity_spike
from spike_fakes import json_answer

REPOSITORY = spike_fakes.REPOSITORY
AWS_FAKES = REPOSITORY / "deploy" / "tests" / "bin"
INSTANCES = {
    "i-0main": {"Name": "vigil-demo-main", "State": "running"},
    "i-0gpu": {"Name": "vigil-demo-gpu", "State": "running"},
}
EMBED_ADDRESS = "172.30.0.7"


def executable(path, text):
    path.write_text(text)
    path.chmod(0o755)


class Root:
    def __init__(self, base):
        self.base = base
        self.root = base / "repository"
        for item in ("spikes/providers", "deploy/lib/aws.sh", "deploy/values/demo.yaml", "docs/31-demo-data"):
            source = REPOSITORY / item
            target = self.root / item
            target.parent.mkdir(parents=True, exist_ok=True)
            if source.is_dir():
                shutil.copytree(source, target, ignore=shutil.ignore_patterns(".venv", "__pycache__", ".pytest_cache"))
            else:
                shutil.copy2(source, target)
        venv = self.root / "spikes" / "providers" / ".venv" / "bin"
        venv.mkdir(parents=True)
        executable(venv / "python", f'#!/bin/sh\nexec "{sys.executable}" "$@"\n')
        self.fakes = base / "fakes"
        self.fakes.mkdir()
        executable(self.fakes / "python3.12", f'#!/bin/sh\nexec "{sys.executable}" "$@"\n')
        executable(self.fakes / "session-manager-plugin", "#!/bin/sh\nexit 0\n")
        self.results = base / "results"
        self.recordings = base / "recordings"
        self.vigil_root = base / "vigil"
        self.vigil_root.mkdir()
        self.state_path = base / "aws-state.json"
        self.log_path = base / "fake.log"
        self.table_path = base / "fake-table.json"
        self.log_path.write_text("")
        self.seed(instances=INSTANCES)
        self.table([])

    def seed(self, **state):
        state.setdefault("account", "123456789012")
        self.state_path.write_text(json.dumps(state))

    def state(self):
        return json.loads(self.state_path.read_text())

    def table(self, entries):
        rows = [
            {"command": command, "pattern": pattern, "answers": [{"out": out, "code": code} for out, code in answers]}
            for command, pattern, answers in entries
        ]
        self.table_path.write_text(json.dumps(rows))

    def environment(self, path=None, **extra):
        environment = {
            key: value
            for key, value in os.environ.items()
            if not key.startswith(("AWS_", "FAKE_", "VIGIL_ROOT", "VIGIL_SPIKE"))
        }
        environment.update(
            PATH=path or os.pathsep.join([str(self.fakes), str(AWS_FAKES), os.environ.get("PATH", "")]),
            VIGIL_TEST_FAST="1",
            VIGIL_ROOT=str(self.vigil_root),
            VIGIL_SPIKE_RESULTS=str(self.results),
            VIGIL_SPIKE_RECORDINGS=str(self.recordings),
            FAKE_AWS_STATE=str(self.state_path),
            FAKE_LOG=str(self.log_path),
            FAKE_TABLE=str(self.table_path),
        )
        environment.update(extra)
        return environment

    def run(self, *arguments, path=None, **extra):
        return subprocess.run(
            [shutil.which("bash"), str(self.root / "spikes" / "providers" / "run.sh"), *arguments],
            cwd=self.base,
            env=self.environment(path, **extra),
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            timeout=300,
            check=False,
        )

    def sessions(self):
        return [call for call in self.state().get("calls", []) if call["args"][:2] == ["ssm", "start-session"]]


def ended(pid):
    """True once the process is gone or a zombie, within 10 s."""
    for _ in range(100):
        try:
            state = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()[0]
        except (FileNotFoundError, ProcessLookupError):
            return True
        if state == "Z":
            return True
        time.sleep(0.1)
    return False


def refused(port):
    try:
        with socket.create_connection(("127.0.0.1", port), timeout=1):
            return False
    except OSError:
        return True


def check_forward(root, address):
    """The start-session call, its process group ended and its port closed after run.sh ended."""
    sessions = root.sessions()
    assert len(sessions) == 1
    args = sessions[0]["args"]
    assert args[:6] == [
        "ssm",
        "start-session",
        "--target",
        args[3],
        "--document-name",
        "AWS-StartPortForwardingSessionToRemoteHost",
    ]
    parameters = json.loads(args[args.index("--parameters") + 1])
    assert parameters["host"] == [address]
    assert parameters["portNumber"] == ["11434"]
    port = int(parameters["localPortNumber"][0])
    assert sessions[0]["region"] == "us-east-1"
    assert ended(sessions[0]["pid"])
    assert refused(port)
    return args[3]


def test_usage_and_identity(tmp_path):
    """SB-T-03 run.sh: the usage line and exit 2; without an AWS identity, the sign-in message and exit 1."""
    root = Root(tmp_path)
    for arguments in ([], ["teams"], ["jira", "claude"]):
        result = root.run(*arguments)
        assert result.returncode == 2
        assert result.stdout == "Usage: spikes/providers/run.sh jira|claude|openai|similarity|local-model\n"
    root.seed(instances=INSTANCES, errors={"sts get-caller-identity": "ExpiredToken"})
    result = root.run("jira")
    assert result.returncode == 1
    assert (
        result.stdout.strip()
        == "Sign in first: aws login --profile vigil, then export AWS_PROFILE=vigil (deploy/edge.md)."
    )


def test_no_python(tmp_path):
    """SB-T-04 run.sh without python3.12 on PATH stops with the BP-OT-10 message."""
    root = Root(tmp_path)
    tools = tmp_path / "tools"
    tools.mkdir()
    for name in ("bash", "dirname"):
        (tools / name).symlink_to(shutil.which(name))
    (tools / "python3").symlink_to(sys.executable)
    (tools / "aws").symlink_to(AWS_FAKES / "aws")
    result = root.run("similarity", path=str(tools))
    assert result.returncode == 1, result.stdout + result.stderr
    assert result.stdout == "Python 3.12 is needed as python3.12 (BP-OT-10).\n"


def test_no_plugin_and_host_stopped(tmp_path):
    """SB-T-04 run.sh without session-manager-plugin, and with the GPU host stopped, exits 1 with its message."""
    root = Root(tmp_path)
    (root.fakes / "session-manager-plugin").unlink()
    path = os.pathsep.join([str(root.fakes), str(AWS_FAKES), "/usr/bin:/bin"])
    result = root.run("local-model", path=path)
    assert result.returncode == 1
    assert result.stdout == (
        "Install the AWS Session Manager plugin first: brew install --cask session-manager-plugin (CTX-D-060).\n"
    )
    root = Root(tmp_path / "stopped")
    root.seed(instances=dict(INSTANCES, **{"i-0gpu": {"Name": "vigil-demo-gpu", "State": "stopped"}}))
    result = root.run("local-model")
    assert result.returncode == 1
    assert result.stdout == (
        "The GPU host is stopped. Start it first (deploy/edge.md, or deploy/gpu.sh start for the GPU host).\n"
    )
    assert root.sessions() == []


def test_local_model_through_forward(tmp_path):
    """SB-T-04 run.sh local-model: the port forward to vigil-llm reaches the fake Ollama and ends with the script."""
    root = Root(tmp_path)
    fake = test_local_model_spike.FakeOllama()
    with spike_fakes.FakeServer(fake) as server:
        result = root.run("local-model", FAKE_AWS_FORWARD=server.address.removeprefix("http://"))
    assert result.returncode == 0, result.stdout + result.stderr
    assert result.stdout.splitlines()[-1] == f"result: {root.results / 'P-06-SB-T-04.json'}"
    assert check_forward(root, "10.40.1.20") == "i-0gpu"
    assert json.loads((root.results / "P-06-SB-T-04.json").read_text())["passed"] is True
    assert fake.chats == 5


def embed_answers(request):
    if request.path == "/api/tags":
        return json_answer(200, {"models": [{"name": "embeddinggemma:latest"}]})
    return EMBED(request)


EMBED = test_similarity_spike.FakeOllama()


def test_similarity_through_forward(tmp_path):
    """SB-T-03 run.sh similarity: host-address.sh finds vigil-embed's address; the forward reaches the fake Ollama."""
    root = Root(tmp_path)
    root.table(
        [
            ("docker", r"^inspect --format \{\{\.State\.Running\}\} vigil-embed$", [("true\n", 0)]),
            ("docker", r"^inspect --format \{\{with index", [(EMBED_ADDRESS + "\n", 0)]),
        ]
    )
    with spike_fakes.FakeServer(embed_answers) as server:
        result = root.run("similarity", FAKE_AWS_FORWARD=server.address.removeprefix("http://"))
    assert result.returncode == 0, result.stdout + result.stderr
    assert check_forward(root, EMBED_ADDRESS) == "i-0main"
    assert json.loads((root.results / "P-06-SB-T-03.json").read_text())["passed"] is True
    commands = [call for call in root.state()["calls"] if call["args"][:2] == ["ssm", "send-command"]]
    assert len(commands) == 1 and "i-0main" in commands[0]["args"]
    assert not list((root.vigil_root / "run").iterdir())


def test_similarity_container_stopped(tmp_path):
    """SB-T-03 a stopped vigil-embed: run.sh prints host-address.sh's message and exits 1 without a port forward."""
    root = Root(tmp_path)
    root.table([("docker", r"^inspect --format \{\{\.State\.Running\}\} vigil-embed$", [("false\n", 0)])])
    result = root.run("similarity")
    assert result.returncode == 1
    assert result.stdout.splitlines()[-1] == "Start vigil-embed first: deploy/deploy.sh."
    assert root.sessions() == []


def test_host_address(tmp_path):
    """SB-T-03 host-address.sh prints the vigil-net address of a running container, or its message and exit 1."""
    root = Root(tmp_path)
    script = root.root / "spikes" / "providers" / "host-address.sh"
    root.table(
        [
            ("docker", r"^inspect --format \{\{\.State\.Running\}\} vigil-embed$", [("true\n", 0), ("false\n", 0)]),
            (
                "docker",
                (
                    r"^inspect --format \{\{with index \.NetworkSettings\.Networks "
                    r'"vigil-net"\}\}\{\{\.IPAddress\}\}\{\{end\}\} vigil-embed$'
                ),
                [(EMBED_ADDRESS + "\n", 0)],
            ),
        ]
    )
    environment = root.environment()
    first = subprocess.run(["bash", str(script), "vigil-embed"], env=environment, capture_output=True, text=True)
    assert first.returncode == 0
    assert first.stdout == EMBED_ADDRESS + "\n"
    second = subprocess.run(["bash", str(script), "vigil-embed"], env=environment, capture_output=True, text=True)
    assert second.returncode == 1
    assert second.stdout == "Start vigil-embed first: deploy/deploy.sh.\n"
