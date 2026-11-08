#!/usr/bin/env bash
# Prints the vigil-net address of a container on the main host (P-06 spec Section 6.9). Run by
# spikes/providers/run.sh similarity through run_on_host.
#   host-address.sh {container}
set -euo pipefail

export AWS_REGION=us-east-1

unit=$1

if [ "$(docker inspect --format '{{.State.Running}}' "$unit" 2> /dev/null)" != true ]; then
  echo "Start $unit first: deploy/deploy.sh."
  exit 1
fi
docker inspect --format '{{with index .NetworkSettings.Networks "vigil-net"}}{{.IPAddress}}{{end}}' "$unit"
