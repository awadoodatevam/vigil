#!/usr/bin/env bash
# Runs the storm timing spike SB-T-01 (development run) in the container storm-spike (P-04 spec Section 6.5).
#   spikes/storm-timing/run.sh [--storms N] [--extra LIST] [--save-recordings] [--foreground] [--project NAME]
# The lab MUST be up (deploy/local.sh up --lab-only). Runs with macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

usage() {
  echo "Usage: spikes/storm-timing/run.sh [--storms N] [--extra LIST] [--save-recordings] [--foreground] [--project NAME]"
  exit 2
}

project=vigil-local
foreground=no
spike_args=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --storms | --extra)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
      spike_args="$spike_args $1 $2"
      shift
      ;;
    --save-recordings) spike_args="$spike_args --save-recordings" ;;
    --foreground) foreground=yes ;;
    --project)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
      project=$2
      shift
      ;;
    *) usage ;;
  esac
  shift
done

base="${VIGIL_LOCAL_HOME:-$HOME/.vigil-local}"
running=$(docker ps --filter name=^lab-db-01$ --filter "label=com.docker.compose.project=$project" --format '{{.Names}}')
if [ "$running" != lab-db-01 ]; then
  echo "Start the lab first: deploy/local.sh up --lab-only"
  exit 1
fi
profile=$(docker inspect -f '{{range .Config.Env}}{{println .}}{{end}}' lab-db-01 | sed -n 's/^LAB_PROFILE=//p')

if docker container inspect storm-spike > /dev/null 2>&1; then
  docker rm -f storm-spike > /dev/null
fi

file="storm-timing-$(date -u +%Y%m%dT%H%M%SZ).json"
absolute() {
  case "$1" in
    /*) echo "$1" ;;
    *) echo "$PWD/$1" ;;
  esac
}
VIGIL_SPIKE_RESULTS=$(absolute "${VIGIL_SPIKE_RESULTS:-spikes/results}")
VIGIL_SPIKE_SAVE=$(absolute "${VIGIL_SPIKE_SAVE:-dev/fakes/recordings/monitoring}")
VIGIL_LOCAL_SECRETS="$base/$project/secrets"
VIGIL_LAB_PROFILE=$profile
VIGIL_LOCAL_UID=$(id -u)
VIGIL_LOCAL_GID=$(id -g)
VIGIL_LOCAL_GENERATED="$base/$project/generated"
VIGIL_LOCAL_CONFIG="$base/$project/config"
VIGIL_LOCAL_RECORDINGS="$base/$project/recordings"
VIGIL_WEBHOOK_BASE_URL=$(sed -n 's/^webhook_base_url: *//p' deploy/values/local.yaml)
export VIGIL_SPIKE_RESULTS VIGIL_SPIKE_SAVE VIGIL_LOCAL_SECRETS VIGIL_LAB_PROFILE VIGIL_LOCAL_UID VIGIL_LOCAL_GID
export VIGIL_LOCAL_GENERATED VIGIL_LOCAL_CONFIG VIGIL_LOCAL_RECORDINGS VIGIL_WEBHOOK_BASE_URL

mode=-d
if [ "$foreground" = yes ]; then
  mode=--rm
fi
code=0
# shellcheck disable=SC2086 # one word per argument
docker compose -f deploy/local/compose.yaml -p "$project" --profile spike run --name storm-spike "$mode" \
  storm-spike $spike_args --out "/results/$file" || code=$?
echo "result file $VIGIL_SPIKE_RESULTS/$file"
if [ "$foreground" = no ]; then
  if [ "$code" -ne 0 ]; then
    exit "$code"
  fi
  echo "storm-spike started"
  exit 0
fi
exit "$code"
