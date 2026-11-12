"""Tests of install_tenant.py (P-26 spec Sections 6.3 and 6.8): a local http.server plays AR-I-95 to AR-I-98."""

import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import install_tenant
import pytest

ROOT = Path(__file__).resolve().parent.parent.parent
LOCAL = ROOT / "deploy" / "values" / "local.yaml"
TOKEN = "test-token-" + "x" * 20
TENANT = "6f1c1d2e-0000-4000-8000-000000000001"
IMPORT = "6f1c1d2e-0000-4000-8000-000000000002"


def parts(outcome="succeeded", error=None):
    return [
        {"module": "vigil-tenancy", "outcome": "succeeded", "counts": {}, "error": None},
        {"module": "vigil-servicemap", "outcome": outcome, "counts": {}, "error": error},
        {
            "module": "vigil-knowledge",
            "outcome": "not_started" if outcome != "succeeded" else "succeeded",
            "counts": {},
            "error": None,
        },
    ]


class Installation:
    def __init__(self, polls_running=2, final_state="succeeded", final_parts=None):
        self.requests = []
        self.polls_running = polls_running
        self.final_state = final_state
        self.final_parts = final_parts or parts()
        installation = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, format, *args):
                pass

            def handle_one(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length) if length else b""
                installation.requests.append(
                    {
                        "method": self.command,
                        "path": self.path,
                        "authorization": self.headers.get("Authorization"),
                        "content_type": self.headers.get("Content-Type"),
                        "body": body,
                    }
                )
                status, answer = installation.answer(self.command, self.path, body)
                data = json.dumps(answer).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_GET = do_POST = do_PUT = handle_one

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def answer(self, method, path, body):
        if method == "POST" and path == "/api/v1/installation/tenants":
            return 200, {"tenant_id": TENANT}
        if method == "POST" and path == f"/api/v1/installation/tenants/{TENANT}/seed-imports":
            return 202, {"seed_import_id": IMPORT}
        if method == "GET" and path == f"/api/v1/installation/seed-imports/{IMPORT}":
            if self.polls_running > 0:
                self.polls_running -= 1
                return 200, {"seed_import_id": IMPORT, "state": "running", "parts": []}
            return 200, {
                "seed_import_id": IMPORT,
                "tenant_id": TENANT,
                "state": self.final_state,
                "parts": self.final_parts,
            }
        if method == "PUT" and path == "/api/v1/installation/aliases/demo.vigil.test":
            return 200, {
                "host_name": "demo.vigil.test",
                "revision": 3,
                "tenant_id": json.loads(body)["tenant_id"],
                "set_at": "2026-11-12T10:00:00.000Z",
            }
        return 404, {"error": {"code": "not_found"}}

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def setup(tmp_path, monkeypatch):
    started = []

    def make(**options):
        installation = Installation(**options)
        started.append(installation)
        monkeypatch.setattr(install_tenant, "BASE_URL", installation.base)
        monkeypatch.setattr(install_tenant, "POLL_SECONDS", 0.01)
        token = tmp_path / "installation-token"
        token.write_text(TOKEN)
        derived = tmp_path / "derived.yaml"
        derived.write_text('lab_db_host_key_sha256: "SHA256:abc"\n')
        arguments = [
            "--values",
            str(LOCAL),
            "--values",
            str(derived),
            "--state",
            str(tmp_path / "state"),
            "--token-file",
            str(token),
        ]
        return installation, arguments, tmp_path / "state"

    yield make
    for installation in started:
        installation.stop()


def test_calls_in_order(setup, capsys):
    """AR-B-07 the tenant is created, the bundle imported, the import polled until not running and the alias set."""
    installation, arguments, state = setup()
    assert install_tenant.main(arguments) == 0
    calls = [(r["method"], r["path"]) for r in installation.requests]
    assert calls == [
        ("POST", "/api/v1/installation/tenants"),
        ("POST", f"/api/v1/installation/tenants/{TENANT}/seed-imports"),
        ("GET", f"/api/v1/installation/seed-imports/{IMPORT}"),
        ("GET", f"/api/v1/installation/seed-imports/{IMPORT}"),
        ("GET", f"/api/v1/installation/seed-imports/{IMPORT}"),
        ("PUT", "/api/v1/installation/aliases/demo.vigil.test"),
    ]
    assert all(r["authorization"] == f"Bearer {TOKEN}" for r in installation.requests)
    name = json.loads(installation.requests[0]["body"])["name"]
    assert name.startswith("local ") and name.endswith("Z") and len(name) == len("local 2026-11-12T10:00:00Z")
    upload = installation.requests[1]
    assert upload["content_type"].startswith("multipart/form-data; boundary=")
    bundle_bytes = (state / "bundle.zip").read_bytes()
    assert bundle_bytes in upload["body"] and b'name="bundle"' in upload["body"]
    assert json.loads(installation.requests[-1]["body"]) == {"tenant_id": TENANT}
    assert json.loads((state / "seed-import.json").read_text())["state"] == "succeeded"
    assert json.loads((state / "tenant.json").read_text())["tenant_id"] == TENANT
    output = capsys.readouterr().out
    assert TOKEN not in output and TENANT in output


def test_failed_part(setup, capsys):
    """AR-B-07 a failed import prints each failed part's error, exits 1 and sets no alias."""
    installation, arguments, state = setup(
        polls_running=0, final_state="failed", final_parts=parts("failed", "02-service-map.yaml: bad value")
    )
    assert install_tenant.main(arguments) == 1
    assert (
        capsys.readouterr().out == "The seed import ended failed.\nvigil-servicemap: 02-service-map.yaml: bad value\n"
    )
    assert [r["method"] for r in installation.requests] == ["POST", "POST", "GET"]
    assert json.loads((state / "seed-import.json").read_text())["state"] == "failed"
    assert not (state / "tenant.json").exists()


def test_refused_call(setup, capsys):
    """AR-B-07 a refused call stops the installation with its status."""
    installation, arguments, state = setup()
    installation.answer = lambda method, path, body: (401, {"error": {"code": "bad_token"}})
    assert install_tenant.main(arguments) == 1
    assert capsys.readouterr().out.startswith("Create tenant (AR-I-95) answered 401")


def test_usage(capsys):
    """AR-B-07 wrong arguments print the usage line and exit 2."""
    assert install_tenant.main(["--state", "x"]) == 2
    assert capsys.readouterr().out.startswith("Usage: install_tenant.py")
