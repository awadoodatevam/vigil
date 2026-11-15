#!/usr/bin/env bash
# Runs the storm timing spike SB-T-01 (final run) on the main host of the demo installation, with the webhook recorder
# in place of vigil-app behind the load balancer (P-32 spec Section 6.6; CTX-D-095 (3)). Run on the Mac after
# deploy/deploy.sh {installation} {release} --spike:
#   spikes/storm-timing/main-host.sh {installation} {release} [--storms N] [--extra LIST]
# Starts the container storm-spike on the host (host-spike.sh start), reads its state every 5 minutes, and when it has
# exited copies the result file to spikes/results/ with deploy/fetch.sh and exits with the spike's exit code. Runs with
# macOS's bash 3.2: no empty arrays under set -u.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: spikes/storm-timing/main-host.sh {installation} {release} [--storms N] [--extra LIST]"
  exit 2
}

if [ "$#" -lt 2 ] || ! known_installation "$1" || ! valid_release "$2"; then
  usage
fi
I=$1
RELEASE=$2
shift 2
spike_args=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    --storms | --extra)
      if [ "$#" -lt 2 ] || [ -z "$2" ]; then usage; fi
      spike_args="$spike_args $1 $2"
      shift
      ;;
    *) usage ;;
  esac
  shift
done

# The spike deployment: deploy/status.sh shows dev-recorder running.
status=$(deploy/status.sh "$I" 2>&1 || true)
if ! awk '$1 == "dev-recorder" && $2 == "running" { found = 1 } END { exit found ? 0 : 1 }' <<< "$status"; then
  echo "dev-recorder is not running: deploy the spike first (deploy/deploy.sh $I $RELEASE --spike)."
  exit 1
fi
check_identity
require_running_host "vigil-$I-main" main

file="storm-timing-$(date -u +%Y%m%dT%H%M%SZ).json"
# shellcheck disable=SC2086 # one word per argument
run_on_host "$HOST_ID" spikes/storm-timing/host-spike.sh -- start "$REGISTRY" "$I" "$RELEASE" "$file" $spike_args

unread=0
while :; do
  pause 300
  if ! out=$(run_on_host "$HOST_ID" spikes/storm-timing/host-spike.sh -- state); then
    unread=$((unread + 1))
    if [ "$unread" -ge 12 ]; then
      echo "The state of storm-spike could not be read for an hour."
      exit 1
    fi
    echo "$(date -u +%H:%M:%S) the state could not be read; again in 5 minutes"
    continue
  fi
  unread=0
  echo "$(date -u +%H:%M:%S) $out"
  state=$(sed -n 's/^state //p' <<< "$out" | head -n 1)
  case "$state" in
    "exited "*) break ;;
    running* | created* | restarting*) ;;
    *)
      echo "The container storm-spike is $state."
      exit 1
      ;;
  esac
done
code=${state#exited }
if ! deploy/fetch.sh "$I" "/srv/vigil/spike/results/$file" "spikes/results/$file"; then
  exit 1
fi
echo "result file spikes/results/$file"
exit "$code"
