"""Tests of the Claude and OpenAI spike (P-06 spec Sections 6.5 and 7) against fake providers."""

import json
import secrets

import ai_spike
import request_data as rd
import spike_fakes
from spike_fakes import json_answer

SL_T_1_OUTPUT = {
    "likely_cause": "Transaction log archives filled /data on the Subscriber DB server.",
    "hypothesis_verdict": "confirms",
    "evidence_labels": ["E1", "E2", "E3"],
    "uncertainty": "Why the nightly purge did not run.",
    "runbooks_cited": ["RB-DB-01"],
}
SL_T_2_OUTPUT = {"summary": "The Subscriber DB server's /data is full; three services fail."}


def output_for(body):
    system = body.get("system") or body.get("instructions")
    return SL_T_1_OUTPUT if system == rd.SYSTEM_SL_T_1 else SL_T_2_OUTPUT


class FakeClaude:
    def __init__(self, key, stop_reason="end_turn", text=None):
        self.key = key
        self.stop_reason = stop_reason
        self.text = text

    def __call__(self, request):
        if request.headers.get("x-api-key") != self.key:
            return json_answer(401, {"type": "error", "error": {"type": "authentication_error"}})
        body = request.json()
        if body["max_tokens"] == 16:
            content = [{"type": "text", "text": '{"likely_cause": "Trans'}]
            return json_answer(
                200,
                {"content": content, "stop_reason": "max_tokens", "usage": {"input_tokens": 9000, "output_tokens": 16}},
            )
        text = self.text if self.text is not None else json.dumps(output_for(body))
        usage = {"input_tokens": 12000, "output_tokens": 301}
        content = [{"type": "thinking", "thinking": "..."}, {"type": "text", "text": text}]
        return json_answer(200, {"content": content, "stop_reason": self.stop_reason, "usage": usage})


class FakeOpenAI:
    def __init__(self, key):
        self.key = key

    def __call__(self, request):
        if request.headers.get("authorization") != f"Bearer {self.key}":
            return json_answer(
                401, {"error": {"message": "Incorrect API key provided", "type": "invalid_request_error"}}
            )
        body = request.json()
        if request.path == "/v1/embeddings":
            data = [{"object": "embedding", "index": i, "embedding": [0.001 * (i + 1)] * 1536} for i in (1, 0)]
            return json_answer(200, {"data": data, "usage": {"prompt_tokens": 47, "total_tokens": 47}})
        usage = {"input_tokens": 11000, "output_tokens": 420, "output_tokens_details": {"reasoning_tokens": 120}}
        if body["max_output_tokens"] == 16:
            return json_answer(
                200,
                {
                    "status": "incomplete",
                    "incomplete_details": {"reason": "max_output_tokens"},
                    "output": [],
                    "usage": usage,
                },
            )
        text = json.dumps(output_for(body))
        half = len(text) // 2
        output = [
            {"type": "reasoning", "summary": []},
            {
                "type": "message",
                "content": [{"type": "output_text", "text": text[:half]}, {"type": "output_text", "text": text[half:]}],
            },
        ]
        return json_answer(200, {"status": "completed", "output": output, "usage": usage})


def run(tmp_path, monkeypatch, provider, fake_class, **options):
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    key = ("sk-ant-" if provider == "claude" else "sk-") + secrets.token_urlsafe(24)
    setup.secret(provider, key)
    fake = fake_class(key, **options)
    with spike_fakes.FakeServer(fake, setup.clock, seconds=lambda request: 1.25) as server:
        code = ai_spike.main([provider, "--address", server.address])
    return setup, key, server, code


def test_claude_run(tmp_path, monkeypatch, capsys):
    """AR-G-20 the Claude run: bodies and headers, stop_reason and output checks, truncated, bad-key, cost."""
    setup, key, server, code = run(tmp_path, monkeypatch, "claude", FakeClaude)
    output = capsys.readouterr().out
    assert code == 0, output
    requests = server.requests
    assert [r.path for r in requests] == ["/v1/messages"] * 4
    for request in requests:
        assert request.method == "POST"
        assert request.headers["anthropic-version"] == "2023-06-01"
        assert request.headers["content-type"] == "application/json"
        assert request.headers["user-agent"] == "vigil-p06-spike"
        assert "authorization" not in request.headers
    assert [r.headers["x-api-key"] for r in requests] == [key, key, key, "invalid-key-p06-spike"]
    rendered_1 = rd.render(rd.Requests().parts_sl_t_1())
    body_1 = {
        "model": "claude-sonnet-5-5",
        "max_tokens": 1500,
        "system": rd.SYSTEM_SL_T_1,
        "messages": [{"role": "user", "content": rendered_1}],
        "output_config": {"format": {"type": "json_schema", "schema": rd.SCHEMA_SL_T_1}},
    }
    assert requests[0].body == json.dumps(body_1)
    parts_2 = rd.Requests().parts_sl_t_2(SL_T_1_OUTPUT["likely_cause"], SL_T_1_OUTPUT["uncertainty"])
    body_2 = {
        "model": "claude-sonnet-5-5",
        "max_tokens": 600,
        "system": rd.SYSTEM_SL_T_2,
        "messages": [{"role": "user", "content": rd.render(parts_2)}],
        "output_config": {"format": {"type": "json_schema", "schema": rd.SCHEMA_SL_T_2}},
    }
    assert requests[1].json() == body_2
    assert requests[2].json() == dict(body_1, max_tokens=16)
    assert requests[3].json() == body_2

    result = setup.result("claude")
    assert result["spike"] == "claude" and result["passed"] is True
    assert [check["name"] for check in result["checks"]] == [
        "sl-t-1-status",
        "sl-t-1-stop",
        "sl-t-1-output",
        "sl-t-1-usage",
        "sl-t-2-status",
        "sl-t-2-stop",
        "sl-t-2-output",
        "sl-t-2-usage",
        "truncated",
        "bad-key",
    ]
    assert result["calls"] == [
        {
            "call": "sl-t-1",
            "status": 200,
            "duration_ms": 1250,
            "input_units": 12000,
            "output_units": 301,
            "reasoning_units": None,
            "cost_usd": "0.027010",
            "cites_rb_db_01": True,
        },
        {
            "call": "sl-t-2",
            "status": 200,
            "duration_ms": 1250,
            "input_units": 12000,
            "output_units": 301,
            "reasoning_units": None,
            "cost_usd": "0.027010",
            "summary_length": len(SL_T_2_OUTPUT["summary"]),
        },
        {
            "call": "truncated",
            "status": 200,
            "duration_ms": 1250,
            "input_units": 9000,
            "output_units": 16,
            "reasoning_units": None,
            "cost_usd": "0.018160",
        },
        {
            "call": "bad-key",
            "status": 401,
            "duration_ms": 1250,
            "input_units": None,
            "output_units": None,
            "reasoning_units": None,
            "cost_usd": None,
        },
    ]
    assert setup.recording_files("claude") == [
        "0001-sl-t-1.json",
        "0002-sl-t-2.json",
        "0003-truncated.json",
        "0004-bad-key.json",
    ]
    recording = setup.recording("claude", "0001-sl-t-1.json")
    assert recording["request"]["url"] == "${endpoint.claude}/v1/messages"
    assert recording["request"]["headers"] == {
        "x-api-key": "<redacted>",
        "anthropic-version": "2023-06-01",
        "content-type": "application/json",
        "User-Agent": "vigil-p06-spike",
    }
    assert recording["request"]["body"] == json.dumps(body_1)
    assert setup.recording("claude", "0004-bad-key.json")["request"]["headers"]["x-api-key"] == "<redacted>"


def test_claude_refusal(tmp_path, monkeypatch, capsys):
    """AR-G-20 a refused answer fails sl-t-1-stop and sl-t-1-output; no SL-T-2; bad-key uses the truncated body."""
    setup, key, server, code = run(tmp_path, monkeypatch, "claude", FakeClaude, stop_reason="refusal", text="I can't.")
    output = capsys.readouterr().out
    assert code == 1
    checks = {check["name"]: check for check in setup.result("claude")["checks"]}
    assert checks["sl-t-1-stop"]["detail"] == "stop_reason is refusal"
    assert checks["sl-t-1-output"]["detail"] == "not JSON"
    assert checks["sl-t-1-status"]["passed"] and checks["sl-t-1-usage"]["passed"]
    for name in ("sl-t-2-status", "sl-t-2-stop", "sl-t-2-output", "sl-t-2-usage"):
        assert checks[name] == {"name": name, "passed": False, "detail": "not run"}
    assert checks["truncated"]["passed"] and checks["bad-key"]["passed"]
    assert len(server.requests) == 3
    assert server.requests[2].json() == server.requests[1].json()
    assert "sl-t-1-stop: FAILED (stop_reason is refusal)" in output
    calls = setup.result("claude")["calls"]
    assert [call["call"] for call in calls] == ["sl-t-1", "truncated", "bad-key"]
    assert calls[0]["cites_rb_db_01"] is None


def test_cost():
    """AR-G-20 cost by AR-G-14: half-up to 6 places, as a string with 6 decimals; null without units."""
    providers = ai_spike.providers()
    assert ai_spike.cost(providers["claude"], 1234, 567) == "0.008138"
    assert ai_spike.cost(providers["openai-embed"], 25, 0) == "0.000001"
    assert ai_spike.cost(providers["openai-embed"], 24, 0) == "0.000000"
    assert ai_spike.cost(providers["claude"], None, 5) is None


def test_openai_run(tmp_path, monkeypatch, capsys):
    """AR-G-21 the OpenAI Responses run: bodies, the output text joined from several output_text items, status."""
    setup, key, server, code = run(tmp_path, monkeypatch, "openai", FakeOpenAI)
    output = capsys.readouterr().out
    assert code == 0, output
    requests = server.requests
    assert [r.path for r in requests] == ["/v1/responses"] * 3 + ["/v1/embeddings", "/v1/responses"]
    assert [r.headers["authorization"] for r in requests] == [f"Bearer {key}"] * 4 + ["Bearer invalid-key-p06-spike"]
    for request in requests:
        assert request.headers["content-type"] == "application/json"
        assert request.headers["user-agent"] == "vigil-p06-spike"
    body_1 = {
        "model": "gpt-6.1-sol",
        "instructions": rd.SYSTEM_SL_T_1,
        "input": rd.render(rd.Requests().parts_sl_t_1()),
        "max_output_tokens": 1500,
        "text": {"format": {"type": "json_schema", "name": "sl_t_1", "schema": rd.SCHEMA_SL_T_1, "strict": True}},
    }
    assert requests[0].body == json.dumps(body_1)
    parts_2 = rd.Requests().parts_sl_t_2(SL_T_1_OUTPUT["likely_cause"], SL_T_1_OUTPUT["uncertainty"])
    body_2 = {
        "model": "gpt-6.1-sol",
        "instructions": rd.SYSTEM_SL_T_2,
        "input": rd.render(parts_2),
        "max_output_tokens": 600,
        "text": {"format": {"type": "json_schema", "name": "sl_t_2", "schema": rd.SCHEMA_SL_T_2, "strict": True}},
    }
    assert requests[1].json() == body_2
    assert requests[2].json() == dict(body_1, max_output_tokens=16)
    assert requests[4].json() == body_2
    result = setup.result("openai")
    assert result["passed"] is True
    assert [check["name"] for check in result["checks"]] == [
        "sl-t-1-status",
        "sl-t-1-complete",
        "sl-t-1-output",
        "sl-t-1-usage",
        "sl-t-2-status",
        "sl-t-2-complete",
        "sl-t-2-output",
        "sl-t-2-usage",
        "truncated",
        "embed-status",
        "embed-vectors",
        "embed-usage",
        "bad-key",
    ]
    calls = {call["call"]: call for call in result["calls"]}
    assert calls["sl-t-1"]["reasoning_units"] == 120
    assert calls["sl-t-1"]["cost_usd"] == "0.026200"
    assert calls["sl-t-1"]["cites_rb_db_01"] is True
    assert calls["sl-t-2"]["summary_length"] == len(SL_T_2_OUTPUT["summary"])
    assert setup.recording_files("openai") == [
        "0001-sl-t-1.json",
        "0002-sl-t-2.json",
        "0003-truncated.json",
        "0004-embed.json",
        "0005-bad-key.json",
    ]
    assert setup.recording("openai", "0001-sl-t-1.json")["request"]["headers"] == {
        "Authorization": "Bearer <redacted>",
        "Content-Type": "application/json",
        "User-Agent": "vigil-p06-spike",
    }


def test_openai_incomplete(tmp_path, monkeypatch, capsys):
    """AR-G-21 an answer whose status is not completed fails the complete check, naming incomplete_details.reason."""

    class Incomplete(FakeOpenAI):
        def __call__(self, request):
            status, body, content_type = super().__call__(request)
            if request.path == "/v1/responses" and isinstance(body, dict) and body.get("status") == "completed":
                body = dict(body, status="incomplete", incomplete_details={"reason": "content_filter"})
            return status, body, content_type

    setup, _, _, code = run(tmp_path, monkeypatch, "openai", Incomplete)
    capsys.readouterr()
    assert code == 1
    checks = {check["name"]: check for check in setup.result("openai")["checks"]}
    assert checks["sl-t-1-complete"]["detail"] == "status is incomplete (content_filter)"
    assert checks["sl-t-1-output"]["passed"]


def test_openai_embeddings(tmp_path, monkeypatch, capsys):
    """AR-G-22 the embeddings body (the storm's and PI-1001's search texts), vectors by index, usage, cost."""
    setup, _, server, code = run(tmp_path, monkeypatch, "openai", FakeOpenAI)
    assert code == 0, capsys.readouterr().out
    requests = rd.Requests()
    embed = server.requests[3]
    assert embed.json() == {
        "model": "text-embedding-3-small",
        "input": [requests.storm_search_text(), dict(requests.past_search_texts())["PI-1001"]],
    }
    result = setup.result("openai")
    calls = {call["call"]: call for call in result["calls"]}
    assert calls["embed"] == {
        "call": "embed",
        "status": 200,
        "duration_ms": 1250,
        "input_units": 47,
        "output_units": 0,
        "reasoning_units": None,
        "cost_usd": "0.000001",
    }
    assert setup.recording("openai", "0004-embed.json")["request"]["url"] == "${endpoint.openai}/v1/embeddings"


def test_openai_embeddings_wrong(tmp_path, monkeypatch, capsys):
    """AR-G-22 a vector of another length and a missing prompt_tokens fail embed-vectors and embed-usage."""

    class Short(FakeOpenAI):
        def __call__(self, request):
            if request.path == "/v1/embeddings":
                data = [{"index": i, "embedding": [0.1] * 768} for i in (0, 1)]
                return json_answer(200, {"data": data, "usage": {}})
            return super().__call__(request)

    setup, _, _, code = run(tmp_path, monkeypatch, "openai", Short)
    capsys.readouterr()
    assert code == 1
    checks = {check["name"]: check for check in setup.result("openai")["checks"]}
    assert checks["embed-status"]["passed"]
    assert checks["embed-vectors"]["detail"] == "a vector does not have 1536 numbers"
    assert checks["embed-usage"]["detail"] == "usage.prompt_tokens is not a positive integer"


def test_arguments_and_key(tmp_path, monkeypatch, capsys):
    """AR-G-20 usage line and exit 2; a missing or invalid key stops before any file changes."""
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    for arguments in ([], ["gemini"], ["claude", "--address"], ["openai", "x"]):
        assert ai_spike.main(arguments) == 2
        assert capsys.readouterr().out == "Usage: ai_spike.py claude|openai [--address {url}]\n"
    assert ai_spike.main(["claude"]) == 1
    assert capsys.readouterr().out == "Missing secret /vigil/demo/claude (AR-S-13).\n"
    setup.secret("openai", "sk-has a space")
    assert ai_spike.main(["openai"]) == 1
    assert capsys.readouterr().out == "Secret /vigil/demo/openai is not valid (AR-S-13).\n"
    assert setup.changed_files() == []
