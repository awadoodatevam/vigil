# Vigil

Vigil is a NOC automation product (working name; the audience sees "Techfinity"). This repository holds the Vigil product (`app/`, `web/`), the demo-only lab and control panel (`demo/`), test tooling (`dev/`, `spikes/`, `tests/`), deployment (`deploy/`) and the project documents (`docs/`). The documents are the specification; `CLAUDE.md` tells Claude Code how to build from them, one package at a time (`docs/60-build-plan.md` Section 6).

## The gate command

```bash
tools/check-all.sh
```

It runs every check a package MUST pass, in this order, and stops at the first failure: the cloud setup version check (only in a cloud session), `./mvnw -B verify` (Spotless, Java tests, and the web application's `npm ci`, lint, Prettier, type check, Vitest and build), `ruff` and `pytest`, `shellcheck`, the demo data checks, Docker Compose validation, and `tools/trace.py --check`. The GitHub Actions job `gate` in `.github/workflows/ci.yml` runs the same command on every pull request to `main`.

Other commands: `./mvnw -B verify` (Java and web build and tests only), `tools/loc.sh origin/main HEAD` (lines of production code added and changed), `tools/trace.py` (requirement IDs without tests).

## Cloud sessions

Claude Code builds in a cloud environment named `vigil`. Its setup script is the content of `tools/cloud-setup.sh`: it installs Eclipse Temurin 25, the Docker Compose plugin, `shellcheck`, the Python tools of `tools/requirements-dev.txt` in `/opt/vigil-tools`, and `gitleaks`, and finishes within 5 minutes. The owner creates the environment once and pastes the script into its setup script field (task BP-OT-02 in `docs/60-build-plan.md`), together with the environment variables `BASH_DEFAULT_TIMEOUT_MS=600000`, `BASH_MAX_TIMEOUT_MS=1800000` and `JAVA_HOME=/opt/temurin-25`. Whenever a package changes `tools/cloud-setup.sh`, the number in its line 2 goes up and the owner updates the environment; `tools/check-all.sh` stops with "The cloud environment vigil is out of date" until then.

## Local installation

The local installation (`deploy/local.sh`) arrives in package P-26.
