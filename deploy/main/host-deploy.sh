#!/usr/bin/env bash
# The deployment sequence on the main host (AR-H-11; P-05 spec Section 6.8, P-32 spec Section 6.4), run as root by
# host-fetch.sh from the files in {VIGIL_ROOT}/deploy/: .env, the lab's folders and Alertmanager configuration,
# compose check, secrets, pull, start, provision, generated secrets, the rest, deployed.yaml and the models check.
# The compose profile is vigil (vigil-app, demo-control) or spike (the webhook recorder, CTX-D-095 (2)).
#   host-deploy.sh {installation} {release} {registry} {profile}
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
profile=$4
cd "$DEPLOY"

compose() {
  docker compose -p vigil -f compose.yaml --env-file .env "$@"
}

# Prints a value of the values file: the text after "{name}: ", quotes removed; nothing when it is missing.
value_of() {
  awk -v prefix="$1: " 'index($0, prefix) == 1 {
    value = substr($0, length(prefix) + 1); sub(/[ \t]+$/, "", value)
    if (value ~ /^".*"$/) { value = substr(value, 2, length(value) - 2) }
    print value; exit
  }' "values/$installation.yaml" 2> /dev/null || true
}

# Step 1.
demo_domain=$(value_of demo_domain)
webhook_base_url=$(value_of webhook_base_url)
lab_profile=$(value_of lab_profile)
for name in demo_domain webhook_base_url lab_profile; do
  if [ -z "$(value_of "$name")" ]; then
    echo "The value $name is missing in values/$installation.yaml."
    exit 1
  fi
done
{
  printf 'VIGIL_REGISTRY=%s\nVIGIL_RELEASE=%s\nVIGIL_INSTALLATION=%s\n' "$registry" "$release" "$installation"
  printf 'COMPOSE_PROFILES=%s\nVIGIL_DEMO_DOMAIN=%s\n' "$profile" "$demo_domain"
  printf 'VIGIL_WEBHOOK_BASE_URL=%s\nVIGIL_LAB_PROFILE=%s\n' "$webhook_base_url" "$lab_profile"
} > .env

# Step 1a: the lab's folders and Alertmanager's configuration with webhook_base_url (as deploy/local.sh writes it);
# with the profile spike also the recorder's folder.
root=no
if [ "$(id -u)" -eq 0 ]; then
  root=yes
fi
if [ ! -d "$VIGIL_ROOT/generated" ]; then
  mkdir -m 0700 "$VIGIL_ROOT/generated"
  if [ "$root" = yes ]; then
    chown 10001 "$VIGIL_ROOT/generated"
  fi
fi
if [ ! -d "$VIGIL_ROOT/config" ]; then
  mkdir -m 0755 "$VIGIL_ROOT/config"
fi
if [ "$profile" = spike ] && [ ! -d "$VIGIL_ROOT/spike/recordings" ]; then
  mkdir -p "$VIGIL_ROOT/spike"
  mkdir -m 0755 "$VIGIL_ROOT/spike/recordings"
  if [ "$root" = yes ]; then
    chown 10001 "$VIGIL_ROOT/spike/recordings"
  fi
fi
awk -v url="$webhook_base_url" '{
  out = ""; rest = $0
  while ((i = index(rest, "${webhook_base_url}")) > 0) { out = out substr(rest, 1, i - 1) url; rest = substr(rest, i + 19) }
  print out rest
}' monitoring/alertmanager/alertmanager.yml > "$VIGIL_ROOT/config/.alertmanager.yml.tmp"
chmod 0644 "$VIGIL_ROOT/config/.alertmanager.yml.tmp"
mv -f "$VIGIL_ROOT/config/.alertmanager.yml.tmp" "$VIGIL_ROOT/config/alertmanager.yml"

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

# Step 6: the containers of the other profile's services are removed; then every service except vigil-app and
# demo-control starts; containers of services that left the file are removed.
if [ "$profile" = spike ]; then
  compose --profile vigil rm -s -f vigil-app demo-control
else
  compose --profile spike rm -s -f dev-recorder
fi
services=$(compose config --services)
first=$(grep -vx -e vigil-app -e demo-control <<< "$services" || true)
if [ -n "$first" ]; then
  # shellcheck disable=SC2086 # one word per service
  compose up -d --remove-orphans $first
fi

# Step 7 (BP-A-19).
if grep -qx lab-zabbix-provision <<< "$services"; then
  code=$(docker wait lab-zabbix-provision)
  if [ "$code" != 0 ]; then
    echo "lab-zabbix-provision ended with exit code $code."
    exit 1
  fi
fi

# Step 8.
bash "$DEPLOY/secrets.sh" "$installation" --generated

# Step 9 (nothing with the profile spike).
rest=$(grep -x -e vigil-app -e demo-control <<< "$services" || true)
if [ "$profile" != spike ] && [ -n "$rest" ]; then
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
  if ! grep -qx "$unit" <<< "$containers"; then
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
