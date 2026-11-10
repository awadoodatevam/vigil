"""Test support of the provider spike tests (P-06 spec Section 7).

HTTP fakes are http.server servers on 127.0.0.1 started by the tests; they keep every request with its header names
in lower case (names are compared without regard to case). prepare() points the spikes at temporary result and
recording folders, puts the fake AWS CLI of deploy/tests/bin on PATH with a seeded state file, sets VIGIL_TEST_FAST=1
and replaces common.monotonic with a clock that only the fakes advance.
"""

import json
import os
import secrets
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import common

REPOSITORY = Path(__file__).resolve().parents[2]
AWS_FAKES = REPOSITORY / "deploy" / "tests" / "bin"


class Clock:
    """The spikes' monotonic clock in tests: it moves only when a fake advances it."""

    def __init__(self):
        self.now = 1000.0
        self.lock = threading.Lock()

    def __call__(self):
        with self.lock:
            return self.now

    def advance(self, seconds):
        with self.lock:
            self.now += seconds


class Setup:
    def __init__(self, tmp_path, monkeypatch):
        tmp_path.mkdir(parents=True, exist_ok=True)
        self.results = tmp_path / "results"
        self.recordings = tmp_path / "recordings"
        self.state_path = tmp_path / "aws-state.json"
        self.clock = Clock()
        for key in list(os.environ):
            if key.startswith(("AWS_", "FAKE_")):
                monkeypatch.delenv(key, raising=False)
        monkeypatch.delenv("VIGIL_SPIKE_PACKAGE", raising=False)
        monkeypatch.setenv("VIGIL_SPIKE_RESULTS", str(self.results))
        monkeypatch.setenv("VIGIL_SPIKE_RECORDINGS", str(self.recordings))
        monkeypatch.setenv("VIGIL_TEST_FAST", "1")
        monkeypatch.setenv("FAKE_AWS_STATE", str(self.state_path))
        monkeypatch.setenv("PATH", str(AWS_FAKES) + os.pathsep + os.environ.get("PATH", ""))
        monkeypatch.setattr(common, "monotonic", self.clock)
        self.parameters = {}
        self.save()

    def secret(self, name, value):
        self.parameters[f"/vigil/demo/{name}"] = {"Value": value, "Type": "SecureString"}
        self.save()

    def save(self):
        self.state_path.write_text(json.dumps({"account": "123456789012", "parameters": self.parameters}))

    def result(self, name):
        return json.loads((self.results / f"P-06-{name}.json").read_text())

    def recording_files(self, folder):
        path = self.recordings / folder
        return sorted(p.name for p in path.glob("*.json")) if path.exists() else []

    def recording(self, folder, name):
        return json.loads((self.recordings / folder / name).read_text())

    def changed_files(self):
        """Every file under the result and recording folders."""
        return sorted(
            str(p.relative_to(base))
            for base in (self.results, self.recordings)
            if base.exists()
            for p in base.rglob("*")
        )


def prepare(tmp_path, monkeypatch):
    return Setup(tmp_path, monkeypatch)


def jira_secret_value(expires_on):
    """A generated jira secret (BP-A-29)."""
    return {
        "email": f"Noc.Spike-{secrets.token_hex(4)}@example.test",
        "api_token": "tok-" + secrets.token_urlsafe(24),
        "expires_on": expires_on,
    }


def account_id():
    return f"712020:{uuid.uuid4()}"


class Request:
    def __init__(self, method, path, headers, body):
        self.method = method
        self.path = path
        self.headers = headers
        self.body = body

    def json(self):
        return json.loads(self.body)


class FakeServer:
    """An HTTP server whose answers come from respond(request) -> (status, body object or text, content type)."""

    def __init__(self, respond, clock=None, seconds=None):
        self.respond = respond
        self.requests = []
        self.clock = clock
        self.seconds = seconds or (lambda request: 0.5)
        fake = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *arguments):
                pass

            def handle_any(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length).decode("utf-8") if length else ""
                headers = {name.lower(): value for name, value in self.headers.items()}
                request = Request(self.command, self.path, headers, body)
                fake.requests.append(request)
                if fake.clock is not None:
                    fake.clock.advance(fake.seconds(request))
                status, content, content_type = fake.respond(request)
                if isinstance(content, (dict, list)):
                    content = json.dumps(content)
                data = (content or "").encode("utf-8")
                self.send_response(status)
                if content_type:
                    self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = handle_any
            do_POST = handle_any

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    @property
    def address(self):
        return f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def __enter__(self):
        return self

    def __exit__(self, *exception):
        self.close()


def json_answer(status, content):
    return status, content, "application/json"
