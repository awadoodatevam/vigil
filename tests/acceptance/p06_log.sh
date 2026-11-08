#!/usr/bin/env bash
# Owner-run steps 4, 5 and 7 of P-06 (spec Sections 6.10 and 6.11): runs a command from the repository root, appends
# its standard output and error to tests/acceptance/results/P-06-gpu.txt (or the file in VIGIL_P06_LOG) while showing
# them, then appends and shows the line "exit {code}", and exits with the command's code.
#   tests/acceptance/p06_log.sh {command} [{argument} ...]
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/../.."

if [ "$#" -lt 1 ]; then
  echo "Usage: tests/acceptance/p06_log.sh {command} [{argument} ...]"
  exit 2
fi
log=${VIGIL_P06_LOG:-tests/acceptance/results/P-06-gpu.txt}

set +e
"$@" 2>&1 | tee -a "$log"
code=${PIPESTATUS[0]}
set -e
echo "exit $code" | tee -a "$log"
exit "$code"
