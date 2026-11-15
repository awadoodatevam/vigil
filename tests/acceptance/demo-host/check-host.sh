#!/usr/bin/env bash
# The main host's part of tests/acceptance/demo-host/check.sh (P-32 spec Section 6.9 item 4), run by run_on_host: the
# bootstrap marker, the clock, the DOCKER-USER rule, the published ports, a connection from lab-api-01 to vigil-app's
# address and to the public name, and the deployed release with the containers of the deployment. Every section ends
# with its exit code; the checks are made on the Mac.
#   check-host.sh {public host name of Vigil}
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
DEPLOY="$VIGIL_ROOT/deploy"
demo=$1

section() {
  local name=$1 code=0
  shift
  echo "-- $name"
  "$@" < /dev/null 2>&1 || code=$?
  echo "exit $code"
}

section bootstrap cat "$VIGIL_ROOT/bootstrap-done"
section chrony chronyc tracking
section timedatectl timedatectl
section docker-user iptables -S DOCKER-USER
section published-ports docker ps --format $'{{.Names}}\t{{.Ports}}'
section lab-to-app docker exec lab-api-01 python3 -c '
import socket
import time

start = time.monotonic()
try:
    socket.create_connection(("172.30.0.10", 8080), 5).close()
    print("lab-to-app opened after %.2f s" % (time.monotonic() - start))
except OSError as error:
    print("lab-to-app blocked after %.2f s: %s" % (time.monotonic() - start, error))
'
section lab-public-name docker exec lab-api-01 python3 -c '
import sys
import urllib.request

try:
    with urllib.request.urlopen(sys.argv[1], timeout=10) as answer:
        print("lab-public-name", answer.status)
except Exception as error:
    print("lab-public-name failed:", error)
' "https://$demo/api/v1/public/branding"
section deployed cat "$DEPLOY/deployed.yaml"
section env grep -E '^(VIGIL_RELEASE|COMPOSE_PROFILES)=' "$DEPLOY/.env"
section services docker compose -p vigil -f "$DEPLOY/compose.yaml" --env-file "$DEPLOY/.env" config --services
