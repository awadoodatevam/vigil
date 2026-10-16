#!/usr/bin/env bash
# Gate step (0) (BP-R-12): start the Docker daemon in a cloud session when it is not running.
set -euo pipefail

if docker info > /dev/null 2>&1; then
  exit 0
fi

if [ "$(id -u)" -eq 0 ]; then
  setsid dockerd --registry-mirror=https://mirror.gcr.io > /tmp/dockerd.log 2>&1 < /dev/null &
else
  setsid sudo -n --preserve-env=HTTPS_PROXY,https_proxy,HTTP_PROXY,http_proxy,NO_PROXY,no_proxy \
    dockerd --registry-mirror=https://mirror.gcr.io > /tmp/dockerd.log 2>&1 < /dev/null &
fi

for _ in $(seq 1 60); do
  if docker info > /dev/null 2>&1; then
    exit 0
  fi
  sleep 1
done

echo "The Docker daemon did not start within 60 s. Last lines of /tmp/dockerd.log:"
tail -n 20 /tmp/dockerd.log || true
exit 1
