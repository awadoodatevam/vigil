"""Test support of the provider spike tests (P-06 spec Section 7; P-07 spec Section 7).

HTTP fakes are http.server servers on 127.0.0.1 started by the tests; they keep every request with its header names
in lower case (names are compared without regard to case). prepare() points the spikes at temporary result and
recording folders, puts the fake AWS CLI of deploy/tests/bin on PATH with a seeded state file, sets VIGIL_TEST_FAST=1
and replaces common.monotonic with a clock that only the fakes advance. Owner answers come from Answers (standard
input whose lines can advance the clock); SmtpFake is the SMTP server of the email spike's tests.
"""

import base64
import io
import json
import os
import secrets
import socket
import socketserver
import ssl
import subprocess
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

    def result(self, name, package="P-06"):
        return json.loads((self.results / f"{package}-{name}.json").read_text())

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


# ---------------------------------------------------------------- P-07: the owner's answers and the SMTP fake
class Answers(io.StringIO):
    """Standard input of the spikes: each answer is a text or (text, seconds), the text a string or a function that
    gives it when it is read; readline advances the test clock by the seconds before it answers. After the last
    answer, standard input ends (an end of input)."""

    def __init__(self, clock, answers):
        super().__init__("")
        self.clock = clock
        self.answers = [answer if isinstance(answer, tuple) else (answer, 0) for answer in answers]
        self.read = 0

    def readline(self, size=-1):
        if not self.answers:
            return ""
        text, seconds = self.answers.pop(0)
        self.clock.advance(seconds)
        if callable(text):
            text = text()
        self.read += 1
        return text + "\n"


def answer_with(monkeypatch, clock, answers):
    """Sets sys.stdin to Answers and returns it."""
    import sys

    stdin = Answers(clock, answers)
    monkeypatch.setattr(sys, "stdin", stdin)
    return stdin


def certificate(folder):
    """A self-signed certificate for IP:127.0.0.1 made with openssl req -x509: (certificate file, key file)."""
    folder.mkdir(parents=True, exist_ok=True)
    cert, key = folder / "smtp-cert.pem", folder / "smtp-key.pem"
    subprocess.run(
        [
            "openssl",
            "req",
            "-x509",
            "-newkey",
            "rsa:2048",
            "-nodes",
            "-days",
            "1",
            "-subj",
            "/CN=127.0.0.1",
            "-addext",
            "subjectAltName=IP:127.0.0.1",
            "-keyout",
            str(key),
            "-out",
            str(cert),
        ],
        check=True,
        capture_output=True,
    )
    return cert, key


def smtp_secret_value():
    """A generated smtp secret (BP-R-14)."""
    return {"username": "AKIAFAKE" + secrets.token_hex(6).upper(), "password": "pw-" + secrets.token_urlsafe(24)}


class SmtpFake:
    """An SMTP server on 127.0.0.1 in a thread: EHLO, STARTTLS, AUTH PLAIN and LOGIN, MAIL, RCPT, DATA, RSET, QUIT.

    It keeps every command (commands: (command, over TLS)) and every accepted message (messages: {sender,
    recipients, data}). Refusals: refuse_login (the numbers of the connections, from 1, whose logins are answered 535);
    refuse_rcpt (the numbers of the RCPT commands, from 1, answered 550); refuse_data (the numbers of the DATA commands
    answered 451 instead of 354); reject_after_body (the numbers of the DATA commands whose body is answered 554);
    close_after (after that many accepted messages, the next MAIL ends the connection).
    """

    def __init__(self, cert, key, username, password, auth="PLAIN LOGIN"):
        self.username = username
        self.password = password
        self.auth = auth
        self.commands = []
        self.messages = []
        self.refuse_login = set()
        self.refuse_rcpt = set()
        self.refuse_data = set()
        self.reject_after_body = set()
        self.close_after = None
        self.connections = 0
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain(cert, key)
        fake = self

        class Handler(socketserver.StreamRequestHandler):
            def setup(self):
                super().setup()
                self.tls = False
                fake.connections += 1

            def send(self, line):
                self.wfile.write(line.encode("utf-8") + b"\r\n")
                self.wfile.flush()

            def read(self):
                line = self.rfile.readline()
                if not line:
                    raise ConnectionError("closed")
                return line.decode("utf-8").rstrip("\r\n")

            def handle(self):
                try:
                    fake.session(self, context)
                except (ConnectionError, ssl.SSLError, OSError):
                    pass

        self.server = socketserver.ThreadingTCPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.rcpts = 0
        self.datas = 0

    @property
    def address(self):
        return f"127.0.0.1:{self.server.server_address[1]}"

    def credentials_valid(self, user, password):
        return self.connections not in self.refuse_login and user == self.username and password == self.password

    def session(self, handler, context):
        handler.send("220 fake-smtp ESMTP")
        sender = None
        recipients = []
        while True:
            line = handler.read()
            verb = line.split(" ", 1)[0].upper()
            self.commands.append((verb, handler.tls))
            if verb == "EHLO":
                features = ["250-fake-smtp", "250-8BITMIME"]
                features.append("250-STARTTLS" if not handler.tls else f"250-AUTH {self.auth}")
                features.append("250 SIZE 10485760")
                for feature in features:
                    handler.send(feature)
            elif verb == "STARTTLS":
                handler.send("220 Ready to start TLS")
                handler.connection = context.wrap_socket(handler.connection, server_side=True)
                handler.rfile = handler.connection.makefile("rb")
                handler.wfile = handler.connection.makefile("wb")
                handler.tls = True
            elif verb == "AUTH":
                self.authenticate(handler, line)
            elif verb == "MAIL":
                if self.close_after is not None and len(self.messages) >= self.close_after:
                    handler.connection.shutdown(socket.SHUT_RDWR)
                    return
                sender = line.split(":", 1)[1].strip().strip("<>").split(">")[0]
                recipients = []
                handler.send("250 Ok")
            elif verb == "RCPT":
                self.rcpts += 1
                if self.rcpts in self.refuse_rcpt:
                    handler.send("550 Mailbox unavailable")
                else:
                    recipients.append(line.split(":", 1)[1].strip().strip("<>"))
                    handler.send("250 Ok")
            elif verb == "DATA":
                self.datas += 1
                if self.datas in self.refuse_data:
                    handler.send("451 Try again later")
                    continue
                handler.send("354 End data with <CR><LF>.<CR><LF>")
                body = []
                while True:
                    raw = handler.rfile.readline()
                    if raw in (b".\r\n", b""):
                        break
                    body.append(raw[1:] if raw.startswith(b"..") else raw)
                if self.datas in self.reject_after_body:
                    handler.send("554 Message rejected: Email address is not verified.")
                else:
                    self.messages.append({"sender": sender, "recipients": recipients, "data": b"".join(body)})
                    handler.send(f"250 Ok 0100{len(self.messages):012d}")
            elif verb == "RSET":
                sender, recipients = None, []
                handler.send("250 Ok")
            elif verb == "QUIT":
                handler.send("221 Bye")
                return
            else:
                handler.send("502 Command not implemented")

    def authenticate(self, handler, line):
        parts = line.split(" ")
        mechanism = parts[1].upper() if len(parts) > 1 else ""
        if not handler.tls:
            handler.send("530 Must issue a STARTTLS command first")
            return
        if mechanism == "PLAIN":
            if len(parts) > 2:
                response = parts[2]
            else:
                handler.send("334 ")
                response = handler.read()
            _, user, password = base64.b64decode(response).decode("utf-8").split("\0")
        elif mechanism == "LOGIN":
            if len(parts) > 2:
                user = base64.b64decode(parts[2]).decode("utf-8")
            else:
                handler.send("334 VXNlcm5hbWU6")
                user = base64.b64decode(handler.read()).decode("utf-8")
            handler.send("334 UGFzc3dvcmQ6")
            password = base64.b64decode(handler.read()).decode("utf-8")
        else:
            handler.send("504 Unrecognized authentication type")
            return
        if self.credentials_valid(user, password):
            handler.send("235 Authentication successful.")
        else:
            handler.send("535 Authentication Credentials Invalid")

    def close(self):
        self.server.shutdown()
        self.server.server_close()

    def __enter__(self):
        return self

    def __exit__(self, *exception):
        self.close()
