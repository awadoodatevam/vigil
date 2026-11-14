"""Unit tests of demo-faults (P-04 spec Section 7): the HTTP interface with a fake Docker exec."""

import ast
import json
import subprocess
import sys
import threading
import time
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

    assert server.request("POST", "/faults/SL-F-5/inject") == (500, {"error": "unknown fault SL-F-5"})
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
    assert server.request("POST", "/faults/clear", {"faults": ["SL-F-2", "SL-F-5"]}) == (
        500,
        {"error": "unknown fault SL-F-5"},
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


class FakeHost:
    """The host's iptables (the programs whose DOCKER-USER chain exists) and conntrack, as a command runner."""

    def __init__(self, programs=("iptables-nft",), conntrack=(0, "1 flow entries have been deleted.\n")):
        self.programs = set(programs)
        self.rules = []
        self.ran = []
        self.conntrack = conntrack

    def __call__(self, command):
        self.ran.append(command)
        program = command[0]
        if program == "conntrack":
            return self.conntrack
        if program not in self.programs:
            return 1, f"{program}: No chain/target/match by that name.\n"
        operation, rule = command[1], command[3:]
        if operation == "-S":
            return 0, "-N DOCKER-USER\n"
        if operation == "-C":
            return (0, "") if (program, rule) in self.rules else (1, "Bad rule\n")
        if operation == "-I":
            self.rules.insert(0, (program, rule))
            return 0, ""
        if operation == "-D":
            self.rules.remove((program, rule))
            return 0, ""
        return 2, "unexpected"


class FakeResolver:
    def __init__(self, names):
        self.names = names

    def __call__(self, name):
        return list(self.names.get(name, []))


ADDRESSES = {"api.anthropic.com": ["160.79.104.10"], "api.openai.com": ["162.159.140.245", "172.66.0.243"]}


def rule(source, address):
    return [
        "-s",
        f"{source}/32",
        "-d",
        f"{address}/32",
        "-p",
        "tcp",
        "-m",
        "tcp",
        "--dport",
        "443",
        "-j",
        "REJECT",
        "--reject-with",
        "tcp-reset",
    ]


def cloud_faults(tmp_path, host, names=ADDRESSES, every=3600):
    lines = []
    log = faults.RequestLog(lines.append)
    cloud = faults.CloudCut(host=host, resolver=FakeResolver(names), log=log.write)
    made = faults.Faults(FakeExec(), state_file=tmp_path / "state.json", log=log, cloud=cloud, every=every)
    made.lines = lines
    return made


def stop(made):
    if made.stop_loop is not None:
        made.stop_loop.set()


def test_sl_f_4_rules(tmp_path):
    """AR-X-03 SL-F-4 adds one rule per source and address and closes their connections."""
    host = FakeHost()
    made = cloud_faults(tmp_path, host)
    try:
        assert made.inject("SL-F-4") == (200, {"active": ["SL-F-4"]})
        addresses = ["160.79.104.10", "162.159.140.245", "172.66.0.243"]
        expected = {("iptables-nft", tuple(rule(s, a))) for s in ("172.30.0.10", "172.31.0.10") for a in addresses}
        assert {(program, tuple(found)) for program, found in host.rules} == expected and len(host.rules) == 6
        conntrack = [c for c in host.ran if c[0] == "conntrack"]
        assert conntrack == [
            ["conntrack", "-D", "-p", "tcp", "-s", s, "-d", a, "--dport", "443"]
            for a in addresses
            for s in ("172.30.0.10", "172.31.0.10")
        ]
        inserted = [c for c in host.ran if c[1] == "-I"]
        assert len(inserted) == 6 and all(c[:3] == ["iptables-nft", "-I", "DOCKER-USER"] for c in inserted)
        state = json.loads((tmp_path / "state.json").read_text())
        assert state == {"active": ["SL-F-4"], "sl_f_4_addresses": addresses}
        host.ran.clear()
        assert made.inject("SL-F-4") == (200, {"active": ["SL-F-4"]})
        assert host.ran == []
    finally:
        stop(made)


def test_sl_f_4_conntrack_no_entry(tmp_path):
    """AR-X-03 conntrack's exit 1 with no entry deleted is success."""
    host = FakeHost(conntrack=(1, "conntrack v1.4.8 (conntrack-tools): 0 flow entries have been deleted.\n"))
    made = cloud_faults(tmp_path, host)
    try:
        assert made.inject("SL-F-4") == (200, {"active": ["SL-F-4"]})
    finally:
        stop(made)
    host = FakeHost(conntrack=(1, "conntrack: Operation failed: invalid parameters\n"))
    (tmp_path / "other").mkdir()
    made = cloud_faults(tmp_path / "other", host)
    status, body = made.inject("SL-F-4")
    assert status == 500 and body["error"].startswith("SL-F-4 inject failed (exit 1): conntrack: Operation failed")
    assert made.active == set() and made.stop_loop is None


def test_sl_f_4_iptables_program(tmp_path):
    """AR-X-03 the iptables of the DOCKER-USER chain, nft or legacy, and none found."""
    host = FakeHost(programs=("iptables-legacy",))
    made = cloud_faults(tmp_path, host)
    try:
        assert made.inject("SL-F-4")[0] == 200
        assert {program for program, _rule in host.rules} == {"iptables-legacy"}
        assert host.ran[:2] == [["iptables-nft", "-S", "DOCKER-USER"], ["iptables-legacy", "-S", "DOCKER-USER"]]
    finally:
        stop(made)
    (tmp_path / "none").mkdir()
    host = FakeHost(programs=())
    made = cloud_faults(tmp_path / "none", host)
    assert made.inject("SL-F-4") == (500, {"error": "SL-F-4 inject failed: the DOCKER-USER chain was not found"})
    assert [c for c in host.ran if c[1] != "-S"] == []
    host = FakeHost(programs=("iptables-nft", "iptables-legacy"))
    made = cloud_faults(tmp_path / "none", host)
    try:
        assert made.inject("SL-F-4")[0] == 200
        assert {program for program, _rule in host.rules} == {"iptables-nft"}
    finally:
        stop(made)


def test_sl_f_4_name_not_resolving(tmp_path):
    """AR-X-03 a name that does not resolve adds nothing until it does."""
    host = FakeHost()
    names = {"api.anthropic.com": ["160.79.104.10"]}
    made = cloud_faults(tmp_path, host, names=names)
    try:
        assert made.inject("SL-F-4")[0] == 200
        assert {r[1][3] for r in host.rules} == {"160.79.104.10/32"}
        assert "SL-F-4: api.openai.com does not resolve" in made.lines
        names["api.openai.com"] = ["162.159.140.245"]
        assert made.cloud.add_new() is True
        assert {r[1][3] for r in host.rules} == {"160.79.104.10/32", "162.159.140.245/32"}
    finally:
        stop(made)


def test_sl_f_4_resolves_again(tmp_path):
    """AR-X-03 SL-F-4 resolves again every 30 s and adds new addresses."""
    assert faults.RESOLVE_EVERY_S == 30
    host = FakeHost()
    names = {"api.anthropic.com": ["160.79.104.10"], "api.openai.com": []}
    made = cloud_faults(tmp_path, host, names=names, every=0.05)
    try:
        assert made.inject("SL-F-4")[0] == 200
        names["api.openai.com"] = ["162.159.140.245"]

        def saved():
            return json.loads((tmp_path / "state.json").read_text())["sl_f_4_addresses"]

        deadline = time.monotonic() + 5
        while saved() != ["160.79.104.10", "162.159.140.245"] and time.monotonic() < deadline:
            time.sleep(0.05)
        assert saved() == ["160.79.104.10", "162.159.140.245"]
        assert len(host.rules) == 4
        assert "SL-F-4: blocked 162.159.140.245" in made.lines
        host.conntrack = (3, "broken\n")
        names["api.openai.com"] = ["162.159.140.245", "172.66.0.243"]
        deadline = time.monotonic() + 5
        while not any(line.startswith("SL-F-4 re-resolve failed (exit 3)") for line in made.lines):
            assert time.monotonic() < deadline
            time.sleep(0.05)
        host.conntrack = (0, "")
        while "172.66.0.243" not in saved():
            assert time.monotonic() < deadline
            time.sleep(0.05)
    finally:
        stop(made)


def test_sl_f_4_resumes(tmp_path):
    """AR-X-03 SL-F-4 resumes after a restart."""
    host = FakeHost()
    made = cloud_faults(tmp_path, host)
    assert made.inject("SL-F-4")[0] == 200
    stop(made)
    host.rules.pop()
    host.ran.clear()
    again = cloud_faults(tmp_path, host)
    try:
        assert again.active == {"SL-F-4"} and again.stop_loop is not None
        assert len(host.rules) == 6
        assert again.cloud.addresses == ["160.79.104.10", "162.159.140.245", "172.66.0.243"]
        assert len([c for c in host.ran if c[1] == "-I"]) == 1
        assert again.clear({"faults": ["SL-F-4"]}) == (200, {"active": []})
        assert host.rules == []
    finally:
        stop(again)


def test_sl_f_4_clear(tmp_path):
    """AR-X-03 clear removes SL-F-4's rules; clearing it when not active does nothing."""
    host = FakeHost()
    made = cloud_faults(tmp_path, host)
    assert made.clear({"faults": ["SL-F-4"]}) == (200, {"active": []})
    assert host.ran == []
    assert made.inject("SL-F-4")[0] == 200
    loop = made.stop_loop
    host.rules.append(host.rules[0])
    assert made.clear({"faults": ["SL-F-1", "SL-F-2", "SL-F-3", "SL-F-4"]}) == (200, {"active": []})
    assert host.rules == [] and made.cloud.addresses == [] and loop.is_set()
    assert json.loads((tmp_path / "state.json").read_text()) == {"active": []}
    assert made.clear({"faults": ["SL-F-4"]}) == (200, {"active": []})


def test_no_call_to_vigil():
    """SL-B-10 demo-faults makes no call to Vigil (no URL in faults.py)."""
    text = (HERE / "faults.py").read_text()
    assert "http://" not in text and "https://" not in text
    assert "vigil.test" not in text and "8080" not in text and "/api/v1" not in text
