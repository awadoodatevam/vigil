"""Tests of the Jira spike (P-06 spec Sections 6.4 and 7) against a fake Jira."""

import base64
import datetime
import json
import urllib.parse

import jira_spike
import pytest
import spike_fakes
from spike_fakes import json_answer

SITE = "https://techfinity-noc.atlassian.net"
TRANSITIONS = [
    {"id": "11", "name": "Back to the queue", "to": {"name": "To Do"}},
    {"id": "21", "name": "Start", "to": {"name": "In Progress"}},
    {"id": "41", "name": "Close it", "to": {"name": "Done"}},
]


class FakeJira:
    """A Jira Cloud site with one project: issues, label search with lag, comments and transitions."""

    def __init__(self, secret, account, lag=0):
        self.secret = secret
        self.account = account
        self.lag = lag
        self.searches = 0
        self.issues = {}
        self.leak = None

    def personal(self):
        return {
            "accountId": self.account,
            "emailAddress": self.secret["email"],
            "displayName": "NOC Spike",
            "avatarUrls": {"48x48": "https://avatar.example.test/a.png"},
            "self": f"{SITE}/rest/api/3/user?accountId={urllib.parse.quote(self.account, safe='')}",
        }

    def __call__(self, request):
        path, _, query = request.path.partition("?")
        if path == "/rest/api/3/myself":
            body = self.personal()
            body["note"] = f"Signed in as {self.secret['email'].upper()} ({self.account})"
            if self.leak:
                body["echo"] = self.leak
            return json_answer(200, body)
        if path == "/rest/api/3/issue" and request.method == "POST":
            key = f"NOC-{len(self.issues) + 1}"
            fields = request.json()["fields"]
            self.issues[key] = {"fields": fields, "status": "To Do", "comments": []}
            return json_answer(201, {"id": "10001", "key": key, "self": f"{SITE}/rest/api/3/issue/10001"})
        if path == "/rest/api/3/search/jql":
            self.searches += 1
            jql = urllib.parse.parse_qs(query)["jql"][0]
            found = [
                {"key": key, "fields": {"status": {"name": issue["status"]}}}
                for key, issue in self.issues.items()
                if f'labels="{issue["fields"]["labels"][0]}"' in jql
            ]
            return json_answer(200, {"issues": found if self.searches > self.lag else [], "isLast": True})
        parts = path.split("/")
        key = parts[5]
        issue = self.issues[key]
        if len(parts) == 6:
            fields = urllib.parse.parse_qs(query)["fields"][0]
            if fields == "description":
                return json_answer(200, {"key": key, "fields": {"description": issue["fields"]["description"]}})
            return json_answer(200, {"key": key, "fields": {"status": {"name": issue["status"]}}})
        if parts[6] == "comment":
            issue["comments"].append(request.json()["body"])
            return json_answer(201, {"id": "1", "author": self.personal(), "body": request.json()["body"]})
        if parts[6] == "transitions" and request.method == "GET":
            return json_answer(200, {"transitions": TRANSITIONS})
        if parts[6] == "transitions":
            target = next(t for t in TRANSITIONS if t["id"] == request.json()["transition"]["id"])
            issue["status"] = target["to"]["name"]
            return 204, "", None
        return json_answer(404, {"errorMessages": ["not found"]})


def start(tmp_path, monkeypatch, expires_on=None, lag=0):
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    today = datetime.datetime.now(datetime.timezone.utc).date()
    expires_on = expires_on or (today + datetime.timedelta(days=300)).isoformat()
    secret = spike_fakes.jira_secret_value(expires_on)
    setup.secret("jira", json.dumps(secret))
    fake = FakeJira(secret, spike_fakes.account_id(), lag)
    server = spike_fakes.FakeServer(fake, setup.clock, seconds=lambda request: 0.4)
    return setup, secret, fake, server


def test_jira_run(tmp_path, monkeypatch, capsys):
    """SB-T-07 the Jira run: calls, bodies and headers as Section 6.4, the label, the result file and recordings."""
    setup, secret, fake, server = start(tmp_path, monkeypatch)
    with server:
        code = jira_spike.main(["--address", server.address])
    output = capsys.readouterr().out
    assert code == 0, output
    result = setup.result("SB-T-07")
    run_id = result["run_id"]
    label = f"vigil-spike-{run_id}"
    link = f"https://demo.vigil.solviclabs.com/incidents/spike-{run_id}"
    assert result["spike"] == "SB-T-07" and result["passed"] is True
    assert [check["name"] for check in result["checks"]] == jira_spike.CHECKS
    assert all(check == {"name": check["name"], "passed": True, "detail": ""} for check in result["checks"])
    assert result["issue_key"] == "NOC-1"
    assert result["search"] == {"attempts": 1, "seconds_after_create": 0.4}
    assert result["token_days_left"] == 300
    assert result["token_expires_on"] == secret["expires_on"]
    assert output.splitlines()[-1] == f"result: {setup.results / 'P-06-SB-T-07.json'}"
    assert "myself: passed" in output.splitlines()

    requests = server.requests
    jql = urllib.parse.quote(f'project=NOC AND labels="{label}"', safe="")
    assert [(r.method, r.path) for r in requests] == [
        ("GET", "/rest/api/3/myself"),
        ("POST", "/rest/api/3/issue"),
        ("GET", f"/rest/api/3/search/jql?jql={jql}&fields=status"),
        ("GET", "/rest/api/3/issue/NOC-1?fields=description"),
        ("POST", "/rest/api/3/issue/NOC-1/comment"),
        ("GET", "/rest/api/3/issue/NOC-1?fields=status"),
        ("GET", "/rest/api/3/issue/NOC-1/transitions"),
        ("POST", "/rest/api/3/issue/NOC-1/transitions"),
        ("GET", "/rest/api/3/issue/NOC-1?fields=status"),
        ("GET", "/rest/api/3/issue/NOC-1/transitions"),
        ("POST", "/rest/api/3/issue/NOC-1/transitions"),
        ("GET", "/rest/api/3/issue/NOC-1?fields=status"),
    ]
    authorization = "Basic " + base64.b64encode(f"{secret['email']}:{secret['api_token']}".encode()).decode()
    for request in requests:
        assert request.headers["authorization"] == authorization
        assert request.headers["accept"] == "application/json"
        assert request.headers["user-agent"] == "vigil-p06-spike"
        assert ("content-type" in request.headers) is (request.method == "POST")
        if request.method == "POST":
            assert request.headers["content-type"] == "application/json"
    assert requests[1].json() == {
        "fields": {
            "project": {"key": "NOC"},
            "issuetype": {"name": "Task"},
            "summary": f"SB-T-07 spike {run_id}",
            "description": jira_spike.adf(
                [
                    f"SB-T-07 spike run {run_id}.",
                    "Created by the P-06 Jira spike; nothing else uses this issue.",
                    f"Link: {link}",
                ]
            ),
            "labels": [label],
        }
    }
    assert requests[4].json() == {"body": jira_spike.adf(["Comment of the SB-T-07 spike.", f"Link: {link}"])}
    assert requests[7].json() == {"transition": {"id": "41"}}
    assert requests[10].json() == {"transition": {"id": "11"}}
    assert fake.issues["NOC-1"]["status"] == "To Do"

    names = [
        "myself",
        "create",
        "search-1",
        "read-description",
        "comment",
        "status-1",
        "transitions-1",
        "resolve",
        "status-2",
        "transitions-2",
        "reopen",
        "status-3",
    ]
    assert setup.recording_files("jira") == [f"{n:04d}-{name}.json" for n, name in enumerate(names, start=1)]
    create = setup.recording("jira", "0002-create.json")
    assert create["request"]["url"] == "${endpoint.jira}/rest/api/3/issue"
    assert create["request"]["headers"] == {
        "Authorization": "Basic <redacted>",
        "Accept": "application/json",
        "Content-Type": "application/json",
        "User-Agent": "vigil-p06-spike",
    }
    assert json.loads(create["request"]["body"]) == requests[1].json()
    assert create["response"]["status"] == 201
    assert create["response"]["headers"] == {"Content-Type": "application/json"}
    assert json.loads(create["response"]["body"])["self"] == "${endpoint.jira}/rest/api/3/issue/10001"
    resolve = setup.recording("jira", "0008-resolve.json")
    assert resolve["response"] == {"status": 204, "headers": {}, "body": ""}
    assert setup.recording("jira", "0003-search-1.json")["request"]["url"] == (
        f"${{endpoint.jira}}/rest/api/3/search/jql?jql={jql}&fields=status"
    )


def test_adf():
    """AR-K-15 ADF of a line with a link, a line without one and an empty line."""
    assert jira_spike.adf(["Plain line.", "Link: https://demo.example.test/incidents/1", ""]) == {
        "type": "doc",
        "version": 1,
        "content": [
            {"type": "paragraph", "content": [{"type": "text", "text": "Plain line."}]},
            {
                "type": "paragraph",
                "content": [
                    {"type": "text", "text": "Link: "},
                    {
                        "type": "text",
                        "text": "https://demo.example.test/incidents/1",
                        "marks": [{"type": "link", "attrs": {"href": "https://demo.example.test/incidents/1"}}],
                    },
                ],
            },
            {"type": "paragraph"},
        ],
    }


def test_transitions_by_target_name(tmp_path, monkeypatch, capsys):
    """AR-K-14 the transitions are found by their target status name, not by their own name or order."""
    setup, _, fake, server = start(tmp_path, monkeypatch)
    with server:
        code = jira_spike.main(["--address", server.address])
    assert code == 0, capsys.readouterr().out
    posts = [r.json() for r in server.requests if r.path.endswith("/transitions") and r.method == "POST"]
    assert posts == [{"transition": {"id": "41"}}, {"transition": {"id": "11"}}]


def test_lag_rule_found_at_fourth(tmp_path, monkeypatch, capsys):
    """AR-K-14 an issue found at the fourth search passes, with the seconds from the create answer."""
    setup, _, fake, server = start(tmp_path, monkeypatch, lag=3)
    with server:
        code = jira_spike.main(["--address", server.address])
    assert code == 0, capsys.readouterr().out
    result = setup.result("SB-T-07")
    assert result["search"] == {"attempts": 4, "seconds_after_create": 1.6}
    assert [r.path.split("?")[0] for r in server.requests].count("/rest/api/3/search/jql") == 4
    files = setup.recording_files("jira")
    assert [f for f in files if "search" in f] == [
        "0003-search-1.json",
        "0004-search-2.json",
        "0005-search-3.json",
        "0006-search-4.json",
    ]


def test_lag_rule_not_found(tmp_path, monkeypatch, capsys):
    """AR-K-14 an issue not found after four searches fails search, with seconds_after_create null."""
    setup, _, fake, server = start(tmp_path, monkeypatch, lag=10)
    with server:
        code = jira_spike.main(["--address", server.address])
    output = capsys.readouterr().out
    assert code == 1
    result = setup.result("SB-T-07")
    assert result["passed"] is False
    assert result["search"] == {"attempts": 4, "seconds_after_create": None}
    search = next(check for check in result["checks"] if check["name"] == "search")
    assert search == {"name": "search", "passed": False, "detail": "NOC-1 not found in 4 searches"}
    assert "search: FAILED (NOC-1 not found in 4 searches)" in output
    assert fake.searches == 4
    assert all(check["passed"] for check in result["checks"] if check["name"] != "search")


def test_token_expiry(tmp_path, monkeypatch, capsys):
    """SB-T-07 token-expiry: a token that expires today passes, one that expired yesterday fails the check."""
    today = datetime.datetime.now(datetime.timezone.utc).date()
    setup, _, _, server = start(tmp_path, monkeypatch, expires_on=today.isoformat())
    with server:
        assert jira_spike.main(["--address", server.address]) == 0
    result = setup.result("SB-T-07")
    assert result["checks"][0] == {"name": "token-expiry", "passed": True, "detail": ""}
    assert result["token_days_left"] == 0

    yesterday = (today - datetime.timedelta(days=1)).isoformat()
    setup, _, _, server = start(tmp_path / "second", monkeypatch, expires_on=yesterday)
    with server:
        assert jira_spike.main(["--address", server.address]) == 1
    result = setup.result("SB-T-07")
    assert result["checks"][0] == {
        "name": "token-expiry",
        "passed": False,
        "detail": f"the token expired on {yesterday}",
    }
    assert result["token_days_left"] == -1
    assert all(check["passed"] for check in result["checks"][1:])
    capsys.readouterr()


@pytest.mark.parametrize(
    "value",
    [
        json.dumps({"email": "a@example.test", "api_token": "t"}),
        json.dumps({"email": "a@example.test", "api_token": "t", "expires_on": 20271105}),
        json.dumps({"email": "a@example.test", "api_token": "t", "expires_on": "2027-02-30"}),
        json.dumps({"email": "a@example.test", "api_token": "t", "expires_on": "05.11.2027"}),
        json.dumps({"email": "a@example.test", "api_token": 7, "expires_on": "2027-11-05"}),
        json.dumps(["a@example.test", "t", "2027-11-05"]),
        "a@example.test:t",
    ],
)
def test_secret_not_valid(tmp_path, monkeypatch, capsys, value):
    """SB-T-07 a jira secret that is not the documented JSON stops with the "not valid" message, no file changed."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    setup.secret("jira", value)
    (setup.recordings / "jira").mkdir(parents=True)
    (setup.recordings / "jira" / "0001-myself.json").write_text("{}")
    assert jira_spike.main([]) == 1
    assert capsys.readouterr().out == "Secret /vigil/demo/jira is not valid (AR-S-13).\n"
    assert setup.changed_files() == ["jira", "jira/0001-myself.json"]


def test_secret_missing_and_usage(tmp_path, monkeypatch, capsys):
    """SB-T-07 a missing secret stops with its message and exit 1; wrong arguments print the usage line, exit 2."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    assert jira_spike.main([]) == 1
    assert capsys.readouterr().out == "Missing secret /vigil/demo/jira (AR-S-13).\n"
    assert setup.changed_files() == []
    for arguments in (["--address"], ["--port", "1"], ["--address", "x", "y"]):
        assert jira_spike.main(arguments) == 2
        assert capsys.readouterr().out == "Usage: jira_spike.py [--address {url}]\n"
