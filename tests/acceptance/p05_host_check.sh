#!/usr/bin/env bash
# The main host's part of tests/acceptance/p05_aws_check.sh (P-05 spec Section 6.12, part main-host), run by
# run_on_host: bootstrap marker, clock, DOCKER-USER rule, Docker daemon configuration, and an embedding request from
# the probe's container to vigil-embed over vigil-net.
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

cat "$VIGIL_ROOT/bootstrap-done"
chronyc tracking
timedatectl
iptables -S DOCKER-USER
cat /etc/docker/daemon.json
docker exec sse-probe python3 -c '
import json
import urllib.request

request = urllib.request.Request(
    "http://vigil-embed:11434/api/embed",
    data=json.dumps({"model": "embeddinggemma", "input": ["probe"]}).encode(),
    headers={"Content-Type": "application/json"},
)
with urllib.request.urlopen(request, timeout=120) as response:
    print("embedding length", len(json.load(response)["embeddings"][0]))
'
