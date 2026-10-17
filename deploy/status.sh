#!/usr/bin/env bash
# Shows the state of an installation's hosts and, when the main host runs, its containers, secrets, health and models
# (AR-H-12; P-05 spec Section 6.9). Run on the Mac: deploy/status.sh {installation}.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/status.sh {installation}"
  exit 2
}

if [ "$#" -ne 1 ] || ! known_installation "$1"; then
  usage
fi
I=$1
check_identity

main=$(find_instance "vigil-$I-main")
gpu=$(find_instance "vigil-$I-gpu")
echo "main host: ${main:-not created}"
echo "gpu host: ${gpu:-not created}"
if [ -z "$main" ] || [ "${main#* }" != running ]; then
  exit 0
fi
code=0
run_on_host "${main%% *}" deploy/main/host-status.sh || code=$?
exit "$code"
