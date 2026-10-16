"""Unit tests of the webhook recorder (P-04 spec Section 7), against a recorder on a free local port."""

import json
import secrets
import threading
import urllib.error
import urllib.request

import pytest
import recorder


@pytest.fixture
def setup(tmp_path):
    folder = tmp_path / "secrets"
    folder.mkdir()
    tokens = {"zabbix-lab": secrets.token_urlsafe(32), "alertmanager-lab": secrets.token_urlsafe(32)}
    (folder / "webhook-zabbix").write_text(tokens["zabbix-lab"])
    (folder / "webhook-alertmanager").write_text(tokens["alertmanager-lab"])
    out = tmp_path / "out"
    out.mkdir()
    return folder, out, tokens


class Running:
    def __init__(self, folder, out):
        self.lines = []
        self.server = recorder.make_server(("127.0.0.1", 0), out, folder, self.lines.append)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"

    def request(self, method, path, body=None, token=None):
        request = urllib.request.Request(self.url + path, data=body, method=method)
        request.add_header("Content-Type", "application/json")
        if token is not None:
            request.add_header("Authorization", f"Bearer {token}")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                return response.status, json.loads(response.read())
        except urllib.error.HTTPError as error:
            return error.code, json.loads(error.read())

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def test_recorder(setup):
    """SB-T-01 (recorder) answers, records, redacts, numbers and filters the webhook calls."""
    folder, out, tokens = setup
    running = Running(folder, out)
    try:
        zabbix = json.dumps({"event_id": "1"}).encode()
        assert running.request("POST", "/api/v1/webhooks/zabbix-lab", zabbix, tokens["zabbix-lab"]) == (
            200,
            {"entries": 1},
        )
        alerts = json.dumps({"version": "4", "alerts": [{}, {}, {}]}).encode()
        path = "/api/v1/webhooks/alertmanager-lab"
        assert running.request("POST", path, alerts, tokens["alertmanager-lab"]) == (200, {"entries": 3})
        assert running.request("POST", path, b"[1]", tokens["alertmanager-lab"]) == (200, {"entries": 0})
        assert running.request("POST", path, alerts) == (401, {"error": "unauthorized"})
        assert running.request("POST", path, alerts, tokens["zabbix-lab"]) == (401, {"error": "unauthorized"})
        assert running.request("POST", "/api/v1/webhooks/other", alerts) == (404, {"error": "not found"})
        assert running.request("GET", "/nothing") == (404, {"error": "not found"})
        big = b"x" * (5 * 1024 * 1024 + 1)
        assert running.request("POST", path, big, tokens["alertmanager-lab"])[0] == 413
        assert running.request("GET", "/_recorder/health") == (200, {"ok": True})

        status, calls = running.request("GET", "/_recorder/calls")
        assert status == 200
        assert [c["seq"] for c in calls] == [1, 2, 3, 4, 5, 6]
        assert [c["status"] for c in calls] == [200, 200, 200, 401, 401, 413]
        assert [c["authorized"] for c in calls] == [True, True, True, False, False, True]
        assert calls[0]["source_key"] == "zabbix-lab" and calls[0]["body"] == zabbix.decode()
        assert calls[3]["body"] == alerts.decode()
        assert calls[5]["body"] == ""
        assert set(calls[0]) == {
            "seq",
            "received_at",
            "source_key",
            "method",
            "path",
            "status",
            "authorized",
            "headers",
            "body",
        }
        assert calls[0]["received_at"].endswith("Z") and len(calls[0]["received_at"]) == 24
        assert calls[0]["headers"]["Authorization"] == "Bearer <redacted>"
        assert "Authorization" not in calls[3]["headers"]
        assert calls[4]["headers"]["Authorization"] == "Bearer <redacted>"
        assert [c["seq"] for c in running.request("GET", "/_recorder/calls?after=4")[1]] == [5, 6]

        files = sorted(p.name for p in out.iterdir())
        assert files[0] == "000001-zabbix-lab.json" and files[1] == "000002-alertmanager-lab.json"
        assert len(files) == 6
        text = "".join(p.read_text() for p in out.iterdir())
        assert json.loads((out / "000001-zabbix-lab.json").read_text()) == calls[0]
        for token in tokens.values():
            assert token not in text
            assert not any(token in line for line in running.lines)
        assert "1 zabbix-lab 200" in running.lines
    finally:
        running.close()

    again = Running(folder, out)
    try:
        assert again.request("POST", "/api/v1/webhooks/zabbix-lab", b"{}", tokens["zabbix-lab"])[0] == 200
        assert [c["seq"] for c in again.request("GET", "/_recorder/calls")[1]] == [7]
        assert (out / "000007-zabbix-lab.json").exists()
    finally:
        again.close()


def test_missing_secret(setup):
    """SB-T-01 (recorder) a missing token file stops the recorder with exit 1."""
    folder, out, _tokens = setup
    (folder / "webhook-alertmanager").unlink()
    lines = []
    with pytest.raises(SystemExit) as stopped:
        recorder.make_server(("127.0.0.1", 0), out, folder, lines.append)
    assert stopped.value.code == 1 and lines == ["missing secret webhook-alertmanager"]
