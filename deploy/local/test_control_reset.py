"""Unit tests of deploy/local/control_reset.py (P-31 spec Section 7) with a local fake of demo-control."""

import json
import sys
import threading
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))

import control_reset  # noqa: E402

PASSWORD = "control-" + "p" * 12


class FakeControl:
    """demo-control's interface: a session, the Reset button and the state with its last result."""

    def __init__(self, outcome="completed", status="Reset completed in 41 s"):
        self.outcome = outcome
        self.status = status
        self.requests = []
        self.pressed = None
        self.reads = 0
        fake = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def _send(self, status, body=None, headers=()):
                raw = b"" if body is None else json.dumps(body).encode()
                self.send_response(status)
                for name, value in headers:
                    self.send_header(name, value)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def do_POST(self):
                raw = self.rfile.read(int(self.headers.get("Content-Length") or 0))
                fake.requests.append(("POST", self.path, self.headers.get("Content-Type"), raw, self.headers))
                if self.path == "/api/session":
                    body = json.loads(raw)
                    if body == {"username": "operator", "password": PASSWORD}:
                        cookie = "control_session=token123; Path=/; HttpOnly; SameSite=Strict"
                        self._send(204, headers=[("Set-Cookie", cookie)])
                    else:
                        self._send(401, {"message": "Username or password is incorrect."})
                elif self.path == "/api/buttons/reset" and self.headers.get("Cookie") == "control_session=token123":
                    fake.pressed = datetime.now(UTC)
                    self._send(202, {})
                else:
                    self._send(401, {})

            def do_GET(self):
                fake.requests.append(("GET", self.path, None, b"", self.headers))
                if self.headers.get("Cookie") != "control_session=token123":
                    self._send(401, {"product": "Techfinity"})
                    return
                fake.reads += 1
                last = {
                    "operation": "Reset",
                    "outcome": "completed",
                    "finished_at": "2020-01-01T00:00:00.000Z",
                }
                status = "Idle"
                if fake.pressed is not None and fake.reads >= 3:
                    finished = fake.pressed + timedelta(seconds=1)
                    last = {
                        "operation": "Reset",
                        "outcome": fake.outcome,
                        "finished_at": finished.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
                    }
                    status = fake.status
                self._send(200, {"status": status, "last_result": last})

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def password_file(tmp_path):
    path = tmp_path / "control-password"
    path.write_text(PASSWORD)
    return path


def run(fake, password_file, capsys, monkeypatch):
    monkeypatch.setattr(control_reset, "EVERY_SECONDS", 0.05)
    code = control_reset.main(["--url", fake.url, "--password-file", str(password_file)])
    return code, capsys.readouterr().out


def test_signs_in_presses_reset_and_waits(password_file, capsys, monkeypatch):
    """SL-A3-03 control_reset.py signs in, presses Reset and waits for its result."""
    fake = FakeControl()
    try:
        code, out = run(fake, password_file, capsys, monkeypatch)
    finally:
        fake.close()
    assert code == 0
    assert out == "Reset completed in 41 s\n"
    posts = [r for r in fake.requests if r[0] == "POST"]
    assert [r[1] for r in posts] == ["/api/session", "/api/buttons/reset"]
    assert all(r[2] == "application/json" for r in posts)
    assert json.loads(posts[1][3]) == {}
    assert PASSWORD.encode() in posts[0][3]
    assert all(PASSWORD not in str(dict(r[4])) for r in fake.requests)
    assert fake.reads >= 3


def test_failed_reset_exits_1(password_file, capsys, monkeypatch):
    """SL-A3-03 a failed reset exits 1 with its status line."""
    line = "Reset failed after 58 s: The seed import was still running after 58 s."
    fake = FakeControl(outcome="failed", status=line)
    try:
        code, out = run(fake, password_file, capsys, monkeypatch)
    finally:
        fake.close()
    assert (code, out) == (1, line + "\n")


def test_wrong_password_and_usage(tmp_path, capsys, monkeypatch):
    """SL-A3-03 a refused sign-in exits 1; wrong arguments print the usage line and exit 2."""
    wrong = tmp_path / "wrong"
    wrong.write_text("other")
    fake = FakeControl()
    try:
        code, out = run(fake, wrong, capsys, monkeypatch)
    finally:
        fake.close()
    assert (code, out) == (1, "Sign-in to the control panel answered HTTP 401.\n")
    assert control_reset.main(["--url"]) == 2
    assert capsys.readouterr().out == control_reset.USAGE + "\n"
