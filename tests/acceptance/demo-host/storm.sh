#!/usr/bin/env bash
# Runs the storm driver against the demo installation from the Mac (P-32 spec Section 6.9 item 3; SB-T-02 with BP-A-32):
# vigil/storm-driver:local with --via control --deliveries checklist, through https://demo.{demo_domain} and
# https://control.{demo_domain}, the secrets of tests/acceptance/demo-host/secrets.sh, docs/31-demo-data and the
# results under tests/acceptance/results/. --with-playwright starts the suite storm first (e2e.sh {installation} storm)
# and waits for its marker storm-ready; --live starts the suite live at the same time (marker live-ready) and copies
# its AR-SP-01.json to spikes/results/P-32-AR-SP-01.json. --measured FILE passes a storm timing result file to the
# driver. Exit 0 when the driver and every started suite passed.
#   tests/acceptance/demo-host/storm.sh {installation} [--mode immediate|script] [--acts driver|pages]
#     [--with-playwright] [--live] [--measured FILE]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

CALLER_DIR=$PWD
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

IMAGE=vigil/storm-driver:local

usage() {
  echo "Usage: tests/acceptance/demo-host/storm.sh {installation} [--mode immediate|script] [--acts driver|pages]" \
    "[--with-playwright] [--live] [--measured FILE]"
  exit 2
}

if [ "$#" -lt 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1
shift
mode=immediate
acts=driver
with_playwright=no
live=no
measured=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode)
      if [ "$#" -lt 2 ]; then usage; fi
      case "$2" in immediate | script) mode=$2 ;; *) usage ;; esac
      shift
      ;;
    --acts)
      if [ "$#" -lt 2 ]; then usage; fi
      case "$2" in driver | pages) acts=$2 ;; *) usage ;; esac
      shift
      ;;
    --with-playwright) with_playwright=yes ;;
    --live) live=yes ;;
    --measured)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
      case "$2" in
        /*) measured=$2 ;;
        *) measured="$CALLER_DIR/$2" ;;
      esac
      shift
      ;;
    *) usage ;;
  esac
  shift
done

values="deploy/values/$I.yaml"
D=$(values_value "$values" demo_domain)
JIRA=$(values_value "$values" endpoint.jira)
PROFILE=$(values_value "$values" lab_profile)
BASE="$HOME/.vigil-demo/$I"
SECRETS="$BASE/secrets"
if [ ! -f "$SECRETS/control-password" ]; then
  echo "Run tests/acceptance/demo-host/secrets.sh $I first."
  exit 1
fi
if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  echo "Run deploy/images.sh --local first (it builds $IMAGE)."
  exit 1
fi
if [ -n "$measured" ] && [ ! -f "$measured" ]; then
  echo "No file $measured."
  exit 1
fi
RESULTS="$PWD/tests/acceptance/results"
mkdir -p "$RESULTS" "$BASE/e2e" "$BASE/e2e-live"

suites=""
# Stops the started suites: their processes and containers.
stop_suites() {
  local pid
  for pid in $suites; do
    kill "$pid" 2> /dev/null || true
    wait "$pid" 2> /dev/null || true
  done
  docker rm -f vigil-demo-e2e-storm vigil-demo-e2e-live > /dev/null 2>&1 || true
}

# Waits for a suite's marker (180 s, VIGIL_STORM_READY_WAIT); arguments: marker, process id, suite.
wait_ready() {
  local marker=$1 pid=$2 name=$3 waited=0 limit="${VIGIL_STORM_READY_WAIT:-180}"
  while [ ! -f "$marker" ]; do
    if ! kill -0 "$pid" 2> /dev/null || [ "$waited" -ge "$limit" ]; then
      stop_suites
      echo "The $name suite did not start."
      exit 1
    fi
    sleep 1
    waited=$((waited + 1))
  done
}

storm_pid=""
live_pid=""
if [ "$with_playwright" = yes ]; then
  rm -f "$BASE/e2e/storm-ready"
  printf '%s\n' "$mode" > "$BASE/e2e/storm-mode"
  tests/acceptance/demo-host/e2e.sh "$I" storm &
  storm_pid=$!
  suites="$suites $storm_pid"
fi
if [ "$live" = yes ]; then
  rm -f "$BASE/e2e-live/live-ready" "$BASE/e2e-live/AR-SP-01.json"
  tests/acceptance/demo-host/e2e.sh "$I" live &
  live_pid=$!
  suites="$suites $live_pid"
fi
if [ -n "$storm_pid" ]; then
  wait_ready "$BASE/e2e/storm-ready" "$storm_pid" storm
fi
if [ -n "$live_pid" ]; then
  wait_ready "$BASE/e2e-live/live-ready" "$live_pid" live
fi

set -- --rm --user "$(id -u):$(id -g)" \
  -e "VIGIL_BASE_URL=https://demo.$D" -e "VIGIL_CONTROL_URL=https://control.$D" -e "VIGIL_JIRA_URL=$JIRA" \
  -e "VIGIL_LAB_PROFILE=$PROFILE" -v "$SECRETS:/run/secrets/e2e:ro" \
  -v "$SECRETS/control-password:/run/secrets/demo-control/control-password:ro" \
  -v "$SECRETS/jira:/run/secrets/vigil-app/jira:ro" -v "$PWD/docs/31-demo-data:/demo-data:ro" -v "$RESULTS:/results"
if [ -n "$measured" ]; then
  set -- "$@" -v "$measured:/measured/$(basename "$measured"):ro"
fi
set -- "$@" "$IMAGE" --mode "$mode" --deliveries checklist --acts "$acts" --via control
if [ -n "$measured" ]; then
  set -- "$@" --measured "/measured/$(basename "$measured")"
fi
driver=0
docker run "$@" || driver=$?
if [ "$driver" -ne 0 ]; then
  stop_suites
  echo "The storm driver failed (exit $driver)."
  exit 1
fi

status=0
if [ -n "$storm_pid" ] && ! wait "$storm_pid"; then
  echo "The suite storm failed."
  status=1
fi
if [ -n "$live_pid" ]; then
  if ! wait "$live_pid"; then
    echo "The suite live failed."
    status=1
  fi
  if [ -f "$BASE/e2e-live/AR-SP-01.json" ]; then
    mkdir -p spikes/results
    cp "$BASE/e2e-live/AR-SP-01.json" spikes/results/P-32-AR-SP-01.json
    echo "AR-SP-01 result spikes/results/P-32-AR-SP-01.json"
  else
    echo "The suite live wrote no AR-SP-01.json."
    status=1
  fi
fi
exit "$status"
