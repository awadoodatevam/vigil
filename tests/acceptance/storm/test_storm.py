"""Tests of the storm driver (P-27 spec Sections 6.2 to 6.4 and 6.8): no Docker, no network beyond 127.0.0.1.

An in-process server ("the world") plays vigil-api, demo-faults, Zabbix, Alertmanager, the Teams and Jira fakes and
Mailpit with answers in the shapes of the OpenAPI file and of the connectors' payloads; the driver runs against it with
an injected clock, so no test waits for real time.
"""

import ast
import copy
import json
import re
import sys
import threading
from datetime import UTC, datetime
from decimal import Decimal
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent.parent
DATA = ROOT / "docs" / "31-demo-data"
sys.path.insert(0, str(HERE))

import storm  # noqa: E402
import storm_api  # noqa: E402
import storm_checks as checks  # noqa: E402

DEV = checks.development_values(DATA / "10-lab-and-faults.yaml")
START = 1_800_000_000.0


def iso(epoch):
    return datetime.fromtimestamp(epoch, UTC).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


class Clock:
    """One shared fake clock: sleep advances it."""

    def __init__(self, start=START):
        self.now = start
        self.lock = threading.Lock()
        self.sleeps = []

    def time(self):
        with self.lock:
            return self.now

    def sleep(self, seconds):
        with self.lock:
            self.sleeps.append(seconds)
            self.now += seconds


class ThreadClock:
    """A fake clock with one timeline per thread, so two branches that run at once each see only their own sleeps."""

    def __init__(self, start=START):
        self.start = start
        self.local = threading.local()
        self.lock = threading.Lock()
        self.sleeps = []

    def time(self):
        return self.start + getattr(self.local, "offset", 0.0)

    def sleep(self, seconds):
        self.local.offset = getattr(self.local, "offset", 0.0) + seconds
        with self.lock:
            self.sleeps.append((threading.current_thread().name, seconds))


# The world


def card(lines):
    """The Adaptive Card of TeamsConnector.payload for (text, link) lines."""
    body = []
    for text, link in lines:
        shown = text if link is None else text[: -len(link)] + f"[{link}]({link})"
        body.append({"type": "TextBlock", "text": shown, "wrap": True})
    return json.dumps(
        {
            "type": "message",
            "attachments": [{"contentType": "application/vnd.microsoft.card.adaptive", "content": {"body": body}}],
        }
    )


def adf(lines):
    content = []
    for text, link in lines:
        if link is None:
            content.append({"type": "paragraph", "content": [{"type": "text", "text": text}]})
        else:
            content.append(
                {
                    "type": "paragraph",
                    "content": [
                        {"type": "text", "text": text[: -len(link)]},
                        {"type": "text", "text": link, "marks": [{"type": "link", "attrs": {"href": link}}]},
                    ],
                }
            )
    return {"type": "doc", "version": 1, "content": content}


STORM_ID = "11111111-1111-4111-8111-111111111111"
REPORT_ID = "22222222-2222-4222-8222-222222222222"
REQUEST_ID = "33333333-3333-4333-8333-333333333333"
TENANT_ID = "44444444-4444-4444-8444-444444444444"
SUMMARY = "Subscriber DB server /data is full of transaction log archives (E1); RB-DB-01 applies."
TYPE_COUNTS = [2, 20, 30, 24, 24, 6, 20]
TEAM_NAMES = {
    "db-ops": "Database Operations",
    "vas-service-owners": "VAS Service Owners",
    "noc-l1": "NOC L1",
    "it-apps": "IT Applications",
}


class World:
    """The state of the systems the driver reaches; answers depend on what the driver did so far."""

    def __init__(self, clock):
        self.clock = clock
        self.base = None
        self.calls = []
        self.injected_at = None
        self.acked_at = None
        self.decided_at = None
        self.notify_delay = 30
        self.no_incidents = False
        self.seed_state = "succeeded"
        self.zabbix_problems = 0
        self.quiet_reads = 0
        self.wrong = {}
        self.clear_event = None
        self.tenant_event = None
        self.csrf = "csrf-value-for-tests"

    # Times
    def t(self, seconds):
        return iso(self.injected_at + seconds)

    def notified(self):
        return self.injected_at is not None and self.clock.time() >= self.injected_at + self.notify_delay

    # Data
    def storm_header(self):
        resolved = self.decided_at is not None
        header = {
            "incident_id": STORM_ID,
            "number": 1,
            "title": checks.STORM_TITLE,
            "state": "resolved" if resolved else "open",
            "stage": "recovery",
            "severity": "P1",
            "owner": {"team_key": "db-ops", "name": "Database Operations"},
            "informed": [{"team_key": "vas-service-owners", "name": "VAS Service Owners"}],
            "rule_name": "Critical service affected",
            "acknowledged": self.ack(),
            "escalation": None,
            "first_alert_fired_at": self.t(10),
            "resolved_at": iso(self.decided_at + 60) if resolved else None,
            "ticket": {"key": "NOC-1", "url": f"{self.base}/browse/NOC-1"},
            "reopen_count": 0,
            "alert_count": self.wrong.get("alert_count", 126),
            "hypothesis": {
                "component_key": "srv-db-01",
                "name": "Subscriber DB server",
                "kind": "server",
                "criticality": "critical",
                "other_affected_count": 6,
                "dependent_count": 6,
                "tie_break": "none",
            },
            "affected_services": [
                {"service_key": key, "name": name, "criticality": "critical", "via": []}
                for key, name in zip(
                    ["svc-balance-check", "svc-bundle-purchase", "svc-sms"], checks.STORM_SERVICES, strict=True
                )
            ],
        }
        return header

    def report_header(self):
        return {
            **self.storm_header(),
            "incident_id": REPORT_ID,
            "number": 2,
            "title": checks.REPORT_TITLE,
            "state": "open",
            "severity": "P4",
            "owner": {"team_key": "it-apps", "name": "IT Applications"},
            "informed": [],
            "rule_name": "Default",
            "acknowledged": None,
            "resolved_at": None,
            "first_alert_fired_at": self.t(12),
            "ticket": {"key": "NOC-2", "url": f"{self.base}/browse/NOC-2"},
            "alert_count": 1,
            "hypothesis": None,
            "affected_services": [],
        }

    def ack(self):
        if self.acked_at is None:
            return None
        return {"user": {"user_id": "u-noc", "display_name": "Hira Baig (NOC L1)"}, "at": iso(self.acked_at)}

    def alert(self, index, kind, name, status):
        return {
            "alert_id": f"a{index}",
            "fired_at": self.t(10 + index % 90),
            "source_kind": kind,
            "title": f"{name} {index}",
            "component_name": "Subscriber DB server",
            "status": status,
            "reason": {
                "relation": "depends_on" if index else "only_alert",
                "reopened": False,
                "related": None,
                "path": [{"component_key": "srv-db-01", "name": "Subscriber DB server"}] if index else [],
                "time_delta_seconds": 5 if index else None,
                "identity": None,
            },
        }

    def storm_alerts(self):
        status = "resolved" if self.decided_at is not None else "firing"
        groups, index = [], 0
        for name, count in zip(checks.STORM_TYPES, TYPE_COUNTS, strict=True):
            alerts = []
            for _ in range(count):
                alerts.append(self.alert(index, "zabbix" if index < 31 else "alertmanager", name, status))
                index += 1
            groups.append({"alert_type": name.lower(), "alert_type_name": name, "count": count, "alerts": alerts})
        return {"firing": 0, "resolved": 126, "grouping_window_seconds": 300, "groups": groups}

    def report_alerts(self):
        alert = self.alert(0, "zabbix", "Report export job failed", "firing")
        group = {"alert_type": "report_failed", "alert_type_name": "Report export job failed", "count": 1}
        return {"firing": 1, "resolved": 0, "grouping_window_seconds": 300, "groups": [{**group, "alerts": [alert]}]}

    def evidence(self):
        entries = [
            {"evidence_entry_id": "e1", "kind": "diagnostic_result", "summary": DEV["disk_usage"],
             "source_system": "lab-db-01 via runner, operation fs_usage"},
            {"evidence_entry_id": "e2", "kind": "diagnostic_result", "summary": DEV["largest_folders"],
             "source_system": "lab-db-01 via runner, operation largest_dirs"},
            {"evidence_entry_id": "e3", "kind": "diagnostic_result", "summary": DEV["disk_growth"],
             "source_system": "lab-db-01 via runner, operation recent_growth"},
            {"evidence_entry_id": "e4", "kind": "ai_investigation", "summary": "confirms", "source_system": "claude"},
            {"evidence_entry_id": "e5", "kind": "similarity_match", "summary": "PI-1001", "source_system": "embed"},
        ]  # fmt: skip
        if self.decided_at is not None:
            entries.append(
                {
                    "evidence_entry_id": "e6",
                    "kind": "action_execution",
                    "summary": "Purge archived transaction logs on Subscriber DB server: succeeded; "
                    f"{DEV['action_files_removed']} files removed ({DEV['action_gib_removed']} GiB).",
                    "source_system": "lab-db-01 via runner, operation purge_old_archives",
                }
            )
        for entry in entries:
            entry.update(collected_at=self.t(60), output_values=None)
        return {"entries": entries}

    def investigation(self):
        return {
            "ai_status": "available",
            "ai_unavailable_reason": None,
            "ai_status_changed_at": self.t(70),
            "ai_last_request_group_id": "g1",
            "result": {
                "likely_cause": "/data on the Subscriber DB server is full of transaction log archives.",
                "verdict": "confirms",
                "evidence_used": [{"evidence_entry_id": "e1", "summary": DEV["disk_usage"]}],
                "uncertainty": "Whether other components are affected independently.",
                "runbooks_supplied": ["RB-API-01", "RB-DB-01", "RB-USSD-01"],
                "runbooks_cited": ["RB-DB-01"],
                "provider_kind": "claude",
                "model_name": "claude-fake",
                "created_at": self.t(70),
                "origin": "workflow_step",
                "requested_by": None,
            },
            "summary": {"text": SUMMARY, "source": "ai"},
        }

    def proposal(self):
        return {
            "action_name": checks.ACTION_NAME,
            "parameters": [{"name": n, "value": v} for n, v in checks.PARAMETERS],
            "target": {"target_key": "lab-db-01", "name": "Subscriber DB server"},
        }

    def response(self):
        approvals, executions = [], []
        if self.acked_at is not None:
            approvals.append(
                {
                    "approval_request_id": REQUEST_ID,
                    "sequence_in_run": 1,
                    "eligible_teams": [{"team_key": "db-ops", "name": "Database Operations"}],
                    "state": "approved" if self.decided_at is not None else "pending",
                    "refusal_reason": None,
                    "decided": None,
                    "expires_at": iso(self.acked_at + 900),
                }
            )
        if self.decided_at is not None:
            executions.append(
                {
                    "action_execution_id": "x1",
                    "attempt_number": 1,
                    "attempt_limit": 1,
                    "state": "succeeded",
                    "recheck": [{"check": c, "passed": True, "detail": "ok"} for c in ("approval_valid", "unchanged")]
                    + [
                        {"check": "incident_open", "passed": True, "detail": "open"},
                        {
                            "check": "precondition",
                            "passed": True,
                            "detail": "Used percentage of one filesystem (mount=/data): 100 ≥ 90",
                        },
                    ],
                    "result_summary": "34 files removed",
                    "started_at": iso(self.decided_at + 1),
                    "finished_at": iso(self.decided_at + 5),
                }
            )
        return {
            "similar": {
                "state": "done",
                "outcome": "match",
                "past_incident": {
                    "past_incident_key": "PI-1001",
                    "title": "Subscriber DB /data full after archive catch-up run",
                    "record_label": "Fictional record",
                    "fix_description": "Purged.",
                },
                "score": "1.00",
                "success_count": 4,
                "min_similarity": "0.90",
            },
            "proposal": {**self.proposal(), "policy_outcome": "approval_required"},
            "no_proposal": False,
            "approvals": approvals,
            "executions": executions,
        }

    def recovery(self):
        if self.decided_at is None:
            return {"verifications": []}
        return {
            "verifications": [
                {
                    "recovery_verification_id": "r1",
                    "check_name": "Subscriber DB services recovered",
                    "state": "recovered",
                    "observation_window_seconds": 60,
                    "started_at": iso(self.decided_at + 5),
                    "max_wait_until": iso(self.decided_at + 305),
                    "window_started_at": iso(self.decided_at + 0),
                    "decided_at": iso(self.decided_at + 60),
                    "conditions": [
                        {"detail": "every alert resolved", "held": True},
                        {"detail": "no USSD", "held": True},
                    ],
                    "sources_healthy": True,
                    "unhealthy_sources": [],
                }
            ]
        }

    def run(self, which):
        if which == "report":
            steps, state = checks.REPORT_STEPS, "waiting"
        elif self.decided_at is not None:
            steps, state = checks.STORM_STEPS, "finished"
        else:
            steps, state = checks.STORM_STEPS[: 7 if self.notified() else 5], "waiting"
        executions = [
            {
                "step_execution_id": f"s{i}",
                "step_id": step,
                "step_type": "notification",
                "visit": 1,
                "state": "waiting" if which == "report" and i == len(steps) - 1 else "completed",
                "started_at": self.t(50),
                "named_output": None,
                "timer_due_at": None,
            }
            for i, step in enumerate(steps)
        ]
        return {
            "run": {
                "run_id": f"run-{which}",
                "state": state,
                "run_number": 1,
                "run_count": 1,
                "current_step_execution_id": None,
                "content": {},
                "team_names": [],
                "step_executions": executions,
            }
        }

    def timeline(self):
        entries = []
        if self.acked_at is not None:
            entries.append(
                {
                    "timeline_entry_id": "t1",
                    "at": iso(self.acked_at),
                    "actor": {"kind": "user", "user_id": "u-noc", "display_name": "Hira Baig (NOC L1)"},
                    "entry_type": "acknowledged",
                    "text": "Acknowledged",
                }
            )
        stable = {"stable_at": self.t(55), "alert_count": 126, "merged_group_count": 1, "hypothesis_name": "x"}
        return {"stable_grouping": stable, "entries": entries}

    def notified_teams(self, which):
        keys = checks.STORM_TEAMS if which == "storm" else checks.REPORT_TEAMS
        teams = [
            {
                "team_key": key,
                "name": TEAM_NAMES[key],
                "purpose": "incident_notification",
                "first_notified_at": self.t(30),
            }
            for key in keys
        ]
        if which == "storm" and self.acked_at is not None:
            teams.append(
                {"team_key": "db-ops", "name": "Database Operations", "purpose": "approval_request",
                 "first_notified_at": iso(self.acked_at + 2)}
            )  # fmt: skip
        return {"teams": teams}

    def row(self, header):
        return {k: header[k] for k in ("incident_id", "number", "title", "severity", "state", "stage", "owner")} | {
            "acknowledged": header["acknowledged"],
            "alert_count": header["alert_count"],
            "first_alert_fired_at": header["first_alert_fired_at"],
            "ticket": header["ticket"],
        }

    def incidents(self):
        if self.injected_at is None or self.no_incidents or self.clock.time() < self.injected_at + 50:
            return {"items": [], "next_cursor": None}
        return {"items": [self.row(self.storm_header()), self.row(self.report_header())], "next_cursor": None}

    def dashboard(self):
        if self.injected_at is None:
            values = [5.7, 41.1, 525, 46]
            live = 0
        else:
            rows = checks.past_incidents(DATA / "09-past-incidents.csv")
            computed = checks.dashboard_formulas(rows, [self.storm_header(), self.report_header()])
            values = [computed[k] for k in checks.METRIC_KEYS]
            values = [float(v) if isinstance(v, Decimal) else v for v in values]
            live = 2
        baselines = [11.0, 72.0, 0, 0]
        return {
            "metrics": [
                {
                    "key": key,
                    "unit": "minutes" if i < 2 else "count",
                    "value": value,
                    "baseline_value": baseline,
                    "baseline_label": "Illustrative baseline",
                }
                for i, (key, value, baseline) in enumerate(zip(checks.METRIC_KEYS, values, baselines, strict=True))
            ],
            "live_incidents": live,
            "past_incidents": 50,
            "record_labels": ["Fictional record"],
        }

    def ack_page(self):
        return {
            "incident_id": STORM_ID,
            "number": 1,
            "title": checks.STORM_TITLE,
            "severity": "P1",
            "owner": {"team_key": "db-ops", "name": "Database Operations"},
            "first_alert_fired_at": self.t(10),
            "alert_count": 126,
            "summary": SUMMARY,
            "state": "open",
            "acknowledged": self.ack(),
            "may_act": self.acked_at is None,
            "refusal": None,
        }

    def approval_page(self):
        return {
            "approval_request_id": REQUEST_ID,
            "sequence_in_run": 1,
            "incident": {"incident_id": STORM_ID, "number": 1, "title": checks.STORM_TITLE},
            "state": "pending",
            "refusal_reason": None,
            "snapshot_hash": "hash-1",
            **self.proposal(),
            "limits": list(checks.LIMITS),
            "attempt_number": 1,
            "attempt_limit": 1,
            "expires_at": iso(self.acked_at + 900),
            "based_on": {
                "past_incident_key": "PI-1001",
                "past_incident_title": "Subscriber DB /data full after archive catch-up run",
                "record_label": "Fictional record",
                "score": "1.00",
                "success_count": 4,
            },
            "evidence": [
                {
                    "evidence_entry_id": e["evidence_entry_id"],
                    "kind": e["kind"],
                    "summary": e["summary"],
                    "collected_at": e["collected_at"],
                }
                for e in self.evidence()["entries"]
                if e["kind"] != "action_execution"
            ],  # fmt: skip
            "recovery_checks": {
                "name": "Subscriber DB services recovered",
                "conditions": ["every alert resolved", "no USSD"],
                "observation_window_seconds": 60,
            },
            "decided": None,
            "may_act": True,
            "refusal": None,
        }

    # Deliveries
    def incident_lines(self, severity, number, title, incident_id, summary):
        link = f"{self.base}/m/acknowledge/{incident_id}"
        return [
            (checks.incident_first(severity, number, title), None),
            ("Owner: x. Informed: y.", None),
            ("Customer impact: x.", None),
            (f"Summary: {summary}", None),
            ("Acknowledge: " + link, link),
        ]

    def approval_lines(self):
        link = f"{self.base}/m/approve/{REQUEST_ID}"
        return [
            (checks.approval_first(1), None),
            (f"[P1] INC-0001 {checks.STORM_TITLE}", None),
            ("Expires: 2027-01-15 08:00:00 UTC", None),
            (f"Summary: {SUMMARY}", None),
            ("Approve or reject: " + link, link),
        ]

    def deliveries(self):
        """[(teams path or mailbox, lines, at)] delivered so far."""
        out = []
        if not self.notified():
            return out
        storm = self.incident_lines("P1", 1, checks.STORM_TITLE, STORM_ID, SUMMARY)
        report = self.incident_lines("P4", 2, checks.REPORT_TITLE, REPORT_ID, "Report summary")
        for key in checks.STORM_TEAMS:
            out.append((f"/teams-team-{key}", storm, self.t(self.notify_delay)))
        out.append(("/teams-team-it-apps", report, self.t(self.notify_delay)))
        for key in checks.STORM_TEAMS:
            out.append((f"{key}@vigil.test", storm, self.t(self.notify_delay)))
        out.append(("it-apps@vigil.test", report, self.t(self.notify_delay)))
        if self.acked_at is not None:
            for key in checks.APPROVERS:
                out.append((f"/teams-user-{key}", self.approval_lines(), iso(self.acked_at + 2)))
                out.append((f"{key}@vigil.test", self.approval_lines(), iso(self.acked_at + 2)))
        if "extra_escalation" in self.wrong:
            out.append(("/teams-team-noc-shift-lead", storm, self.t(40)))
        return out

    def teams_calls(self):
        return [
            {"at": at, "method": "POST", "path": to, "body": card(lines)}
            for to, lines, at in self.deliveries()
            if to.startswith("/")
        ]

    def mail(self):
        return [(f"m{i}", to, lines, at) for i, (to, lines, at) in enumerate(self.deliveries()) if "@" in to]

    def jira_issues(self):
        link = f"{self.base}/incidents/{STORM_ID}"
        description = adf(
            [
                (f"Incident: {checks.STORM_TITLE}", None),
                ("Alerts: 126 alerts. Types: " + ", ".join(sorted(checks.STORM_TYPES)) + ".", None),
                ("Customer impact: " + ", ".join(checks.STORM_SERVICES) + ".", None),
                ("Root-cause hypothesis: Subscriber DB server (srv-db-01). A hypothesis from the service map.", None),
                (f"AI summary: {SUMMARY}", None),
                ("Evidence:", None),
                (f"Open INC-0001 in Techfinity: {link}", link),
            ]
        )
        comments = [adf([("Resolved: recovery verified.", None), ("Conditions:", None)])] if self.decided_at else []
        storm_issue = {
            "id": "10001",
            "key": "NOC-1",
            "summary": f"{checks.LABEL} - {checks.STORM_TITLE}",
            "labels": ["vigil-abc"],
            "description": description,
            "status": "Done" if self.decided_at else "To Do",
            "comments": comments,
        }
        report_issue = {**storm_issue, "id": "10002", "key": "NOC-2", "status": "To Do", "comments": []}
        return [storm_issue, report_issue] if self.notified() else []

    # HTTP
    def answer(self, method, path, headers, body):
        self.calls.append((method, path, self.clock.time()))
        clean = path.split("?")[0]
        if clean == "/faults/clear":
            if self.clear_event is not None:
                self.tenant_event.wait(5)
                if not self.tenant_event.is_set():
                    return 500, {"error": "the tenant was not created while the clear ran"}
            return 200, {"cleared": True}
        if match := re.fullmatch(r"/faults/(SL-F-\d)/inject", clean):
            if match.group(1) == "SL-F-1":
                self.injected_at = self.clock.time()
            return 200, {}
        if clean == "/api_jsonrpc.php":
            self.quiet_reads += 1
            problems = [{"eventid": "1", "name": "x"}] if self.quiet_reads <= self.zabbix_problems else []
            return 200, {"jsonrpc": "2.0", "result": problems, "id": 1}
        if clean == "/api/v2/alerts":
            return 200, []
        if clean == "/api/v1/installation/tenants":
            if self.tenant_event is not None:
                self.tenant_event.set()
            return 200, {"tenant_id": TENANT_ID}
        if clean.endswith("/seed-imports") and method == "POST":
            return 202, {"seed_import_id": "seed-1"}
        if clean == "/api/v1/installation/seed-imports/seed-1":
            state = self.seed_state
            parts = [{"module": "vigil-identity", "outcome": "succeeded" if state == "succeeded" else "failed",
                      "counts": {}, "error": None}]  # fmt: skip
            return 200, {"seed_import_id": "seed-1", "tenant_id": TENANT_ID, "state": state, "parts": parts}
        if clean.startswith("/api/v1/installation/aliases/"):
            return 200, {"host_name": clean.rsplit("/", 1)[1], "revision": 2, "tenant_id": TENANT_ID, "set_at": "x"}
        if clean == "/api/v1/session" and method == "POST":
            return 200, {"username": body["username"], "display_name": "x", "permissions": [], "team_keys": []}
        if method == "POST" and headers.get("X-Vigil-CSRF") != self.csrf:
            return 403, {"error": {"code": "forbidden"}}
        if clean == f"/api/v1/incidents/{STORM_ID}/acknowledgements":
            self.acked_at = self.clock.time()
            return 201, self.ack()
        if clean == f"/api/v1/approval-requests/{REQUEST_ID}/decisions":
            self.decided_at = self.clock.time()
            user = {"user_id": "u-eng", "display_name": "Usman Tariq (DB Ops)"}
            return 201, {"state": "approved", "user": user, "at": iso(self.decided_at)}
        routes = {
            "/api/v1/alerts/strip": lambda: {"sources": {"healthy": 2, "total": 2, "not_healthy": []}},
            "/api/v1/incidents": self.incidents,
            "/api/v1/dashboard": self.dashboard,
            f"/api/v1/incidents/{STORM_ID}": self.storm_header,
            f"/api/v1/incidents/{REPORT_ID}": self.report_header,
            f"/api/v1/incidents/{STORM_ID}/alerts": self.storm_alerts,
            f"/api/v1/incidents/{REPORT_ID}/alerts": self.report_alerts,
            f"/api/v1/incidents/{STORM_ID}/evidence": self.evidence,
            f"/api/v1/incidents/{STORM_ID}/investigation": self.investigation,
            f"/api/v1/incidents/{STORM_ID}/response": self.response,
            f"/api/v1/incidents/{STORM_ID}/recovery": self.recovery,
            f"/api/v1/incidents/{STORM_ID}/run": lambda: self.run("storm"),
            f"/api/v1/incidents/{REPORT_ID}/run": lambda: self.run("report"),
            f"/api/v1/incidents/{STORM_ID}/timeline": self.timeline,
            f"/api/v1/incidents/{STORM_ID}/notified-teams": lambda: self.notified_teams("storm"),
            f"/api/v1/incidents/{REPORT_ID}/notified-teams": lambda: self.notified_teams("report"),
            f"/api/v1/incidents/{STORM_ID}/acknowledgement": self.ack_page,
            f"/api/v1/approval-requests/{REQUEST_ID}": self.approval_page,
            "/_fakes/calls": self.teams_calls,
            "/_fakes/issues": self.jira_issues,
            "/api/v1/messages": lambda: {"messages": [{"ID": m[0]} for m in self.mail()]},
        }
        if method == "GET" and clean in routes:
            return 200, routes[clean]()
        if match := re.fullmatch(r"/api/v1/message/(m\d+)", clean):
            for message_id, to, lines, at in self.mail():
                if message_id == match.group(1):
                    text = "\r\n".join(text for text, _ in lines) + "\r\n"
                    return 200, {"ID": message_id, "To": [{"Name": "", "Address": to}], "Subject": lines[0][0],
                                 "Text": text, "Created": at}  # fmt: skip
        if match := re.fullmatch(r"/rest/api/3/issue/(NOC-\d+)", clean):
            issue = next((i for i in self.jira_issues() if i["key"] == match.group(1)), None)
            if issue and headers.get("Authorization", "").startswith("Basic "):
                return 200, {"key": issue["key"], "fields": {"status": {"name": issue["status"]}}}
        return 404, {"error": {"code": "not_found"}}


def serve(world):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def handle_any(self):
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            try:
                body = json.loads(raw) if raw and "json" in (self.headers.get("Content-Type") or "") else None
            except ValueError:
                body = None
            status, answer = world.answer(self.command, self.path, self.headers, body)
            data = json.dumps(answer).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            if self.path == "/api/v1/session":
                self.send_header("Set-Cookie", "vigil_session=session-value; Path=/; HttpOnly; SameSite=Lax")
                self.send_header("Set-Cookie", f"vigil_csrf={world.csrf}; Path=/; SameSite=Strict")
            self.end_headers()
            self.wfile.write(data)

        do_GET = do_POST = do_PUT = handle_any

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server


@pytest.fixture
def world():
    clock = Clock()
    state = World(clock)
    server = serve(state)
    state.base = f"http://127.0.0.1:{server.server_address[1]}"
    yield state
    server.shutdown()


def make_root(tmp_path, profile="test"):
    root = tmp_path / "root"
    files = {
        "run/secrets/vigil-app/installation-token": "installation-token-value",
        "run/secrets/vigil-app/zabbix-api": "zabbix-token-value",
        "run/secrets/vigil-app/alertmanager-password": "alertmanager-password-value",
        "run/secrets/vigil-app/jira": json.dumps({"email": "vigil@vigil.test", "api_token": "jira-token-value"}),
        "run/secrets/demo-control/faults-token": "faults-token-value",
        "run/state/bundle.zip": "PK-bundle",
    }
    for user in storm.USERS:
        files[f"run/secrets/e2e/seed-password-{user}"] = f"password-of-{user}"
    for relative, content in files.items():
        (root / relative).parent.mkdir(parents=True, exist_ok=True)
        (root / relative).write_text(content)
    (root / "demo-data").symlink_to(DATA)
    (root / "results").mkdir()
    return root


def config_for(world, tmp_path, mode="immediate", deliveries="fakes", profile="test"):
    root = make_root(tmp_path)
    config = storm.Config(mode, deliveries, {"VIGIL_LAB_PROFILE": profile, "VIGIL_BASE_URL": world.base}, root)
    for name in ("faults_url", "alertmanager_url", "teams_url", "jira_fake_url", "mailpit_url"):
        setattr(config, name, world.base)
    config.zabbix_url = world.base + "/api_jsonrpc.php"
    return config


def run_driver(world, tmp_path, **options):
    config = config_for(world, tmp_path, **options)
    lines = []
    driver = storm.Driver(config, clock=world.clock.time, sleep=world.clock.sleep, out=lines.append)
    code = driver.run()
    files = sorted(config.results.glob("storm-*.json"))
    result = json.loads(files[-1].read_text()) if files else None
    return code, result, driver, lines, config


KEYS = [
    "driver",
    "mode",
    "deliveries",
    "acts",
    "profile",
    "started_at",
    "finished_at",
    "finished",
    "t0",
    "tenant_id",
    "reset",
    "incidents",
    "times",
    "investigation",
    "checklist",
    "checks",
    "not_run",
    "passed",
]


# Full runs


def test_immediate_run_passes(world, tmp_path):
    """SB-T-02 mode immediate: a run that gives every value passes; the result file has exactly its keys; exit 0."""
    code, result, driver, lines, _ = run_driver(world, tmp_path)
    failed = [c for c in result["checks"] if not c["passed"]]
    assert failed == []
    assert code == 0
    assert list(result) == KEYS
    assert result["passed"] is True and result["finished"] is True
    assert result["tenant_id"] == TENANT_ID
    assert set(result["reset"]) == {"branch_a_seconds", "quiet_seconds", "branch_b_seconds", "total_seconds"}
    assert result["incidents"]["storm"] == {"incident_id": STORM_ID, "number": 1, "ticket_key": "NOC-1"}
    assert result["investigation"]["runbooks_cited"] == ["RB-DB-01"]
    assert set(result["times"]) == {"SB-TL-01", "SB-TL-02", "SB-TL-04", "SB-TL-06", "SB-TL-07", "SB-TL-08", "SB-TL-10"}
    assert result["times"]["SB-TL-04"] == 30.0
    names = [f"{c['id']} {c['name']}" for c in result["checks"]]
    assert "SB-R-06 repeatability result" in names and "SB-S05 ticket" in names
    assert result["not_run"] == [
        "SB-S01 storm within 120 s",
        "SB-S03 diagnostic values",
        "SB-S06 action values",
        "SB-S09 dashboard values",
    ]
    assert lines[-1].startswith("result file storm-immediate-") and "PASS SB-S02 storm header" in lines
    assert result["checklist"] is None
    for secret in ("installation-token-value", "faults-token-value", "jira-token-value", "password-of-noc-l1"):
        assert secret not in json.dumps(result) and secret not in "\n".join(lines)


def test_failing_value_exits_one(world, tmp_path):
    """SB-T-02 a wrong value fails its check, passed is false and the exit code is 1."""
    world.wrong["alert_count"] = 125
    code, result, *_ = run_driver(world, tmp_path)
    assert code == 1 and result["passed"] is False and result["finished"] is True
    failed = {f"{c['id']} {c['name']}" for c in result["checks"] if not c["passed"]}
    assert failed == {"SB-S02 storm header", "SB-R-06 repeatability result"}


def test_wait_limit_ends_the_run(world, tmp_path):
    """SB-T-02 a wait that reaches its limit: finished false, the step entry not passed, exit 1."""
    world.no_incidents = True
    code, result, driver, *_ = run_driver(world, tmp_path)
    assert code == 1 and result["finished"] is False and result["passed"] is False
    entry = [c for c in result["checks"] if c["id"] == "SB-T-02"]
    assert entry[0]["name"] == "step 5 Incidents finished within T0 + 360 s" and entry[0]["passed"] is False
    assert world.clock.time() >= storm_api_epoch(result["t0"]) + 360
    names = [f"{c['id']} {c['name']}" for c in result["checks"]]
    assert "SB-SET-07 incident list empty" in names and "SB-S02 storm header" not in names
    assert result["incidents"]["storm"]["incident_id"] is None


def storm_api_epoch(text):
    return checks.epoch(text)


def test_checklist_run(world, tmp_path):
    """SB-T-02 deliveries checklist: no fake is read, the F checks are not run, the checklist is written."""
    code, result, driver, lines, config = run_driver(world, tmp_path, deliveries="checklist")
    assert code == 0, [c for c in result["checks"] if not c["passed"]]
    assert result["not_run"][:4] == [
        "SB-S01 storm within 120 s",
        "SB-S03 diagnostic values",
        "SB-S05 ticket",
        "SB-S05 incident messages",
    ]
    assert {"SB-S06 approval messages", "SB-S07 ticket comment"} <= set(result["not_run"])
    assert not any(path.startswith("/_fakes/") or path.startswith("/api/v1/message") for _, path, _ in world.calls)
    checklist = config.results / result["checklist"]
    assert re.fullmatch(r"storm-checklist-\d{8}T\d{6}Z\.md", result["checklist"])
    text = checklist.read_text().splitlines()
    assert text == checks.checklist_lines(driver.data)


def test_script_clock(world, tmp_path):
    """SB-T-02 mode script: acknowledgement not before T0 + 390 s, approval not before T0 + 480 s; exact values."""
    code, result, driver, *_ = run_driver(world, tmp_path, mode="script", profile="demo")
    t0 = checks.epoch(result["t0"])
    assert world.acked_at >= t0 + 390 and world.acked_at < t0 + 392
    assert world.decided_at >= t0 + 480 and world.decided_at < t0 + 482
    assert code == 0, [c for c in result["checks"] if not c["passed"]]
    assert result["not_run"] == []
    names = [f"{c['id']} {c['name']}" for c in result["checks"]]
    assert {"SB-S09 dashboard values", "SB-S03 diagnostic values", "SB-S06 action values"} <= set(names)
    assert result["times"]["SB-TL-06"] == pytest.approx(390, abs=2)


def test_script_abort_when_the_link_is_late(world, tmp_path):
    """SB-T-02 mode script: an Acknowledge link after T0 + 405 s ends the run as a script that cannot be followed."""
    world.notify_delay = 410
    code, result, *_ = run_driver(world, tmp_path, mode="script")
    assert code == 1 and result["finished"] is False
    entries = [c for c in result["checks"] if c["id"] == "SB-T-02"]
    assert [e["name"] for e in entries] == ["the script can be followed: Acknowledge link by 07:45"]
    assert world.acked_at is None


def test_script_abort_when_the_approval_is_late(world, tmp_path, monkeypatch):
    """SB-T-02 mode script: no approval request message by T0 + 510 s ends the run (Approval link by 09:30)."""
    original = world.approval_lines
    monkeypatch.setattr(world, "deliveries", lambda: [d for d in World.deliveries(world) if "user" not in d[0]])
    assert original
    code, result, *_ = run_driver(world, tmp_path, mode="script")
    entries = [c["name"] for c in result["checks"] if c["id"] == "SB-T-02"]
    assert entries == ["the script can be followed: Approval link by 09:30"]
    assert code == 1 and world.decided_at is None


def test_csrf_header_and_users(world, tmp_path):
    """SB-T-02 the acknowledgement and the decision carry X-Vigil-CSRF; the run logs in the three users."""
    code, *_ = run_driver(world, tmp_path)
    assert code == 0
    posts = [path for method, path, _ in world.calls if method == "POST" and path.startswith("/api/v1/")]
    assert posts.count("/api/v1/session") == 3


def test_usage(capsys):
    """SB-T-02 wrong arguments print the usage line and exit 2."""
    for arguments in ([], ["--mode"], ["--mode", "fast"], ["--deliveries", "fakes"], ["--mode", "script", "x"]):
        assert storm.main(arguments) == 2
        assert capsys.readouterr().out == storm.USAGE + "\n"
    assert storm.parse_arguments(["--mode", "script", "--deliveries", "checklist"]) == ("script", "checklist", "driver")
    assert storm.parse_arguments(["--mode", "immediate"]) == ("immediate", "fakes", "driver")
    assert storm.parse_arguments(["--mode", "immediate", "--acts", "pages"]) == ("immediate", "fakes", "pages")
    assert storm.USAGE == (
        "Usage: storm.py --mode immediate|script [--deliveries fakes|checklist] [--acts driver|pages]"
    )


def test_pages_refused_in_mode_script(capsys):
    """SB-T-02 --acts pages with mode script is refused with the usage line (exit 2)."""
    for arguments in (
        ["--mode", "script", "--acts", "pages"],
        ["--acts", "pages", "--mode", "script", "--deliveries", "fakes"],
        ["--mode", "immediate", "--acts", "both"],
        ["--mode", "immediate", "--acts", "pages", "--acts", "pages"],
    ):
        assert storm.parse_arguments(arguments) is None
        assert storm.main(arguments) == 2
        assert capsys.readouterr().out == storm.USAGE + "\n"
    assert storm.parse_arguments(["--mode", "script", "--acts", "driver"]) == ("script", "fakes", "driver")


def on_pages(world, acknowledge=True):
    """The world of a --acts pages run: the acknowledgement and the decision are made on the pages, each after the
    driver's first read of its page."""
    reads = {"ack": 0, "approval": 0}
    ack_page, approval_page = world.ack_page, world.approval_page
    engineer = {"user_id": "u-eng", "display_name": "Usman Tariq (DB Ops)"}

    def acknowledgement():
        reads["ack"] += 1
        if acknowledge and reads["ack"] == 2 and world.acked_at is None:
            world.acked_at = world.clock.time()
        page = ack_page()
        if world.acked_at is not None:
            page.update(
                may_act=True,
                refusal={"key": "already_acknowledged", "message": "Already acknowledged by Hira Baig (NOC L1) at x."},
            )
        return page

    def approval():
        reads["approval"] += 1
        if reads["approval"] == 2 and world.decided_at is None:
            world.decided_at = world.clock.time()
        page = approval_page()
        if world.decided_at is not None:
            page.update(
                state="approved",
                decided={"user": engineer, "at": iso(world.decided_at)},
                refusal={"key": "already_decided", "message": "Already approved by Usman Tariq (DB Ops) at x."},
            )
        return page

    world.ack_page = acknowledgement
    world.approval_page = approval
    return reads


def pages_driver(world, tmp_path):
    config = config_for(world, tmp_path)
    config.acts = "pages"
    lines = []
    driver = storm.Driver(config, clock=world.clock.time, sleep=world.clock.sleep, out=lines.append)
    code = driver.run()
    result = json.loads(sorted(config.results.glob("storm-*.json"))[-1].read_text())
    return code, result, driver, lines


def test_pages_run_passes(world, tmp_path):
    """SB-T-02 --acts pages: the driver sends no acknowledgement or decision, waits for the ones made on the pages
    after its first reads, and passes every check."""
    reads = on_pages(world)
    code, result, driver, lines = pages_driver(world, tmp_path)
    failed = [c for c in result["checks"] if not c["passed"]]
    assert failed == []
    assert code == 0 and result["passed"] is True and result["acts"] == "pages"
    assert reads["ack"] >= 2 and reads["approval"] >= 2
    posts = [path for method, path, _ in world.calls if method == "POST"]
    assert not any(path.endswith("/acknowledgements") or path.endswith("/decisions") for path in posts)
    link = f"{world.base}/m/acknowledge/{STORM_ID}"
    assert f"step 6 Acknowledge: waiting for the page at {link}" in lines
    assert f"step 6 Acknowledge: {link} at {iso(world.acked_at)}" in lines
    approve = f"{world.base}/m/approve/{REQUEST_ID}"
    assert f"step 7 Approve: waiting for the page at {approve}" in lines
    assert f"step 7 Approve: {approve} approved at {iso(world.decided_at)}" in lines
    d = driver.data
    assert d.acts == "pages" and d.ack_before is None and d.ack_status is None and d.decision_status is None
    assert d.decision == {
        "state": "approved",
        "user": {"user_id": "u-eng", "display_name": "Usman Tariq (DB Ops)"},
        "at": iso(world.decided_at),
    }
    names = {f"{c['id']} {c['name']}" for c in result["checks"]}
    assert {"SB-S05 acknowledge page", "SB-S06 approval page", "SB-S06 decision"} <= names


def test_pages_run_without_acknowledgement_fails_at_its_limit(world, tmp_path, monkeypatch):
    """SB-T-02 --acts pages: no acknowledgement on the page ends the run at step 6's (shortened) limit."""
    monkeypatch.setattr(storm, "PAGES_LIMIT", 10)
    on_pages(world, acknowledge=False)
    code, result, _, lines = pages_driver(world, tmp_path)
    assert code == 1 and result["finished"] is False and result["passed"] is False
    entries = [c for c in result["checks"] if c["id"] == "SB-T-02"]
    assert [e["name"] for e in entries] == ["step 6 Acknowledge finished within 10 s"]
    assert world.acked_at is None
    assert not any(method == "POST" and path.endswith("/acknowledgements") for method, path, _ in world.calls)


# The checks one by one: a passing and a failing answer each


@pytest.fixture
def good(world, tmp_path):
    """The Data of a passing script run with the demo profile."""
    code, result, driver, *_ = run_driver(world, tmp_path, mode="script", profile="demo")
    assert code == 0
    return driver.data


def mutate_alerts(d, change):
    for group in d.storm["alerts"]["groups"]:
        for alert in group["alerts"]:
            change(alert)


FAILING = {
    "SB-SET-07 incident list empty": lambda d: d.start_incidents.update(items=[{"incident_id": "x"}]),
    "SB-SET-07 sources healthy": lambda d: d.start_strip["sources"].update(healthy=1),
    "SB-V-38 seeded dashboard": lambda d: d.start_dashboard["metrics"][2].update(value=524),
    "SB-V-39 baselines": lambda d: d.start_dashboard["metrics"][0].update(baseline_label="Baseline"),
    "SB-R-06 two incidents": lambda d: d.incidents["items"].pop(),
    "SB-S01 storm alerts": lambda d: d.storm["alerts"]["groups"][0]["alerts"][0].update(source_kind="alertmanager"),
    "SB-S01 storm alert types": lambda d: d.storm["alerts"]["groups"][0].update(alert_type_name="Disk nearly full"),
    "SB-S01 report alert": lambda d: d.report["alerts"]["groups"][0]["alerts"][0].update(source_kind="alertmanager"),
    "SB-S01 storm within 120 s": lambda d: mutate_alerts(d, lambda a: a.update(fired_at=iso(d.t0 + 121))),
    "SB-S02 storm header": lambda d: d.storm["header"]["affected_services"].reverse(),
    "SB-S02 grouping reasons": lambda d: mutate_alerts(d, lambda a: a["reason"].update(time_delta_seconds=301)),
    "SB-S02 report incident": lambda d: d.report["notified"]["teams"].append(
        {"team_key": "it-apps", "purpose": "escalation"}
    ),
    "SB-S03 diagnostics": lambda d: d.storm["evidence"]["entries"][0].update(
        summary="/data is 80% full (1.0 of 2.0 GiB used, 1.0 GiB free)"
    ),  # noqa: E501
    "SB-S03 diagnostic values": lambda d: d.dev_values.update(disk_growth="1.0 GiB written to 1 files under /data"),
    "SB-S04 routing": lambda d: d.storm["header"]["informed"].append({"team_key": "customer-care", "name": "CC"}),
    "SB-S04 investigation": lambda d: d.storm["investigation"]["result"].update(runbooks_cited=["RB-OTHER"]),
    "SB-S05 ticket": lambda d: d.jira_issues[0].update(summary=checks.STORM_TITLE),
    "SB-S05 incident messages": lambda d: d.teams_calls.append(dict(d.teams_calls[0])),
    "SB-S05 acknowledge page": lambda d: d.ack_after.update(acknowledged=None),
    "SB-S05 timeline acknowledgement": lambda d: d.storm["timeline"]["entries"][0].update(at=iso(1)),
    "SB-S06 similar incident": lambda d: d.storm["response"]["similar"].update(score="0.99"),
    "SB-S06 proposal": lambda d: d.storm["response"]["proposal"]["parameters"].reverse(),
    "SB-S06 approval messages": lambda d: [
        c.update(path="/teams-user-other") for c in d.teams_calls if "user-dbops-lead" in c["path"]
    ],  # fmt: skip
    "SB-S06 approval page": lambda d: d.approval_page["limits"].pop(),
    "SB-S06 decision": lambda d: setattr(d, "decision_status", 422),
    "SB-S06 action executed once": lambda d: d.storm["response"]["executions"][0]["recheck"].reverse(),
    "SB-S06 action result": lambda d: d.storm["evidence"]["entries"][-1].update(source_system="other"),
    "SB-S06 action values": lambda d: d.storm["response"]["executions"][0]["recheck"][3].update(
        detail="Used percentage of one filesystem (mount=/data): 99 ≥ 90"
    ),
    "SB-S07 recovery": lambda d: d.storm["recovery"]["verifications"][0]["conditions"][0].update(held=False),
    "SB-S07 resolved": lambda d: d.storm["alerts"]["groups"][0]["alerts"][0].update(status="firing"),
    "SB-S07 ticket resolved": lambda d: setattr(d, "ticket_done", False),
    "SB-S07 ticket comment": lambda d: d.jira_issues[0].update(comments=[]),
    "SB-S08 storm run": lambda d: d.storm["run"]["run"]["step_executions"][3].update(visit=2),
    "SB-S09 dashboard formulas": lambda d: d.dashboard["metrics"][1].update(value=Decimal("40.6")),
    "SB-S09 dashboard values": lambda d: d.dashboard["metrics"][3].update(value=48),
    "SB-TL-05 no escalation": lambda d: d.storm["notified"]["teams"].append(
        {"team_key": "noc-shift-lead", "purpose": "escalation"}
    ),
}


def entries_of(data):
    made, _ = checks.run_checks(data)
    return {f"{e['id']} {e['name']}": e for e in made}


def test_every_check_has_a_failing_answer():
    """SB-T-02 the list of checks is the complete list of Section 6.3 (37 checks)."""
    names = [f"{c[0]} {c[1]}" for c in checks.CHECKS]
    assert len(names) == 37
    assert sorted(set(names) - {"SB-R-06 repeatability result"}) == sorted(FAILING)


@pytest.mark.parametrize("name", sorted(FAILING))
def test_check_passes_and_fails(good, name):
    """SB-T-02 each check of Section 6.3 passes on the good answers and fails on a wrong one."""
    before = entries_of(good)
    assert before[name]["passed"] is True, before[name]
    bad = copy.deepcopy(good)
    FAILING[name](bad)
    after = entries_of(bad)
    assert after[name]["passed"] is False, after[name]
    for value in ("installation-token-value", "jira-token-value"):
        assert value not in json.dumps(after[name])


def test_r06_passes_only_when_its_eight_checks_pass(good):
    """SL-A3-04 the SB-R-06 result passes only when its eight checks pass."""
    assert entries_of(good)["SB-R-06 repeatability result"]["passed"] is True
    for name in checks.R06_PARTS:
        bad = copy.deepcopy(good)
        FAILING[name](bad)
        entry = entries_of(bad)["SB-R-06 repeatability result"]
        assert entry["passed"] is False and name in entry["detail"]
    other = copy.deepcopy(good)
    FAILING["SB-S03 diagnostics"](other)
    assert entries_of(other)["SB-R-06 repeatability result"]["passed"] is True


def test_missing_data_skips_checks(good):
    """SB-T-02 after a failing step only the checks whose data exists are made."""
    early = checks.Data(good.base_url, "immediate", "fakes", "test")
    early.start_strip, early.start_incidents, early.start_dashboard = (
        good.start_strip,
        good.start_incidents,
        good.start_dashboard,
    )
    made, not_run = checks.run_checks(early)
    assert [f"{e['id']} {e['name']}" for e in made] == [
        "SB-SET-07 incident list empty",
        "SB-SET-07 sources healthy",
        "SB-V-38 seeded dashboard",
        "SB-V-39 baselines",
        "SB-R-06 repeatability result",
    ]
    assert made[-1]["passed"] is False


# Messages, links and ADF


def test_links_from_a_teams_card_and_a_mailpit_message():
    """SB-T-02 the Acknowledge and Approve links of a Teams card and a Mailpit message."""
    link = "http://demo.vigil.test:8080/m/acknowledge/abc"
    message = checks.teams_message(
        {"path": "/teams-team-noc-l1", "at": "t", "body": card([("First", None), ("Acknowledge: " + link, link)])}
    )
    assert (message.recipient, message.first, message.links) == ("/teams-team-noc-l1", "First", {"Acknowledge: ": link})
    other = json.loads(card([("First", None)]))
    other["attachments"][0]["content"]["body"].append({"text": f"Acknowledge: [{link}]({link}x)"})
    assert checks.teams_message({"path": "/p", "body": json.dumps(other)}).links["Acknowledge: "] != link
    assert checks.teams_message({"path": "/p", "body": "not json"}) is None
    approve = "http://demo.vigil.test:8080/m/approve/r1"
    mail = checks.mail_message(
        {"To": [{"Address": "dbops-lead@vigil.test"}], "Subject": "Approval needed",
         "Text": f"Approval needed\r\nSummary: s\r\nApprove or reject: {approve}\r\n"}
    )  # fmt: skip
    assert (mail.recipient, mail.first, mail.links) == ("dbops-lead@vigil.test", "Approval needed", {
        "Approve or reject: ": approve
    })  # fmt: skip
    assert checks.holding([message, mail], "Approve or reject: ", "/m/approve/r1") == [mail]


def test_adf_paragraph_texts():
    """SB-T-02 the ADF paragraphs' text nodes are joined per paragraph."""
    link = "http://demo.vigil.test:8080/incidents/x"
    document = adf([("Incident: t", None), ("Open INC-0001 in Techfinity: " + link, link)])
    document["content"].append({"type": "paragraph"})
    assert checks.adf_paragraphs(document) == ["Incident: t", "Open INC-0001 in Techfinity: " + link, ""]


# DM-R-53


def test_dashboard_formulas_on_the_csv_alone():
    """SB-T-02 DM-R-53 on the 50 past incidents alone gives DD-K-11's 5.7, 41.1, 525 and 46."""
    values = checks.dashboard_formulas(checks.past_incidents(DATA / "09-past-incidents.csv"), [])
    assert values == {
        "time_to_acknowledge": Decimal("5.7"),
        "time_to_recover": Decimal("41.1"),
        "alerts_suppressed": 525,
        "incidents_merged": 46,
    }


def test_dashboard_formulas_with_two_live_incidents():
    """SB-T-02 DM-R-53 with the storm (126 alerts, acknowledged after 6.0, resolved after 9.8 min) and the report."""
    fired = "2026-11-12T10:00:00.000Z"
    storm_incident = {
        "first_alert_fired_at": fired,
        "acknowledged": {"at": "2026-11-12T10:06:00.000Z"},
        "resolved_at": "2026-11-12T10:09:48.000Z",
        "alert_count": 126,
    }
    report = {"first_alert_fired_at": fired, "acknowledged": None, "resolved_at": None, "alert_count": 1}
    values = checks.dashboard_formulas(checks.past_incidents(DATA / "09-past-incidents.csv"), [storm_incident, report])
    assert values == {
        "time_to_acknowledge": Decimal("5.7"),
        "time_to_recover": Decimal("40.5"),
        "alerts_suppressed": 650,
        "incidents_merged": 47,
    }


def test_half_up_rounding_and_milliseconds():
    """SB-T-02 means are rounded half-up to one place over whole milliseconds, as Metrics.mean."""
    assert checks.half_up_mean(3_000, 1) == Decimal("0.1")  # 0.05 min rounds up
    assert checks.half_up_mean(2_999, 1) == Decimal("0.0")
    assert checks.millis_between("2026-11-12T10:00:00.000Z", "2026-11-12T10:00:01.250Z") == 1250


def test_development_values_are_read_from_the_data_file():
    """SB-T-02 the development values of 10-lab-and-faults.yaml, not the placeholders above them."""
    values = checks.development_values(DATA / "10-lab-and-faults.yaml")
    assert values["disk_usage"].startswith("/data is ") and "<" not in values["disk_usage"]
    assert re.fullmatch(r"\d+", values["action_files_removed"])
    assert re.fullmatch(r"\d+\.\d", values["action_gib_removed"])
    for _, pattern, key in checks.DIAGNOSTICS:
        assert pattern.match(values[key]), key


def test_development_values_reader(tmp_path):
    """SB-T-02 quoted and plain values, comments, and the end of the indented block."""
    path = tmp_path / "lab.yaml"
    path.write_text(
        "lab_db_server:\n"
        '  expected:\n    disk_usage: "/data is <x>"\n'
        "  development_values:     # note\n"
        '    disk_usage: "a # b"    # comment\n'
        "\n"
        "    action_gib_removed: 8.5 # c\n"
        "faults:\n  disk_usage: wrong\n"
    )
    assert checks.development_values(path) == {"disk_usage": "a # b", "action_gib_removed": "8.5"}


# The reset (AR-B-03)


def reset_for(world, clock):
    installation = storm_api.Installation(world.base, "installation-token-value")
    lab = storm_api.Lab(world.base, world.base + "/api_jsonrpc.php", world.base, "f", "z", "a")
    return storm_api.Reset(installation, lab, b"bundle", "demo.vigil.test", clock.time, clock.sleep)


def alias_calls(world):
    return [c for c in world.calls if c[1].startswith("/api/v1/installation/aliases/")]


def test_reset_branches_run_at_once_and_the_alias_is_last(world):
    """AR-B-03 the clear waits until the tenant is created (so both branches run at once); the alias comes last."""
    world.clear_event = world.tenant_event = threading.Event()
    clock = ThreadClock()
    tenant = reset_for(world, clock).run()
    assert tenant == TENANT_ID
    paths = [c[1] for c in world.calls]
    assert paths[-1] == "/api/v1/installation/aliases/demo.vigil.test"
    assert paths.index("/faults/clear") < len(paths) - 1 and "/api/v1/installation/seed-imports/seed-1" in paths


def test_reset_waits_12_s_after_the_lab_is_quiet(world):
    """AR-B-03 branch A reads until the lab is quiet, every 2 s, then waits 12 s (injected clock)."""
    world.zabbix_problems = 3
    clock = ThreadClock()
    reset = reset_for(world, clock)
    reset.run()
    branch_a = [seconds for name, seconds in clock.sleeps if seconds in (2, 12)]
    assert branch_a.count(12) == 1 and branch_a.count(2) >= 3
    assert reset.result["quiet_seconds"] == 6.0
    assert reset.result["branch_a_seconds"] == 18.0
    assert world.calls[-1][1].startswith("/api/v1/installation/aliases/")


def test_failed_import_fails_without_the_alias(world):
    """AR-B-03 an import that ends failed fails branch B and the reset, without AR-I-98."""
    world.seed_state = "failed"
    with pytest.raises(storm_api.Failed, match="ended failed"):
        reset_for(world, Clock()).run()
    assert alias_calls(world) == []


def test_branch_limit_fails_without_the_alias(world):
    """AR-B-03 branch A at its 180 s limit fails the reset without AR-I-98."""
    world.zabbix_problems = 10_000
    with pytest.raises(storm_api.Failed) as failure:
        reset_for(world, ThreadClock()).run()
    assert failure.value.within_limit == "180 s"
    assert alias_calls(world) == []


def test_reset_failure_in_a_run(world, tmp_path):
    """AR-B-03 a failed reset ends the run at step 1 with its entry; nothing is injected."""
    world.seed_state = "failed"
    code, result, *_ = run_driver(world, tmp_path)
    assert code == 1 and result["finished"] is False
    assert [c["name"] for c in result["checks"] if c["id"] == "SB-T-02"] == ["step 1 Reset finished"]
    assert not any("/inject" in path for _, path, _ in world.calls)


# The checklist


def test_checklist_lines(good):
    """SB-T-02 the checklist: one line per expected message, then the six recipients with no message."""
    lines = checks.checklist_lines(good)
    base = good.base_url
    assert lines[0] == (
        f'- [ ] Teams channel Database Operations: "{checks.LABEL} - [P1] INC-0001 {checks.STORM_TITLE}" '
        f"with the link {base}/m/acknowledge/{STORM_ID}"
    )
    assert lines[3].startswith('- [ ] mailbox of Database Operations: "')
    assert lines[6].startswith('- [ ] Teams channel IT Applications: "') and "[P4] INC-0002" in lines[6]
    assert lines[8] == (
        f'- [ ] Teams channel Approvals — Usman: "{checks.approval_first(1)}" '
        f"with the link {base}/m/approve/{REQUEST_ID}"
    )
    assert lines[10].startswith('- [ ] mailbox of Usman Tariq (DB Ops): "')
    assert lines[11].startswith('- [ ] mailbox of Farah Naqvi (DB Ops Lead): "')
    assert lines[12:] == [
        "- [ ] no message in Teams channel NOC Shift Lead",
        "- [ ] no message in Teams channel NOC Management",
        "- [ ] no message in Teams channel Customer Care",
        "- [ ] no message in mailbox of NOC Shift Lead",
        "- [ ] no message in mailbox of NOC Management",
        "- [ ] no message in mailbox of Customer Care",
    ]


# BP-R-04


def test_image_base_and_standard_library_only():
    """BP-R-04 the storm driver image is based on ubuntu:24.04 and the program imports only the standard library."""
    ours = (HERE / "Dockerfile").read_text().splitlines()
    fakes = (ROOT / "dev" / "fakes" / "Dockerfile").read_text().splitlines()
    assert [line for line in ours if line.startswith("FROM ")] == [line for line in fakes if line.startswith("FROM ")]
    assert [line for line in ours if line.startswith("FROM ")][0].startswith("FROM ubuntu:24.04@sha256:")
    assert 'ENTRYPOINT ["/usr/bin/python3", "/opt/storm/storm.py"]' in ours
    assert (HERE / ".dockerignore").read_text().split() == ["test_*.py"]
    local = {path.stem for path in HERE.glob("*.py") if not path.name.startswith("test_")}
    assert local == {"storm", "storm_api", "storm_checks"}
    for name in local:
        tree = ast.parse((HERE / f"{name}.py").read_text())
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                modules = [alias.name.split(".")[0] for alias in node.names]
            elif isinstance(node, ast.ImportFrom):
                modules = [(node.module or "").split(".")[0]]
            else:
                continue
            for module in modules:
                assert module in sys.stdlib_module_names or module in local, (name, module)
