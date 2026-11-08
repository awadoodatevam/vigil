"""The SL-T-1 and SL-T-2 requests and the search texts of the provider spikes (P-06 spec Section 6.3).

Built from docs/31-demo-data/ with the functions of docs/31-demo-data/tools/check_data.py (load, Map, lab_alerts,
classify, hypothesis, impact, title), loaded with importlib from that path; its main is never called.
"""

import importlib.util
import itertools
import json
from pathlib import Path

import common

RUNBOOK_KEYS = ["RB-API-01", "RB-DB-01", "RB-USSD-01"]
DIAGNOSTIC_KEYS = ["disk_usage", "largest_folders", "disk_growth"]
CATEGORY_NAMES = {
    "alert_data": "Alert data",
    "service_context": "Service context",
    "diagnostic_output": "Diagnostic output",
    "runbook_text": "Runbook text",
    "incident_record": "Incident record",
}

SYSTEM_SL_T_1 = (
    "You are an IT and service NOC investigator. Use only the information in the supplied parts. Give the most likely "
    "cause of the incident in likely_cause. In hypothesis_verdict, say whether the likely cause confirms or disputes "
    "the supplied hypothesis, or not_applicable when no hypothesis is supplied. In evidence_labels, list the labels of "
    "the evidence entries (E1, E2, ...) the likely cause rests on. In uncertainty, state what remains uncertain. In "
    "runbooks_cited, list the key of every supplied runbook whose steps address the likely cause, and no other key. "
    "Do not decide severity, owner or any action. Answer only with the JSON object."
)
SYSTEM_SL_T_2 = (
    "Write a summary of at most 600 characters for NOC staff in summary, in plain sentences, using only the supplied "
    "parts. Answer only with the JSON object."
)
SCHEMA_SL_T_1 = {
    "type": "object",
    "properties": {
        "likely_cause": {"type": "string"},
        "hypothesis_verdict": {"type": "string", "enum": ["confirms", "disputes", "not_applicable"]},
        "evidence_labels": {"type": "array", "items": {"type": "string"}},
        "uncertainty": {"type": "string"},
        "runbooks_cited": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["likely_cause", "hypothesis_verdict", "evidence_labels", "uncertainty", "runbooks_cited"],
    "additionalProperties": False,
}
SCHEMA_SL_T_2 = {
    "type": "object",
    "properties": {"summary": {"type": "string"}},
    "required": ["summary"],
    "additionalProperties": False,
}
MAX_UNITS_SL_T_1 = 1500
MAX_UNITS_SL_T_2 = 600


def check_data_module(folder=None):
    """docs/31-demo-data/tools/check_data.py as a module."""
    folder = Path(folder or common.DATA)
    path = folder / "tools" / "check_data.py"
    spec = importlib.util.spec_from_file_location("check_data", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def joined(items):
    """Comma-separated, as check_data.py joins."""
    return ", ".join(items)


def alert_combinations(monitoring):
    """The dimension values of every lab alert, in the order lab_alerts returns the alerts: (row, {name: value})."""
    out = []
    for row in monitoring["lab_alert_catalogue"]:
        dimensions = row.get("dimensions") or {}
        names = list(dimensions)
        for combination in itertools.product(*[dimensions[name] for name in names]):
            out.append((row, dict(zip(names, combination, strict=True))))
    return out


def alert_detail(alert, row, values):
    """The {detail} of a member alert line (Section 6.3 part 1)."""
    pairs = dict(alert["pairs"])
    if alert["source"] == "zabbix":
        return f"{alert['fields']['event_name']} on {pairs['host']}"
    detail = row["alertname"]
    if pairs.get("instance") is not None:
        detail += f" on {pairs['instance']}"
    others = [f"{name}={value}" for name, value in values.items() if name != "instance"]
    if row.get("dimensions") and others:
        detail += f" ({joined(others)})"
    return detail


def runbook_text(path, key):
    """The text of a runbook by DD-R-09: the lines after the last table line below its heading, stripped."""
    lines = Path(path).read_text(encoding="utf-8").splitlines()
    start = lines.index(f"## {key}") + 1
    end = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
    section = lines[start:end]
    last_table = max(i for i, line in enumerate(section) if line.startswith("|"))
    return "\n".join(section[last_table + 1 :]).strip()


class Requests:
    """The storm incident of SL-F-1 and the parts, texts and search texts built from it."""

    def __init__(self, folder=None):
        folder = Path(folder or common.DATA)
        cd = check_data_module(folder)
        self.data = d = cd.load(str(folder))
        m = cd.Map(d["smap"])
        self.map = m
        alerts = cd.lab_alerts(d["mon"])
        combinations = alert_combinations(d["mon"])
        for alert, (row, values) in zip(alerts, combinations, strict=True):
            alert["type"] = cd.classify(d["mon"]["alert_type_mappings"], alert["source"], alert["fields"])
            alert["component"] = m.match(alert["source"], alert["pairs"])
            alert["detail"] = alert_detail(alert, row, values)
        self.storm = [alert for alert in alerts if "SL-F-1" in alert["fires_in"]]
        components = sorted({alert["component"] for alert in self.storm})
        self.hypothesis, self.counts = cd.hypothesis(m, components)
        self.services = cd.impact(m, components)
        display = {entry["alert_type"]: entry["display_name"] for entry in d["mon"]["alert_types"]}
        self.title = cd.title(m, display, self.hypothesis, self.storm, self.services)
        self.types = sorted({alert["type"] for alert in self.storm})
        diagnostics = {entry["diagnostic_key"]: entry["name"] for entry in d["ops"]["diagnostic_definitions"]}
        values = d["lab"]["lab_db_server"]["development_values"]
        self.evidence = [(diagnostics[key], values[key]) for key in DIAGNOSTIC_KEYS]
        titles = {book["runbook_key"]: book["title"] for book in d["runbooks"]}
        self.runbooks = [(key, titles[key], runbook_text(folder / "08-runbooks.md", key)) for key in RUNBOOK_KEYS]
        self.past = d["past"]

    def member_lines(self):
        return [
            f"{i}. {alert['type']} | {alert['component']} | {alert['source']} | {alert['detail']}"
            for i, alert in enumerate(self.storm, start=1)
        ]

    def hypothesis_text(self):
        component = self.map.comps[self.hypothesis]
        return (
            f"{component['name']} ({self.hypothesis}), kind {component['kind']}, "
            f"criticality {component.get('criticality')}; {self.counts[self.hypothesis]} other affected components "
            "depend on it"
        )

    def impact_text(self):
        return "\n".join(
            f"{service['name']} ({service['service_key']}), criticality {service['criticality']}, "
            f"through {joined(service['via'])}"
            for service in self.services
        )

    def parts_sl_t_1(self):
        """The parts of SL-T-1 in order: (label, data category, text)."""
        parts = [
            ("Member alerts", "alert_data", "\n".join(self.member_lines())),
            ("Hypothesis", "service_context", self.hypothesis_text()),
            ("Customer impact", "service_context", self.impact_text()),
        ]
        for i, (name, summary) in enumerate(self.evidence, start=1):
            parts.append((f"E{i}", "diagnostic_output", f"{name}: {summary}"))
        for key, title, text in self.runbooks:
            parts.append((key, "runbook_text", f"{title}\n\n{text}"))
        return parts

    def parts_sl_t_2(self, likely_cause, uncertainty):
        """The parts of SL-T-2 in order, built from an accepted SL-T-1 answer."""
        parts = [
            ("Title", "incident_record", self.title),
            ("Investigation", "incident_record", f"Likely cause: {likely_cause}\nUncertainty: {uncertainty}"),
            ("Alerts", "alert_data", f"{len(self.storm)} alerts of {len(self.types)} types: {joined(self.types)}"),
            ("Customer impact", "service_context", self.impact_text()),
            ("Hypothesis", "service_context", self.hypothesis_text()),
        ]
        for i, (_name, summary) in enumerate(self.evidence, start=1):
            parts.append((f"E{i}", "diagnostic_output", summary))
        return parts

    def storm_search_text(self):
        return search_text(self.types, [service["service_key"] for service in self.services], self.hypothesis)

    def past_search_texts(self):
        """(past_incident_key, search text) of the 50 past incidents, in file order."""
        return [
            (
                past["past_incident_key"],
                search_text(
                    past["alert_types"].split(";"),
                    past["affected_service_keys"].split(";"),
                    past["hypothesis_component_key"],
                ),
            )
            for past in self.past
        ]


def search_text(types, services, component):
    """DM-R-51, as check_data.py DD-K-09 builds it."""
    return f"alert types: {joined(sorted(types))}; services: {joined(sorted(services))}; component: {component}"


def render(parts):
    """AR-G-10: each part as '### {label} ({data category display name})' and its text, joined by one empty line."""
    return "\n\n".join(f"### {label} ({CATEGORY_NAMES[category]})\n{text}" for label, category, text in parts)


def valid_output(text, schema):
    """The parsed object when the text is valid for the schema (AR-G-11), otherwise the reason it failed (a string)."""
    try:
        value = json.loads(text)
    except (TypeError, ValueError):
        return "not JSON"
    if not isinstance(value, dict):
        return "not an object"
    properties = schema["properties"]
    for name in properties:
        if name not in value:
            return f"missing {name}"
    for name in value:
        if name not in properties:
            return f"unexpected {name}"
    for name, rule in properties.items():
        item = value[name]
        if rule["type"] == "string":
            if not isinstance(item, str):
                return f"{name} has the wrong type"
            if "enum" in rule and item not in rule["enum"]:
                return f"{name} is not one of the allowed values"
        elif rule["type"] == "array":
            if not isinstance(item, list) or not all(isinstance(entry, str) for entry in item):
                return f"{name} has the wrong type"
    return value


def is_valid(result):
    return isinstance(result, dict)


def cites_rb_db_01(result):
    """True when an accepted SL-T-1 output cites RB-DB-01; None when the output was not accepted."""
    if not is_valid(result):
        return None
    return "RB-DB-01" in result.get("runbooks_cited", [])
