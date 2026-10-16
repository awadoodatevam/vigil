# The demo-faults image (AR-U-07, AR-X-03; P-04 spec Sections 6.1 and 6.3). Built only by deploy/images.sh --test.
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

COPY faults.py /opt/faults/
COPY scripts/ /opt/faults/scripts/

ENV TZ=UTC
WORKDIR /opt/faults
ENTRYPOINT ["/usr/bin/python3", "/opt/faults/faults.py"]
