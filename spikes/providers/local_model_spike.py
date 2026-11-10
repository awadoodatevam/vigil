"""SB-T-04 local model part: the GPU host spike (P-06 spec Section 6.7; AR-G-23, AR-G-32, AR-ASM-12).

Checks that vigil-llm has gemma4:12b, warms it up with the product's options, sends five SL-T-1 calls (each within
45 s) and checks that the whole model is on the GPU. Usage: local_model_spike.py --address {url}
"""

import json

import common
import request_data

USAGE = "Usage: local_model_spike.py --address {url}"
MODEL = "gemma4:12b"
TIMEOUT = 120
LIMIT_MS = 45000
NUM_CTX = 16384
MEASUREMENTS = 5
CHECKS = ["model-present", "warm-up"] + [f"chat-{k}" for k in range(1, MEASUREMENTS + 1)] + ["gpu-fit"]
MISSING = "gemma4:12b is not in vigil-llm; run deploy/gpu.sh start"


def chat_body(user_text):
    """AR-G-23 with the settings of local-llm (AR-K-09)."""
    return {
        "model": MODEL,
        "messages": [
            {"role": "system", "content": request_data.SYSTEM_SL_T_1},
            {"role": "user", "content": user_text},
        ],
        "format": request_data.SCHEMA_SL_T_1,
        "stream": False,
        "think": False,
        "keep_alive": -1,
        "options": {"temperature": 0, "seed": 7, "num_ctx": NUM_CTX, "num_predict": request_data.MAX_UNITS_SL_T_1},
    }


def find_model(parsed):
    entries = parsed.get("models") if isinstance(parsed, dict) else None
    for entry in entries if isinstance(entries, list) else []:
        if isinstance(entry, dict) and MODEL in (entry.get("name"), entry.get("model")):
            return entry
    return None


class LocalModelSpike:
    def __init__(self, base):
        self.base = base
        self.guard = common.Guard()
        self.run = common.Run("SB-T-04", self.guard)
        self.recorder = common.Recorder("local-llm", "http://${gpu_host_ip}:11434", self.guard)
        self.parts = request_data.render(request_data.Requests().parts_sl_t_1())
        self.lists = {
            name: [None] * MEASUREMENTS
            for name in (
                "durations_ms",
                "prompt_eval_counts",
                "eval_counts",
                "load_durations_ns",
                "prompt_eval_durations_ns",
                "eval_durations_ns",
                "cites_rb_db_01",
            )
        }
        self.ps = None

    def call(self, name, method, path, body=None):
        text = None if body is None else json.dumps(body)
        headers = [("Content-Type", "application/json")] if body is not None else []
        return common.call_http(name, self.base, method, path, headers, text, TIMEOUT)

    def execute(self):
        run = self.run
        tags = self.call("tags", "GET", "/api/tags")
        if not tags.answered or tags.status != 200 or find_model(tags.json()) is None:
            run.check("model-present", False, MISSING if tags.answered else f"{tags.error}; {MISSING}")
            run.not_run(*CHECKS[1:])
            return
        run.check("model-present", True)

        warm = self.call("warm-up", "POST", "/api/chat", chat_body("Warm-up."))
        run.check("warm-up", warm.answered and warm.status == 200, warm.error or f"status {warm.status}")

        for k in range(1, MEASUREMENTS + 1):
            self.measure(k)
        self.gpu_fit()

    def measure(self, k):
        name = f"chat-{k}"
        answer = self.call(name, "POST", "/api/chat", chat_body(f"Measurement {k} of {MEASUREMENTS}.\n\n{self.parts}"))
        self.recorder.record(answer)
        index = k - 1
        if not answer.answered:
            self.run.check(name, False, answer.error)
            return
        self.lists["durations_ms"][index] = answer.duration_ms
        parsed = answer.json()
        parsed = parsed if isinstance(parsed, dict) else {}
        for key, field in (
            ("prompt_eval_counts", "prompt_eval_count"),
            ("eval_counts", "eval_count"),
            ("load_durations_ns", "load_duration"),
            ("prompt_eval_durations_ns", "prompt_eval_duration"),
            ("eval_durations_ns", "eval_duration"),
        ):
            value = parsed.get(field)
            self.lists[key][index] = value if isinstance(value, int) and not isinstance(value, bool) else None
        content = (parsed.get("message") or {}).get("content") if isinstance(parsed.get("message"), dict) else None
        output = request_data.valid_output(content, request_data.SCHEMA_SL_T_1) if content is not None else None
        accepted = request_data.is_valid(output)
        self.lists["cites_rb_db_01"][index] = request_data.cites_rb_db_01(output) if accepted else None
        detail = ""
        if answer.status != 200:
            detail = f"status {answer.status}"
        elif parsed.get("done_reason") != "stop":
            detail = f"done_reason is {parsed.get('done_reason')}"
        elif not accepted:
            detail = f"output: {output if output is not None else 'no message.content'}"
        elif not (
            common.positive_integer(parsed.get("prompt_eval_count"))
            and common.positive_integer(parsed.get("eval_count"))
        ):
            detail = "prompt_eval_count and eval_count are not positive integers"
        elif answer.duration_ms > LIMIT_MS:
            detail = f"{answer.duration_ms} ms, more than {LIMIT_MS} ms (AR-G-32)"
        self.run.check(name, not detail, detail)

    def gpu_fit(self):
        answer = self.call("ps", "GET", "/api/ps")
        entry = find_model(answer.json()) if answer.answered and answer.status == 200 else None
        if entry is not None:
            self.ps = {key: entry.get(key) for key in ("name", "size", "size_vram")}
            if "context_length" in entry:
                self.ps["context_length"] = entry["context_length"]
        counts = [count for count in self.lists["prompt_eval_counts"] if count is not None]
        if not answer.answered:
            detail = answer.error
        elif entry is None:
            detail = f"{MODEL} is not loaded (/api/ps)"
        elif not isinstance(entry.get("size"), int) or entry.get("size_vram") != entry.get("size"):
            detail = f"size_vram {entry.get('size_vram')} is not size {entry.get('size')}"
        elif not counts:
            detail = "no prompt_eval_count"
        elif any(count >= NUM_CTX for count in counts):
            detail = f"a prompt_eval_count is not below {NUM_CTX}"
        else:
            detail = ""
        self.run.check("gpu-fit", not detail, detail)

    def own(self):
        durations = [value for value in self.lists["durations_ms"] if value is not None]
        own = {
            "durations_ms": self.lists["durations_ms"],
            "max_duration_ms": max(durations) if durations else None,
        }
        for key in (
            "prompt_eval_counts",
            "eval_counts",
            "load_durations_ns",
            "prompt_eval_durations_ns",
            "eval_durations_ns",
        ):
            own[key] = self.lists[key]
        own["ps"] = self.ps
        own["cites_rb_db_01"] = self.lists["cites_rb_db_01"]
        return own


def body(arguments):
    address = common.address_argument(arguments, USAGE, required=True)
    spike = LocalModelSpike(address)
    spike.recorder.clear()
    spike.execute()
    return spike.run.write(spike.own())


def main(arguments):
    return common.main_wrapper(body, arguments)


if __name__ == "__main__":
    common.exit_with(body)
