# The demo-lab image (AR-X-01, AR-U-08): one image for the five lab hosts, each started with its role.
# Built only by deploy/images.sh --test (BP-R-04 as amended by BP-A-17).

# Zabbix agent 2 of the 7.0 LTS line: the newest ubuntu-7.0 tag built on Ubuntu 24.04 (noble). Only its program is copied.
FROM zabbix/zabbix-agent2:ubuntu-7.0.25@sha256:4a84f14b76fdc317dccfad1d92a8e09b650127adc79e3c7d40d4ca802dda3a1f AS agent

FROM ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55

# The proxy variables are Docker's predefined build arguments: not declared, not stored in the image.
RUN --mount=type=secret,id=ca_bundle,required=false,mode=0444 \
    set -eu; \
    if [ -f /run/secrets/ca_bundle ]; then \
      echo 'Acquire::https::CAInfo "/run/secrets/ca_bundle";' > /etc/apt/apt.conf.d/99vigil-build-ca; \
      sed -i 's|http://|https://|g' /etc/apt/sources.list.d/ubuntu.sources; \
    fi; \
    apt-get update; \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
      python3 python3-venv openssh-server openssh-client prometheus-node-exporter libcurl4t64 \
      xfsprogs util-linux iproute2 procps ca-certificates tzdata; \
    rm -f /etc/apt/apt.conf.d/99vigil-build-ca; \
    sed -i 's|https://|http://|g' /etc/apt/sources.list.d/ubuntu.sources; \
    rm -rf /var/lib/apt/lists/*

COPY --from=agent /usr/sbin/zabbix_agent2 /usr/sbin/zabbix_agent2
RUN if ldd /usr/sbin/zabbix_agent2 | grep 'not found'; then \
      echo 'zabbix_agent2 needs libraries this image does not have'; exit 1; \
    fi

RUN --mount=type=secret,id=ca_bundle,required=false,mode=0444 \
    --mount=type=bind,source=requirements.txt,target=/tmp/requirements.txt \
    set -eu; \
    python3 -m venv /opt/venv; \
    if [ -f /run/secrets/ca_bundle ]; then \
      /opt/venv/bin/pip install --no-cache-dir --cert /run/secrets/ca_bundle -r /tmp/requirements.txt; \
    else \
      /opt/venv/bin/pip install --no-cache-dir -r /tmp/requirements.txt; \
    fi

# The image holds no SSH host key (AR-S-04): lab-db-01 writes it from its secret file at every start.
RUN rm -f /etc/ssh/ssh_host_*

RUN set -eu; \
    groupadd -g 1997 zabbix; \
    useradd -u 1997 -g zabbix -M -d /var/lib/zabbix -s /usr/sbin/nologin zabbix; \
    groupadd -g 2001 vigil-diag; \
    useradd -u 2001 -g vigil-diag -m -d /home/vigil-diag -s /bin/bash -p '*' vigil-diag; \
    groupadd -g 2002 vigil-action; \
    useradd -u 2002 -g vigil-action -G vigil-diag -m -d /home/vigil-action -s /bin/bash -p '*' vigil-action

COPY labmocks/ /opt/labmocks/labmocks/
COPY --chmod=0755 labctl.sh /usr/local/bin/labctl
COPY --chmod=0755 entrypoint.sh /usr/local/bin/lab-entrypoint
COPY sshd_config /etc/ssh/sshd_config.d/10-vigil.conf

ENV TZ=UTC PYTHONPATH=/opt/labmocks
WORKDIR /opt/labmocks
ENTRYPOINT ["/usr/local/bin/lab-entrypoint"]
