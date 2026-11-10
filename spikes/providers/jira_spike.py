"""SB-T-07: the Jira Cloud spike (P-06 spec Section 6.4; AR-K-14, AR-K-15).

Creates an issue with a label, finds it by that label within the lag rule (at most 4 searches, 5 s apart), reads its
ADF description, comments, resolves and reopens it. Usage: jira_spike.py [--address {url}]
"""

import datetime
import json
import urllib.parse
from decimal import Decimal

import common
import yaml

USAGE = "Usage: jira_spike.py [--address {url}]"
TIMEOUT = 15
SEARCHES = 4
SEARCH_GAP_S = 5
CHECKS = [
    "token-expiry",
    "myself",
    "create",
    "search",
    "read-description",
    "comment",
    "status-before",
    "resolve",
    "reopen",
]


def adf(lines):
    """AR-K-15: one paragraph per line; a line with https:// is the text before it and the address with a link mark."""
    content = []
    for line in lines:
        if not line:
            content.append({"type": "paragraph"})
        elif "https://" in line:
            index = line.index("https://")
            address = line[index:]
            content.append(
                {
                    "type": "paragraph",
                    "content": [
                        {"type": "text", "text": line[:index]},
                        {"type": "text", "text": address, "marks": [{"type": "link", "attrs": {"href": address}}]},
                    ],
                }
            )
        else:
            content.append({"type": "paragraph", "content": [{"type": "text", "text": line}]})
    return {"type": "doc", "version": 1, "content": content}


def jira_settings():
    """The settings of connector instance jira-demo in 07-settings.yaml."""
    with open(common.DATA / "07-settings.yaml", encoding="utf-8") as file:
        settings = yaml.safe_load(file)
    for instance in settings["connector_instances"]:
        if instance["handle"] == "jira-demo":
            return instance["settings"]
    raise common.Stop("Connector instance jira-demo is missing in 07-settings.yaml.")


def description_matches(description, lines, link):
    """The detail of a failed read-description check, or "" when the description holds the three lines and the link."""
    if not isinstance(description, dict):
        return "no description"
    paragraphs = [node for node in description.get("content", []) if node.get("type") == "paragraph"]
    if len(paragraphs) != len(lines):
        return f"{len(paragraphs)} paragraphs, expected {len(lines)}"
    for number, (paragraph, line) in enumerate(zip(paragraphs, lines, strict=True), start=1):
        texts = [node for node in paragraph.get("content", []) if node.get("type") == "text"]
        if "".join(node.get("text", "") for node in texts) != line:
            return f"paragraph {number} differs from its line"
    hrefs = [
        mark.get("attrs", {}).get("href")
        for node in paragraphs[2].get("content", [])
        for mark in node.get("marks", [])
        if mark.get("type") == "link"
    ]
    if link not in hrefs:
        return "paragraph 3 has no link mark with the link"
    return ""


class JiraSpike:
    def __init__(self, base, site, secret, settings, link_domain):
        self.base = base
        self.secret = secret
        self.settings = settings
        self.guard = common.Guard(
            secrets=[secret["api_token"], common.basic_authorization(secret["email"], secret["api_token"])[6:]],
            email=secret["email"],
            site=site,
        )
        self.run = common.Run("SB-T-07", self.guard)
        self.recorder = common.Recorder("jira", "${endpoint.jira}", self.guard, jira=True)
        self.label = f"{settings['label_prefix']}spike-{self.run.run_id}"
        self.link = f"https://{link_domain}/incidents/spike-{self.run.run_id}"
        self.own = {
            "token_expires_on": secret["expires_on"],
            "token_days_left": None,
            "issue_key": None,
            "search": {"attempts": 0, "seconds_after_create": None},
        }

    def headers(self, post):
        headers = [
            ("Authorization", common.basic_authorization(self.secret["email"], self.secret["api_token"])),
            ("Accept", "application/json"),
        ]
        if post:
            headers.append(("Content-Type", "application/json"))
        return headers

    def call(self, name, method, path, body=None):
        text = None if body is None else json.dumps(body)
        answer = common.call_http(name, self.base, method, path, self.headers(method == "POST"), text, TIMEOUT)
        if name == "myself":
            parsed = answer.json()
            if isinstance(parsed, dict):
                self.guard.account(parsed.get("accountId"))
        self.recorder.record(answer)
        return answer

    @staticmethod
    def problem(answer, status):
        """The detail when an answer is missing or has another status, else ""."""
        if not answer.answered:
            return answer.error
        if answer.status != status:
            return f"status {answer.status}"
        return ""

    def execute(self):
        run = self.run
        today = common.utc_now().date()
        expires = datetime.date.fromisoformat(self.secret["expires_on"])
        days = (expires - today).days
        self.own["token_days_left"] = days
        run.check("token-expiry", days >= 0, f"the token expired on {self.secret['expires_on']}")

        myself = self.call("myself", "GET", "/rest/api/3/myself")
        detail = self.problem(myself, 200)
        parsed = myself.json()
        account = parsed.get("accountId") if isinstance(parsed, dict) else None
        if not detail and not (isinstance(account, str) and account):
            detail = "no accountId"
        if not run.check("myself", not detail, detail):
            run.not_run(*CHECKS[2:])
            return

        key = self.create()
        self.search(key)
        if key is None:
            run.not_run("read-description", "comment", "status-before", "resolve", "reopen")
            return
        self.read_description(key)
        comment = self.call(
            "comment",
            "POST",
            f"/rest/api/3/issue/{key}/comment",
            {"body": adf(["Comment of the SB-T-07 spike.", f"Link: {self.link}"])},
        )
        detail = self.problem(comment, 201)
        run.check("comment", not detail, detail)
        detail = self.status_detail("status-1", key, self.settings["reopened_status"])
        run.check("status-before", not detail, detail)
        self.transition("resolve", "transitions-1", "status-2", key, self.settings["resolved_status"])
        self.transition("reopen", "transitions-2", "status-3", key, self.settings["reopened_status"])

    def create(self):
        s = self.settings
        lines = [
            f"SB-T-07 spike run {self.run.run_id}.",
            "Created by the P-06 Jira spike; nothing else uses this issue.",
            f"Link: {self.link}",
        ]
        body = {
            "fields": {
                "project": {"key": s["project_key"]},
                "issuetype": {"name": s["issue_type"]},
                "summary": f"SB-T-07 spike {self.run.run_id}",
                "description": adf(lines),
                "labels": [self.label],
            }
        }
        answer = self.call("create", "POST", "/rest/api/3/issue", body)
        self.created_at = answer.finished
        detail = self.problem(answer, 201)
        parsed = answer.json()
        key = parsed.get("key") if isinstance(parsed, dict) else None
        if not detail and not (isinstance(key, str) and key):
            detail = "no key"
        if self.run.check("create", not detail, detail):
            self.own["issue_key"] = key
            return key
        return None

    def search(self, key):
        if key is None:
            self.run.not_run("search")
            return
        jql = f'project={self.settings["project_key"]} AND labels="{self.label}"'
        query = urllib.parse.urlencode({"jql": jql, "fields": "status"}, quote_via=urllib.parse.quote)
        for attempt in range(1, SEARCHES + 1):
            if attempt > 1:
                common.pause(SEARCH_GAP_S)
            answer = self.call(f"search-{attempt}", "GET", f"/rest/api/3/search/jql?{query}")
            self.own["search"]["attempts"] = attempt
            parsed = answer.json() if answer.answered and answer.status == 200 else None
            issues = parsed.get("issues", []) if isinstance(parsed, dict) else []
            if any(isinstance(issue, dict) and issue.get("key") == key for issue in issues):
                seconds = common.half_up(Decimal(repr(answer.finished - self.created_at)), 1)
                self.own["search"]["seconds_after_create"] = float(seconds)
                self.run.check("search", True)
                return
        self.run.check("search", False, f"{key} not found in {SEARCHES} searches")

    def read_description(self, key):
        answer = self.call("read-description", "GET", f"/rest/api/3/issue/{key}?fields=description")
        detail = self.problem(answer, 200)
        if not detail:
            parsed = answer.json()
            description = (parsed.get("fields") or {}).get("description") if isinstance(parsed, dict) else None
            lines = [
                f"SB-T-07 spike run {self.run.run_id}.",
                "Created by the P-06 Jira spike; nothing else uses this issue.",
                f"Link: {self.link}",
            ]
            detail = description_matches(description, lines, self.link)
        self.run.check("read-description", not detail, detail)

    def status_name(self, answer):
        parsed = answer.json()
        try:
            return parsed["fields"]["status"]["name"]
        except (KeyError, TypeError):
            return None

    def status_detail(self, call, key, expected):
        answer = self.call(call, "GET", f"/rest/api/3/issue/{key}?fields=status")
        detail = self.problem(answer, 200)
        if detail:
            return detail
        name = self.status_name(answer)
        if name != expected:
            return f"status is {name}, expected {expected}"
        return ""

    def transition(self, check, list_call, status_call, key, target):
        answer = self.call(list_call, "GET", f"/rest/api/3/issue/{key}/transitions")
        detail = self.problem(answer, 200)
        transition_id = None
        if not detail:
            parsed = answer.json()
            transitions = parsed.get("transitions", []) if isinstance(parsed, dict) else []
            for transition in transitions:
                if isinstance(transition, dict) and (transition.get("to") or {}).get("name") == target:
                    transition_id = transition.get("id")
                    break
            if transition_id is None:
                detail = f"no transition to {target}"
        if detail:
            self.run.check(check, False, detail)
            return
        answer = self.call(check, "POST", f"/rest/api/3/issue/{key}/transitions", {"transition": {"id": transition_id}})
        detail = self.problem(answer, 204)
        if detail:
            self.run.check(check, False, detail)
            return
        detail = self.status_detail(status_call, key, target)
        self.run.check(check, not detail, detail)


def body(arguments):
    address = common.address_argument(arguments, USAGE, required=False)
    values = common.read_values()
    site = values.get("endpoint.jira")
    domain = values.get("demo_domain")
    if not site or not domain:
        raise common.Stop("endpoint.jira and demo_domain are needed in deploy/values/demo.yaml.")
    settings = jira_settings()
    secret = common.jira_secret()
    spike = JiraSpike(address or site.rstrip("/"), site, secret, settings, f"demo.{domain}")
    spike.recorder.clear()
    spike.execute()
    return spike.run.write(spike.own)


def main(arguments):
    return common.main_wrapper(body, arguments)


if __name__ == "__main__":
    common.exit_with(body)
