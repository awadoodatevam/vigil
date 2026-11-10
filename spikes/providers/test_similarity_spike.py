"""Tests of the local embedding spike (P-06 spec Sections 6.6 and 7) against a fake Ollama with fixed vectors."""

import hashlib
import json
from decimal import Decimal

import request_data as rd
import similarity_spike
import spike_fakes
from spike_fakes import json_answer

REQUESTS = rd.Requests()
STORM = REQUESTS.storm_search_text()
PAST = dict(REQUESTS.past_search_texts())


def vector(text):
    """A fixed vector of 16 numbers for a text."""
    digest = hashlib.sha256(text.encode()).digest()
    return [round(byte / 255 - 0.5, 6) for byte in digest[:16]]


class FakeOllama:
    """/api/embed with fixed vectors; changes are {call number: function(vectors) -> vectors}."""

    def __init__(self, vectors=vector, changes=None, statuses=None):
        self.vectors = vectors
        self.changes = changes or {}
        self.statuses = statuses or {}
        self.count = 0

    def __call__(self, request):
        self.count += 1
        if self.count in self.statuses:
            return json_answer(self.statuses[self.count], {"error": "model failed"})
        texts = request.json()["input"]
        embeddings = [self.vectors(text) for text in texts]
        if self.count in self.changes:
            embeddings = self.changes[self.count](embeddings)
        return json_answer(
            200, {"model": "embeddinggemma", "embeddings": embeddings, "prompt_eval_count": 31 * len(texts)}
        )


def run(tmp_path, monkeypatch, fake):
    setup = spike_fakes.prepare(tmp_path, monkeypatch)
    with spike_fakes.FakeServer(fake, setup.clock, seconds=lambda request: 0.2) as server:
        code = similarity_spike.main(["--address", server.address])
    return setup, server, code


def test_similarity_run(tmp_path, monkeypatch, capsys):
    """SB-T-03 equal answers pass same-vectors; PI-1001 with 1.00 passes best-match; result file and recordings."""
    setup, server, code = run(tmp_path, monkeypatch, FakeOllama())
    output = capsys.readouterr().out
    assert code == 0, output
    result = setup.result("SB-T-03")
    assert result["spike"] == "SB-T-03" and result["passed"] is True
    assert [check["name"] for check in result["checks"]] == [
        "past-1",
        "storm-1",
        "past-2",
        "storm-2",
        "same-vectors",
        "best-match",
    ]
    assert result["dimensions"] == 16
    assert result["calls"] == [
        {"call": "past-1", "prompt_eval_count": 1550, "duration_ms": 200},
        {"call": "storm-1", "prompt_eval_count": 31, "duration_ms": 200},
        {"call": "past-2", "prompt_eval_count": 1550, "duration_ms": 200},
        {"call": "storm-2", "prompt_eval_count": 31, "duration_ms": 200},
    ]
    assert len(result["scores"]) == 5
    assert result["scores"][0] == {"past_incident_key": "PI-1001", "score": "1.00"}
    scores = [Decimal(entry["score"]) for entry in result["scores"]]
    assert scores == sorted(scores, reverse=True)
    assert setup.recording_files("local-embed") == [
        "0001-past-1.json",
        "0002-storm-1.json",
        "0003-past-2.json",
        "0004-storm-2.json",
    ]
    recording = setup.recording("local-embed", "0001-past-1.json")
    assert recording["request"]["url"] == "${endpoint.local_embedding}/api/embed"
    assert recording["request"]["headers"] == {"Content-Type": "application/json", "User-Agent": "vigil-p06-spike"}
    assert "best-match: passed" in output


def test_embed_bodies(tmp_path, monkeypatch, capsys):
    """AR-G-24 the four calls in order: the 50 past texts batched, then the storm text alone, keep_alive -1."""
    setup, server, code = run(tmp_path, monkeypatch, FakeOllama())
    capsys.readouterr()
    assert code == 0
    past = {"model": "embeddinggemma", "input": list(PAST.values()), "keep_alive": -1}
    storm = {"model": "embeddinggemma", "input": [STORM], "keep_alive": -1}
    assert [(r.method, r.path) for r in server.requests] == [("POST", "/api/embed")] * 4
    assert [r.json() for r in server.requests] == [past, storm, past, storm]
    assert len(past["input"]) == 50
    assert server.requests[0].body == json.dumps(past)
    for request in server.requests:
        assert request.headers["content-type"] == "application/json"


def changed(embeddings):
    embeddings = [list(item) for item in embeddings]
    embeddings[-1][3] += 0.000001
    return embeddings


def test_same_vectors_fails(tmp_path, monkeypatch, capsys):
    """SB-T-03 a changed number in past-2 or in storm-2 fails same-vectors."""
    setup, _, code = run(tmp_path, monkeypatch, FakeOllama(changes={3: changed}))
    assert code == 1
    checks = {check["name"]: check for check in setup.result("SB-T-03")["checks"]}
    assert checks["same-vectors"]["detail"] == "the past vectors differ"
    assert checks["best-match"]["passed"]

    setup, _, code = run(tmp_path / "storm", monkeypatch, FakeOllama(changes={4: changed}))
    assert code == 1
    checks = {check["name"]: check for check in setup.result("SB-T-03")["checks"]}
    assert checks["same-vectors"]["detail"] == "the storm vectors differ"
    capsys.readouterr()


def test_tie_goes_to_first_key(tmp_path, monkeypatch, capsys):
    """SB-T-03 a tie on the rounded score goes to the alphabetically first key; another best key fails best-match."""

    def tie(text):
        return vector(STORM) if text == PAST["PI-1007"] else vector(text)

    setup, _, code = run(tmp_path, monkeypatch, FakeOllama(vectors=tie))
    assert code == 0, capsys.readouterr().out
    scores = setup.result("SB-T-03")["scores"]
    assert scores[:2] == [
        {"past_incident_key": "PI-1001", "score": "1.00"},
        {"past_incident_key": "PI-1007", "score": "1.00"},
    ]

    def other(embeddings):
        embeddings = [list(item) for item in embeddings]
        embeddings[0], embeddings[3] = vector("another text"), vector(STORM)
        return embeddings

    setup, _, code = run(tmp_path / "other", monkeypatch, FakeOllama(changes={1: other, 3: other}))
    assert code == 1
    checks = {check["name"]: check for check in setup.result("SB-T-03")["checks"]}
    assert checks["best-match"]["detail"] == "best match PI-1004 with 1.00"
    capsys.readouterr()


def test_rounding_half_up():
    """SB-T-03 scores round half-up through Decimal(repr(score)): 0.995 is 1.00; ties sort by key."""
    assert similarity_spike.rounded(0.995) == Decimal("1.00")
    assert similarity_spike.rounded(0.985) == Decimal("0.99")
    assert similarity_spike.rounded(0.99499) == Decimal("0.99")
    assert similarity_spike.cosine([1.0, 0.0], [2.0, 0.0]) == 1.0
    ranked = similarity_spike.ranking(
        [1.0, 0.0], [("PI-1003", [1.0, 0.0]), ("PI-1002", [3.0, 0.0]), ("PI-1001", [0.0, 1.0])]
    )
    assert ranked == [("PI-1002", Decimal("1.00")), ("PI-1003", Decimal("1.00")), ("PI-1001", Decimal("0.00"))]


def test_failed_call(tmp_path, monkeypatch, capsys):
    """AR-G-24 when past-1 fails, the other calls are still made and same-vectors and best-match are "not run"."""
    setup, server, code = run(tmp_path, monkeypatch, FakeOllama(statuses={1: 500}))
    capsys.readouterr()
    assert code == 1
    assert len(server.requests) == 4
    result = setup.result("SB-T-03")
    checks = {check["name"]: check for check in result["checks"]}
    assert checks["past-1"]["detail"] == "status 500"
    assert checks["storm-1"]["passed"] and checks["past-2"]["passed"] and checks["storm-2"]["passed"]
    assert checks["same-vectors"]["detail"] == "not run"
    assert checks["best-match"]["detail"] == "not run"
    assert result["scores"] == []
    assert result["calls"][0] == {"call": "past-1", "prompt_eval_count": None, "duration_ms": 200}


def test_usage(tmp_path, monkeypatch, capsys):
    """AR-G-24 the address is required: the usage line and exit 2."""
    spike_fakes.prepare(tmp_path, monkeypatch)
    for arguments in ([], ["--address"], ["http://127.0.0.1:1"]):
        assert similarity_spike.main(arguments) == 2
        assert capsys.readouterr().out == "Usage: similarity_spike.py --address {url}\n"
