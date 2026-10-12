# CLAUDE.md — Vigil

Instructions for Claude Code in this repository. Written in Round 6 (`docs/60-build-plan.md`). Where this file and `docs/60-build-plan.md` differ, the build plan wins.

## What this repository is

Vigil is a NOC automation product (working name; the audience sees "Techfinity"). This repository holds the Vigil product (`app/`, `web/`), the demo-only lab and control panel (`demo/`), test tooling (`dev/`, `spikes/`, `tests/`), deployment (`deploy/`) and the project documents (`docs/`). The documents are the specification. You build exactly what they specify: nothing missed, nothing extra.

## How you work: one build package at a time

You are always building one package `P-##`, specified in `docs/packages/P-##.md`. The owner has already created the branch `p-##-{slug}` with the spec as its first commit. If this session started on another branch, check out `p-##-{slug}` and work and push there.

1. Read this file, then `docs/00-context.md` Sections 1 to 11, then the package spec, then exactly the document sections the spec lists under "Read first". Do not read `docs/_superseded/` or `docs/sources/`.
2. Build only the spec's "Requirements built" (part 4). Anything the spec lists under "Not in this package" belongs to a later package. You MUST NOT add screen elements, operations, fields, settings or behaviour that no document requires.
3. Write tests for every row of part 4 whose proof is `test`. Start the test's display name with the requirement ID: JUnit `@DisplayName("AR-R-16 alerts commit in sequence order")`; Vitest and Playwright test titles; the first word of a pytest test's docstring.
4. Run `tools/check-all.sh` until it passes. You MUST NOT open the pull request before it passes.
5. Write `docs/packages/P-##-report.md` with every part of the report template (build plan Section 6.4).
6. Push the branch and open the pull request titled `P-##: {package name}` with the REST API: `gh api -X POST repos/{owner}/{repo}/pulls -f title=… -f head=p-##-{slug} -f base=main -f body=…` (`gh pr` does not work in cloud sessions). If that call is refused, put the pull request text at the end of the report and tell the owner.

When the spec lists owner-run steps, build the scripts, run what can run here, and say in the report which steps the owner runs. With the prompt "Verify P-01", write `tests/acceptance/results/P-01-verify.txt` (output of `java -version`, the content of `/etc/vigil-cloud-setup.version`, the gate command's result), commit and push it. In a later session with the prompt "Record the owner-run results of P-##", copy the values from the result files under `spikes/results/` or `tests/acceptance/results/` into the report and into the files the spec names under "Recorded by this package".

**When the spec or a document is unclear, contradicts another document, or cannot be built as written: stop.** Write the question under "Blocking questions" in the package report and say so in the session. Do not guess, and do not fill a gap with an invented value.

**When the code proves a document wrong** (a library does not behave as documented, a value does not work): record it under "Document feedback" in the report. Do not change the documents yourself.

## Documents: precedence and what you may change

Order of authority, highest first: `docs/00-context.md`; `docs/01-scope-lock.md`; the round documents (`10-product-map`, `20-domain-model`, `30-jazz-storyboard`, `31-demo-data/`, `40-demo-cut`, `50-architecture`, `60-build-plan`), each authoritative for its own topic; your package spec for how to build it.

Some documents end with sections that change earlier sections of the same document (for example `10-product-map` Sections 19 to 21, titled as amendments or changes). Where such a section and the main text differ, the later section wins.

Under `docs/` you MAY change only: your package report; the values your spec names under "Recorded by this package". After any change under `docs/31-demo-data/`, run the data checks (Commands); every check MUST pass.

## Wording in the documents

- MUST / MUST NOT are requirements. MAY is an option.
- "(complete list)" means anything not in the list is excluded.
- "Example" marks an example, not a requirement.
- Every item has an ID with its document's prefix: `CTX-`, `SL-`, `PM-`, `DM-`, `SB-`, `DD-`, `DC-`, `AR-`, `BP-`.

## Commands

| What | Command |
| --- | --- |
| Every check a package MUST pass (setup version, Java and web build and tests, Python checks and tests, shell checks, data checks, compose validation, traceability) | `tools/check-all.sh` |
| Java and web build and tests only | `./mvnw -B verify` |
| Data checks | `/opt/vigil-tools/bin/python docs/31-demo-data/tools/check_data.py docs/31-demo-data` |
| Lines of production code added and changed | `tools/loc.sh origin/main HEAD` |
| Requirement IDs with and without tests | `tools/trace.py` |
| Lab only (from P-03) | `deploy/local.sh up --lab-only [--profile test|demo]`, `deploy/local.sh down` |
| Local installation: Vigil, lab, dev fakes (from P-26) | `deploy/local.sh up`, `deploy/local.sh down`, `deploy/local.sh reset`, `deploy/local.sh logs` |
| Build the local images that tests use (from P-03) | `deploy/images.sh --test` (the gate command runs it) |
| Storm driver (from P-27) | `docker compose -f deploy/local/compose.yaml --profile acceptance run --rm storm-driver --mode immediate` |
| Playwright tests (from P-26) | `deploy/local.sh e2e [suite]` |

Builds with Testcontainers take longer than 2 minutes: run them with a longer timeout or in the background and wait for the result. A foreground command stops waiting after at most 10 minutes. In cloud sessions use the lab profile `test`.

## Environment notes (cloud sessions)

- Java 25 (Temurin) is installed by the setup script `tools/cloud-setup.sh` of the cloud environment `vigil`. If `java -version` shows 21: in package P-01, run `sudo bash tools/cloud-setup.sh` and go on; in any other package, say that the session was not started in the environment `vigil` and stop.
- If `tools/check-all.sh` says the cloud environment is out of date, say so and stop: the owner updates the environment's setup script.
- Docker and `docker compose` are available. Testcontainers needs them. Build local images only with `deploy/images.sh`, never with a plain `docker build` (it passes the proxy settings and CA bundle the build needs). Tests use those images by their local tags.
- Python tools (`ruff`, `pytest`, `PyYAML`) are in the virtual environment `/opt/vigil-tools`; Ubuntu's system Python refuses `pip install`. Run Python checks with `/opt/vigil-tools/bin/python`. Python images install their packages into `/opt/venv`.
- Reachable: GitHub (including release downloads), Maven Central, npm, PyPI, Docker Hub, `ghcr.io`, `mcr.microsoft.com`, `public.ecr.aws`, `nodejs.org`, Ubuntu package mirrors, `api.anthropic.com`.
- Not reachable: Debian package mirrors, `repo.zabbix.com`, Jira (Atlassian), Microsoft Teams, SMTP servers, `api.openai.com`, the Ollama model registry, Playwright's browser download hosts (use the `mcr.microsoft.com/playwright` image). Tests never call real external systems: use WireMock in Java tests and the dev fakes (`dev/fakes/`) in the local installation. Spikes against real systems are owner-run steps (`spikes/`).

## Code rules you MUST NOT break

- **Modules.** The 23 Java modules are top-level packages under `vigil` (`docs/50-architecture.md` Section 7.1). A module uses another module only through that module's `api` package and only if its `@ApplicationModule(allowedDependencies = …)` lists it. Shared code lives only in `vigil.shared`. `ApplicationModules.verify()` (AR-Q-01) MUST pass.
- **Data.** A module reads and writes only its own schema. No foreign keys across schemas; other modules' records are referenced by ID or key only. No deletes: the runtime database role has no `DELETE` privilege.
- **Migrations.** One Flyway folder per schema, file names as AR-P-05. Never edit a merged migration. The privilege test (AR-Q-06) and the tenant test (AR-Q-08) read every table from the database; add each new entity class to their class table.
- **External calls.** Only `vigil.connectors` and `vigil.runner` use the HTTP client, mail or SSH. Never make an external call inside a database transaction (AR-R-09). Database passwords are the one secret read outside `vigil.connectors` (AR-S-04 as amended).
- **Events.** Every event listener MUST give the same result when it gets the same event twice; add it to the double-delivery test (AR-Q-05).
- **Tenants.** Every tenant table has `tenant_id`; every query filters by it. Scheduled work and event processing run only for active tenants.
- **No demo logic in the product.** Nothing under `app/src/main` or `web/src` may know about the demo, the lab, the control panel, the dev fakes or Jazz. Demo behaviour comes only from configuration and seeded data. Tests under `app/src/test` MAY build the `demo-lab` image from `demo/lab/Dockerfile` and use recordings copied into `app/src/test/resources/recordings/` (build plan BP-R-06). Code under `demo/`, `dev/`, `spikes/` and `tests/` reaches Vigil only through `vigil-api` (`/api/v1`) and the pages of `vigil-web`.
- **Secrets.** Never commit a secret, token, password, key or webhook address, including test values. Never log one. Tests generate their secrets at start.
- **Public contract.** Every `vigil-api` operation you add goes into `app/src/main/resources/openapi/vigil-api.yaml` in the same package; the contract test checks it.
- **Exact texts.** Where `docs/40-demo-cut.md` gives an exact text (screen wording, messages, Jira and Teams content), produce exactly that text.
- **Versions.** Pin exact versions (build plan BP-R-08). When you introduce a dependency, take its latest patch release on the day the package started (the date of the spec commit) and record it in the report. Do not change an existing pin unless your spec names the change.

## Style

- Java: google-java-format through Spotless; logging through SLF4J in the JSON format of AR-S-11; no `System.out`.
- TypeScript: ESLint, Prettier, `tsc --noEmit`; TanStack Query for data; CSS Modules for styles.
- Python (`demo/`, `dev/`, `spikes/`, `tests/acceptance/`, `deploy/local/`): Python 3.12, standard library first; `ruff check` and `ruff format`; pytest in `test_*.py` files next to the code.
- Shell: `set -euo pipefail`; `shellcheck` clean.
- Tests: `*Test` for unit tests, `*IT` for tests that start PostgreSQL, WireMock, SSH, containers or a Spring context; `*.test.ts(x)` for Vitest; Playwright under `tests/e2e/`.

## Commits and pull requests

- Commit messages start with the package ID and use the imperative: `P-13: store webhook calls and raw alerts`.
- The pull request description lists the requirement IDs built and links the package report.
- Never push to `main` and never merge: `main` is not protected (GitHub Free), so only the owner merges, after review and after the `gate` job passes.
