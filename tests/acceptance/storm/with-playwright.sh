#!/usr/bin/env bash
# Runs the Playwright suite storm alongside the storm driver (P-28 spec Section 6.11, CTX-D-085 (9), BP-W-02): writes the
# driver's mode into the run folder (storm-mode, P-29 spec Section 6.11 item 3), starts deploy/local.sh e2e storm, waits
# until its test has signed in (the marker storm-ready in the run folder), then runs tests/acceptance/storm/run.sh with
# --acts pages in mode immediate (the pages acknowledge and approve) and --acts driver in mode script (P-30 spec
# Section 6.10 item 2); exits 0 only when both pass.
#   tests/acceptance/storm/with-playwright.sh [--mode immediate|script] [--project NAME]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

CALLER_DIR=$PWD
if [ -n "${VIGIL_STORM_RESULTS:-}" ]; then
  case "$VIGIL_STORM_RESULTS" in
    /*) ;;
    *) VIGIL_STORM_RESULTS="$CALLER_DIR/$VIGIL_STORM_RESULTS" ;;
  esac
  export VIGIL_STORM_RESULTS
fi
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."

usage() {
  echo "Usage: tests/acceptance/storm/with-playwright.sh [--mode immediate|script] [--project NAME]"
  exit 2
}

mode=immediate
project=vigil-local
while [ "$#" -gt 0 ]; do
  case "$1" in
    --mode)
      if [ "$#" -lt 2 ]; then usage; fi
      case "$2" in immediate | script) mode=$2 ;; *) usage ;; esac
      shift
      ;;
    --project)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
      project=$2
      shift
      ;;
    *) usage ;;
  esac
  shift
done

base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
if [ ! -f "$base/$project/state/values-file" ]; then
  echo "Run deploy/local.sh up first."
  exit 1
fi

marker="$base/$project/e2e/storm-ready"
rm -f "$marker"
mkdir -p "$base/$project/e2e"
printf '%s\n' "$mode" > "$base/$project/e2e/storm-mode"

deploy/local.sh e2e storm --project "$project" &
suite=$!

# Stops the background suite: its process, then every e2e container of the project.
stop_suite() {
  kill "$suite" 2> /dev/null || true
  wait "$suite" 2> /dev/null || true
  local containers
  containers=$(docker ps -aq --filter "label=com.docker.compose.project=$project" \
    --filter "label=com.docker.compose.service=e2e" || true)
  if [ -n "$containers" ]; then
    # shellcheck disable=SC2086 # one argument per container id
    docker rm -f $containers > /dev/null || true
  fi
}

limit="${VIGIL_STORM_READY_WAIT:-180}"
waited=0
while [ ! -f "$marker" ]; do
  if ! kill -0 "$suite" 2> /dev/null || [ "$waited" -ge "$limit" ]; then
    stop_suite
    echo "The storm suite did not start."
    exit 1
  fi
  sleep 1
  waited=$((waited + 1))
done

acts=pages
if [ "$mode" = script ]; then
  acts=driver
fi
if ! tests/acceptance/storm/run.sh --mode "$mode" --acts "$acts" --project "$project"; then
  stop_suite
  exit 1
fi

if wait "$suite"; then
  exit 0
fi
exit 1
