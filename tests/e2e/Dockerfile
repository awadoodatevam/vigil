# The Playwright runner image vigil/e2e:local (BP-R-04, CTX-D-081 (4)). Built only by deploy/images.sh --local, with
# tests/e2e/ as context; the version of the base image equals the @playwright/test version of package.json. The tests
# are mounted at /e2e/tests; node_modules stay in the image.
FROM mcr.microsoft.com/playwright:v1.65.0-noble@sha256:9beb546099e11f52d62e505537914870e01a67e0e6b00ae41da4775324e73dbe
WORKDIR /e2e
ENV HOME=/tmp
COPY package.json package-lock.json ./
RUN --mount=type=secret,id=ca_bundle,required=false,mode=0444 sh -c 'if [ -f /run/secrets/ca_bundle ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/ca_bundle; fi; npm ci'
