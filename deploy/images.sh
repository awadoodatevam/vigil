#!/usr/bin/env bash
# Builds the local images (BP-R-04). --test: the images that tests use (P-03, P-04), after pulling the third-party
# images of deploy/local/compose.yaml.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

usage() {
  echo "Usage: deploy/images.sh --test"
  exit 2
}

if [ "$#" -ne 1 ] || [ "$1" != "--test" ]; then
  usage
fi

if ! docker buildx version > /dev/null 2>&1; then
  echo "BuildKit (docker buildx) is required"
  exit 1
fi

# Pulls an image when it is missing, with retries for refused requests (BP-O-04).
pull_image() {
  local reference=$1 attempt=1
  if docker image inspect "$reference" > /dev/null 2>&1; then
    return
  fi
  until docker pull "$reference"; do
    if [ "$attempt" -ge 5 ]; then
      echo "Could not pull $reference after $attempt attempts"
      exit 1
    fi
    attempt=$((attempt + 1))
    echo "Pull of $reference failed; attempt $attempt of 5 in 30 s"
    sleep 30
  done
}

pull_base_images() {
  local reference
  while read -r reference; do
    pull_image "$reference"
  done < <(awk 'toupper($1) == "FROM" { print $2 }' "$1")
}

# The third-party images of the local compose file: every image: value pinned by digest.
while read -r reference; do
  pull_image "$reference"
done < <(awk '$1 == "image:" && $2 ~ /@sha256:/ { print $2 }' deploy/local/compose.yaml)

build_image() {
  local tag=$1 context=$2 name
  local args=(--network host -t "$tag" -f "$context/Dockerfile")
  for name in HTTP_PROXY HTTPS_PROXY NO_PROXY http_proxy https_proxy no_proxy; do
    if [ -n "${!name+set}" ]; then
      args+=(--build-arg "$name")
    fi
  done
  if [ -f /etc/ssl/certs/ca-certificates.crt ]; then
    args+=(--secret "id=ca_bundle,src=/etc/ssl/certs/ca-certificates.crt")
  fi
  pull_base_images "$context/Dockerfile"
  DOCKER_BUILDKIT=1 docker build "${args[@]}" "$context"
}

build_image vigil/demo-lab:local demo/lab
build_image vigil/demo-faults:local demo/faults
build_image vigil/lab-zabbix-provision:local demo/monitoring/provision
build_image vigil/recorder:local dev/recorder
build_image vigil/storm-spike:local spikes/storm-timing
