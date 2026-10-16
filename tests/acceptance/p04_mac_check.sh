#!/usr/bin/env bash
# Owner-run step of P-04 (spec Section 6.13): the storm timing spike with 3 storms in the demo profile on the Mac.
# Run from the repository root when no lab container exists. Writes tests/acceptance/results/P-04-mac-demo.txt.
set -euo pipefail

result=tests/acceptance/results/P-04-mac-demo.txt

: > "$result"
uname -m 2>&1 | tee -a "$result"
docker version --format '{{.Server.Version}}' 2>&1 | tee -a "$result"
deploy/images.sh --test 2>&1 | tee -a "$result"
awk '$1 == "image:" && $2 ~ /@sha256:/ { print $2 }' deploy/local/compose.yaml | while read -r image; do
  docker image inspect --format '{{index .RepoDigests 0}} {{.Os}}/{{.Architecture}}' "$image" 2>&1 | tee -a "$result"
done
deploy/local.sh up --lab-only --profile demo 2>&1 | tee -a "$result"

spike=(spikes/storm-timing/run.sh --storms 3 --foreground)
if command -v caffeinate > /dev/null 2>&1; then
  spike=(caffeinate -i "${spike[@]}")
fi
# The spike's exit status is kept, so the lab is stopped also when it failed.
set +e
"${spike[@]}" 2>&1 | tee -a "$result"
code=${PIPESTATUS[0]}
set -e

deploy/local.sh down 2>&1 | tee -a "$result"
grep '^result file ' "$result" | tail -n 1
exit "$code"
