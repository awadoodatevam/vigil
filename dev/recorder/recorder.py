"""The webhook recorder: stands in for vigil-app's webhook operation AR-I-90 while Vigil is not running (P-04 spec 6.4).

It answers the Zabbix and Alertmanager webhook calls of the lab as vigil-app would (bearer token per source) and keeps
every call, with the Authorization value redacted, in memory and as one JSON file per call.
"""

import hmac
import json
import os
import re
import sys
import tempfile
import threading
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

SECRETS = Path("/run/secrets/vigil")
SOURCES = {"zabbix-lab": "webhook-zabbix", "alertmanager-lab": "webhook-alertmanager"}
WEBHOOK_PATH = re.compile(r"/api/v1/webhooks/(zabbix-lab|alertmanager-lab)")
MAX_BODY = 5 * 1024 * 1024
FILE_NAME = re.compile(r"(\d{6,})-[a-z-]+\.json")


def utc_now():
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class Recordings:
    """The recorded calls, numbered from 1 or from one more than the highest number already in the output folder."""

    def __init__(self, folder, clock=utc_now):
        self.folder = Path(folder)
        self.clock = clock
        self.lock = threading.Lock()
        self.calls = []
        numbers = [int(m.group(1)) for p in self.folder.iterdir() if (m := FILE_NAME.fullmatch(p.name))]
        self.last = max(numbers, default=0)

    def add(self, call):
        """Numbers the call, keeps it and writes it; call holds every key except seq and received_at."""
        with self.lock:
            self.last += 1
            recorded = {"seq": self.last, "received_at": self.clock(), **call}
            self.calls.append(recorded)
            target = self.folder / f"{self.last:06d}-{call['source_key']}.json"
            with tempfile.NamedTemporaryFile("w", dir=self.folder, delete=False, prefix=".call-") as file:
                json.dump(recorded, file)
            os.chmod(file.name, 0o644)
            os.replace(file.name, target)
            return recorded

    def after(self, number):
        with self.lock:
            return [call for call in self.calls if call["seq"] > number]


def entries(source_key, body):
    """The number of entries of a call: 1 for Zabbix; for Alertmanager the length of the body's alerts array."""
    if source_key == "zabbix-lab":
        return 1
    try:
        parsed = json.loads(body)
    except ValueError:
        return 0
    if isinstance(parsed, dict) and isinstance(parsed.get("alerts"), list):
        return len(parsed["alerts"])
    return 0


def make_handler(recordings, tokens, log):
    expected = {source: f"Bearer {token}".encode() for source, token in tokens.items()}

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

        def _read_body(self):
            """The body, or None when it is longer than 5 MiB (then read to its end and dropped)."""
            length = int(self.headers.get("Content-Length") or 0)
            if length <= MAX_BODY:
                return self.rfile.read(length)
            while length > 0:
                chunk = self.rfile.read(min(length, 65536))
                if not chunk:
                    break
                length -= len(chunk)
            return None

        def do_POST(self):
            match = WEBHOOK_PATH.fullmatch(self.path)
            if not match:
                self._send(404, {"error": "not found"})
                return
            source = match.group(1)
            body = self._read_body()
            presented = (self.headers.get("Authorization") or "").encode()
            authorized = hmac.compare_digest(presented, expected[source])
            if body is None:
                status, answer, text = 413, {"error": "payload too large"}, ""
            elif authorized:
                text = body.decode("utf-8", errors="replace")
                status, answer = 200, {"entries": entries(source, text)}
            else:
                status, answer, text = 401, {"error": "unauthorized"}, body.decode("utf-8", errors="replace")
            headers = {}
            for name, value in self.headers.items():
                if name.lower() == "authorization":
                    value = "Bearer <redacted>"
                headers[name] = f"{headers[name]}, {value}" if name in headers else value
            call = {
                "source_key": source,
                "method": self.command,
                "path": self.path,
                "status": status,
                "authorized": authorized,
                "headers": headers,
                "body": text,
            }
            recorded = recordings.add(call)
            self._send(status, answer)
            log(f"{recorded['seq']} {source} {status}")

        def do_GET(self):
            url = urlsplit(self.path)
            if url.path == "/_recorder/health":
                self._send(200, {"ok": True})
            elif url.path == "/_recorder/calls":
                after = parse_qs(url.query).get("after", ["0"])[0]
                self._send(200, recordings.after(int(after) if after.isdigit() else 0))
            else:
                self._send(404, {"error": "not found"})

        def _not_found(self):
            self._send(404, {"error": "not found"})

        do_PUT = do_DELETE = do_PATCH = _not_found

    return Handler


def make_server(address, folder, secrets=SECRETS, log=None):
    """The recorder's server; raises SystemExit(1) with the message of P-04 spec 6.4 when it cannot start."""
    log = log or (lambda line: print(line, flush=True))
    tokens = {}
    for source, name in SOURCES.items():
        try:
            tokens[source] = (Path(secrets) / name).read_text()
        except FileNotFoundError:
            log(f"missing secret {name}")
            raise SystemExit(1) from None
    folder = Path(folder)
    if not folder.is_dir() or not os.access(folder, os.W_OK):
        log(f"output folder {folder} is not writable")
        raise SystemExit(1)
    server = ThreadingHTTPServer(address, make_handler(Recordings(folder), tokens, log), bind_and_activate=False)
    server.request_queue_size = 128
    server.server_bind()
    server.server_activate()
    return server


def main():
    server = make_server(("0.0.0.0", 8080), os.environ.get("RECORDER_OUT", "/recordings"))
    print("recorder listening on 0.0.0.0:8080", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    sys.exit(main())
