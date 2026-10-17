#!/usr/bin/env bash
# Builds the images of a release for linux/amd64 and pushes them to the demo installation's ECR repositories under the
# release name (AR-H-11; P-05 spec Section 6.8). Run by release.yml (BP-W-03), and on the Mac for P-05's spike
# (CTX-D-041): deploy/build.sh {release}. Images in this package: the SSE probe.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."
# shellcheck source=deploy/lib/aws.sh
. deploy/lib/aws.sh

usage() {
  echo "Usage: deploy/build.sh {release}"
  exit 2
}

if [ "$#" -ne 1 ] || ! valid_release "$1"; then
  usage
fi
RELEASE=$1
check_identity
INSTALLATION=demo
# {image}:{folder} per image (complete list in P-05).
IMAGES="sse-probe:spikes/sse-probe"

for entry in $IMAGES; do
  repository="$INSTALLATION/${entry%%:*}"
  if out=$(aws ecr describe-images --repository-name "$repository" --image-ids "imageTag=$RELEASE" --output json 2>&1); then
    echo "Release $RELEASE already exists in $repository."
    exit 1
  fi
  if [ "$(error_code "$out")" != ImageNotFoundException ]; then
    echo "$out"
    exit 1
  fi
done

for entry in $IMAGES; do
  tag="$REGISTRY/$INSTALLATION/${entry%%:*}:$RELEASE"
  aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
  docker buildx build --platform linux/amd64 --provenance=false --tag "$tag" --push "${entry#*:}"
  docker logout "$REGISTRY"
  echo "built $tag"
done
