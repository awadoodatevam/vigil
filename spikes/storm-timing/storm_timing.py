"""Storm timing spike SB-T-01, development run (P-04 spec Section 6.5).

Runs storms against the lab with the webhook recorder in place of vigil-app and checks every timing target of DD-F-10
that does not need Vigil. The evaluation of a run (evaluate_run) is a pure function of the recorded calls and the run's
marks, so the unit tests check it on synthetic call lists; the class Lab holds every call to the lab.
"""

import argparse
import base64
import itertools
import json
import os
import platform
import re
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
from datetime import UTC, datetime
from decimal import ROUND_HALF_UP, Decimal
from pathlib import Path

import yaml

DATA = Path("/demo-data")
RESULTS = Path("/results")
SAVE = Path("/save")
ZABBIX_API = "http://lab-zabbix-web:8080/api_jsonrpc.php"
ALERTMANAGER = "http://lab-alertmanager:9093"
PROMETHEUS = "http://lab-prometheus:9090"
RECORDER = "http://dev-recorder:8080"
FAULTS = "http://172.31.0.1:8091"
VIGIL_APP = Path("/run/secrets/vigil-app")
DEMO_CONTROL = Path("/run/secrets/demo-control")
PROMETHEUS_JOBS = ("lab-db-node", "lab-api", "lab-vas", "lab-probe")
ALL_FAULTS = ["SL-F-1", "SL-F-2", "SL-F-3"]
SSH_OPTIONS = ["-o", "BatchMode=yes", "-o", "StrictHostKeyChecking=no", "-o", "UserKnownHostsFile=/dev/null"]
SSH_OPTIONS += ["-o", "ConnectTimeout=5"]
INJECTED_FILES = "find /data/txlog_archive -maxdepth 1 -type f -name 'txlog_*.arc' -printf '%s\\n'"
SL_F_3_WITHIN_S = 30  # DD-F-10 faults.SL-F-3.expected_effect
QUIET_WITHIN_S = 120
GIB = 1073741824
MIB = 1048576
# The five summaries, written exactly as DD-F-10 monitoring_configuration.prometheus writes them (DC-A-17).
SUMMARIES = {
    "NodeFilesystemFull": "Disk full on lab-db-01 /data",
    "BalanceApiErrors": "Balance-check API errors on {instance} {endpoint}",
    "BundleApiErrors": "Bundle-purchase API errors on {instance} {endpoint}",
    "UssdGatewayTimeouts": "USSD gateway timeouts on {node}, menu {menu}",
    "SyntheticUssdSessionFailed": "Synthetic USSD session failed: {city}, {journey}",
}
ZABBIX_KEYS = {
    "event_id",
    "event_value",
    "event_name",
    "host",
    "severity",
    "tags",
    "event_date",
    "event_time",
    "recovery_date",
    "recovery_time",
}
DATE = re.compile(r"\d{4}\.\d{2}\.\d{2}")
TIME = re.compile(r"\d{2}:\d{2}:\d{2}")
KINDS = {
    "standard": {"faults": ["SL-F-1", "SL-F-3"], "action": True},
    "read-only": {"faults": ["SL-F-1", "SL-F-2", "SL-F-3"], "action": True},
    "clear": {"faults": ["SL-F-1", "SL-F-3"], "action": False},
}
# The step at which each check's observation finishes (P-04 spec Section 6.5, steps of a run).
FINISHED_AT = {
    "arrival": 4,
    "max_gap": 4,
    "sl_f_3": 4,
    "precondition": 5,
    "disk_growth": 5,
    "action": 6,
    "resolve_after_action": 7,
    "no_refire": 7,
    "sl_f_2": 7,
    "resolve_after_clear": 8,
    "no_unexpected": 9,
    "messages": 9,
    "quiet_after": 9,
}
CHECK_ORDER = (
    "arrival",
    "exactly_once",
    "counts",
    "no_unexpected",
    "max_gap",
    "sl_f_3",
    "precondition",
    "disk_growth",
    "action",
    "resolve_after_action",
    "no_refire",
    "sl_f_2",
    "resolve_after_clear",
    "messages",
    "quiet_before",
    "quiet_after",
    "steps",
)


def log(line):
    print(f"{datetime.now(UTC):%H:%M:%S} {line}", flush=True)


def rfc3339(epoch, millis=True):
    if epoch is None:
        return None
    moment = datetime.fromtimestamp(epoch, UTC)
    if millis:
        return moment.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_time(text):
    return datetime.strptime(text, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=UTC).timestamp()


def one_decimal(value):
    return None if value is None else float(Decimal(str(value)).quantize(Decimal("0.1"), ROUND_HALF_UP))


# ---------------------------------------------------------------------------------------------------------------------
# DD-F-03 catalogue, DD-F-04 operations and parsers, DD-F-10 numbers.


class Catalogue:
    """The expected lab alerts of DD-F-03 lab_alert_catalogue, as identities.

    A Zabbix identity is ("zabbix", host, trigger name); an Alertmanager identity is ("alertmanager", alertname,
    the values of the row's dimension labels in the row's order).
    """

    def __init__(self, rows):
        self.rows = rows
        self.row_of = {}
        self.zabbix_rows = {}
        self.am_rows = {}
        for row in rows:
            dimensions = row.get("dimensions") or {}
            names = list(dimensions)
            for values in itertools.product(*(dimensions[name] for name in names)):
                if row["source"] == "zabbix":
                    identity = (
                        "zabbix",
                        row["host"],
                        row["trigger_name"].format(**dict(zip(names, values, strict=True))),
                    )
                else:
                    identity = ("alertmanager", row["alertname"], tuple(values))
                self.row_of[identity] = row
            if row["source"] == "alertmanager":
                self.am_rows[row["alertname"]] = row

    def fires_in(self, fault):
        return {identity for identity, row in self.row_of.items() if fault in row["fires_in"]}

    @property
    def storm(self):
        return self.fires_in("SL-F-1")

    @property
    def sl_f_3(self):
        return self.fires_in("SL-F-3")

    @property
    def keeps_firing(self):
        return self.fires_in("SL-F-2")

    def by_id(self, row_id):
        return {identity for identity, row in self.row_of.items() if row["id"] == row_id}

    def am_identity(self, labels):
        row = self.am_rows.get(labels.get("alertname"))
        if row is None:
            return ("alertmanager", labels.get("alertname"), tuple(sorted(labels.items())))
        return ("alertmanager", row["alertname"], tuple(labels.get(name) for name in row.get("dimensions") or {}))


def load_data(folder=DATA):
    monitoring = yaml.safe_load((Path(folder) / "03-monitoring.yaml").read_text())
    operations = yaml.safe_load((Path(folder) / "04-operations-actions-recovery.yaml").read_text())
    lab = yaml.safe_load((Path(folder) / "10-lab-and-faults.yaml").read_text())
    return Catalogue(monitoring["lab_alert_catalogue"]), operations, lab["timing_targets"]["numbers"]


def gib(value):
    return Decimal(value) / Decimal(GIB)


def round_gib(value):
    return gib(value).quantize(Decimal("0.1"), ROUND_HALF_UP)


class ParseError(ValueError):
    pass


def _integer(text):
    if not re.fullmatch(r"-?\d+", text.strip()):
        raise ParseError(f"not a number: {text!r}")
    return int(text)


# The rules of DD-F-04 output_parsers, in Python.
def df_row(output, values):
    lines = output.splitlines()
    fields = lines[-1].split() if lines else []
    if len(fields) != 5 or not fields[4].endswith("%"):
        raise ParseError(f"df_row cannot read {output!r}")
    size, used, avail = (_integer(field) for field in fields[1:4])
    return {
        "mount": fields[0],
        "size_bytes": size,
        "used_bytes": used,
        "avail_bytes": avail,
        "used_pct": _integer(fields[4][:-1]),
        "size_gib": round_gib(size),
        "used_gib": round_gib(used),
        "avail_gib": round_gib(avail),
    }


def du_top(output, values):
    named = {}
    position = 0
    for line in output.splitlines():
        if "\t" not in line:
            raise ParseError(f"du_top cannot read {line!r}")
        size, path = line.split("\t", 1)
        if path == values["path"]:
            continue
        position += 1
        named[f"top{position}_path"] = path
        named[f"top{position}_gib"] = round_gib(_integer(size))
    return named


def count_bytes(output, values):
    fields = output.split()
    if len(fields) != 2:
        raise ParseError(f"count_bytes cannot read {output!r}")
    files, size = (_integer(field) for field in fields)
    return {"files": files, "bytes": size, "gib": round_gib(size)}


def single_number(output, values):
    if not re.fullmatch(r"-?\d+", output.strip()):
        raise ParseError(f"single_number cannot read {output!r}")
    return {"value": int(output.strip())}


def removed_files(output, values):
    lines = output.splitlines()
    total = 0
    for line in lines:
        fields = line.split(" ", 1)
        if len(fields) != 2:
            raise ParseError(f"removed_files cannot read {line!r}")
        total += _integer(fields[0])
    return {"files_removed": len(lines), "bytes_removed": total, "gib_removed": round_gib(total)}


PARSERS = {
    "df_row": df_row,
    "du_top": du_top,
    "count_bytes": count_bytes,
    "single_number": single_number,
    "removed_files": removed_files,
}


def quote(value):
    """AR-K-12: the value between single quotes, with every ' written as '\\''."""
    return "'" + str(value).replace("'", "'\\''") + "'"


def render(operation, values):
    """The command of a DD-F-04 operation: placeholders of declared parameters replaced by their quoted values."""
    command = operation["command_template"]
    for parameter in operation["parameters"]:
        command = command.replace("{" + parameter["name"] + "}", quote(values[parameter["name"]]))
    return command


def summary(template, values):
    return template.format(**{name: str(value) for name, value in values.items()})


# ---------------------------------------------------------------------------------------------------------------------
# Entries and checks.


def entries_of(call, catalogue):
    """The entries of one recorded call: (identity, status, key, time, source)."""
    try:
        body = json.loads(call["body"])
    except ValueError:
        return []
    if not isinstance(body, dict):
        return []
    when = parse_time(call["received_at"])
    if call["source_key"] == "zabbix-lab":
        status = {"1": "firing", "0": "resolved"}.get(body.get("event_value"), str(body.get("event_value")))
        identity = ("zabbix", body.get("host"), body.get("event_name"))
        return [{"identity": identity, "status": status, "key": body.get("event_id"), "time": when, "call": call}]
    found = []
    for alert in body.get("alerts") or []:
        identity = catalogue.am_identity(alert.get("labels") or {})
        found.append(
            {"identity": identity, "status": alert.get("status"), "key": alert.get("fingerprint"), "time": when}
        )
    return found


def check(passed, detail=""):
    return {"passed": bool(passed), "detail": detail}


def describe(identities, limit=5):
    items = sorted(identities, key=str)
    text = "; ".join(" ".join(str(part) for part in identity[1:]) for identity in items[:limit])
    return f"{len(items)}: {text}" + (" …" if len(items) > limit else "")


def firing_at(entries, identity, moment):
    before = [entry for entry in entries if entry["identity"] == identity and entry["time"] < moment]
    return bool(before) and before[-1]["status"] == "firing"


def message_problems(call, catalogue, firing_ids):
    """The problems of one recorded call against the message checks of P-04 spec Section 6.5."""
    problems = []
    if call.get("authorized") is not True or call.get("status") != 200:
        problems.append(f"call {call.get('seq')}: authorized {call.get('authorized')}, status {call.get('status')}")
    headers = {name.lower(): value for name, value in (call.get("headers") or {}).items()}
    try:
        body = json.loads(call["body"])
    except ValueError:
        return problems + [f"call {call.get('seq')}: body is not JSON"]
    seq = call.get("seq")
    if call["source_key"] == "zabbix-lab":
        if call.get("path") != "/api/v1/webhooks/zabbix-lab":
            problems.append(f"call {seq}: path {call.get('path')}")
        if not headers.get("content-type", "").startswith("application/json"):
            problems.append(f"call {seq}: Content-Type {headers.get('content-type')}")
        if headers.get("user-agent") != "zabbix-webhook":
            problems.append(f"call {seq}: User-Agent {headers.get('user-agent')}")
        if not isinstance(body, dict) or set(body) != ZABBIX_KEYS:
            keys = sorted(body) if isinstance(body, dict) else type(body).__name__
            return problems + [f"call {seq}: keys {keys}"]
        for key, value in body.items():
            if key != "tags" and not isinstance(value, str):
                problems.append(f"call {seq}: {key} is not a string")
        identity = ("zabbix", body["host"], body["event_name"])
        row = catalogue.row_of.get(identity)
        tags = body["tags"]
        if not isinstance(tags, list) or not all(
            isinstance(tag, dict) and set(tag) == {"tag", "value"} and all(isinstance(v, str) for v in tag.values())
            for tag in tags
        ):
            problems.append(f"call {seq}: tags {tags!r} is not an array of tag objects")
        elif row is None:
            problems.append(f"call {seq}: no catalogue row for {identity[1]} {identity[2]}")
        else:
            pairs = {(tag["tag"], tag["value"]) for tag in tags}
            if ("component", row["tag_component"]) not in pairs or ("vigil", "lab") not in pairs:
                problems.append(f"call {seq}: tags {tags!r}")
        if not (isinstance(body["event_date"], str) and DATE.fullmatch(body["event_date"])):
            problems.append(f"call {seq}: event_date {body['event_date']!r}")
        if not (isinstance(body["event_time"], str) and TIME.fullmatch(body["event_time"])):
            problems.append(f"call {seq}: event_time {body['event_time']!r}")
        if body["event_value"] == "1":
            if body["recovery_date"] != "" or body["recovery_time"] != "":
                problems.append(f"call {seq}: recovery date or time on a problem")
        elif body["event_value"] == "0":
            if not (isinstance(body["recovery_date"], str) and DATE.fullmatch(body["recovery_date"])):
                problems.append(f"call {seq}: recovery_date {body['recovery_date']!r}")
            if not (isinstance(body["recovery_time"], str) and TIME.fullmatch(body["recovery_time"])):
                problems.append(f"call {seq}: recovery_time {body['recovery_time']!r}")
            if body["event_id"] != firing_ids.get(identity):
                problems.append(f"call {seq}: event_id {body['event_id']} is not that of the alert's firing entry")
        else:
            problems.append(f"call {seq}: event_value {body['event_value']!r}")
        return problems
    if call.get("path") != "/api/v1/webhooks/alertmanager-lab":
        problems.append(f"call {seq}: path {call.get('path')}")
    if not isinstance(body, dict) or body.get("version") != "4" or not isinstance(body.get("alerts"), list):
        return problems + [f"call {seq}: not an Alertmanager message of version 4"]
    for alert in body["alerts"]:
        labels = alert.get("labels") or {}
        row = catalogue.am_rows.get(labels.get("alertname"))
        name = labels.get("alertname")
        if alert.get("status") not in ("firing", "resolved"):
            problems.append(f"call {seq}: {name} status {alert.get('status')!r}")
        if not alert.get("fingerprint"):
            problems.append(f"call {seq}: {name} without fingerprint")
        if not isinstance(alert.get("startsAt"), str) or not isinstance(alert.get("endsAt"), str):
            problems.append(f"call {seq}: {name} without startsAt or endsAt")
        if row is None:
            problems.append(f"call {seq}: no catalogue row for {name}")
            continue
        if labels.get("component") != row["labels"]["component"]:
            problems.append(f"call {seq}: {name} component {labels.get('component')!r}")
        try:
            expected = SUMMARIES[name].format(**labels)
        except KeyError:
            expected = None
        actual = (alert.get("annotations") or {}).get("summary")
        if actual != expected:
            problems.append(f"call {seq}: {name} summary {actual!r}")
        if name == "NodeFilesystemFull" and (
            labels.get("instance") != "lab-db-01:9100" or labels.get("mountpoint") != "/data"
        ):
            problems.append(f"call {seq}: {name} labels {labels!r}")
    return problems


def evaluate_run(run, calls, catalogue, numbers, observed):
    """The checks, timing and counts of one run (P-04 spec Section 6.5), from its recorded calls and marks.

    run: kind, faults, t0, action_end, clear_at (epoch seconds or None), completed (the last step done), stopped_at
    (the step it stopped at, or None), baseline_window (bool), steps_error (text or None), quiet_before and
    quiet_after (bool or None). observed: measured (dict) and, for read-only, zabbix_problems and
    alertmanager_alerts (identity sets) or None.
    """
    kind = run["kind"]
    faults = run["faults"]
    t0, ta, tc = run["t0"], run["action_end"], run["clear_at"]
    measured = observed.get("measured") or {}
    entries = sorted((e for call in calls for e in entries_of(call, catalogue)), key=lambda e: e["time"])
    storm = catalogue.storm
    la09 = catalogue.sl_f_3
    lab = set(catalogue.row_of)
    sl_f_3 = "SL-F-3" in faults
    window_end = ta if KINDS[kind]["action"] else tc
    arrival_end = t0 + 150 if t0 is not None else None
    checks, timing, counts = {}, dict.fromkeys(TIMING_KEYS), dict.fromkeys(COUNT_KEYS)

    def firing(identities, start, end):
        return [
            e for e in entries if e["identity"] in identities and e["status"] == "firing" and start <= e["time"] < end
        ]

    if t0 is not None and run["completed"] >= 4:
        storm_arrivals = firing(storm, t0, arrival_end + 0.001)
        first = {}
        for entry in storm_arrivals:
            first.setdefault(entry["identity"], entry["time"])
        late = {i for i in storm if i not in first or first[i] > t0 + numbers["arrival_within_s"]}
        checks["arrival"] = check(not late, f"late or missing {describe(late)}" if late else "")
        times = sorted(entry["time"] for entry in storm_arrivals)
        gaps = [later - earlier for earlier, later in itertools.pairwise(times)]
        biggest = max(gaps, default=0.0)
        if times:
            timing["first_arrival_s"] = one_decimal(times[0] - t0)
            timing["last_arrival_s"] = one_decimal(max(first.values()) - t0)
            timing["max_gap_s"] = one_decimal(biggest)
        checks["max_gap"] = check(
            bool(times) and biggest <= numbers["max_gap_s"], f"largest gap {biggest:.1f} s" if times else "no arrival"
        )
        if sl_f_3:
            la09_firing = firing(la09, t0, arrival_end + 0.001)
            arrived = la09_firing[0]["time"] - t0 if la09_firing else None
            timing["sl_f_3_arrival_s"] = one_decimal(arrived)
            on_time = arrived is not None and arrived <= SL_F_3_WITHIN_S
            checks["sl_f_3"] = check(
                on_time, "no firing entry" if arrived is None else f"arrived after {arrived:.1f} s"
            )

    if t0 is not None and window_end is not None:
        window = firing(storm | la09, t0, window_end)
        per_identity = {}
        for entry in window:
            per_identity[entry["identity"]] = per_identity.get(entry["identity"], 0) + 1
        expected_once = storm | (la09 if sl_f_3 else set())
        wrong = {i for i in expected_once if per_identity.get(i, 0) != 1}
        checks["exactly_once"] = check(not wrong, f"not exactly one firing entry {describe(wrong)}" if wrong else "")
        storm_firing = [e for e in window if e["identity"] in storm]
        counts["storm_firing"] = len(storm_firing)
        counts["zabbix_firing"] = sum(1 for e in storm_firing if e["identity"][0] == "zabbix")
        counts["alertmanager_firing"] = sum(1 for e in storm_firing if e["identity"][0] == "alertmanager")
        counts["sl_f_3_firing"] = sum(1 for e in window if e["identity"] in la09)
        zabbix_storm = sum(1 for i in storm if i[0] == "zabbix")
        expected = (len(storm), zabbix_storm, len(storm) - zabbix_storm, 1 if sl_f_3 else 0)
        actual = (
            counts["storm_firing"],
            counts["zabbix_firing"],
            counts["alertmanager_firing"],
            counts["sl_f_3_firing"],
        )
        checks["counts"] = check(actual == expected, f"counted {actual}, expected {expected}")

    if run["completed"] >= 5 and measured:
        used = measured.get("used_pct_after_injection")
        avail = measured.get("avail_bytes_after_injection")
        ok = used is not None and used >= 90 and avail is not None and 32 * MIB <= avail <= 96 * MIB
        checks["precondition"] = check(ok, f"fs_used_pct {used}, avail_bytes {avail}")
        if run["baseline_window"]:
            growth = observed.get("disk_growth_values") or {}
            files, size = growth.get("files"), growth.get("bytes")
            same = (files, size) == (measured.get("injected_files"), measured.get("injected_bytes"))
            injected = (measured.get("injected_files"), measured.get("injected_bytes"))
            checks["disk_growth"] = check(same, f"disk_growth {files} files, {size} bytes; injected {injected}")

    if KINDS[kind]["action"] and run["completed"] >= 6:
        removed = observed.get("removed") or {}
        after = measured.get("used_pct_after_action")
        baseline = measured.get("baseline_used_pct")
        ok = (
            (removed.get("files_removed"), removed.get("bytes_removed"))
            == (measured.get("injected_files"), measured.get("injected_bytes"))
            and after is not None
            and baseline is not None
            and abs(after - baseline) <= 1
        )
        checks["action"] = check(
            ok,
            f"removed {removed.get('files_removed')} files, {removed.get('bytes_removed')} bytes; "
            f"used {after} % after, {baseline} % before",
        )

    def resolved_by(identity, start, end):
        return [
            e["time"]
            for e in entries
            if e["identity"] == identity and e["status"] == "resolved" and start <= e["time"] <= end
        ]

    if kind == "standard" and run["completed"] >= 7:
        limit = ta + numbers["resolve_after_action_s"]
        resolved = {i: resolved_by(i, t0, float("inf")) for i in storm}
        late = {i for i in storm if not any(t <= limit for t in resolved[i])}
        la09_resolved = resolved_by(next(iter(la09)), t0, tc if tc is not None else float("inf"))
        firsts = [min(times) for times in resolved.values() if times]
        if firsts:
            timing["last_resolve_after_action_s"] = one_decimal(max(firsts) - ta)
        detail = f"no resolved entry by TA + 60 s {describe(late)}" if late else ""
        if la09_resolved:
            detail += " LA-09 resolved before the clear"
        checks["resolve_after_action"] = check(not late and not la09_resolved, detail.strip())
        refired = set()
        for identity in storm:
            for moment in resolved[identity][:1]:
                again = [
                    e
                    for e in entries
                    if e["identity"] == identity
                    and e["status"] == "firing"
                    and moment < e["time"] <= moment + numbers["no_refire_s"]
                ]
                if again:
                    refired.add(identity)
        checks["no_refire"] = check(not refired, f"fired again {describe(refired)}" if refired else "")

    if kind == "read-only" and run["completed"] >= 7:
        limit = ta + numbers["resolve_after_action_s"]
        disk_full = catalogue.by_id("LA-01") | catalogue.by_id("LA-04")
        keeps = catalogue.keeps_firing
        resolved = {i for i in storm if resolved_by(i, t0, limit)}
        disk_times = [min(resolved_by(i, t0, limit)) for i in disk_full if i in resolved]
        if disk_times:
            timing["last_resolve_after_action_s"] = one_decimal(max(disk_times) - ta)
        counts["still_firing_after_action"] = sum(1 for i in storm if i not in resolved)
        expected_problems = {i for i in keeps if i[0] == "zabbix"} | la09
        expected_alerts = {i for i in keeps if i[0] == "alertmanager"}
        problems = observed.get("zabbix_problems")
        alerts = observed.get("alertmanager_alerts")
        failures = []
        if resolved & storm != disk_full:
            failures.append(f"resolved by TA + 60 s {describe(resolved)}")
        if problems != expected_problems:
            failures.append("Zabbix problems " + ("not read" if problems is None else describe(problems)))
        if alerts != expected_alerts:
            failures.append("Alertmanager alerts " + ("not read" if alerts is None else describe(alerts)))
        checks["sl_f_2"] = check(not failures, "; ".join(failures))

    if tc is not None and run["completed"] >= 8:
        limit = tc + numbers["resolve_after_clear_s"]
        open_at_clear = {i for i in lab if firing_at(entries, i, tc)}
        late, times = set(), []
        for identity in open_at_clear:
            resolved = resolved_by(identity, tc, float("inf"))
            if resolved:
                times.append(resolved[0] - tc)
            if not resolved or resolved[0] > limit:
                late.add(identity)
        if times:
            timing["last_resolve_after_clear_s"] = one_decimal(max(times))
        checks["resolve_after_clear"] = check(not late, f"not resolved within 40 s {describe(late)}" if late else "")

    if run["completed"] >= 9:
        unexpected = {e["identity"] for e in entries if e["identity"] not in lab}
        checks["no_unexpected"] = check(not unexpected, f"unexpected {describe(unexpected)}" if unexpected else "")
        firing_ids = {}
        for entry in entries:
            if entry["identity"][0] == "zabbix" and entry["status"] == "firing":
                firing_ids.setdefault(entry["identity"], entry["key"])
        problems = [problem for call in calls for problem in message_problems(call, catalogue, firing_ids)]
        checks["messages"] = check(not problems, "; ".join(problems[:5]) + (" …" if len(problems) > 5 else ""))

    if run["quiet_before"] is not None:
        checks["quiet_before"] = check(run["quiet_before"], "" if run["quiet_before"] else "lab not quiet")
    if run["quiet_after"] is not None:
        checks["quiet_after"] = check(run["quiet_after"], "" if run["quiet_after"] else "lab not quiet")
    checks["steps"] = check(run["steps_error"] is None, run["steps_error"] or "")

    applicable = applicable_checks(kind, faults, run["baseline_window"])
    ordered = {}
    for name in CHECK_ORDER:
        if name not in applicable:
            continue
        if name in checks:
            ordered[name] = checks[name]
        else:
            ordered[name] = check(False, f"not evaluated: run stopped at step {run['stopped_at']}")
    return ordered, timing, counts


TIMING_KEYS = (
    "first_arrival_s",
    "last_arrival_s",
    "max_gap_s",
    "sl_f_3_arrival_s",
    "last_resolve_after_action_s",
    "last_resolve_after_clear_s",
)
COUNT_KEYS = ("storm_firing", "zabbix_firing", "alertmanager_firing", "sl_f_3_firing", "still_firing_after_action")
MEASURED_KEYS = (
    "baseline_used_pct",
    "used_pct_after_injection",
    "avail_bytes_after_injection",
    "injected_files",
    "injected_bytes",
    "injected_gib",
    "disk_usage",
    "largest_folders",
    "disk_growth",
    "action_files_removed",
    "action_gib_removed",
    "used_pct_after_action",
)


def applicable_checks(kind, faults, baseline_window):
    names = set(CHECK_ORDER)
    if "SL-F-3" not in faults:
        names.discard("sl_f_3")
    if not baseline_window:
        names.discard("disk_growth")
    if not KINDS[kind]["action"]:
        names.discard("action")
    if kind != "standard":
        names -= {"resolve_after_action", "no_refire"}
    if kind != "read-only":
        names.discard("sl_f_2")
    return names


# ---------------------------------------------------------------------------------------------------------------------
# Calls to the lab.


class HttpError(Exception):
    pass


OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http(method, url, body=None, headers=None, timeout=30):
    """(status, parsed JSON or None); raises HttpError for a connection error."""
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if data is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with OPENER.open(request, timeout=timeout) as response:
            status, raw = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, raw = error.code, error.read()
    except (OSError, ValueError) as error:
        raise HttpError(str(error)) from None
    try:
        return status, json.loads(raw) if raw else None
    except ValueError:
        return status, None


class SshError(Exception):
    pass


class Lab:
    """Every call of the spike to the lab: Zabbix, Alertmanager, Prometheus, the recorder, demo-faults, SSH."""

    def __init__(self, operations, folder):
        self.operations = {op["operation_key"]: op for op in operations["operations"]}
        self.diagnostics = {d["diagnostic_key"]: d for d in operations["diagnostic_definitions"]}
        self.action = operations["registered_actions"][0]
        self.zabbix_token = (VIGIL_APP / "zabbix-api").read_text()
        password = (VIGIL_APP / "alertmanager-password").read_text()
        self.am_auth = "Basic " + base64.b64encode(f"vigil:{password}".encode()).decode()
        self.faults_token = (DEMO_CONTROL / "faults-token").read_text()
        self.keys = {}
        for kind in ("diag", "action"):
            secret = json.loads((VIGIL_APP / f"lab-db-01-{kind}").read_text())
            path = Path(folder) / f"key-{kind}"
            descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
            with os.fdopen(descriptor, "w") as file:
                file.write(secret["private_key"].rstrip("\n") + "\n")
            self.keys[kind] = (secret["username"], str(path))
        self.recorded = []
        self.last_seq = 0

    # Zabbix
    def zabbix(self, method, params, token=None, auth=True):
        body = {"jsonrpc": "2.0", "method": method, "params": params, "id": 1}
        headers = {"Content-Type": "application/json-rpc"}
        if auth:
            headers["Authorization"] = f"Bearer {token or self.zabbix_token}"
        status, answer = http("POST", ZABBIX_API, body, headers)
        if status != 200 or not isinstance(answer, dict) or "result" not in answer:
            error = (answer or {}).get("error") if isinstance(answer, dict) else None
            raise HttpError(f"Zabbix {method}: HTTP {status} {error or ''}".strip())
        return answer["result"]

    def zabbix_problems(self):
        """The identities of Zabbix's open problems with tag vigil = lab."""
        problems = self.zabbix(
            "problem.get",
            {"output": ["eventid", "objectid", "name"], "tags": [{"tag": "vigil", "value": "lab", "operator": 1}]},
        )
        if not problems:
            return set()
        triggers = self.zabbix(
            "trigger.get",
            {"output": ["triggerid"], "triggerids": [p["objectid"] for p in problems], "selectHosts": ["host"]},
        )
        hosts = {t["triggerid"]: (t["hosts"][0]["host"] if t["hosts"] else None) for t in triggers}
        return {("zabbix", hosts.get(p["objectid"]), p["name"]) for p in problems}

    # Alertmanager
    def alertmanager_alerts(self, catalogue):
        status, alerts = http("GET", f"{ALERTMANAGER}/api/v2/alerts", headers={"Authorization": self.am_auth})
        if status != 200 or not isinstance(alerts, list):
            raise HttpError(f"Alertmanager alerts: HTTP {status}")
        names = set(SUMMARIES)
        return {
            catalogue.am_identity(a.get("labels") or {})
            for a in alerts
            if (a.get("labels") or {}).get("alertname") in names
        }

    def quiet(self, catalogue):
        try:
            return not self.zabbix_problems() and not self.alertmanager_alerts(catalogue)
        except HttpError:
            return False

    # Recorder
    def poll(self):
        status, calls = http("GET", f"{RECORDER}/_recorder/calls?after={self.last_seq}")
        if status != 200 or not isinstance(calls, list):
            raise HttpError(f"recorder: HTTP {status}")
        for call in calls:
            if call["seq"] > self.last_seq:
                self.recorded.append(call)
                self.last_seq = call["seq"]
        return calls

    # demo-faults
    def faults(self, path, body=None):
        headers = {"Authorization": f"Bearer {self.faults_token}"}
        try:
            return http("POST", f"{FAULTS}{path}", body if body is not None else {}, headers, timeout=180)
        except HttpError as error:
            return None, {"error": str(error)}

    # SSH
    def ssh(self, kind, command, timeout):
        user, key = self.keys[kind]
        try:
            result = subprocess.run(
                ["ssh", *SSH_OPTIONS, "-i", key, f"{user}@lab-db-01", command],
                capture_output=True,
                text=True,
                timeout=timeout,
                check=False,
            )
        except subprocess.TimeoutExpired:
            raise SshError(f"{command[:60]}…: no answer within {timeout} s") from None
        if result.returncode != 0:
            raise SshError(f"{command[:60]}…: exit {result.returncode}: {result.stderr.strip()[-300:]}")
        return result.stdout

    def operation(self, key, values):
        operation = self.operations[key]
        kind = "diag" if operation["permission_kind"] == "diagnostic" else "action"
        output = self.ssh(kind, render(operation, values), operation["timeout_s"])
        try:
            return PARSERS[operation["output_parser"]](output, values)
        except ParseError as error:
            raise SshError(str(error)) from None

    def diagnostic(self, key):
        definition = self.diagnostics[key]
        values = self.operation(definition["operation_key"], definition["parameter_values"])
        return values, summary(definition["summary_template"], values)

    def used_pct(self):
        precondition = self.action["precondition"]
        return self.operation(precondition["operation_key"], precondition["parameters"])["value"]

    def purge(self):
        return self.operation(self.action["operation_key"], self.action["parameter_values"])

    def injected(self):
        try:
            sizes = [_integer(line) for line in self.ssh("diag", INJECTED_FILES, 30).split()]
        except ParseError as error:
            raise SshError(str(error)) from None
        return len(sizes), sum(sizes)


# ---------------------------------------------------------------------------------------------------------------------
# The spike.


class StepFailed(Exception):
    def __init__(self, step, detail):
        super().__init__(detail)
        self.step = step
        self.detail = detail


class Spike:
    def __init__(self, args, lab, catalogue, numbers, clock=time.time, sleep=time.sleep):
        self.args = args
        self.lab = lab
        self.catalogue = catalogue
        self.numbers = numbers
        self.clock = clock
        self.sleep = sleep
        self.result = {
            "spike": "SB-T-01",
            "started_at": rfc3339(clock(), millis=False),
            "finished_at": None,
            "finished": False,
            "profile": os.environ.get("VIGIL_LAB_PROFILE"),
            "machine": platform.machine(),
            "arguments": {
                "storms": args.storms,
                "extra": args.extra,
                "out": args.out,
                "save_recordings": args.save_recordings,
            },
            "versions": {},
            "checks": {},
            "runs": [],
            "passed": False,
        }

    def write(self):
        write_result(Path(self.args.out), self.result)

    def finish(self, passed):
        self.result["finished"] = True
        self.result["finished_at"] = rfc3339(self.clock(), millis=False)
        self.result["passed"] = passed
        self.write()
        log(f"result: passed {passed}")
        return 0 if passed else 1

    def until(self, condition, within, every):
        end = self.clock() + within
        while True:
            value = condition()
            if value or self.clock() >= end:
                return value
            self.sleep(every)

    def ready(self):
        def check_all():
            try:
                if http("GET", f"{RECORDER}/_recorder/health")[0] != 200:
                    return False
                items = self.lab.zabbix("item.get", {"output": ["key_", "lastclock"], "templated": False})
                if len(items) != 32 or not all(int(item["lastclock"]) > 0 for item in items):
                    return False
                if not self.targets_up():
                    return False
                return self.am_status(True)[0] == 200
            except (HttpError, ValueError, KeyError, TypeError):
                return False

        return self.until(check_all, 300, 2)

    def targets_up(self):
        status, answer = http("GET", f"{PROMETHEUS}/api/v1/targets")
        if status != 200:
            return False
        targets = answer["data"]["activeTargets"]
        for job in PROMETHEUS_JOBS:
            of_job = [t for t in targets if t["labels"].get("job") == job]
            if not of_job or not all(t["health"] == "up" for t in of_job):
                return False
        return True

    def am_status(self, authenticated):
        headers = {"Authorization": self.lab.am_auth} if authenticated else {}
        return http("GET", f"{ALERTMANAGER}/api/v2/status", headers=headers)

    def connection_checks(self):
        checks = self.result["checks"]
        try:
            result = self.lab.zabbix("host.get", {"output": ["hostid"], "limit": 1})
            checks["zabbix_connection_test"] = check(isinstance(result, list), "HTTP 200 with a result array")
        except HttpError as error:
            checks["zabbix_connection_test"] = check(False, str(error))
        for name, authenticated, expected in (
            ("alertmanager_connection_test", True, 200),
            ("alertmanager_requires_auth", False, 401),
        ):
            try:
                status = self.am_status(authenticated)[0]
                checks[name] = check(status == expected, f"HTTP {status}")
            except HttpError as error:
                checks[name] = check(False, str(error))
        try:
            up = self.targets_up()
            checks["prometheus_targets_up"] = check(up, "4 jobs up" if up else "a job is not up")
        except (HttpError, KeyError) as error:
            checks["prometheus_targets_up"] = check(False, str(error))
        versions = self.result["versions"]
        try:
            versions["zabbix"] = self.lab.zabbix("apiinfo.version", {}, auth=False)
            versions["prometheus"] = http("GET", f"{PROMETHEUS}/api/v1/status/buildinfo")[1]["data"]["version"]
            versions["alertmanager"] = self.am_status(True)[1]["versionInfo"]["version"]
        except (HttpError, KeyError, TypeError) as error:
            log(f"versions: {error}")
        self.write()

    def start_clear(self):
        status, answer = self.lab.faults("/faults/clear", {"faults": ALL_FAULTS})
        self.result["checks"]["start_clear"] = check(status == 200, f"HTTP {status} {json.dumps(answer)}")
        self.write()
        return status == 200

    def baseline_window(self):
        started = self.clock()

        def zero_files():
            try:
                return self.lab.diagnostic("disk_growth")[0]["files"] == 0
            except SshError as error:
                log(f"baseline window: {error}")
                return False

        reached = self.until(zero_files, 960, 10)
        waited = round(self.clock() - started)
        self.result["checks"]["baseline_window"] = check(reached, f"{waited}")
        log(f"baseline window: {'reached' if reached else 'not reached'} after {waited} s")
        self.write()
        return reached

    def run_once(self, kind, index, baseline_window):
        faults = KINDS[kind]["faults"]
        run = {
            "kind": kind,
            "faults": faults,
            "t0": None,
            "action_end": None,
            "clear_at": None,
            "completed": 0,
            "stopped_at": None,
            "baseline_window": baseline_window,
            "steps_error": None,
            "quiet_before": None,
            "quiet_after": None,
        }
        observed = {"measured": dict.fromkeys(MEASURED_KEYS)}
        measured = observed["measured"]
        lab = self.lab
        first_seq = None
        log(f"run {kind} {index}: start")
        try:
            # 1. Quiet.
            run["quiet_before"] = self.settle()
            if not run["quiet_before"]:
                raise StepFailed(1, None)
            run["completed"] = 1
            # 2. Baseline.
            measured["baseline_used_pct"] = self.ssh_step(2, lab.used_pct)
            run["completed"] = 2
            # 3. Inject.
            self.read_until(self.clock())
            first_seq = lab.last_seq
            run["t0"] = self.clock()
            for fault in faults:
                status, answer = lab.faults(f"/faults/{fault}/inject")
                log(f"inject {fault}: HTTP {status}")
                if status != 200:
                    raise StepFailed(3, f"inject {fault}: HTTP {status} {json.dumps(answer)}")
            run["completed"] = 3
            # 4. Arrival.
            self.read_until(run["t0"] + 150)
            run["completed"] = 4
            # 5. Measure.
            measured["used_pct_after_injection"] = self.ssh_step(5, lab.used_pct)
            usage, measured["disk_usage"] = self.ssh_step(5, lambda: lab.diagnostic("disk_usage"))
            measured["avail_bytes_after_injection"] = usage["avail_bytes"]
            _, measured["largest_folders"] = self.ssh_step(5, lambda: lab.diagnostic("largest_folders"))
            observed["disk_growth_values"], measured["disk_growth"] = self.ssh_step(
                5, lambda: lab.diagnostic("disk_growth")
            )
            files, size = self.ssh_step(5, lab.injected)
            measured["injected_files"], measured["injected_bytes"] = files, size
            measured["injected_gib"] = float(round_gib(size))
            run["completed"] = 5
            log(f"measured: {measured['disk_usage']}; {files} files, {size} bytes injected")
            # 6. Action.
            if KINDS[kind]["action"]:
                removed = self.ssh_step(6, lab.purge)
                run["action_end"] = self.clock()
                observed["removed"] = removed
                measured["action_files_removed"] = removed["files_removed"]
                measured["action_gib_removed"] = float(removed["gib_removed"])
                measured["used_pct_after_action"] = self.ssh_step(6, lab.used_pct)
                log(f"action: {removed['files_removed']} files removed")
            run["completed"] = 6
            # 7. After the action.
            if kind == "standard":
                self.read_until(run["action_end"] + self.numbers["resolve_after_action_s"])
                while True:
                    resolved = [
                        e["time"]
                        for call in lab.recorded
                        if call["seq"] > first_seq
                        for e in entries_of(call, self.catalogue)
                        if e["identity"] in self.catalogue.storm and e["status"] == "resolved"
                    ]
                    end = max(resolved, default=run["action_end"] + 60) + self.numbers["no_refire_s"]
                    if self.clock() >= end:
                        break
                    self.read_until(min(end, self.clock() + 10))
            elif kind == "read-only":
                self.read_until(run["action_end"] + self.numbers["resolve_after_action_s"])
                try:
                    observed["zabbix_problems"] = lab.zabbix_problems()
                except HttpError as error:
                    log(f"Zabbix problems: {error}")
                    observed["zabbix_problems"] = None
                try:
                    observed["alertmanager_alerts"] = lab.alertmanager_alerts(self.catalogue)
                except HttpError as error:
                    log(f"Alertmanager alerts: {error}")
                    observed["alertmanager_alerts"] = None
            run["completed"] = 7
            # 8. Clear.
            run["clear_at"] = self.clock()
            status, answer = lab.faults("/faults/clear", {"faults": faults})
            log(f"clear: HTTP {status}")
            if status != 200:
                raise StepFailed(8, f"clear: HTTP {status} {json.dumps(answer)}")
            self.read_until(run["clear_at"] + self.numbers["resolve_after_clear_s"])
            run["completed"] = 8
            # 9. Settle.
            run["quiet_after"] = self.settle()
            self.read_until(self.clock())
            run["completed"] = 9
        except StepFailed as failure:
            run["stopped_at"] = failure.step
            if failure.detail is not None:
                run["steps_error"] = failure.detail
            status, answer = lab.faults("/faults/clear", {"faults": faults})
            log(f"run stopped at step {failure.step}: {failure.detail or 'lab not quiet'}; clear: HTTP {status}")
            try:
                lab.poll()
            except HttpError:
                pass
        calls = [call for call in lab.recorded if first_seq is not None and call["seq"] > first_seq]
        checks, timing, counts = evaluate_run(run, calls, self.catalogue, self.numbers, observed)
        passed = all(c["passed"] for c in checks.values())
        failed = [name for name, c in checks.items() if not c["passed"]]
        log(f"run {kind} {index}: passed {passed}" + (f" (failed: {', '.join(failed)})" if failed else ""))
        result = {
            "kind": kind,
            "index": index,
            "faults": faults,
            "t0": rfc3339(run["t0"]),
            "action_end": rfc3339(run["action_end"]),
            "clear_at": rfc3339(run["clear_at"]),
            "measured": measured,
            "timing": timing,
            "counts": counts,
            "checks": checks,
            "passed": passed,
        }
        return result, calls, run["stopped_at"] is None

    def ssh_step(self, step, function):
        try:
            return function()
        except SshError as error:
            raise StepFailed(step, f"SSH: {error}") from None

    def read_until(self, moment):
        while True:
            try:
                self.lab.poll()
            except HttpError as error:
                log(f"recorder: {error}")
            if self.clock() >= moment:
                return
            self.sleep(min(1, max(0, moment - self.clock())))

    def settle(self):
        quiet = self.until(lambda: self.lab.quiet(self.catalogue), QUIET_WITHIN_S, 2)
        if quiet:
            self.sleep(self.numbers["clear_settle_s"])
        return bool(quiet)

    def save(self, calls):
        for number, call in enumerate(calls, start=1):
            write_owned(SAVE / f"{number:04d}-{call['source_key']}.json", json.dumps(call, indent=2) + "\n")
        log(f"saved {len(calls)} recordings")

    def main(self):
        log(f"SB-T-01: {self.args.storms} standard storms, extra {','.join(self.args.extra) or 'none'}")
        if not self.ready():
            log("the lab is not ready within 300 s")
            return self.finish(False)
        self.connection_checks()
        if not self.start_clear():
            return self.finish(False)
        baseline = self.args.storms >= 1
        if baseline and not self.baseline_window():
            return self.finish(False)
        plan = [("standard", n) for n in range(1, self.args.storms + 1)] + [(kind, 1) for kind in self.args.extra]
        for kind, index in plan:
            result, calls, completed = self.run_once(kind, index, baseline)
            self.result["runs"].append(result)
            self.write()
            if kind == "standard" and index == 1 and self.args.save_recordings and completed:
                self.save(calls)
            if not completed:
                return self.finish(False)
        passed = (
            all(c["passed"] for c in self.result["checks"].values())
            and all(run["passed"] for run in self.result["runs"])
            and len(self.result["runs"]) == len(plan)
        )
        return self.finish(passed)


def owner():
    return int(os.environ.get("VIGIL_LOCAL_UID", "10001")), int(os.environ.get("VIGIL_LOCAL_GID", "10001"))


def write_owned(path, text):
    """Writes a file through a temporary file and a rename, mode 0644, owned by VIGIL_LOCAL_UID:VIGIL_LOCAL_GID."""
    path = Path(path)
    with tempfile.NamedTemporaryFile("w", dir=path.parent, delete=False, prefix=".tmp-") as file:
        file.write(text)
    os.chmod(file.name, 0o644)
    if os.geteuid() == 0:
        os.chown(file.name, *owner())
    os.replace(file.name, path)


def write_result(path, result):
    write_owned(path, json.dumps(result, indent=2) + "\n")


def parse_args(argv):
    parser = argparse.ArgumentParser(prog="storm_timing.py")
    parser.add_argument("--storms", type=int, default=10)
    parser.add_argument("--extra", default="read-only,clear")
    parser.add_argument("--out", required=True)
    parser.add_argument("--save-recordings", action="store_true")
    args = parser.parse_args(argv)
    extra = [] if args.extra == "none" else args.extra.split(",")
    if args.storms < 0 or extra not in ([], ["read-only"], ["clear"], ["read-only", "clear"]):
        parser.error("--storms must be at least 0 and --extra a subset of read-only,clear in that order, or none")
    args.extra = extra
    return args


def main(argv):
    args = parse_args(argv)
    if args.save_recordings and (args.storms == 0 or any(SAVE.glob("*.json"))):
        print("--save-recordings needs --storms of at least 1 and a save folder without *.json files", flush=True)
        return 2
    catalogue, operations, numbers = load_data()
    with tempfile.TemporaryDirectory() as folder:
        lab = Lab(operations, folder)
        return Spike(args, lab, catalogue, numbers).main()


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
