# The SSE probe image of spike AR-SP-01 (P-05 spec Section 6.11). Built by deploy/build.sh for the demo installation
# and by deploy/images.sh --test as vigil/sse-probe:local.
FROM ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55

# The proxy variables are Docker's predefined build arguments: not declared, not stored in the image.
RUN --mount=type=secret,id=ca_bundle,required=false,mode=0444 \
    set -eu; \
    if [ -f /run/secrets/ca_bundle ]; then \
      echo 'Acquire::https::CAInfo "/run/secrets/ca_bundle";' > /etc/apt/apt.conf.d/99vigil-build-ca; \
      sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends python3 ca-certificates tzdata; \
    rm -f /etc/apt/apt.conf.d/99vigil-build-ca; \
    sed -i 's|https://|http://|g' /etc/apt/sources.list.d/ubuntu.sources; \
    rm -rf /var/lib/apt/lists/*

COPY probe.py index.html /opt/probe/

ENV TZ=UTC
USER 10001
ENTRYPOINT ["/usr/bin/python3", "/opt/probe/probe.py"]
