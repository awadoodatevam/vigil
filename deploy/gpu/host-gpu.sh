#!/usr/bin/env bash
# Starts vigil-llm on the GPU host and waits until the model answers (AR-G-31, AR-H-11, AR-T-18; P-05 spec
# Section 6.10). Run by deploy/gpu.sh start through run_on_host, with compose.yaml and models.lock beside it.
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
GPU="$VIGIL_ROOT/gpu"
ADDRESS=10.40.1.20

# Sleeps that many seconds, or not at all when VIGIL_TEST_FAST=1 (tests).
pause() {
  if [ "${VIGIL_TEST_FAST:-}" != 1 ]; then
    sleep "$1"
  fi
}

mkdir -p "$GPU" "$VIGIL_ROOT/logs"
cp compose.yaml models.lock "$GPU/"
cd "$GPU"

version=$(docker version --format '{{.Server.Version}}')
if [ "${version%%.*}" -lt 28 ]; then
  echo "Docker $version is older than 28.0 (AR-T-18)."
  exit 1
fi

compose() {
  docker compose -p vigil-gpu -f compose.yaml "$@"
}
if ! compose config --quiet; then
  echo "The compose file is not valid for this Docker Compose (AR-T-18)."
  exit 1
fi
compose pull --quiet
compose up -d

# The models check of host-deploy.sh step 11 for vigil-llm.
read_tags() {
  local attempt=0
  until TAGS=$(curl -sS --max-time 10 "http://$ADDRESS:11434/api/tags" < /dev/null); do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
      echo "vigil-llm does not answer on port 11434."
      exit 1
    fi
    pause 2
  done
}

digest_of() {
  printf '%s' "$TAGS" | python3 -c '
import json
import sys

model = sys.argv[1]
names = {model, model + ":latest"} if ":" not in model else {model}
for entry in json.load(sys.stdin).get("models", []):
    if entry.get("name") in names:
        print(entry.get("digest", ""))
        break
' "$1"
}

while read -r model unit recorded <&3; do
  case "$model" in
    "" | "#"*) continue ;;
  esac
  if [ "$unit" != vigil-llm ]; then
    continue
  fi
  read_tags
  digest=$(digest_of "$model")
  if [ -z "$digest" ]; then
    docker exec "$unit" ollama pull "$model" < /dev/null >> "$VIGIL_ROOT/logs/ollama-pull.log" 2>&1 || true
    read_tags
    digest=$(digest_of "$model")
    if [ -z "$digest" ]; then
      echo "Model $model is not in $unit after ollama pull."
      exit 1
    fi
  fi
  if [ "$recorded" = unrecorded ]; then
    echo "No digest recorded for $model: run deploy/models.sh update $model and commit deploy/models.lock."
    exit 1
  fi
  if [ "$digest" != "$recorded" ]; then
    echo "Model $model has digest $digest, models.lock records $recorded."
    exit 1
  fi
done 3< models.lock

# The warm-up (AR-G-31): the model is loaded once it answers a chat request.
start=$(date +%s)
attempt=0
while true; do
  attempt=$((attempt + 1))
  status=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 60 -X POST -H 'Content-Type: application/json' \
    -d '{"model":"gemma4:12b","messages":[{"role":"user","content":"Reply with OK."}],"stream":false}' \
    "http://$ADDRESS:11434/api/chat" < /dev/null || true)
  if [ "$status" = 200 ]; then
    break
  fi
  if [ "$attempt" -ge 15 ]; then
    echo "vigil-llm did not answer the warm-up."
    exit 1
  fi
  pause 10
done
echo "vigil-llm ready after $(($(date +%s) - start)) s"
