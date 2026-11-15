#!/usr/bin/env bash
# The main host's part of spikes/storm-timing/main-host.sh (P-32 spec Section 6.6; SB-T-01 final), run as root by
# run_on_host.
#   host-spike.sh start {registry} {installation} {release} {file} [{argument} ...]
#     pulls the storm spike's image and starts the container storm-spike on lab-net (an earlier one is removed first),
#     with the lab profile of the deployment, the secrets of vigil-app and demo-control, the deployed data and the
#     results folder; the spike writes /results/{file}.
#   host-spike.sh state
#     prints "state {status} {exit code}" of the container storm-spike, then its last 3 log lines.
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

usage() {
  echo "Usage: host-spike.sh start {registry} {installation} {release} {file} [{argument} ...] | state"
  exit 2
}

if [ "$#" -lt 1 ]; then
  usage
fi
case "$1" in
  state)
    if [ "$#" -ne 1 ]; then
      usage
    fi
    if ! status=$(docker inspect --format '{{.State.Status}} {{.State.ExitCode}}' storm-spike 2> /dev/null); then
      echo "state missing"
      exit 0
    fi
    echo "state $status"
    docker logs --tail 3 storm-spike 2>&1 || true
    exit 0
    ;;
  start)
    if [ "$#" -lt 5 ]; then
      usage
    fi
    ;;
  *) usage ;;
esac

registry=$2
installation=$3
release=$4
file=$5
shift 5
image="$registry/$installation/storm-spike:$release"
profile=$(sed -n 's/^VIGIL_LAB_PROFILE=//p' "$VIGIL_ROOT/deploy/.env")
if [ -z "$profile" ]; then
  echo "No lab profile in $VIGIL_ROOT/deploy/.env: deploy first."
  exit 1
fi

aws ecr get-login-password | docker login --username AWS --password-stdin "$registry"
docker pull --quiet "$image"
docker logout "$registry"

if docker container inspect storm-spike > /dev/null 2>&1; then
  docker rm -f storm-spike > /dev/null
fi
mkdir -p "$VIGIL_ROOT/spike/results"
docker run -d --name storm-spike --network lab-net -e "VIGIL_LAB_PROFILE=$profile" \
  -v "$VIGIL_ROOT/secrets/vigil-app:/run/secrets/vigil-app:ro" \
  -v "$VIGIL_ROOT/secrets/demo-control:/run/secrets/demo-control:ro" \
  -v "$VIGIL_ROOT/deploy/spike/demo-data:/demo-data:ro" \
  -v "$VIGIL_ROOT/spike/results:/results" \
  "$image" "$@" --out "/results/$file"
echo "storm-spike started: /srv/vigil/spike/results/$file"
