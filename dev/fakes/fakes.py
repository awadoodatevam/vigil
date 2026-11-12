"""The dev fakes of the local installation (60-build-plan Section 11.3, P-26 spec Section 6.5).

One program for every role, chosen by FAKE_ROLE: jira, teams, claude, openai, ollama-llm, ollama-embed. Each keeps
everything in memory and answers the calls of Vigil's connectors in the forms of dev/fakes/recordings/. Every role
offers GET /_fakes/calls; jira also GET /_fakes/issues. Standard library only.
"""

import hashlib
import json
import math
import os
import re
import struct
import sys
import threading
import time
from datetime import UTC, datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

ROLES = ("jira", "teams", "claude", "openai", "ollama-llm", "ollama-embed")
PORTS = {"ollama-llm": 11434, "ollama-embed": 11434}
DEFAULT_PORT = 8000
DIMENSIONS = 64
CAUSE_EVIDENCE_MAX = 200
SUMMARY_MAX = 600
UNCERTAINTY = "Whether other components are affected independently."
NO_HYPOTHESIS = "No hypothesis was supplied."
NO_INVESTIGATION = "No AI investigation was accepted."
PREFERRED_RUNBOOK = "RB-DB-01"
PART_HEADER = re.compile(
    r"^### (.+) \((Alert data|Service context|Diagnostic output|Runbook text|Incident history|Incident record)\)$",
    re.MULTILINE,
)
HYPOTHESIS = re.compile(r"^Root-cause hypothesis: (.*) \(([^()]*)\), kind ")
ISSUE_PATH = re.compile(r"/rest/api/3/issue/([A-Z][A-Z0-9]*-\d+)")
COMMENT_PATH = re.compile(r"/rest/api/3/issue/([A-Z][A-Z0-9]*-\d+)/comment")
TRANSITIONS_PATH = re.compile(r"/rest/api/3/issue/([A-Z][A-Z0-9]*-\d+)/transitions")
LABEL_TERM = re.compile(r'labels\s*=\s*"([^"]*)"')
# The three transitions of the recordings (dev/fakes/recordings/jira/0007-transitions-1.json): id, status, status id.
TRANSITIONS = (("11", "To Do", "10005"), ("21", "In Progress", "3"), ("31", "Done", "10006"))
NOT_FOUND = {"error": "not found"}


def utc_now():
    return datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def tokens(text):
    """A token count: the characters of the text divided by 4, rounded up."""
    return math.ceil(len(text) / 4)


def vector(text):
    """64 numbers as TestEmbedding.vector: from the first two bytes of SHA-256 of {text}#{k}, as 32-bit floats."""
    numbers = []
    for k in range(DIMENSIONS):
        digest = hashlib.sha256(f"{text}#{k}".encode()).digest()
        value = ((digest[0] << 8) | digest[1]) / 65535.0 - 0.5
        numbers.append(struct.unpack("f", struct.pack("f", value))[0])
    return numbers


def parts(user_text):
    """The parts of a user message as AiConnector renders them: {label: (category, text)}, in order."""
    found = {}
    headers = list(PART_HEADER.finditer(user_text))
    for index, header in enumerate(headers):
        end = headers[index + 1].start() if index + 1 < len(headers) else len(user_text)
        found[header.group(1)] = (header.group(2), user_text[header.end() + 1 : end].rstrip("\n"))
    return found


def hypothesis(found):
    """(name, component key) of the part Hypothesis, or None."""
    part = found.get("Hypothesis")
    match = HYPOTHESIS.match(part[1]) if part else None
    return (match.group(1), match.group(2)) if match else None


def sl_t_1(found):
    """The SL-T-1 output from the parts: passes PM-M17-03 when E1 and a runbook are supplied."""
    named = hypothesis(found)
    evidence = found.get("E1")
    runbooks = [label for label, (category, _) in found.items() if category == "Runbook text"]
    if named is None:
        cause = NO_HYPOTHESIS
    elif evidence is not None:
        cause = f"{named[0]} ({named[1]}) is the likely cause: {evidence[1][:CAUSE_EVIDENCE_MAX]}."
    else:
        cause = f"{named[0]} ({named[1]}) is the likely cause."
    if PREFERRED_RUNBOOK in runbooks:
        cited = [PREFERRED_RUNBOOK]
    else:
        cited = runbooks[:1]
    return {
        "likely_cause": cause,
        "hypothesis_verdict": "confirms" if named else "not_applicable",
        "evidence_labels": ["E1"] if evidence is not None else [],
        "uncertainty": UNCERTAINTY,
        "runbooks_cited": cited,
    }


def sl_t_2(found):
    """The SL-T-2 summary from the parts, at most 600 characters."""
    alerts = found.get("Alerts", ("", ""))[1]
    investigation = found.get("Investigation")
    cause = NO_INVESTIGATION
    if investigation is not None:
        for line in investigation[1].split("\n"):
            if line.startswith("Likely cause: "):
                cause = line[len("Likely cause: ") :]
                break
    text = f"{alerts}. {cause}"
    named = hypothesis(found)
    if named is not None:
        text = f"{named[0]}: {text}"
    return {"summary": text[:SUMMARY_MAX]}


def answer_for(schema, user_text):
    """The output object for the task told by the output schema, or None for any other request."""
    properties = schema.get("properties") if isinstance(schema, dict) else None
    if not isinstance(properties, dict) or not isinstance(user_text, str):
        return None
    if "likely_cause" in properties:
        return sl_t_1(parts(user_text))
    if "summary" in properties:
        return sl_t_2(parts(user_text))
    return None


def embed_inputs(body):
    texts = body.get("input") if isinstance(body, dict) else None
    if isinstance(texts, str):
        texts = [texts]
    if not isinstance(texts, list) or not texts or not all(isinstance(text, str) for text in texts):
        return None
    return texts


class Fake:
    """The in-memory state of one role and its answers: handle() gives (status, body object or None)."""

    def __init__(self, role, clock=utc_now):
        if role not in ROLES:
            raise ValueError(f"FAKE_ROLE must be one of {', '.join(ROLES)}")
        self.role = role
        self.clock = clock
        self.lock = threading.Lock()
        self.calls = []
        self.issues = []
        self.answers = 0

    def record(self, method, path, body):
        with self.lock:
            self.calls.append({"at": self.clock(), "method": method, "path": path, "body": body})

    def handle(self, method, path, body, host):
        """Answers one request; method and path as received, body as text."""
        route = urlsplit(path).path
        if route.startswith("/_fakes/"):
            return self.own(method, route)
        self.record(method, path, body)
        with self.lock:
            return getattr(self, self.role.replace("-", "_"))(method, path, body, host)

    def own(self, method, route):
        with self.lock:
            if method == "GET" and route == "/_fakes/calls":
                return 200, list(self.calls)
            if method == "GET" and route == "/_fakes/issues" and self.role == "jira":
                return 200, [dict(issue) for issue in self.issues]
        return 404, NOT_FOUND

    # Jira (AR-K-14, the calls of JiraConnector).

    def jira(self, method, path, body, host):
        split = urlsplit(path)
        route = split.path
        base = f"http://{host}"
        if method == "POST" and route == "/rest/api/3/issue":
            fields = json_object(body).get("fields")
            if not isinstance(fields, dict):
                return 400, jira_error("Field 'fields' is required.")
            number = len(self.issues) + 1
            issue = {
                "id": str(10000 + number),
                "key": f"NOC-{number}",
                "summary": fields.get("summary"),
                "labels": list(fields.get("labels") or []),
                "description": fields.get("description"),
                "status": "To Do",
                "comments": [],
            }
            self.issues.append(issue)
            return 201, {"id": issue["id"], "key": issue["key"], "self": f"{base}/rest/api/3/issue/{issue['id']}"}
        if method == "GET" and route == "/rest/api/3/search/jql":
            jql = parse_qs(split.query).get("jql", [""])[0]
            term = LABEL_TERM.search(jql)
            found = [issue for issue in self.issues if term and term.group(1) in issue["labels"]]
            return 200, {"issues": [issue_status(issue, base) for issue in found], "isLast": True}
        if match := COMMENT_PATH.fullmatch(route):
            issue = self.issue(match.group(1))
            if method != "POST":
                return 404, NOT_FOUND
            if issue is None:
                return 404, jira_missing()
            comment = {
                "id": str(10000 + sum(len(i["comments"]) for i in self.issues)),
                "body": json_object(body).get("body"),
            }
            issue["comments"].append(comment["body"])
            return 201, {"self": f"{base}/rest/api/3/issue/{issue['id']}/comment/{comment['id']}", **comment}
        if match := TRANSITIONS_PATH.fullmatch(route):
            issue = self.issue(match.group(1))
            if issue is None:
                return 404, jira_missing()
            if method == "GET":
                return 200, {"expand": "transitions", "transitions": [transition(entry, base) for entry in TRANSITIONS]}
            if method == "POST":
                wanted = json_object(body).get("transition")
                wanted = wanted.get("id") if isinstance(wanted, dict) else None
                for transition_id, status, _ in TRANSITIONS:
                    if transition_id == wanted:
                        issue["status"] = status
                        return 204, None
                return 400, jira_error(f"Transition id '{wanted}' is not valid for this issue.")
            return 404, NOT_FOUND
        if (match := ISSUE_PATH.fullmatch(route)) and method == "GET":
            issue = self.issue(match.group(1))
            if issue is None:
                return 404, jira_missing()
            return 200, issue_status(issue, base)
        return 404, NOT_FOUND

    def issue(self, key):
        return next((issue for issue in self.issues if issue["key"] == key), None)

    # Teams Workflows webhooks: any path.

    def teams(self, method, path, body, host):
        return (202, None) if method == "POST" else (404, NOT_FOUND)

    # The AI providers.

    def claude(self, method, path, body, host):
        if (method, urlsplit(path).path) != ("POST", "/v1/messages"):
            return 404, NOT_FOUND
        request = json_object(body)
        messages = request.get("messages")
        user = (
            messages[0].get("content")
            if isinstance(messages, list) and messages and isinstance(messages[0], dict)
            else None
        )
        schema = (
            (request.get("output_config") or {}).get("format", {})
            if isinstance(request.get("output_config"), dict)
            else {}
        )
        output = answer_for(schema.get("schema") if isinstance(schema, dict) else None, user)
        if output is None:
            return 400, {
                "type": "error",
                "error": {"type": "invalid_request_error", "message": "The fake answers only SL-T-1 and SL-T-2."},
            }
        text = json.dumps(output)
        self.answers += 1
        return 200, {
            "model": request.get("model"),
            "id": f"msg_fake_{self.answers}",
            "type": "message",
            "role": "assistant",
            "content": [{"type": "text", "text": text}],
            "stop_reason": "end_turn",
            "stop_sequence": None,
            "usage": {"input_tokens": tokens(str(request.get("system") or "") + user), "output_tokens": tokens(text)},
        }

    def openai(self, method, path, body, host):
        route = urlsplit(path).path
        if method == "POST" and route == "/v1/embeddings":
            request = json_object(body)
            texts = embed_inputs(request)
            if texts is None:
                return 400, openai_error("'input' must be a string or a list of strings.")
            used = sum(tokens(text) for text in texts)
            return 200, {
                "object": "list",
                "data": [{"object": "embedding", "embedding": vector(t), "index": i} for i, t in enumerate(texts)],
                "model": request.get("model"),
                "usage": {"prompt_tokens": used, "total_tokens": used},
            }
        if (method, route) != ("POST", "/v1/responses"):
            return 404, NOT_FOUND
        request = json_object(body)
        user = request.get("input")
        text_format = request.get("text") if isinstance(request.get("text"), dict) else {}
        schema = text_format.get("format", {}) if isinstance(text_format.get("format"), dict) else {}
        output = answer_for(schema.get("schema"), user)
        if output is None:
            return 400, openai_error("The fake answers only SL-T-1 and SL-T-2.")
        text = json.dumps(output)
        self.answers += 1
        used_in = tokens(str(request.get("instructions") or "") + user)
        used_out = tokens(text)
        return 200, {
            "id": f"resp_fake_{self.answers}",
            "object": "response",
            "created_at": int(time.time()),
            "status": "completed",
            "model": request.get("model"),
            "output": [
                {
                    "id": f"msg_fake_{self.answers}",
                    "type": "message",
                    "status": "completed",
                    "role": "assistant",
                    "content": [{"type": "output_text", "annotations": [], "text": text}],
                }
            ],
            "usage": {"input_tokens": used_in, "output_tokens": used_out, "total_tokens": used_in + used_out},
        }

    def ollama_llm(self, method, path, body, host):
        if (method, urlsplit(path).path) != ("POST", "/api/chat"):
            return 404, NOT_FOUND
        request = json_object(body)
        messages = request.get("messages") if isinstance(request.get("messages"), list) else []
        texts = [m.get("content") for m in messages if isinstance(m, dict) and isinstance(m.get("content"), str)]
        user = next((m.get("content") for m in messages if isinstance(m, dict) and m.get("role") == "user"), None)
        output = answer_for(request.get("format"), user)
        if output is None:
            return 400, {"error": "The fake answers only SL-T-1 and SL-T-2."}
        text = json.dumps(output)
        return 200, {
            "model": request.get("model"),
            "created_at": self.clock(),
            "message": {"role": "assistant", "content": text},
            "done": True,
            "done_reason": "stop",
            "prompt_eval_count": tokens("".join(texts)),
            "eval_count": tokens(text),
        }

    def ollama_embed(self, method, path, body, host):
        if (method, urlsplit(path).path) != ("POST", "/api/embed"):
            return 404, NOT_FOUND
        request = json_object(body)
        texts = embed_inputs(request)
        if texts is None:
            return 400, {"error": "'input' must be a string or a list of strings."}
        return 200, {
            "model": request.get("model"),
            "embeddings": [vector(text) for text in texts],
            "prompt_eval_count": sum(tokens(text) for text in texts),
        }


def json_object(body):
    try:
        value = json.loads(body)
    except ValueError:
        return {}
    return value if isinstance(value, dict) else {}


def jira_error(message):
    return {"errorMessages": [message], "errors": {}}


def jira_missing():
    return jira_error("Issue does not exist or you do not have permission to see it.")


def openai_error(message):
    return {"error": {"message": message, "type": "invalid_request_error", "param": None, "code": None}}


def status_object(name, base):
    status_id = next(entry[2] for entry in TRANSITIONS if entry[1] == name)
    return {"self": f"{base}/rest/api/3/status/{status_id}", "name": name, "id": status_id}


def issue_status(issue, base):
    return {
        "id": issue["id"],
        "self": f"{base}/rest/api/3/issue/{issue['id']}",
        "key": issue["key"],
        "fields": {"status": status_object(issue["status"], base)},
    }


def transition(entry, base):
    transition_id, name, _ = entry
    return {"id": transition_id, "name": name, "to": status_object(name, base), "hasScreen": False}


def make_handler(fake, log):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format, *args):
            pass

        def _handle(self):
            started = time.monotonic()
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length).decode("utf-8", errors="replace") if length else ""
            status, answer = fake.handle(self.command, self.path, body, self.headers.get("Host") or "localhost")
            data = b"" if answer is None else json.dumps(answer).encode()
            self.send_response(status)
            if answer is not None:
                self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            elapsed = round((time.monotonic() - started) * 1000)
            log(f"{self.command} {self.path} {status} {elapsed} ms")

        do_GET = do_POST = do_PUT = do_DELETE = do_PATCH = _handle

    return Handler


def make_server(role, port, host="0.0.0.0", log=None):
    """A server of the role on host:port (port 0: a free port); the caller runs serve_forever()."""
    fake = Fake(role)
    server = ThreadingHTTPServer((host, port), make_handler(fake, log or (lambda line: print(line, flush=True))))
    server.daemon_threads = True
    server.fake = fake
    return server


def main():
    role = os.environ.get("FAKE_ROLE", "")
    if role not in ROLES:
        print(f"FAKE_ROLE must be one of {', '.join(ROLES)}", flush=True)
        sys.exit(2)
    server = make_server(role, PORTS.get(role, DEFAULT_PORT))
    print(f"{role} fake listening on port {server.server_address[1]}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
