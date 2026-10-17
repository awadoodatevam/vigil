#!/usr/bin/env bash
# Copies a release's deployment files from the deployment bucket into {VIGIL_ROOT}/deploy/ and runs
# host-deploy.sh (P-05 spec Section 6.8; AR-H-11). Run on the main host by deploy/deploy.sh through run_on_host.
#   host-fetch.sh {bucket} {release} {installation} {registry}
set -euo pipefail

export AWS_REGION=us-east-1
VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}

bucket=$1
release=$2
installation=$3
registry=$4

mkdir -p "$VIGIL_ROOT/deploy"
aws s3 cp --recursive --only-show-errors "s3://$bucket/releases/$release/" "$VIGIL_ROOT/deploy/"
bash "$VIGIL_ROOT/deploy/host-deploy.sh" "$installation" "$release" "$registry"
