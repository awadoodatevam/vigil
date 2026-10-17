"""Tests of the SSE probe of spike AR-SP-01 (P-05 spec Section 7)."""

import http.client
import json
import os
import socket
import subprocess
import sys
import time
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
IMAGE = "vigil/sse-probe:local"


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def wait_for(port, deadline=10):
    end = time.monotonic() + deadline
    while time.monotonic() < end:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1):
                return
        except OSError:
            time.sleep(0.1)
    raise AssertionError(f"nothing answers on port {port}")


@pytest.fixture(scope="module")
def probe():
    port = free_port()
    environment = dict(
        os.environ,
        PROBE_ADDRESS="127.0.0.1",
        PROBE_PORT=str(port),
        PROBE_PING_SECONDS="0.2",
        PROBE_CLOSE_SECONDS="1.2",
        PROBE_NOTICE_SECONDS="0.3",
    )
    process = subprocess.Popen([sys.executable, str(HERE / "probe.py")], env=environment)
    try:
        wait_for(port)
        yield port
    finally:
        process.terminate()
        process.wait(timeout=10)


def get(port, path):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    connection.request("GET", path)
    response = connection.getresponse()
    body = response.read()
    connection.close()
    return response, body


def read_stream(port, mode):
    """The lines of a stream and the seconds from the answer's headers to the stream's end."""
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    connection.request("GET", f"/probe/stream?mode={mode}")
    response = connection.getresponse()
    opened = time.monotonic()
    assert response.status == 200
    assert response.version == 11
    assert response.getheader("Transfer-Encoding") == "chunked"
    assert response.getheader("Content-Type") == "text/event-stream;charset=UTF-8"
    assert response.getheader("Cache-Control") == "no-cache"
    lines = []
    while True:
        line = response.readline()
        if not line:
            break
        lines.append(line.decode().rstrip("\n"))
    duration = time.monotonic() - opened
    connection.close()
    return lines, duration


def test_fixed_answers(probe):
    """AR-SP-01 the probe serves the page, both health paths, the time, the info, 400 and 404."""
    response, body = get(probe, "/")
    assert response.status == 200
    assert response.version == 11
    assert response.getheader("Content-Type") == "text/html; charset=utf-8"
    assert response.getheader("Cache-Control") == "no-cache"
    assert int(response.getheader("Content-Length")) == len(body)
    assert body == (HERE / "index.html").read_bytes()

    for path in ("/actuator/health/liveness", "/actuator/health"):
        response, body = get(probe, path)
        assert response.status == 200
        assert json.loads(body) == {"status": "UP"}

    before = time.time() * 1000
    response, body = get(probe, "/probe/time")
    assert response.status == 200
    assert response.getheader("Cache-Control") == "no-store"
    assert abs(json.loads(body)["server_ms"] - before) < 1000

    response, body = get(probe, "/probe/info")
    assert json.loads(body) == {"local_address": "127.0.0.1", "forwarded_for": None}

    for path, status in (("/probe/stream", 400), ("/probe/stream?mode=other", 400), ("/unknown", 404)):
        response, body = get(probe, path)
        assert response.status == status
        assert body == b""


def test_streams(probe):
    """AR-SP-01 notices with seq and sent_ms, pings, retry first, and the end after PROBE_CLOSE_SECONDS."""
    lines, duration = read_stream(probe, "notices")
    assert lines[:2] == ["retry: 1000", ""]
    assert ": ping" in lines
    notices = [json.loads(line[len("data: ") :]) for line in lines if line.startswith("data: ")]
    assert lines.count("event: change") == len(notices)
    # Notices at 0.3, 0.6 and 0.9 s (1.2 s is the end).
    assert [notice["seq"] for notice in notices] == [1, 2, 3]
    assert all(isinstance(notice["sent_ms"], float) for notice in notices)
    assert 1.2 <= duration < 2

    lines, duration = read_stream(probe, "idle")
    assert lines[:2] == ["retry: 1000", ""]
    assert ": ping" in lines
    assert not any(line.startswith(("event:", "data:")) for line in lines)
    assert 1.2 <= duration < 2


def test_image():
    """AR-SP-01 the image vigil/sse-probe:local answers the health check and serves the page."""
    container = subprocess.run(
        ["docker", "run", "-d", "--rm", "-e", "PROBE_ADDRESS=0.0.0.0", "-p", "127.0.0.1::8080", IMAGE],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    try:
        mapping = subprocess.run(
            ["docker", "port", container, "8080/tcp"], capture_output=True, text=True, check=True
        ).stdout
        port = int(mapping.split()[0].rsplit(":", 1)[1])
        end = time.monotonic() + 30
        while True:
            try:
                response, body = get(port, "/actuator/health/liveness")
                break
            except (OSError, http.client.HTTPException):
                if time.monotonic() > end:
                    raise
                time.sleep(0.5)
        assert response.status == 200
        assert json.loads(body) == {"status": "UP"}
        response, body = get(port, "/")
        assert body == (HERE / "index.html").read_bytes()
    finally:
        subprocess.run(["docker", "rm", "-f", container], capture_output=True, check=False)
