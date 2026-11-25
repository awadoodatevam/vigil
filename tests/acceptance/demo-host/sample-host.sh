#!/usr/bin/env bash
# The main host's part of tests/acceptance/demo-host/sample.sh (P-32 spec Section 6.9 item 6; AR-SP-03), run by
# run_on_host.
#   sample-host.sh start   starts the sampler detached (PID in {VIGIL_ROOT}/logs/sample.pid): every 10 s it appends
#                          to {VIGIL_ROOT}/logs/sample.csv the UTC time, the CPU busy percentage over the last 10 s
#                          (/proc/stat) and the memory used percentage (1 - MemAvailable / MemTotal, /proc/meminfo)
#   sample-host.sh stop    ends it
set -euo pipefail

VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
LOGS="$VIGIL_ROOT/logs"
PID_FILE="$LOGS/sample.pid"

running() {
  [ -f "$PID_FILE" ] && kill -0 "$(cat "$PID_FILE")" 2> /dev/null
}

case "${1:-}" in
  start)
    mkdir -p "$LOGS"
    if running; then
      echo "The sampler runs already (PID $(cat "$PID_FILE"))."
      exit 1
    fi
    printf 'time,cpu_pct,memory_pct\n' > "$LOGS/sample.csv"
    setsid nohup python3 -c '
import sys
import time
from datetime import datetime, timezone


def cpu():
    with open("/proc/stat") as stat:
        values = [int(v) for v in stat.readline().split()[1:9]]
    return sum(values), values[3] + values[4]


def memory():
    found = {}
    with open("/proc/meminfo") as info:
        for line in info:
            key, _, value = line.partition(":")
            found[key] = int(value.split()[0])
    return 100 * (1 - found["MemAvailable"] / found["MemTotal"])


total, idle = cpu()
while True:
    time.sleep(10)
    now_total, now_idle = cpu()
    spent = now_total - total
    busy = 100 * (1 - (now_idle - idle) / spent) if spent > 0 else 0.0
    total, idle = now_total, now_idle
    stamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    with open(sys.argv[1], "a") as out:
        out.write(f"{stamp},{busy:.1f},{memory():.1f}\n")
' "$LOGS/sample.csv" > /dev/null 2>&1 < /dev/null &
    echo "$!" > "$PID_FILE"
    echo "sampler started (PID $!), writing /srv/vigil/logs/sample.csv"
    ;;
  stop)
    if running; then
      kill "$(cat "$PID_FILE")"
      echo "sampler stopped"
    else
      echo "no sampler runs"
    fi
    rm -f "$PID_FILE"
    ;;
  *)
    echo "Usage: sample-host.sh start|stop"
    exit 2
    ;;
esac
