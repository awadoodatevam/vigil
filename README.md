# Vigil

Vigil is a NOC automation product (working name; the audience sees "Techfinity"). This repository holds the Vigil product (`app/`, `web/`), the demo-only lab and control panel (`demo/`), test tooling (`dev/`, `spikes/`, `tests/`), deployment (`deploy/`) and the project documents (`docs/`). The documents are the specification; `CLAUDE.md` tells Claude Code how to build from them, one package at a time (`docs/60-build-plan.md` Section 6).

## The gate command

```bash
tools/check-all.sh
```

It runs every check a package MUST pass, in this order, and stops at the first failure: in a cloud session, step (0) starts the Docker daemon when it is not running (`tools/start-docker.sh`, with the registry mirror `mirror.gcr.io`; Docker Hub stays the fallback); the cloud setup version check (only in a cloud session); the build of the images that tests use (`deploy/images.sh --test`); `./mvnw -B verify` (Spotless, Java tests, and the web application's `npm ci`, lint, Prettier, type check, Vitest and build), `ruff` and `pytest` (the Python tests of `tools/`, `demo/`, `dev/`, `deploy/local/`, `spikes/` and `tests/acceptance/`; the tests marked `lab`, which start the lab with its monitoring and run one storm, only when the branch changes a file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/`), `shellcheck`, the demo data checks, Docker Compose validation, and `tools/trace.py --check`. The GitHub Actions job `gate` in `.github/workflows/ci.yml` runs the same command on every pull request to `main`.

Other commands: `./mvnw -B verify` (Java and web build and tests only), `tools/loc.sh origin/main HEAD` (lines of production code added and changed), `tools/trace.py` (requirement IDs without tests).

## Cloud sessions

Claude Code builds in a cloud environment named `vigil`. Its setup script is the content of `tools/cloud-setup.sh`: it installs Eclipse Temurin 25, the Docker Compose plugin, `shellcheck`, the Python tools of `tools/requirements-dev.txt` in `/opt/vigil-tools`, and `gitleaks`, and finishes within 5 minutes. The owner creates the environment once and pastes the script into its setup script field (task BP-OT-02 in `docs/60-build-plan.md`), together with the environment variables `BASH_DEFAULT_TIMEOUT_MS=600000`, `BASH_MAX_TIMEOUT_MS=1800000` and `JAVA_HOME=/opt/temurin-25`. Whenever a package changes `tools/cloud-setup.sh`, the number in its line 2 goes up and the owner updates the environment; `tools/check-all.sh` stops with "The cloud environment vigil is out of date" until then.

## Database

`vigil-app` runs against PostgreSQL 17 with pgvector. At its first start the database runs `deploy/db/init/10-vigil-roles.sh`, which creates the owner role `vigil_owner`, the runtime role `vigil_app` and the extension `vector`. The same image runs as two units: `vigil-migrate` (Spring profile `migrate`, role `vigil_owner`) runs the Flyway migrations and stops with exit code 0; then `vigil-app` (no profile, role `vigil_app`) starts. Each reads only its own database password from its secret folder (`VIGIL_SECRETS_DIR`, default `/run/secrets/vigil`): `vigil-db-owner` for `vigil-migrate`, `vigil-db-app` for `vigil-app`. `VIGIL_DB_HOST`, `VIGIL_DB_PORT` and `VIGIL_DB_NAME` locate the database. The Java tests start the database in Testcontainers (`app/src/test/java/vigil/testsupport/`), so `./mvnw -B verify` needs Docker.

## Migrations

Migrations are Flyway SQL files in `app/src/main/resources/db/migration/{schema}/`, one folder per schema, named `V{yyyyMMddHHmmss}__{schema}_{description}.sql` (UTC date and time when the file is written; `{description}` in `a-z 0-9 _`). A merged migration is never edited; a change is a new migration. Rules for every migration:

- A module's first migration grants `USAGE` on its schema to `vigil_app`.
- Every `CREATE TABLE` is followed in the same file by the grant its mutability class gets: `SELECT, INSERT, UPDATE` for stateful, configuration and technical tables; `SELECT, INSERT` for immutable and revisioned tables; never `DELETE` or `TRUNCATE`.
- A migration names only its own schema.
- Every tenant-scoped table has `tenant_id uuid NOT NULL`, and every secondary index on it starts with `tenant_id`.
- The table's row is added to the class table `app/src/test/resources/db/table-classes.csv` (`table,class,tenant_scoped`), which the privilege test and the tenant test read.

## Module code

- A request is a method of a service interface in the callee's `api` package.
- Writing operations are `@Transactional(propagation = REQUIRED)`.
- An operation that makes an external call says "external call: not inside a transaction" in its Javadoc and calls `TransactionGuard.assertNoTransaction()` first.
- An event is a Java record in the publisher's `api` package carrying the tenant identifier (except AR-E-14) and the payload of `docs/50-architecture.md` Section 8.3, nothing else, published with `ApplicationEventPublisher` inside the transaction of the change.
- Listeners are `@ApplicationModuleListener` methods and are tested with `DoubleDelivery`.
- Every `*IT` that starts `vigil-app` uses `VigilTestApp` on a fresh database (`VigilDatabase.fresh()`), except a test that must run the JAR as a process.
- Time comes from the `Clock` bean through `Timestamps.now(clock)`; tests replace it with a `@Primary` test clock passed to `VigilTestApp`.
- Every module's scheduled work for tenants is matched to `TenantService.activeTenants()` at restart step (6) and on `TenantAliasChanged` (AR-R-23).

Interfaces of the platform modules for later packages (P-08):

- P-09: `TenantService.resolveHost`, `settings`, `logo`; `IdentityService.login`, `authenticate`, `logout`, `hasPermission`, `user`.
- P-11: `TenantService.createTenant`, `markReady`, `markFailed`, `setAlias`; `TenantImport` and `IdentityImport` (their problems lists become the part's `error`).
- P-13 to P-24: `AuditService.append` inside their transactions; `TenantService.activeTenants` and their own `TenantAliasChanged` listeners; `IdentityService.team`, `isMember`, `teamMembers`.
- P-39: `AuditService.query`.

## Local installation

`deploy/images.sh --test` builds the images that tests use, after pulling the pinned third-party images of `deploy/local/compose.yaml` (with retries): `vigil/demo-lab:local` (the five lab hosts, `demo/lab/`), `vigil/demo-faults:local` (`demo/faults/`), `vigil/lab-zabbix-provision:local` (`demo/monitoring/provision/`), `vigil/recorder:local` (`dev/recorder/`) and `vigil/storm-spike:local` (`spikes/storm-timing/`). It is the only way to build local images: it passes the proxy variables and the CA bundle the build needs.

```bash
deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]   # starts the lab with its monitoring
deploy/local.sh down [--volumes] [--project NAME]                      # stops it; --volumes also removes /data, the Zabbix database and the fault state
```

`up --lab-only` starts the five lab hosts (in dependency order), Zabbix (`lab-zabbix-db`, `lab-zabbix-server`, `lab-zabbix-web`, then the one-shot `lab-zabbix-provision`), Prometheus, Alertmanager, `demo-faults` (on the host network at `http://172.31.0.1:8091`) and the webhook recorder, which answers at `http://demo.vigil.test:8080` on `lab-net` in place of Vigil (`webhook_base_url` of `deploy/values/local.yaml`). No port is published. The default project is `vigil-local` and the default profile is `lab_profile` of `deploy/values/local.yaml` (`test`: a 4 GiB `/data`; `demo`: 16 GiB).

`up` generates the secrets under `~/.vigil-local/{project}/secrets/{unit}/` (files 0444 in folders 0555): the `lab-db-01` key pairs and host key, and the monitoring and fault secrets `webhook-zabbix`, `webhook-alertmanager`, `alertmanager-password`, `alertmanager-web-config`, `zabbix-admin-password`, `zabbix-db-password` and `faults-token`; it copies the Zabbix API token that `lab-zabbix-provision` writes to `~/.vigil-local/{project}/generated/zabbix-api` into `secrets/vigil-app/` and `secrets/demo-control/`. It also writes `values/derived.yaml` (`lab_db_host_key_sha256`), `config/alertmanager.yml` (the Alertmanager configuration with `webhook_base_url`) and keeps the recorder's calls in `recordings/`. Secrets never enter the repository, and existing secrets are kept (delete `~/.vigil-local/{project}` and run `down --volumes` to start from new ones). One local installation runs per Docker host, because its subnets and container names are fixed.

The storm timing spike (SB-T-01, development run) runs against the lab that is up:

```bash
spikes/storm-timing/run.sh [--storms N] [--extra LIST] [--save-recordings] [--foreground] [--project NAME]
```

It writes `spikes/results/storm-timing-{UTC time}.json` (see `spikes/storm-timing/README.md`). Vigil and the rest of the local installation arrive in P-26.
