#!/bin/bash
# User data of the main host: its bootstrap, run once as root by cloud-init at the first boot (P-05 spec Section 6.5;
# AR-H-09, AR-H-11, AR-H-12, AR-S-08, BP-A-06). The last step writes the marker {VIGIL_ROOT}/bootstrap-done.
set -euo pipefail

exec >> /var/log/vigil-bootstrap.log 2>&1
trap 'echo "bootstrap failed at line $LINENO"' ERR

VIGIL_ROOT=${VIGIL_ROOT:-/srv/vigil}
# The Compose plugin version of tools/cloud-setup.sh.
COMPOSE_VERSION="2.40.3"
APT=(apt-get -o DPkg::Lock::Timeout=600)

timedatectl set-timezone UTC

"${APT[@]}" update
DEBIAN_FRONTEND=noninteractive "${APT[@]}" install -y docker.io chrony python3-yaml python3-bcrypt curl ca-certificates
docker_version=$(dockerd --version | sed -n 's/^Docker version \([0-9][0-9.]*\).*/\1/p')
if [ -z "$docker_version" ] || [ "${docker_version%%.*}" -lt 28 ]; then
  echo "docker.io $docker_version is older than 28.0."
  exit 1
fi
apt-mark hold docker.io

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

mkdir -p /etc/docker
echo '{"log-driver": "local", "log-opts": {"max-size": "10m", "max-file": "5"}}' > /etc/docker/daemon.json

snap wait system seed.loaded
snap install aws-cli --classic

for folder in "$VIGIL_ROOT" "$VIGIL_ROOT/deploy" "$VIGIL_ROOT/secrets" "$VIGIL_ROOT/values" "$VIGIL_ROOT/logs"; do
  mkdir -p "$folder"
  chown root:root "$folder"
  chmod 0755 "$folder"
done

# The persistent DOCKER-USER rule (AR-S-08): lab-net may not open connections to vigil-app.
cat > /etc/systemd/system/vigil-docker-user.service <<'UNIT'
[Unit]
Description=Vigil: drop new connections from lab-net to vigil-app (AR-S-08)
After=docker.service
PartOf=docker.service

[Service]
Type=oneshot
RemainAfterExit=yes
ExecStart=/bin/sh -c 'iptables -C DOCKER-USER -s 172.31.0.0/24 -d 172.30.0.10 -m conntrack --ctstate NEW -j DROP 2> /dev/null || iptables -I DOCKER-USER -s 172.31.0.0/24 -d 172.30.0.10 -m conntrack --ctstate NEW -j DROP'

[Install]
WantedBy=docker.service
UNIT
systemctl daemon-reload
systemctl enable vigil-docker-user.service

systemctl enable --now docker
systemctl restart docker

echo "bootstrap main 1 docker $(docker version --format '{{.Server.Version}}') compose $(docker compose version --short)" \
  > "$VIGIL_ROOT/bootstrap-done"
echo "bootstrap done"
