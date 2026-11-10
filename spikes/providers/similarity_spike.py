"""SB-T-03 runtime part: the local embedding spike (P-06 spec Section 6.6; AR-G-24, SL-ASM-04, DD-ASM-04).

Embeds the 50 past incidents' search texts in one batched call and the storm's search text alone, twice each, on
vigil-embed; checks that the same texts give the same vectors and that PI-1001 is the storm's best match with 1.00.
Usage: similarity_spike.py --address {url}
"""

import json
import math
from decimal import Decimal

import common
import request_data

USAGE = "Usage: similarity_spike.py --address {url}"
TIMEOUT = 30
CALLS = ["past-1", "storm-1", "past-2", "storm-2"]


def cosine(a, b):
    """Dot product divided by the product of the norms, in Python float."""
    dot = sum(x * y for x, y in zip(a, b, strict=True))
    return dot / (math.sqrt(sum(x * x for x in a)) * math.sqrt(sum(y * y for y in b)))


def rounded(similarity):
    """Half-up to 2 places through Decimal(repr(similarity))."""
    return common.half_up(Decimal(repr(similarity)), 2)


def ranking(storm_vector, past):
    """(key, rounded score) of every past incident, the highest score first, ties by key."""
    scores = [(key, rounded(cosine(storm_vector, vector))) for key, vector in past]
    return sorted(scores, key=lambda item: (-item[1], item[0]))


class SimilaritySpike:
    def __init__(self, base):
        self.base = base
        self.guard = common.Guard()
        self.run = common.Run("SB-T-03", self.guard)
        self.recorder = common.Recorder("local-embed", "${endpoint.local_embedding}", self.guard)
        requests = request_data.Requests()
        self.storm_text = requests.storm_search_text()
        self.past = requests.past_search_texts()
        self.vectors = {}
        self.dimensions = None
        self.calls = []

    def embed(self, name, texts):
        body = json.dumps({"model": "embeddinggemma", "input": texts, "keep_alive": -1})
        answer = common.call_http(
            name, self.base, "POST", "/api/embed", [("Content-Type", "application/json")], body, TIMEOUT
        )
        self.recorder.record(answer)
        parsed = answer.json() if answer.answered else None
        parsed = parsed if isinstance(parsed, dict) else {}
        count = parsed.get("prompt_eval_count")
        self.calls.append(
            {
                "call": name,
                "prompt_eval_count": count if answer.answered else None,
                "duration_ms": answer.duration_ms if answer.answered else None,
            }
        )
        if not answer.answered:
            self.run.check(name, False, answer.error)
            return
        embeddings = parsed.get("embeddings")
        detail = ""
        if answer.status != 200:
            detail = f"status {answer.status}"
        elif not isinstance(embeddings, list) or len(embeddings) != len(texts):
            detail = f"{len(embeddings) if isinstance(embeddings, list) else 0} vectors for {len(texts)} texts"
        elif not all(isinstance(vector, list) and vector for vector in embeddings):
            detail = "a vector is empty or not a list"
        else:
            lengths = {len(vector) for vector in embeddings}
            if self.dimensions is None and len(lengths) == 1:
                self.dimensions = lengths.pop()
                lengths = {self.dimensions}
            if lengths != {self.dimensions}:
                detail = f"vector lengths {sorted(lengths)}, expected {self.dimensions}"
            elif not common.positive_integer(count):
                detail = "prompt_eval_count is not a positive integer"
        if self.run.check(name, not detail, detail):
            self.vectors[name] = embeddings

    def execute(self):
        past_texts = [text for _, text in self.past]
        for name in CALLS:
            self.embed(name, past_texts if name.startswith("past") else [self.storm_text])
        run = self.run
        if all(name in self.vectors for name in CALLS):
            differing = [
                label
                for label, first, second in (("past", "past-1", "past-2"), ("storm", "storm-1", "storm-2"))
                if self.vectors[first] != self.vectors[second]
            ]
            run.check("same-vectors", not differing, f"the {' and '.join(differing)} vectors differ")
        else:
            run.not_run("same-vectors")
        self.scores = []
        if "past-1" in self.vectors and "storm-1" in self.vectors:
            past = [(key, vector) for (key, _), vector in zip(self.past, self.vectors["past-1"], strict=True)]
            ranked = ranking(self.vectors["storm-1"][0], past)
            self.scores = [{"past_incident_key": key, "score": f"{score:.2f}"} for key, score in ranked[:5]]
            best_key, best_score = ranked[0]
            passed = best_key == "PI-1001" and best_score == Decimal("1.00")
            run.check("best-match", passed, f"best match {best_key} with {best_score:.2f}")
        else:
            run.not_run("best-match")

    def own(self):
        return {"dimensions": self.dimensions, "calls": self.calls, "scores": self.scores}


def body(arguments):
    address = common.address_argument(arguments, USAGE, required=True)
    spike = SimilaritySpike(address)
    spike.recorder.clear()
    spike.execute()
    return spike.run.write(spike.own())


def main(arguments):
    return common.main_wrapper(body, arguments)


if __name__ == "__main__":
    common.exit_with(body)
