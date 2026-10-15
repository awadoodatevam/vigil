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

## Local installation

The local installation (`deploy/local.sh`) arrives in package P-26.
