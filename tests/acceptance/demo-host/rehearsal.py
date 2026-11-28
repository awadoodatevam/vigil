"""SB-T-08's verdict on one rehearsal (P-34 spec Section 6.7 items 1 and 2).

Reads tests/acceptance/results/P-34-rehearsal-{n}.txt, written by the owner from the rehearsal's recording, one item
per line in this order: "date {YYYY-MM-DD}"; "SB-S00 {m:ss}" to "SB-S10 {m:ss}" (the time in the recording at which
each scene starts); "end {m:ss}" (the end of SB-S10); "DC-ASM-01 yes|no"; "DC-ASM-03 yes|no"; "DC-ASM-04 yes|no";
"SB-ASM-01 yes|no". Lines starting with "#" are comments. Writes {file without .txt}.json with each scene's duration
against its budget (30-jazz-storyboard Section 7.1), the length and the assumptions, prints PASS or FAIL per
criterion and exits 0 when every criterion passed, 1 when one failed; a file it cannot read ends with
"Line {n}: {reason}" and exit 2. Runs with the Mac's python3, standard library only:
    python3 tests/acceptance/demo-host/rehearsal.py tests/acceptance/results/P-34-rehearsal-{n}.txt
"""

import json
import re
import sys
from datetime import date
from pathlib import Path

SCENES = [f"SB-S{n:02d}" for n in range(11)]
# 30-jazz-storyboard Section 7.1: SB-S00 to SB-S10.
BUDGETS = {
    "SB-S00": 60,
    "SB-S01": 120,
    "SB-S02": 90,
    "SB-S03": 45,
    "SB-S04": 60,
    "SB-S05": 105,
    "SB-S06": 105,
    "SB-S07": 135,
    "SB-S08": 30,
    "SB-S09": 60,
    "SB-S10": 30,
}
ASSUMPTIONS = ["DC-ASM-01", "DC-ASM-03", "DC-ASM-04", "SB-ASM-01"]
ITEMS = ["date", *SCENES, "end", *ASSUMPTIONS]
TIME = re.compile(r"^(\d+):([0-5]\d)$")
TOLERANCE_S = 30
LENGTH_S = 900
CRITERIA = ("scenes_within_30", "length_900", "assumptions_yes")


class Unreadable(Exception):
    """A line the file cannot have: exit 2."""

    def __init__(self, line, reason):
        super().__init__(f"Line {line}: {reason}")


def seconds(line, value):
    match = TIME.match(value)
    if not match:
        raise Unreadable(line, f"unreadable time {value!r} (m:ss)")
    return int(match.group(1)) * 60 + int(match.group(2))


def read_items(path):
    """{item: value} in ITEMS' order; times in seconds."""
    values, previous = {}, None
    lines = path.read_text(encoding="utf-8").splitlines()
    for number, line in enumerate(lines, 1):
        if not line.strip() or line.startswith("#"):
            continue
        key, _, value = line.strip().partition(" ")
        value = value.strip()
        if key in values:
            raise Unreadable(number, f"{key} repeated")
        expected = ITEMS[len(values)] if len(values) < len(ITEMS) else None
        if key != expected:
            raise Unreadable(number, f"expected {expected}, found {key}" if expected else f"unexpected {key}")
        if key == "date":
            try:
                if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", value):
                    raise ValueError(value)
                date.fromisoformat(value)
            except ValueError:
                raise Unreadable(number, f"unreadable date {value!r} (YYYY-MM-DD)") from None
            values[key] = value
        elif key in ASSUMPTIONS:
            if value not in ("yes", "no"):
                raise Unreadable(number, f"{key} must be yes or no")
            values[key] = value
        else:
            at = seconds(number, value)
            if previous is not None and at < values[previous]:
                raise Unreadable(number, f"{key} {value} is earlier than {previous}")
            values[key], previous = at, key
    if len(values) < len(ITEMS):
        raise Unreadable(len(lines) + 1, f"{ITEMS[len(values)]} missing")
    return values


def verdict(values):
    scenes = []
    for index, scene in enumerate(SCENES):
        following = SCENES[index + 1] if index + 1 < len(SCENES) else "end"
        duration = values[following] - values[scene]
        scenes.append(
            {
                "id": scene,
                "start_s": values[scene],
                "duration_s": duration,
                "budget_s": BUDGETS[scene],
                "within": abs(duration - BUDGETS[scene]) <= TOLERANCE_S,
            }
        )
    length = values["end"] - values["SB-S00"]
    assumptions = {name: values[name] for name in ASSUMPTIONS}
    criteria = {
        "scenes_within_30": all(scene["within"] for scene in scenes),
        "length_900": length <= LENGTH_S,
        "assumptions_yes": all(answer == "yes" for answer in assumptions.values()),
    }
    return {
        "date": values["date"],
        "scenes": scenes,
        "length_s": length,
        "assumptions": assumptions,
        "criteria": criteria,
        "passed": all(criteria.values()),
    }


def main(arguments):
    if len(arguments) != 1 or not arguments[0].endswith(".txt") or not Path(arguments[0]).is_file():
        print("Usage: python3 tests/acceptance/demo-host/rehearsal.py tests/acceptance/results/P-34-rehearsal-{n}.txt")
        return 2
    path = Path(arguments[0])
    try:
        report = verdict(read_items(path))
    except Unreadable as unreadable:
        print(unreadable)
        return 2
    target = path.with_suffix(".json")
    target.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    for scene in report["scenes"]:
        print(f"{scene['id']} {scene['duration_s']} s, budget {scene['budget_s']} s")
    print(f"length {report['length_s']} s")
    for name in CRITERIA:
        print(f"{'PASS' if report['criteria'][name] else 'FAIL'} {name}")
    print(f"result {target}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
