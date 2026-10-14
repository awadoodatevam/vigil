#!/usr/bin/env bash
# VIGIL_SETUP_VERSION=1
# Setup script of the cloud environment "vigil" (BP-R-01). Run as root; it reads nothing from the repository.
# Whenever this script changes, increase the number in line 2 and SETUP_VERSION below to the same value (BP-R-31).
set -euo pipefail

SETUP_VERSION=1
START_SECONDS=$(date +%s)

# Versions (pinned on 14 October 2026, BP-R-08). tools/requirements-dev.txt holds the same Python pins as REQUIREMENTS below.
TEMURIN_TAG="jdk-25.0.4.1+1"
TEMURIN_FILE="OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz"
COMPOSE_VERSION="2.40.3"
GITLEAKS_VERSION="8.30.1"

# Third-party images that tests pull; later packages add entries.
TEST_IMAGES=()

WORK_DIR=$(mktemp -d)
trap 'rm -rf "$WORK_DIR"' EXIT

# (1) Eclipse Temurin 25
temurin_url="https://github.com/adoptium/temurin25-binaries/releases/download/${TEMURIN_TAG//+/%2B}/${TEMURIN_FILE}"
curl -fsSL -o "$WORK_DIR/$TEMURIN_FILE" "$temurin_url"
curl -fsSL -o "$WORK_DIR/$TEMURIN_FILE.sha256.txt" "$temurin_url.sha256.txt"
(cd "$WORK_DIR" && sha256sum -c "$TEMURIN_FILE.sha256.txt")
rm -rf /opt/temurin-25
mkdir -p /opt/temurin-25
tar -xzf "$WORK_DIR/$TEMURIN_FILE" -C /opt/temurin-25 --strip-components=1
update-alternatives --install /usr/bin/java java /opt/temurin-25/bin/java 2500
update-alternatives --install /usr/bin/javac javac /opt/temurin-25/bin/javac 2500
update-alternatives --set java /opt/temurin-25/bin/java
update-alternatives --set javac /opt/temurin-25/bin/javac

# (2) Docker Compose plugin
compose_file="docker-compose-linux-x86_64"
compose_base="https://github.com/docker/compose/releases/download/v${COMPOSE_VERSION}"
curl -fsSL -o "$WORK_DIR/$compose_file" "$compose_base/$compose_file"
curl -fsSL -o "$WORK_DIR/compose-checksums.txt" "$compose_base/checksums.txt"
(cd "$WORK_DIR" && grep -E " \*${compose_file}\$" compose-checksums.txt | sha256sum -c -)
mkdir -p /usr/local/lib/docker/cli-plugins
install -m 0755 "$WORK_DIR/$compose_file" /usr/local/lib/docker/cli-plugins/docker-compose

# (3) shellcheck and the virtual environment package
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y shellcheck python3-venv

# (4) Python tools in /opt/vigil-tools
cat > "$WORK_DIR/requirements-dev.txt" <<'REQUIREMENTS'
ruff==0.17.0
pytest==9.1.1
PyYAML==6.0.3
REQUIREMENTS
rm -rf /opt/vigil-tools
python3 -m venv /opt/vigil-tools
/opt/vigil-tools/bin/python -m pip install --disable-pip-version-check -r "$WORK_DIR/requirements-dev.txt"
ln -sf /opt/vigil-tools/bin/ruff /usr/local/bin/ruff
ln -sf /opt/vigil-tools/bin/pytest /usr/local/bin/pytest

# (5) gitleaks
gitleaks_file="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
gitleaks_base="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}"
curl -fsSL -o "$WORK_DIR/$gitleaks_file" "$gitleaks_base/$gitleaks_file"
curl -fsSL -o "$WORK_DIR/gitleaks-checksums.txt" "$gitleaks_base/gitleaks_${GITLEAKS_VERSION}_checksums.txt"
(cd "$WORK_DIR" && grep -E " ${gitleaks_file}\$" gitleaks-checksums.txt | sha256sum -c -)
tar -xzf "$WORK_DIR/$gitleaks_file" -C "$WORK_DIR" gitleaks
install -m 0755 "$WORK_DIR/gitleaks" /usr/local/bin/gitleaks

# (6) Test images
for image in "${TEST_IMAGES[@]}"; do
  docker pull "$image"
done

# (7) Setup version
echo "$SETUP_VERSION" > /etc/vigil-cloud-setup.version

# (8) Run time in whole seconds
echo "$(($(date +%s) - START_SECONDS))" > /etc/vigil-cloud-setup.seconds
