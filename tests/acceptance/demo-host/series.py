"""SB-T-02's verdict over a series of storm driver runs on the demo installation (P-34 spec Section 6.5 item 2).

Reads the manifest written by series.sh (lines "run {k} exit {code} result {file} checklist {file}"; lines starting
with "#" are comments) and the result files and checklists it names (in the manifest's folder), and writes
P-34-SB-T-02.json beside the manifest: the runs, the criteria (SB-T-02, SL-A3-04, SB-T-03, SB-T-04, AR-G-12) and the
values recorded but not judged. Prints PASS or FAIL per criterion; exits 0 when every criterion passed, 1 when one
failed, 2 for wrong arguments. Runs with the Mac's python3, standard library only:
    python3 tests/acceptance/demo-host/series.py tests/acceptance/results/P-34-series-{time}.txt
"""

import json
import re
import sys
from pathlib import Path

RUNS = 5
RUN_LINE = re.compile(r"^run (\d+) exit (\d+) result (\S+) checklist (\S+)$")
SETTINGS = ("mode", "deliveries", "acts", "profile", "finished")
EXPECTED_SETTINGS = {"mode": "script", "deliveries": "checklist", "acts": "driver", "profile": "demo", "finished": True}
# SB-T-04 (SB-TL-03, SB-TL-04, SB-TL-08): diagnostics, SL-T-1 with SL-T-2, ticket and messages, and the action.
LIMITS = {"diagnostics_s": 15, "ai_and_messages_s": 60, "action_s": 15}
CRITERIA = (
    "runs_5",
    "settings_5",
    "drivers_5",
    "checklists_5",
    "repeatability_5",
    "similar_5",
    "step_times_5",
    "rb_db_01_4_of_5",
)


class Wrong(Exception):
    """A manifest that cannot be read: exit 2."""


def manifest_runs(path):
    """[(run, exit, result, checklist)] of the manifest."""
    runs = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        match = RUN_LINE.match(line)
        if not match:
            raise Wrong(f"Line {number}: not a run line: {line}")
        runs.append((int(match.group(1)), int(match.group(2)), match.group(3), match.group(4)))
    return runs


def read_json(path):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def ticked(path):
    """True when every line that starts with "- [" starts with "- [x] ", and there is at least one."""
    try:
        lines = [line for line in path.read_text(encoding="utf-8").splitlines() if line.startswith("- [")]
    except OSError:
        return False
    return bool(lines) and all(line.startswith("- [x] ") for line in lines)


def check_passed(result, check_id, name):
    for check in result.get("checks") or []:
        if check.get("id") == check_id and check.get("name") == name:
            return check.get("passed") is True
    return False


def difference(later, earlier):
    if later is None or earlier is None:
        return None
    return round(later - earlier, 1)


def run_entry(folder, run, code, result_name, checklist_name):
    result = (read_json(folder / result_name) if result_name != "none" else None) or {}
    times = result.get("times") or {}
    investigation = result.get("investigation") or {}
    return {
        "run": run,
        "exit": code,
        "result": result_name,
        "checklist": checklist_name,
        "driver_passed": result.get("passed") is True,
        "suite_storm_passed": code == 0,
        "settings": {key: result.get(key) for key in SETTINGS},
        "checklist_ticked": checklist_name != "none" and ticked(folder / checklist_name),
        "repeatability": check_passed(result, "SB-R-06", "repeatability result"),
        "similar": check_passed(result, "SB-S06", "similar incident"),
        "times": times,
        "step_times": {
            "diagnostics_s": difference(times.get("SB-TL-03"), times.get("SB-TL-02")),
            "ai_and_messages_s": difference(times.get("SB-TL-04"), times.get("SB-TL-03")),
            "action_s": difference(times.get("SB-TL-08"), times.get("approved")),
        },
        "runbooks_cited": investigation.get("runbooks_cited"),
        "summary_length": investigation.get("summary_length"),
    }


def within_limits(step_times):
    return all(step_times[key] is not None and step_times[key] <= limit for key, limit in LIMITS.items())


def verdict(manifest):
    folder = manifest.parent
    runs = [run_entry(folder, *run) for run in manifest_runs(manifest)]
    criteria = {
        "runs_5": len(runs) == RUNS,
        "settings_5": all(r["settings"] == EXPECTED_SETTINGS for r in runs),
        "drivers_5": all(r["driver_passed"] for r in runs),
        "checklists_5": all(r["checklist_ticked"] for r in runs),
        "repeatability_5": all(r["repeatability"] for r in runs),
        "similar_5": all(r["similar"] for r in runs),
        "step_times_5": all(within_limits(r["step_times"]) for r in runs),
        "rb_db_01_4_of_5": sum("RB-DB-01" in (r["runbooks_cited"] or []) for r in runs) >= 4,
    }
    return {
        "manifest": manifest.name,
        "runs": runs,
        "criteria": criteria,
        "passed": all(criteria.values()),
        "suite_storm_runs_passed": sum(r["suite_storm_passed"] for r in runs),
        "summary_lengths": [r["summary_length"] for r in runs],
    }


def main(arguments):
    if len(arguments) != 1 or not Path(arguments[0]).is_file():
        print("Usage: python3 tests/acceptance/demo-host/series.py tests/acceptance/results/P-34-series-{time}.txt")
        return 2
    manifest = Path(arguments[0])
    try:
        report = verdict(manifest)
    except Wrong as wrong:
        print(wrong)
        return 2
    target = manifest.parent / "P-34-SB-T-02.json"
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for name in CRITERIA:
        print(f"{'PASS' if report['criteria'][name] else 'FAIL'} {name}")
    print(f"suite storm passed in {report['suite_storm_runs_passed']} of {len(report['runs'])} runs")
    print(f"result {target}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
