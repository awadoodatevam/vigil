"""The checks of the storm driver (P-27 spec Section 6.3) and what they read: messages of the Teams fake and Mailpit,
Jira's ADF, the dashboard formulas of DM-R-53, the development values of 10-lab-and-faults.yaml, the checklist.

Every check is a function of the run's Data that answers (passed, detail) or raises Missing when the data it reads
does not exist (a run that ended early). Standard library only (BP-R-04).
"""

import csv
import json
import re
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime
from decimal import Decimal
from pathlib import Path

LABEL = "Demo environment — fictional data"
STORM_TITLE = "Subscriber DB server: Disk full — 3 services affected"
REPORT_TITLE = "Report export job: Report export job failed — 1 service affected"
STORM_PREFIX = "Subscriber DB server: "
REPORT_PREFIX = "Report export job: "
ACTION_NAME = "Purge archived transaction logs"
TARGET_NAME = "Subscriber DB server"
STORM_TYPES = (
    "Disk full",
    "Database write errors",
    "USSD gateway timeouts",
    "Balance-check API errors",
    "Bundle-purchase API errors",
    "SMS queue growth",
    "Synthetic USSD session failed",
)
STORM_SERVICES = ("Balance Check (USSD)", "Bundle Purchase", "SMS Notifications")
RUNBOOKS = {"RB-API-01", "RB-DB-01", "RB-USSD-01"}
# The limits of 04-operations-actions-recovery.yaml, in order.
LIMITS = [
    "Deletes only *.arc files directly under /data/txlog_archive",
    "Deletes only files last modified more than 24 hours ago",
    "Deletes at most 12 GiB, oldest files first",
    "Runs only on the Subscriber DB server (lab-db-01)",
]
PARAMETERS = [("path", "/data/txlog_archive"), ("older_than_minutes", 1440), ("max_bytes", 12884901888)]
STORM_STEPS = [
    "s-trigger",
    "s-severity",
    "s-diagnose",
    "s-investigate",
    "s-summarise",
    "s-ticket",
    "s-notify",
    "s-wait-ack",
    "s-similar",
    "s-approve",
    "s-action",
    "s-recover",
    "s-resolve",
]
REPORT_STEPS = ["s-trigger", "s-severity", "s-summarise-2", "s-ticket-2", "s-notify-2", "s-recover-2"]
DIAGNOSTICS = [
    (
        "lab-db-01 via runner, operation fs_usage",
        re.compile(r"^/data is (\d+)% full \(\d+\.\d of \d+\.\d GiB used, \d+\.\d GiB free\)$"),
        "disk_usage",
    ),
    (
        "lab-db-01 via runner, operation largest_dirs",
        re.compile(r"^Largest folder under /data: /data/txlog_archive \(\d+\.\d GiB\)$"),
        "largest_folders",
    ),
    (
        "lab-db-01 via runner, operation recent_growth",
        re.compile(r"^\d+\.\d GiB written to \d+ files under /data in the last 15 minutes$"),
        "disk_growth",
    ),
]
ACTION_SOURCE = "lab-db-01 via runner, operation purge_old_archives"
ACTION_SUMMARY = re.compile(
    r"^Purge archived transaction logs on Subscriber DB server: .+; (\d+) files removed \((\d+\.\d) GiB\)\.$"
)
PRECONDITION = re.compile(r"^Used percentage of one filesystem \(mount=/data\): (\d+) ≥ 90$")
RECHECKS = ["approval_valid", "unchanged", "incident_open", "precondition"]
METRIC_KEYS = ["time_to_acknowledge", "time_to_recover", "alerts_suppressed", "incidents_merged"]
SEEDED = [Decimal("5.7"), Decimal("41.1"), 525, 46]
BASELINES = [Decimal("11.0"), Decimal("72.0"), 0, 0]
SCRIPT_DASHBOARD = [Decimal("5.7"), Decimal("40.5"), 650, 47]
STORM_TEAMS = ["db-ops", "vas-service-owners", "noc-l1"]
REPORT_TEAMS = ["it-apps"]
APPROVERS = ["dbops-engineer", "dbops-lead"]
APPROVER_CHANNELS = {"dbops-engineer": "Approvals — Usman", "dbops-lead": "Approvals — Farah"}
APPROVER_NAMES = {"dbops-engineer": "Usman Tariq (DB Ops)", "dbops-lead": "Farah Naqvi (DB Ops Lead)"}
ESCALATION_TEAMS = {
    "noc-shift-lead": "NOC Shift Lead",
    "noc-management": "NOC Management",
    "customer-care": "Customer Care",
}
MAIL_DOMAIN = "vigil.test"
TEAMS_LINK = re.compile(r"^(Acknowledge: |Approve or reject: )\[(.*)\]\((.*)\)$")
MAIL_LINK = re.compile(r"^(Acknowledge: |Approve or reject: )(\S+)$")


class Missing(Exception):
    """The data a check reads does not exist (the run ended before it)."""


def need(*values):
    for value in values:
        if value is None:
            raise Missing()
    return values[0] if len(values) == 1 else values


def number_text(number):
    """INC- and four digits (DC-TX-01)."""
    return f"INC-{int(number):04d}"


def parse_time(text):
    """An API time (YYYY-MM-DDTHH:MM:SS.sssZ) as an aware datetime."""
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def epoch(text):
    return parse_time(text).timestamp()


def millis_between(start, end):
    """Whole milliseconds from start to end (API times), as Duration.toMillis does it."""
    delta = parse_time(end) - parse_time(start)
    return (delta.days * 86_400 + delta.seconds) * 1000 + delta.microseconds // 1000


# Messages


@dataclass
class Message:
    """One delivered message: channel teams or email, recipient (Teams path or mailbox), lines; links by label."""

    channel: str
    recipient: str
    first: str
    lines: list
    links: dict = field(default_factory=dict)
    at: str | None = None


def teams_message(call):
    """The message of a Teams fake call: the Adaptive Card of TeamsConnector.payload, or None."""
    try:
        card = json.loads(call.get("body") or "")
        body = card["attachments"][0]["content"]["body"]
        lines = [block["text"] for block in body]
    except (ValueError, KeyError, IndexError, TypeError):
        return None
    if not lines or not all(isinstance(line, str) for line in lines):
        return None
    links = {}
    for line in lines:
        match = TEAMS_LINK.match(line)
        if match:
            label, shown, target = match.groups()
            links[label] = shown if shown == target else f"[{shown}]({target})"
    return Message("teams", call.get("path"), lines[0], lines, links, call.get("at"))


def mail_message(message):
    """The message of a Mailpit message (GET /api/v1/message/{ID})."""
    to = message.get("To") or []
    recipient = to[0].get("Address") if to and isinstance(to[0], dict) else None
    lines = (message.get("Text") or "").splitlines()
    links = {}
    for line in lines:
        match = MAIL_LINK.match(line)
        if match:
            links[match.group(1)] = match.group(2)
    return Message("email", recipient, message.get("Subject") or "", lines, links, message.get("Created"))


def holding(messages, label, suffix):
    """The messages whose link of the label ends with the suffix (the incident or request they belong to)."""
    return [m for m in messages if (m.links.get(label) or "").endswith(suffix)]


def adf_paragraphs(document):
    """The texts of an ADF document's paragraphs: the text nodes of each paragraph joined."""
    paragraphs = []
    for node in (document or {}).get("content") or []:
        if isinstance(node, dict) and node.get("type") == "paragraph":
            paragraphs.append(
                "".join(
                    part.get("text", "")
                    for part in node.get("content") or []
                    if isinstance(part, dict) and part.get("type") == "text"
                )
            )
    return paragraphs


# DM-R-53


def past_incidents(path):
    with Path(path).open(encoding="utf-8", newline="") as file:
        return list(csv.DictReader(file))


def half_up_mean(total_millis, count):
    """total / (60,000 × count) minutes, rounded half-up to one decimal place, as Metrics.mean."""
    numerator, denominator = abs(total_millis) * 10, 60_000 * count
    quotient, rest = divmod(numerator, denominator)
    if 2 * rest >= denominator:
        quotient += 1
    return Decimal(-quotient if total_millis < 0 else quotient).scaleb(-1)


def dashboard_formulas(rows, live):
    """The four metrics of DM-R-53 over the CSV rows and the live incidents (AR-I-22 headers with a number)."""
    acknowledged, recovered = [], []
    alert_counts = []
    for row in rows:
        if row.get("acknowledged_after_minutes"):
            acknowledged.append(int(Decimal(row["acknowledged_after_minutes"]) * 60_000))
        if row.get("recovered_after_minutes"):
            recovered.append(int(Decimal(row["recovered_after_minutes"]) * 60_000))
        alert_counts.append(int(row["alert_count"]))
    for incident in live:
        if incident.get("acknowledged"):
            acknowledged.append(millis_between(incident["first_alert_fired_at"], incident["acknowledged"]["at"]))
        if incident.get("resolved_at"):
            recovered.append(millis_between(incident["first_alert_fired_at"], incident["resolved_at"]))
        alert_counts.append(int(incident["alert_count"]))
    counted = len(alert_counts)
    return {
        "time_to_acknowledge": half_up_mean(sum(acknowledged), len(acknowledged)) if acknowledged else None,
        "time_to_recover": half_up_mean(sum(recovered), len(recovered)) if recovered else None,
        "alerts_suppressed": sum(alert_counts) - counted,
        "incidents_merged": sum(1 for count in alert_counts if count >= 2),
    }


# The development values of 10-lab-and-faults.yaml


def development_values(path):
    """{key: text} of the lines under lab_db_server.development_values (P-27 spec Section 6.3)."""
    values = {}
    indent = None
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if indent is None:
            if line.strip().startswith("development_values:"):
                indent = len(line) - len(line.lstrip())
            continue
        if not line.strip():
            continue
        if len(line) - len(line.lstrip()) <= indent:
            break
        match = re.match(r'^\s+([A-Za-z0-9_]+):\s+(?:"(.*)"|([^\s#]+))\s*(?:#.*)?$', line)
        if match:
            values[match.group(1)] = match.group(2) if match.group(2) is not None else match.group(3)
    return values


# The run's data


@dataclass
class Data:
    """Everything the checks read; None where the run ended before it was read."""

    base_url: str
    mode: str
    deliveries: str
    profile: str
    t0: float | None = None
    start_strip: dict | None = None
    start_incidents: dict | None = None
    start_dashboard: dict | None = None
    storm_id: str | None = None
    report_id: str | None = None
    ack_before: dict | None = None
    ack_status: int | None = None
    ack_answer: dict | None = None
    ack_after: dict | None = None
    request_id: str | None = None
    approval_page: dict | None = None
    approval_read_at: float | None = None
    decision_status: int | None = None
    decision: dict | None = None
    ticket_done: bool | None = None
    ticket_detail: str | None = None
    incidents: dict | None = None
    storm: dict = field(default_factory=dict)
    report: dict = field(default_factory=dict)
    dashboard: dict | None = None
    teams_calls: list | None = None
    mail: list | None = None
    jira_issues: list | None = None
    dev_values: dict | None = None
    past_rows: list | None = None
    results: dict = field(default_factory=dict)

    def messages(self):
        teams = [m for m in (teams_message(call) for call in need(self.teams_calls)) if m is not None]
        return teams + [mail_message(m) for m in need(self.mail)]


def part(data, which, key):
    return need((data.storm if which == "storm" else data.report).get(key))


def alerts_of(section):
    return [alert for group in section.get("groups") or [] for alert in group.get("alerts") or []]


def compare(found, expected):
    return found == expected, f"expected {expected!r}, found {found!r}"


def problems(found):
    """(passed, detail) from a list of problem texts."""
    return (not found), ("as expected" if not found else "; ".join(found))


def metric(dashboard, key):
    return next((m for m in dashboard.get("metrics") or [] if m.get("key") == key), {})


def equal_number(value, expected):
    return value is not None and not isinstance(value, bool) and Decimal(value) == Decimal(expected)


# The checks (Section 6.3), in the table's order


def set07_list_empty(d):
    return compare(need(d.start_incidents).get("items"), [])


def set07_sources(d):
    sources = need(d.start_strip).get("sources") or {}
    return compare((sources.get("healthy"), sources.get("total")), (2, 2))


def v38_seeded(d):
    dashboard = need(d.start_dashboard)
    found = [metric(dashboard, key).get("value") for key in METRIC_KEYS]
    bad = [
        f"{k} {v} (expected {e})" for k, v, e in zip(METRIC_KEYS, found, SEEDED, strict=True) if not equal_number(v, e)
    ]
    if dashboard.get("live_incidents") != 0:
        bad.append(f"live_incidents {dashboard.get('live_incidents')} (expected 0)")
    if dashboard.get("past_incidents") != 50:
        bad.append(f"past_incidents {dashboard.get('past_incidents')} (expected 50)")
    return problems(bad)


def v39_baselines(d):
    dashboard = need(d.start_dashboard)
    bad = []
    for key, expected in zip(METRIC_KEYS, BASELINES, strict=True):
        found = metric(dashboard, key)
        if (
            not equal_number(found.get("baseline_value"), expected)
            or found.get("baseline_label") != "Illustrative baseline"
        ):
            bad.append(f"{key} {found.get('baseline_value')} {found.get('baseline_label')!r} (expected {expected})")
    return problems(bad)


def r06_two_incidents(d):
    items = need(d.incidents).get("items") or []
    ids = sorted(item.get("incident_id") for item in items)
    bad = []
    if ids != sorted([need(d.storm_id), need(d.report_id)]):
        bad.append(f"rows {[item.get('title') for item in items]} (expected the storm and the report incident)")
    bad += [f"{item.get('title')}: no number" for item in items if item.get("number") is None]
    return problems(bad)


def s01_storm_alerts(d):
    section = part(d, "storm", "alerts")
    total = sum(group.get("count") or 0 for group in section.get("groups") or [])
    kinds = Counter(alert.get("source_kind") for alert in alerts_of(section))
    return compare((total, kinds.get("zabbix", 0), kinds.get("alertmanager", 0)), (126, 31, 95))


def s01_storm_types(d):
    names = {group.get("alert_type_name") for group in part(d, "storm", "alerts").get("groups") or []}
    return compare(sorted(names), sorted(STORM_TYPES))


def s01_report_alert(d):
    alerts = alerts_of(part(d, "report", "alerts"))
    return compare([alert.get("source_kind") for alert in alerts], ["zabbix"])


def s01_within_120(d):
    t0 = need(d.t0)
    late = [a["fired_at"] for a in alerts_of(part(d, "storm", "alerts")) if epoch(a["fired_at"]) > t0 + 120]
    latest = max((epoch(a["fired_at"]) for a in alerts_of(part(d, "storm", "alerts"))), default=t0) - t0
    return (not late), f"latest fired_at T0 + {latest:.1f} s; {len(late)} after T0 + 120 s"


def s02_storm_header(d):
    header = part(d, "storm", "header")
    hypothesis = header.get("hypothesis") or {}
    found = {
        "title": header.get("title"),
        "alert_count": header.get("alert_count"),
        "hypothesis": [
            hypothesis.get("component_key"),
            hypothesis.get("name"),
            hypothesis.get("other_affected_count"),
            hypothesis.get("dependent_count"),
        ],
        "affected_services": [s.get("name") for s in header.get("affected_services") or []],
        "reopen_count": header.get("reopen_count"),
    }
    expected = {
        "title": STORM_TITLE,
        "alert_count": 126,
        "hypothesis": ["srv-db-01", "Subscriber DB server", 6, 6],
        "affected_services": list(STORM_SERVICES),
        "reopen_count": 0,
    }
    return compare(found, expected)


def s02_grouping_reasons(d):
    bad = []
    for alert in alerts_of(part(d, "storm", "alerts")):
        reason = alert.get("reason")
        title = alert.get("title")
        if not isinstance(reason, dict):
            bad.append(f"{title}: no reason")
            continue
        relation = reason.get("relation")
        if relation not in ("same_component", "depends_on", "depended_on_by", "only_alert"):
            bad.append(f"{title}: relation {relation}")
        if relation in ("depends_on", "depended_on_by") and not reason.get("path"):
            bad.append(f"{title}: empty path")
        delta = reason.get("time_delta_seconds")
        if delta is not None and delta > 300:
            bad.append(f"{title}: time_delta_seconds {delta}")
    return problems(bad)


def s02_report_incident(d):
    header, run, notified = part(d, "report", "header"), part(d, "report", "run"), part(d, "report", "notified")
    run = run.get("run") or {}
    steps = run.get("step_executions") or []
    found = {
        "title": header.get("title"),
        "severity": header.get("severity"),
        "owner": (header.get("owner") or {}).get("team_key"),
        "informed": header.get("informed"),
        "acknowledged": header.get("acknowledged"),
        "escalation": header.get("escalation"),
        "state": header.get("state"),
        "run_state": run.get("state"),
        "steps": [s.get("step_id") for s in steps],
        "last_step_state": steps[-1].get("state") if steps else None,
        "notified": [(t.get("team_key"), t.get("purpose")) for t in notified.get("teams") or []],
    }
    expected = {
        "title": REPORT_TITLE,
        "severity": "P4",
        "owner": "it-apps",
        "informed": [],
        "acknowledged": None,
        "escalation": None,
        "state": "open",
        "run_state": "waiting",
        "steps": REPORT_STEPS,
        "last_step_state": "waiting",
        "notified": [("it-apps", "incident_notification")],
    }
    return compare(found, expected)


def diagnostic_entries(d):
    entries = [e for e in part(d, "storm", "evidence").get("entries") or [] if e.get("kind") == "diagnostic_result"]
    return entries


def s03_diagnostics(d):
    entries = diagnostic_entries(d)
    bad = []
    if len(entries) != 3:
        bad.append(f"{len(entries)} diagnostic_result entries (expected 3)")
    for source, pattern, _ in DIAGNOSTICS:
        found = [e for e in entries if e.get("source_system") == source]
        if len(found) != 1:
            bad.append(f"{len(found)} entries from {source!r}")
            continue
        match = pattern.match(found[0].get("summary") or "")
        if not match:
            bad.append(f"{source}: summary {found[0].get('summary')!r}")
        elif match.groups() and int(match.group(1)) < 90:
            bad.append(f"{source}: {match.group(1)}% below 90")
    return problems(bad)


def s03_diagnostic_values(d):
    values = need(d.dev_values)
    entries = diagnostic_entries(d)
    bad = []
    for source, _, key in DIAGNOSTICS:
        found = [e.get("summary") for e in entries if e.get("source_system") == source]
        if found != [values.get(key)]:
            bad.append(f"{key}: expected {values.get(key)!r}, found {found!r}")
    return problems(bad)


def s04_routing(d):
    header = part(d, "storm", "header")
    found = (
        header.get("severity"),
        (header.get("owner") or {}).get("team_key"),
        (header.get("owner") or {}).get("name"),
        [t.get("team_key") for t in header.get("informed") or []],
        header.get("rule_name"),
    )
    return compare(found, ("P1", "db-ops", "Database Operations", ["vas-service-owners"], "Critical service affected"))


def s04_investigation(d):
    section = part(d, "storm", "investigation")
    result = section.get("result") or {}
    summary = section.get("summary") or {}
    diagnostic_ids = {e.get("evidence_entry_id") for e in diagnostic_entries(d)}
    supplied, cited = result.get("runbooks_supplied") or [], result.get("runbooks_cited") or []
    d.results["investigation"] = {"runbooks_cited": cited, "likely_cause": result.get("likely_cause")}
    bad = []
    if section.get("ai_status") != "available":
        bad.append(f"ai_status {section.get('ai_status')}")
    if result.get("provider_kind") != "claude":
        bad.append(f"provider_kind {result.get('provider_kind')}")
    if result.get("verdict") != "confirms":
        bad.append(f"verdict {result.get('verdict')}")
    if not result.get("likely_cause") or not result.get("uncertainty"):
        bad.append("likely_cause or uncertainty empty")
    if set(supplied) != RUNBOOKS:
        bad.append(f"runbooks_supplied {supplied}")
    if not cited or not set(cited) <= set(supplied):
        bad.append(f"runbooks_cited {cited}")
    if not any(e.get("evidence_entry_id") in diagnostic_ids for e in result.get("evidence_used") or []):
        bad.append("evidence_used holds no diagnostic_result entry")
    if summary.get("source") != "ai" or not summary.get("text"):
        bad.append(f"summary {summary}")
    passed, detail = problems(bad)
    return passed, f"{detail}; runbooks_cited {cited}"


def storm_issue(d):
    key = (part(d, "storm", "header").get("ticket") or {}).get("key")
    issues = [i for i in need(d.jira_issues) if i.get("key") == key]
    return key, issues[0] if len(issues) == 1 else None


def s05_ticket(d):
    key, issue = storm_issue(d)
    if issue is None:
        return False, f"no fake issue with the key {key!r}"
    paragraphs = adf_paragraphs(issue.get("description"))
    link = f"{d.base_url}/incidents/{d.storm_id}"
    bad = []
    if issue.get("summary") != f"{LABEL} - {STORM_TITLE}":
        bad.append(f"summary {issue.get('summary')!r}")
    labels = [label for label in issue.get("labels") or [] if label.startswith("vigil-")]
    if len(labels) != 1:
        bad.append(f"labels {issue.get('labels')}")

    def starting(prefix):
        return [p for p in paragraphs if p.startswith(prefix)]

    if f"Incident: {STORM_TITLE}" not in paragraphs:
        bad.append("no paragraph 'Incident: {title}'")
    alerts = starting("Alerts: 126 alerts. Types: ")
    if not alerts or not all(name in alerts[0] for name in STORM_TYPES):
        bad.append(f"alerts paragraph {alerts}")
    impact = starting("Customer impact: ")
    if not impact or not all(name in impact[0] for name in STORM_SERVICES):
        bad.append(f"impact paragraph {impact}")
    if not starting("Root-cause hypothesis: Subscriber DB server ("):
        bad.append("no hypothesis paragraph")
    if not starting("AI summary: "):
        bad.append("no AI summary paragraph")
    if not any(link in p for p in paragraphs):
        bad.append(f"no paragraph with {link}")
    return problems(bad)


def recipients(teams, mailboxes):
    return [("teams", f"/teams-{kind}-{key}") for kind, key in teams] + [
        ("email", f"{key}@{MAIL_DOMAIN}") for key in mailboxes
    ]


def check_messages(messages, wanted, label, suffix, first, link, extra_line=None):
    bad = []
    for channel, recipient in wanted:
        found = [m for m in holding(messages, label, suffix) if m.channel == channel and m.recipient == recipient]
        if len(found) != 1:
            bad.append(f"{recipient}: {len(found)} messages")
            continue
        message = found[0]
        if message.first != first:
            bad.append(f"{recipient}: first line {message.first!r}")
        if extra_line is not None and extra_line not in message.lines:
            bad.append(f"{recipient}: no line {extra_line!r}")
        if message.links.get(label) != link:
            bad.append(f"{recipient}: link {message.links.get(label)!r} (expected {link})")
    return bad


def incident_first(severity, number, title):
    return f"{LABEL} - [{severity}] {number_text(number)} {title}"


def s05_incident_messages(d):
    messages = d.messages()
    storm, report = part(d, "storm", "header"), part(d, "report", "header")
    summary = (part(d, "storm", "investigation").get("summary") or {}).get("text")
    bad = check_messages(
        messages,
        recipients([("team", key) for key in STORM_TEAMS], STORM_TEAMS),
        "Acknowledge: ",
        f"/m/acknowledge/{d.storm_id}",
        incident_first("P1", storm.get("number"), STORM_TITLE),
        f"{d.base_url}/m/acknowledge/{d.storm_id}",
        f"Summary: {summary}",
    )
    bad += check_messages(
        messages,
        recipients([("team", key) for key in REPORT_TEAMS], REPORT_TEAMS),
        "Acknowledge: ",
        f"/m/acknowledge/{d.report_id}",
        incident_first("P4", report.get("number"), REPORT_TITLE),
        f"{d.base_url}/m/acknowledge/{d.report_id}",
    )
    return problems(bad)


def s05_acknowledge_page(d):
    before, after = need(d.ack_before), need(d.ack_after)
    summary = (part(d, "storm", "investigation").get("summary") or {}).get("text")
    found = {
        "title": before.get("title"),
        "severity": before.get("severity"),
        "owner": (before.get("owner") or {}).get("name"),
        "summary": before.get("summary"),
        "may_act": before.get("may_act"),
        "refusal": before.get("refusal"),
        "acknowledged": before.get("acknowledged"),
        "answer": d.ack_status,
        "after": ((after.get("acknowledged") or {}).get("user") or {}).get("display_name"),
    }
    expected = {
        "title": STORM_TITLE,
        "severity": "P1",
        "owner": "Database Operations",
        "summary": summary,
        "may_act": True,
        "refusal": None,
        "acknowledged": None,
        "answer": 201,
        "after": "Hira Baig (NOC L1)",
    }
    return compare(found, expected)


def s05_timeline(d):
    at = need(d.ack_answer).get("at")
    entries = [e for e in part(d, "storm", "timeline").get("entries") or [] if e.get("entry_type") == "acknowledged"]
    found = [(e.get("actor", {}).get("kind"), e.get("actor", {}).get("display_name"), e.get("at")) for e in entries]
    return compare(found, [("user", "Hira Baig (NOC L1)", at)])


def s06_similar(d):
    similar = part(d, "storm", "response").get("similar") or {}
    past = similar.get("past_incident") or {}
    found = (
        similar.get("state"),
        similar.get("outcome"),
        past.get("past_incident_key"),
        past.get("title"),
        past.get("record_label"),
        similar.get("score"),
        similar.get("success_count"),
    )
    expected = (
        "done",
        "match",
        "PI-1001",
        "Subscriber DB /data full after archive catch-up run",
        "Fictional record",
        "1.00",
        4,
    )
    return compare(found, expected)


def action_shape(section):
    return (
        section.get("action_name"),
        [(p.get("name"), p.get("value")) for p in section.get("parameters") or []],
        ((section.get("target") or {}).get("target_key"), (section.get("target") or {}).get("name")),
    )


ACTION_SHAPE = (ACTION_NAME, PARAMETERS, ("lab-db-01", TARGET_NAME))


def s06_proposal(d):
    proposal = part(d, "storm", "response").get("proposal") or {}
    return compare((*action_shape(proposal), proposal.get("policy_outcome")), (*ACTION_SHAPE, "approval_required"))


def approval_first(number):
    return f"{LABEL} - Approval needed: {ACTION_NAME} on {TARGET_NAME} for {number_text(number)}"


def s06_approval_messages(d):
    request_id = need(d.request_id)
    bad = check_messages(
        d.messages(),
        recipients([("user", key) for key in APPROVERS], APPROVERS),
        "Approve or reject: ",
        f"/m/approve/{request_id}",
        approval_first(part(d, "storm", "header").get("number")),
        f"{d.base_url}/m/approve/{request_id}",
    )
    return problems(bad)


def s06_approval_page(d):
    page, read_at = need(d.approval_page, d.approval_read_at)
    bad = []
    if page.get("state") != "pending":
        bad.append(f"state {page.get('state')}")
    if action_shape(page) != ACTION_SHAPE:
        bad.append(f"action {action_shape(page)}")
    if page.get("limits") != LIMITS:
        bad.append(f"limits {page.get('limits')}")
    if (page.get("attempt_number"), page.get("attempt_limit")) != (1, 1):
        bad.append(f"attempt {page.get('attempt_number')} of {page.get('attempt_limit')}")
    expires = epoch(page["expires_at"]) if page.get("expires_at") else None
    if expires is None or not read_at < expires <= read_at + 900:
        bad.append(f"expires_at {page.get('expires_at')} (read at {read_at:.3f})")
    based = page.get("based_on") or {}
    if (based.get("past_incident_key"), based.get("record_label"), based.get("score"), based.get("success_count")) != (
        "PI-1001",
        "Fictional record",
        "1.00",
        4,
    ):
        bad.append(f"based_on {based}")
    kinds = Counter(e.get("kind") for e in page.get("evidence") or [])
    if kinds != Counter({"diagnostic_result": 3, "ai_investigation": 1, "similarity_match": 1}):
        bad.append(f"evidence kinds {dict(kinds)}")
    checks = page.get("recovery_checks") or {}
    if (checks.get("name"), len(checks.get("conditions") or []), checks.get("observation_window_seconds")) != (
        "Subscriber DB services recovered",
        2,
        60,
    ):
        bad.append(f"recovery_checks {checks}")
    if page.get("may_act") is not True or page.get("refusal") is not None:
        bad.append(f"may_act {page.get('may_act')}, refusal {page.get('refusal')}")
    return problems(bad)


def s06_decision(d):
    answer = d.decision or {}
    found = (d.decision_status, answer.get("state"), (answer.get("user") or {}).get("display_name"))
    need(d.decision_status)
    return compare(found, (201, "approved", "Usman Tariq (DB Ops)"))


def action_entries(d):
    return [e for e in part(d, "storm", "evidence").get("entries") or [] if e.get("kind") == "action_execution"]


def s06_executed_once(d):
    executions = part(d, "storm", "response").get("executions") or []
    found = [
        (
            e.get("attempt_number"),
            e.get("attempt_limit"),
            e.get("state"),
            [(r.get("check"), r.get("passed")) for r in e.get("recheck") or []],
        )
        for e in executions
    ]
    expected = [(1, 1, "succeeded", [(check, True) for check in RECHECKS])]
    passed, detail = compare(found, expected)
    entries = len(action_entries(d))
    return passed and entries == 1, f"{detail}; {entries} action_execution entries (expected 1)"


def action_values(d):
    """(files, GiB text, precondition value) or None for each that does not match its form."""
    entries = action_entries(d)
    summary = ACTION_SUMMARY.match(entries[0].get("summary") or "") if len(entries) == 1 else None
    executions = part(d, "storm", "response").get("executions") or []
    detail = next(
        (r.get("detail") for e in executions[:1] for r in e.get("recheck") or [] if r.get("check") == "precondition"),
        None,
    )
    precondition = PRECONDITION.match(detail or "")
    return entries, summary, detail, precondition


def s06_action_result(d):
    entries, summary, detail, precondition = action_values(d)
    bad = []
    if len(entries) != 1 or entries[0].get("source_system") != ACTION_SOURCE:
        bad.append(f"action_execution entries {[e.get('source_system') for e in entries]}")
    if summary is None:
        bad.append(f"summary {[e.get('summary') for e in entries]}")
    if precondition is None or int(precondition.group(1)) < 90:
        bad.append(f"precondition detail {detail!r}")
    return problems(bad)


def s06_action_values(d):
    values = need(d.dev_values)
    _, summary, detail, precondition = action_values(d)
    found = (
        int(summary.group(1)) if summary else None,
        summary.group(2) if summary else None,
        int(precondition.group(1)) if precondition else None,
    )
    expected = (int(values.get("action_files_removed", "-1")), values.get("action_gib_removed"), 100)
    return compare(found, expected)


def s07_recovery(d):
    verifications = part(d, "storm", "recovery").get("verifications") or []
    found = [
        (
            v.get("check_name"),
            v.get("state"),
            v.get("observation_window_seconds"),
            [c.get("held") for c in v.get("conditions") or []],
            v.get("sources_healthy"),
        )
        for v in verifications
    ]
    return compare(found, [("Subscriber DB services recovered", "recovered", 60, [True, True], True)])


def s07_resolved(d):
    header = part(d, "storm", "header")
    statuses = Counter(a.get("status") for a in alerts_of(part(d, "storm", "alerts")))
    found = (header.get("state"), header.get("resolved_at") is not None, header.get("escalation"), dict(statuses))
    expected = ("resolved", True, None, {"resolved": 126})
    passed = found[:3] == expected[:3] and set(statuses) == {"resolved"}
    return passed, f"expected state resolved with resolved_at, no escalation, every alert resolved; found {found}"


def s07_ticket_resolved(d):
    return bool(need(d.ticket_done)), d.ticket_detail or ""


def s07_ticket_comment(d):
    key, issue = storm_issue(d)
    if issue is None:
        return False, f"no fake issue with the key {key!r}"
    comments = issue.get("comments") or []
    first = adf_paragraphs(comments[-1])[:1] if comments else []
    return compare(first, ["Resolved: recovery verified."])


def s08_storm_run(d):
    run = part(d, "storm", "run").get("run") or {}
    steps = run.get("step_executions") or []
    found = (
        run.get("state"),
        run.get("run_number"),
        run.get("run_count"),
        [(s.get("step_id"), s.get("state"), s.get("visit")) for s in steps],
    )
    expected = ("finished", 1, 1, [(step, "completed", 1) for step in STORM_STEPS])
    return compare(found, expected)


def live_headers(d):
    return [part(d, "storm", "header"), part(d, "report", "header")]


def s09_formulas(d):
    dashboard = need(d.dashboard)
    expected = dashboard_formulas(need(d.past_rows), live_headers(d))
    bad = []
    for key in METRIC_KEYS:
        value = metric(dashboard, key).get("value")
        if expected[key] is None and value is None:
            continue
        if expected[key] is None or not equal_number(value, expected[key]):
            bad.append(f"{key} {value} (computed {expected[key]})")
    if dashboard.get("live_incidents") != 2 or dashboard.get("past_incidents") != 50:
        bad.append(f"live {dashboard.get('live_incidents')}, past {dashboard.get('past_incidents')} (expected 2, 50)")
    passed, detail = problems(bad)
    return passed, f"{detail}; computed {', '.join(f'{k} {expected[k]}' for k in METRIC_KEYS)}"


def s09_values(d):
    dashboard = need(d.dashboard)
    found = [metric(dashboard, key).get("value") for key in METRIC_KEYS]
    passed = all(equal_number(v, e) for v, e in zip(found, SCRIPT_DASHBOARD, strict=True))
    return passed, f"expected {[str(e) for e in SCRIPT_DASHBOARD]}, found {[str(v) for v in found]}"


def tl05_no_escalation(d):
    bad = [
        t.get("team_key") for t in part(d, "storm", "notified").get("teams") or [] if t.get("purpose") == "escalation"
    ]
    bad = [f"escalation to {key}" for key in bad]
    if d.deliveries == "fakes":
        paths = {f"/teams-team-{key}" for key in ESCALATION_TEAMS}
        boxes = {f"{key}@{MAIL_DOMAIN}" for key in ESCALATION_TEAMS}
        bad += [f"Teams call on {c.get('path')}" for c in need(d.teams_calls) if c.get("path") in paths]
        bad += [f"email to {m.recipient}" for m in (mail_message(m) for m in need(d.mail)) if m.recipient in boxes]
    return problems(bad)


R06_PARTS = [
    "SB-R-06 two incidents",
    "SB-S01 storm alerts",
    "SB-S02 storm header",
    "SB-S04 routing",
    "SB-S06 similar incident",
    "SB-S06 action executed once",
    "SB-S07 resolved",
    "SB-S07 ticket resolved",
]


def r06_result(d, entries):
    """SL-A3-04: the eight checks passed (entries: the checks already made, by 'id name')."""
    passed = {f"{e['id']} {e['name']}": e["passed"] for e in entries}
    failing = [name for name in R06_PARTS if passed.get(name) is not True]
    return (not failing), ("all eight passed" if not failing else "not passed: " + ", ".join(failing))


# (id, name, function, mode: B, S or SD, fakes only)
CHECKS = [
    ("SB-SET-07", "incident list empty", set07_list_empty, "B", False),
    ("SB-SET-07", "sources healthy", set07_sources, "B", False),
    ("SB-V-38", "seeded dashboard", v38_seeded, "B", False),
    ("SB-V-39", "baselines", v39_baselines, "B", False),
    ("SB-R-06", "two incidents", r06_two_incidents, "B", False),
    ("SB-S01", "storm alerts", s01_storm_alerts, "B", False),
    ("SB-S01", "storm alert types", s01_storm_types, "B", False),
    ("SB-S01", "report alert", s01_report_alert, "B", False),
    ("SB-S01", "storm within 120 s", s01_within_120, "S", False),
    ("SB-S02", "storm header", s02_storm_header, "B", False),
    ("SB-S02", "grouping reasons", s02_grouping_reasons, "B", False),
    ("SB-S02", "report incident", s02_report_incident, "B", False),
    ("SB-S03", "diagnostics", s03_diagnostics, "B", False),
    ("SB-S03", "diagnostic values", s03_diagnostic_values, "SD", False),
    ("SB-S04", "routing", s04_routing, "B", False),
    ("SB-S04", "investigation", s04_investigation, "B", False),
    ("SB-S05", "ticket", s05_ticket, "B", True),
    ("SB-S05", "incident messages", s05_incident_messages, "B", True),
    ("SB-S05", "acknowledge page", s05_acknowledge_page, "B", False),
    ("SB-S05", "timeline acknowledgement", s05_timeline, "B", False),
    ("SB-S06", "similar incident", s06_similar, "B", False),
    ("SB-S06", "proposal", s06_proposal, "B", False),
    ("SB-S06", "approval messages", s06_approval_messages, "B", True),
    ("SB-S06", "approval page", s06_approval_page, "B", False),
    ("SB-S06", "decision", s06_decision, "B", False),
    ("SB-S06", "action executed once", s06_executed_once, "B", False),
    ("SB-S06", "action result", s06_action_result, "B", False),
    ("SB-S06", "action values", s06_action_values, "SD", False),
    ("SB-S07", "recovery", s07_recovery, "B", False),
    ("SB-S07", "resolved", s07_resolved, "B", False),
    ("SB-S07", "ticket resolved", s07_ticket_resolved, "B", False),
    ("SB-S07", "ticket comment", s07_ticket_comment, "B", True),
    ("SB-S08", "storm run", s08_storm_run, "B", False),
    ("SB-S09", "dashboard formulas", s09_formulas, "B", False),
    ("SB-S09", "dashboard values", s09_values, "S", False),
    ("SB-TL-05", "no escalation", tl05_no_escalation, "B", False),
    ("SB-R-06", "repeatability result", None, "B", False),
]


def applies(mode_column, fakes_only, data):
    if mode_column == "S" and data.mode != "script":
        return False
    if mode_column == "SD" and (data.mode != "script" or data.profile != "demo"):
        return False
    return not (fakes_only and data.deliveries != "fakes")


def run_checks(data):
    """(entries, not_run): every applicable check whose data exists, in the table's order."""
    entries, not_run = [], []
    for check_id, name, function, mode_column, fakes_only in CHECKS:
        if not applies(mode_column, fakes_only, data):
            not_run.append(f"{check_id} {name}")
            continue
        try:
            passed, detail = r06_result(data, entries) if function is None else function(data)
        except Missing:
            continue
        except (KeyError, TypeError, ValueError, AttributeError, IndexError) as error:
            passed, detail = False, f"unexpected answer: {type(error).__name__} {error}"
        entries.append({"id": check_id, "name": name, "passed": bool(passed), "detail": detail})
    return entries, not_run


# The checklist (deliveries checklist, for P-32)


def team_names(data):
    names = dict((key, name) for key, name in ESCALATION_TEAMS.items())
    for which in ("storm", "report"):
        section = data.storm if which == "storm" else data.report
        for team in (section.get("notified") or {}).get("teams") or []:
            names[team.get("team_key")] = team.get("name")
        header = section.get("header") or {}
        for team in [header.get("owner") or {}, *(header.get("informed") or [])]:
            if team.get("team_key"):
                names[team["team_key"]] = team.get("name")
    return names


def checklist_lines(data):
    """The Markdown lines of the checklist (P-27 spec Section 6.4)."""
    names = team_names(data)
    lines = []

    def expected(keys, first, link, approvers=False):
        for key in keys:
            channel = f"Teams channel {APPROVER_CHANNELS[key]}" if approvers else f"Teams channel {names.get(key, key)}"
            lines.append(f'- [ ] {channel}: "{first}" with the link {link}')
        for key in keys:
            channel = f"mailbox of {APPROVER_NAMES[key]}" if approvers else f"mailbox of {names.get(key, key)}"
            lines.append(f'- [ ] {channel}: "{first}" with the link {link}')

    storm, report = data.storm.get("header"), data.report.get("header")
    if storm and data.storm_id:
        expected(
            STORM_TEAMS,
            incident_first("P1", storm.get("number"), STORM_TITLE),
            f"{data.base_url}/m/acknowledge/{data.storm_id}",
        )
    if report and data.report_id:
        expected(
            REPORT_TEAMS,
            incident_first("P4", report.get("number"), REPORT_TITLE),
            f"{data.base_url}/m/acknowledge/{data.report_id}",
        )
    if storm and data.request_id:
        expected(APPROVERS, approval_first(storm.get("number")), f"{data.base_url}/m/approve/{data.request_id}", True)
    for name in ESCALATION_TEAMS.values():
        lines.append(f"- [ ] no message in Teams channel {name}")
    for name in ESCALATION_TEAMS.values():
        lines.append(f"- [ ] no message in mailbox of {name}")
    return lines
