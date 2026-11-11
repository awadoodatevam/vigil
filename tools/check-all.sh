#!/usr/bin/env bash
# The gate command (BP-R-12): every check a package MUST pass, in order, stopping at the first failure.
# --part java: steps (0), (1), (1a) with only the demo-lab image, and (2); --part rest: steps (0), (1), (1a), (3) to (7).
# ci.yml runs the two parts in parallel jobs (BP-W-01, CTX-D-067 (5)).
set -euo pipefail

part=""
if [ "$#" -eq 2 ] && [ "$1" = "--part" ] && { [ "$2" = "java" ] || [ "$2" = "rest" ]; }; then
  part=$2
elif [ "$#" -ne 0 ]; then
  echo "Usage: tools/check-all.sh [--part java|rest]"
  exit 2
fi

cd "$(dirname "${BASH_SOURCE[0]}")/.."

PY="${VIGIL_TOOLS:-/opt/vigil-tools}/bin/python"
if [ -d /opt/temurin-25 ]; then
  export JAVA_HOME=/opt/temurin-25
  export PATH="$JAVA_HOME/bin:$PATH"
fi

step() {
  echo "== step $1: $2"
}

if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  step 0 "docker daemon"
  tools/start-docker.sh
fi

if [ "${CLAUDE_CODE_REMOTE:-}" = "true" ]; then
  step 1 "setup version"
  expected=$(sed -n '2s/^# VIGIL_SETUP_VERSION=\([0-9][0-9]*\)$/\1/p' tools/cloud-setup.sh)
  installed=""
  if [ -f /etc/vigil-cloud-setup.version ]; then
    installed=$(cat /etc/vigil-cloud-setup.version)
  fi
  if [ -z "$expected" ] || [ "$expected" != "$installed" ]; then
    echo "The cloud environment vigil is out of date: update its setup script from tools/cloud-setup.sh."
    exit 1
  fi
fi

if [ -f deploy/images.sh ]; then
  step 1a "test images"
  if [ "$part" = "java" ]; then
    deploy/images.sh --test --java
  else
    deploy/images.sh --test
  fi
fi

if [ "$part" != "rest" ]; then
  step 2 "java and web build and tests"
  ./mvnw -B verify
fi

if [ "$part" = "java" ]; then
  echo "== all checks passed"
  exit 0
fi

step 3 "python checks and tests"
"$PY" -m ruff check .
"$PY" -m ruff format --check .
test_dirs=()
for dir in tools demo dev deploy spikes tests/acceptance; do
  if [ -d "$dir" ]; then
    test_dirs+=("$dir")
  fi
done
# The lab tests run only when the branch changes the lab (CTX-D-036, CTX-D-067 (3)).
markers="not installation"
lab=$("$PY" tools/gate_scope.py lab)
if [ "$lab" = "no-lab" ]; then
  markers="not installation and not lab"
  echo "lab tests skipped: no change to the lab (BP-R-12 step (3))"
fi
"$PY" -m pytest -m "$markers" "${test_dirs[@]}"

step 4 "shellcheck"
git ls-files '*.sh' | xargs shellcheck

step 5 "data checks"
"$PY" docs/31-demo-data/tools/check_data.py docs/31-demo-data

step 6 "compose validation"
git ls-files deploy | { grep '/compose\.yaml$' || true; } | while read -r file; do
  docker compose -f "$file" config --quiet
done

step 7 "traceability"
"$PY" tools/trace.py --check

echo "== all checks passed"
