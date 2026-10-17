#!/usr/bin/env bash
# The main host's part of deploy/status.sh (AR-H-12; P-05 spec Section 6.9): bootstrap marker, Docker and Compose
# versions, deployed release, containers, secret directories, vigil-app's health and the models. Run by run_on_host.
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
DEPLOY="$VIGIL_ROOT/deploy"

if [ -f "$VIGIL_ROOT/bootstrap-done" ]; then
  cat "$VIGIL_ROOT/bootstrap-done"
else
  echo "no bootstrap marker"
fi
echo "docker $(docker version --format '{{.Server.Version}}')"
echo "compose $(docker compose version --short)"

if [ -f "$DEPLOY/deployed.yaml" ]; then
  grep -E '^(release|deployed_at): ' "$DEPLOY/deployed.yaml"
else
  echo "not deployed"
fi

docker compose -p vigil ps --all --format 'table {{.Name}}\t{{.State}}\t{{.Status}}'
running=$(docker ps --filter label=com.docker.compose.project=vigil --format '{{.Names}}')

# The secret directories (AR-ASM-13), checked as each container's own user.
if [ -f "$DEPLOY/units.yaml" ]; then
  units=$(python3 -c '
import sys

import yaml

with open(sys.argv[1]) as file:
    for name, unit in yaml.safe_load(file)["units"].items():
        if unit["secrets"]:
            print(name)
' "$DEPLOY/units.yaml")
  checked=0
  for unit in $units; do
    if printf '%s\n' "$running" | grep -qx "$unit"; then
      checked=$((checked + 1))
      if docker exec "$unit" sh -c 'ls /run/secrets/vigil > /dev/null' < /dev/null; then
        echo "$unit: secrets readable"
      else
        echo "$unit: cannot read its secret directory"
      fi
    fi
  done
  if [ "$checked" -eq 0 ]; then
    echo "no unit with secrets is running"
  fi
else
  echo "not deployed"
fi

if health=$(curl -sS --max-time 10 http://172.30.0.10:8080/actuator/health 2> /dev/null); then
  echo "vigil-app health: $health"
else
  echo "vigil-app does not answer on port 8080"
fi

# The models of models.lock in running units (AR-H-11).
if [ ! -f "$DEPLOY/models.lock" ]; then
  echo "not deployed"
  exit 0
fi
while read -r model unit recorded <&3; do
  case "$model" in
    "" | "#"*) continue ;;
  esac
  if ! printf '%s\n' "$running" | grep -qx "$unit"; then
    continue
  fi
  address=$(docker inspect --format '{{with index .NetworkSettings.Networks "vigil-net"}}{{.IPAddress}}{{end}}' "$unit" < /dev/null)
  if ! tags=$(curl -sS --max-time 10 "http://$address:11434/api/tags" < /dev/null 2> /dev/null); then
    echo "$unit does not answer on port 11434"
    continue
  fi
  digest=$(printf '%s' "$tags" | python3 -c '
import json
import sys

model = sys.argv[1]
names = {model, model + ":latest"} if ":" not in model else {model}
for entry in json.load(sys.stdin).get("models", []):
    if entry.get("name") in names:
        print(entry.get("digest", ""))
        break
' "$model")
  if [ -z "$digest" ]; then
    echo "$model: not in $unit"
  elif [ "$recorded" = unrecorded ]; then
    echo "$model $digest not recorded in models.lock"
  elif [ "$digest" = "$recorded" ]; then
    echo "$model $digest matches models.lock"
  else
    echo "$model $digest differs from models.lock"
  fi
done 3< "$DEPLOY/models.lock"
