#!/usr/bin/env bash
# Owner-run step of P-03 (spec Section 6.10): the lab tests marked mac, in the demo profile, on the owner's Mac.
# Run from the repository root when no lab container exists. Writes tests/acceptance/results/P-03-mac-demo.txt.
set -euo pipefail

result=tests/acceptance/results/P-03-mac-demo.txt
tools="${VIGIL_TOOLS:-$HOME/.vigil-tools}"
python="$tools/bin/python"

if [ ! -x "$python" ]; then
  if ! command -v python3.12 > /dev/null 2>&1; then
    echo "Python 3.12 is required (python3.12)."
    exit 1
  fi
  python3.12 -m venv "$tools"
  "$python" -m pip install --disable-pip-version-check -r tools/requirements-dev.txt
fi

: > "$result"
uname -m 2>&1 | tee -a "$result"
docker version --format '{{.Server.Version}}' 2>&1 | tee -a "$result"
deploy/images.sh --test 2>&1 | tee -a "$result"
VIGIL_LAB_PROFILE=demo VIGIL_LAB_REPORT="$result" "$python" -m pytest -m "lab and mac" demo/lab -v 2>&1 | tee -a "$result"
