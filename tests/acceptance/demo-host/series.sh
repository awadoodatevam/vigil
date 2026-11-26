#!/usr/bin/env bash
# SB-T-02's runs on the demo installation (P-34 spec Section 6.5 item 1): writes the manifest
# tests/acceptance/results/P-34-series-{UTC start}.txt, then runs tests/acceptance/demo-host/storm.sh {installation}
# --mode script --with-playwright [--measured FILE] N times (default 5), each from the control panel's Reset (the
# driver's step 1). A failed run does not stop the series. After each run it finds the run's new storm-script-*.json
# and storm-checklist-*.md under tests/acceptance/results/ (exactly one each, else none), copies the suite's screens/
# and results/ of ~/.vigil-demo/{installation}/e2e/ to ~/.vigil-demo/{installation}/series/{UTC start}/run-{k}/ and
# appends "run {k} exit {code} result {file} checklist {file}" to the manifest. The suite's screens/ folder is emptied
# before each run, so a run's copy holds only that run's screenshots. Prints the manifest's path; exits 0 when every
# run's exit code was 0, else 1. Then: python3 tests/acceptance/demo-host/series.py {manifest}.
#   tests/acceptance/demo-host/series.sh {installation} [--runs N] [--measured FILE]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

CALLER_DIR=$PWD
cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: tests/acceptance/demo-host/series.sh {installation} [--runs N] [--measured FILE]"
  exit 2
}

if [ "$#" -lt 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1
shift
runs=5
measured=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --runs)
      if [ "$#" -lt 2 ]; then usage; fi
      case "$2" in '' | *[!0-9]* | 0*) usage ;; esac
      runs=$2
      shift
      ;;
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
if [ -n "$measured" ] && [ ! -f "$measured" ]; then
  echo "No file $measured."
  exit 1
fi

RESULTS="$PWD/tests/acceptance/results"
BASE="$HOME/.vigil-demo/$I"
START=$(date -u +%Y%m%dT%H%M%SZ)
MANIFEST="tests/acceptance/results/P-34-series-$START.txt"
SERIES="$BASE/series/$START"
mkdir -p "$RESULTS" "$SERIES"
WORK=$(temp_folder)
# shellcheck disable=SC2064 # the folder name is fixed now
trap "rm -rf '$WORK'" EXIT

measured_name=none
if [ -n "$measured" ]; then
  measured_name=$(basename "$measured")
fi
{
  echo "# P-34 SB-T-02 series $START: installation $I, $runs runs, measured $measured_name"
  echo "# Each run: tests/acceptance/demo-host/storm.sh $I --mode script --with-playwright; copies in $SERIES"
} > "$MANIFEST"

# The storm driver's result files and checklists under tests/acceptance/results/, one name per line, sorted.
listing() {
  (cd "$RESULTS" && find . -maxdepth 1 -type f \( -name 'storm-script-*.json' -o -name 'storm-checklist-*.md' \) |
    sed 's|^\./||' | LC_ALL=C sort)
}

# The one new file of a pattern between two listings, or "none"; arguments: before, after, pattern.
new_file() {
  local found
  found=$(LC_ALL=C comm -13 "$1" "$2" | grep -E "$3" || true)
  if [ -n "$found" ] && [ "$(printf '%s\n' "$found" | wc -l | tr -d ' ')" = 1 ]; then
    echo "$found"
  else
    echo none
  fi
}

failed=0
k=1
while [ "$k" -le "$runs" ]; do
  echo "== run $k of $runs"
  listing > "$WORK/before"
  rm -rf "$BASE/e2e/screens"
  set -- "$I" --mode script --with-playwright
  if [ -n "$measured" ]; then
    set -- "$@" --measured "$measured"
  fi
  set +e
  tests/acceptance/demo-host/storm.sh "$@"
  code=$?
  set -e
  listing > "$WORK/after"
  result=$(new_file "$WORK/before" "$WORK/after" '^storm-script-.*\.json$')
  checklist=$(new_file "$WORK/before" "$WORK/after" '^storm-checklist-.*\.md$')
  mkdir -p "$SERIES/run-$k"
  for folder in screens results; do
    if [ -d "$BASE/e2e/$folder" ]; then
      cp -R "$BASE/e2e/$folder" "$SERIES/run-$k/"
    fi
  done
  echo "run $k exit $code result $result checklist $checklist" >> "$MANIFEST"
  echo "run $k exit $code result $result checklist $checklist"
  if [ "$code" -ne 0 ]; then
    failed=1
  fi
  k=$((k + 1))
done

echo "manifest $MANIFEST"
exit "$failed"
