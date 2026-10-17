#!/bin/bash
# User data of the GPU host: its bootstrap, run once as root by cloud-init at the first boot (P-05 spec Section 6.5;
# AR-H-09, AR-H-11, AR-H-12, BP-A-06). Docker, the NVIDIA container toolkit, the AWS CLI and the Systems Manager
# agent come with the image (BP-ASM-12). The last step writes the marker {VIGIL_ROOT}/bootstrap-done.
set -euo pipefail

exec >> /var/log/vigil-bootstrap.log 2>&1
trap 'echo "bootstrap failed at line $LINENO"' ERR

VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
# The Compose plugin version of tools/cloud-setup.sh.
COMPOSE_VERSION="2.40.3"
APT=(apt-get -o DPkg::Lock::Timeout=600)

timedatectl set-timezone UTC

if ! dpkg-query -W -f '${Status}' chrony 2> /dev/null | grep -q 'install ok installed'; then
  "${APT[@]}" update
  DEBIAN_FRONTEND=noninteractive "${APT[@]}" install -y chrony
fi
mkdir -p /etc/chrony/sources.d
echo 'server 169.254.169.123 prefer iburst minpoll 4 maxpoll 4' > /etc/chrony/sources.d/amazon-time-sync.sources
systemctl restart chrony

work=$(mktemp -d)
compose_file="docker-compose-linux-x86_64"
compose_base="https://github.com/docker/compose/releases/download/v${COMPOSE_VERSION}"
curl -fsSL -o "$work/$compose_file" "$compose_base/$compose_file"
curl -fsSL -o "$work/compose-checksums.txt" "$compose_base/checksums.txt"
(cd "$work" && grep -E " \*${compose_file}\$" compose-checksums.txt | sha256sum -c -)
mkdir -p /usr/local/lib/docker/cli-plugins
install -m 0755 "$work/$compose_file" /usr/local/lib/docker/cli-plugins/docker-compose
rm -rf "$work"

# The log settings (AR-H-12), merged into the image's daemon configuration (its NVIDIA runtime is kept).
mkdir -p /etc/docker
python3 - /etc/docker/daemon.json <<'PYTHON'
import json
import os
import sys

path = sys.argv[1]
settings = {}
if os.path.exists(path):
    with open(path) as file:
        text = file.read()
    if text.strip():
        settings = json.loads(text)
settings["log-driver"] = "local"
settings["log-opts"] = {"max-size": "10m", "max-file": "5"}
with open(path, "w") as file:
    json.dump(settings, file, indent=2)
    file.write("\n")
PYTHON
systemctl restart docker

for folder in "$VIGIL_ROOT" "$VIGIL_ROOT/gpu" "$VIGIL_ROOT/logs"; do
  mkdir -p "$folder"
  chown root:root "$folder"
  chmod 0755 "$folder"
done

echo "bootstrap gpu 1 docker $(docker version --format '{{.Server.Version}}') compose $(docker compose version --short)" \
  > "$VIGIL_ROOT/bootstrap-done"
echo "bootstrap done"
