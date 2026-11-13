"""The storm driver's calls (P-27 spec Sections 6.1 and 6.2): vigil-api with sessions and the installation token,
demo-faults, the lab's monitoring (read only), the dev fakes, Mailpit and the Jira status; the reset of AR-B-03.

Standard library only (BP-R-04). No proxy of the environment is used. No secret value is printed or returned in a
message: bearer and basic headers are built in memory from the mounted secret files.
"""

import base64
import json
import secrets
import threading
import urllib.error
import urllib.request
from datetime import UTC, datetime
from decimal import Decimal
from http.cookies import SimpleCookie
from urllib.parse import urlsplit

TIMEOUT_SECONDS = 30
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))
# The five Alertmanager catalogue alert names of 03-monitoring.yaml (the lab clear of AR-B-03 branch A).
CATALOGUE_ALERTS = (
    "NodeFilesystemFull",
    "BalanceApiErrors",
    "BundleApiErrors",
    "UssdGatewayTimeouts",
    "SyntheticUssdSessionFailed",
)
BRANCH_A_LIMIT = 180
BRANCH_B_LIMIT = 300
QUIET_EVERY = 2
CLEAR_SETTLE = 12
IMPORT_EVERY = 1


class Unreachable(Exception):
    """A connection error (no answer); its message is the error's text."""


class Failed(Exception):
    """A step that failed with a message; within_limit names the limit when a branch or wait ran out."""

    def __init__(self, message, within_limit=None):
        super().__init__(message)
        self.within_limit = within_limit


def parse_json(raw):
    """JSON with numbers that have a fraction as Decimal (AR-I-80 compares exactly); None when it is not JSON."""
    if not raw:
        return None
    try:
        return json.loads(raw, parse_float=Decimal)
    except ValueError:
        return None


def request(method, url, body=None, headers=None, timeout=TIMEOUT_SECONDS, content_type="application/json"):
    """(status, response headers, raw body); raises Unreachable for a connection error."""
    data = None
    if body is not None:
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
    call = urllib.request.Request(url, data=data, method=method, headers=dict(headers or {}))
    call.add_header("Accept", "application/json")
    if data is not None:
        call.add_header("Content-Type", content_type)
    try:
        with OPENER.open(call, timeout=max(timeout, 0.5)) as response:
            return response.status, response.headers, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.headers, error.read()
    except (OSError, ValueError) as error:
        raise Unreachable(f"{method} {url}: {error}") from None


def call_json(method, url, body=None, headers=None, timeout=TIMEOUT_SECONDS, content_type="application/json"):
    status, _, raw = request(method, url, body, headers, timeout, content_type)
    return status, parse_json(raw)


def answer_text(status, body):
    """A short description of an answer for a message (never a request header)."""
    text = json.dumps(body, default=str)[:300] if body is not None else "no JSON body"
    return f"HTTP {status} {text}"


def read_secret(path):
    return path.read_text(encoding="utf-8")


def basic(user, password):
    return "Basic " + base64.b64encode(f"{user}:{password}".encode()).decode()


class Session:
    """One user's session of vigil-api (AR-I-12, AR-S-02, AR-S-03): a cookie store and the CSRF header."""

    def __init__(self, base_url, username, password):
        self.base_url = base_url.rstrip("/")
        self.username = username
        self._password = password
        self.cookies = {}

    def _keep(self, headers):
        for header in headers.get_all("Set-Cookie") or []:
            cookie = SimpleCookie()
            try:
                cookie.load(header)
            except Exception:  # noqa: BLE001 - a cookie Python cannot read is not one of Vigil's
                continue
            for name, morsel in cookie.items():
                self.cookies[name] = morsel.value

    def _call(self, method, path, body=None):
        headers = {}
        if self.cookies:
            headers["Cookie"] = "; ".join(f"{name}={value}" for name, value in self.cookies.items())
        if method != "GET" and "vigil_csrf" in self.cookies:
            headers["X-Vigil-CSRF"] = self.cookies["vigil_csrf"]
        status, response_headers, raw = request(method, self.base_url + path, body, headers)
        self._keep(response_headers)
        return status, parse_json(raw)

    def login(self):
        self.cookies = {}
        status, body = self._call("POST", "/api/v1/session", {"username": self.username, "password": self._password})
        if status != 200:
            raise Failed(f"Login of {self.username} (AR-I-12) answered HTTP {status}.")
        return body

    def get(self, path):
        return self._call("GET", path)

    def post(self, path, body):
        return self._call("POST", path, body)


class Installation:
    """The installation operations AR-I-95 to AR-I-98 with the installation token (AR-S-07)."""

    def __init__(self, base_url, token):
        self.base_url = base_url.rstrip("/")
        self._token = token

    def call(self, method, path, body=None, timeout=TIMEOUT_SECONDS, content_type="application/json"):
        headers = {"Authorization": f"Bearer {self._token}"}
        return call_json(method, self.base_url + path, body, headers, timeout, content_type)


class Lab:
    """demo-faults (changes), Zabbix and Alertmanager (read only, the lab clear)."""

    def __init__(self, faults_url, zabbix_url, alertmanager_url, faults_token, zabbix_token, alertmanager_password):
        self.faults_url = faults_url.rstrip("/")
        self.zabbix_url = zabbix_url
        self.alertmanager_url = alertmanager_url.rstrip("/")
        self._faults = f"Bearer {faults_token}"
        self._zabbix = f"Bearer {zabbix_token}"
        self._alertmanager = basic("vigil", alertmanager_password)

    def faults(self, path, body, timeout):
        """(status, body) of a demo-faults operation (AR-X-03)."""
        return call_json("POST", self.faults_url + path, body, {"Authorization": self._faults}, timeout)

    def zabbix_problems(self):
        """The open Zabbix problems with tag vigil = lab (problem.get); raises Unreachable when unreadable."""
        body = {
            "jsonrpc": "2.0",
            "method": "problem.get",
            "params": {"output": ["eventid", "name"], "tags": [{"tag": "vigil", "value": "lab", "operator": 1}]},
            "id": 1,
        }
        headers = {"Authorization": self._zabbix}
        status, answer = call_json("POST", self.zabbix_url, body, headers, content_type="application/json-rpc")
        if status != 200 or not isinstance(answer, dict) or not isinstance(answer.get("result"), list):
            raise Unreachable(f"Zabbix problem.get answered HTTP {status}")
        return answer["result"]

    def alertmanager_firing(self):
        """The Alertmanager alerts whose alertname is a catalogue name; raises Unreachable when unreadable."""
        status, alerts = call_json(
            "GET", f"{self.alertmanager_url}/api/v2/alerts", headers={"Authorization": self._alertmanager}
        )
        if status != 200 or not isinstance(alerts, list):
            raise Unreachable(f"Alertmanager alerts answered HTTP {status}")
        return [
            a for a in alerts if isinstance(a, dict) and (a.get("labels") or {}).get("alertname") in CATALOGUE_ALERTS
        ]

    def quiet(self):
        try:
            return not self.zabbix_problems() and not self.alertmanager_firing()
        except Unreachable:
            return False


def multipart(field, filename, content):
    boundary = "vigil-" + secrets.token_hex(16)
    head = (
        f"--{boundary}\r\n"
        f'Content-Disposition: form-data; name="{field}"; filename="{filename}"\r\n'
        "Content-Type: application/zip\r\n\r\n"
    ).encode()
    return head + content + f"\r\n--{boundary}--\r\n".encode(), f"multipart/form-data; boundary={boundary}"


def utc_name(epoch):
    return datetime.fromtimestamp(epoch, UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class Reset:
    """AR-B-03 on the local installation (P-27 spec Section 6.2 step 1): branches A and B at once, the alias last."""

    def __init__(self, installation, lab, bundle, host, clock, sleep, log=print):
        self.installation = installation
        self.lab = lab
        self.bundle = bundle
        self.host = host
        self.clock = clock
        self.sleep = sleep
        self.log = log
        self.result = {"branch_a_seconds": None, "quiet_seconds": None, "branch_b_seconds": None, "total_seconds": None}

    def _wait(self, deadline, every):
        self.sleep(max(0.0, min(every, deadline - self.clock())))

    def branch_a(self):
        started = self.clock()
        deadline = started + BRANCH_A_LIMIT
        status, body = self.lab.faults(
            "/faults/clear", {"faults": ["SL-F-1", "SL-F-2", "SL-F-3"]}, max(1.0, deadline - self.clock())
        )
        if status != 200:
            raise Failed(f"demo-faults clear answered {answer_text(status, body)}")
        cleared = self.clock()
        while not self.lab.quiet():
            if self.clock() >= deadline:
                raise Failed(f"Branch A: the lab was not quiet within {BRANCH_A_LIMIT} s.", f"{BRANCH_A_LIMIT} s")
            self._wait(deadline, QUIET_EVERY)
        self.result["quiet_seconds"] = round(self.clock() - cleared, 3)
        self.sleep(CLEAR_SETTLE)
        if self.clock() - started > BRANCH_A_LIMIT:
            raise Failed(f"Branch A did not finish within {BRANCH_A_LIMIT} s.", f"{BRANCH_A_LIMIT} s")
        self.result["branch_a_seconds"] = round(self.clock() - started, 3)

    def branch_b(self):
        started = self.clock()
        deadline = started + BRANCH_B_LIMIT
        name = "jazz-demo " + utc_name(self.clock())
        status, body = self.installation.call("POST", "/api/v1/installation/tenants", {"name": name})
        if status != 200 or not isinstance(body, dict) or "tenant_id" not in body:
            raise Failed(f"Create tenant (AR-I-95) answered {answer_text(status, body)}")
        tenant_id = body["tenant_id"]
        content, content_type = multipart("bundle", "bundle.zip", self.bundle)
        status, body = self.installation.call(
            "POST", f"/api/v1/installation/tenants/{tenant_id}/seed-imports", content, content_type=content_type
        )
        if status != 202 or not isinstance(body, dict) or "seed_import_id" not in body:
            raise Failed(f"Seed import (AR-I-96) answered {answer_text(status, body)}")
        seed_import_id = body["seed_import_id"]
        while True:
            answer = None
            try:
                status, answer = self.installation.call("GET", f"/api/v1/installation/seed-imports/{seed_import_id}")
            except Unreachable:
                status = None
            if status == 200 and isinstance(answer, dict) and answer.get("state") in ("succeeded", "failed"):
                break
            if self.clock() >= deadline:
                raise Failed(
                    f"Branch B: the seed import did not finish within {BRANCH_B_LIMIT} s.", f"{BRANCH_B_LIMIT} s"
                )
            self._wait(deadline, IMPORT_EVERY)
        failed = [p for p in answer.get("parts") or [] if p.get("outcome") != "succeeded"]
        if answer["state"] != "succeeded" or failed:
            parts = "; ".join(f"{p.get('module')}: {p.get('outcome')} {p.get('error') or ''}".strip() for p in failed)
            raise Failed(f"The seed import ended {answer['state']}. {parts}".strip())
        self.result["branch_b_seconds"] = round(self.clock() - started, 3)
        return tenant_id

    def run(self):
        """The new tenant's id; raises Failed (without AR-I-98) when a branch failed."""
        started = self.clock()
        outcome = {}

        def run_branch(key, branch):
            try:
                outcome[key] = ("ok", branch())
            except Failed as error:
                outcome[key] = ("failed", error)
            except Unreachable as error:
                outcome[key] = ("failed", Failed(str(error)))

        threads = [
            threading.Thread(target=run_branch, args=("A", self.branch_a), daemon=True),
            threading.Thread(target=run_branch, args=("B", self.branch_b), daemon=True),
        ]
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
        errors = [(key, outcome[key][1]) for key in ("A", "B") if outcome.get(key, ("failed",))[0] != "ok"]
        if errors:
            limit = next((error.within_limit for _, error in errors if error.within_limit), None)
            raise Failed(" ".join(f"Branch {key}: {error}" for key, error in errors), limit)
        tenant_id = outcome["B"][1]
        status, body = self.installation.call(
            "PUT", f"/api/v1/installation/aliases/{self.host}", {"tenant_id": tenant_id}
        )
        if status != 200:
            raise Failed(f"Set alias (AR-I-98) answered {answer_text(status, body)}")
        self.result["total_seconds"] = round(self.clock() - started, 3)
        return tenant_id


def ticket_base(url, key):
    """The Jira base of a ticket link: the url without /browse/{key}."""
    suffix = f"/browse/{key}"
    return url[: -len(suffix)] if url.endswith(suffix) else urlsplit(url)._replace(path="", query="").geturl()


def jira_status(url, key, email, api_token):
    """fields.status.name of the issue, or None; raises Unreachable for a connection error."""
    status, body = call_json(
        "GET",
        f"{ticket_base(url, key)}/rest/api/3/issue/{key}?fields=status",
        headers={"Authorization": basic(email, api_token)},
    )
    if status != 200 or not isinstance(body, dict):
        return None
    return ((body.get("fields") or {}).get("status") or {}).get("name")
