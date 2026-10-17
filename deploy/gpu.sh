#!/usr/bin/env bash
# Starts, stops or shows the GPU host of the demo installation (AR-G-31; P-05 spec Section 6.10). start waits until
# vigil-llm answers a warm-up chat request. Run on the Mac: deploy/gpu.sh start|stop|status.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/gpu.sh start|stop|status"
  exit 2
}

if [ "$#" -ne 1 ]; then
  usage
fi
case "$1" in
  start | stop | status) ;;
  *) usage ;;
esac
command=$1
check_identity
HOST_KIND=GPU

found=$(find_instance vigil-demo-gpu)
if [ -z "$found" ]; then
  if [ "$command" = status ]; then
    echo "gpu host: not created"
    exit 0
  fi
  echo "The GPU host does not exist yet (deploy/edge.sh, BP-OT-09)."
  exit 1
fi
ID=${found%% *}
STATE=${found#* }

# Waits (every 10 s, at most 60 attempts) until the instance has that state; returns 1 when it does not.
wait_state() {
  local attempt=0
  while [ "$(instance_state "$ID")" != "$1" ]; do
    attempt=$((attempt + 1))
    if [ "$attempt" -ge 60 ]; then
      return 1
    fi
    pause 10
  done
}

wait_stopped() {
  if ! wait_state stopped; then
    echo "The GPU host did not stop."
    exit 1
  fi
}

case "$command" in
  status)
    echo "gpu host: $found"
    if [ "$STATE" = running ]; then
      code=0
      run_on_host "$ID" deploy/gpu/host-gpu-status.sh || code=$?
      exit "$code"
    fi
    ;;
  stop)
    case "$STATE" in
      pending)
        wait_state running || true
        aws ec2 stop-instances --instance-ids "$ID" > /dev/null
        ;;
      running) aws ec2 stop-instances --instance-ids "$ID" > /dev/null ;;
    esac
    wait_stopped
    ;;
  start)
    if [ "$STATE" = stopping ]; then
      wait_stopped
      STATE=stopped
    fi
    if [ "$STATE" = stopped ]; then
      if ! out=$(aws ec2 start-instances --instance-ids "$ID" --output json 2>&1); then
        if [ "$(error_code "$out")" = InsufficientInstanceCapacity ]; then
          echo "AWS has no g6.xlarge capacity now; try again in a few minutes (AR-ASM-06)."
        else
          echo "$out"
        fi
        exit 1
      fi
    fi
    attempt=0
    until [ "$(instance_state "$ID")" = running ] && [ "$(ping_status "$ID")" = Online ]; do
      attempt=$((attempt + 1))
      if [ "$attempt" -ge 60 ]; then
        echo "The GPU host is not reachable through Systems Manager."
        exit 1
      fi
      pause 10
    done
    code=0
    run_on_host "$ID" deploy/gpu/host-gpu.sh deploy/gpu/compose.yaml deploy/models.lock -- || code=$?
    exit "$code"
    ;;
esac
