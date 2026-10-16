"""Unit tests of the storm timing spike (P-04 spec Section 7): the checks on synthetic call lists, parsers, quoting."""

import argparse
import json
from decimal import Decimal
from pathlib import Path

import pytest
import storm_timing as st
import yaml

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "docs" / "31-demo-data"
CATALOGUE, OPERATIONS, NUMBERS = st.load_data(DATA)
T0 = 1_800_000_000.0


class Calls:
    """Builds recorded calls as the recorder writes them."""

    def __init__(self):
        self.calls = []
        self.event_ids = {}

    def add(self, identity, status, when, **change):
        row = CATALOGUE.row_of.get(identity)
        if identity[0] == "zabbix":
            event_id = self.event_ids.setdefault(identity, str(1000 + len(self.event_ids)))
            recovery = status == "resolved"
            component = row["tag_component"] if row else "unknown"
            body = {
                "event_id": event_id,
                "event_value": "0" if recovery else "1",
                "event_name": identity[2],
                "host": identity[1],
                "severity": "Not classified",
                "tags": [{"tag": "component", "value": component}, {"tag": "vigil", "value": "lab"}],
                "event_date": "2027.01.15",
                "event_time": "08:00:00",
                "recovery_date": "2027.01.15" if recovery else "",
                "recovery_time": "08:05:00" if recovery else "",
            }
            body.update(change.pop("body", {}))
            headers = {"Content-Type": "application/json", "User-Agent": "zabbix-webhook"}
            source = "zabbix-lab"
        else:
            labels = {"alertname": identity[1], **(row["labels"] if row else {"component": "unknown"})}
            if row:
                labels.update(zip(row.get("dimensions") or {}, identity[2], strict=True))
            summary = st.SUMMARIES.get(identity[1], "").format(**labels)
            alert = {
                "status": status,
                "labels": labels,
                "annotations": {"summary": summary},
                "startsAt": "2027-01-15T08:00:00Z",
                "endsAt": "0001-01-01T00:00:00Z",
                "fingerprint": f"{abs(hash(identity)):016x}",
            }
            alert.update(change.pop("alert", {}))
            body = {"version": "4", "status": status, "alerts": [alert]}
            headers = {"Content-Type": "application/json", "User-Agent": "Alertmanager/0.34.1"}
            source = "alertmanager-lab"
        headers.update(change.pop("headers", {}))
        call = {
            "seq": 0,
            "received_at": st.rfc3339(when),
            "source_key": source,
            "method": "POST",
            "path": f"/api/v1/webhooks/{source}",
            "status": 200,
            "authorized": True,
            "headers": {**headers, "Authorization": "Bearer <redacted>"},
            "body": json.dumps(body),
        }
        self.calls.append(call)
        return call

    def ordered(self):
        self.calls.sort(key=lambda call: call["received_at"])
        for number, call in enumerate(self.calls, start=1):
            call["seq"] = number
        return self.calls


def storm_ids():
    return sorted(CATALOGUE.storm, key=str)


LA09 = next(iter(CATALOGUE.sl_f_3))
DISK_FULL = CATALOGUE.by_id("LA-01") | CATALOGUE.by_id("LA-04")


def good_run(kind="standard"):
    """A run of the kind that passes every check: (run, calls, observed)."""
    calls = Calls()
    ids = storm_ids()
    for number, identity in enumerate(ids):
        calls.add(identity, "firing", T0 + 10 + number * 0.6)
    calls.add(LA09, "firing", T0 + 20)
    ta = T0 + 170 if st.KINDS[kind]["action"] else None
    if kind == "standard":
        for number, identity in enumerate(ids):
            calls.add(identity, "resolved", ta + 5 + number * 0.3)
        tc = ta + 5 + len(ids) * 0.3 + 300
        calls.add(LA09, "resolved", tc + 12)
    elif kind == "read-only":
        for identity in DISK_FULL:
            calls.add(identity, "resolved", ta + 20)
        tc = ta + 61
        for number, identity in enumerate(sorted(CATALOGUE.keeps_firing | {LA09}, key=str)):
            calls.add(identity, "resolved", tc + 5 + number * 0.2)
    else:
        tc = T0 + 170
        for number, identity in enumerate(ids + [LA09]):
            calls.add(identity, "resolved", tc + 5 + number * 0.2)
    run = {
        "kind": kind,
        "faults": st.KINDS[kind]["faults"],
        "t0": T0,
        "action_end": ta,
        "clear_at": tc,
        "completed": 9,
        "stopped_at": None,
        "baseline_window": True,
        "steps_error": None,
        "quiet_before": True,
        "quiet_after": True,
    }
    measured = dict.fromkeys(st.MEASURED_KEYS)
    measured.update(
        baseline_used_pct=26,
        used_pct_after_injection=99,
        avail_bytes_after_injection=67104768,
        injected_files=11,
        injected_bytes=3079475200,
        used_pct_after_action=26 if st.KINDS[kind]["action"] else None,
    )
    observed = {
        "measured": measured,
        "disk_growth_values": {"files": 11, "bytes": 3079475200},
        "removed": {"files_removed": 11, "bytes_removed": 3079475200},
    }
    if kind == "read-only":
        observed["zabbix_problems"] = {i for i in CATALOGUE.keeps_firing if i[0] == "zabbix"} | {LA09}
        observed["alertmanager_alerts"] = {i for i in CATALOGUE.keeps_firing if i[0] == "alertmanager"}
    return run, calls, observed


def evaluate(run, calls, observed):
    return st.evaluate_run(run, calls.ordered(), CATALOGUE, NUMBERS, observed)


def failed(checks):
    return {name for name, result in checks.items() if not result["passed"]}


def test_expected_sets():
    """SB-T-01 the storm set has 126 identities (31 + 95), the SL-F-3 set 1 and the keeps-firing set 124."""
    storm = CATALOGUE.storm
    assert len(storm) == 126
    assert sum(1 for i in storm if i[0] == "zabbix") == 31
    assert sum(1 for i in storm if i[0] == "alertmanager") == 95
    assert CATALOGUE.sl_f_3 == {("zabbix", "lab-rpt-01", "Report export job failed")}
    assert len(CATALOGUE.keeps_firing) == 124
    assert ("zabbix", "lab-db-01", "Database write errors on schema sms_delivery") in storm
    assert ("alertmanager", "NodeFilesystemFull", ()) in storm
    assert ("alertmanager", "BalanceApiErrors", ("api-balance-2", "/v1/balance/main")) in storm


@pytest.mark.parametrize("kind", ["standard", "read-only", "clear"])
def test_good_runs(kind):
    """SB-T-01 every check of the run's kind passes on a good call list; counts and timing are filled."""
    checks, timing, counts = evaluate(*good_run(kind))
    assert failed(checks) == set(), {name: checks[name] for name in failed(checks)}
    assert set(checks) == st.applicable_checks(kind, st.KINDS[kind]["faults"], True)
    assert counts["storm_firing"] == 126 and counts["zabbix_firing"] == 31 and counts["alertmanager_firing"] == 95
    assert counts["sl_f_3_firing"] == 1
    assert counts["still_firing_after_action"] == (124 if kind == "read-only" else None)
    assert timing["first_arrival_s"] == 10.0 and timing["sl_f_3_arrival_s"] == 20.0
    assert timing["max_gap_s"] == 0.6 and timing["last_arrival_s"] == 85.0
    assert timing["last_resolve_after_clear_s"] is not None
    assert (timing["last_resolve_after_action_s"] is None) is (kind == "clear")
    if kind == "read-only":
        assert timing["last_resolve_after_action_s"] == 20.0


def shift(calls, identity, status, seconds):
    for call in calls.calls:
        entries = st.entries_of(call, CATALOGUE)
        if entries[0]["identity"] == identity and entries[0]["status"] == status:
            call["received_at"] = st.rfc3339(st.parse_time(call["received_at"]) + seconds)
            return
    raise AssertionError(identity)


def test_failing_lists():
    """SB-T-01 each check fails on a list with its fault."""
    ids = storm_ids()

    run, calls, observed = good_run()
    late = ids[0]
    shift(calls, late, "firing", T0 + 121 - (T0 + 10))
    assert "arrival" in failed(evaluate(run, calls, observed)[0])

    run, calls, observed = good_run()
    for call in calls.calls:
        entry = st.entries_of(call, CATALOGUE)[0]
        if entry["status"] == "firing" and entry["identity"] in CATALOGUE.storm and entry["time"] >= T0 + 40:
            call["received_at"] = st.rfc3339(entry["time"] + 26)
    checks, timing, _ = evaluate(run, calls, observed)
    assert "max_gap" in failed(checks) and timing["max_gap_s"] > 25

    run, calls, observed = good_run()
    calls.add(ids[5], "firing", T0 + 100)
    checks, _, counts = evaluate(run, calls, observed)
    assert {"exactly_once", "counts"} <= failed(checks) and counts["storm_firing"] == 127

    run, calls, observed = good_run()
    calls.add(("alertmanager", "SomethingElse", ()), "firing", T0 + 50)
    assert failed(evaluate(run, calls, observed)[0]) == {"no_unexpected", "messages"}

    run, calls, observed = good_run()
    first_resolved = run["action_end"] + 5
    shift(calls, ids[0], "resolved", run["action_end"] + 61 - first_resolved)
    assert failed(evaluate(run, calls, observed)[0]) == {"resolve_after_action"}

    run, calls, observed = good_run()
    calls.add(ids[3], "firing", run["action_end"] + 5 + 3 * 0.3 + 299)
    assert "no_refire" in failed(evaluate(run, calls, observed)[0])

    run, calls, observed = good_run("clear")
    calls.calls = [
        c
        for c in calls.calls
        if not (
            st.entries_of(c, CATALOGUE)[0]["identity"] == ids[7]
            and st.entries_of(c, CATALOGUE)[0]["status"] == "resolved"
        )
    ]
    assert failed(evaluate(run, calls, observed)[0]) == {"resolve_after_clear"}

    run, calls, observed = good_run()
    shift(calls, LA09, "firing", 11)
    assert failed(evaluate(run, calls, observed)[0]) == {"sl_f_3"}


def zabbix_id():
    return next(i for i in storm_ids() if i[0] == "zabbix")


def am_id():
    return ("alertmanager", "UssdGatewayTimeouts", ("ussd-gw-b", "my-bundles"))


@pytest.mark.parametrize(
    "change",
    [
        {"body": {"extra": "x"}},
        {"headers": {"User-Agent": "Zabbix"}},
        {"body": {"tags": '[{"tag":"vigil","value":"lab"}]'}},
        {"body": {"event_date": "15.01.2027"}},
        {"body": {"event_id": 7}},
    ],
)
def test_zabbix_message_checks(change):
    """SB-T-01 a Zabbix message with an extra key, a wrong User-Agent or tags as a string fails the messages check."""
    run, calls, observed = good_run()
    calls.calls = [c for c in calls.calls if st.entries_of(c, CATALOGUE)[0]["identity"] != zabbix_id()]
    calls.add(zabbix_id(), "firing", T0 + 10, **json.loads(json.dumps(change)))
    calls.add(zabbix_id(), "resolved", run["action_end"] + 5)
    assert failed(evaluate(run, calls, observed)[0]) == {"messages"}


def test_alertmanager_message_checks():
    """SB-T-01 an Alertmanager alert with a wrong summary or component fails the messages check."""
    for change in ({"annotations": {"summary": "USSD gateway timeouts on ussd-gw-b"}}, {"fingerprint": ""}):
        run, calls, observed = good_run()
        for call in calls.calls:
            entry = st.entries_of(call, CATALOGUE)[0]
            if entry["identity"] == am_id() and entry["status"] == "firing":
                body = json.loads(call["body"])
                body["alerts"][0].update(change)
                call["body"] = json.dumps(body)
        assert "messages" in failed(evaluate(run, calls, observed)[0])


def test_measure_checks():
    """SB-T-01 precondition, disk_growth and action fail on wrong measured values; sl_f_2 on a wrong problem list."""
    run, calls, observed = good_run()
    observed["measured"]["avail_bytes_after_injection"] = 200 * st.MIB
    observed["disk_growth_values"] = {"files": 23, "bytes": 1}
    observed["removed"] = {"files_removed": 10, "bytes_removed": 1}
    assert failed(evaluate(run, calls, observed)[0]) == {"precondition", "disk_growth", "action"}

    run, calls, observed = good_run("read-only")
    observed["zabbix_problems"] = set()
    assert failed(evaluate(run, calls, observed)[0]) == {"sl_f_2"}
    run, calls, observed = good_run("read-only")
    calls.add(storm_ids()[40], "resolved", run["action_end"] + 30)
    assert "sl_f_2" in failed(evaluate(run, calls, observed)[0])


def test_stopped_run():
    """SB-T-01 a run stopped at step 2 keeps quiet_before and steps; every later check is not evaluated."""
    run, calls, observed = good_run()
    run.update(t0=None, action_end=None, clear_at=None, completed=1, stopped_at=2, steps_error="SSH: exit 255")
    run["quiet_after"] = None
    checks, timing, counts = evaluate(run, Calls(), {"measured": dict.fromkeys(st.MEASURED_KEYS)})
    assert checks["quiet_before"] == {"passed": True, "detail": ""}
    assert checks["steps"] == {"passed": False, "detail": "SSH: exit 255"}
    assert checks["arrival"] == {"passed": False, "detail": "not evaluated: run stopped at step 2"}
    assert set(checks) == st.applicable_checks("standard", ["SL-F-1", "SL-F-3"], True)
    assert set(timing.values()) == {None} and set(counts.values()) == {None}


def test_parsers_and_quoting():
    """SB-T-01 the DD-F-04 parsers, the summaries and the AR-K-12 quoting give the values of the spec's table."""
    df = st.df_row("Mounted on 1B-blocks Used Avail Use%\n/data 4227858432 4160753664 67104768 99%\n", {})
    assert df == {
        "mount": "/data",
        "size_bytes": 4227858432,
        "used_bytes": 4160753664,
        "avail_bytes": 67104768,
        "used_pct": 99,
        "size_gib": Decimal("3.9"),
        "used_gib": Decimal("3.9"),
        "avail_gib": Decimal("0.1"),
    }
    du = st.du_top(
        "4227858432\t/data\n3079475200\t/data/txlog_archive\n858980352\t/data/db\n107364352\t/data/export\n",
        {"path": "/data"},
    )
    assert du == {
        "top1_path": "/data/txlog_archive",
        "top1_gib": Decimal("2.9"),
        "top2_path": "/data/db",
        "top2_gib": Decimal("0.8"),
        "top3_path": "/data/export",
        "top3_gib": Decimal("0.1"),
    }
    assert st.count_bytes("11 3079475200\n", {}) == {"files": 11, "bytes": 3079475200, "gib": Decimal("2.9")}
    assert st.single_number("99", {}) == {"value": 99}
    for bad in ("", "9x"):
        with pytest.raises(st.ParseError):
            st.single_number(bad, {})
    removed = st.removed_files("268435456 txlog_000001.arc\n268435456 txlog_000002.arc\n", {})
    assert removed == {"files_removed": 2, "bytes_removed": 536870912, "gib_removed": Decimal("0.5")}
    template = {d["diagnostic_key"]: d for d in OPERATIONS["diagnostic_definitions"]}["disk_usage"]["summary_template"]
    assert st.summary(template, df) == "/data is 99% full (3.9 of 3.9 GiB used, 0.1 GiB free)"
    assert st.quote("/data") == "'/data'"
    assert st.quote("it's") == "'it'\\''s'"
    operation = {o["operation_key"]: o for o in OPERATIONS["operations"]}["fs_used_pct"]
    assert "-- '/data' |" in st.render(operation, {"mount": "/data"})


def test_result_file(tmp_path, monkeypatch):
    """SB-T-01 the result file and a run have exactly the keys of P-04 spec Section 6.5."""
    out = tmp_path / "result.json"
    args = argparse.Namespace(storms=0, extra=["read-only"], out=str(out), save_recordings=False)
    clock = iter(range(1_800_000_000, 1_800_100_000))

    class QuietlessLab:
        recorded = []
        last_seq = 0

        def quiet(self, catalogue):
            return False

        def faults(self, path, body=None):
            return 200, {"active": []}

        def poll(self):
            return []

    spike = st.Spike(args, QuietlessLab(), CATALOGUE, NUMBERS, clock=lambda: float(next(clock)), sleep=lambda s: None)
    result, calls, completed = spike.run_once("read-only", 1, False)
    spike.result["runs"].append(result)
    assert spike.finish(False) == 1
    written = json.loads(out.read_text())
    assert set(written) == {
        "spike",
        "started_at",
        "finished_at",
        "finished",
        "profile",
        "machine",
        "arguments",
        "versions",
        "checks",
        "runs",
        "passed",
    }
    run = written["runs"][0]
    assert set(run) == {
        "kind",
        "index",
        "faults",
        "t0",
        "action_end",
        "clear_at",
        "measured",
        "timing",
        "counts",
        "checks",
        "passed",
    }
    assert set(run["measured"]) == set(st.MEASURED_KEYS) and len(st.MEASURED_KEYS) == 12
    assert set(run["timing"]) == set(st.TIMING_KEYS) and set(run["counts"]) == set(st.COUNT_KEYS)
    assert run["checks"]["quiet_before"] == {"passed": False, "detail": "lab not quiet"}
    assert run["checks"]["steps"]["passed"] is True
    assert run["checks"]["sl_f_2"]["detail"] == "not evaluated: run stopped at step 1"
    assert written["spike"] == "SB-T-01" and written["finished"] is True and completed is False


def test_numbers_and_summaries():
    """DD-F-10 (timing_targets) the numbers come from timing_targets.numbers; the summaries are DD-F-10's words."""
    assert (
        NUMBERS["arrival_within_s"],
        NUMBERS["max_gap_s"],
        NUMBERS["resolve_after_action_s"],
        NUMBERS["no_refire_s"],
        NUMBERS["resolve_after_clear_s"],
        NUMBERS["clear_settle_s"],
    ) == (120, 25, 60, 300, 40, 12)
    lab = yaml.safe_load((DATA / "10-lab-and-faults.yaml").read_text())
    prometheus = lab["monitoring_configuration"]["prometheus"]
    for text in st.SUMMARIES.values():
        assert f"'{text}'" in prometheus
