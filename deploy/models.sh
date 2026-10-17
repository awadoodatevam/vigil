#!/usr/bin/env bash
# Changes an Ollama model on purpose: pulls its tag in its unit and writes the new digest into deploy/models.lock in
# the working tree, which the operator commits (AR-H-11; P-05 spec Section 6.10). Run on the Mac:
# deploy/models.sh update {model}.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/models.sh update {model}"
  exit 2
}

if [ "$#" -ne 2 ] || [ "$1" != update ]; then
  usage
fi
MODEL=$2
LOCK=deploy/models.lock
check_identity

unit=$(awk -v model="$MODEL" '$1 !~ /^#/ && $1 == model { print $2; exit }' "$LOCK")
old=$(awk -v model="$MODEL" '$1 !~ /^#/ && $1 == model { print $3; exit }' "$LOCK")
if [ -z "$unit" ]; then
  echo "Unknown model $MODEL; models.lock lists: $(awk '$1 !~ /^#/ && NF { s = s (s == "" ? "" : ", ") $1 } END { print s }' "$LOCK")."
  exit 2
fi

if [ "$unit" = vigil-llm ]; then
  HOST_KIND=GPU
  found=$(find_instance vigil-demo-gpu)
  missing="The GPU host does not exist yet (deploy/edge.sh, BP-OT-09)."
else
  found=$(find_instance vigil-demo-main)
  missing="The main host does not exist yet (deploy/edge.sh)."
fi
if [ -z "$found" ]; then
  echo "$missing"
  exit 1
fi
if [ "${found#* }" != running ]; then
  echo "Start $unit first: deploy/deploy.sh or deploy/gpu.sh start."
  exit 1
fi

code=0
out=$(run_on_host "${found%% *}" deploy/hosts/model-update.sh -- "$unit" "$MODEL") || code=$?
new=$(printf '%s\n' "$out" | tail -n 1)
if [ "$code" -ne 0 ] || ! printf '%s\n' "$new" | grep -qE '^[0-9a-f]{64}$'; then
  printf '%s\n' "$out"
  if [ "$code" -eq 0 ]; then
    code=1
  fi
  exit "$code"
fi

python3 - "$LOCK" "$MODEL" "$new" <<'PYTHON'
import sys

path, model, digest = sys.argv[1:4]
with open(path) as file:
    lines = file.read().splitlines(keepends=True)
with open(path, "w") as file:
    for line in lines:
        parts = line.split()
        if not line.startswith("#") and len(parts) == 3 and parts[0] == model:
            line = f"{model} {parts[1]} {digest}\n"
        file.write(line)
PYTHON
echo "$MODEL: $old -> $new"
