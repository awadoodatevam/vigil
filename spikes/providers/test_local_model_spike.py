"""Tests of the local model spike (P-06 spec Sections 6.7 and 7) against a fake Ollama."""

import json

import local_model_spike
import request_data as rd
import spike_fakes
from spike_fakes import json_answer

OUTPUT = {
    "likely_cause": "Archived transaction logs filled /data.",
    "hypothesis_verdict": "confirms",
    "evidence_labels": ["E1", "E2"],
    "uncertainty": "Why the purge did not run.",
    "runbooks_cited": ["RB-DB-01"],
}
MODEL_SIZE = 9_800_000_000


class FakeOllama:
    """/api/tags, /api/chat and /api/ps of vigil-llm; per-chat overrides by measurement number."""

    def __init__(self, models=("gemma4:12b",), size_vram=MODEL_SIZE, overrides=None):
        self.models = models
        self.size_vram = size_vram
        self.overrides = overrides or {}
        self.chats = 0

    def __call__(self, request):
        if request.path == "/api/tags":
            return json_answer(200, {"models": [{"name": name, "model": name} for name in self.models]})
        if request.path == "/api/ps":
            entry = {"name": "gemma4:12b", "model": "gemma4:12b", "size": MODEL_SIZE, "size_vram": self.size_vram}
            return json_answer(200, {"models": [dict(entry, context_length=16384)]})
        body = request.json()
        answer = {
            "model": "gemma4:12b",
            "message": {"role": "assistant", "content": json.dumps(OUTPUT)},
            "done": True,
            "done_reason": "stop",
            "prompt_eval_count": 9100,
            "eval_count": 180,
            "load_duration": 50_000_000,
            "prompt_eval_duration": 4_000_000_000,
            "eval_duration": 6_000_000_000,
        }
        if body["messages"][1]["content"] != "Warm-up.":
            self.chats += 1
            answer.update(self.overrides.get(self.chats, {}))
        return json_answer(200, answer)


def seconds(request):
    """The fake clock: a measured chat takes 20 s, measurement 3 takes 45.5 s when its body asks for it."""
    if request.path != "/api/chat":
        return 0.1
    content = json.loads(request.body)["messages"][1]["content"]
    if content.startswith("Measurement 3 of 5.") and SLOW.get("on"):
        return 45.5
    return 20.0


SLOW = {}


def run(tmp_path, monkeypatch, fake, slow=False):
    SLOW["on"] = slow
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    with spike_fakes.FakeServer(fake, setup.clock, seconds=seconds) as server:
        code = local_model_spike.main(["--address", server.address])
    return setup, server, code


def test_local_model_run(tmp_path, monkeypatch, capsys):
    """SB-T-04 the warm-up first, not recorded; five measured calls; gpu-fit; result file and recordings."""
    setup, server, code = run(tmp_path, monkeypatch, FakeOllama())
    output = capsys.readouterr().out
    assert code == 0, output
    assert [(r.method, r.path) for r in server.requests] == (
        [("GET", "/api/tags")] + [("POST", "/api/chat")] * 6 + [("GET", "/api/ps")]
    )
    assert server.requests[1].json()["messages"][1]["content"] == "Warm-up."
    result = setup.result("SB-T-04")
    assert result["spike"] == "SB-T-04" and result["passed"] is True
    assert [check["name"] for check in result["checks"]] == local_model_spike.CHECKS
    assert result["durations_ms"] == [20000] * 5
    assert result["max_duration_ms"] == 20000
    assert result["prompt_eval_counts"] == [9100] * 5
    assert result["eval_counts"] == [180] * 5
    assert result["load_durations_ns"] == [50_000_000] * 5
    assert result["prompt_eval_durations_ns"] == [4_000_000_000] * 5
    assert result["eval_durations_ns"] == [6_000_000_000] * 5
    assert result["cites_rb_db_01"] == [True] * 5
    assert result["ps"] == {"name": "gemma4:12b", "size": MODEL_SIZE, "size_vram": MODEL_SIZE, "context_length": 16384}
    assert setup.recording_files("local-llm") == [f"{k:04d}-chat-{k}.json" for k in range(1, 6)]
    recording = setup.recording("local-llm", "0002-chat-2.json")
    assert recording["request"]["url"] == "http://${gpu_host_ip}:11434/api/chat"
    assert recording["duration_ms"] == 20000


def test_chat_body(tmp_path, monkeypatch, capsys):
    """AR-G-23 the chat body: system and user messages, the SL-T-1 schema, stream and think off, local-llm options."""
    setup, server, code = run(tmp_path, monkeypatch, FakeOllama())
    capsys.readouterr()
    assert code == 0
    parts = rd.render(rd.Requests().parts_sl_t_1())
    chats = [r for r in server.requests if r.path == "/api/chat"]
    for k, request in enumerate(chats):
        user = "Warm-up." if k == 0 else f"Measurement {k} of 5.\n\n{parts}"
        assert request.json() == {
            "model": "gemma4:12b",
            "messages": [{"role": "system", "content": rd.SYSTEM_SL_T_1}, {"role": "user", "content": user}],
            "format": rd.SCHEMA_SL_T_1,
            "stream": False,
            "think": False,
            "keep_alive": -1,
            "options": {"temperature": 0, "seed": 7, "num_ctx": 16384, "num_predict": 1500},
        }
        assert request.headers["content-type"] == "application/json"
        assert request.headers["user-agent"] == "vigil-p06-spike"


def test_slow_call(tmp_path, monkeypatch, capsys):
    """AR-G-32 a call slower than 45 s by the fake's clock fails its chat check."""
    setup, _, code = run(tmp_path, monkeypatch, FakeOllama(), slow=True)
    capsys.readouterr()
    assert code == 1
    result = setup.result("SB-T-04")
    checks = {check["name"]: check for check in result["checks"]}
    assert checks["chat-3"]["detail"] == "45500 ms, more than 45000 ms (AR-G-32)"
    assert all(checks[f"chat-{k}"]["passed"] for k in (1, 2, 4, 5))
    assert result["max_duration_ms"] == 45500


def test_length_and_gpu_fit(tmp_path, monkeypatch, capsys):
    """SB-T-04 done_reason length fails chat-k; gpu-fit fails when size_vram is below size."""
    fake = FakeOllama(size_vram=MODEL_SIZE - 1, overrides={2: {"done_reason": "length", "message": {"content": "{"}}})
    setup, _, code = run(tmp_path, monkeypatch, fake)
    capsys.readouterr()
    assert code == 1
    result = setup.result("SB-T-04")
    checks = {check["name"]: check for check in result["checks"]}
    assert checks["chat-2"]["detail"] == "done_reason is length"
    assert checks["gpu-fit"]["detail"] == f"size_vram {MODEL_SIZE - 1} is not size {MODEL_SIZE}"
    assert result["cites_rb_db_01"] == [True, None, True, True, True]


def test_model_missing(tmp_path, monkeypatch, capsys):
    """SB-T-04 a vigil-llm without gemma4:12b stops the spike after model-present; the other checks are not run."""
    setup, server, code = run(tmp_path, monkeypatch, FakeOllama(models=("embeddinggemma:latest",)))
    output = capsys.readouterr().out
    assert code == 1
    assert len(server.requests) == 1
    result = setup.result("SB-T-04")
    assert result["checks"][0] == {
        "name": "model-present",
        "passed": False,
        "detail": "gemma4:12b is not in vigil-llm; run deploy/gpu.sh start",
    }
    assert all(check["detail"] == "not run" for check in result["checks"][1:])
    assert result["durations_ms"] == [None] * 5
    assert result["ps"] is None
    assert setup.recording_files("local-llm") == []
    assert "model-present: FAILED (gemma4:12b is not in vigil-llm; run deploy/gpu.sh start)" in output


def test_usage(tmp_path, monkeypatch, capsys):
    """AR-G-23 the address is required: the usage line and exit 2."""
    spike_fakes.prepare(tmp_path, monkeypatch)
    assert local_model_spike.main([]) == 2
    assert capsys.readouterr().out == "Usage: local_model_spike.py --address {url}\n"
