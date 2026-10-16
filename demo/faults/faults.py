"""demo-faults: injects and clears the lab faults SL-F-1 to SL-F-3 (AR-U-07, AR-X-03; P-04 spec Section 6.3).

HTTP interface (complete list): POST /faults/{id}/inject and POST /faults/clear with the body {"faults": [ids]},
each only with "Authorization: Bearer {faults-token}". Fault commands run inside the lab containers through the
Docker Engine API on the mounted socket, as "docker exec" does.
"""

import hmac
import http.client
import json
import os
import re
import socket
import struct
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

TOKEN_FILE = "/run/secrets/vigil/faults-token"
STATE_FILE = "/var/lib/demo-faults/state.json"
DOCKER_SOCKET = "/var/run/docker.sock"
SCRIPTS = Path(__file__).resolve().parent / "scripts"
DEADLINE_S = 60
ORDER = ("SL-F-1", "SL-F-2", "SL-F-3")
INJECT_PATH = re.compile(r"/faults/([^/]+)/inject")


class CommandFailed(Exception):
    """A fault command that ended with an exit code other than 0, or did not end within its deadline."""

    def __init__(self, code, output):
        super().__init__(code, output)
        self.code = code
        self.output = output

    def describe(self):
        if self.code is None:
            return f"no exit after {DEADLINE_S} s): {self.output}"
        return f"exit {self.code}): {self.output[-500:]}"


def fault_commands(scripts=SCRIPTS):
    """{fault: (container, inject command, clear command)} (P-04 spec Section 6.3 table)."""
    inject = (scripts / "sl-f-1-inject.sh").read_text()
    clear = (scripts / "sl-f-1-clear.sh").read_text()
    return {
        "SL-F-1": ("lab-db-01", ["bash", "-c", inject], ["bash", "-c", clear]),
        "SL-F-2": ("lab-db-01", ["labctl", "db", "read-only", "on"], ["labctl", "db", "read-only", "off"]),
        "SL-F-3": ("lab-rpt-01", ["labctl", "report", "fail"], ["labctl", "report", "succeed"]),
    }


class _UnixConnection(http.client.HTTPConnection):
    def __init__(self, path, timeout):
        super().__init__("localhost", timeout=timeout)
        self.socket_path = path

    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.socket_path)


class DockerExec:
    """Runs a command in a container through the Docker Engine API, as "docker exec" does."""

    def __init__(self, socket_path=DOCKER_SOCKET, deadline=DEADLINE_S):
        self.socket_path = socket_path
        self.deadline = deadline
        connection = _UnixConnection(socket_path, 10)
        try:
            connection.request("GET", "/_ping")
            response = connection.getresponse()
            response.read()
            self.prefix = f"/v{response.getheader('Api-Version')}"
        finally:
            connection.close()

    def _json(self, method, path, body, timeout):
        connection = _UnixConnection(self.socket_path, timeout)
        try:
            data = None if body is None else json.dumps(body)
            connection.request(method, self.prefix + path, body=data, headers={"Content-Type": "application/json"})
            response = connection.getresponse()
            raw = response.read()
            if response.status >= 300:
                raise CommandFailed(-1, f"Docker API {method} {path}: HTTP {response.status} {raw.decode()[-300:]}")
            return json.loads(raw) if raw else {}
        finally:
            connection.close()

    def __call__(self, container, command):
        """Returns the command's output (standard output and error) when it exits 0; raises CommandFailed otherwise."""
        end = time.monotonic() + self.deadline
        created = self._json(
            "POST",
            f"/containers/{container}/exec",
            {"User": "root", "AttachStdout": True, "AttachStderr": True, "Tty": False, "Cmd": command},
            10,
        )
        exec_id = created["Id"]
        output = bytearray()
        connection = _UnixConnection(self.socket_path, self.deadline)
        try:
            connection.request(
                "POST",
                f"{self.prefix}/exec/{exec_id}/start",
                body=json.dumps({"Detach": False, "Tty": False}),
                headers={"Content-Type": "application/json"},
            )
            sock = connection.sock
            response = connection.getresponse()
            stream = bytearray()
            while True:
                remaining = end - time.monotonic()
                if remaining <= 0:
                    raise TimeoutError
                sock.settimeout(remaining)
                chunk = response.read1(65536)
                if not chunk:
                    break
                stream.extend(chunk)
                output.extend(_demultiplex(stream))
        except (TimeoutError, socket.timeout):
            raise CommandFailed(None, output.decode(errors="replace")) from None
        finally:
            connection.close()
        while True:
            state = self._json("GET", f"/exec/{exec_id}/json", None, 10)
            if not state.get("Running"):
                break
            if time.monotonic() > end:
                raise CommandFailed(None, output.decode(errors="replace"))
            time.sleep(0.1)
        text = output.decode(errors="replace")
        if state.get("ExitCode") != 0:
            raise CommandFailed(state.get("ExitCode"), text)
        return text


def _demultiplex(stream):
    """Takes the complete frames of Docker's multiplexed stream out of the buffer and returns their payload."""
    payload = bytearray()
    while len(stream) >= 8:
        size = struct.unpack(">I", bytes(stream[4:8]))[0]
        if len(stream) < 8 + size:
            break
        payload.extend(stream[8 : 8 + size])
        del stream[: 8 + size]
    return payload


class Faults:
    """The fault state and the inject and clear operations; one operation at a time."""

    def __init__(self, run, state_file=STATE_FILE, commands=None, log=None):
        self.run = run
        self.state_file = Path(state_file)
        self.commands = commands or fault_commands()
        self.log = log or RequestLog()
        self.lock = threading.Lock()
        self.active = set()
        if self.state_file.exists():
            self.active = set(json.loads(self.state_file.read_text())["active"])

    def _save(self):
        folder = self.state_file.parent
        with tempfile.NamedTemporaryFile("w", dir=folder, delete=False, prefix=".state-") as file:
            json.dump({"active": sorted(self.active)}, file)
        os.replace(file.name, self.state_file)

    def _answer(self):
        return 200, {"active": sorted(self.active)}

    def _execute(self, fault, which):
        container, inject, clear = self.commands[fault]
        try:
            output = self.run(container, inject if which == "inject" else clear)
        except CommandFailed as failure:
            self.log(failure.output.rstrip("\n"))
            return f"{fault} {which} failed ({failure.describe()}"
        self.log(output.rstrip("\n"))
        return None

    def inject(self, fault):
        with self.lock:
            if fault not in self.commands:
                return 500, {"error": f"unknown fault {fault}"}
            if fault in self.active:
                return self._answer()
            error = self._execute(fault, "inject")
            if error:
                return 500, {"error": error}
            self.active.add(fault)
            self._save()
            return self._answer()

    def clear(self, body):
        with self.lock:
            faults = body.get("faults") if isinstance(body, dict) else None
            if not isinstance(faults, list) or not all(isinstance(fault, str) for fault in faults):
                return 500, {"error": "invalid body"}
            for fault in faults:
                if fault not in self.commands:
                    return 500, {"error": f"unknown fault {fault}"}
            for fault in ORDER:
                if fault not in faults:
                    continue
                error = self._execute(fault, "clear")
                if error:
                    return 500, {"error": error}
                self.active.discard(fault)
                self._save()
            return self._answer()


def make_handler(faults, token):
    expected = f"Bearer {token}".encode()

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format, *args):
            pass

        def _send(self, status, body):
            raw = json.dumps(body).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def _handle(self):
            started = time.monotonic()
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            status, body = self._route(raw)
            self._send(status, body)
            took = round((time.monotonic() - started) * 1000)
            faults.log(f"{self.command} {self.path} {status} {took} ms", first=True)

        def _route(self, raw):
            presented = (self.headers.get("Authorization") or "").encode()
            if not hmac.compare_digest(presented, expected):
                return 401, {"error": "unauthorized"}
            if self.command != "POST":
                return 404, {"error": "not found"}
            match = INJECT_PATH.fullmatch(self.path)
            if match:
                return faults.inject(match.group(1))
            if self.path == "/faults/clear":
                try:
                    body = json.loads(raw or b"null")
                except ValueError:
                    body = None
                return faults.clear(body)
            return 404, {"error": "not found"}

        do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = do_HEAD = _handle

    return Handler


class RequestLog:
    """One line per request (method, path, status, duration), with the fault commands' output on the following lines."""

    def __init__(self, write=None):
        self.write = write or (lambda line: print(line, flush=True))
        self.local = threading.local()

    def __call__(self, line, first=False):
        if first:
            self.write(line)
            for pending in getattr(self.local, "pending", []):
                self.write(pending)
            self.local.pending = []
        elif line:
            self.local.pending = [*getattr(self.local, "pending", []), line]


def main():
    listen = os.environ.get("FAULTS_LISTEN", "172.31.0.1:8091")
    try:
        token = Path(TOKEN_FILE).read_text()
    except FileNotFoundError:
        print("missing secret faults-token", flush=True)
        return 1
    host, port = listen.rsplit(":", 1)
    faults = Faults(DockerExec())
    server = ThreadingHTTPServer((host, int(port)), make_handler(faults, token))
    print(f"demo-faults listening on {listen}", flush=True)
    server.serve_forever()
    return 0


if __name__ == "__main__":
    sys.exit(main())
