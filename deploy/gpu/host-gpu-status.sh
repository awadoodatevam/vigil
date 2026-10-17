#!/usr/bin/env bash
# The GPU host's part of deploy/gpu.sh status (AR-G-31; P-05 spec Section 6.10): containers, the models compared with
# the models.lock that the last deploy/gpu.sh start copied, and the GPU's memory. Run by run_on_host.
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

docker compose -p vigil-gpu ps
running=$(docker ps --filter label=com.docker.compose.project=vigil-gpu --format '{{.Names}}')

if [ ! -f "$VIGIL_ROOT/gpu/models.lock" ]; then
  echo "not deployed"
else
  while read -r model unit recorded <&3; do
    case "$model" in
      "" | "#"*) continue ;;
    esac
    if ! printf '%s\n' "$running" | grep -qx "$unit"; then
      continue
    fi
    if ! tags=$(curl -sS --max-time 10 http://10.40.1.20:11434/api/tags < /dev/null 2> /dev/null); then
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
  done 3< "$VIGIL_ROOT/gpu/models.lock"
fi

nvidia-smi --query-gpu=name,memory.used,memory.total --format=csv
