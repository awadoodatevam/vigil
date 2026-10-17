#!/usr/bin/env bash
# The deployment sequence on the main host (AR-H-11; P-05 spec Section 6.8), run as root by host-fetch.sh from the
# files in {VIGIL_ROOT}/deploy/: .env, compose check, secrets, pull, start, provision, generated secrets, the rest,
# deployed.yaml and the models check.
#   host-deploy.sh {installation} {release} {registry}
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
DEPLOY="$VIGIL_ROOT/deploy"

# Sleeps that many seconds, or not at all when VIGIL_TEST_FAST=1 (tests).
pause() {
  if [ "${VIGIL_TEST_FAST:-}" != 1 ]; then
    sleep "$1"
  fi
}

installation=$1
release=$2
registry=$3
cd "$DEPLOY"

compose() {
  docker compose -p vigil -f compose.yaml --env-file .env "$@"
}

# Step 1.
printf 'VIGIL_REGISTRY=%s\nVIGIL_RELEASE=%s\nVIGIL_INSTALLATION=%s\n' "$registry" "$release" "$installation" > .env

# Step 2 (AR-T-18).
if ! compose config --quiet; then
  echo "The compose file is not valid for this Docker Compose (AR-T-18)."
  exit 1
fi

# Step 3.
bash "$DEPLOY/secrets.sh" "$installation"

# Steps 4 and 5: pull with the instance role.
aws ecr get-login-password | docker login --username AWS --password-stdin "$registry"
compose pull --quiet
docker logout "$registry"

# Step 6: every service except vigil-app and demo-control; containers of services that left the file are removed.
services=$(compose config --services)
first=$(printf '%s\n' "$services" | grep -vx -e vigil-app -e demo-control || true)
if [ -n "$first" ]; then
  # shellcheck disable=SC2086 # one word per service
  compose up -d --remove-orphans $first
fi

# Step 7 (BP-A-19).
if printf '%s\n' "$services" | grep -qx lab-zabbix-provision; then
  code=$(docker wait lab-zabbix-provision)
  if [ "$code" != 0 ]; then
    echo "lab-zabbix-provision ended with exit code $code."
    exit 1
  fi
fi

# Step 8.
bash "$DEPLOY/secrets.sh" "$installation" --generated

# Step 9.
rest=$(printf '%s\n' "$services" | grep -x -e vigil-app -e demo-control || true)
if [ -n "$rest" ]; then
  # shellcheck disable=SC2086 # one word per service
  compose up -d $rest
fi

# Step 10: deployed.yaml with the image of every running container of the project.
containers=$(docker ps --filter label=com.docker.compose.project=vigil --format '{{.Names}}' | sort)
{
  echo "release: \"$release\""
  echo "deployed_at: \"$(date -u +%Y-%m-%dT%H:%M:%SZ)\""
  echo "images:"
  for container in $containers; do
    image=$(docker inspect --format '{{.Image}}' "$container")
    reference=$(docker image inspect --format '{{index .RepoDigests 0}}' "$image" 2> /dev/null || true)
    if [ -z "$reference" ]; then
      reference=$image
    fi
    echo "  $container: \"$reference\""
  done
} > deployed.yaml.tmp
mv -f deployed.yaml.tmp deployed.yaml

# Step 11: the models check (AR-H-11).
# Sets TAGS to the answer of the unit's /api/tags, or stops when it does not answer.
read_tags() {
  local unit=$1 address=$2 attempt=0
  until TAGS=$(curl -sS --max-time 10 "http://$address:11434/api/tags" < /dev/null); do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 30 ]; then
      echo "$unit does not answer on port 11434."
      exit 1
    fi
    pause 2
  done
}

# Prints the digest of a model in TAGS (the entry {model}, or {model}:latest when it has no tag), or nothing.
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

mkdir -p "$VIGIL_ROOT/logs"
while read -r model unit recorded <&3; do
  case "$model" in
    "" | "#"*) continue ;;
  esac
  if ! printf '%s\n' "$containers" | grep -qx "$unit"; then
    continue
  fi
  address=$(docker inspect --format '{{with index .NetworkSettings.Networks "vigil-net"}}{{.IPAddress}}{{end}}' "$unit" < /dev/null)
  read_tags "$unit" "$address"
  digest=$(digest_of "$model")
  if [ -z "$digest" ]; then
    docker exec "$unit" ollama pull "$model" < /dev/null >> "$VIGIL_ROOT/logs/ollama-pull.log" 2>&1 || true
    read_tags "$unit" "$address"
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

# Step 12.
echo "deployment $release done"
