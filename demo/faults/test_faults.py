"""Unit tests of demo-faults (P-04 spec Section 7): the HTTP interface with a fake Docker exec."""

import ast
import json
import subprocess
import sys
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from pathlib import Path

import faults
import pytest

HERE = Path(__file__).resolve().parent
TOKEN = "test-token-" + "x" * 20


class FakeExec:
    """Records the commands it runs; a command listed in fail exits with its code, one in hang never exits."""

    def __init__(self):
        self.ran = []
        self.fail = {}
        self.hang = set()

    def __call__(self, container, command):
        self.ran.append((container, command))
        key = " ".join(command[:3])
        if key in self.hang:
            raise faults.CommandFailed(None, "started")
        if key in self.fail:
            raise faults.CommandFailed(self.fail[key], "x" * 600 + "boom")
        return "ok\n"


class Server:
    def __init__(self, state_file, run):
        self.lines = []
        self.faults = faults.Faults(run, state_file=state_file, log=faults.RequestLog(self.lines.append))
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), faults.make_handler(self.faults, TOKEN))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def request(self, method, path, body=None, token=TOKEN):
        data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
        request = urllib.request.Request(self.url + path, data=data, method=method)
        if token is not None:
            request.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(request, timeout=5) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def fake():
    return FakeExec()


@pytest.fixture
def server(tmp_path, fake):
    server = Server(tmp_path / "state.json", fake)
    yield server
    server.close()


def labctl(fake):
    return [command for _container, command in fake.ran]


def test_interface(tmp_path, server, fake):
    """AR-X-03 token, operations, idempotent inject and clear, order, failures, unknown IDs and the state file."""
    assert server.request("POST", "/faults/clear", {"faults": []}, token=None) == (401, {"error": "unauthorized"})
    assert server.request("POST", "/faults/clear", {"faults": []}, token="wrong") == (401, {"error": "unauthorized"})
    assert server.request("GET", "/faults/clear", token="wrong")[0] == 401
    assert server.request("GET", "/faults") == (404, {"error": "not found"})
    assert server.request("POST", "/faults/SL-F-2") == (404, {"error": "not found"})
    assert server.request("POST", "/other", {}) == (404, {"error": "not found"})
    assert fake.ran == []

    assert server.request("POST", "/faults/SL-F-2/inject") == (200, {"active": ["SL-F-2"]})
    assert server.request("POST", "/faults/SL-F-2/inject") == (200, {"active": ["SL-F-2"]})
    assert fake.ran == [("lab-db-01", ["labctl", "db", "read-only", "on"])]
    assert json.loads((tmp_path / "state.json").read_text()) == {"active": ["SL-F-2"]}

    assert server.request("POST", "/faults/SL-F-4/inject") == (500, {"error": "unknown fault SL-F-4"})
    assert server.request("POST", "/faults/SL-F-9/inject") == (500, {"error": "unknown fault SL-F-9"})

    fake.ran.clear()
    assert server.request("POST", "/faults/SL-F-3/inject") == (200, {"active": ["SL-F-2", "SL-F-3"]})
    fake.ran.clear()
    body = {"faults": ["SL-F-3", "SL-F-1", "SL-F-2"]}
    assert server.request("POST", "/faults/clear", body) == (200, {"active": []})
    containers = [container for container, _command in fake.ran]
    assert containers == ["lab-db-01", "lab-db-01", "lab-rpt-01"]
    commands = labctl(fake)
    assert commands[0][:2] == ["bash", "-c"] and "txlog_*.arc" in commands[0][2]
    assert commands[1:] == [["labctl", "db", "read-only", "off"], ["labctl", "report", "succeed"]]
    assert json.loads((tmp_path / "state.json").read_text()) == {"active": []}

    fake.ran.clear()
    assert server.request("POST", "/faults/clear", {"faults": ["SL-F-2", "SL-F-4"]}) == (
        500,
        {"error": "unknown fault SL-F-4"},
    )
    assert fake.ran == []
    for body in ({"faults": "SL-F-1"}, {"faults": [1]}, [], {"other": []}, b"not json"):
        assert server.request("POST", "/faults/clear", body) == (500, {"error": "invalid body"})
    assert server.request("POST", "/faults/clear", {"faults": []}) == (200, {"active": []})
    assert fake.ran == []
    assert any(line.startswith("POST /faults/clear 200 ") and line.endswith(" ms") for line in server.lines)
    assert not any(TOKEN in line for line in server.lines)


def test_failures(tmp_path, server, fake):
    """AR-X-03 a failing command gives 500 with its exit code; a command without exit gives the 60 s error."""
    fake.fail["labctl report fail"] = 3
    status, body = server.request("POST", "/faults/SL-F-3/inject")
    assert status == 500
    assert body["error"].startswith("SL-F-3 inject failed (exit 3): ")
    assert body["error"].endswith("boom") and len(body["error"]) == len("SL-F-3 inject failed (exit 3): ") + 500
    assert not (tmp_path / "state.json").exists()

    assert server.request("POST", "/faults/SL-F-2/inject")[0] == 200
    assert server.request("POST", "/faults/SL-F-1/inject")[0] == 200
    fake.fail["labctl db read-only"] = 1
    fake.ran.clear()
    status, body = server.request("POST", "/faults/clear", {"faults": ["SL-F-1", "SL-F-2", "SL-F-3"]})
    assert status == 500 and body["error"].startswith("SL-F-2 clear failed (exit 1): ")
    assert len(fake.ran) == 2
    assert json.loads((tmp_path / "state.json").read_text()) == {"active": ["SL-F-2"]}

    fake.hang.add("labctl report fail")
    status, body = server.request("POST", "/faults/SL-F-3/inject")
    assert (status, body) == (500, {"error": "SL-F-3 inject failed (no exit after 60 s): started"})


def test_restart(tmp_path, fake):
    """AR-X-03 the state file survives a restart of the server object."""
    first = Server(tmp_path / "state.json", fake)
    try:
        assert first.request("POST", "/faults/SL-F-1/inject") == (200, {"active": ["SL-F-1"]})
    finally:
        first.close()
    second = Server(tmp_path / "state.json", fake)
    try:
        fake.ran.clear()
        assert second.request("POST", "/faults/SL-F-1/inject") == (200, {"active": ["SL-F-1"]})
        assert fake.ran == []
    finally:
        second.close()


def test_docker_exec_stream():
    """AR-X-03 Docker's multiplexed output frames are joined in order."""
    frames = bytearray(b"\x01\x00\x00\x00\x00\x00\x00\x03out" + b"\x02\x00\x00\x00\x00\x00\x00\x04err\n" + b"\x01\x00")
    assert faults._demultiplex(frames) == b"outerr\n"
    assert frames == bytearray(b"\x01\x00")


def test_standard_library_and_scripts():
    """AR-T-21 faults.py uses only the standard library; the fault scripts are bash with set -euo pipefail."""
    tree = ast.parse((HERE / "faults.py").read_text())
    modules = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            modules.update(alias.name.split(".")[0] for alias in node.names)
        elif isinstance(node, ast.ImportFrom):
            modules.add(node.module.split(".")[0])
    assert modules <= sys.stdlib_module_names, modules - sys.stdlib_module_names
    for name in ("sl-f-1-inject.sh", "sl-f-1-clear.sh"):
        lines = (HERE / "scripts" / name).read_text().splitlines()
        assert lines[0] == "#!/usr/bin/env bash"
        code = [line for line in lines[1:] if line.strip() and not line.lstrip().startswith("#")]
        assert code[0] == "set -euo pipefail"
        subprocess.run(["bash", "-n", str(HERE / "scripts" / name)], check=True)
