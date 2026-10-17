#!/usr/bin/env bash
# Prints the bootstrap marker line of the host; exit 1 when it is missing (P-05 spec Sections 6.1 and 6.5).
# Run on a host by run_on_host (deploy/lib/aws.sh).
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

if [ ! -f "$VIGIL_ROOT/bootstrap-done" ]; then
  exit 1
fi
cat "$VIGIL_ROOT/bootstrap-done"
