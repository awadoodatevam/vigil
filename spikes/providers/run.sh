#!/usr/bin/env bash
# Runs one provider spike of P-06 on the Mac (spec Section 6.9): jira, claude and openai directly; similarity and
# local-model through a Systems Manager port forward to vigil-embed on the main host or vigil-llm on the GPU host.
#   spikes/providers/run.sh jira|claude|openai|similarity|local-model
# Runs with macOS's bash 3.2; the spikes run in the virtual environment spikes/providers/.venv (Python 3.12).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: spikes/providers/run.sh jira|claude|openai|similarity|local-model"
  exit 2
}

if [ "$#" -ne 1 ]; then
  usage
fi
case "$1" in
  jira | claude | openai | similarity | local-model) ;;
  *) usage ;;
esac
spike=$1
check_identity

if ! command -v python3.12 > /dev/null 2>&1; then
  echo "Python 3.12 is needed as python3.12 (BP-OT-10)."
  exit 1
fi
VENV=spikes/providers/.venv
PY="$VENV/bin/python"
if [ ! -e "$PY" ]; then
  if ! python3.12 -m venv "$VENV" || ! "$VENV/bin/pip" install --disable-pip-version-check -r spikes/providers/requirements.txt; then
    rm -rf "$VENV"
    echo "Installing spikes/providers/requirements.txt failed."
    exit 1
  fi
fi

code=0
case "$spike" in
  jira)
    "$PY" spikes/providers/jira_spike.py || code=$?
    exit "$code"
    ;;
  claude | openai)
    "$PY" spikes/providers/ai_spike.py "$spike" || code=$?
    exit "$code"
    ;;
esac

if ! command -v session-manager-plugin > /dev/null 2>&1; then
  echo "Install the AWS Session Manager plugin first: brew install --cask session-manager-plugin (CTX-D-060)."
  exit 1
fi

if [ "$spike" = local-model ]; then
  require_running_host vigil-demo-gpu GPU
  unit=vigil-llm
  address=10.40.1.20
  program=local_model_spike.py
else
  require_running_host vigil-demo-main main
  unit=vigil-embed
  program=similarity_spike.py
  out=$(run_on_host "$HOST_ID" spikes/providers/host-address.sh -- vigil-embed 2>&1) || code=$?
  address=$(printf '%s\n' "$out" | grep -E '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' | tail -n 1 || true)
  if [ "$code" -ne 0 ] || [ -z "$address" ]; then
    printf '%s\n' "$out"
    exit 1
  fi
fi

port=$(python3.12 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1]); s.close()')
log=$(mktemp)
set -m
aws ssm start-session --target "$HOST_ID" --document-name AWS-StartPortForwardingSessionToRemoteHost \
  --parameters "{\"host\":[\"$address\"],\"portNumber\":[\"11434\"],\"localPortNumber\":[\"$port\"]}" \
  < /dev/null > "$log" 2>&1 &
forward=$!
set +m
# shellcheck disable=SC2064 # the process group and the file are fixed now
trap "kill -TERM -- -$forward 2> /dev/null || true; rm -f '$log'" EXIT

attempt=0
until "$PY" -c '
import sys
import urllib.request

opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
try:
    with opener.open(sys.argv[1], timeout=2) as response:
        sys.exit(0 if response.status == 200 else 1)
except Exception:
    sys.exit(1)
' "http://127.0.0.1:$port/api/tags"; do
  attempt=$((attempt + 1))
  if [ "$attempt" -ge 60 ]; then
    cat "$log"
    echo "The port forward to $unit did not open."
    exit 1
  fi
  pause 1
done

"$PY" "spikes/providers/$program" --address "http://127.0.0.1:$port" || code=$?
exit "$code"
