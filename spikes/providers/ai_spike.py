"""The Claude and OpenAI spike (P-06 spec Section 6.5; AR-G-20 to AR-G-22, AR-G-14).

Sends the SL-T-1 and SL-T-2 structured-output requests, a truncated request, (OpenAI) the embeddings call, and a call
with an invalid key, and checks the answers and their usage fields. Usage: ai_spike.py claude|openai [--address {url}]
"""

import copy
import json
from decimal import Decimal

import common
import request_data
import yaml

USAGE = "Usage: ai_spike.py claude|openai [--address {url}]"
BASES = {"claude": "https://api.anthropic.com", "openai": "https://api.openai.com"}
PLACEHOLDERS = {"claude": "${endpoint.claude}", "openai": "${endpoint.openai}"}
CHAT_TIMEOUT = 90
EMBED_TIMEOUT = 30
INVALID_KEY = "invalid-key-p06-spike"


def providers():
    """ai_provider_configurations of 07-settings.yaml by provider key."""
    with open(common.DATA / "07-settings.yaml", encoding="utf-8") as file:
        settings = yaml.safe_load(file)
    return {entry["provider_key"]: entry for entry in settings["ai_provider_configurations"]}


def cost(provider, input_units, output_units):
    """AR-G-14: (input x input price + output x output price) / 1,000,000, half-up to 6 places, as a string."""
    if not (isinstance(input_units, int) and isinstance(output_units, int)):
        return None
    total = (
        Decimal(input_units) * Decimal(str(provider["price_input_per_million"]))
        + Decimal(output_units) * Decimal(str(provider["price_output_per_million"]))
    ) / Decimal(1000000)
    return f"{common.half_up(total, 6):.6f}"


def integer_or_none(value):
    return value if isinstance(value, int) and not isinstance(value, bool) else None


class AiSpike:
    def __init__(self, provider, base, key):
        self.provider = provider
        self.base = base
        self.key = key
        self.guard = common.Guard(secrets=[key])
        self.run = common.Run(provider, self.guard)
        self.recorder = common.Recorder(provider, PLACEHOLDERS[provider], self.guard)
        self.configurations = providers()
        self.requests = request_data.Requests()
        self.calls = []

    # ---------------------------------------------------------------- wire formats
    def headers(self, key):
        if self.provider == "claude":
            return [("x-api-key", key), ("anthropic-version", "2023-06-01"), ("content-type", "application/json")]
        return [("Authorization", f"Bearer {key}"), ("Content-Type", "application/json")]

    def chat_body(self, task, parts, max_units):
        system, schema, name = {
            "sl-t-1": (request_data.SYSTEM_SL_T_1, request_data.SCHEMA_SL_T_1, "sl_t_1"),
            "sl-t-2": (request_data.SYSTEM_SL_T_2, request_data.SCHEMA_SL_T_2, "sl_t_2"),
        }[task]
        model = self.configurations[self.provider]["model_name"]
        rendered = request_data.render(parts)
        if self.provider == "claude":
            return {
                "model": model,
                "max_tokens": max_units,
                "system": system,
                "messages": [{"role": "user", "content": rendered}],
                "output_config": {"format": {"type": "json_schema", "schema": schema}},
            }
        return {
            "model": model,
            "instructions": system,
            "input": rendered,
            "max_output_tokens": max_units,
            "text": {"format": {"type": "json_schema", "name": name, "schema": schema, "strict": True}},
        }

    def truncate(self, body):
        body = copy.deepcopy(body)
        body["max_tokens" if self.provider == "claude" else "max_output_tokens"] = 16
        return body

    def path(self):
        return "/v1/messages" if self.provider == "claude" else "/v1/responses"

    def send(self, name, path, body, key=None, timeout=CHAT_TIMEOUT):
        answer = common.call_http(
            name, self.base, "POST", path, self.headers(key or self.key), json.dumps(body), timeout
        )
        self.recorder.record(answer)
        return answer

    # ---------------------------------------------------------------- answers
    def output_text(self, parsed):
        """Claude: the first text content block; OpenAI: every output_text of the message items, concatenated."""
        if not isinstance(parsed, dict):
            return None
        if self.provider == "claude":
            for block in parsed.get("content") or []:
                if isinstance(block, dict) and block.get("type") == "text":
                    return block.get("text")
            return None
        texts = [
            item.get("text", "")
            for output in parsed.get("output") or []
            if isinstance(output, dict) and output.get("type") == "message"
            for item in output.get("content") or []
            if isinstance(item, dict) and item.get("type") == "output_text"
        ]
        return "".join(texts) if texts else None

    def units(self, parsed, embeddings=False):
        """(input units, output units, reasoning units) by AR-G-14; None where the answer has no usage."""
        usage = parsed.get("usage") if isinstance(parsed, dict) else None
        if not isinstance(usage, dict):
            return None, None, None
        if embeddings:
            prompt = integer_or_none(usage.get("prompt_tokens"))
            return prompt, (0 if prompt is not None else None), None
        reasoning = None
        if self.provider == "openai":
            details = usage.get("output_tokens_details")
            if isinstance(details, dict):
                reasoning = integer_or_none(details.get("reasoning_tokens"))
        return integer_or_none(usage.get("input_tokens")), integer_or_none(usage.get("output_tokens")), reasoning

    def note(self, answer, embeddings=False, **extra):
        parsed = answer.json() if answer.answered else None
        input_units, output_units, reasoning = self.units(parsed, embeddings)
        provider = self.configurations["openai-embed" if embeddings else self.provider]
        entry = {
            "call": answer.call,
            "status": answer.status,
            "duration_ms": answer.duration_ms,
            "input_units": input_units,
            "output_units": output_units,
            "reasoning_units": reasoning,
            "cost_usd": cost(provider, input_units, output_units),
        }
        entry.update(extra)
        self.calls.append(entry)

    def task_checks(self, task, answer, schema):
        """The four checks of an SL-T-1 or SL-T-2 call; returns the accepted output or None."""
        run = self.run
        second = "stop" if self.provider == "claude" else "complete"
        names = [f"{task}-status", f"{task}-{second}", f"{task}-output", f"{task}-usage"]
        if not answer.answered:
            run.check(names[0], False, answer.error)
            run.not_run(*names[1:])
            return None
        run.check(names[0], answer.status == 200, f"status {answer.status}")
        parsed = answer.json()
        parsed_dict = parsed if isinstance(parsed, dict) else {}
        if self.provider == "claude":
            stop = parsed_dict.get("stop_reason")
            run.check(names[1], stop == "end_turn", f"stop_reason is {stop}")
        else:
            status = parsed_dict.get("status")
            reason = (parsed_dict.get("incomplete_details") or {}).get("reason")
            detail = f"status is {status}" + (f" ({reason})" if reason else "")
            run.check(names[1], status == "completed", detail)
        text = self.output_text(parsed)
        result = request_data.valid_output(text, schema) if text is not None else "no output text"
        accepted = request_data.is_valid(result)
        run.check(names[2], accepted, "" if accepted else result)
        input_units, output_units, _ = self.units(parsed)
        run.check(
            names[3],
            common.positive_integer(input_units) and common.positive_integer(output_units),
            "usage.input_tokens and usage.output_tokens are not positive integers",
        )
        return result if accepted else None

    # ---------------------------------------------------------------- the run
    def execute(self):
        run = self.run
        requests = self.requests
        body_1 = self.chat_body("sl-t-1", requests.parts_sl_t_1(), request_data.MAX_UNITS_SL_T_1)
        answer = self.send("sl-t-1", self.path(), body_1)
        output_1 = self.task_checks("sl-t-1", answer, request_data.SCHEMA_SL_T_1)
        self.note(answer, cites_rb_db_01=request_data.cites_rb_db_01(output_1) if output_1 else None)

        body_2 = None
        second = "stop" if self.provider == "claude" else "complete"
        if output_1 is None:
            run.not_run("sl-t-2-status", f"sl-t-2-{second}", "sl-t-2-output", "sl-t-2-usage")
        else:
            parts = requests.parts_sl_t_2(output_1["likely_cause"], output_1["uncertainty"])
            body_2 = self.chat_body("sl-t-2", parts, request_data.MAX_UNITS_SL_T_2)
            answer = self.send("sl-t-2", self.path(), body_2)
            output_2 = self.task_checks("sl-t-2", answer, request_data.SCHEMA_SL_T_2)
            self.note(answer, summary_length=len(output_2["summary"]) if output_2 else None)

        truncated_body = self.truncate(body_1)
        answer = self.send("truncated", self.path(), truncated_body)
        self.note(answer)
        if not answer.answered:
            run.check("truncated", False, answer.error)
        else:
            parsed = answer.json() if isinstance(answer.json(), dict) else {}
            if self.provider == "claude":
                value = parsed.get("stop_reason")
                passed = answer.status == 200 and value == "max_tokens"
                run.check("truncated", passed, f"status {answer.status}, stop_reason {value}")
            else:
                value = parsed.get("status")
                passed = answer.status == 200 and value == "incomplete"
                run.check("truncated", passed, f"status {answer.status}, status field {value}")

        if self.provider == "openai":
            self.embed()

        answer = self.send("bad-key", self.path(), body_2 or truncated_body, key=INVALID_KEY)
        self.note(answer)
        if not answer.answered:
            run.check("bad-key", False, answer.error)
        else:
            run.check("bad-key", answer.status == 401, f"status {answer.status}")

    def embed(self):
        run = self.run
        texts = [self.requests.storm_search_text(), dict(self.requests.past_search_texts())["PI-1001"]]
        model = self.configurations["openai-embed"]["model_name"]
        answer = self.send("embed", "/v1/embeddings", {"model": model, "input": texts}, timeout=EMBED_TIMEOUT)
        self.note(answer, embeddings=True)
        if not answer.answered:
            run.check("embed-status", False, answer.error)
            run.not_run("embed-vectors", "embed-usage")
            return
        run.check("embed-status", answer.status == 200, f"status {answer.status}")
        parsed = answer.json()
        data = parsed.get("data") if isinstance(parsed, dict) else None
        detail = ""
        if not isinstance(data, list) or len(data) != len(texts) or not all(isinstance(item, dict) for item in data):
            detail = f"{len(data) if isinstance(data, list) else 0} vectors, expected {len(texts)}"
        else:
            ordered = sorted(data, key=lambda item: item.get("index") if isinstance(item.get("index"), int) else -1)
            if [item.get("index") for item in ordered] != list(range(len(texts))):
                detail = "the indexes are not those of the input order"
            elif not all(
                isinstance(item.get("embedding"), list)
                and len(item["embedding"]) == 1536
                and all(isinstance(number, (int, float)) for number in item["embedding"])
                for item in ordered
            ):
                detail = "a vector does not have 1536 numbers"
        run.check("embed-vectors", not detail, detail)
        prompt, _, _ = self.units(parsed, embeddings=True)
        run.check("embed-usage", common.positive_integer(prompt), "usage.prompt_tokens is not a positive integer")


def body(arguments):
    if not arguments or arguments[0] not in BASES:
        raise common.Stop(USAGE, 2)
    provider = arguments[0]
    address = common.address_argument(arguments[1:], USAGE, required=False)
    key = common.api_key(provider)
    spike = AiSpike(provider, address or BASES[provider], key)
    spike.recorder.clear()
    spike.execute()
    return spike.run.write({"calls": spike.calls})


def main(arguments):
    return common.main_wrapper(body, arguments)


if __name__ == "__main__":
    common.exit_with(body)
