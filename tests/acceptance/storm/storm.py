"""The storm driver (P-27 spec Section 6.2; SB-T-02 with amendment BP-A-11, SL-A3-04): runs the primary demo headless
on a local installation and checks its result.

Usage: storm.py --mode immediate|script [--deliveries fakes|checklist] [--acts driver|pages]

It resets as AR-B-03 does, injects the storm, acknowledges as noc-l1 and approves as dbops-engineer through links it
builds from the identifiers it reads (with --acts pages, mode immediate only, it waits for the acknowledgement and the
decision made on the pages instead, P-30 spec Section 6.10 item 1), waits until the storm incident is resolved, then
checks the SB-R-06 result and the values of SB-S01 to SB-S09 that need no screen (storm_checks.py). It changes things
only through vigil-api and demo-faults; it reads Zabbix and Alertmanager for the lab clear and, with deliveries fakes,
the dev fakes and Mailpit. It writes /results/storm-{mode}-{UTC time}.json and exits 0 when every check passed, 1
otherwise, 2 for wrong arguments. Standard library only (BP-R-04); no secret value is printed or written.
"""

import json
import os
import sys
import time
from datetime import UTC, datetime
from pathlib import Path
from urllib.parse import urlsplit

sys.path.insert(0, str(Path(__file__).resolve().parent))

import storm_api as api  # noqa: E402
import storm_checks as checks  # noqa: E402

USAGE = "Usage: storm.py --mode immediate|script [--deliveries fakes|checklist] [--acts driver|pages]"
STEP_NAMES = {
    1: "Reset",
    2: "Starting state",
    3: "Script start",
    4: "Storm",
    5: "Incidents",
    6: "Acknowledge",
    7: "Approve",
    8: "Recovery",
    9: "Checks",
}
EVERY = 2
SOURCES_LIMIT = 90
INCIDENTS_LIMIT = 360
NOTIFY_LIMIT = 420
SCRIPT_ACK_AT = 390
SCRIPT_ACK_LATEST = 405
APPROVAL_LIMIT = 180
SCRIPT_APPROVE_AT = 480
SCRIPT_APPROVE_LATEST = 510
PAGES_LIMIT = 300
RESOLVE_LIMIT = 480
TICKET_LIMIT = 120
INJECT_TIMEOUT = 180
USERS = ("presenter", "noc-l1", "dbops-engineer")


class WaitLimit(Exception):
    """A wait that reached its limit: the entry 'step {k} {name} finished within {limit}'."""

    def __init__(self, limit):
        super().__init__(limit)
        self.limit = limit


class ScriptAbort(Exception):
    """The script cannot be followed (steps 6 and 7)."""


def stamp(epoch):
    return datetime.fromtimestamp(epoch, UTC).strftime("%Y%m%dT%H%M%SZ")


def iso(epoch):
    return datetime.fromtimestamp(epoch, UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def parse_arguments(arguments):
    """(mode, deliveries, acts) or None; --acts pages is refused with mode script."""
    mode, deliveries, acts = None, "fakes", "driver"
    values = {"--mode": ("immediate", "script"), "--deliveries": ("fakes", "checklist"), "--acts": ("driver", "pages")}
    index = 0
    seen = set()
    while index < len(arguments):
        flag = arguments[index]
        if flag not in values or flag in seen or index + 1 >= len(arguments):
            return None
        seen.add(flag)
        value = arguments[index + 1]
        if value not in values[flag]:
            return None
        if flag == "--mode":
            mode = value
        elif flag == "--deliveries":
            deliveries = value
        else:
            acts = value
        index += 2
    if not mode or (acts == "pages" and mode == "script"):
        return None
    return (mode, deliveries, acts)


class Config:
    """Where the driver reaches and what it reads (P-27 spec Sections 6.1 and 6.2); tests replace the addresses."""

    def __init__(self, mode, deliveries, environ=None, root=Path("/"), acts="driver"):
        environ = os.environ if environ is None else environ
        self.mode = mode
        self.deliveries = deliveries
        self.acts = acts
        self.profile = environ.get("VIGIL_LAB_PROFILE", "test")
        self.base_url = environ.get("VIGIL_BASE_URL", "http://demo.vigil.test:8080").rstrip("/")
        self.faults_url = "http://172.31.0.1:8091"
        self.zabbix_url = "http://lab-zabbix-web:8080/api_jsonrpc.php"
        self.alertmanager_url = "http://lab-alertmanager:9093"
        self.teams_url = "http://teams.fakes:8000"
        self.jira_fake_url = "http://jira.fakes:8000"
        self.mailpit_url = "http://mailpit:8025"
        self.vigil_app = root / "run" / "secrets" / "vigil-app"
        self.demo_control = root / "run" / "secrets" / "demo-control"
        self.e2e = root / "run" / "secrets" / "e2e"
        self.bundle = root / "run" / "state" / "bundle.zip"
        self.data = root / "demo-data"
        self.results = root / "results"


class Driver:
    def __init__(self, config, clock=time.time, sleep=time.sleep, out=None):
        self.config = config
        self.clock = clock
        self.sleep = sleep
        self.out = out or (lambda line: print(line, flush=True))
        self.data = checks.Data(config.base_url, config.mode, config.deliveries, config.profile, config.acts)
        self.step = 0
        self.entries = []
        self.finished = False
        self.started = clock()
        self.reset = {"branch_a_seconds": None, "quiet_seconds": None, "branch_b_seconds": None, "total_seconds": None}
        self.tenant_id = None
        self.sessions = {}
        self.teams_seen = 0
        self.mail_seen = set()
        self.ack_done_at = None
        self.decided_at = None
        self.checklist = None

    # Helpers

    def say(self, line):
        self.out(line)

    def entry(self, name, detail):
        self.entries.append({"id": "SB-T-02", "name": name, "passed": False, "detail": detail})
        self.say(f"FAIL SB-T-02 {name}: {detail}")

    def until(self, condition, limit_at, limit_text):
        """Calls condition every 2 s until it answers a true value (connection errors retried) or limit_at passes."""
        while True:
            try:
                value = condition()
            except api.Unreachable:
                value = None
            if value:
                return value
            now = self.clock()
            if now >= limit_at:
                raise WaitLimit(limit_text)
            self.sleep(max(0.0, min(EVERY, limit_at - now)))

    def sleep_until(self, moment):
        now = self.clock()
        if moment > now:
            self.sleep(moment - now)

    def view(self, path):
        """A read as presenter: the body of a 200 answer, or None (a wait retries)."""
        status, body = self.sessions["presenter"].get(path)
        return body if status == 200 else None

    def read(self, path, user="presenter"):
        """A read outside a wait: the body of a 200 answer, otherwise the run fails."""
        status, body = self.sessions[user].get(path)
        if status != 200:
            raise api.Failed(f"GET {path} answered {api.answer_text(status, body)}")
        return body

    def secret(self, folder, name):
        return api.read_secret(folder / name)

    # Deliveries

    def teams_calls(self):
        status, calls = api.call_json("GET", f"{self.config.teams_url}/_fakes/calls")
        if status != 200 or not isinstance(calls, list):
            raise api.Unreachable(f"Teams fake calls answered HTTP {status}")
        return calls

    def mail_ids(self):
        status, body = api.call_json("GET", f"{self.config.mailpit_url}/api/v1/messages?limit=1000")
        if status != 200 or not isinstance(body, dict):
            raise api.Unreachable(f"Mailpit messages answered HTTP {status}")
        return [m.get("ID") for m in body.get("messages") or []]

    def new_mail(self):
        messages = []
        for message_id in self.mail_ids():
            if message_id in self.mail_seen:
                continue
            status, body = api.call_json("GET", f"{self.config.mailpit_url}/api/v1/message/{message_id}")
            if status != 200 or not isinstance(body, dict):
                raise api.Unreachable(f"Mailpit message answered HTTP {status}")
            messages.append(body)
        return messages

    def new_teams_messages(self):
        return [m for m in (checks.teams_message(c) for c in self.teams_calls()[self.teams_seen :]) if m is not None]

    def teams_link(self, path, label, suffix):
        """The first new Teams message on the path whose link of the label ends with suffix, or None."""
        found = [m for m in checks.holding(self.new_teams_messages(), label, suffix) if m.recipient == path]
        return found[0] if found else None

    # The steps

    def step1_reset(self):
        cfg = self.config
        installation = api.Installation(cfg.base_url, self.secret(cfg.vigil_app, "installation-token"))
        lab = api.Lab(
            cfg.faults_url,
            cfg.zabbix_url,
            cfg.alertmanager_url,
            self.secret(cfg.demo_control, "faults-token"),
            self.secret(cfg.vigil_app, "zabbix-api"),
            self.secret(cfg.vigil_app, "alertmanager-password"),
        )
        reset = api.Reset(
            installation, lab, cfg.bundle.read_bytes(), urlsplit(cfg.base_url).hostname, self.clock, self.sleep
        )
        try:
            self.tenant_id = reset.run()
        finally:
            self.reset = reset.result
        self.say(f"step 1 Reset: tenant {self.tenant_id}, {json.dumps(self.reset)}")

    def step2_starting_state(self):
        cfg = self.config
        for user in USERS:
            session = api.Session(cfg.base_url, user, self.secret(cfg.e2e, f"seed-password-{user}"))
            session.login()
            self.sessions[user] = session
        limit = self.clock() + SOURCES_LIMIT

        def healthy():
            strip = self.view("/api/v1/alerts/strip")
            sources = (strip or {}).get("sources") or {}
            return strip if sources and sources.get("healthy") == sources.get("total") else None

        self.data.start_strip = self.until(healthy, limit, f"{SOURCES_LIMIT} s")
        self.data.start_incidents = self.read("/api/v1/incidents?limit=50")
        self.data.start_dashboard = self.read("/api/v1/dashboard")
        if cfg.deliveries == "fakes":
            self.teams_seen = len(self.teams_calls())
            self.mail_seen = set(self.mail_ids())
        self.say("step 2 Starting state: three users logged in, sources healthy")

    def step3_script_start(self):
        if self.config.mode == "script":
            self.say("step 3 Script start: the storm is injected at once (T0 is the script's 01:00)")
        else:
            self.say("step 3 Script start: mode immediate follows no script clock")

    def step4_storm(self):
        lab = api.Lab(self.config.faults_url, "", "", self.secret(self.config.demo_control, "faults-token"), "", "")
        self.data.t0 = self.clock()
        for fault in ("SL-F-1", "SL-F-3"):
            status, body = lab.faults(f"/faults/{fault}/inject", {}, INJECT_TIMEOUT)
            if status != 200:
                raise api.Failed(f"demo-faults inject {fault} answered {api.answer_text(status, body)}")
        self.say(f"step 4 Storm: SL-F-1 and SL-F-3 injected (T0 {iso(self.data.t0)})")

    def step5_incidents(self):
        def both():
            page = self.view("/api/v1/incidents?limit=50")
            items = (page or {}).get("items") or []
            storm = [i for i in items if (i.get("title") or "").startswith(checks.STORM_PREFIX)]
            report = [i for i in items if (i.get("title") or "").startswith(checks.REPORT_PREFIX)]
            return (storm[0], report[0]) if storm and report else None

        storm, report = self.until(both, self.data.t0 + INCIDENTS_LIMIT, f"T0 + {INCIDENTS_LIMIT} s")
        self.data.storm_id, self.data.report_id = storm["incident_id"], report["incident_id"]
        self.say(
            f"step 5 Incidents: storm {checks.number_text(storm['number'])}, "
            f"report {checks.number_text(report['number'])}"
        )

    def step6_acknowledge(self):
        d, script = self.data, self.config.mode == "script"
        storm = d.storm_id

        def notified():
            run = (self.view(f"/api/v1/incidents/{storm}/run") or {}).get("run") or {}
            done = any(
                s.get("step_id") == "s-notify" and s.get("state") == "completed"
                for s in run.get("step_executions") or []
            )
            if not done:
                return None
            if self.config.deliveries == "fakes":
                return self.teams_link("/teams-team-noc-l1", "Acknowledge: ", f"/m/acknowledge/{storm}")
            return True

        limit = d.t0 + (SCRIPT_ACK_LATEST if script else NOTIFY_LIMIT)
        try:
            self.until(notified, limit, f"T0 + {NOTIFY_LIMIT} s")
        except WaitLimit:
            if script:
                raise ScriptAbort("the script can be followed: Acknowledge link by 07:45") from None
            raise
        if script and self.clock() > d.t0 + SCRIPT_ACK_LATEST:
            raise ScriptAbort("the script can be followed: Acknowledge link by 07:45")
        link = f"{self.config.base_url}/m/acknowledge/{storm}"
        if self.config.acts == "pages":
            self.acknowledged_on_page(link)
            return
        if script:
            self.sleep_until(d.t0 + SCRIPT_ACK_AT)
        noc = self.sessions["noc-l1"]
        d.ack_before = self.read(f"/api/v1/incidents/{storm}/acknowledgement", "noc-l1")
        d.ack_status, d.ack_answer = noc.post(f"/api/v1/incidents/{storm}/acknowledgements", None)
        if d.ack_status != 201:
            message = f"AR-I-33 answered {api.answer_text(d.ack_status, d.ack_answer)}"
            d.ack_answer = None
            raise api.Failed(message)
        self.ack_done_at = self.clock()
        d.ack_after = self.read(f"/api/v1/incidents/{storm}/acknowledgement", "noc-l1")
        self.say(f"step 6 Acknowledge: {link} at {d.ack_answer.get('at')}")

    def read_as(self, user, path):
        """A read as user inside a wait: the body of a 200 answer, or None (the wait retries)."""
        status, body = self.sessions[user].get(path)
        return body if status == 200 else None

    def acknowledged_on_page(self, link):
        """Step 6 with --acts pages: AR-I-32 as noc-l1 every 2 s until the acknowledgement made on the page."""
        d = self.data
        self.say(f"step 6 Acknowledge: waiting for the page at {link}")
        path = f"/api/v1/incidents/{d.storm_id}/acknowledgement"

        def acknowledged():
            page = self.read_as("noc-l1", path)
            return page if page and page.get("acknowledged") is not None else None

        d.ack_after = self.until(acknowledged, self.clock() + PAGES_LIMIT, f"{PAGES_LIMIT} s")
        d.ack_answer = d.ack_after["acknowledged"]
        self.ack_done_at = self.clock()
        self.say(f"step 6 Acknowledge: {link} at {d.ack_answer.get('at')}")

    def approved_on_page(self):
        """Step 7 with --acts pages: the run's first request in any state, then AR-I-34 until it is approved."""
        d = self.data
        storm = d.storm_id

        def first():
            response = self.view(f"/api/v1/incidents/{storm}/response") or {}
            requests = sorted(response.get("approvals") or [], key=lambda a: a.get("sequence_in_run") or 0)
            if not requests:
                return None
            request_id = requests[0]["approval_request_id"]
            if self.config.deliveries == "fakes" and not self.teams_link(
                "/teams-user-dbops-engineer", "Approve or reject: ", f"/m/approve/{request_id}"
            ):
                return None
            return request_id

        d.request_id = self.until(first, self.ack_done_at + APPROVAL_LIMIT, f"{APPROVAL_LIMIT} s")
        d.approval_read_at = self.clock()
        link = f"{self.config.base_url}/m/approve/{d.request_id}"
        self.say(f"step 7 Approve: waiting for the page at {link}")
        path = f"/api/v1/approval-requests/{d.request_id}"

        def approved():
            page = self.read_as("dbops-engineer", path)
            return page if page and page.get("state") == "approved" else None

        d.approval_page = self.until(approved, d.approval_read_at + PAGES_LIMIT, f"{PAGES_LIMIT} s")
        decided = d.approval_page.get("decided") or {}
        d.decision = {"state": d.approval_page.get("state"), "user": decided.get("user"), "at": decided.get("at")}
        self.decided_at = self.clock()
        self.say(f"step 7 Approve: {link} approved at {d.decision.get('at')}")

    def step7_approve(self):
        d, script = self.data, self.config.mode == "script"
        storm = d.storm_id
        if self.config.acts == "pages":
            self.approved_on_page()
            return

        def pending():
            response = self.view(f"/api/v1/incidents/{storm}/response") or {}
            requests = [a for a in response.get("approvals") or [] if a.get("state") == "pending"]
            if not requests:
                return None
            request_id = requests[0]["approval_request_id"]
            if self.config.deliveries == "fakes" and not self.teams_link(
                "/teams-user-dbops-engineer", "Approve or reject: ", f"/m/approve/{request_id}"
            ):
                return None
            return request_id

        if script:
            limit, text = d.t0 + SCRIPT_APPROVE_LATEST, f"T0 + {SCRIPT_APPROVE_LATEST} s"
        else:
            limit, text = self.ack_done_at + APPROVAL_LIMIT, f"{APPROVAL_LIMIT} s"
        try:
            d.request_id = self.until(pending, limit, text)
        except WaitLimit:
            if script:
                raise ScriptAbort("the script can be followed: Approval link by 09:30") from None
            raise
        link = f"{self.config.base_url}/m/approve/{d.request_id}"
        if script:
            self.sleep_until(d.t0 + SCRIPT_APPROVE_AT)
        engineer = self.sessions["dbops-engineer"]
        d.approval_read_at = self.clock()
        d.approval_page = self.read(f"/api/v1/approval-requests/{d.request_id}", "dbops-engineer")
        d.decision_status, answer = engineer.post(
            f"/api/v1/approval-requests/{d.request_id}/decisions",
            {"decision": "approve", "snapshot_hash_shown": d.approval_page.get("snapshot_hash")},
        )
        if d.decision_status != 201:
            raise api.Failed(f"AR-I-35 answered {api.answer_text(d.decision_status, answer)}")
        d.decision = answer
        self.decided_at = self.clock()
        self.say(f"step 7 Approve: {link} approved at {answer.get('at')}")

    def step8_recovery(self):
        d = self.data
        storm = d.storm_id

        def resolved():
            header = self.view(f"/api/v1/incidents/{storm}")
            return header if header and header.get("state") == "resolved" else None

        header = self.until(resolved, self.decided_at + RESOLVE_LIMIT, f"{RESOLVE_LIMIT} s")
        ticket = header.get("ticket") or {}
        jira = json.loads(self.secret(self.config.vigil_app, "jira"))
        state = {"status": None}

        def done():
            if ticket.get("url") and ticket.get("key"):
                state["status"] = api.jira_status(ticket["url"], ticket["key"], jira["email"], jira["api_token"])
            run = (self.view(f"/api/v1/incidents/{storm}/run") or {}).get("run") or {}
            return state["status"] == "Done" and run.get("state") == "finished"

        try:
            self.until(done, self.clock() + TICKET_LIMIT, f"{TICKET_LIMIT} s")
        finally:
            d.ticket_done = state["status"] == "Done"
            d.ticket_detail = f"expected Done within step 8's limit, found {state['status']!r} ({ticket.get('key')})"
        self.say(f"step 8 Recovery: resolved at {header.get('resolved_at')}, ticket {ticket.get('key')} Done")

    # Step 9: the end reads, the checks, the result

    def end_reads(self):
        d, problems = self.data, []

        def get(path):
            try:
                return self.read(path)
            except (api.Failed, api.Unreachable) as error:
                problems.append(str(error))
                return None

        if "presenter" not in self.sessions:
            return problems
        d.incidents = get("/api/v1/incidents?limit=50")
        d.dashboard = get("/api/v1/dashboard")
        for incident_id, section, parts in (
            (
                d.storm_id,
                d.storm,
                [
                    "",
                    "alerts",
                    "evidence",
                    "investigation",
                    "response",
                    "recovery",
                    "run",
                    "timeline",
                    "notified-teams",
                ],
            ),
            (d.report_id, d.report, ["", "alerts", "run", "notified-teams"]),
        ):
            if incident_id is None:
                continue
            for name in parts:
                key = {"": "header", "notified-teams": "notified"}.get(name, name)
                section[key] = get(f"/api/v1/incidents/{incident_id}" + (f"/{name}" if name else ""))
        if self.config.deliveries == "fakes":
            try:
                d.teams_calls = self.teams_calls()[self.teams_seen :]
                d.mail = self.new_mail()
                status, issues = api.call_json("GET", f"{self.config.jira_fake_url}/_fakes/issues")
                if status != 200 or not isinstance(issues, list):
                    raise api.Unreachable(f"Jira fake issues answered HTTP {status}")
                d.jira_issues = issues
            except api.Unreachable as error:
                problems.append(str(error))
        try:
            d.past_rows = checks.past_incidents(self.config.data / "09-past-incidents.csv")
            if self.config.mode == "script" and self.config.profile == "demo":
                d.dev_values = checks.development_values(self.config.data / "10-lab-and-faults.yaml")
        except OSError as error:
            problems.append(str(error))
        return problems

    def times(self):
        d = self.data
        result = {
            key: None for key in ("SB-TL-01", "SB-TL-02", "SB-TL-04", "SB-TL-06", "SB-TL-07", "SB-TL-08", "SB-TL-10")
        }
        if d.t0 is None:
            return result

        def after(text):
            return round(checks.epoch(text) - d.t0, 3) if text else None

        alerts = checks.alerts_of(d.storm.get("alerts") or {})
        if alerts:
            result["SB-TL-01"] = round(max(checks.epoch(a["fired_at"]) for a in alerts) - d.t0, 3)
        result["SB-TL-02"] = after(((d.storm.get("timeline") or {}).get("stable_grouping") or {}).get("stable_at"))
        if d.teams_calls is not None:
            messages = [m for m in (checks.teams_message(c) for c in d.teams_calls) if m is not None]
            first = checks.holding(messages, "Acknowledge: ", f"/m/acknowledge/{d.storm_id}")
            result["SB-TL-04"] = after(first[0].at) if first and d.storm_id else None
            approvals = checks.holding(messages, "Approve or reject: ", f"/m/approve/{d.request_id}")
            result["SB-TL-07"] = after(approvals[0].at) if approvals and d.request_id else None
        result["SB-TL-06"] = after((d.ack_answer or {}).get("at"))
        executions = (d.storm.get("response") or {}).get("executions") or []
        result["SB-TL-08"] = after(executions[0].get("finished_at")) if executions else None
        result["SB-TL-10"] = after((d.storm.get("header") or {}).get("resolved_at"))
        return result

    def write_checklist(self):
        name = f"storm-checklist-{stamp(self.clock())}.md"
        lines = checks.checklist_lines(self.data)
        (self.config.results / name).write_text("\n".join(lines) + "\n", encoding="utf-8")
        self.checklist = name
        self.say(f"checklist {name}")

    def incidents_record(self):
        d = self.data
        record = {}
        for which, incident_id, section in (("storm", d.storm_id, d.storm), ("report", d.report_id, d.report)):
            header = section.get("header") or {}
            record[which] = {
                "incident_id": incident_id,
                "number": header.get("number"),
                "ticket_key": (header.get("ticket") or {}).get("key"),
            }
        return record

    def run(self):
        steps = [
            (1, self.step1_reset),
            (2, self.step2_starting_state),
            (3, self.step3_script_start),
            (4, self.step4_storm),
            (5, self.step5_incidents),
            (6, self.step6_acknowledge),
            (7, self.step7_approve),
            (8, self.step8_recovery),
        ]
        try:
            for number, function in steps:
                self.step = number
                function()
            self.finished = True
        except WaitLimit as limit:
            self.entry(f"step {self.step} {STEP_NAMES[self.step]} finished within {limit.limit}", "the wait ran out")
        except ScriptAbort as abort:
            self.entry(str(abort), "the run ended: the script cannot be followed")
        except api.Failed as error:
            if error.within_limit:
                self.entry(f"step {self.step} {STEP_NAMES[self.step]} finished within {error.within_limit}", str(error))
            else:
                self.entry(f"step {self.step} {STEP_NAMES[self.step]} finished", str(error))
        except (api.Unreachable, OSError, ValueError, KeyError) as error:
            self.entry(f"step {self.step} {STEP_NAMES[self.step]} finished", f"{type(error).__name__}: {error}")
        self.step = 9
        problems = self.end_reads()
        if problems:
            self.entry("step 9 Checks finished", "; ".join(problems))
        made, not_run = checks.run_checks(self.data)
        for check in made:
            self.say(
                f"{'PASS' if check['passed'] else 'FAIL'} {check['id']} {check['name']}"
                + ("" if check["passed"] else f": {check['detail']}")
            )
        if self.config.deliveries == "checklist":
            self.write_checklist()
        return self.write_result(made, not_run)

    def write_result(self, made, not_run):
        all_checks = made + self.entries
        passed = self.finished and all(c["passed"] for c in all_checks)
        d = self.data
        result = {
            "driver": "storm",
            "mode": self.config.mode,
            "deliveries": self.config.deliveries,
            "acts": self.config.acts,
            "profile": self.config.profile,
            "started_at": iso(self.started),
            "finished_at": iso(self.clock()),
            "finished": self.finished,
            "t0": iso(d.t0) if d.t0 is not None else None,
            "tenant_id": self.tenant_id,
            "reset": self.reset,
            "incidents": self.incidents_record(),
            "times": self.times(),
            "investigation": d.results.get("investigation", {"runbooks_cited": None, "likely_cause": None}),
            "checklist": self.checklist,
            "checks": all_checks,
            "not_run": not_run,
            "passed": passed,
        }
        name = f"storm-{self.config.mode}-{stamp(self.started)}.json"
        (self.config.results / name).write_text(json.dumps(result, indent=2, default=str) + "\n", encoding="utf-8")
        self.say(f"result file {name}")
        return 0 if passed else 1


def main(arguments, config=None, **driver_options):
    parsed = parse_arguments(arguments)
    if parsed is None:
        print(USAGE)
        return 2
    mode, deliveries, acts = parsed
    return Driver(config or Config(mode, deliveries, acts=acts), **driver_options).run()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
