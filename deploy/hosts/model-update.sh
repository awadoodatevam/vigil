#!/usr/bin/env bash
# Pulls a model by its tag in its unit and prints the digest Ollama reports (AR-H-11; P-05 spec Section 6.10).
# Run by deploy/models.sh update through run_on_host.
#   model-update.sh {unit} {model}
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

unit=$1
model=$2

if [ "$(docker inspect --format '{{.State.Running}}' "$unit" 2> /dev/null)" != true ]; then
  echo "Start $unit first: deploy/deploy.sh or deploy/gpu.sh start."
  exit 1
fi
mkdir -p "$VIGIL_ROOT/logs"
docker exec "$unit" ollama pull "$model" < /dev/null >> "$VIGIL_ROOT/logs/ollama-pull.log" 2>&1 || true
if [ "$unit" = vigil-llm ]; then
  address=10.40.1.20
else
  address=$(docker inspect --format '{{with index .NetworkSettings.Networks "vigil-net"}}{{.IPAddress}}{{end}}' "$unit")
fi
tags=$(curl -sS --max-time 10 "http://$address:11434/api/tags")
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
  echo "Model $model is not in $unit after ollama pull."
  exit 1
fi
echo "$digest"
