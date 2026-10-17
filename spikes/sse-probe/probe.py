"""The SSE test probe of spike AR-SP-01 (P-05 spec Section 6.11).

It stands at vigil-app's address and port on the main host and imitates vigil-app's live update stream (AR-I-04), so
the path through the load balancer can be tested from a browser before vigil-app exists. HTTP/1.1, one thread per
connection; fixed answers carry Content-Length, streams use chunked transfer encoding with one chunk per write.
Settings from the environment (defaults): PROBE_ADDRESS (127.0.0.1), PROBE_PORT (8080), PROBE_PING_SECONDS (15),
PROBE_CLOSE_SECONDS (300), PROBE_NOTICE_SECONDS (3).
"""

import json
import os
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

INDEX = Path(__file__).with_name("index.html")


def setting(name, default):
    return float(os.environ.get(name, default))


PING_SECONDS = setting("PROBE_PING_SECONDS", 15)
CLOSE_SECONDS = setting("PROBE_CLOSE_SECONDS", 300)
NOTICE_SECONDS = setting("PROBE_NOTICE_SECONDS", 3)


def server_ms():
    return time.time() * 1000


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        url = urlsplit(self.path)
        if url.path == "/":
            self.answer(200, INDEX.read_bytes(), "text/html; charset=utf-8", "no-cache")
        elif url.path in ("/actuator/health/liveness", "/actuator/health"):
            self.answer_json({"status": "UP"})
        elif url.path == "/probe/time":
            self.answer_json({"server_ms": server_ms()}, "no-store")
        elif url.path == "/probe/info":
            info = {
                "local_address": self.connection.getsockname()[0],
                "forwarded_for": self.headers.get("X-Forwarded-For"),
            }
            self.answer_json(info)
        elif url.path == "/probe/stream":
            mode = parse_qs(url.query).get("mode", [None])[0]
            if mode in ("notices", "idle"):
                self.stream(mode)
            else:
                self.answer(400, b"")
        else:
            self.answer(404, b"")

    def answer(self, status, body, content_type=None, cache_control=None):
        self.send_response(status)
        if content_type:
            self.send_header("Content-Type", content_type)
        if cache_control:
            self.send_header("Cache-Control", cache_control)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def answer_json(self, value, cache_control=None):
        self.answer(200, json.dumps(value).encode(), "application/json", cache_control)

    def write_chunk(self, text):
        """Writes one chunk and flushes it; False when the write failed."""
        data = text.encode()
        try:
            self.wfile.write(f"{len(data):X}\r\n".encode() + data + b"\r\n")
            self.wfile.flush()
        except OSError:
            return False
        return True

    def stream(self, mode):
        """The stream of AR-I-04: retry, pings, notices (mode notices) and the end after PROBE_CLOSE_SECONDS."""
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream;charset=UTF-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()
        opened = time.monotonic()
        close_at = opened + CLOSE_SECONDS
        next_ping = opened + PING_SECONDS
        seq = 1
        if not self.write_chunk("retry: 1000\n\n"):
            return

        def notice_due():
            return opened + seq * NOTICE_SECONDS if mode == "notices" and seq * NOTICE_SECONDS < CLOSE_SECONDS else None

        while True:
            next_notice = notice_due()
            next_event = min(t for t in (close_at, next_ping, next_notice) if t is not None)
            delay = next_event - time.monotonic()
            if delay > 0:
                time.sleep(delay)
            now = time.monotonic()
            if now >= close_at:
                break
            if next_notice is not None and now >= next_notice:
                data = json.dumps({"seq": seq, "sent_ms": server_ms()}, separators=(",", ":"))
                if not self.write_chunk(f"event: change\ndata: {data}\n\n"):
                    return
                seq += 1
            if now >= next_ping:
                if not self.write_chunk(": ping\n\n"):
                    return
                next_ping += PING_SECONDS
        try:
            self.wfile.write(b"0\r\n\r\n")
            self.wfile.flush()
        except OSError:
            return


def main():
    address = os.environ.get("PROBE_ADDRESS", "127.0.0.1")
    port = int(os.environ.get("PROBE_PORT", "8080"))
    ThreadingHTTPServer((address, port), Handler).serve_forever()


if __name__ == "__main__":
    main()
