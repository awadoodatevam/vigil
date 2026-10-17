#!/usr/bin/env bash
# The gate command (BP-R-12): every check a package MUST pass, in order, stopping at the first failure.
set -euo pipefail

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
  deploy/images.sh --test
fi

step 2 "java and web build and tests"
./mvnw -B verify

step 3 "python checks and tests"
"$PY" -m ruff check .
"$PY" -m ruff format --check .
test_dirs=()
for dir in tools demo dev deploy spikes tests/acceptance; do
  if [ -d "$dir" ]; then
    test_dirs+=("$dir")
  fi
done
# The lab tests run only when the branch changes the lab (CTX-D-036).
markers="not installation"
if git rev-parse --verify --quiet origin/main > /dev/null; then
  changed=$({ git diff --name-only origin/main; git ls-files --others --exclude-standard; } | sort -u)
  if ! echo "$changed" | grep -qE '^(demo/|deploy/|dev/|spikes/|tools/|docs/31-demo-data/)'; then
    markers="not installation and not lab"
    echo "lab tests skipped: no change under demo/, deploy/, dev/, spikes/, tools/, docs/31-demo-data/"
  fi
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
