"""Shared pieces of the mocks: lab names and ports, calls with a deadline, JSON HTTP servers, status files, logging."""

import json
import logging
import os
import tempfile
import threading
import urllib.error
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

log = logging.getLogger("labmocks")

# DD-F-10 mock_behaviour.subscriber_database (complete list).
SCHEMAS = ("subscriber_profile", "balance_ledger", "bundle_catalog", "bundle_orders", "ussd_sessions", "sms_delivery")

DB_PORT = 7000
VAS_PORT = 7100
METRICS_PORT = 9200
DB_URL = f"http://lab-db-01:{DB_PORT}"
VAS_URL = f"http://lab-vas-01:{VAS_PORT}"
API_HOST = "lab-api-01"

STATUS_DIR = "/var/lib/lab/status"
CONTROL_DIR = "/var/lib/lab/control"

_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def urllib_post(url, body, timeout):
    """POSTs a JSON body with urllib and returns (status, JSON answer); raises on connection errors and time-outs."""
    request = urllib.request.Request(
        url, data=json.dumps(body).encode(), method="POST", headers={"Content-Type": "application/json"}
    )
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            return response.status, _json_or_empty(response.read())
    except urllib.error.HTTPError as error:
        with error:
            return error.code, _json_or_empty(error.read())


def _json_or_empty(raw):
    try:
        answer = json.loads(raw or b"{}")
    except ValueError:
        return {}
    return answer if isinstance(answer, dict) else {}


def _is_timeout(error):
    if isinstance(error, TimeoutError):
        return True
    return isinstance(error, urllib.error.URLError) and isinstance(error.reason, TimeoutError)


def call(http, clock, url, body, deadline):
    """Makes one call that MUST finish within deadline seconds (connect, send and read together).

    Returns (status, answer, error); error is None only for a 200 answer within the deadline, otherwise it is the
    error text of the answer, "timeout", or the connection error.
    """
    if deadline <= 0:
        return None, {}, "timeout"
    start = clock()
    try:
        status, answer = http(url, body, deadline)
    except Exception as error:  # every failed call counts as failed
        return None, {}, "timeout" if _is_timeout(error) else str(error)
    if clock() - start > deadline:
        return status, answer, "timeout"
    if status != 200:
        return status, answer, str(answer.get("error") or f"HTTP {status}")
    return status, answer, None


def parse_body(raw):
    """Reads a request body: None for an empty body, the JSON object, or ValueError for anything else."""
    if not raw:
        return None
    body = json.loads(raw)
    if not isinstance(body, dict):
        raise ValueError("not a JSON object")
    return body


class _Server(ThreadingHTTPServer):
    request_queue_size = 128
    daemon_threads = True


def make_server(port, handle):
    """An HTTP server on port whose POST requests are answered by handle(path, raw body) -> (status, JSON object)."""

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            status, answer = handle(self.path, raw)
            self._answer(status, answer)

        def do_GET(self):
            self._answer(404, {"error": "not found"})

        def _answer(self, status, answer):
            data = json.dumps(answer).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, format, *args):  # nothing is logged per call
            pass

    return _Server(("0.0.0.0", port), Handler)


NOT_FOUND = (404, {"error": "not found"})
BAD_REQUEST = (400, {"error": "bad request"})


def write_status(directory, name, value):
    """Writes one status file (a decimal integer and a newline, mode 0644) by rename, so a reader never sees a part."""
    fd, temporary = tempfile.mkstemp(dir=directory, prefix=f".{name}.")
    try:
        with os.fdopen(fd, "w") as file:
            file.write(f"{value}\n")
        os.chmod(temporary, 0o644)
        os.replace(temporary, os.path.join(directory, name))
    except BaseException:
        if os.path.exists(temporary):
            os.unlink(temporary)
        raise


class Outcomes:
    """Logs when the outcome for one key changes from success to failure (with the error) or back."""

    def __init__(self, what):
        self._what = what
        self._failing = {}
        self._lock = threading.Lock()

    def record(self, key, error):
        with self._lock:
            was_failing = self._failing.get(key, False)
            self._failing[key] = error is not None
        if error is not None and not was_failing:
            log.info("%s %s failing: %s", self._what, key, error)
        elif error is None and was_failing:
            log.info("%s %s recovered", self._what, key)


class Counts:
    """Thread-safe counters, created at 0 for every key."""

    def __init__(self, keys):
        self._values = dict.fromkeys(keys, 0)
        self._lock = threading.Lock()

    def add(self, key, amount=1):
        with self._lock:
            self._values[key] += amount

    def snapshot(self):
        with self._lock:
            return dict(self._values)


def run_fixed_rate(period, tasks, clock, sleep, stop=None):
    """Starts every task, each in its own thread, at every multiple of period from now, whether or not earlier calls
    have finished."""
    next_start = clock()
    while stop is None or not stop.is_set():
        for task in tasks:
            threading.Thread(target=task, daemon=True).start()
        next_start += period
        delay = next_start - clock()
        if delay > 0:
            sleep(delay)
