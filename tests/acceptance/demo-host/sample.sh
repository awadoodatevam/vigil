#!/usr/bin/env bash
# AR-SP-03 on the demo installation (P-32 spec Section 6.9 item 6, CTX-D-095 (7)): the capacity of the main host during
# the storm runs. start runs the 10 s sampler on the main host (sample-host.sh); stop ends it, fetches its CSV
# (deploy/fetch.sh) and, from the driver's result files of the runs, writes spikes/results/P-32-AR-SP-03.json: per storm
# (from T0 to the run's resolve) the largest CPU and memory value and SB-TL-01 to SB-TL-04 of the result file's times,
# the criteria cpu_80, memory_85, sb_tl_02, sb_tl_03, sb_tl_04, storms_3, and passed. Run on the Mac:
#   tests/acceptance/demo-host/sample.sh {installation} start
#   tests/acceptance/demo-host/sample.sh {installation} stop {driver result file} ...
set -euo pipefail

CALLER_DIR=$PWD
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: tests/acceptance/demo-host/sample.sh {installation} start | stop {driver result file} ..."
  exit 2
}

if [ "$#" -lt 2 ] || ! known_installation "$1"; then
  usage
fi
I=$1
action=$2
shift 2
case "$action" in
  start)
    if [ "$#" -ne 0 ]; then usage; fi
    ;;
  stop)
    if [ "$#" -eq 0 ]; then usage; fi
    ;;
  *) usage ;;
esac
files=()
while [ "$#" -gt 0 ]; do
  file=$1
  shift
  case "$file" in
    /*) ;;
    *) file="$CALLER_DIR/$file" ;;
  esac
  if [ ! -f "$file" ]; then
    echo "No file $file."
    exit 1
  fi
  files+=("$file")
done

check_identity
require_running_host "vigil-$I-main" main
run_on_host "$HOST_ID" tests/acceptance/demo-host/sample-host.sh -- "$action"
if [ "$action" = start ]; then
  exit 0
fi

WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT
deploy/fetch.sh "$I" /srv/vigil/logs/sample.csv "$WORK/sample.csv"
mkdir -p spikes/results
python3 - "$WORK/sample.csv" spikes/results/P-32-AR-SP-03.json "${files[@]}" <<'PYTHON'
import csv
import json
import os
import sys
from datetime import datetime, timezone

samples_file, out, result_files = sys.argv[1], sys.argv[2], sys.argv[3:]


def epoch(text):
    return datetime.fromisoformat(text.replace("Z", "+00:00")).timestamp()


with open(samples_file, newline="") as file:
    samples = [(epoch(row["time"]), float(row["cpu_pct"]), float(row["memory_pct"])) for row in csv.DictReader(file)]


def within(value, limit):
    return value is not None and value <= limit


storms = []
for path in result_files:
    with open(path) as file:
        result = json.load(file)
    times = result.get("times") or {}
    start = epoch(result["t0"]) if result.get("t0") else None
    end = None
    if start is not None and times.get("SB-TL-10") is not None:
        end = start + times["SB-TL-10"]
    elif result.get("finished_at"):
        end = epoch(result["finished_at"])
    window = [s for s in samples if start is not None and end is not None and start <= s[0] <= end]
    tl = {key: times.get(key) for key in ("SB-TL-01", "SB-TL-02", "SB-TL-03", "SB-TL-04")}
    storms.append(
        {
            "result_file": os.path.basename(path),
            "t0": result.get("t0"),
            "end_after_t0_s": None if start is None or end is None else round(end - start, 3),
            "samples": len(window),
            "cpu_max_pct": max((s[1] for s in window), default=None),
            "memory_max_pct": max((s[2] for s in window), default=None),
            **tl,
        }
    )


def gap(storm, later, earlier):
    if storm[later] is None or storm[earlier] is None:
        return None
    return storm[later] - storm[earlier]


criteria = {
    "cpu_80": bool(storms) and all(within(s["cpu_max_pct"], 80) for s in storms),
    "memory_85": bool(storms) and all(within(s["memory_max_pct"], 85) for s in storms),
    # SB-TL-02: at most 45 s after the last storm alert, never before T+40 s.
    "sb_tl_02": bool(storms)
    and all(within(gap(s, "SB-TL-02", "SB-TL-01"), 45) and (s["SB-TL-02"] or 0) >= 40 for s in storms),
    # SB-TL-03: at most 15 s after SB-TL-02; SB-TL-04: at most 60 s after SB-TL-03.
    "sb_tl_03": bool(storms) and all(within(gap(s, "SB-TL-03", "SB-TL-02"), 15) for s in storms),
    "sb_tl_04": bool(storms) and all(within(gap(s, "SB-TL-04", "SB-TL-03"), 60) for s in storms),
    "storms_3": len([s for s in storms if s["samples"] > 0]) >= 3,
}
report = {
    "spike": "AR-SP-03",
    "sampled": {
        "every_s": 10,
        "count": len(samples),
        "from": samples[0][0] if samples else None,
        "to": samples[-1][0] if samples else None,
    },
    "storms": storms,
    "criteria": criteria,
    "passed": all(criteria.values()),
}
for key in ("from", "to"):
    if report["sampled"][key] is not None:
        stamp = datetime.fromtimestamp(report["sampled"][key], timezone.utc)
        report["sampled"][key] = stamp.strftime("%Y-%m-%dT%H:%M:%SZ")
with open(out + ".tmp", "w") as file:
    json.dump(report, file, indent=2)
    file.write("\n")
os.replace(out + ".tmp", out)
for name, value in criteria.items():
    print(f"{'PASS' if value else 'FAIL'} {name}")
print(f"result {out}: {'passed' if report['passed'] else 'failed'}")
sys.exit(0 if report["passed"] else 1)
PYTHON
