#!/usr/bin/env bash
# Builds the JARs and the images of a release for linux/amd64 and pushes them to the demo installation's ECR
# repositories under the release name (AR-H-11, BP-R-04; P-32 spec Section 6.2). Run by release.yml (BP-W-03), and on
# the Mac for the owner-run steps before a merge (CTX-D-041 (2), CTX-D-095 (1)): deploy/build.sh {release}.
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
# {image}:{folder} per image (complete list, P-32 spec Section 6.2 item 1).
IMAGES="vigil-app:app demo-control:demo/control demo-lab:demo/lab lab-zabbix-provision:demo/monitoring/provision
demo-faults:demo/faults dev-recorder:dev/recorder storm-spike:spikes/storm-timing"

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

# The JARs that the images of vigil-app and demo-control copy (BP-R-04).
./mvnw -B -q -pl app,demo/control -DskipTests package

for entry in $IMAGES; do
  tag="$REGISTRY/$INSTALLATION/${entry%%:*}:$RELEASE"
  args="--platform linux/amd64 --provenance=false --tag $tag --push"
  # An Ubuntu mirror for the apt sources of the build only (CTX-D-070), as deploy/images.sh passes it.
  if [ -n "${VIGIL_UBUNTU_MIRROR:-}" ]; then
    args="$args --build-arg UBUNTU_MIRROR=$VIGIL_UBUNTU_MIRROR"
  fi
  aws ecr get-login-password | docker login --username AWS --password-stdin "$REGISTRY"
  # shellcheck disable=SC2086 # one word per argument
  docker buildx build $args "${entry#*:}"
  docker logout "$REGISTRY"
  echo "built $tag"
done
