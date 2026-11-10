"""Tests of the recordings and result files of the spikes (P-06 spec Sections 6.1, 6.2 and 7)."""

import json
import secrets
import urllib.parse

import ai_spike
import jira_spike
import spike_fakes
from test_ai_spike import FakeClaude
from test_jira_spike import SITE, start


def texts(folder):
    return {path.name: path.read_text() for path in sorted(folder.glob("*.json"))}


def test_jira_recordings_hold_no_personal_data(tmp_path, monkeypatch, capsys):
    """SB-T-07 recordings replace the site, the email in any case and the account ID, and redact the personal keys."""
    setup, secret, fake, server = start(tmp_path, monkeypatch)
    with server:
        assert jira_spike.main(["--address", server.address]) == 0
    capsys.readouterr()
    encoded = urllib.parse.quote(fake.account, safe="")
    assert encoded != fake.account
    files = texts(setup.recordings / "jira")
    files["result"] = (setup.results / "P-06-SB-T-07.json").read_text()
    for name, text in files.items():
        for value in (secret["api_token"], fake.account, encoded, SITE, server.address):
            assert value not in text, name
        assert secret["email"].lower() not in text.lower(), name
    myself = setup.recording("jira", "0001-myself.json")
    assert myself["request"]["url"] == "${endpoint.jira}/rest/api/3/myself"
    assert myself["request"]["headers"] == {
        "Authorization": "Basic <redacted>",
        "Accept": "application/json",
        "User-Agent": "vigil-p06-spike",
    }
    answer = json.loads(myself["response"]["body"])
    assert answer == {
        "accountId": "<redacted>",
        "emailAddress": "<redacted>",
        "displayName": "<redacted>",
        "avatarUrls": "<redacted>",
        "self": "${endpoint.jira}/rest/api/3/user?accountId=<account-id>",
        "note": "Signed in as <email> (<account-id>)",
    }
    comment = json.loads(setup.recording("jira", "0005-comment.json")["response"]["body"])
    assert comment["author"]["emailAddress"] == "<redacted>"
    assert comment["author"]["self"] == "${endpoint.jira}/rest/api/3/user?accountId=<account-id>"


def test_jira_secret_in_answer_stops(tmp_path, monkeypatch, capsys):
    """SB-T-07 an answer that would put the API token into a recording stops the spike; nothing more is written."""
    setup, secret, fake, server = start(tmp_path, monkeypatch)
    fake.leak = secret["api_token"]
    stale = setup.recordings / "jira" / "0009-old.json"
    stale.parent.mkdir(parents=True)
    stale.write_text("{}")
    with server:
        code = jira_spike.main(["--address", server.address])
    output = capsys.readouterr().out
    assert code == 1
    path = setup.recordings / "jira" / "0001-myself.json"
    assert output.splitlines()[-1] == f"A secret would be written to {path}; nothing written."
    assert setup.changed_files() == ["jira"]
    assert len(server.requests) == 1


def test_secret_in_later_answer_keeps_earlier_files(tmp_path, monkeypatch, capsys):
    """AR-G-20 a Claude answer holding the key stops at that call; earlier recordings stay, no result file."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    key = "sk-ant-" + secrets.token_urlsafe(24)
    setup.secret("claude", key)
    claude = FakeClaude(key)

    def leaking(request):
        status, body, content_type = claude(request)
        if request.json()["max_tokens"] == 16:
            body = dict(body, echo=f"key {key}")
        return status, body, content_type

    with spike_fakes.FakeServer(leaking, setup.clock) as server:
        code = ai_spike.main(["claude", "--address", server.address])
    output = capsys.readouterr().out
    assert code == 1
    path = setup.recordings / "claude" / "0003-truncated.json"
    assert output.splitlines()[-1] == f"A secret would be written to {path}; nothing written."
    assert setup.changed_files() == ["claude", "claude/0001-sl-t-1.json", "claude/0002-sl-t-2.json"]
    for text in texts(setup.recordings / "claude").values():
        assert key not in text
        recording = json.loads(text)
        assert recording["request"]["headers"]["x-api-key"] == "<redacted>"
        assert recording["request"]["url"] == "${endpoint.claude}/v1/messages"
        assert sorted(recording) == ["call", "duration_ms", "error", "request", "response", "seq", "started_at"]
        assert recording["response"]["headers"] == {"Content-Type": "application/json"}


def test_connection_failure_is_recorded(tmp_path, monkeypatch, capsys):
    """AR-G-20 a call without an answer is recorded with response null and the error text, and fails its check."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    setup.secret("claude", "sk-ant-" + secrets.token_urlsafe(24))
    server = spike_fakes.FakeServer(lambda request: (200, "", None))
    address = server.address
    server.close()
    assert ai_spike.main(["claude", "--address", address]) == 1
    capsys.readouterr()
    recording = setup.recording("claude", "0001-sl-t-1.json")
    assert recording["response"] is None
    assert recording["error"]
    result = setup.result("claude")
    checks = {check["name"]: check for check in result["checks"]}
    assert checks["sl-t-1-status"]["detail"] == recording["error"]
    assert checks["sl-t-1-stop"]["detail"] == "not run"
    assert result["calls"][0]["status"] is None
    assert result["started_at"].endswith("Z") and len(result["run_id"]) == 16
