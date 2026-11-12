# The dev fakes image vigil/dev-fakes:local (60-build-plan Section 11.3, P-26 spec Section 6.5). Built only by
# deploy/images.sh --local. One program for every role, chosen by FAKE_ROLE; Python 3.12 from Ubuntu, standard
# library only (BP-R-04).
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
    DEBIAN_FRONTEND=noninteractive apt-get -o Acquire::Retries=5 install -y --no-install-recommends python3 tzdata; \
    rm -f /etc/apt/apt.conf.d/99vigil-build-ca; \
    mv /tmp/ubuntu.sources.vigil-backup /etc/apt/sources.list.d/ubuntu.sources; \
    rm -rf /var/lib/apt/lists/*

COPY fakes.py /opt/fakes/

ENV TZ=UTC
WORKDIR /opt/fakes
ENTRYPOINT ["/usr/bin/python3", "/opt/fakes/fakes.py"]
