# The storm timing spike image (SB-T-01; P-04 spec Sections 6.1 and 6.5). Built only by deploy/images.sh --test.
FROM ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55

# The proxy variables are Docker's predefined build arguments: not declared, not stored in the image.
# UBUNTU_MIRROR (CTX-D-070): when set, the apt sources of this build use that mirror; the image keeps its own sources.
ARG UBUNTU_MIRROR
RUN --mount=type=secret,id=ca_bundle,required=false,mode=0444 \
    set -eu; \
    cp /etc/apt/sources.list.d/ubuntu.sources /tmp/ubuntu.sources.vigil-backup; \
    if [ -n "${UBUNTU_MIRROR:-}" ]; then \
      sed -i -e "s|http://archive.ubuntu.com/ubuntu/|${UBUNTU_MIRROR}|g" \
        -e "s|http://security.ubuntu.com/ubuntu/|${UBUNTU_MIRROR}|g" /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    if [ -f /run/secrets/ca_bundle ]; then \
      echo 'Acquire::https::CAInfo "/run/secrets/ca_bundle";' > /etc/apt/apt.conf.d/99vigil-build-ca; \
      sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    apt-get -o Acquire::Retries=5 update --error-on=any; \
    DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=5 install -y --no-install-recommends python3 python3-venv openssh-client ca-certificates tzdata; \
    rm -f /etc/apt/apt.conf.d/99vigil-build-ca; \
    mv /tmp/ubuntu.sources.vigil-backup /etc/apt/sources.list.d/ubuntu.sources; \
    rm -rf /var/lib/apt/lists/*

RUN --mount=type=secret,id=ca_bundle,required=false,mode=0444 \
    --mount=type=bind,source=requirements.txt,target=/tmp/requirements.txt \
    set -eu; \
    python3 -m venv /opt/venv; \
    if [ -f /run/secrets/ca_bundle ]; then \
      /opt/venv/bin/pip install --no-cache-dir --cert /run/secrets/ca_bundle -r /tmp/requirements.txt; \
    else \
      /opt/venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt; \
    fi

COPY storm_timing.py /opt/storm-timing/

ENV TZ=UTC
WORKDIR /opt/storm-timing
ENTRYPOINT ["/opt/venv/bin/python", "/opt/storm-timing/storm_timing.py"]
