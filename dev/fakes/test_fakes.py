"""Tests of the dev fakes (P-26 spec Sections 6.5 and 6.8): each role runs in-process on a free port."""

import ast
import json
import sys
import threading
import urllib.error
import urllib.request
from pathlib import Path

import fakes
import pytest

HERE = Path(__file__).resolve().parent
# TestEmbedding.vector of the Java tests for these texts, numbers 0 to 3 (computed with the Java code).
JAVA_VECTORS = {
    "disk full on srv-db-01": [0.087777525, -0.1099794, -0.42983902, 0.32250705],
    "Ünïcode": [-0.3206607, 0.011589227, 0.1805066, 0.049217977],
}
HYPOTHESIS_PART = (
    "### Hypothesis (Service context)\n"
    "Root-cause hypothesis: Subscriber DB server (srv-db-01), kind server, criticality critical; "
    "6 other affected components depend on it"
)
E1_TEXT = "Disk usage of /data: /data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)"


def render(*parts):
    """A user message as AiConnector.render writes it."""
    return "\n\n".join(parts)


def runbook(key):
    return f"### {key} (Runbook text)\nTitle of {key}\n\nStep one.\n\n### not a header\nStep two."


SL_T_1_SCHEMA = {
    "type": "object",
    "properties": {
        "likely_cause": {"type": "string"},
        "hypothesis_verdict": {"type": "string"},
        "evidence_labels": {"type": "array"},
        "uncertainty": {"type": "string"},
        "runbooks_cited": {"type": "array"},
    },
}
SL_T_2_SCHEMA = {"type": "object", "properties": {"summary": {"type": "string"}}}
OTHER_SCHEMA = {"type": "object", "properties": {"answer": {"type": "string"}}}


class Running:
    def __init__(self, role):
        self.lines = []
        self.server = fakes.make_server(role, 0, host="127.0.0.1", log=self.lines.append)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_address[1]}"

    def call(self, method, path, body=None, headers=None):
        data = None if body is None else (body if isinstance(body, bytes) else json.dumps(body).encode())
        request = urllib.request.Request(self.base + path, data=data, method=method, headers=headers or {})
        if data is not None:
            request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=10) as response:
                raw = response.read()
                return response.status, (json.loads(raw) if raw else None)
        except urllib.error.HTTPError as error:
            raw = error.read()
            return error.code, (json.loads(raw) if raw else None)

    def stop(self):
        self.server.shutdown()
        self.server.server_close()


@pytest.fixture
def start():
    started = []

    def run(role):
        fake = Running(role)
        started.append(fake)
        return fake

    yield run
    for fake in started:
        fake.stop()


def test_image_and_imports():
    """BP-R-04 the dev fakes image is based on ubuntu:24.04 and the program imports only the standard library."""
    froms = [line.split()[1] for line in (HERE / "Dockerfile").read_text().splitlines() if line.startswith("FROM ")]
    assert len(froms) == 1 and froms[0].startswith("ubuntu:24.04@sha256:")
    tree = ast.parse((HERE / "fakes.py").read_text())
    modules = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            modules |= {alias.name.split(".")[0] for alias in node.names}
        elif isinstance(node, ast.ImportFrom):
            modules.add(node.module.split(".")[0])
    assert modules <= set(sys.stdlib_module_names), modules - set(sys.stdlib_module_names)


def test_calls_are_recorded_without_headers(start):
    """BP-R-04 GET /_fakes/calls lists method, path and body of each call, no header, and not the /_fakes/ calls."""
    fake = start("teams")
    assert fake.call("POST", "/workflows/teams-team-noc-l1?x=1", b'{"a": 1}', {"Authorization": "Bearer t"})[0] == 202
    assert fake.call("GET", "/_fakes/calls")[0] == 200
    status, calls = fake.call("GET", "/_fakes/calls")
    assert status == 200
    assert len(calls) == 1
    assert set(calls[0]) == {"at", "method", "path", "body"}
    assert calls[0]["method"] == "POST"
    assert calls[0]["path"] == "/workflows/teams-team-noc-l1?x=1"
    assert calls[0]["body"] == '{"a": 1}'
    assert calls[0]["at"].endswith("Z")
    assert "Bearer" not in json.dumps(calls)
    assert len(fake.lines) == 3
    assert fake.lines[0].startswith("POST /workflows/teams-team-noc-l1?x=1 202 ") and fake.lines[0].endswith(" ms")


@pytest.mark.parametrize("role", fakes.ROLES)
def test_unknown_path(start, role):
    """BP-R-04 an unknown path answers 404 {"error": "not found"} for every role."""
    fake = start(role)
    assert fake.call("GET", "/nothing/here") == (404, {"error": "not found"})
    assert fake.call("GET", "/_fakes/other") == (404, {"error": "not found"})
    if role != "jira":
        assert fake.call("GET", "/_fakes/issues") == (404, {"error": "not found"})


def adf(text):
    return {
        "type": "doc",
        "version": 1,
        "content": [{"type": "paragraph", "content": [{"type": "text", "text": text}]}],
    }


def create(fake, label, summary="Disk full"):
    body = {
        "fields": {
            "project": {"key": "NOC"},
            "issuetype": {"name": "Task"},
            "summary": summary,
            "description": adf("line"),
            "labels": [label],
        }
    }
    return fake.call("POST", "/rest/api/3/issue", body)


def test_jira(start):
    """BP-R-04 the Jira fake keeps issues, labels, comments and statuses as JiraConnector calls them."""
    fake = start("jira")
    status, created = create(fake, "vigil-incident-a")
    assert status == 201 and created["key"] == "NOC-1"
    assert create(fake, "vigil-incident-b")[1]["key"] == "NOC-2"
    jql = "jql=project%3DNOC%20AND%20labels%3D%22vigil-incident-b%22&fields=status"
    status, found = fake.call("GET", f"/rest/api/3/search/jql?{jql}")
    assert status == 200
    assert [issue["key"] for issue in found["issues"]] == ["NOC-2"]
    assert found["issues"][0]["fields"]["status"]["name"] == "To Do"
    none = "jql=project%3DNOC%20AND%20labels%3D%22vigil-incident-c%22&fields=status"
    assert fake.call("GET", f"/rest/api/3/search/jql?{none}")[1]["issues"] == []
    assert fake.call("GET", "/rest/api/3/issue/NOC-1?fields=status")[1]["fields"]["status"]["name"] == "To Do"
    assert fake.call("GET", "/rest/api/3/issue/NOC-9?fields=status")[0] == 404
    assert fake.call("POST", "/rest/api/3/issue/NOC-1/comment", {"body": adf("comment")})[0] == 201
    status, transitions = fake.call("GET", "/rest/api/3/issue/NOC-1/transitions")
    assert status == 200
    assert [(t["id"], t["to"]["name"]) for t in transitions["transitions"]] == [
        ("11", "To Do"),
        ("21", "In Progress"),
        ("31", "Done"),
    ]
    assert fake.call("POST", "/rest/api/3/issue/NOC-1/transitions", {"transition": {"id": "31"}}) == (204, None)
    assert fake.call("GET", "/rest/api/3/issue/NOC-1?fields=status")[1]["fields"]["status"]["name"] == "Done"
    assert fake.call("POST", "/rest/api/3/issue/NOC-1/transitions", {"transition": {"id": "21"}}) == (204, None)
    assert fake.call("POST", "/rest/api/3/issue/NOC-1/transitions", {"transition": {"id": "99"}})[0] == 400
    status, issues = fake.call("GET", "/_fakes/issues")
    assert status == 200
    assert issues[0]["key"] == "NOC-1" and issues[0]["status"] == "In Progress"
    assert issues[0]["labels"] == ["vigil-incident-a"]
    assert issues[0]["comments"] == [adf("comment")]
    assert issues[0]["description"] == adf("line")
    assert issues[1]["status"] == "To Do" and issues[1]["comments"] == []


def test_teams(start):
    """BP-R-04 the Teams fake answers 202 with an empty body to a POST on any path and records the body."""
    fake = start("teams")
    assert fake.call("POST", "/teams-user-dbops-lead", {"type": "message"}) == (202, None)
    assert fake.call("POST", "/any/other/path", {"type": "message"}) == (202, None)
    assert [call["path"] for call in fake.call("GET", "/_fakes/calls")[1]] == [
        "/teams-user-dbops-lead",
        "/any/other/path",
    ]


def full_message(e1=E1_TEXT):
    return render(
        "### Member alerts (Alert data)\n- disk_full | srv-db-01 | zabbix | Disk full | fired 2026-11-12T10:00:00Z",
        HYPOTHESIS_PART,
        "### Customer impact (Service context)\nnone identified",
        f"### E1 (Diagnostic output)\n{e1}",
        "### E2 (Diagnostic output)\nLargest folder under /data: /data/txlog_archive (8.5 GiB)",
        runbook("RB-API-01"),
        runbook("RB-DB-01"),
    )


def claude_request(schema, user):
    return {
        "model": "claude-test",
        "max_tokens": 1500,
        "system": "system text",
        "messages": [{"role": "user", "content": user}],
        "output_config": {"format": {"type": "json_schema", "schema": schema}},
    }


def claude_output(fake, schema, user):
    status, answer = fake.call("POST", "/v1/messages", claude_request(schema, user))
    assert status == 200, answer
    assert answer["type"] == "message" and answer["stop_reason"] == "end_turn"
    text = answer["content"][0]["text"]
    assert answer["usage"] == {"input_tokens": -(-len("system text" + user) // 4), "output_tokens": -(-len(text) // 4)}
    return json.loads(text)


def accepted(output, labels, supplied):
    """The SL-T-1 checks of OutputChecks (PM-M17-03) with a hypothesis: the reasons, empty when accepted."""
    reasons = []
    if output["hypothesis_verdict"] not in ("confirms", "disputes"):
        reasons.append("does not say whether it confirms or disputes the hypothesis")
    if not output["evidence_labels"]:
        reasons.append("no evidence reference")
    elif set(output["evidence_labels"]) - set(labels):
        reasons.append("refers to evidence that is not on the incident")
    if not output["runbooks_cited"]:
        reasons.append("no runbook cited")
    elif set(output["runbooks_cited"]) - set(supplied):
        reasons.append("cites a runbook that was not supplied")
    if not output["likely_cause"].strip() or not output["uncertainty"].strip():
        reasons.append("no likely cause or uncertainty")
    return reasons


def test_sl_t_1(start):
    """BP-R-04 the Claude fake answers SL-T-1 from the hypothesis, E1 and the supplied runbooks, passing PM-M17-03."""
    fake = start("claude")
    output = claude_output(fake, SL_T_1_SCHEMA, full_message())
    assert output == {
        "likely_cause": f"Subscriber DB server (srv-db-01) is the likely cause: {E1_TEXT}.",
        "hypothesis_verdict": "confirms",
        "evidence_labels": ["E1"],
        "uncertainty": "Whether other components are affected independently.",
        "runbooks_cited": ["RB-DB-01"],
    }
    assert accepted(output, ["E1", "E2"], ["RB-API-01", "RB-DB-01"]) == []
    long = "x" * 250
    assert claude_output(fake, SL_T_1_SCHEMA, full_message(long))["likely_cause"].endswith(": " + "x" * 200 + ".")
    first = render(HYPOTHESIS_PART, "### E1 (Diagnostic output)\ntext", runbook("RB-USSD-01"), runbook("RB-API-01"))
    assert claude_output(fake, SL_T_1_SCHEMA, first)["runbooks_cited"] == ["RB-USSD-01"]


def test_sl_t_1_without_parts(start):
    """BP-R-04 without E1 and runbooks it is refused as the product refuses it; no hypothesis: not_applicable."""
    fake = start("claude")
    output = claude_output(fake, SL_T_1_SCHEMA, render("### Member alerts (Alert data)\n- a", HYPOTHESIS_PART))
    assert output["likely_cause"] == "Subscriber DB server (srv-db-01) is the likely cause."
    assert output["evidence_labels"] == [] and output["runbooks_cited"] == []
    assert accepted(output, [], []) == ["no evidence reference", "no runbook cited"]
    output = claude_output(fake, SL_T_1_SCHEMA, render("### E1 (Diagnostic output)\ntext", runbook("RB-DB-01")))
    assert output["likely_cause"] == "No hypothesis was supplied."
    assert output["hypothesis_verdict"] == "not_applicable"
    assert output["evidence_labels"] == ["E1"]


def sl_t_2_message(alerts, investigation=True, hypothesis=True):
    parts = ["### Title (Incident record)\nSubscriber DB server: Disk full — 3 services affected"]
    if investigation:
        parts.append(
            "### Investigation (Incident record)\nLikely cause: The disk is full.\nUncertainty: Why it filled."
        )
    parts.append(f"### Alerts (Alert data)\n{alerts}")
    parts.append("### Customer impact (Service context)\nnone identified")
    if hypothesis:
        parts.append(HYPOTHESIS_PART)
    parts.append("### E1 (Diagnostic output)\n/data is 100% full")
    return render(*parts)


def test_sl_t_2(start):
    """BP-R-04 the Claude fake answers SL-T-2 with a summary built from the parts, at most 600 characters."""
    fake = start("claude")
    alerts = "126 alerts of 7 types: disk_full, db_write_errors"
    output = claude_output(fake, SL_T_2_SCHEMA, sl_t_2_message(alerts))
    assert output == {"summary": f"Subscriber DB server: {alerts}. The disk is full."}
    output = claude_output(fake, SL_T_2_SCHEMA, sl_t_2_message(alerts, investigation=False, hypothesis=False))
    assert output == {"summary": f"{alerts}. No AI investigation was accepted."}
    output = claude_output(fake, SL_T_2_SCHEMA, sl_t_2_message("y" * 700))
    assert output["summary"] == ("Subscriber DB server: " + "y" * 700)[:600]


def test_other_requests_are_refused(start):
    """BP-R-04 a request with another output schema answers 400 in the provider's error form."""
    claude = start("claude")
    status, answer = claude.call("POST", "/v1/messages", claude_request(OTHER_SCHEMA, "text"))
    assert status == 400 and answer["type"] == "error" and answer["error"]["type"] == "invalid_request_error"
    openai = start("openai")
    status, answer = openai.call("POST", "/v1/responses", {"input": "text", "text": {"format": {"schema": {}}}})
    assert status == 400 and set(answer["error"]) == {"message", "type", "param", "code"}
    ollama = start("ollama-llm")
    status, answer = ollama.call("POST", "/api/chat", {"messages": [], "format": SL_T_1_SCHEMA})
    assert status == 400 and isinstance(answer["error"], str)


def test_openai(start):
    """BP-R-04 the OpenAI fake answers the Responses call in its recorded form and embeddings as ollama-embed."""
    fake = start("openai")
    request = {
        "model": "gpt-test",
        "instructions": "system",
        "input": full_message(),
        "max_output_tokens": 1500,
        "text": {"format": {"type": "json_schema", "name": "sl_t_1", "schema": SL_T_1_SCHEMA, "strict": True}},
    }
    status, answer = fake.call("POST", "/v1/responses", request)
    assert status == 200 and answer["status"] == "completed" and answer["object"] == "response"
    message = answer["output"][0]
    assert message["type"] == "message" and message["content"][0]["type"] == "output_text"
    output = json.loads(message["content"][0]["text"])
    assert output["runbooks_cited"] == ["RB-DB-01"] and output["evidence_labels"] == ["E1"]
    usage = answer["usage"]
    assert usage["input_tokens"] == -(-len("system" + full_message()) // 4)
    assert usage["total_tokens"] == usage["input_tokens"] + usage["output_tokens"]
    texts = list(JAVA_VECTORS)
    status, answer = fake.call("POST", "/v1/embeddings", {"model": "embed-test", "input": texts})
    assert status == 200
    assert [item["index"] for item in answer["data"]] == [0, 1]
    for item, text in zip(answer["data"], texts, strict=True):
        assert item["embedding"] == fakes.vector(text)
    used = sum(-(-len(text) // 4) for text in texts)
    assert answer["usage"] == {"prompt_tokens": used, "total_tokens": used}


def test_ollama_llm(start):
    """BP-R-04 the local model fake answers /api/chat in Ollama's form with the schema read from format."""
    fake = start("ollama-llm")
    request = {
        "model": "gemma-test",
        "messages": [{"role": "system", "content": "system"}, {"role": "user", "content": sl_t_2_message("2 alerts")}],
        "format": SL_T_2_SCHEMA,
        "stream": False,
    }
    status, answer = fake.call("POST", "/api/chat", request)
    assert status == 200 and answer["done"] is True and answer["done_reason"] == "stop"
    assert json.loads(answer["message"]["content"]) == {"summary": "Subscriber DB server: 2 alerts. The disk is full."}
    assert answer["prompt_eval_count"] == -(-len("system" + sl_t_2_message("2 alerts")) // 4)
    assert answer["eval_count"] == -(-len(answer["message"]["content"]) // 4)


def test_ollama_embed(start):
    """BP-R-04 the embedding fake gives the vectors of TestEmbedding (equal texts, equal vectors) and token counts."""
    fake = start("ollama-embed")
    texts = [*JAVA_VECTORS, "disk full on srv-db-01"]
    status, answer = fake.call("POST", "/api/embed", {"model": "embeddinggemma", "input": texts, "keep_alive": -1})
    assert status == 200
    vectors = answer["embeddings"]
    assert len(vectors) == 3 and all(len(v) == 64 for v in vectors)
    for vector, text in zip(vectors, texts, strict=True):
        for number, expected in zip(vector[:4], JAVA_VECTORS[text], strict=False):
            assert abs(number - expected) < 1e-7
    assert vectors[0] == vectors[2]
    assert all(-0.5 <= n <= 0.5 for n in vectors[0])
    assert answer["prompt_eval_count"] == sum(-(-len(text) // 4) for text in texts)
    assert fake.call("POST", "/api/embed", {"model": "x", "input": "one"})[1]["embeddings"] == [fakes.vector("one")]
    assert fake.call("POST", "/api/embed", {"model": "x", "input": []})[0] == 400


def test_role_is_checked():
    """BP-R-04 an unknown FAKE_ROLE is refused."""
    with pytest.raises(ValueError):
        fakes.Fake("smtp")
