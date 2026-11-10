"""Shared parts of the provider spikes of P-06 (spec Sections 6.1 and 6.2): secrets, HTTP calls, recordings, results.

Every spike runs in this order: read and check its arguments, inputs and secrets (a failure raises Stop and changes no
file); remove the *.json files of its recording folder; make its calls, writing each recording as soon as the call
ends; write its result file {package}-{spike}.json ({package} P-06, or P-20 with run.sh --package P-20; P-20 spec
Section 6.14). No secret value is printed, logged or written: every file is checked before it is written (SecretInFile
stops the spike, and nothing more is written).
"""

import base64
import datetime
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "docs" / "31-demo-data"
VALUES = ROOT / "deploy" / "values" / "demo.yaml"
USER_AGENT = "vigil-p06-spike"
PERSONAL_KEYS = ("accountId", "emailAddress", "displayName", "avatarUrls")


class Stop(Exception):
    """Ends a spike before any file changes (arguments, inputs, secrets): the message and the exit code."""

    def __init__(self, message, code=1):
        super().__init__(message)
        self.message = message
        self.code = code


class SecretInFile(Exception):
    """A file would hold a secret value or personal data: nothing more is written."""

    def __init__(self, path):
        super().__init__(f"A secret would be written to {path}; nothing written.")
        self.path = path


def monotonic():
    """The clock of every duration (tests replace it)."""
    return time.monotonic()


def utc_now():
    return datetime.datetime.now(datetime.timezone.utc)


def pause(seconds):
    """Every wait of the spikes: sleeps, or not at all when VIGIL_TEST_FAST=1."""
    if os.environ.get("VIGIL_TEST_FAST") != "1":
        time.sleep(seconds)


def rfc3339(moment):
    """UTC, RFC 3339 with milliseconds (Example: 2026-11-05T10:15:00.123Z)."""
    return (
        moment.astimezone(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.") + f"{moment.microsecond // 1000:03d}Z"
    )


def run_id(moment):
    return moment.astimezone(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def folder_from(variable, default):
    """A folder from an environment variable or the default, relative paths taken from the repository root."""
    path = Path(os.environ.get(variable) or default)
    return path if path.is_absolute() else ROOT / path


def results_folder():
    return folder_from("VIGIL_SPIKE_RESULTS", "spikes/results")


def result_package():
    """The package that names the result files: VIGIL_SPIKE_PACKAGE (run.sh --package P-20) or P-06 when unset."""
    return os.environ.get("VIGIL_SPIKE_PACKAGE") or "P-06"


def recordings_folder(name):
    return folder_from("VIGIL_SPIKE_RECORDINGS", "dev/fakes/recordings") / name


def positive_integer(value):
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def half_up(value, places):
    """A number rounded half-up to that many decimal places, as a Decimal."""
    return Decimal(value).quantize(Decimal(1).scaleb(-places), rounding=ROUND_HALF_UP)


def address_argument(arguments, usage, required):
    """The value of --address {url} (None when it is optional and absent); wrong arguments stop with the usage line."""
    if not arguments and not required:
        return None
    if len(arguments) == 2 and arguments[0] == "--address" and arguments[1]:
        return arguments[1].rstrip("/")
    raise Stop(usage, 2)


def read_values():
    """The deployment values of the demo installation (deploy/values/demo.yaml)."""
    import yaml

    with open(VALUES, encoding="utf-8") as file:
        return yaml.safe_load(file) or {}


# ---------------------------------------------------------------- secrets (Section 6.1)
def read_secret(name):
    """The value of /vigil/demo/{name}, read with the AWS CLI on PATH and kept in memory only."""
    parameter = f"/vigil/demo/{name}"
    environment = dict(os.environ, AWS_REGION="us-east-1", AWS_PAGER="")
    try:
        process = subprocess.run(
            ["aws", "ssm", "get-parameter", "--name", parameter, "--with-decryption", "--output", "json"],
            stdin=subprocess.DEVNULL,
            capture_output=True,
            text=True,
            env=environment,
            check=False,
        )
    except OSError as error:
        raise Stop(str(error)) from None
    if process.returncode != 0:
        if "ParameterNotFound" in process.stderr:
            raise Stop(f"Missing secret {parameter} (AR-S-13).")
        raise Stop(process.stderr.rstrip("\n"))
    try:
        value = json.loads(process.stdout)["Parameter"]["Value"]
    except (ValueError, KeyError, TypeError):
        raise Stop(f"Secret {parameter} is not valid (AR-S-13).") from None
    return value


def valid_date(text):
    """True when the text is a calendar date written YYYY-MM-DD."""
    if not isinstance(text, str) or not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2}", text):
        return False
    try:
        datetime.date.fromisoformat(text)
    except ValueError:
        return False
    return True


def api_key(name):
    """The secret of an API key (claude, openai): a non-empty string without whitespace."""
    value = read_secret(name)
    if not isinstance(value, str) or not value or re.search(r"\s", value):
        raise Stop(f"Secret /vigil/demo/{name} is not valid (AR-S-13).")
    return value


def jira_secret():
    """The secret jira: a JSON object with the strings email, api_token and expires_on (a valid YYYY-MM-DD date)."""
    text = read_secret("jira")
    try:
        value = json.loads(text)
    except ValueError:
        value = None
    if (
        not isinstance(value, dict)
        or not all(isinstance(value.get(key), str) for key in ("email", "api_token", "expires_on"))
        or not valid_date(value["expires_on"])
    ):
        raise Stop("Secret /vigil/demo/jira is not valid (AR-S-13).")
    return value


# ---------------------------------------------------------------- HTTP calls
class Answer:
    """One call: what was sent and what came back (response None and error set on a connection failure)."""

    def __init__(self, call, method, path, headers, body, started_at):
        self.call = call
        self.method = method
        self.path = path
        self.headers = headers
        self.body = body
        self.started_at = started_at
        self.status = None
        self.content_type = None
        self.text = None
        self.error = None
        self.duration_ms = None
        self.finished = None

    @property
    def answered(self):
        return self.error is None

    def json(self):
        """The answer body parsed as JSON, or None."""
        if self.text is None:
            return None
        try:
            return json.loads(self.text)
        except ValueError:
            return None


def call_http(call, base, method, path, headers, body, timeout):
    """Makes one HTTP call with urllib; any HTTP status is an answer, a connection failure or timeout is an error.

    headers is a list of (name, value) as the spike sets them; User-Agent is added. body is a text or None.
    """
    headers = list(headers) + [("User-Agent", USER_AGENT)]
    data = None if body is None else body.encode("utf-8")
    request = urllib.request.Request(base + path, data=data, method=method)
    for name, value in headers:
        request.add_header(name, value)
    if urllib.parse.urlsplit(base).hostname == "127.0.0.1":
        # The port forwards of run.sh: never through a proxy of the environment, as run.sh's readiness probe.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    else:
        opener = urllib.request.build_opener()
    answer = Answer(call, method, path, headers, body, utc_now())
    start = monotonic()
    try:
        try:
            response = opener.open(request, timeout=timeout)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            answer.status = response.status if hasattr(response, "status") else response.code
            answer.content_type = response.headers.get("Content-Type")
        answer.text = raw.decode("utf-8", errors="replace")
    except Exception as error:  # every failure to get an answer is recorded as the error text
        answer.error = str(error) or type(error).__name__
    answer.finished = monotonic()
    answer.duration_ms = int(round((answer.finished - start) * 1000))
    return answer


def basic_authorization(email, token):
    return "Basic " + base64.b64encode(f"{email}:{token}".encode()).decode()


# ---------------------------------------------------------------- recordings and results (Section 6.2)
class Guard:
    """The values no written file may hold and the replacements of the recordings."""

    def __init__(self, secrets=(), email=None, site=None):
        self.secrets = [value for value in secrets if value]
        self.email = email or None
        self.site = site or None
        self.account_id = None

    def account(self, account_id):
        if isinstance(account_id, str) and account_id:
            self.account_id = account_id

    def account_forms(self):
        if not self.account_id:
            return []
        return [self.account_id, urllib.parse.quote(self.account_id, safe="")]

    def check(self, text, path):
        for value in self.secrets + self.account_forms():
            if value in text:
                raise SecretInFile(path)
        if self.email and self.email.lower() in text.lower():
            raise SecretInFile(path)

    def clean(self, text):
        """Rule 3: the Jira site, the account's email and its account ID replaced in a body text."""
        if self.site:
            text = text.replace(self.site, "${endpoint.jira}")
        if self.email:
            text = re.sub(re.escape(self.email), "<email>", text, flags=re.IGNORECASE)
        for form in sorted(self.account_forms(), key=len, reverse=True):
            text = text.replace(form, "<account-id>")
        return text


def redact_personal(value):
    """Every value of a key named accountId, emailAddress, displayName or avatarUrls, at any depth, as "<redacted>"."""
    if isinstance(value, dict):
        return {key: "<redacted>" if key in PERSONAL_KEYS else redact_personal(item) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_personal(item) for item in value]
    return value


REDACTED_HEADERS = {"authorization", "x-api-key"}


def recorded_headers(headers):
    """Rule 2: the headers as set, Authorization and x-api-key values replaced by <redacted> (keeping the scheme)."""
    result = {}
    for name, value in headers:
        if name.lower() in REDACTED_HEADERS:
            scheme = value.split(" ", 1)[0] if " " in value else ""
            value = f"{scheme} <redacted>" if scheme in ("Basic", "Bearer") else "<redacted>"
        result[name] = value
    return result


def write_checked(path, text, guard):
    """Writes the text after the secret check of Section 6.1 (raises SecretInFile before writing anything)."""
    guard.check(text, path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name("." + path.name + ".tmp")
    temporary.write_text(text, encoding="utf-8")
    temporary.replace(path)


class Recorder:
    """Writes one recording per recorded call, {n:04d}-{call}.json, into a recording folder."""

    def __init__(self, name, placeholder, guard, jira=False):
        self.folder = recordings_folder(name)
        self.placeholder = placeholder
        self.guard = guard
        self.jira = jira
        self.count = 0

    def clear(self):
        """Removes the *.json files of the folder (the README stays)."""
        self.folder.mkdir(parents=True, exist_ok=True)
        for path in self.folder.glob("*.json"):
            path.unlink()

    def body(self, text, answer_body=False):
        if text is None:
            return ""
        if self.jira and answer_body:
            try:
                parsed = json.loads(text)
            except ValueError:
                parsed = None
            if isinstance(parsed, (dict, list)):
                text = json.dumps(redact_personal(parsed), ensure_ascii=False)
        return self.guard.clean(text)

    def record(self, answer):
        self.count += 1
        response = None
        if answer.answered:
            response = {
                "status": answer.status,
                "headers": {"Content-Type": answer.content_type} if answer.content_type is not None else {},
                "body": self.body(answer.text, answer_body=True),
            }
        content = {
            "seq": self.count,
            "call": answer.call,
            "started_at": rfc3339(answer.started_at),
            "duration_ms": answer.duration_ms,
            "request": {
                "method": answer.method,
                "url": self.placeholder + answer.path,
                "headers": recorded_headers(answer.headers),
                "body": self.body(answer.body),
            },
            "response": response,
            "error": answer.error,
        }
        path = self.folder / f"{self.count:04d}-{answer.call}.json"
        write_checked(path, json.dumps(content, indent=2, ensure_ascii=False) + "\n", self.guard)


class Run:
    """The checks and the result file of one spike run."""

    def __init__(self, spike, guard):
        self.spike = spike
        self.guard = guard
        self.started = utc_now()
        self.run_id = run_id(self.started)
        self.checks = []

    def check(self, name, passed, detail=""):
        self.checks.append({"name": name, "passed": bool(passed), "detail": "" if passed else detail})
        print(f"{name}: passed" if passed else f"{name}: FAILED ({detail})", flush=True)
        return bool(passed)

    def not_run(self, *names):
        for name in names:
            self.check(name, False, "not run")

    def passed(self, name):
        return any(check["name"] == name and check["passed"] for check in self.checks)

    @property
    def all_passed(self):
        return bool(self.checks) and all(check["passed"] for check in self.checks)

    def write(self, own):
        content = {
            "spike": self.spike,
            "run_id": self.run_id,
            "started_at": rfc3339(self.started),
            "finished_at": rfc3339(utc_now()),
            "passed": self.all_passed,
            "checks": self.checks,
        }
        content.update(own)
        path = results_folder() / f"{result_package()}-{self.spike}.json"
        write_checked(path, json.dumps(content, indent=2, ensure_ascii=False) + "\n", self.guard)
        print(f"result: {path}", flush=True)
        return 0 if self.all_passed else 1


def main_wrapper(body, arguments):
    """Runs a spike's body and turns Stop and SecretInFile into their message and exit code."""
    try:
        return body(arguments)
    except Stop as stop:
        print(stop.message, flush=True)
        return stop.code
    except SecretInFile as stop:
        print(str(stop), flush=True)
        return 1


def exit_with(body):
    sys.exit(main_wrapper(body, sys.argv[1:]))
