#!/usr/bin/env bash
# Runs the Playwright suites against the demo installation from the Mac (P-32 spec Section 6.9 item 2; BP-R-24): the
# image vigil/e2e:local (deploy/images.sh --local) with VIGIL_BASE_URL https://demo.{demo_domain}, VIGIL_CONTROL_URL
# https://control.{demo_domain} and VIGIL_JIRA_URL endpoint.jira of deploy/values/{installation}.yaml, the secrets of
# tests/acceptance/demo-host/secrets.sh and a state folder with lab-profile. Without a suite every test except those
# tagged @local, @ai-down, @storm, @control and @live; with a suite the tests tagged @{suite}. The run folder is
# ~/.vigil-demo/{installation}/e2e (screens/, results/, report/), for the suite live its own e2e-live, because it runs
# beside the suite storm (storm.sh --live).
#   tests/acceptance/demo-host/e2e.sh {installation} [suite]
# Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

IMAGE=vigil/e2e:local

usage() {
  echo "Usage: tests/acceptance/demo-host/e2e.sh {installation} [suite]"
  exit 2
}

pattern='^[a-z][a-z0-9-]{0,19}$'
if [ "$#" -lt 1 ] || [ "$#" -gt 2 ] || ! known_installation "$1"; then
  usage
fi
I=$1
suite=${2:-}
if [ -n "$suite" ] && ! [[ $suite =~ $pattern ]]; then
  usage
fi
values="deploy/values/$I.yaml"
D=$(values_value "$values" demo_domain)
JIRA=$(values_value "$values" endpoint.jira)
PROFILE=$(values_value "$values" lab_profile)
BASE="$HOME/.vigil-demo/$I"
if [ ! -f "$BASE/secrets/seed-password-presenter" ]; then
  echo "Run tests/acceptance/demo-host/secrets.sh $I first."
  exit 1
fi
if ! docker image inspect "$IMAGE" > /dev/null 2>&1; then
  echo "Run deploy/images.sh --local first (it builds $IMAGE)."
  exit 1
fi
run="$BASE/e2e"
if [ "$suite" = live ]; then
  run="$BASE/e2e-live"
fi
mkdir -p "$run" "$BASE/state"
printf '%s\n' "$PROFILE" > "$BASE/state/lab-profile"
# The mount point of the container's tmpfs, which hides the host's node_modules (as deploy/local.sh e2e).
mkdir -p tests/e2e/node_modules

if [ -n "$suite" ]; then
  set -- --grep "@$suite"
else
  set -- --grep-invert "@local|@ai-down|@storm|@control|@live"
fi
docker run --rm --name "vigil-demo-e2e-${suite:-default}" --user "$(id -u):$(id -g)" \
  -e "VIGIL_BASE_URL=https://demo.$D" -e "VIGIL_CONTROL_URL=https://control.$D" -e "VIGIL_JIRA_URL=$JIRA" \
  -v "$PWD/tests/e2e:/e2e/tests:ro" --tmpfs /e2e/tests/node_modules -v "$run:/e2e/run" \
  -v "$BASE/secrets:/run/secrets/vigil:ro" -v "$BASE/state:/run/state:ro" \
  "$IMAGE" /e2e/node_modules/.bin/playwright test -c /e2e/tests/playwright.config.ts "$@"
