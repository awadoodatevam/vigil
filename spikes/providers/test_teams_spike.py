"""Tests of the Teams spike (P-07 spec Sections 6.4, 6.6 and 7) against a fake Teams webhook server."""

import json
import secrets
import shutil
import socket
import urllib.parse

import common
import spike_fakes
import teams_spike

KEYS = [
    "noc-l1",
    "db-ops",
    "vas-service-owners",
    "it-apps",
    "noc-shift-lead",
    "noc-management",
    "customer-care",
    "dbops-engineer",
    "dbops-lead",
]
SECRETS = {key: f"teams-team-{key}" for key in KEYS[:7]} | {key: f"teams-user-{key}" for key in KEYS[7:]}
ADDRESSES = {
    "noc-l1": "Techfinity Demo / NOC L1",
    "db-ops": "Techfinity Demo / Database Operations",
    "vas-service-owners": "Techfinity Demo / VAS Service Owners",
    "it-apps": "Techfinity Demo / IT Applications",
    "noc-shift-lead": "Techfinity Demo / NOC Shift Lead",
    "noc-management": "Techfinity Demo / NOC Management",
    "customer-care": "Techfinity Demo / Customer Care",
    "dbops-engineer": "Techfinity Demo / Approvals — Usman",
    "dbops-lead": "Techfinity Demo / Approvals — Farah",
}


class FakeTeams:
    """The Workflows webhooks of the nine channels, one path per channel: 202 unless statuses names another."""

    def __init__(self, statuses=None):
        self.statuses = statuses or {}
        self.echo = None

    def __call__(self, request):
        key = request.path.split("?", 1)[0].lstrip("/")
        status = self.statuses.get(key, 202)
        return status, self.echo or "", "text/plain; charset=utf-8" if self.echo else None


def closed_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def signature():
    """A random sig value whose decoded form differs from the written one."""
    return secrets.token_urlsafe(12) + "%2F" + secrets.token_urlsafe(12)


def start(tmp_path, monkeypatch, statuses=None, refused=()):
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    fake = FakeTeams(statuses)
    server = spike_fakes.FakeServer(fake, setup.clock, seconds=lambda request: 0.25)
    webhooks = {}
    for key in KEYS:
        base = f"http://127.0.0.1:{closed_port()}" if key in refused else server.address
        webhooks[key] = f"{base}/{key}?sig={signature()}"
        setup.secret(SECRETS[key], webhooks[key])
    return setup, fake, server, webhooks


def guarded_values(webhooks):
    values = []
    for webhook in webhooks.values():
        raw = webhook.rsplit("sig=", 1)[1]
        values += [webhook, raw, urllib.parse.unquote(raw)]
    assert all(raw != urllib.parse.unquote(raw) for raw in values[1::3])
    return values


def link_in(server, key):
    """The link of the message the fake got for that channel (line 4 of the card)."""
    for request in server.requests:
        if request.path.startswith(f"/{key}?"):
            text = request.json()["attachments"][0]["content"]["body"][3]["text"]
            return text.split("](", 1)[1].rstrip(")")
    raise AssertionError(key)


def expected_payload(lines, link):
    return {
        "type": "message",
        "attachments": [
            {
                "contentType": "application/vnd.microsoft.card.adaptive",
                "content": {
                    "$schema": "http://adaptivecards.io/schemas/adaptive-card.json",
                    "type": "AdaptiveCard",
                    "version": "1.4",
                    "body": [
                        {"type": "TextBlock", "text": lines[0], "wrap": True, "weight": "Bolder"},
                        {"type": "TextBlock", "text": lines[1], "wrap": True},
                        {"type": "TextBlock", "text": lines[2], "wrap": True},
                        {"type": "TextBlock", "text": f"Acknowledge: [{link}]({link})", "wrap": True},
                    ],
                },
            }
        ],
    }


def correct(server, key):
    return lambda: link_in(server, key)


def test_teams_run(tmp_path, monkeypatch, capsys):
    """SB-T-06 the Teams run: nine posts in DD-F-07 order, 2xx passes, 400 and a refused connection fail and make
    their questions "not run"; the owner's answers judged as Section 6.6; the result file."""
    setup, fake, server, webhooks = start(
        tmp_path, monkeypatch, statuses={"noc-l1": 200, "db-ops": 400}, refused={"it-apps"}
    )

    def safe():
        wrapped = urllib.parse.quote(link_in(server, "customer-care"), safe="")
        return f"https://emea01.safelinks.protection.outlook.com/?url={wrapped}&data=05%7C02"

    stdin = spike_fakes.answer_with(
        monkeypatch,
        setup.clock,
        [
            " Y ",
            correct(server, "noc-l1"),
            "maybe",
            "no",
            correct(server, "vas-service-owners"),
            "yes",
            "",
            "y",
            "https://demo.vigil.solviclabs.com/m/acknowledge/another",
            "y",
            safe,
            "y",
            correct(server, "dbops-engineer"),
            "y",
            correct(server, "dbops-lead"),
        ],
    )
    with server:
        code = teams_spike.main([])
    output = capsys.readouterr().out
    assert code == 1, output
    assert stdin.answers == []
    result = setup.result("SB-T-06", package="P-07")
    run_id = result["run_id"]
    assert result["spike"] == "SB-T-06" and result["passed"] is False
    refused = next(check for check in result["checks"] if check["name"] == "post-it-apps")["detail"]
    assert refused.startswith("<urlopen error") and "refused" in refused.lower()
    no = "the owner answered no"
    assert [(c["name"], c["passed"], c["detail"]) for c in result["checks"]] == [
        ("post-noc-l1", True, ""),
        ("post-db-ops", False, "status 400"),
        ("post-vas-service-owners", True, ""),
        ("post-it-apps", False, refused),
        ("post-noc-shift-lead", True, ""),
        ("post-noc-management", True, ""),
        ("post-customer-care", True, ""),
        ("post-dbops-engineer", True, ""),
        ("post-dbops-lead", True, ""),
        ("lines-noc-l1", True, ""),
        ("link-noc-l1", True, ""),
        ("lines-db-ops", False, "not run"),
        ("link-db-ops", False, "not run"),
        ("lines-vas-service-owners", False, no),
        ("link-vas-service-owners", True, ""),
        ("lines-it-apps", False, "not run"),
        ("link-it-apps", False, "not run"),
        ("lines-noc-shift-lead", True, ""),
        ("link-noc-shift-lead", False, "no link pasted"),
        ("lines-noc-management", True, ""),
        ("link-noc-management", False, "the pasted link is not the link sent"),
        ("lines-customer-care", True, ""),
        ("link-customer-care", False, "the link was rewritten by Safe Links"),
        ("lines-dbops-engineer", True, ""),
        ("link-dbops-engineer", True, ""),
        ("lines-dbops-lead", True, ""),
        ("link-dbops-lead", True, ""),
    ]
    statuses = {"noc-l1": 200, "db-ops": 400, "it-apps": None}
    assert [(c["key"], c["address"], c["status"]) for c in result["channels"]] == [
        (key, ADDRESSES[key], statuses.get(key, 202)) for key in KEYS
    ]
    assert all(isinstance(channel["duration_ms"], int) for channel in result["channels"])
    assert result["channels"][0]["duration_ms"] == 250
    assert set(result) == {"spike", "run_id", "started_at", "finished_at", "passed", "checks", "channels"}
    assert output.splitlines()[-1] == f"result: {setup.results / 'P-07-SB-T-06.json'}"
    assert "post-db-ops: FAILED (status 400)" in output.splitlines()

    assert len(server.requests) == 8
    for request in server.requests:
        key = request.path.split("?")[0][1:]
        if key != "db-ops":
            lines = [block["text"] for block in request.json()["attachments"][0]["content"]["body"]]
            lines[3] = f"Acknowledge: {link_in(server, key)}"
            assert lines[0] == f"SB-T-06 spike {run_id} - {ADDRESSES[key]}"
            assert f'Does the message "{lines[0]}" show these four lines' in output
            assert "\n".join(f"  {line}" for line in lines) in output
    assert 'In the Teams desktop client, open the channel "Techfinity Demo / NOC L1".' in output
    assert "Right-click the link in that message, choose Copy link, and paste it here:" in output
    assert output.count("(y or n) ") == 8


def test_teams_payload(tmp_path, monkeypatch, capsys):
    """AR-K-18 each post is the Adaptive Card payload exactly (text and key order, weight on line 1 only, line 4's
    Markdown link) with its two headers; 200 and 202 are sent, 400 fails with "status 400"."""
    setup, fake, server, webhooks = start(tmp_path, monkeypatch, statuses={"noc-l1": 200, "db-ops": 400})
    spike_fakes.answer_with(monkeypatch, setup.clock, [])
    with server:
        assert teams_spike.main([]) == 1
    capsys.readouterr()
    result = setup.result("SB-T-06", package="P-07")
    run_id = result["run_id"]
    posts = [(c["name"], c["passed"], c["detail"]) for c in result["checks"][:9]]
    assert posts == [(f"post-{key}", key != "db-ops", "status 400" if key == "db-ops" else "") for key in KEYS]
    requests = server.requests
    assert [request.path.split("?")[0] for request in requests] == [f"/{key}" for key in KEYS]
    links = set()
    for request in requests:
        key = request.path.split("?")[0][1:]
        assert request.method == "POST"
        assert request.path == webhooks[key].split(server.address, 1)[1]
        assert request.headers["content-type"] == "application/json"
        assert request.headers["user-agent"] == "vigil-p07-spike"
        link = link_in(server, key)
        assert link.startswith("https://demo.vigil.solviclabs.com/m/acknowledge/")
        links.add(link)
        lines = [
            f"SB-T-06 spike {run_id} - {ADDRESSES[key]}",
            "Second line: the four lines of this message are on separate lines.",
            "Third line: sent by the P-07 Teams spike; nothing else uses this message.",
            f"Acknowledge: {link}",
        ]
        assert request.body == json.dumps(expected_payload(lines, link), ensure_ascii=False)
        assert list(request.json()) == ["type", "attachments"]
        assert list(request.json()["attachments"][0]["content"]) == ["$schema", "type", "version", "body"]
        assert [list(block) for block in request.json()["attachments"][0]["content"]["body"]] == [
            ["type", "text", "wrap", "weight"],
            ["type", "text", "wrap"],
            ["type", "text", "wrap"],
            ["type", "text", "wrap"],
        ]
    assert len(links) == 9
    assert "Approvals — Usman" in requests[7].body


def test_teams_pacing(tmp_path, monkeypatch, capsys):
    """AR-K-19 the posts are at least 300 ms apart: common.pause(0.3) exactly eight times, each between two posts."""
    setup, fake, server, webhooks = start(tmp_path, monkeypatch)
    pauses = []
    monkeypatch.setattr(common, "pause", lambda seconds: pauses.append((seconds, len(server.requests))))
    spike_fakes.answer_with(monkeypatch, setup.clock, [])
    with server:
        teams_spike.main([])
    capsys.readouterr()
    assert pauses == [(0.3, count) for count in range(1, 9)]
    assert len(server.requests) == 9


def test_teams_no_answer(tmp_path, monkeypatch, capsys):
    """SB-T-06 an end of input at a question lists it and every later question's check as "no answer"; the result file
    is still written, and a channel whose post failed stays "not run"."""
    setup, fake, server, webhooks = start(tmp_path, monkeypatch, statuses={"dbops-lead": 500})
    spike_fakes.answer_with(monkeypatch, setup.clock, ["y", correct(server, "noc-l1"), "y"])
    with server:
        assert teams_spike.main([]) == 1
    capsys.readouterr()
    checks = {c["name"]: (c["passed"], c["detail"]) for c in setup.result("SB-T-06", package="P-07")["checks"]}
    assert checks["lines-noc-l1"] == checks["link-noc-l1"] == checks["lines-db-ops"] == (True, "")
    assert checks["link-db-ops"] == (False, "no answer")
    for key in KEYS[2:8]:
        assert checks[f"lines-{key}"] == checks[f"link-{key}"] == (False, "no answer")
    assert checks["lines-dbops-lead"] == checks["link-dbops-lead"] == (False, "not run")


def test_teams_recordings(tmp_path, monkeypatch, capsys):
    """SB-T-06 recordings name the webhook secret, never its address or sig value (written or decoded)."""
    setup, fake, server, webhooks = start(tmp_path, monkeypatch, statuses={"db-ops": 400})
    spike_fakes.answer_with(monkeypatch, setup.clock, [])
    stale = setup.recordings / "teams" / "0042-old.json"
    stale.parent.mkdir(parents=True)
    stale.write_text("{}")
    (setup.recordings / "teams" / "README.md").write_text("kept")
    with server:
        assert teams_spike.main([]) == 1
    output = capsys.readouterr().out
    assert setup.recording_files("teams") == [f"{n:04d}-post-{key}.json" for n, key in enumerate(KEYS, start=1)]
    assert (setup.recordings / "teams" / "README.md").read_text() == "kept"
    first = setup.recording("teams", "0001-post-noc-l1.json")
    last = setup.recording("teams", "0009-post-dbops-lead.json")
    assert first["request"]["url"] == "secret:teams-team-noc-l1"
    assert last["request"]["url"] == "secret:teams-user-dbops-lead"
    assert list(first) == ["seq", "call", "started_at", "duration_ms", "request", "response", "error"]
    assert first["call"] == "post-noc-l1" and first["seq"] == 1 and first["duration_ms"] == 250
    assert first["request"]["method"] == "POST"
    assert first["request"]["headers"] == {"Content-Type": "application/json", "User-Agent": "vigil-p07-spike"}
    assert first["request"]["body"] == server.requests[0].body
    assert first["response"] == {"status": 202, "headers": {}, "body": ""}
    assert first["error"] is None
    assert setup.recording("teams", "0002-post-db-ops.json")["response"]["status"] == 400
    texts = [path.read_text() for path in (setup.recordings / "teams").glob("*.json")]
    texts += [(setup.results / "P-07-SB-T-06.json").read_text(), output]
    for value in guarded_values(webhooks):
        for text in texts:
            assert value not in text


def test_teams_secret_in_answer_stops(tmp_path, monkeypatch, capsys):
    """SB-T-06 a fake answer body that echoes the sig value stops the spike; nothing more is written."""
    setup, fake, server, webhooks = start(tmp_path, monkeypatch)
    raw = webhooks["noc-l1"].rsplit("sig=", 1)[1]
    fake.echo = f"received sig {urllib.parse.unquote(raw)}"
    spike_fakes.answer_with(monkeypatch, setup.clock, [])
    with server:
        code = teams_spike.main([])
    output = capsys.readouterr().out
    path = setup.recordings / "teams" / "0001-post-noc-l1.json"
    assert code == 1
    assert output.strip() == f"A secret would be written to {path}; nothing written."
    assert setup.changed_files() == ["teams"]
    assert len(server.requests) == 1


def test_teams_error_redacted(tmp_path, monkeypatch, capsys):
    """SB-T-06 an error text that names the webhook address is redacted in the check's detail and the recording."""
    setup, fake, server, webhooks = start(tmp_path, monkeypatch)

    class Failing:
        def open(self, request, timeout):
            sig = urllib.parse.unquote(request.full_url.rsplit("sig=", 1)[1])
            raise OSError(f"cannot reach {request.full_url} (sig {sig})")

    monkeypatch.setattr(common.urllib.request, "build_opener", lambda *handlers: Failing())
    spike_fakes.answer_with(monkeypatch, setup.clock, [])
    with server:
        assert teams_spike.main([]) == 1
    output = capsys.readouterr().out
    result = setup.result("SB-T-06", package="P-07")
    assert result["checks"][0] == {
        "name": "post-noc-l1",
        "passed": False,
        "detail": "cannot reach <redacted> (sig <redacted>)",
    }
    assert setup.recording("teams", "0001-post-noc-l1.json")["error"] == "cannot reach <redacted> (sig <redacted>)"
    texts = [output] + [path.read_text() for path in (setup.recordings / "teams").glob("*.json")]
    for value in guarded_values(webhooks):
        assert all(value not in text for text in texts)


def test_teams_usage(tmp_path, monkeypatch, capsys):
    """SB-T-06 any argument prints the usage line and exits 2 before any file changes."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    assert teams_spike.main(["--address", "http://127.0.0.1:1"]) == 2
    assert capsys.readouterr().out == "Usage: teams_spike.py\n"
    assert setup.changed_files() == []


def test_teams_link_rule():
    """SB-T-06 the link rule: equal passes; empty; a Safe Links address only when its url parameter is the link."""
    link = "https://demo.vigil.solviclabs.com/m/acknowledge/2f0c0000-0000-4000-8000-000000000000"
    wrapped = "https://nam02.safelinks.protection.outlook.com/?url=" + urllib.parse.quote(link, safe="")
    assert teams_spike.link_detail(link, link) == ""
    assert teams_spike.link_detail("", link) == "no link pasted"
    assert teams_spike.link_detail(wrapped, link) == "the link was rewritten by Safe Links"
    other = wrapped.replace("2f0c", "9f0c")
    assert teams_spike.link_detail(other, link) == "the pasted link is not the link sent"
    assert teams_spike.link_detail(wrapped.replace("https", "http", 1), link) == "the pasted link is not the link sent"
    assert teams_spike.link_detail("http://[::1", link) == "the pasted link is not the link sent"
    assert teams_spike.link_detail(link + "/", link) == "the pasted link is not the link sent"


def test_teams_secrets(tmp_path, monkeypatch, capsys):
    """AR-S-13 a missing teams-* parameter, or a webhook value that is not an https address with a host (an http one
    for a host other than 127.0.0.1, one with whitespace), stops with the AR-S-13 message before any file changes."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    for key in KEYS[:3]:
        setup.secret(SECRETS[key], f"https://example.test/{key}?sig={signature()}")
    assert teams_spike.main([]) == 1
    assert capsys.readouterr().out == "Missing secret /vigil/demo/teams-team-it-apps (AR-S-13).\n"
    for key in KEYS:
        setup.secret(SECRETS[key], f"https://example.test/{key}?sig={signature()}")
    setup.parameters.pop("/vigil/demo/teams-user-dbops-lead")
    setup.save()
    assert teams_spike.main([]) == 1
    assert capsys.readouterr().out == "Missing secret /vigil/demo/teams-user-dbops-lead (AR-S-13).\n"
    for value in (
        "",
        "not an address",
        "ftp://example.test/x",
        "https://",
        "https:///path",
        "http://example.test/x?sig=1",
        "http://127.0.0.2/x",
        "https://example.test/a b",
        " https://example.test/x",
        "https://[::1/x",
    ):
        setup.secret("teams-team-db-ops", value)
        assert teams_spike.main([]) == 1, value
        assert capsys.readouterr().out == "Secret /vigil/demo/teams-team-db-ops is not valid (AR-S-13).\n"
    setup.secret("teams-team-db-ops", "http://127.0.0.1:1/db-ops?sig=x")
    assert teams_spike.webhook_valid("http://127.0.0.1:1/db-ops?sig=x")
    assert setup.changed_files() == []


def test_teams_inputs(tmp_path, monkeypatch, capsys):
    """SB-T-06 not nine Teams delivery addresses, or one without its address, stops before any file changes."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    data = tmp_path / "root"
    shutil.copytree(common.ROOT / "docs" / "31-demo-data", data / "docs" / "31-demo-data")
    settings = data / "docs" / "31-demo-data" / "07-settings.yaml"
    original = settings.read_text()
    monkeypatch.setattr(common, "ROOT", data)
    settings.write_text(
        "".join(
            line for line in original.splitlines(True) if "subject_key: dbops-lead,         channel: teams" not in line
        )
    )
    assert teams_spike.main([]) == 1
    assert capsys.readouterr().out == "07-settings.yaml has not 9 Teams delivery addresses (DD-F-07).\n"
    settings.write_text(original.replace('address: "Techfinity Demo / IT Applications", ', ""))
    assert teams_spike.main([]) == 1
    assert capsys.readouterr().out == "docs/31-demo-data/07-settings.yaml has no delivery_addresses[6].address.\n"
    settings.write_text(original.replace('locator: "secret:teams-team-db-ops"', 'locator: "teams-team-db-ops"'))
    assert teams_spike.main([]) == 1
    assert capsys.readouterr().out == (
        "docs/31-demo-data/07-settings.yaml has no credential_references.cred-teams-db-ops.locator.\n"
    )
    assert setup.changed_files() == []
