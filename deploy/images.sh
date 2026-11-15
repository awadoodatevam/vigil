#!/usr/bin/env bash
# Builds the local images (BP-R-04). --test: the images that tests use (P-03, P-04, P-05), after pulling the
# third-party images of deploy/local/compose.yaml. --test --java: only vigil/demo-lab:local, the image the Java tests
# use (BP-R-06), pulling only the base images of its Dockerfile (BP-R-12, P-42). --local: the images of a local
# installation, vigil/vigil-app:local (from the JAR Maven built), vigil/dev-fakes:local, vigil/e2e:local (P-26), the
# storm driver vigil/storm-driver:local (P-27) and vigil/demo-control:local (from the JAR Maven built, P-31).
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

usage() {
  echo "Usage: deploy/images.sh --test [--java] | --local"
  exit 2
}

java_only=false
local_images=false
if [ "$#" -eq 2 ] && [ "$1" = "--test" ] && [ "$2" = "--java" ]; then
  java_only=true
elif [ "$#" -eq 1 ] && [ "$1" = "--local" ]; then
  local_images=true
elif [ "$#" -ne 1 ] || [ "$1" != "--test" ]; then
  usage
fi

if [ "$local_images" = "true" ] && [ ! -f app/target/vigil-app.jar ]; then
  echo "Run ./mvnw -B -pl app -DskipTests package first."
  exit 1
fi
if [ "$local_images" = "true" ] && [ ! -f demo/control/target/demo-control.jar ]; then
  echo "Run ./mvnw -B -pl app,demo/control -DskipTests package first."
  exit 1
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

build_image() {
  local tag=$1 context=$2 name
  local args=(--network host -t "$tag" -f "$context/Dockerfile")
  for name in HTTP_PROXY HTTPS_PROXY NO_PROXY http_proxy https_proxy no_proxy; do
    if [ -n "${!name+set}" ]; then
      args+=(--build-arg "$name")
    fi
  done
  # An Ubuntu mirror for the apt sources of the build only (CTX-D-070); nothing new without the variable.
  if [ -n "${VIGIL_UBUNTU_MIRROR:-}" ]; then
    args+=(--build-arg "UBUNTU_MIRROR=$VIGIL_UBUNTU_MIRROR")
  fi
  if [ -f /etc/ssl/certs/ca-certificates.crt ]; then
    args+=(--secret "id=ca_bundle,src=/etc/ssl/certs/ca-certificates.crt")
  fi
  pull_base_images "$context/Dockerfile"
  DOCKER_BUILDKIT=1 docker build "${args[@]}" "$context"
}

if [ "$java_only" = "true" ]; then
  build_image vigil/demo-lab:local demo/lab
  exit 0
fi

if [ "$local_images" = "true" ]; then
  build_image vigil/vigil-app:local app
  build_image vigil/demo-control:local demo/control
  build_image vigil/dev-fakes:local dev/fakes
  build_image vigil/e2e:local tests/e2e
  build_image vigil/storm-driver:local tests/acceptance/storm
  exit 0
fi

# The third-party images of the local compose file: every image: value pinned by digest, except the lines ending with
# "# optional" (vigil-embed's Ollama image, the owner's option).
while read -r reference; do
  pull_image "$reference"
done < <(awk '$1 == "image:" && $2 ~ /@sha256:/ && $0 !~ /# optional$/ { print $2 }' deploy/local/compose.yaml)

build_image vigil/demo-lab:local demo/lab
build_image vigil/demo-faults:local demo/faults
build_image vigil/lab-zabbix-provision:local demo/monitoring/provision
build_image vigil/recorder:local dev/recorder
build_image vigil/storm-spike:local spikes/storm-timing
build_image vigil/sse-probe:local spikes/sse-probe
