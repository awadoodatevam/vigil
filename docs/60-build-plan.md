# Vigil — Build Plan

| Field | Value |
| --- | --- |
| Document | 60-build-plan (ID prefix `BP-`) |
| Version | 1.10 |
| Date | 19 October 2026 |
| Owner | Abdul Wadood |
| Status | Baseline (owner reviewed). Round 6 closed. |

## 1. Purpose

This document turns the design of Rounds 0 to 5 into work that one builder (CTX-C-01) can carry out with Claude Code. It defines (complete list): the environments in which code is built, tested and run (Section 4); the repository layout and conventions (Section 5); how a build package is specified, built, checked and accepted (Section 6); the complete list of build packages with their scope, tests and done-checks (Section 7); their order, phases and the checkpoints SL-CP-1 to SL-CP-3, including the screens of SL-CP-1 (Section 8, resolves SL-O-19); where every spike and test of earlier rounds runs (Section 9); the build pipeline and deployment scripts (Section 10); the local installation used on the owner's Mac, in CI and in cloud sessions (Section 11); and the tasks only the owner can do (Section 12).

It does not change what Vigil does. Where building needs a mechanism that an earlier document lacks, the change is listed as an amendment (Section 17.2) and is written into that document when the owner closes this round, before P-01 starts.

`CLAUDE.md` at the repository root is the second output of this round: the instruction file Claude Code reads at the start of every session. It repeats the rules of Sections 5 and 6 that Claude Code needs while building. Where `CLAUDE.md` and this document differ, this document wins.

## 2. Inputs read

- `docs/00-context.md` v1.9.
- `docs/01-scope-lock.md` v1.9.
- `docs/10-product-map.md` v1.3.
- `docs/20-domain-model.md` v1.3.
- `docs/30-jazz-storyboard.md` v1.2.
- `docs/31-demo-data/` v1.2: every file; `04-operations-actions-recovery.yaml`, `06-workflow.yaml`, `07-settings.yaml`, `10-lab-and-faults.yaml` and `tools/check_data.py` in detail.
- `docs/40-demo-cut.md` v1.1.
- `docs/50-architecture.md` v1.0.
- The repository on the owner's Mac (`noc automation`), 12 October 2026: branch `main` at commit 5cf1027, `docs/` and `.gitignore` only, no remote, no code.
- Owner answers given in the Claude project conversation on 12 October 2026 (complete list): (1) there is no target demo date yet (CTX-O-01 stays open); (2) building is done in Claude Code cloud sessions, and the owner also runs the software on his Mac, which has Docker; (3) the repository is hosted as a private GitHub repository with GitHub Actions.
- Official documentation checked on 12 October 2026 (Section 16).

Not read: `docs/_superseded/` (its README states that its files are not inputs to any round).

## 3. Terms (complete list)

Terms defined in earlier documents keep their meaning. The terms below are added.

| Term | Meaning |
| --- | --- |
| Build package | One unit of building work, ID `P-##`, specified in `docs/packages/P-##.md`, built on one branch and merged by one pull request (Section 6). |
| Package spec | The file `docs/packages/P-##.md` that tells Claude Code exactly what to build for one package (template Section 6.2). |
| Package report | The file `docs/packages/P-##-report.md` that Claude Code writes at the end of a package (template Section 6.4). |
| Module core | A module's records, rules, requests, events, step handlers and seed import part, with their tests; without the HTTP operations of `vigil-api` that screens use. |
| API layer | The HTTP operations of `50-architecture` Section 10.2 that `vigil-api` offers, with their OpenAPI entries and contract tests. |
| Cloud session | A Claude Code session that runs in an Anthropic-hosted cloud environment (BP-E-01). |
| Cloud environment `vigil` | The Claude Code cloud environment the owner creates for this repository (BP-OT-02). |
| Owner-run step | A step of a package that only the owner can do, on his Mac or in AWS, with a script the package provides (BP-L-5). |
| Local installation | An installation of Vigil, the lab and the dev fakes from `deploy/local/compose.yaml` on one machine without the AWS edge (Section 11). It runs on the owner's Mac, on GitHub Actions runners and in cloud sessions. |
| Demo installation | The installation on the AWS main host and GPU host of `50-architecture` Section 17, reached by the public names. |
| Lab size profile | `demo` or `test`: the size of the lab DB server's `/data` filesystem and baseline content (BP-R-30). |
| Dev fakes | Test doubles of the external systems that a local installation uses instead of the real ones (Section 11.3). They are neither product nor demo-only components. |
| Gate command | `tools/check-all.sh`: the one command that runs every check a package MUST pass before its pull request (BP-R-12). |
| Size class | S, M or L: the estimated amount of production code in a package (BP-R-05). |
| Phase | A group of packages that ends at a milestone (Section 8.1). |
| Owner task | Work only the owner can do, such as creating accounts or approving costs, ID `BP-OT-##` (Section 12). |

## 4. Build environments (complete list)

### 4.1 Environments

| ID | Environment | Facts this plan relies on (Section 16) | Used for |
| --- | --- | --- | --- |
| BP-E-01 | Claude Code cloud session in the cloud environment `vigil` | Ubuntu 24.04 on x86_64; about 4 vCPU, 16 GB memory, 30 GB disk; Docker with `docker compose`; OpenJDK 21, Maven, Node.js 22, Python 3 with pytest, PostgreSQL 16 pre-installed; a setup script runs as root before the session and is cached as a filesystem snapshot when it finishes in about 5 minutes; Trusted network access reaches GitHub (including release assets), Maven Central, the npm registry, PyPI, Docker Hub, `ghcr.io`, `mcr.microsoft.com`, `public.ecr.aws`, `nodejs.org`, Ubuntu package mirrors and `api.anthropic.com`; it does not reach Debian package mirrors, `repo.zabbix.com`, Atlassian, Microsoft Teams webhooks, SMTP servers, `api.openai.com` or the Ollama model registry; `gh` works through a proxy that refuses GraphQL subcommands such as `gh pr`, while `gh api` REST calls work; the variable `CLAUDE_CODE_REMOTE` is `true`; a foreground command waits 2 minutes by default and at most 10 minutes, then moves to the background, where it may run 30 more minutes unless `BASH_DEFAULT_TIMEOUT_MS` is set above 1,800,000 ms; Ubuntu's system Python refuses system-wide `pip install` (externally managed). | Writing code; unit, module and connector tests; the local installation with dev fakes and the `test` lab profile. |
| BP-E-02 | GitHub Actions on GitHub-hosted Ubuntu runners | x86_64; for private repositories 2 CPU, 8 GB memory, 14 GB SSD; Docker; full internet access; included minutes on GitHub Free 2,000 a month, on GitHub Pro 3,000; larger runners need GitHub Team or Enterprise Cloud. | The merge gate `ci.yml`, the end-to-end run `e2e.yml`, the image build `release.yml` (Section 10.1). |
| BP-E-03 | The owner's Mac | Apple Silicon (arm64), macOS, Docker installed (owner answer 2). | Running the local installation with the `demo` lab profile; spikes that need real external services with the owner's credentials; the operator's scripts against AWS (`deploy/*.sh`). |
| BP-E-04 | AWS main host | `50-architecture` AR-H-01 (x86_64). | The demo installation; spikes that need the public edge or the real host. |
| BP-E-05 | AWS GPU host | `50-architecture` AR-H-02. | The local model; SB-T-04 (local model part). |

### 4.2 Environment rules (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| BP-R-01 | `tools/cloud-setup.sh` MUST (complete list): install Eclipse Temurin 25 from its GitHub release asset, verified by its published SHA-256, into `/opt/temurin-25` and make it the default `java` and `javac` with `update-alternatives`; install the Docker Compose plugin version pinned in the script from its GitHub release; install `shellcheck` from Ubuntu; create the virtual environment `/opt/vigil-tools` and install into it the Python tools of `tools/requirements-dev.txt` (exact versions: `ruff`, `pytest`, `PyYAML`), with links to `ruff` and `pytest` in `/usr/local/bin`; install `gitleaks` from its GitHub release, verified by checksum; pull the third-party container images that tests use (the list is in the script); write the script's version line to `/etc/vigil-cloud-setup.version`; write its own run time in whole seconds to `/etc/vigil-cloud-setup.seconds`. It MUST exit 0 and finish within 5 minutes. It does not read the repository; Maven and npm dependencies are fetched at build time (Maven Central and npm are reachable). `tools/check-all.sh` runs Python with `${VIGIL_TOOLS:-/opt/vigil-tools}/bin/python`; `ci.yml` creates the same virtual environment. | AR-T-01, AR-T-18; BP-ASM-01 |
| BP-R-02 | Every check of the gate command MUST run in BP-E-01 and BP-E-02 without any system that BP-E-01 cannot reach. External systems are replaced in Java tests by WireMock (`50-architecture` AR-T-20) and in the local installation by the dev fakes (Section 11.3). | AR-T-20 |
| BP-R-03 | A test that needs a real external system (Jira, Teams, SES, Claude, OpenAI, Ollama models, the AWS edge) is a spike or an acceptance test (Section 9). It runs only in BP-E-03, BP-E-04 or BP-E-05 as an owner-run step (BP-L-5), started with a script under `spikes/` or `tests/acceptance/`, which writes its result file under `spikes/results/` or `tests/acceptance/results/`. | AR §19.2, §19.3 |
| BP-R-04 | **Images.** The images of `vigil-app` and `demo-control` copy the JAR that Maven built on the build machine and download nothing during the image build. The images of the lab, `demo-faults`, `lab-zabbix-provision`, the recorder, the storm timing spike (P-04), the SSE probe (P-05), the dev fakes and the storm driver are based on `ubuntu:24.04` (amendment BP-A-08), install only from Ubuntu mirrors and PyPI (except that the `demo-lab` image also copies the Zabbix agent 2 program from the pinned official `zabbix/zabbix-agent2` image in a build stage, amendment BP-A-17), and install Python packages into the virtual environment `/opt/venv` of the image. Playwright runs in the pinned image `mcr.microsoft.com/playwright`, with `tests/e2e/node_modules` installed by `npm ci` on the machine that runs it and mounted read-only. Local images are built only by `deploy/images.sh` (`--test` builds the images that tests use), which passes the build machine's proxy variables as build arguments, builds with `--network host`, and gives the machine's CA bundle `/etc/ssl/certs/ca-certificates.crt` to the build as a BuildKit secret, so that builds reach the mirrors through a cloud session's proxy (BP-ASM-02); every `RUN` step that uses the secret mounts it with mode 0444, because apt fetches as its sandbox user `_apt`, which cannot read the default mode 0400 (P-03 report, document feedback 3). Before it builds, `deploy/images.sh --test` pulls every base image of the Dockerfiles it builds and every third-party image named in `deploy/local/compose.yaml` that is not present, each pull tried again after 30 s and at most 5 times (BP-O-04). Tests use these images by their local tags and do not build images themselves. Images of the demo installation are built for `linux/amd64` by `release.yml`; images of a local installation are built on the machine that runs it, for its own architecture. Third-party images are pinned by tag and by the digest of their multi-architecture index. | AR-H-11, AR-T-21; BP-ASM-02, BP-ASM-05 |

## 5. Repository layout and conventions

### 5.1 Layout (complete list of top-level entries)

| Path | Content | Category |
| --- | --- | --- |
| `CLAUDE.md` | Instructions for Claude Code (this round). | Other |
| `README.md` | What the repository is, the gate command, how to run the local installation. | Other |
| `pom.xml`, `mvnw`, `.mvn/` | Root Maven aggregator with the modules `app` and `demo/control`; Maven wrapper. The aggregator holds no code and no dependency management shared by the two modules. | Other |
| `app/` | The Maven project of `vigil-app` (AR-U-01): `src/main/java/vigil/{package}/` per module (AR §7.1) with its `api` sub-package; `src/main/resources/db/migration/{schema}/` (AR-P-05); `src/main/resources/openapi/vigil-api.yaml` (AR-I-08); `src/test/`; `Dockerfile` (one image for `vigil-app` and `vigil-migrate`). | Product |
| `web/` | `vigil-web` (AR-M-24): Vite, React, TypeScript; built into the JAR by `frontend-maven-plugin`, which also runs its lint, type check and Vitest tests (AR-T-07). | Product |
| `demo/lab/` | The `demo-lab` image and its mock services (AR-U-08, AR-X-01). | Demo-only |
| `demo/monitoring/` | `lab-zabbix-provision`, Prometheus, Alertmanager and node exporter configuration (AR-U-09, AR-U-10, AR-X-02). | Demo-only |
| `demo/faults/` | `demo-faults` (AR-U-07, AR-X-03). | Demo-only |
| `demo/control/` | `demo-control` (AR-U-06, AR-X-04): Maven project (Java 25, Spring Boot 4.1) with one static page. | Demo-only |
| `dev/fakes/` | The dev fakes (Section 11.3), their image and `recordings/` (sanitised payloads from the spikes). | Other (test tooling) |
| `dev/recorder/` | Webhook recorder used by the storm timing spike (P-04). | Other (test tooling) |
| `deploy/` | `db/init/` (the `vigil-db` init script, AR-U-02); `main/`, `gpu/` and `local/` compose files; `local/bundle.py`; `images.sh` (BP-R-04); `values/{installation}.yaml`; `units.yaml`; `models.lock`; the scripts of Section 10.3; `edge.md`. | Other (operations) |
| `spikes/` | Re-runnable spike scripts (Section 9) with their README and their pytest tests (BP-R-10); `results/` for spike results (owner-run and, for P-04's storm timing spike, also the cloud session's). | Other (test tooling) |
| `tests/e2e/` | Playwright tests (TypeScript). | Other (test tooling) |
| `tests/acceptance/` | The storm driver and other scripted acceptance runs (Python); `results/`. | Other (test tooling) |
| `tools/` | `cloud-setup.sh`, `requirements-dev.txt`, `check-all.sh`, `start-docker.sh` (from P-03), `trace.py`, `loc.sh`. | Other |
| `.github/workflows/` | `ci.yml`, `e2e.yml`, `release.yml` (Section 10.1). | Other |
| `.gitignore` | Build outputs, `node_modules/`, local results and generated files. | Other |
| `.gitleaksignore` | Fingerprints of `gitleaks` findings that are false positives, one per line (P-05 deviation 9; CTX-D-042). | Other |
| `docs/` | The project documents, with `docs/packages/` for package specs and reports. | Other |

### 5.2 Conventions (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| BP-R-05 | **Size classes.** A package's size is its estimated production code: Java, TypeScript, Python and shell under `app/src/main`, `web/src`, `demo/`, `dev/` and `deploy/`, excluding tests, generated code, data files and lock files. S: at most 800 lines; M: at most 2,000; L: at most 3,500. A package whose estimate in its spec exceeds 3,500 lines MUST be split before it starts. `tools/loc.sh {base} {head}` counts the lines a branch adds and changes; the package report records the count. | CTX-C-01 |
| BP-R-06 | **Product and demo separation.** Production code under `app/src/main` and `web/src` MUST NOT reference any file, name or address of `demo/`, `dev/`, `spikes/` or `tests/`. Test code under `app/src/test` MAY use (complete list): the `demo-lab` image that `deploy/images.sh --test` built, by its local tag; payload files copied from `dev/fakes/recordings/` into `app/src/test/resources/recordings/`. Code under `demo/`, `dev/`, `spikes/` and `tests/` MUST reach Vigil only through `vigil-api` (`/api/v1`) and the pages of `vigil-web`. A test (ArchUnit on production classes plus a text search of `app/src/main` and `web/src` for `demo-control`, `demo-faults`, `dev-fakes`, `fakes`, `172.31.0.1`) MUST fail on a violation. | CTX-C-03; SL-B-10; AR-R-02, AR-R-03 |
| BP-R-07 | **Branches and pull requests.** One package = one branch `p-##-{slug}` (lowercase, words joined by `-`) = one pull request titled `P-##: {package name}`, merged by squash after the `ci.yml` job `gate` passes. The package spec is the branch's first commit (BP-L-3). Commit messages are in the imperative and start with the package ID. When a cloud session starts on another branch, Claude Code checks out `p-##-{slug}` and pushes to it. Claude Code opens the pull request with the GitHub tool the session offers (a GitHub tool such as `create_pull_request`, or `gh api -X POST repos/{owner}/{repo}/pulls` where `gh` is installed); when that is refused, it writes the pull request text into the report and the owner opens it. `main` changes only through such pull requests. The repository stays on GitHub Free, where `main` cannot be protected (BP-D-13), so the owner enforces this: he merges only after `gate` passes and never pushes to `main` directly; Claude Code never pushes to `main`. | CTX-C-01 |
| BP-R-08 | **Versions.** Exact versions are pinned (complete list of places): Maven dependency and plugin versions in the POMs (no ranges); the Maven wrapper; npm packages with exact versions in `package.json` and lock files; container images in compose files and Dockerfiles by tag and digest; Ollama models in `deploy/models.lock` (AR-H-11); Temurin, Node.js, Docker Compose, `gitleaks` in `tools/cloud-setup.sh` and the workflows; Python tools in `tools/requirements-dev.txt` and in each image's requirements file; GitHub Actions actions by commit SHA. A package that introduces a dependency chooses its latest patch release on the day the package starts, as AR §5 says, and records it in its report; when that release needs a newer minor or major version of a library the Spring Boot BOM manages, it chooses the latest release that does not (CTX-D-038). Changing an existing pin is done only in a package whose spec names the change; a change of minor or major version needs a decision log entry (AR §5). | AR §5 |
| BP-R-09 | **Formatting and static checks.** Java: Spotless with google-java-format. TypeScript: ESLint, Prettier, `tsc --noEmit` (run by `frontend-maven-plugin` in the Maven build). Python (every Python file outside `docs/`): `ruff check` and `ruff format --check`. Shell: `shellcheck`. Compose files: `docker compose config --quiet` (with no secrets present; paths to secrets and values come from variables with defaults). | CTX-C-01 |
| BP-R-10 | **Tests.** Java unit tests are `*Test` (Surefire); tests that start PostgreSQL, WireMock, an SSH server, containers or a Spring context are `*IT` (Failsafe). Web unit tests are `*.test.ts(x)` (Vitest). Python tests use pytest, in `test_*.py` files next to the code they test. End-to-end tests are under `tests/e2e/`. Every test of a requirement starts its display name with the requirement's ID: JUnit `@DisplayName("AR-R-16 …")`; Vitest and Playwright test titles; the first word of a pytest test function's docstring. A requirement ID is any ID of `docs/` (`CTX-`, `SL-`, `PM-`, `DM-`, `SB-`, `DD-`, `DC-`, `AR-`, `BP-`). | CTX-W-05; CTX-Q-8 |
| BP-R-11 | **Migrations.** One Flyway folder per schema; file names as AR-P-05. A merged migration is never edited; a change is a new migration. The privilege test AR-Q-06 and the tenant test AR-Q-08 read every table from the database; the package that adds a table adds its entity class to the test's class table (DM Section 6.1). | AR-P-05, AR-P-06 |
| BP-R-12 | **Gate command.** `tools/check-all.sh` runs, in this order, and stops at the first failure (complete list): (0) only when `CLAUDE_CODE_REMOTE` is `true`, `tools/start-docker.sh`, which starts the Docker daemon when `docker info` fails (from P-03; P-02 report, document feedback 1), with the registry mirror `https://mirror.gcr.io` (from P-08; BP-O-04, CTX-D-037); (1) the setup version check (BP-R-31), only when `CLAUDE_CODE_REMOTE` is `true`; (1a) `deploy/images.sh --test`, when it exists; (2) `./mvnw -B verify` (Spotless check, all Java tests, the web application's `npm ci`, lint, Prettier check, type check, Vitest and build); (3) `ruff check` and `ruff format --check` on every Python file outside `docs/`, and the pytest tests (run with the Python of BP-R-01) of `tools/`, `demo/`, `dev/`, `deploy/` (from P-05; `deploy/local/` before), `spikes/` (from P-04) and `tests/acceptance/` that need no running installation, where the tests marked `lab` (they start the lab) run only when the branch changes the lab: a file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` is named by `git diff --name-only origin/main` or is an untracked file not ignored by git, or `origin/main` does not exist; otherwise step 3 also excludes the marker `lab` and prints that it did (CTX-D-036); (4) `shellcheck` on every `*.sh`; (5) `docs/31-demo-data/tools/check_data.py docs/31-demo-data` with the Python of BP-R-01 (DD-R-06); (6) `docker compose config --quiet` for each compose file that exists under `deploy/`; (7) `tools/trace.py --check` (BP-R-15). It needs Docker and nothing outside BP-E-01. A run longer than the foreground limit continues in the background (BP-E-01); Claude Code waits for its result. | CTX-Q-8 |
| BP-R-13 | **Public contract.** A package that adds `vigil-api` operations adds them to `app/src/main/resources/openapi/vigil-api.yaml` (OpenAPI 3.1, AR-I-08) in the same package. TypeScript types for `vigil-web` are generated from that file at build time; generated files are not committed. A contract test MUST fail when an operation's path, method, status codes or body differs from the file. | AR-I-08; CTX-G-2 |
| BP-R-14 | **Secrets in the repository.** No secret value is committed, including test values. Tests generate their secrets at start; a local installation generates its secrets outside the repository (BP-R-26). `ci.yml` runs `gitleaks` on the pull request's commits. | SL-B-14; AR-S-04 |
| BP-R-15 | **Traceability.** In a package spec, part 4 "Requirements built" is a table with one requirement ID per row (no ranges) and the columns: ID; part built (empty when the whole item is built); proof (`test` or `checked by review: {reason}`). `tools/trace.py` reads the part 4 tables of every spec under `docs/packages/` in the working tree (so the current package's spec counts from its first commit), and the IDs at the start of test names (BP-R-10) in `app/src/test`, `web/src`, `tests/`, `demo/`, `dev/`, `deploy/` (from P-05; `deploy/local/` before) and `spikes/` (from P-04). `--check` fails when a row with proof `test` has no test that names its ID. Without `--check` it prints, per document prefix, the IDs of the earlier documents that no test names and no spec of a package with a report on `main` marks "checked by review", for the checkpoint reviews. | CTX-Q-1; CTX-Q-8 |
| BP-R-16 | **Logging.** From P-02 on, application code logs through SLF4J only, in the JSON format of AR-S-11. No `System.out` in `app/src/main` or `demo/control/src/main`. | AR-S-11 |
| BP-R-17 | **Documents.** Claude Code MUST NOT change any file under `docs/` except, for its own package: the package report; values the spec names under "Recorded by this package" (Example: the measured values of DD-F-10). Every other disagreement between code and a document goes into the package report under "Document feedback" and is resolved in the Claude project through the decision log (CTX-Q-8). | CTX-D-009; CTX-Q-8 |

## 6. Package protocol

### 6.1 Life of a package (complete list of steps, in order)

| Step | Who | What |
| --- | --- | --- |
| BP-L-1 | Claude (Claude project) | Writes `docs/packages/P-##.md` from the template of Section 6.2, from the package entry of Section 7 and the documents it names. |
| BP-L-2 | A fresh Claude given only `docs/` | Runs the readiness test CTX-Q-5 on the spec. Claude fixes the spec, or the documents through the decision log, until the test finds no question. |
| BP-L-3 | Abdul | Creates the branch `p-##-{slug}` from `main`, commits the spec as its first commit, pushes, and starts a cloud session in the environment `vigil` on that branch with the prompt "Build package P-## as specified in docs/packages/P-##.md." |
| BP-L-4 | Claude Code | Reads `CLAUDE.md`, `docs/00-context.md` Sections 1 to 11, the spec and the sections the spec lists; builds and tests; runs the gate command until it passes; writes the package report; pushes; opens the pull request (BP-R-07). When the spec cannot be followed, it stops, writes the question under "Blocking questions" in the report and says so in the session instead of guessing (CTX-W-04). |
| BP-L-5 | Abdul (owner-run steps, only when the spec lists them) | Runs the scripts the package built, in the environment the spec names (BP-E-03 to BP-E-05), and commits their result files under `spikes/results/` or `tests/acceptance/results/` to the branch. A step that is a cloud session (P-01's "Verify P-01") writes its own result file under `tests/acceptance/results/` and commits it. Then a follow-up cloud session on the branch, with the prompt "Record the owner-run results of P-##", copies the values into the report and the files the spec names under "Recorded by this package". |
| BP-L-6 | GitHub Actions | Runs `ci.yml` (and `e2e.yml` when the package calls for it, Section 10.1) on the pull request. |
| BP-L-7 | Abdul | Reviews the pull request with the spec's review list. For packages that change what the audience sees, runs the local installation on his Mac (Section 11). Merges when the done-check (Section 6.3) holds. |
| BP-L-8 | Claude (Claude project) | Reads the package report; resolves "Document feedback" and "Blocking questions" through the decision log; carries open points into the next spec. At a checkpoint package, runs the checkpoint review of Section 8.3. |

### 6.2 Package spec template (complete list of parts, in order)

1. **Header**: ID, name, size class, phase, packages that MUST be merged before it starts, owner tasks it needs, owner-run steps (or "none").
2. **Goal**: one paragraph.
3. **Read first**: the exact document sections Claude Code reads.
4. **Requirements built**: the table of BP-R-15.
5. **Not in this package** (complete list): parts of the listed requirements left to a named later package.
6. **Files and code**: folders, packages and public interface types to create or change; migrations; API operations; seed import part.
7. **Tests** (complete list): each test with the requirement IDs it proves, its layer and its data.
8. **Done-check** (complete list): the common items of Section 6.3 plus the package's own.
9. **Recorded by this package**: values or files under `docs/` the package fills (BP-R-17), or "none".
10. **Review list for Abdul**: at most 10 points to look at in the pull request.
11. **Assumptions and spikes** the package depends on, with their state.

### 6.3 Common done-check (complete list; applies to every package)

| ID | Item |
| --- | --- |
| BP-DC-01 | Every row of the spec's part 4 is built and has its proof (BP-R-15). |
| BP-DC-02 | The gate command passes in a cloud session, and the `ci.yml` job `gate` passes on the pull request. |
| BP-DC-03 | Nothing outside the spec's scope is built (CTX-Q-1): no screen element, operation, field, setting or behaviour that no document requires. |
| BP-DC-04 | The module rules hold with everything the package adds: AR-Q-01 to AR-Q-03 and AR-Q-06 to AR-Q-09 pass; every new event listener is in the double-delivery test AR-Q-05; every new entry point that makes an external call is in AR-Q-04. |
| BP-DC-05 | Migrations, OpenAPI entries and seed import parts the package adds follow BP-R-11, BP-R-13 and AR-B-11. |
| BP-DC-06 | `README.md` and `CLAUDE.md` are current for the commands the package adds or changes. |
| BP-DC-07 | The package report exists with every part of Section 6.4, and every owner-run step of the spec has its result file. |

### 6.4 Package report template (complete list of parts)

What was built (with requirement IDs); tests added (with IDs); deviations from the spec, each with its reason; document feedback (CTX-Q-8); blocking questions; versions introduced (BP-R-08); values recorded under `docs/`; owner-run results; lines of production code added and changed (`tools/loc.sh`); gate command result; follow-up items for later packages.

### 6.5 Concurrency and order (complete list of rules)

| ID | Rule | Trace |
| --- | --- | --- |
| BP-R-18 | At most two packages MUST be in progress at the same time. Two packages run at the same time only when the folders they change do not overlap, except these shared files, which the second package to merge rebases (complete list): `README.md`, `CLAUDE.md`, `tools/cloud-setup.sh`, `tools/requirements-dev.txt`, `.github/workflows/ci.yml`, the OpenAPI file, `deploy/local/compose.yaml`, and the class tables of AR-Q-06 and AR-Q-08. | CTX-C-01 |
| BP-R-19 | A package MUST NOT start before every package it depends on (Section 7) is merged. | CTX-C-01 |
| BP-R-20 | A package that depends on a spike (Section 9) MUST NOT start before that spike has passed or the owner has decided what replaces it (AR §19.3). | AR §19.3 |
| BP-R-21 | A package MUST NOT add an item to list A or list B of `01-scope-lock`. A need for one goes into its report and to the change rule SL-CR-02. | SL-CR-01, SL-CR-02 |

## 7. Build packages (complete list)

Packages are listed in build order. "Depends on" lists packages that MUST be merged first (BP-R-19). Requirement lists are the content of the spec's part 4 (each spec lists the IDs one per row, BP-R-15). Every package also meets the common done-check (Section 6.3). A module package builds the module core (Section 3). The display reads a screen needs (DC-R-08, PM-R-10) are built in the owning module's code by the package that builds the API operation that uses them (P-25 for the primary demo; P-35 to P-39 for the deep dives), and are proved by that operation's tests. Each module that `50-architecture` Section 8.3 lists as reacting to AR-E-14 gets its listener in its own package: the listener reads the active tenants from `vigil-tenancy` and registers or cancels its own per-tenant scheduled work with the task scheduler that P-02 configures (AR-R-23, AR-R-28), and joins the double-delivery test AR-Q-05.

Cross-cutting items have no package of their own; every package whose code they touch follows them, and each spec lists the ones that apply in its part 4 (complete list): PM-R-01, PM-R-02, PM-R-03 (proved by AR-Q-01), PM-R-04 to PM-R-08, PM-R-11; DM-C-06, DM-C-11, DM-C-12; DM-V-01 to DM-V-12 (built with the entities that use them); DC-R-01 to DC-R-07; DC-TX-00. Items with nothing to build (complete list): withdrawn items (PM-M09-10, AR-S-10, AR-U-05); Later items (PM-K-11 to PM-K-14, AR-M-25 to AR-M-28, AR-L-01 to AR-L-03, DC-L-01 to DC-L-14); records of decisions, amendments and assumptions of earlier rounds (their content is built through the items they changed); module cut tags DC-M-01 to DC-M-43 (built through the modules' packages); the data files DD-F-00, DD-F-11 and DD-F-12 (the folder's README and its tools, run by the gate command). Every other data file DD-F-01 to DD-F-10 is imported by the seed part of the package that names it, or by the package whose module owns that part.

### 7.1 Phase A — Foundations and risk spikes

#### P-01 Repository, toolchain and CI skeleton

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; none; BP-OT-01 |
| Builds | Root aggregator POM and Maven wrapper; `app/` with Spring Boot 4.1 web, Spring Modulith core and the test libraries it uses (JUnit 5, AssertJ, ArchUnit, Spring Modulith test support); the 23 module packages of AR §7.1, each with `@ApplicationModule(allowedDependencies = …)` exactly as AR §7.1 lists and an empty `api` named interface; the `vigil.shared` OPEN module declaration; `web/` Vite React TypeScript skeleton built and checked by `frontend-maven-plugin` and served with the route fallback and cache headers of AR-W-01; `demo/control/` empty Spring Boot project; `tools/cloud-setup.sh`, `tools/requirements-dev.txt`, `tools/check-all.sh`, `tools/trace.py`, `tools/loc.sh`; Spotless, ESLint, Prettier, ruff configuration; `.github/workflows/ci.yml` (job `gate`); `.gitignore` entries for build outputs, `node_modules/` and local results; `README.md`. Libraries that later packages first use (persistence, SSH, mail, canvas, WireMock, Playwright) arrive with those packages (BP-R-08). |
| Requirements built | AR-T-01, AR-T-02, AR-T-03 (module verification part), AR-T-07, AR-T-08, AR-T-20 (JUnit, AssertJ, ArchUnit, Modulith test); AR-M-01 to AR-M-24 (module packages and allowed dependencies); AR-R-02 (folders), AR-R-03, AR-R-04, AR-R-05, AR-R-06 (declaration); AR-Q-01, AR-Q-03; SL-B-10 (separation test); AR-W-01 (static serving and fallback); BP-R-01, BP-R-05 to BP-R-10, BP-R-12, BP-R-14, BP-R-15, BP-R-31. |
| Tests | AR-Q-01; AR-Q-03; the BP-R-06 separation test; the AR-W-01 fallback and cache header test. |
| Done-check | (1) In a cloud session of the default environment, `sudo bash tools/cloud-setup.sh` exits 0 and the gate command then passes (the default environment keeps the 10-minute foreground limit, so the first Maven build runs in the background). (2) Owner-run step: the owner creates the environment `vigil` from the branch's `tools/cloud-setup.sh` (BP-OT-02) and starts a second session on the branch with the prompt "Verify P-01"; that session writes `tests/acceptance/results/P-01-verify.txt` (the output of `java -version`, the setup version line, the script's recorded run time, the gate command's result) and commits it. BP-ASM-01 holds when the recorded run time is at most 300 seconds. (3) `ci.yml` job `gate` passes. |
| Recorded by this package | Versions introduced; BP-ASM-01 result. |
| Runs in | BP-E-01, BP-E-02 |

#### P-02 Platform foundation

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-01; BP-OT-02 |
| Builds | `vigil.shared` complete (AR-R-06 list: identifiers as UUID version 7, `Key`, `Actor`, tenant identifier, clock, canonical JSON and SHA-256 hashing AR-P-03, error type, transaction guard AR-R-09, step protocol types AR-R-21); persistence (AR-T-04 to AR-T-06): Spring Data JDBC, a connection pool of 20 connections, Flyway per schema (AR-P-05), the `platform` schema with `event_publication` (AR-R-13); `deploy/db/init/` with the roles `vigil_owner` and `vigil_app` and the extension `vector` (AR-U-02); the `migrate` profile (`vigil-migrate`, AR-U-01); database passwords read from secret files at start (amendment BP-A-10); the Event Publication Registry and the 60 s resubmission (AR-R-13, AR-R-14); the advisory lock (AR-R-01); the readiness gate and the restart procedure framework with seven ordered step slots that later packages fill (AR-R-24); the task scheduler of AR-R-28 with the per-installation task of AR-R-23 (event resubmission); per-tenant tasks are registered with that scheduler by the modules that own them (Section 7 introduction); JSON logging (AR-S-11); Actuator health and liveness settings (AR-U-01); `app/Dockerfile` (copies the JAR, BP-R-04). Test support: a Testcontainers PostgreSQL with pgvector, the init script and both roles, used by every later `*IT` test. |
| Requirements built | AR-R-01, AR-R-06, AR-R-07, AR-R-09 (guard), AR-R-10, AR-R-13, AR-R-14 (resubmission), AR-R-15, AR-R-23 (per-installation task), AR-R-24 (framework and ready flag), AR-R-28, AR-P-01, AR-P-02, AR-P-03, AR-P-04, AR-P-05, AR-P-06, AR-T-03 (event registry), AR-T-04, AR-T-05, AR-T-06, AR-U-01, AR-U-02, AR-S-11, AR-TM-08; DM-C-01, DM-C-02, DM-C-04, DM-C-05, DM-C-08. |
| Tests | AR-Q-02, AR-Q-06, AR-Q-08 (data-driven over the database and the class table); AR-Q-05 helper (delivers an event twice); AR-Q-07 helper (log scanner used by P-27); a second process stops with the message of AR-R-01; 503 `starting` until step 7 of AR-R-24; RFC 8785 test vectors. |
| Done-check | `vigil-migrate` then `vigil-app` start against the pgvector container in an `*IT` test with the two roles and their passwords as secret files. |
| Runs in | BP-E-01, BP-E-02 |

#### P-03 Lab hosts and mocks

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-01; BP-OT-10 (Docker and Python parts, before the owner-run step) |
| Builds | `demo/lab/`: the `demo-lab` image on `ubuntu:24.04` (amendment BP-A-08) with Python 3, OpenSSH and the node exporter from Ubuntu's archive and Zabbix agent 2 copied from the official `zabbix/zabbix-agent2` 7.0 image (amendment BP-A-17), and the five host roles with every mock of DD-F-10 `mock_behaviour` (Python, standard library and `prometheus_client`); `lab-db-01` with the users `vigil-diag` and `vigil-action`, keys and host key from secret files, and its XFS `/data` image created and loop-mounted by the container itself in the size profile given by its environment (amendment BP-A-05, BP-R-30); the baseline content of DD-F-10 for each profile; the lab services of `deploy/local/compose.yaml` with the networks and addresses of AR-H-03; `deploy/local.sh` with `up --lab-only [--profile test|demo] [--project NAME]`, `down [--volumes] [--project NAME]` and the generation of the lab's secrets and values (BP-R-26); `deploy/images.sh --test` building `demo-lab` (BP-R-04); `tools/start-docker.sh` and gate step (0) (BP-R-12). |
| Requirements built | SL-A3-01; DD-F-10 (`lab_hosts`, `mock_behaviour`, `lab_db_server`); DD-ASM-02; AR-U-08, AR-X-01 (as amended by BP-A-17), AR-T-21 (lab part, as amended by BP-A-08); AR-H-03 (Docker networks), AR-H-09 (time zone); AR-S-04, AR-S-13 and AR-B-07 (the `lab-db-01` secrets and the derived host key fingerprint); BP-R-04 (`demo-lab` image), BP-R-12 (step (0)), BP-R-26, BP-R-30. |
| Tests | pytest: each mock's behaviour when writes fail and recover (DD-F-10 `mock_behaviour`); the five command templates of DD-F-04 give output that the parsers' rules of DD-F-04 `output_parsers` can read on `lab-db-01`, in the `test` profile before and after an injection like SL-F-1 (DD-ASM-02); the users can do exactly what DD-F-10 `permissions` allows. |
| Done-check | The lab tests pass in a cloud session with the `test` profile. Owner-run step: `deploy/local.sh up --lab-only --profile demo` on the Mac and the same command-template test in the `demo` profile (BP-ASM-06). |
| Recorded by this package | BP-ASM-02 (lab part), BP-ASM-06 (loop mount part) and BP-ASM-09 (P-03 part: the copied agent runs) results. |
| Runs in | BP-E-01, BP-E-02 (gate), BP-E-03 (owner-run) |

#### P-04 Lab monitoring, faults and storm timing (spike SB-T-01, development run)

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-03; none |
| Builds | `demo/monitoring/`: `lab-zabbix-provision` (AR-X-02: hosts, items, triggers with tags, the media type with the message of AR §12.3 and the base address `webhook_base_url` (amendment BP-A-04), the action, the user `vigil` and its API token written to the generated-secrets folder); Prometheus rules with the `component` label and `summary` annotations (DC-A-17); Alertmanager route, receiver, basic authentication and bearer token (AR §12.4); the Zabbix, Prometheus and Alertmanager units in `deploy/local/compose.yaml`; `demo/faults/`: `demo-faults` with the HTTP interface of AR-X-03 for SL-F-1, SL-F-2 and SL-F-3 and its state file; `dev/recorder/`, which stands in for `vigil-app` as the webhook receiver at `webhook_base_url` while Vigil is not running and writes each received message, with secrets removed (the sanitised messages of one storm are committed under `dev/fakes/recordings/monitoring/`); `spikes/storm-timing/`, which runs SB-T-01 against the recorder in its own container. `deploy/local.sh up --lab-only` starts the lab hosts, monitoring, `demo-faults` and the recorder, with the monitoring secrets of AR-S-13 (BP-R-26) and the lab value `webhook_base_url` (Section 11.4); the lab hosts start in dependency order (P-03 report, document feedback 5); `deploy/images.sh --test` builds the four new images; the gate tests `spikes/` (BP-R-12, BP-R-15). |
| Requirements built | SL-A3-02 (SL-F-1 to SL-F-3); SL-F-1, SL-F-2, SL-F-3; DD-F-10 (`faults` SL-F-1 to SL-F-3, `monitoring_configuration`, `timing_targets`); AR-U-07 (SL-F-1 to SL-F-3), AR-U-08 (start order), AR-U-09, AR-U-10; AR-X-02 (with the sending side of AR §12.3 and §12.4), AR-X-03 (without SL-F-4); AR-T-21 (faults part), AR-T-22; AR-S-04 and AR-S-13 (the monitoring and fault secrets of a local installation); BP-R-04 (the new images), BP-R-12 and BP-R-15 (`spikes/`), BP-R-26; SB-T-01 (development run), SB-T-05 (lab clear part). |
| Tests | In the gate: unit tests of `demo-faults`, the recorder and the spike's checks; lab tests that check the provisioned Zabbix, Prometheus and Alertmanager configuration, the connection tests of AR-K-01 and AR-K-02, the fault interface, and one storm with SL-F-1, SL-F-2 and SL-F-3 against the recorder (DD-K-01 expectations on the recorded calls: 126 storm entries, 31 Zabbix and 95 Alertmanager, and 1 SL-F-3 entry; 124 still firing after the action with SL-F-2; "Clear all faults" resolves every lab alert within 40 s, SB-T-05 lab part). Outside the gate: SB-T-01 development run, 10 storms against the recorder, each meeting every target of DD-F-10 `timing_targets` that does not need Vigil, followed by one storm with SL-F-2 and one storm cleared without the action. |
| Done-check | 10 of 10 storms pass in a cloud session (`test` profile); the run takes about 120 minutes (each storm waits for the no-refire window, and a new lab first waits 15 minutes until its baseline files are older than the `disk_growth` period), so the spike runs as a detached container that writes `spikes/results/storm-timing-{date}.json`, and Claude Code reads that file. Owner-run step: 3 storms on the Mac (`demo` profile). Sanitised recordings of one storm are committed under `dev/fakes/recordings/monitoring/`. |
| Recorded by this package | DD-F-10 `lab_db_server.development_values` from the Mac run (amendment BP-A-18; final values P-32); results of DD-ASM-03, DD-ASM-05, SL-ASM-01, DM-ASM-05, DM-ASM-08, AR-ASM-07, BP-ASM-09 (P-04 part), BP-ASM-10 and BP-ASM-11 in the report. |
| Runs in | BP-E-01, BP-E-02 (gate), BP-E-03 (owner-run) |

#### P-05 AWS edge, hosts and deployment scripts (spike AR-SP-01)

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-01; BP-OT-03, BP-OT-04 |
| Builds | `deploy/edge.sh` and `deploy/edge.md` (AR-H-03 to AR-H-06, AR-H-08 DNS records, AR-H-09; and the deployment bucket, the GitHub OIDC role, ECR repositories with immutable tags and the host bootstrap of amendment BP-A-06); `deploy/new-installation.sh`, `deploy/secrets.sh`, `deploy/units.yaml`, `deploy/deploy.sh`, `deploy/status.sh`, `deploy/gpu.sh`, `deploy/models.sh`, `deploy/models.lock` (Section 10.3); `deploy/main/compose.yaml` (with `vigil-embed`, AR-U-03) and `deploy/gpu/compose.yaml` with the networks, addresses, `gw_priority`, published ports and the `DOCKER-USER` rule of AR-S-08; `models.lock` with `embeddinggemma` and `gemma4:12b`; `release.yml` that builds and pushes the probe image; `spikes/sse-probe/`: a server at `vigil-app`'s address and port that serves the AR-SP-01 test page and stream. |
| Requirements built | AR-H-01 to AR-H-13 (infrastructure and scripts; the Vigil units follow in P-32); AR-G-31 (`deploy/gpu.sh`); AR-U-03, AR-U-04; AR-S-04 (file layout and `secrets.sh`), AR-S-08, AR-S-09, AR-S-13 (generation by `new-installation.sh`); AR-T-18, AR-T-19, AR-T-23; SL-A3-05 (edge part). |
| Tests | Owner-run: AR-SP-01 with the probe in place of `vigil-app`, from a browser in Pakistan (its check that a lab container cannot open a connection to `vigil-app` is run in P-32, when the lab exists on the main host); `vigil-embed` answers an embedding request from the main host; `deploy/status.sh` reports the started units (the readable secret directories of AR-ASM-13 are checked in P-32, the first deployment of units with secrets); `dig NS ${demo_domain}` shows the Route 53 name servers (AR-ASM-09); the edge resources as created by `deploy/edge.sh` (a result file). Gate: the scripts' testable parts with a fake AWS CLI and Docker, the compose files and the probe. `deploy/gpu.sh` and the GPU host's model are first run in P-06 (SB-T-04), after BP-OT-09. |
| Done-check | The scripts pass the gate (`shellcheck`, compose validation); owner-run AR-SP-01 passes. |
| Runs in | BP-E-01 (scripts), BP-E-03, BP-E-04 (owner-run) |

#### P-06 Spikes: Jira and AI providers

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-05; BP-OT-05, BP-OT-06, BP-OT-09, BP-OT-14 (Jira part) |
| Builds | `spikes/` scripts (Python 3.12, standard library and `requests`): SB-T-07 (create with label, search by label with the lag rule of AR-K-14, comment with the ADF of AR-K-15, resolve, reopen); structured-output calls to Claude (AR-G-20) and OpenAI (AR-G-21, AR-G-22); the first start of the GPU host with `deploy/gpu.sh` and the `gemma4:12b` digest in `deploy/models.lock` (`deploy/models.sh`, built by P-05); the Jira token expiry that `deploy/secrets.sh` prints (AR-H-07); on the main host against `vigil-embed`: SB-T-03 runtime part (the search texts of the 50 past incidents and the storm incident, built by DM-R-51 from `docs/31-demo-data`, embedded twice: same vectors, PI-1001 best with 1.00); on the GPU host: SB-T-04 local model part (5 SL-T-1 calls with a request built from the data files, each within 45 s, AR-G-32). Each spike stores the requests and answers it saw, with secrets removed, under `dev/fakes/recordings/`. |
| Requirements built | None in the product. Proves PM-ASM-03, DM-ASM-07, DM-ASM-09, SL-ASM-04 (runtime part), AR-ASM-06, AR-ASM-08, AR-ASM-10, AR-ASM-11, AR-ASM-12, AR-G-32 (measurement). |
| Tests | Owner-run: SB-T-07; SB-T-03 (runtime part); SB-T-04 (local model part); the Claude and OpenAI calls. |
| Done-check | Every listed spike passes, or the report records the failure with an owner decision item; every recording passes the secret scanner. |
| Runs in | BP-E-01 (scripts), BP-E-03, BP-E-04, BP-E-05 (owner-run) |

#### P-07 Spikes: Teams and email

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | S; P-05; BP-OT-07, BP-OT-08, BP-OT-14 (Teams and SMTP part) |
| Builds | `spikes/` scripts: SB-T-06 (a message with line breaks and a link in each of the 9 channels, payload of AR-K-18, at least 300 ms apart); AR-SP-02 (10 messages to each of 2 shared mailboxes through SES, lines and link intact). Recordings under `dev/fakes/recordings/`. |
| Requirements built | None in the product. Proves SL-ASM-02, AR-ASM-01, AR-ASM-02, AR-ASM-04. |
| Tests | Owner-run: SB-T-06; AR-SP-02. |
| Done-check | As P-06. |
| Runs in | BP-E-01 (scripts), BP-E-03 (owner-run) |

### 7.2 Phase B — Product core

#### P-08 Audit, tenancy and identity

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-02; none |
| Builds | `vigil-audit` (DM-E-08: append, query, sequence per tenant); `vigil-tenancy` (DM-E-01, DM-E-02, DM-E-83, the active tenants, AR-E-13, AR-E-14, public base address for links); `vigil-identity` (DM-E-03 to DM-E-07, Argon2id, sessions (the cookie with the `Secure` attribute of amendment BP-A-02 is P-09), session expiry sweep, reaction to AR-E-14, restart step (6)); seed import operations of parts 1 and 2 (AR-B-11, AR-S-06) with module tests (their registration in the import registry is P-11); the registry mirror of `tools/start-docker.sh` (BP-R-12 step (0), CTX-D-037). |
| Requirements built | PM-M01-01 to PM-M01-04, PM-M02-01 to PM-M02-04, PM-M03-01 to PM-M03-03, PM-R-11; DD-F-01 (import); DM-E-01 to DM-E-08, DM-E-83 and the rules DM-E01-R1 (login part), DM-E02-R1, DM-E05-R1, DM-E06-R1 to DM-E06-R3, DM-E08-R1, DM-E08-R2, DM-E83-R1; DM-SM-01, DM-SM-02; DM-C-03, DM-C-07, DM-C-10; DM-R-70 (identity part); AR-R-11, AR-R-19 (tenancy side), AR-R-23 and AR-R-24 (session sweep, step (6)), AR-E-13, AR-E-14; AR-B-11 (parts 1 and 2); AR-S-01, AR-S-02, AR-S-06, AR-TM-03; AR-Q-05 (the new listener); SL-B-01, SL-B-06, SL-B-13 (identity side), SL-A3-07 (data); BP-R-12 (step (0) mirror). |
| Tests | DM-E06-R1 to DM-E06-R3; DM-E02-R1; DM-E83-R1; session lifetime and idle limit (AR-S-02); Argon2 parameters (AR-S-01); AR-E-14 delivered twice (AR-Q-05); audit events cannot be changed through the runtime role (AR-Q-06). |
| Runs in | BP-E-01, BP-E-02 |

#### P-09 Public interface foundation

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-08; none |
| Builds | `vigil-api` foundation: tenant of a request from the host name (AR-I-02 with amendment BP-A-03), error format (AR-I-03 with amendment BP-A-20, including the code `internal_error` in `VigilError`), the session cookie (AR-S-02 with BP-A-02), authentication filter, CSRF (AR-S-03 with BP-A-02), permission check (AR-I-07), the live update stream with its topics registry, heartbeats, 300 s close and the 1 s delivery path (AR-I-04, AR-T-10, AR-TM-04), operations AR-I-10 to AR-I-16 with AR-B-10 (unknown host); the OpenAPI file and the contract test (BP-R-13). Spring Security (AR-T-11). |
| Requirements built | PM-M23-01 to PM-M23-05 (foundation), PM-R-09; AR-I-01 to AR-I-04, AR-I-06 to AR-I-08, AR-I-10 to AR-I-16; AR-B-10 (server side); AR-R-33; AR-S-02 (cookie), AR-S-03; AR-T-10, AR-T-11, AR-TM-04; SL-B-01 (sessions), SL-B-13; DC-U-05 (server side); BP-R-13 (file and contract test); BP-A-20 and AR-R-06 (the code `internal_error`); AR-S-11 (no secret in logs). |
| Tests | Host resolution with and without port, unknown host, installation paths on any host (AR-I-02); each error code this package produces (AR-I-03); CSRF accepted and refused; 403 on a missing permission (through a permission-checked operation registered only in that test); live stream heartbeat, close and reconnection with shortened timings; a session of another tenant refused; the contract test (BP-R-13). |
| Runs in | BP-E-01, BP-E-02 |

#### P-10 Connectors, secrets and monitoring connectors

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-09, P-04; none |
| Builds | `vigil-connectors`: connector kinds (DM-E-09: the ten kinds of AR §12.2 registered by name; the implementations of the other kinds arrive in P-18 to P-22), instances and credential references (DM-E-10, DM-E-11), the `Secrets` interface with `verifyInstallationToken` (AR-S-04 with amendment BP-A-10), the transaction guard on every external call (AR-R-09); `zabbix` and `alertmanager` connectors: `parse` and `test` (AR-K-01, AR-K-02, AR §12.3, §12.4); seed import operation of part 3. |
| Requirements built | PM-M04-01 to PM-M04-04; PM-K-01, PM-K-02; DM-E-09 to DM-E-11; AR-K-00a, AR-K-01, AR-K-02; AR §12.1, §12.3, §12.4 (receiving side); AR-R-08, AR-R-09; AR-S-04, AR-S-05; AR-T-13, AR-T-15; AR-TM-01 (monitoring tests); SL-B-07, SL-B-14 (secrets). |
| Tests | AR-Q-04; AR-Q-09, extended to `verifyInstallationToken` (caller `vigil-api` only, BP-A-10); parsing of the messages P-04 recorded in `dev/fakes/recordings/monitoring/`, copied into test resources (exact payload text, keys, statuses, identity pairs, mapping fields); connection tests against WireMock (pass, HTTP error, connection error); constant-time verify. |
| Runs in | BP-E-01, BP-E-02 |

#### P-11 Seed import and installation operations

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-10; none |
| Builds | `vigil-setup` seed import: ZIP reading and entry rules (AR-B-05), placeholder refusal (AR-B-07), the part registry in DM-R-56 order that each module's import operation joins (parts 1 to 3 join here), the interim rule BP-R-29, the seed import record (DM-E-82), the cross-module key check registry (DM-R-60), the one audit event (DM-E08-R3), the import worker (AR-R-31), restart step 2 (AR-R-24); the installation token (AR-S-07); operations AR-I-95 to AR-I-98; a test helper that zips `docs/31-demo-data/` with test placeholder values. |
| Requirements built | PM-M22-03, PM-M22-04, PM-M22-05, PM-M23-06; DM-SM-16 (seed import record); DM-E-82, DM-E82-R1; DM-R-56, DM-R-57, DM-R-60 (framework); AR-B-02, AR-B-05, AR-B-07, AR-B-11, AR-I-95 to AR-I-98, AR-R-31, AR-S-07; SL-A3-03 (Vigil side). |
| Tests | AR-Q-10 for the registered parts (extended by each later package that registers a part); refusal of a bundle with placeholders, unknown entries and unknown sections of registered parts; a failed part fails the tenant (DM-E82-R1); installation token required on any host; alias set only to a `ready` tenant. |
| Runs in | BP-E-01, BP-E-02 |

#### P-12 Service map

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | S; P-11; none |
| Builds | `vigil-servicemap`: DM-E-24 to DM-E-28; matching (DM-R-05); relation, dependents, impact and target questions (DM-E26-R1, DM-E28-R2); file import with the format of AR-B-06 and the rules and messages of DM-R-55 and DC-TX-11; AR-E-04; seed import part 5. |
| Requirements built | PM-M07-01 to PM-M07-06; DM-E-24 to DM-E-28, DM-E24-R1, DM-E26-R1, DM-E27-R1, DM-E28-R1, DM-E28-R2; DM-R-04 to DM-R-07, DM-R-54, DM-R-55; AR-B-06; AR-E-04; DC-TX-11 (service map messages). |
| Tests | DD-K-03 through the import; every DC-TX-11 service map message; YAML anchors, aliases and tags refused; matching by identity pair order. |
| Runs in | BP-E-01, BP-E-02 |

#### P-13 Intake

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-12; none |
| Builds | `vigil-intake`: DM-E-15 to DM-E-23; operation AR-I-90 with the order of AR-R-16 and the counter row; normalisation and mapping (DM-E17-R1, DM-E21-R1); events AR-E-01 to AR-E-03; source health (DM-R-39 to DM-R-41) with scheduled connection tests and the 1 s evaluation for active tenants (AR-R-23), registered by its AR-E-14 listener (Section 7 introduction); on-request connection test (PM-M06-07); seed import part 6. |
| Requirements built | PM-M06-01 to PM-M06-07; DM-E-15 to DM-E-23 with their rules; DM-R-01 to DM-R-03, DM-R-39 to DM-R-41; DM-SM-03, DM-SM-04; AR-R-16, AR-R-25, AR-E-01 to AR-E-03, AR-I-90, AR-TM-02; SL-A1-01 (Vigil side), SL-B-02. |
| Tests | Concurrent webhook calls commit alerts in `sequence` order without gaps; wrong or missing token stores nothing; repeat firing, resolve and orphan resolve; health transitions with a fixed clock; events delivered twice; body limit 5 MiB. |
| Runs in | BP-E-01, BP-E-02 |

#### P-14 Incidents

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-13; none |
| Builds | `vigil-incidents`: DM-E-32 to DM-E-40; lifecycle DM-SM-05 with transition records; title (DM-R-20); memberships and merge (DM-R-12); acknowledgement with the atomic check (DM-E35-R1, AR-R-12); escalation records, notified teams, evidence entries, AI status fields, timeline texts (DC-TX-04); closure (DM-R-23) as scheduled work; events AR-E-05 to AR-E-08; seed import part 9. |
| Requirements built | PM-M09-01 to PM-M09-09, PM-M09-11, PM-M09-12; DM-SM-16 (incident alert membership); DM-E-32 to DM-E-40 with their rules; DM-R-12 (incident side), DM-R-20, DM-R-23, DM-R-63 (acknowledgement); DM-SM-05; AR-R-12, AR-E-05 to AR-E-08; DC-TX-01 (incident number and states), DC-TX-04; SL-B-12. |
| Tests | Every transition of DM-SM-05 and the forbidden ones; fields set only by their module (DM-E32-R1); two simultaneous acknowledgements, one succeeds; title cases of DM-R-20; closure after the reopen window; timeline texts. |
| Runs in | BP-E-01, BP-E-02 |

#### P-15 Routing

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | S; P-14; none |
| Builds | `vigil-routing`: DM-E-41 to DM-E-43; fields and operators of DM-R-25 and DM-R-26 (routing fields); evaluation (DM-E43-R1, DM-E41-R2), applied only while forming (DM-R-17); dry run (DM-R-28, request only); rule set validation (DM-E41-R1) and change summary (DC-TX-10); escalation policy; seed import part 11. |
| Requirements built | PM-M10-01 (storage and validation), PM-M10-02 to PM-M10-04; DD-F-05 (import); DM-E-41 to DM-E-43 with their rules; DM-R-17, DM-R-25, DM-R-26, DM-R-27, DM-R-28; SL-A1-04 (assignment), SL-B-09. |
| Tests | DD-K-05 expectations; same inputs give the same result; frozen assignment not applied; each refusal of DM-E41-R1; each operator of DM-R-26 on its value type. |
| Runs in | BP-E-01, BP-E-02 |

#### P-16 Correlation

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-15; none |
| Builds | `vigil-correlation`: DM-E-29 to DM-E-31; the per-tenant worker with cursor and retries (AR-R-17, AR-R-18, AR-R-26); decisions, merge, stability, hypothesis, impact, reopen and final reasons; the `correlation` health indicator (AR-H-12); restart step 5; seed import part 10. |
| Requirements built | PM-M08-01 to PM-M08-10; DM-E-29 to DM-E-31; DM-R-08 to DM-R-19, DM-R-21, DM-R-58, DM-R-61, DM-R-62; AR-R-17, AR-R-18, AR-R-26; SL-A1-02 (Vigil side). |
| Tests | The 126 storm alerts and the SL-F-3 alert of the lab catalogue (DD-F-03, DD-F-02) fed in 20 random orders: always one storm incident with hypothesis `srv-db-01`, the impact of DD-K-06 and a separate SL-F-3 incident; merge of forming incidents; reopen inside and after the window; worker retry and health `DOWN`; one decision per alert on duplicate delivery. |
| Runs in | BP-E-01, BP-E-02 |

#### P-17 Engine and workflow runs

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-16; none |
| Builds | `vigil-engine`: DM-E-50 to DM-E-52; run executors, hand-offs, durable wakes, retries and cancellation (AR-R-20 to AR-R-22, AR-R-27); timer poll (AR-R-23); restart step 4; Trigger, Condition (DM-R-25, DM-R-26 condition fields) and Wait/timer steps; the Action step guard (DM-R-31) and the start of reconciliation (PM-M12-10) through requests that P-24 implements; events AR-E-10. `vigil-workflows`: DM-E-44, DM-E-45, DM-E-47 (versions from seed); step type catalogue; starting live runs on AR-E-05 and AR-E-07 including reopen; seed import part 16. Other modules' step handlers are tested here with test handlers. |
| Requirements built | PM-M11-01, PM-M11-02, PM-M11-07; PM-M12-01 to PM-M12-08, PM-M12-10, PM-M12-11; PM-S-01, PM-S-02, PM-S-11 to PM-S-14; DM-E-44, DM-E-45, DM-E-47, DM-E-50 to DM-E-52 with their rules; DM-S-01, DM-S-02, DM-S-11, DM-S-12; DM-R-16, DM-R-22, DM-R-25, DM-R-26, DM-R-31, DM-R-64 (engine side), DM-R-66, DM-R-67, DM-R-70; DM-SM-07 to DM-SM-09; AR-R-20 to AR-R-22, AR-R-27, AR-E-10, AR-TM-05; SL-B-03 (execution), SL-B-04 (engine guard), SL-B-11 (engine side). |
| Tests | AR-Q-11 cases (a) to (d) and (f); workflow version 1 of DD-F-06 on the P1 path and the otherwise path with test handlers; no step after the Trigger while the incident is forming; one live run per incident and episode; restart in every state of AR-R-24 step 4. |
| Runs in | BP-E-01, BP-E-02 |

#### P-18 Runner and diagnostics

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-17, P-03; none |
| Builds | `vigil-runner`: DM-E-12 to DM-E-14; the `linux_server` target connector over SSH with host key pinning, quoting and the five parsers (AR-K-03, AR-K-11 to AR-K-13, AR-T-12); request IDs (DM-E14-R1); restart step 1; seed import part 4. `vigil-diagnostics`: DM-E-53, DM-E-54; the Diagnostic step (DM-S-03) with evidence and summaries (DC-TX-03); seed import part 12. |
| Requirements built | PM-M05-01 to PM-M05-04, PM-M13-01 to PM-M13-03; PM-K-03; PM-S-03; DM-E-12 to DM-E-14, DM-E-53, DM-E-54 with their rules; DM-SM-10; DM-S-03; AR-K-00b, AR-K-03, AR-K-11 to AR-K-13, AR-T-12; SL-A1-03, SL-B-05. |
| Tests | Against the `demo-lab` image in the `test` profile (BP-R-06): each operation of DD-F-04; parameter refusals (DM-V-06 path prefix, ranges); action refused without an action grant; wrong host key refused; timeout outcomes; same request ID returns the earlier run; after an injection like SL-F-1 the three diagnostics give summaries in the forms of DD-F-04 (exact `demo` values are checked in P-27's Mac run and P-32). |
| Runs in | BP-E-01, BP-E-02 |

#### P-19 AI gateway and AI connectors

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-18, P-06; none |
| Builds | `vigil-ai-gateway`: DM-E-70 to DM-E-74; provider choice, data routing and fallback for SL-T-1 and SL-T-2 (DM-R-45, DM-R-69, AR-G-13); the single provider of SL-T-3 (DM-E72-R2); usage records and cost (AR-G-14); AR-E-11; seed import part 7. Connectors `claude`, `openai`, `local_model`, `local_embedding` (AR-K-07 to AR-K-10, AR-G-20 to AR-G-24, AR-T-16, AR-T-17) with the timeouts of AR-TM-01, tested on P-06's recordings. |
| Requirements built | PM-M18-01 to PM-M18-05; PM-K-07 to PM-K-10; DM-E-70 to DM-E-74 with their rules; DM-R-42, DM-R-44, DM-R-45, DM-R-69; AR-K-00e, AR-K-07 to AR-K-10; AR-G-01 to AR-G-05, AR-G-13, AR-G-14, AR-G-20 to AR-G-24; AR-T-16, AR-T-17; SL-B-08; SL-A2-05 (gateway side). |
| Tests | Fallback on unreachable and provider error; no fallback on a 2xx refusal or truncation; data routing skips with `refused_by_data_routing`; units and cost from the recorded answers; SL-T-3 uses only the named provider. |
| Runs in | BP-E-01, BP-E-02 |

#### P-20 Knowledge and investigation

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-19; none |
| Builds | `vigil-knowledge`: DM-E-63 to DM-E-67; runbook applicability (DM-R-50); past incidents with times from offsets (AR-B-08); embeddings at import with its usage record (AR-B-09, AR-G-30); similarity search (DM-R-51, AR-P-07, DM-E66-R1); re-embedding (DM-SM-15, AR-R-30, AR-TM-07); seed import part 8. `vigil-investigation`: DM-E-68, DM-E-69; the AI task step for SL-T-1, SL-T-2 and SL-T-3 (DM-S-04); request parts and categories (AR-G-10, DM-R-43); prompts (AR-G-12); output checks (PM-M17-03, AR-G-11, DM-E68-R3); template summary (DM-E69-R1, DM-E69-R2); evidence and AI status; the re-run request with its worker (AR-R-29; its HTTP operation AR-I-26 is P-39). |
| Requirements built | PM-M16-01 to PM-M16-05, PM-M17-01 to PM-M17-07; PM-S-04; DD-F-08, DD-F-09 (import); DM-E-63 to DM-E-69 with their rules; DM-R-43, DM-R-50 to DM-R-52; DM-SM-15; DM-S-04; AR-B-08, AR-B-09, AR-G-10 to AR-G-12, AR-G-30, AR-G-33, AR-P-07, AR-R-29, AR-R-30, AR-TM-07; SL-T-1, SL-T-2, SL-T-3; SL-A1-04 (AI), SL-A1-06 (search), SL-A2-07 (request). |
| Tests | DD-K-08 and DD-K-09 through the product with a test embedding that gives equal vectors for equal texts; every AI rejection reason of DC-TX-11; template summary pattern; re-run allowed states (DM-E68-R2); searches refused during re-embedding. |
| Runs in | BP-E-01, BP-E-02 |

#### P-21 Ticketing and the Jira connector

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-20, P-06; none |
| Builds | `jira_cloud` connector (AR-K-04, AR-K-14 to AR-K-16); `vigil-ticketing`: DM-E-75, DM-E-76; the Ticket step (DM-S-10) for create, reopen and resolve with DC-OUT-01, DC-OUT-03, DC-OUT-04; escalation records for P-22 (DC-OUT-02); duplicate protection after a restart (DM-E76-R2); incident link (AR-I-100, amendment BP-A-09). |
| Requirements built | PM-M19-01 to PM-M19-04; PM-K-04; PM-S-10; DM-E-75, DM-E-76 with their rules; DM-SM-14; DM-S-10; AR-K-00c, AR-K-04, AR-K-14 to AR-K-16, AR-I-100; DC-OUT-00 to DC-OUT-04; DM-E02-R2 (Jira); SL-A1-05 (ticket), SL-A1-07 (ticket resolved), SL-DR-03 (Jira). |
| Tests | WireMock on P-06's recordings: create with label and the seven description parts in order; label search repeated up to 3 times; resolve and reopen by status name; a `pending` record after a restart creates no second issue; environment label prefix. |
| Runs in | BP-E-01, BP-E-02 |

#### P-22 Notifications, Teams and email connectors, Escalation step

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-21, P-07; none |
| Builds | `ms_teams` connector (AR-K-05, AR-K-17, AR-K-18) and the installation-wide sender (AR-K-19, AR-R-32); `email` connector (AR-K-06, AR-K-20, AR-K-21, AR-T-14); `vigil-notifications`: DM-E-77, DM-E-78; the Notification step (DM-S-09) with recipients, de-duplication (DM-R-65), notified teams and DC-OUT-05 to DC-OUT-07 with the links of AR-I-101 and AR-I-102; seed import part 15. `vigil-routing`'s Escalation step (DM-S-08) in both modes. |
| Requirements built | PM-M20-01 to PM-M20-05, PM-M10-05; PM-K-05, PM-K-06; PM-S-08, PM-S-09; DM-E-77, DM-E-78 with their rules; DM-S-08, DM-S-09; DM-R-65; AR-K-00d, AR-K-05, AR-K-06, AR-K-17 to AR-K-21, AR-I-101, AR-I-102, AR-R-32, AR-T-14; DC-OUT-05 to DC-OUT-07; DM-E02-R2 (messages); SL-A1-05 (messages), SL-A2-03, SL-DR-03 (messages). |
| Tests | Teams posts at least 300 ms apart; Adaptive Card lines and link text from P-07's recordings; email subject and body; no second send for a `sent` record; escalation through two levels with timer 120 s and a fixed clock; an acknowledgement made before the step starts ends it at once. |
| Runs in | BP-E-01, BP-E-02 |

#### P-23 Recovery and dashboard metrics

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-22; none |
| Builds | `vigil-recovery`: DM-E-61, DM-E-62; the Recovery check step (DM-S-07, DM-R-34 to DM-R-38) with the source health guard, window, DC-TX-03 condition texts and cancellation; the resolve request (PM-M09-06); seed import part 13. `vigil-reporting`: DM-E-79, DM-E-80; metrics (DM-R-53); seed import part 17. |
| Requirements built | PM-M15-01 to PM-M15-04, PM-M21-01, PM-M21-02; PM-S-07; DM-E-61, DM-E-62, DM-E-79, DM-E-80 with their rules; DM-S-07; DM-R-34 to DM-R-38, DM-R-52, DM-R-53; DM-SM-13; DC-TX-03 (recovery texts); SL-A1-07, SL-A1-09 (computation). |
| Tests | Recovered only after the window with every source healthy; window reset when a condition stops holding or a source turns stale; not recovered at `max_wait_until`; DD-K-11 seeded values (5.7 min, 41.1 min, 525, 46) through the product. |
| Runs in | BP-E-01, BP-E-02 |

#### P-24 Actions and approvals

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-23; none |
| Builds | `vigil-actions`: DM-E-55 to DM-E-60; proposal (DM-R-29); the Approval step (DM-S-05) with the order of checks, policy outcomes, snapshot and hash (DM-R-30, DM-V-12, AR-P-03), requests to every eligible approver, expiry and cancellation; decisions with the atomic check (DM-E59-R1); the Action step (DM-S-06) with rechecks, runner call and reconciliation including after a restart (DM-R-32, DM-R-33, AR-TM-06, PM-M12-10); evidence (DC-TX-03); AR-E-09; the requests DM-A-15 lists; seed import part 14. |
| Requirements built | PM-M14-01 to PM-M14-09; PM-S-05, PM-S-06; DM-E-55 to DM-E-60 with their rules; DM-S-05, DM-S-06; DM-R-29 to DM-R-33, DM-R-63 (approval); DM-SM-11, DM-SM-12; DM-V-12; AR-E-09, AR-TM-06; SL-A1-06, SL-A2-04 (attempt limit), SL-B-04. |
| Tests | Policy outcomes `preauthorized` and `blocked` (SL-B-04 automated tests); a second approver must differ; expired, rejected, refused paths; each recheck failing on its own; unknown result reconciled, `unresolved` after 3 attempts; AR-Q-11 case (e); the snapshot hash changes when any part changes. |
| Runs in | BP-E-01, BP-E-02 |

#### P-25 Primary-demo API operations

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-24; none |
| Builds | The API layer of the primary demo's screens: AR-I-20 to AR-I-25, AR-I-27 to AR-I-35, AR-I-80; the live update producers for the topics `incidents`, `incident`, `run`, `alerts`, `sources`, `approval` and `tenant` (AR-I-04); the data the polled screens read (AR-I-05); OpenAPI entries and contract tests. |
| Requirements built | AR-I-05, AR-I-20 to AR-I-25, AR-I-27 to AR-I-35, AR-I-80; AR-I-04 (topics); PM-M23-04; DC-R-08; PM-R-10. |
| Tests | Contract tests; each operation's permission and refusals (AR-I-32 and AR-I-34 return the refusal that applies); every topic's notice within 1 s of the commit in an `*IT` test. |
| Runs in | BP-E-01, BP-E-02 |

#### P-26 Local installation and dev fakes

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-25, P-04; BP-OT-10 (Mac run) |
| Builds | `deploy/local/compose.yaml` complete (Section 11); `deploy/local.sh` complete (`up`, `down`, `reset`, `logs`, `e2e`; secrets and values; tenant creation, bundle import and alias through the installation operations); `deploy/local/bundle.py` (builds the seed bundle with the values merge of AR-B-07); `dev/fakes/` (Section 11.3); the Playwright runner container in a compose profile `e2e` and `deploy/local.sh e2e [suite]`, which runs `npm ci` for `tests/e2e/` on the host, stops `claude-fake` and `openai-fake` before suites tagged `@ai-down` and starts them again after; `e2e.yml` (Section 10.1). |
| Requirements built | BP-R-24 to BP-R-28, BP-R-30; Section 11; AR-Q-10 (complete: all 17 parts registered, the interim rule BP-R-29 ends). |
| Tests | AR-Q-10 with the bundle of `docs/31-demo-data/` built by `bundle.py`; `deploy/local.sh up` then a Playwright smoke test (login page loads, banner text) in a cloud session and in `e2e.yml`; the dev fakes' own pytest tests. |
| Done-check | As above in BP-E-01 and BP-E-02. Owner-run step: `deploy/local.sh up --profile demo` on the Mac (BP-ASM-04, BP-ASM-05). |
| Runs in | BP-E-01, BP-E-02, BP-E-03 (owner-run) |

#### P-27 Headless storm acceptance

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-26; none |
| Builds | `tests/acceptance/storm/`: the storm driver (Python 3.12, standard library, image as BP-R-04), run as a container in the compose profile `acceptance` on `lab-net`. It, through `vigil-api` and `demo-faults` only: resets (new tenant, import, alias, lab clear), injects SL-F-1 and SL-F-3, logs in as `noc-l1` and `dbops-engineer`, acknowledges and approves using links built from the identifiers it reads (AR-I-21, AR-I-27, AR-§10.3 forms) and checks them against the links in the dev fakes' recorded messages; checks Teams and email delivery through the dev fakes' `/_fakes/calls` and Mailpit's API (in a local installation) or, against the demo installation, writes a checklist of the expected messages per channel and mailbox that the owner ticks; and checks the SB-R-06 results and the values of SB-S01 to SB-S07 that need no screen. Mode `immediate` acknowledges and approves as soon as possible and checks times by the formulas of DM-R-53; mode `script` follows the script clock (SB-TL-06, SB-TL-08) and checks the exact values; `e2e.yml` uses `immediate`. |
| Requirements built | SL-A3-04 (headless); AR-Q-07; SB-T-02 (driver, amendment BP-A-11). |
| Tests | The driver in mode `immediate` once in a cloud session and in `e2e.yml`; AR-Q-07 on the log of that run. Owner-run step: the driver in mode `script` on the Mac with the `demo` profile and the dev fakes, checking the `demo` diagnostic values of DD-F-04. |
| Runs in | BP-E-01, BP-E-02, BP-E-03 (owner-run) |

### 7.3 Phase C — Screens and demo operations, to SL-CP-1

#### P-28 Web foundation, login and incident list

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-27; none |
| Builds | `vigil-web` shell: TypeScript types generated at build time from the OpenAPI file (BP-R-13); routes (AR-W-01), session handling and return to the linked page (DC-U-13), banner, header and navigation (DC-U-01 to DC-U-05; navigation per BP-R-22), kind tag component (DC-U-10), screen states (DC-U-09), time and duration formats (DC-U-07, AR-W-06), long lists (DC-U-12), tab titles (DC-U-15), live updates and polling client (AR-W-02); DC-S01 (DC-S01-01 to DC-S01-05) with the unknown-host state (AR-B-10); DC-S02 (DC-S02-01 to DC-S02-06); client polling intervals (AR-TM-09). |
| Requirements built | PM-M24-01 (these screens), PM-M24-02, PM-M24-05; SL-A4-01, SL-A4-02; DC-U-01 to DC-U-10, DC-U-12, DC-U-13, DC-U-15; DC-S01-01 to DC-S01-05, DC-S02-01 to DC-S02-06; AR-B-10; AR-W-01, AR-W-02, AR-W-06; AR-TM-09; SL-A3-06, SL-DR-03 (screens). |
| Tests | Vitest for formats, states and tags; Playwright on the local installation: login, wrong password, no-access page, unknown host, empty list, the strip and rows during a storm driver run (SB-S00 to SB-S02 list parts). |
| Runs in | BP-E-01, BP-E-02, BP-E-03 |

#### P-29 Incident workspace and run canvas

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-28; none |
| Builds | DC-S03 complete except the "Re-run AI investigation" control and the usage link of DC-S03-09 (P-39); the read-only canvas with one node type per step type, card content of DC-S06-05, styles of DC-S03-07, follow mode, zoom, Fit and Expand (AR-W-03, AR-T-09). |
| Requirements built | SL-A4-03 (without the re-run control); SL-WE-10; DC-S03-01 to DC-S03-14 (DC-S03-09 without the control and link); DC-S06-05 (card content); DC-TX-02, DC-TX-03 (display); AR-W-03 (read-only), AR-T-09 (React Flow); SL-A1-02, SL-A1-03, SL-A1-04, SL-A1-06, SL-A1-07, SL-A1-08 (screens). |
| Tests | Playwright during a storm driver run: the values of SB-S02 to SB-S07 that DC-S03 shows (exact in the `demo` profile owner-run, by form in `test`); the highlight moves within 2 s of each step change, measured from the commit time the server logs for `StepChanged`. |
| Runs in | BP-E-01, BP-E-02, BP-E-03 |

#### P-30 Mobile Acknowledge and Approval pages

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | S; P-29; none |
| Builds | DC-S04 and DC-S05 with the one-column layout (AR-W-07), refusals, countdown, polling and live state; the confirmation dialog component (DC-U-11) used for Approve and Reject. |
| Requirements built | SL-A4-04, SL-A4-05; PM-M24-03; DC-S04-01 to DC-S04-06, DC-S05-01 to DC-S05-07; DC-U-11 (Approve, Reject); AR-W-07; SL-B-13 (pages). |
| Tests | Playwright at 360 px: acknowledge as `noc-l1`; every refusal of DC-S04-05 and DC-S05-06; approve with confirmation; a changed snapshot refused. |
| Runs in | BP-E-01, BP-E-02 |

#### P-31 Demo control panel and SL-F-4

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-27; none |
| Builds | `demo-control` (AR-U-06, AR-X-04): login (AR-S-12 with BP-A-02), DC-S12 complete with its confirmation (DC-U-11, Reset), guards, fault state file, the bundle builder with the values merge of AR-B-07, the reset protocol of AR-B-03 with both branches, limits and failure cases; `demo-faults` SL-F-4 (AR-X-03: `DOCKER-USER` rules, `conntrack`, re-resolve every 30 s, state file). |
| Requirements built | SL-A3-02 (SL-F-4), SL-A3-03, SL-A4-12; SL-F-4; DC-S12-01 to DC-S12-07; DC-U-11 (Reset); AR-B-01 to AR-B-04 (control side), AR-S-12, AR-T-21 (control panel part), AR-U-06, AR-U-07 (SL-F-4), AR-X-03 (SL-F-4), AR-X-04, AR-X-05, AR-TM-10; DM-R-24 (d); DD-F-10 `faults` SL-F-4, `control_panel`. |
| Tests | Reset on the local installation within 60 s, five times in a row, including after SL-F-1 with SL-F-2 (SB-T-05, local run); the bundle `demo-control` builds equals the one `bundle.py` builds for the same values; guards refuse storm buttons while a storm fault is active; a failed import leaves the alias unchanged. SL-F-4 is tested on the main host in P-32 (Section 11.1). |
| Runs in | BP-E-01, BP-E-02 |

#### P-32 Demo installation and SL-CP-1 acceptance

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-05, P-30, P-31; BP-OT-03 to BP-OT-09, BP-OT-11, BP-OT-14 |
| Builds | `deploy/main/compose.yaml` and `deploy/gpu/compose.yaml` with every unit of AR §6; `release.yml` with every image; `deploy/values/demo.yaml`; the first deployment of the demo installation; Playwright and driver configuration for the demo host. |
| Requirements built | SL-A3-05; AR-H-01 to AR-H-13 (with the Vigil units); AR §6 including AR-U-03 and AR-U-04; SL-CP-1. Proves AR-ASM-03 (AR-SP-01) and AR-ASM-05 (AR-SP-03). |
| Tests | Owner-run on the main host: SB-T-01 final (10 storms through the load balancer); SB-T-05 (5 resets, including after a run like SB-D07); AR-SP-01 with `vigil-app` (live topics); `deploy/status.sh` reports readable secret directories (AR-ASM-13); AR-SP-03; SB-T-09 part one (with SL-F-4 active for 10 minutes, every Claude and OpenAI call fails within 5 s and Jira, Teams, email and webhooks work); the storm driver in mode `script` against the demo host with the real Jira, Teams and SES (SB-T-02 first pass); the Playwright suites of P-28 to P-30 against the demo host. |
| Done-check | The SL-CP-1 review of Section 8.3 passes. |
| Recorded by this package | DD-F-10 measured values from the main host (final; closes DD-O-03 and SB-O-03). |
| Runs in | BP-E-01 (scripts), BP-E-02, BP-E-03, BP-E-04, BP-E-05 (owner-run) |

### 7.4 Phase D — Primary demo complete, to SL-CP-2

#### P-33 Dashboard screen

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | S; P-32; none |
| Builds | DC-S10 and its navigation item. |
| Requirements built | SL-A1-09, SL-A4-10; DC-S10-01 to DC-S10-05. |
| Tests | Playwright: the starting state shows SB-V-38; after a storm driver run in mode `script` the values of SB-S09 (in mode `immediate`, the values the formulas of DM-R-53 give from the run's own times). |
| Runs in | BP-E-01, BP-E-02 |

#### P-34 Primary demo hardening

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-33; BP-OT-12 |
| Builds | Fixes found by the tests below; the prompt rewording AR-G-12 allows when RB-DB-01 is cited in fewer than 4 of 5 runs; no new feature. |
| Requirements built | SL-A3-04; SL-A3-08 (primary demo part); SL-CP-2. |
| Tests | Owner-run on the demo installation: SB-T-02 (5 runs from a reset, mode `script`, every exact value of SB-S01 to SB-S09); SB-T-03 in the product with `embeddinggemma`; SB-T-04 (all step times and the local model within 45 s); SB-T-08 (two rehearsals with the senior manager); the backup video of the primary demo. |
| Done-check | The SL-CP-2 review of Section 8.3 passes. |
| Runs in | BP-E-01, BP-E-03, BP-E-04, BP-E-05 |

### 7.5 Phase E — Deep dives, to SL-CP-3

#### P-35 Workflow drafts, validation, versions and rollback

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-34; none |
| Builds | `vigil-workflows`: DM-E-46 to DM-E-48; drafts and revisions (DM-SM-06), validation (DM-E48-R1, DC-TX-07), publish with the change list (DC-TX-08), rollback, AR-E-12, configuration audit summaries (DC-TX-10); operations AR-I-40 to AR-I-46 and AR-I-50. |
| Requirements built | PM-M11-03 to PM-M11-06; DM-E-46 to DM-E-48 with their rules; DM-SM-06; SL-WE-07 to SL-WE-09; SL-B-03 (versions, rollback); DC-TX-07, DC-TX-08; AR-I-40 to AR-I-46, AR-I-50, AR-E-12. |
| Tests | DD-K-07 through the product (version 2 from `draft_changes_for_version_2`, its change list in order); each validation rule violated once; a save with an old revision refused; rollback from 2 to 1 creates version 3. |
| Runs in | BP-E-01, BP-E-02 |

#### P-36 Test replay

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-35; none |
| Builds | `vigil-engine` test mode (DM-R-46 to DM-R-49, DM-R-59) with the notes of DC-TX-09; `vigil-workflows` test replay (DM-E-49, DM-R-68, AR-I-47 to AR-I-49); the topic `test-replay`. |
| Requirements built | PM-M11-08, PM-M12-09; DM-E-49 with its rules; DM-SM-16 (test replay result); DM-R-46 to DM-R-49, DM-R-59, DM-R-68; DM-C-09; DC-TX-09; DC-U-14; AR-I-47 to AR-I-49; SL-A2-06 (test replay). |
| Tests | Replay of a storm incident on version 2 with the routing change gives SB-D02 step 3's path, notes and dry run; no connector, runner, AI call or live audit event during a replay; an invalid draft gives a failed result with its reason. |
| Runs in | BP-E-01, BP-E-02 |

#### P-37 Workflow editor screen

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | L; P-36; none |
| Builds | DC-S06 complete: modes, top bar, palette, cards, connecting, selection, parameter panel with entry checks, undo and redo (AR-W-05), zoom, pan, Fit, Auto layout (AR-W-04), save, problems list, test replay dialog and panel, publish and rollback with their confirmations (DC-U-11), versions panel, leave warning. |
| Requirements built | SL-A4-06; SL-WE-01 to SL-WE-09; PM-M24-04; DC-S06-01 to DC-S06-16; DC-TX-05; DC-U-11 (Publish, Roll back); AR-W-03 (editing), AR-W-04, AR-W-05; SL-A2-06 (editor). |
| Tests | Playwright: every editor action of SB-D02 step 1 in order, then save, replay, publish and the versions panel lines of SB-D02 step 9; undo of 50 changes. |
| Runs in | BP-E-01, BP-E-02 |

#### P-38 Routing rules and setup screens

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-37; none |
| Builds | DC-S08 with AR-I-55 and AR-I-56; DC-S07 with AR-I-70 to AR-I-75: source cards, address edit through `vigil-setup` and `vigil-connectors` with its audit, connection test, service map import, readiness gaps (DM-E-81) and `vigil-setup`'s reactions to AR-E-03 and AR-E-04; the "Saved." feedback (DC-U-16). |
| Requirements built | SL-A2-01, SL-A4-07, SL-A4-08; PM-M10-01 (screen), PM-M22-01, PM-M22-02; DM-E-81; DC-S07-01 to DC-S07-05, DC-S08-01 to DC-S08-05; DC-TX-06, DC-TX-11 (routing and gaps); DC-U-16; AR-I-55, AR-I-56, AR-I-70 to AR-I-75; SL-A2-06 (routing change). |
| Tests | Playwright: SB-D01 steps 2 to 5 with their exact values; SB-D02 step 2 and the card text; refusals of DC-TX-11. |
| Runs in | BP-E-01, BP-E-02 |

#### P-39 AI settings, re-run and audit trail screens

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-38; none |
| Builds | DC-S09 with AR-I-60 to AR-I-64 (task assignments, the SL-T-3 provider change with re-embedding, data routing, usage summary, usage log with the request group filter); the re-run control, its confirmation and the usage link of DC-S03-09 with AR-I-26; DC-S11 with AR-I-81 and AR-I-82 (filters including merged incidents, details). |
| Requirements built | SL-A2-02, SL-A2-05 (screens), SL-A2-07, SL-A2-08, SL-A4-03 (re-run control), SL-A4-09, SL-A4-11; DC-S03-09 (control and link), DC-S09-01 to DC-S09-05, DC-S11-01 to DC-S11-04; DC-TX-10; DC-U-11 (Re-run, provider change); AR-I-26, AR-I-60 to AR-I-64, AR-I-81, AR-I-82. |
| Tests | Playwright on the local installation with the cloud AI fakes stopped (Section 11.3): SB-D03 and SB-D04 outcomes; SB-D05 data routing save; SB-D06 filters and counts (126 "Alert grouped"). |
| Runs in | BP-E-01, BP-E-02 |

#### P-40 Full demo hardening and backup video

| Item | Content |
| --- | --- |
| Size; depends on; owner tasks | M; P-39; BP-OT-12 |
| Builds | Fixes found by the tests below; no new feature. |
| Requirements built | SL-A2-01 to SL-A2-08 on the demo installation; DM-R-24 (a) to (c) (demo order of the rehearsal); SL-A3-08 (complete); SL-CP-3. |
| Tests | Owner-run: SB-T-09 (all parts, on 3 days); SB-T-10; a rehearsal of SB-D01 to SB-D07 on the demo installation; the backup video of the full demo. |
| Done-check | The SL-CP-3 review of Section 8.3 passes. |
| Runs in | BP-E-01, BP-E-03, BP-E-04, BP-E-05 |

### 7.6 Interim rules during the build (complete list)

| ID | Rule | Ends | Trace |
| --- | --- | --- | --- |
| BP-R-22 | The navigation of DC-U-04 lists only the screens already built (amendment BP-A-12). | SL-CP-3 | DC-U-04 |
| BP-R-29 | Until every seed import part is registered, the import skips the top-level sections of parts that are not registered (their files are still read and checked at the turn of the first part that reads them, amendment BP-A-23), and the cross-module key checks whose referring or target part did not succeed; AR-Q-10 imports the full bundle and checks the registered parts. A section that belongs to no part (AR-B-05) still refuses the first part that reads its file. | P-26 | AR-B-05; DM-R-60 |

### 7.7 Size summary

5 packages S, 25 packages M, 10 packages L (P-05 became L in v1.7). With one cloud session for S, one to two for M and two to three for L, the estimate is about 65 cloud sessions, plus review time and owner-run steps. This is an effort estimate for planning, not a date (CTX-C-05).

## 8. Phases, order and checkpoints

### 8.1 Phases (complete list)

| Phase | Packages | Ends at | Order and parallel work (BP-R-18) |
| --- | --- | --- | --- |
| A Foundations and risk spikes | P-01 to P-07 | All Phase A packages merged | P-02 and P-03 start after P-01; P-04 after P-03; P-05 after P-01; P-06 and P-07 after P-05. Phase A packages that change only `demo/`, `deploy/`, `spikes/` or `dev/` run alongside Phase B. |
| B Product core | P-08 to P-27 | P-27 merged: the storm runs headless from intake to a resolved incident on the local installation | Starts when P-02 is merged. One product package at a time, in order (PM-R-03); P-10 and P-26 also wait for P-04, P-18 for P-03, P-19 and P-21 for P-06, P-22 for P-07 (BP-R-19, BP-R-20). |
| C Screens and demo operations | P-28 to P-32 | SL-CP-1 | P-31 alongside P-28 to P-30. |
| D Primary demo complete | P-33, P-34 | SL-CP-2 | None. |
| E Deep dives | P-35 to P-40 | SL-CP-3 | None. |

### 8.2 SL-CP-1 content (resolves SL-O-19)

SL-CP-1 is reached when, on the demo installation through the public names, from a reset, the storm from the control panel goes through to a resolved incident and a resolved Jira ticket as in SB-S00 to SB-S08, shown on these screens (complete list): DC-S01 Login; DC-S02 Incident list; DC-S03 Incident workspace, every item except the "Re-run AI investigation" control and the usage link of DC-S03-09; DC-S04 Acknowledge page; DC-S05 Approval page; DC-S12 Demo control panel. The dashboard (DC-S10) belongs to SL-CP-2; DC-S06 to DC-S09 and DC-S11 belong to SL-CP-3.

### 8.3 Checkpoint reviews (complete list of checks)

Each review is done in the Claude project with the package reports of the phase and recorded in the report of the checkpoint package (P-32, P-34, P-40).

| ID | Check | SL-CP-1 | SL-CP-2 | SL-CP-3 |
| --- | --- | --- | --- | --- |
| BP-CR-01 | Every scene of the checkpoint runs on the demo installation with its exact values (SL-CP-1: SB-S00 to SB-S08; SL-CP-2: SB-S00 to SB-S10; SL-CP-3: also SB-D01 to SB-D07). | Yes | Yes | Yes |
| BP-CR-02 | `tools/trace.py` lists no ID of the built packages' part 4 tables without its proof, and the list of earlier-document IDs with no test is reviewed. | Yes | Yes | Yes |
| BP-CR-03 | Every spike and test that Section 9 assigns to the phase has passed, with its result in a package report. | Yes | Yes | Yes |
| BP-CR-04 | Every "Document feedback" item of the phase is resolved in the decision log or carried with an owner. | Yes | Yes | Yes |
| BP-CR-05 | Nothing outside list A and list B was built (BP-DC-03, BP-R-21). | Yes | Yes | Yes |
| BP-CR-06 | The scope is reopened only here (SL-CR-03): the owner decides on every change request collected in the phase. | Yes | Yes | Yes |
| BP-CR-07 | Repeatability: SB-T-02 five runs (SL-A3-04). | No | Yes | Yes |
| BP-CR-08 | Backup video exists: primary demo at SL-CP-2, full demo at SL-CP-3 (SL-A3-08). | No | Yes | Yes |

## 9. Spikes and tests of earlier rounds (complete list)

| Test | Package | Environment |
| --- | --- | --- |
| AR-SP-01 | P-05 (probe), P-32 (with `vigil-app`, and the lab-to-`vigil-app` check) | BP-E-04, browser in Pakistan |
| AR-SP-02 | P-07 | BP-E-03 |
| AR-SP-03 | P-32 | BP-E-04 |
| SB-T-01 | P-04 (development run), P-32 (final, main host through the load balancer) | BP-E-01, BP-E-03; BP-E-04 |
| SB-T-02 | P-27 (driver; mode `immediate` with dev fakes; mode `script` on the Mac), P-32 (first pass, real services), P-34 (five runs) | BP-E-01, BP-E-02, BP-E-03; BP-E-04 |
| SB-T-03 | P-06 (runtime), P-34 (in the product) | BP-E-04 |
| SB-T-04 | P-06 (local model), P-34 (all step times) | BP-E-04, BP-E-05 |
| SB-T-05 | P-04 (lab clear), P-31 (local reset), P-32 (main host) | BP-E-01; BP-E-04 |
| SB-T-06 | P-07 | BP-E-03 |
| SB-T-07 | P-06 | BP-E-03 |
| SB-T-08 | P-34 | BP-E-04 |
| SB-T-09 | P-32 (part one: the cut and the other traffic), P-40 (all parts on 3 days; the order Claude and OpenAI only needs AR-I-61) | BP-E-04 |
| SB-T-10 | P-40 | BP-E-04 |
| AR-Q-01, AR-Q-03 | P-01, then every package | BP-E-01, BP-E-02 |
| AR-Q-02, AR-Q-05, AR-Q-06, AR-Q-08 | P-02, then every package | BP-E-01, BP-E-02 |
| AR-Q-04, AR-Q-09 | P-10, then P-18 to P-22 | BP-E-01, BP-E-02 |
| AR-Q-07 | P-27 | BP-E-01, BP-E-02 |
| AR-Q-10 | P-11 (registered parts), each package that registers a part, complete in P-26 | BP-E-01, BP-E-02 |
| AR-Q-11 | P-17 (a to d, f), P-24 (e) | BP-E-01, BP-E-02 |
| SL-B-04 automated tests (preauthorized, blocked) | P-24 | BP-E-01, BP-E-02 |
| DD-K-01 to DD-K-16 (`check_data.py`) | Every package, through the gate command | BP-E-01, BP-E-02 |
| Connector recordings (AR §19.2) | P-04, P-06, P-07 record; P-10, P-19, P-21, P-22 use | BP-E-01, BP-E-03, BP-E-04, BP-E-05 |

## 10. Build pipeline and deployment

### 10.1 GitHub Actions workflows (complete list)

Every workflow pins its actions by commit SHA, cancels an earlier run of the same branch (`concurrency` with `cancel-in-progress`) and keeps artifacts for 3 days.

| ID | Workflow | Trigger | Steps (complete list) |
| --- | --- | --- | --- |
| BP-W-01 | `ci.yml`, job `gate` | Every pull request to `main`; manual run | When the pull request changes only `docs/**`: `check_data.py` and `trace.py --check`. Otherwise: set up Temurin 25, Node.js 22, Python 3.12, the pinned tools (as BP-R-01); cache Maven and npm; `tools/check-all.sh` without step (1); `gitleaks` on the pull request's commits. |
| BP-W-02 | `e2e.yml` | Pull requests labelled `e2e`; manual run; the specs of P-26 to P-40 tell the owner to add the label | Build the local installation's images; `deploy/local.sh up` (dev fakes, `test` profile); the storm driver in mode `immediate`; the Playwright suites; upload logs, Playwright traces and the dev fakes' calls as artifacts on failure only. |
| BP-W-03 | `release.yml` | Manual run on `main` with a release name | `deploy/build.sh {release}`: build the product and demo-only images for `linux/amd64` and push them to the installation's ECR repositories, tagged with the release name (immutable tags), with the GitHub OIDC role (amendment BP-A-06). Nothing is committed. |

### 10.2 Minutes budget

Estimate for the whole build (not per month): `ci.yml` about 15 minutes a run, about 4 runs per package, 40 packages: about 2,400 minutes; `e2e.yml` about 30 minutes a run, about 2 runs for each of 15 packages: about 900 minutes. The build therefore needs about 3,300 minutes over its whole length; months with more than the included minutes are handled under BP-O-02. From P-04 a gate run with the lab tests takes about 20 minutes; packages that change only the product skip them (BP-R-12 step (3), CTX-D-036). Larger runners are not available on personal plans (Section 16 item 5); when a runner's memory or disk is too small for `e2e.yml` (BP-ASM-04), the e2e run moves to cloud sessions.

### 10.3 Deployment scripts (complete list; AR-H-11 "Round 6 defines the scripts")

All scripts are bash with `set -euo pipefail`, run on the owner's Mac with the AWS CLI, and act on the hosts through AWS Systems Manager, except `secrets.sh` and the steps `deploy.sh` runs on the host.

| Script | Does (complete list) | Built in |
| --- | --- | --- |
| `deploy/edge.sh` | Creates or updates, idempotently, the resources of AR-H-03 to AR-H-06 and AR-H-08 (network, security groups, hosts with the bootstrap user data of BP-A-06, roles, load balancer, certificate, Route 53 records, SES identity and DKIM records), the ECR repositories with immutable tags, the deployment bucket `vigil-{installation}-deploy-{account id}` (BP-A-21), and the GitHub OIDC provider and push role (BP-A-06); prints the four name servers for `deploy/edge.md`. | P-05 |
| `deploy/edge.md` | Manual steps: the `NS` delegation at Cloudflare, the SES production access request, the G instance quota check (BP-OT-09). | P-05 |
| `deploy/new-installation.sh {installation}` | Generates the secrets marked `new-installation` in AR-S-13 into Parameter Store `/vigil/{installation}/`; never overwrites an existing parameter. | P-05 |
| `deploy/secrets.sh {installation} [--generated]` | On the host: writes the secret files of AR-S-04 from Parameter Store per `deploy/units.yaml`; with `--generated`, copies `zabbix-api` and writes `derived.yaml` (AR-B-07); prints the Jira token expiry (AR-H-07). | P-05 |
| `deploy/deploy.sh {installation} {release}` | Uploads the deployment files (compose file, `units.yaml`, `secrets.sh`, `values/{installation}.yaml`, and the host scripts and `models.lock` the P-05 spec names) to the deployment bucket; on the host through Systems Manager: downloads them to `/srv/vigil/deploy/`, then runs AR-H-11's sequence with the images of the release tag, and records the image digests it started in `/srv/vigil/deploy/deployed.yaml`; checks `models.lock`. | P-05 (sequence), P-32 (all units) |
| `deploy/status.sh {installation}` | AR-H-12. | P-05 |
| `deploy/gpu.sh start|stop|status` | AR-G-31 on the GPU host, including the warm-up check and `models.lock`. | P-05 |
| `deploy/models.sh update {model}` | AR-H-11's model change procedure. | P-05 |
| `deploy/build.sh {release}` | Called by `release.yml` (BP-W-03). | P-05 (probe), P-32 (all images) |
| `deploy/local.sh up|down|reset|logs` | Section 11. | P-03 (lab part), P-26 (complete) |

## 11. Local installation

### 11.1 Content (complete list of units)

`deploy/local/compose.yaml` runs, on one Docker host: `vigil-db`, `vigil-migrate`, `vigil-app` (product images built locally); the lab hosts, Zabbix, Prometheus, Alertmanager and `demo-faults` (P-03, P-04); `demo-control` (P-31); the dev fakes and `mailpit` (Section 11.3); `vigil-embed` with `embeddinggemma` when the values file asks for the real local embedding model (owner's Mac), otherwise the embedding fake; in the compose profile `acceptance` the storm driver; in the profile `e2e` the Playwright runner (image `mcr.microsoft.com/playwright`, pinned); in the profile `lab-only` the webhook recorder (`dev/recorder/`), which `deploy/local.sh up --lab-only` starts in place of `vigil-app` at `webhook_base_url` (P-04); in the profile `spike` the storm timing spike (`spikes/storm-timing/`, P-04). There is no load balancer, no TLS, no `DOCKER-USER` rule (AR-S-08) and no SL-F-4 (the AI fakes are stopped instead, Section 11.3). It is reachable only from the machine it runs on.

### 11.2 Rules (complete list)

| ID | Rule | Trace |
| --- | --- | --- |
| BP-R-24 | Host names: `demo.vigil.test` (Vigil, port 8080) and `control.vigil.test` (control panel, port 8090), published on 127.0.0.1 only. On the owner's Mac they resolve through `/etc/hosts` (BP-OT-10); inside the compose project, `demo.vigil.test` is a network alias of `vigil-app` on `lab-net` and `vigil-net`, so the lab's webhooks, the driver and Playwright use the same host name as the browser and reach the same tenant alias. | AR-I-02 (BP-A-03) |
| BP-R-25 | The local installation differs from the demo installation only in (complete list): `vigil.security.secure-cookies=false` for `vigil-app` and `demo-control` (BP-A-02); `vigil-app` listens on all its container addresses (`vigil.http.bind-address=0.0.0.0`, BP-A-09); the link base `http://{host}:8080` (`vigil.links.base`, BP-A-09); the deployment values of Section 11.4; the lab profile; the units of Section 11.1; the network alias `demo.vigil.test` (BP-R-24); the secret file location and modes (BP-R-26), and the user of `lab-zabbix-provision` (the user who runs `deploy/local.sh`, so that it can write the generated-secrets folder under that user's home; P-04); no load balancer, so AR-S-08 and AR-S-09 apply as amended by BP-A-16. | AR-S-02, AR-S-03, AR-U-01, AR §10.3 |
| BP-R-26 | Secrets are generated by `deploy/local.sh` into `~/.vigil-local/{project}/secrets/{unit}/` with the file names of AR-S-04, files mode 0444 and folders 0555, owned by the user who runs the script, mounted read-only (amendment BP-A-10); they never enter the repository. Real external service credentials, when the owner wants them, are placed there by the owner. | AR-S-04; SL-B-14 |
| BP-R-27 | The local model is reached through `gpu_host_ip`: on the owner's Mac `host.docker.internal`, where Ollama runs natively when the owner wants the real local model (BP-ASM-08); otherwise `ollama-llm.fakes`. | AR-K-09 |
| BP-R-28 | The same `deploy/local/compose.yaml` and `deploy/local.sh` run on the owner's Mac, on GitHub Actions runners and in cloud sessions. `deploy/values/local.yaml` (committed) selects the dev fakes; `deploy/values/local-real.yaml` (owner's Mac only, not committed, because it holds the owner's own test mailbox addresses) selects real services. | CTX-C-01 |
| BP-R-30 | **Lab size profiles.** `demo`: the `/data` device and baseline of DD-F-10 (16 GiB; `/data/db` 6 GiB; `/data/export` 1 GiB). `test`: a 4 GiB device, `/data/db` 0.8 GiB, `/data/export` 0.1 GiB; the injection rule of SL-F-1 is unchanged (fill until 64 MiB stay free). The demo installation and the owner-run Mac checks use `demo`; cloud sessions, `e2e.yml` and the Java tests use `test`. Values that depend on sizes (DD-F-04 summaries, DD-F-10 measured values, SB-S03 and SB-S06 sizes, and the `df` used percentage after SL-F-1: 100 % in `demo`, 99 % in `test`, because 64 MiB is 1.6 % of the `test` device and `df` rounds up) are compared exactly only with `demo`. The precondition of the registered action (at least 90 %) and the monitoring thresholds hold in both profiles. | DD-F-10 (BP-A-05) |
| BP-R-31 | `tools/check-all.sh` step (1) compares the version line of `tools/cloud-setup.sh` with `/etc/vigil-cloud-setup.version` when it runs in a cloud session; when they differ it stops with "The cloud environment vigil is out of date: update its setup script from tools/cloud-setup.sh." A package that changes `tools/cloud-setup.sh` lists the update of the environment as an owner-run step. | BP-R-01 |

### 11.3 Dev fakes (complete list)

All fakes except `mailpit` are one image (`dev/fakes/`, Python 3.12, standard library), started once per role with its own network alias, so each can be stopped alone.

| Container and alias | Replaces | Behaviour |
| --- | --- | --- |
| `jira-fake`, `jira.fakes:8000` | Jira Cloud REST API v3 (the calls of AR-K-14) | Keeps issues, labels, comments and statuses (`To Do`, `In Progress`, `Done`) in memory. |
| `teams-fake`, `teams.fakes:8000` | Teams Workflows webhooks (any path) | Records each posted body; answers 202. |
| `claude-fake`, `claude.fakes:8000` | Claude Messages API | Answers SL-T-1 requests with an output that passes PM-M17-03: likely cause naming the hypothesis component, `confirms`, the label `E1`, an uncertainty sentence, the runbook key RB-DB-01 when supplied, otherwise the first supplied key; answers SL-T-2 with a summary built from the request; reports token counts. |
| `openai-fake`, `openai.fakes:8000` | OpenAI Responses and Embeddings APIs | As `claude-fake` for chat; embeddings as `ollama-embed-fake`. |
| `ollama-llm-fake`, `ollama-llm.fakes:11434` | Ollama `/api/chat` | As `claude-fake`, in Ollama's answer format. |
| `ollama-embed-fake`, `ollama-embed.fakes:11434` | Ollama `/api/embed` | A vector derived only from the input text (equal texts give equal vectors), with token counts. |
| `mailpit` (pinned third-party image), `mailpit:1025` | Amazon SES SMTP | Accepts SMTP without STARTTLS; its API lists received messages. |

Every fake offers `GET /_fakes/calls` (and `jira-fake` also `GET /_fakes/issues`) for the tests. To make the cloud AI providers unreachable (SB-D03, SB-D04 in a local installation), `deploy/local.sh e2e` stops `claude-fake` and `openai-fake` (`docker compose stop`) on the host before the suites tagged `@ai-down`, so their names do not resolve, which AR-G-13 counts as `unreachable`; the Playwright container itself has no Docker access. No product code knows the fakes (BP-R-06).

### 11.4 Deployment values of the local installation (complete list)

The placeholders of AR-B-07 as amended by BP-A-01, with these values in `deploy/values/local.yaml`: `demo_domain` = `vigil.test`; `endpoint.jira` = `http://jira.fakes:8000`; `endpoint.smtp` = `smtp://mailpit:1025`; `smtp_starttls` = `false`; `endpoint.claude` = `http://claude.fakes:8000`; `endpoint.openai` = `http://openai.fakes:8000`; `endpoint.local_embedding` = `http://ollama-embed.fakes:11434` (or `http://vigil-embed:11434`); `gpu_host_ip` = `ollama-llm.fakes` (or `host.docker.internal`, BP-R-27); `lab_db_host_key_sha256` derived by `deploy/local.sh`; `mailbox.{key}` = `{key}@vigil.test`. Lab values: `webhook_base_url` = `http://demo.vigil.test:8080` (BP-A-04); `lab_profile` = `test` or `demo`. The Teams webhook secrets point to `http://teams.fakes:8000/{channel key}`.

## 12. Owner tasks (complete list)

| ID | Task | Needed before | Trace |
| --- | --- | --- | --- |
| BP-OT-01 | Create a private GitHub repository and push `main`; install the Claude GitHub App on the repository. `main` stays unprotected (GitHub Free, BP-D-13). | P-01 | Owner answer 3 |
| BP-OT-02 | Create the cloud environment `vigil`: Trusted network access; setup script = the content of `tools/cloud-setup.sh` from P-01's branch; environment variables `BASH_DEFAULT_TIMEOUT_MS=600000`, `BASH_MAX_TIMEOUT_MS=1800000`, `JAVA_HOME=/opt/temurin-25`. Update the script whenever a package changes `tools/cloud-setup.sh` (BP-R-31). | P-01 merge (owner-run step of P-01) | BP-E-01 |
| BP-OT-03 | AWS: an administrator user with MFA for the operator, region `us-east-1`, a monthly budget alert at the approved estimate (AR §17.1). | P-05 | CTX-D-026 |
| BP-OT-04 | Choose the subdomain `${demo_domain}`; after `deploy/edge.sh`, add the four `NS` records at Cloudflare (`deploy/edge.md`). | P-05 | AR-H-05 |
| BP-OT-05 | Create API keys for Claude and OpenAI; store them as `/vigil/demo/claude` and `/vigil/demo/openai` in Parameter Store. | P-06 | AR-S-13 |
| BP-OT-06 | Create the Jira Cloud Free site, project `NOC` and the Vigil account's API token (AR-H-07). | P-06 | AR-H-07 |
| BP-OT-07 | Microsoft 365 prerequisites of AR-H-10: the team "Techfinity Demo", nine channels, nine Workflows webhooks each with a co-owner, nine shared mailboxes (resolves AR-O-01). | P-07 | AR-H-10; AR-O-01 |
| BP-OT-08 | SES: request production access; until granted, verify the nine mailboxes (AR-H-08). | P-07 | AR-H-08 |
| BP-OT-09 | Check that the EC2 quota for G instances in `us-east-1` allows a `g6.xlarge` (4 vCPU); request an increase when it does not. | P-06 | AR-H-02; AR-ASM-06 |
| BP-OT-10 | On the Mac: give Docker at least 10 GB of memory and 40 GB of disk; install Python 3.12 as `python3.12` (Example: from Homebrew) for the owner-run test scripts; add `127.0.0.1 demo.vigil.test control.vigil.test` to `/etc/hosts`; optionally install Ollama natively and pull `gemma4:12b` and `embeddinggemma` (BP-ASM-08). | P-03 owner-run step | BP-R-24, BP-R-27 |
| BP-OT-11 | Obtain the senior manager's confirmation that the Jazz name and logo may be shown, because the demo login page shows the customer name before login (CTX-O-05). | P-32 | CTX-O-05 |
| BP-OT-12 | Rehearsals with the senior manager (SB-T-08) and the backup videos (SL-A3-08). | P-34, P-40 | SB-T-08; SL-A3-08 |
| BP-OT-13 | Review and merge every package pull request (BP-L-7); run the owner-run steps (BP-L-5); take part in the checkpoint reviews. | Every package | BP-L-5, BP-L-7 |
| BP-OT-14 | Store the operator-made secrets of AR-S-13 in Parameter Store under `/vigil/demo/`: `jira`; `smtp` (create the SES SMTP credentials first); the nine `teams-team-{key}` and `teams-user-{username}` webhook addresses. Fill `deploy/values/demo.yaml` with the Jira address and the nine mailbox addresses (committed: they are not secrets). | P-06 (Jira), P-07 (Teams, SMTP), P-32 (values) | AR-S-13, AR-B-07 |

## 13. Traceability

### 13.1 Scope lock items (complete list; CTX-Q-1)

| Scope lock item | Built and tested in |
| --- | --- |
| SL-A1-01 | P-04, P-10, P-13, P-28; tested P-27, P-32 (SB-T-01) |
| SL-A1-02 | P-12, P-14, P-15, P-16, P-28, P-29 |
| SL-A1-03 | P-18, P-29 |
| SL-A1-04 | P-15, P-19, P-20, P-29 |
| SL-A1-05 | P-21, P-22, P-30 |
| SL-A1-06 | P-20, P-24, P-29, P-30 |
| SL-A1-07 | P-21, P-23, P-29 |
| SL-A1-08 | P-09, P-17, P-25, P-29 |
| SL-A1-09 | P-23, P-25, P-33 |
| SL-A2-01 | P-13, P-38 |
| SL-A2-02 | P-19, P-39 |
| SL-A2-03 | P-22, P-29 |
| SL-A2-04 | P-22, P-23, P-24; tested P-40 |
| SL-A2-05 | P-19, P-20, P-31, P-39 |
| SL-A2-06 | P-35, P-36, P-37, P-38 |
| SL-A2-07 | P-20, P-39 |
| SL-A2-08 | P-08, P-39 |
| SL-A3-01 | P-03, P-04 |
| SL-A3-02 | P-04, P-31 |
| SL-A3-03 | P-11, P-31 |
| SL-A3-04 | P-27, P-34 |
| SL-A3-05 | P-05, P-32 |
| SL-A3-06 | P-08, P-28 |
| SL-A3-07 | P-08 |
| SL-A3-08 | P-34, P-40 |
| SL-A4-01, SL-A4-02 | P-28 |
| SL-A4-03 | P-29, P-39 |
| SL-A4-04, SL-A4-05 | P-30 |
| SL-A4-06 | P-37 |
| SL-A4-07, SL-A4-08 | P-38 |
| SL-A4-09, SL-A4-11 | P-39 |
| SL-A4-10 | P-33 |
| SL-A4-12 | P-31 |
| SL-T-1, SL-T-2, SL-T-3 | P-19, P-20 |
| SL-WE-01, SL-WE-02, SL-WE-03, SL-WE-04, SL-WE-05, SL-WE-06 | P-37 |
| SL-WE-07, SL-WE-08, SL-WE-09 | P-35, P-37 |
| SL-WE-10 | P-29 |
| SL-B-01 | P-02, P-08 |
| SL-B-02 | P-02, P-13 |
| SL-B-03 | P-17, P-35 |
| SL-B-04 | P-17, P-24 |
| SL-B-05 | P-18 |
| SL-B-06 | P-02, P-08 |
| SL-B-07 | P-10 |
| SL-B-08 | P-19 |
| SL-B-09 | P-11, P-15 |
| SL-B-10 | P-01 (BP-R-06), P-04, P-31 |
| SL-B-11 | P-02, P-17, P-18, P-21, P-24 |
| SL-B-12 | P-14, P-16 |
| SL-B-13 | P-08, P-09, P-30 |
| SL-B-14 | P-05, P-08, P-09, P-10, BP-R-14 (clarified by BP-A-13) |
| SL-C-01, SL-C-02, SL-C-03, SL-C-04, SL-C-05, SL-C-06, SL-C-07, SL-C-08, SL-C-09, SL-C-10, SL-C-12, SL-C-13, SL-C-14, SL-C-15, SL-C-16, SL-C-17, SL-C-18, SL-C-19 | Not built; checked by BP-DC-03 and BP-CR-05. SL-C-17: the path is tested in P-24, not shown. |
| SL-C-11 | Withdrawn in `01-scope-lock`; no mapping |
| SL-DR-01, SL-DR-02, SL-DR-04 | P-11, P-26 (the data set is imported unchanged, with the placeholders of BP-A-01) |
| SL-DR-03 | P-21, P-22, P-28, P-29, P-33 |
| SL-F-1, SL-F-2, SL-F-3 | P-04 |
| SL-F-4 | P-31; tested P-32 |
| SL-ASM-01 | P-04, P-32 (SB-T-01) |
| SL-ASM-02 | P-07 (SB-T-06) |
| SL-ASM-03 | No build content (confirmed in Round 3) |
| SL-ASM-04 | P-06, P-34 (SB-T-03) |
| SL-O-01, SL-O-02, SL-O-03, SL-O-04, SL-O-05, SL-O-06, SL-O-07, SL-O-08, SL-O-09, SL-O-10, SL-O-11, SL-O-12, SL-O-13, SL-O-14, SL-O-15, SL-O-16, SL-O-17, SL-O-18, SL-O-20, SL-O-21, SL-O-22, SL-O-23 | Closed in earlier rounds; their values arrive with the seed bundle (P-11 and the parts of Section 7) |
| SL-O-19 | Section 8.2 |
| SL-X-01, SL-X-02, SL-X-03, SL-X-04, SL-X-05, SL-X-06, SL-X-07, SL-X-08, SL-X-09, SL-X-10, SL-X-11, SL-X-12, SL-X-13, SL-X-14, SL-X-15, SL-X-16 | No build content (record of differences) |
| SL-CR-01, SL-CR-02, SL-CR-03 | BP-R-21, BP-CR-05, BP-CR-06 |
| SL-CP-1, SL-CP-2, SL-CP-3 | Section 8; P-32, P-34, P-40 |

### 13.2 Product modules and demo-only components (complete list)

| Module or component | Packages |
| --- | --- |
| `vigil-audit` | P-08; screen P-39 |
| `vigil-tenancy` | P-08 |
| `vigil-identity` | P-08 |
| `vigil-connectors` | P-10; kinds in P-18 to P-22 |
| `vigil-runner` | P-18 |
| `vigil-servicemap` | P-12; screen P-38 |
| `vigil-intake` | P-13 |
| `vigil-ai-gateway` | P-19; screen P-39 |
| `vigil-knowledge` | P-20 |
| `vigil-incidents` | P-14 |
| `vigil-investigation` | P-20 |
| `vigil-notifications` | P-22 |
| `vigil-ticketing` | P-21 |
| `vigil-routing` | P-15, P-22 (Escalation step); screen P-38 |
| `vigil-correlation` | P-16 |
| `vigil-recovery` | P-23 |
| `vigil-diagnostics` | P-18 |
| `vigil-actions` | P-24 |
| `vigil-engine` | P-17, P-36 (test mode) |
| `vigil-workflows` | P-17, P-35, P-36 |
| `vigil-reporting` | P-23; screen P-33 |
| `vigil-setup` | P-11, P-38 |
| `vigil-api` | P-09, P-11, P-13, P-25, P-35, P-36, P-38, P-39 |
| `vigil-web` | P-01 (skeleton), P-28 to P-30, P-33, P-37 to P-39 |
| `demo-lab` | P-03 |
| `demo-monitoring` | P-04 |
| `demo-faults` | P-04, P-31 |
| `demo-control` | P-31 |
| `demo-data` | P-11, P-26, P-31 (bundle builders) |
| Backup video | P-34, P-40 |

## 14. Scenario walkthrough of the primary demo (CTX-Q-3)

| Scene | Packages that build it | First runs end to end | Proved by |
| --- | --- | --- | --- |
| SL-A1-01 | P-03, P-04 lab and faults; P-10 parsing; P-13 intake; P-28 strip; P-31 storm button | P-27 headless; P-32 on the demo installation | SB-T-01 (P-04, P-32) |
| SL-A1-02 | P-12, P-14, P-15, P-16; P-28 list; P-29 workspace | P-27; P-32 | P-16 order test; P-29 Playwright |
| SL-A1-03 | P-18; P-29 evidence | P-27; P-32 | P-18 runner tests; P-27 `demo` run |
| SL-A1-04 | P-15 routing; P-19, P-20 AI; P-29 AI section | P-27 (AI fakes); P-32 (Claude) | SB-T-04, citation rate (P-34) |
| SL-A1-05 | P-21 Jira; P-22 Teams, email; P-30 Acknowledge page | P-27 (fakes); P-32 (real) | SB-T-06, SB-T-07 (P-06, P-07); P-30 Playwright |
| SL-A1-06 | P-20 search; P-24 approval and action; P-30 Approval page | P-27; P-32 | SB-T-03 (P-06, P-34); SL-B-04 tests (P-24) |
| SL-A1-07 | P-23 recovery; P-21 resolve | P-27; P-32 | P-23 window tests; SB-T-01 resolve timing |
| SL-A1-08 | P-17 events; P-09, P-25 live stream; P-29 canvas | P-29 on the local installation; P-32 | AR-SP-01 (P-05, P-32); P-29 highlight timing |
| SL-A1-09 | P-23 metrics; P-25 operation; P-33 screen | P-33 | DD-K-11 (P-23); SB-S09 values (P-33, P-34) |

Result: every scene has its building packages, the point where it first runs end to end, and the test that proves it.

## 15. Explicitly out of scope for this document

The detail of each package spec beyond its entry in Section 7 (written in the Build round); dates (CTX-C-05); visual design; changes to behaviour defined by earlier documents, except the amendments of Section 17.2; every item of list C of `01-scope-lock`.

## 16. Fact check of external products (CTX-Q-6)

Checked on 12 October 2026.

| # | Claim | Result | Source |
| --- | --- | --- | --- |
| 1 | Anthropic-hosted cloud sessions: Ubuntu 24.04 on x86_64, about 4 vCPU, 16 GB memory, 30 GB disk, repository cloned; OpenJDK 21 with Maven and Gradle, Node.js 20 to 22, Python with pytest, Docker with `docker compose`, PostgreSQL 16; a setup script runs as root, MUST exit 0, and is cached as a filesystem snapshot when it finishes in about 5 minutes; foreground commands wait 2 minutes by default and at most 10, then move to the background for up to 30 more minutes, configurable with `BASH_DEFAULT_TIMEOUT_MS` and `BASH_MAX_TIMEOUT_MS`; `CLAUDE_CODE_REMOTE` is `true` in cloud sessions. | Confirmed. | [Claude Code: Configure cloud environments](https://code.claude.com/docs/en/cloud-environments) |
| 2 | Trusted network access includes GitHub with release assets, Docker Hub, `ghcr.io`, `mcr.microsoft.com`, `public.ecr.aws`, Maven Central, npm, PyPI, `nodejs.org`, Ubuntu mirrors, `*.amazonaws.com`, `api.anthropic.com`; it does not list Debian mirrors, `repo.zabbix.com`, Atlassian, `api.openai.com`, Teams webhook hosts, the Ollama model registry or Playwright's download hosts. SMTP to SES uses port 587, outside the HTTPS proxy, so it is treated as unreachable. | Confirmed (default allowed domains). | Same page, "Default allowed domains" |
| 3 | Cloud sessions clone from GitHub and push branches with the Claude GitHub App; the GitHub proxy refuses GraphQL subcommands (`gh pr`, `gh issue`) and allows `gh api` REST calls. | Confirmed. | Same page, "Work with GitHub issues and pull requests"; [Use Claude Code in the cloud](https://code.claude.com/docs/en/claude-code-on-the-web) |
| 4 | GitHub Free includes 2,000 Actions minutes a month and GitHub Pro 3,000, for private repositories on GitHub-hosted runners. | Confirmed. | [GitHub's plans](https://docs.github.com/en/get-started/learning-about-github/githubs-plans) |
| 5 | The standard GitHub-hosted Linux runner for private repositories has 2 CPU, 8 GB memory and 14 GB SSD; larger runners need GitHub Team or Enterprise Cloud. | Confirmed (re-checked in review pass 2: the table row reads 2 CPU, 8 GB, 14 GB). | [GitHub-hosted runners](https://docs.github.com/en/actions/reference/runners/github-hosted-runners) |
| 6 | Protected branches are available in public repositories on GitHub Free, and in private repositories on GitHub Pro, Team and Enterprise. | Confirmed. | [About protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches) |
| 7 | Eclipse Temurin 25 binaries are published by Adoptium. | Confirmed (announcement); download from GitHub release assets is BP-ASM-01. | [Adoptium: Eclipse Temurin 25 available](https://adoptium.net/news/2025/09/eclipse-temurin-25-available) |
| 8 | GitHub Actions can assume an AWS IAM role through GitHub's OIDC provider and push to Amazon ECR without stored keys. | Confirmed (secondary source, AWS re:Post). Added 18 October 2026 (CTX-D-042): for repositories created after 15 July 2026, the default `sub` claim carries the owner's and the repository's IDs (`repo:{owner}@{owner id}/{repository}@{repository id}:ref:…`), so the role's trust condition uses that form ([GitHub changelog](https://github.blog/changelog/2026-04-23-immutable-subject-claims-for-github-actions-oidc-tokens)). | [AWS re:Post: pushing images to ECR with GitHub Actions](https://www.repost.aws/articles/ARMUXtVzgISX6SQKRlroJheg/setting-up-to-push-container-images-to-ecr-using-github-actions) |
| 9 | The pinned third-party images (Zabbix 7.0, PostgreSQL with pgvector, Prometheus, Alertmanager, Ollama, Mailpit, Ubuntu, Playwright) publish `linux/arm64`; Ubuntu 24.04's archive has the Prometheus node exporter (it has no Zabbix package: refuted on 15 October 2026 by the P-03 readiness test, which read the archive's package indexes; amendment BP-A-17); Docker in cloud sessions and Docker Desktop on macOS run privileged containers with loop devices and XFS; image builds in cloud sessions reach Ubuntu mirrors and PyPI through the session's proxy. | Not confirmed from official pages in this round: BP-ASM-02, BP-ASM-05, BP-ASM-06, BP-ASM-09. | [Docker Hub: zabbix/zabbix-server-pgsql tags](https://hub.docker.com/r/zabbix/zabbix-server-pgsql/tags) |

## 17. Decisions, changes to earlier documents, assumptions

### 17.1 Decisions made in this round

Status "Decided by owner" means Abdul gave the answer himself (Section 2 or the owner review). "Agreed by owner" means Abdul agreed the proposal in the owner review of 12 Oct 2026 (Section 19, CTX-Q-7). "Proposed" means Claude made the decision within the earlier documents; Abdul MAY change any of them through the `00-context` decision log. Items marked "Owner review" are put to Abdul under CTX-Q-7.

| ID | Decision | Reason | Status | Trace |
| --- | --- | --- | --- | --- |
| BP-D-01 | The 40 packages of Section 7 in five phases; risk first: the lab and storm timing (P-03, P-04), the AWS edge with live updates (P-05) and the external services (P-06, P-07) are proved in Phase A, and the connectors that depend on a spike wait for it (BP-R-20). | Storm timing, live updates through the load balancer, Teams and Jira are the demo's largest technical risks; finding a problem after the screens exist would cost a rebuild. Product order follows PM-R-03. | Agreed by owner, 12 Oct 2026 | CTX-C-01; AR §19.3 |
| BP-D-02 | SL-CP-1 is the storm to a resolved incident on the demo installation with screens DC-S01 to DC-S05 and DC-S12 (Section 8.2). | The smallest set that shows the whole primary story on the real hosting. | Agreed by owner, 12 Oct 2026 | SL-O-19 |
| BP-D-03 | Building happens in cloud sessions with Java 25 installed by the setup script; `ci.yml` is the merge gate; spikes that need real services are owner-run steps (BP-R-02, BP-R-03, BP-L-5). | Owner answers 2 and 3; cloud sessions cannot reach Jira, Teams, SES, OpenAI or Ollama models. | Decided by owner (cloud sessions, GitHub); Proposed (the rest) | Owner answers 2, 3 |
| BP-D-04 | One local installation profile, used on the Mac, in CI and in cloud sessions, with dev fakes, the `test` lab profile and installation properties for cookies, bind address and links (Section 11); needs amendments BP-A-01 to BP-A-05, BP-A-09, BP-A-10, BP-A-16 and the SL-B-14 clarification of BP-A-13. | Owner answer 2; the same stack proves the storm end to end on every change without real accounts and before the demo installation exists. | Agreed by owner, 12 Oct 2026 | Owner answer 2 |
| BP-D-05 | Images of the demo installation are built for `linux/amd64` by `release.yml`, pushed to ECR with the GitHub OIDC role under immutable release tags; the owner deploys with `deploy/deploy.sh` through an S3 deployment bucket and Systems Manager (BP-A-06). | The Mac builds arm64 images; the AWS hosts are x86_64; no AWS keys in GitHub; no commit needed for a release. | Agreed by owner, 12 Oct 2026 | AR-H-11 |
| BP-D-06 | Package protocol of Section 6: a spec written in the Claude project and readiness-tested before each package and committed as the branch's first commit; one branch and pull request per package; owner-run steps with result files; Abdul merges; at most two packages at once. | Keeps every package reviewable by one builder and keeps code and documents consistent (CTX-Q-5, CTX-Q-8). | Agreed by owner, 12 Oct 2026 | CTX-C-01; CTX §9 |
| BP-D-07 | `ci.yml` on pull requests only, with a light path for documentation-only changes; `e2e.yml` on label; artifacts only on failure, kept 3 days (Section 10). | Included minutes and artifact storage on personal plans (Section 16 items 4 and 5). | Agreed by owner, 12 Oct 2026 | BP-ASM-07 |
| BP-D-08 | Repository layout and conventions of Section 5, including the root aggregator POM and the test-tooling folders `dev/`, `spikes/`, `tests/` (BP-A-07). | One build command for both Java projects; test tooling kept apart from product and demo. | Proposed | AR-R-02, AR-T-07 |
| BP-D-09 | Module packages build the module core; the primary demo's API layer, with the display reads it needs, is one package (P-25) before the headless acceptance; the deep-dive screens' packages build their own operations and display reads. `vigil-reporting`'s core is built with recovery (P-23). | The headless driver needs the API layer before the screens; the full bundle needs every seed part before P-26. | Agreed by owner, 12 Oct 2026 | DM-R-56; AR §10.2 |
| BP-D-10 | Requirement IDs at the start of test names, checked by `tools/trace.py` (BP-R-10, BP-R-15). | Coverage both ways stays checkable during the build. | Proposed | CTX-Q-1, CTX-Q-8 |
| BP-D-11 | CTX-O-05 (Jazz name and logo) is resolved before P-32, the first deployment of the public demo installation. | The login page shows the customer name before login (AR-B-10); there is no separate branding package. | Proposed | CTX-O-05 |
| BP-D-12 | Spikes are kept as re-runnable scripts under `spikes/`, their recordings under `dev/fakes/recordings/`. | CTX-C-03; the recordings feed the connector tests (AR §19.2). | Proposed | CTX-C-03 |
| BP-D-13 | The repository stays on GitHub Free. `main` is not protected; only the owner merges, and only after `gate` passes (BP-R-07). Included Actions minutes are 2,000 a month. | Owner answer to review item 6: no paid plan; branch protection for private repositories needs a paid plan (Section 16 item 6). | Decided by owner | BP-OT-01 |
| BP-D-14 | The lab, faults, recorder and dev fakes images are based on `ubuntu:24.04`; the product and control panel images copy host-built JARs (BP-R-04, BP-A-08). | Debian mirrors and `repo.zabbix.com` are not reachable from cloud sessions (Section 16 item 2). | Proposed | AR-T-21, AR-X-01 |
| BP-D-15 | Two lab size profiles, `demo` and `test` (BP-R-30, BP-A-05). | A 16 GiB lab does not fit a 14 GB CI runner or share a 30 GB cloud session disk with the images. | Proposed | DD-F-10 |

Owner review items (seven, CTX-Q-7): (1) BP-D-01 with BP-D-09; (2) BP-D-02; (3) BP-D-04 with its amendments (BP-A-01 to BP-A-05, BP-A-09, BP-A-10, BP-A-13, BP-A-16); (4) BP-D-05; (5) BP-D-06; (6) BP-D-13; (7) BP-D-07.

### 17.2 Changes to earlier documents (complete list)

Written into the named documents at the close of this round, 12 Oct 2026: `00-context` v1.10, `01-scope-lock` v1.10, `30-jazz-storyboard` v1.3, `31-demo-data` v1.3, `40-demo-cut` v1.2, `50-architecture` v1.1. None adds an item to list A or list B of `01-scope-lock`; BP-A-13 records a clarification of SL-B-14.

| ID | Document and item | Change |
| --- | --- | --- |
| BP-A-01 | `50-architecture` AR-B-07, AR §12.2 (rows AR-K-04, AR-K-06, AR-K-07, AR-K-08, AR-K-10), AR-H-07; `31-demo-data` DD-F-07, DD-R-05, `tools/check_data.py` (DD-K-16) | AR-B-07's placeholder list drops `jira_site` and adds (complete list): `endpoint.jira` (demo value: the Jira site's address `https://{site name}.atlassian.net`, where `{site name}` is the name the owner chose in BP-OT-06), `endpoint.smtp` (demo `smtp://email-smtp.us-east-1.amazonaws.com:587`), `smtp_starttls` (demo `true`), `endpoint.claude` (demo `https://api.anthropic.com`), `endpoint.openai` (demo `https://api.openai.com`), `endpoint.local_embedding` (demo `http://vigil-embed:11434`). In DD-F-07 the addresses of `jira-demo`, `email-demo`, `claude-api`, `openai-api`, `openai-embed-api` and `local-embed`, and `email-demo`'s `starttls`, become these placeholders; `email-demo` is written in block style with `starttls: ${smtp_starttls}` unquoted. AR-B-07 adds: placeholders are replaced as text before the files are parsed, so a value is inserted exactly as the values file writes it. DD-R-05 and DD-K-16 list them; DD-K-16 also allows `webhook_base_url` in `10-lab-and-faults.yaml` only (BP-A-04). Tested in this round on the workspace copies: `check_data.py` passes (49 checks); a bundle merged with the local values parses and `starttls` is a boolean; a file with `${jira_site}` fails DD-K-16. |
| BP-A-02 | `50-architecture` AR-S-02, AR-S-03, AR-S-12 | The `Secure` attribute of `vigil_session`, `vigil_csrf` and the control panel's session cookie is set by the installation property `vigil.security.secure-cookies` (default `true`). It MUST be `true` on every installation reachable from outside its own host; only a local installation sets it to `false` (BP-R-25). |
| BP-A-03 | `50-architecture` AR-I-02 | The host name of a request is the `Host` header without its port, in lower case. |
| BP-A-04 | `50-architecture` AR-X-02, AR-U-09, AR-U-10, AR §12.3 and §12.4 (lab sending addresses); `31-demo-data` DD-F-10 `monitoring_configuration`, DD-R-05, DD-K-16 | The lab's webhook base address is the lab value `webhook_base_url` from `deploy/values/{installation}.yaml` (demo value `https://demo.${demo_domain}`, so the demo installation is unchanged: webhooks still travel by HTTPS through the load balancer). In a local installation it is `http://demo.vigil.test:8080`. |
| BP-A-05 | `50-architecture` AR-X-01, AR-U-08; `31-demo-data` DD-F-10 `lab_db_server`, DD-K-14 | `lab-db-01` runs as a privileged container that, at every start, creates its XFS image file on a named volume when it does not exist and loop-mounts it at `/data` (the host needs loop devices and XFS support, BP-ASM-06); the systemd mount unit on the main host is withdrawn. Accepted for the demo-only lab on the same grounds as `demo-faults` (AR-U-07). DD-F-10 adds `filesystem_setup` and `size_profiles` (the `test` profile of BP-R-30); the `demo` profile keeps every DD-F-10 value; DD-K-14 adds one check that the `demo` profile equals the existing values and that the `test` profile meets the same injection limits (about 2.85 GiB injected). |
| BP-A-06 | `50-architecture` AR-H-05, AR-H-06, AR-H-11 | `deploy/edge.sh` also creates: the GitHub OIDC identity provider and a role, limited to the repository's `main` branch, that may push to the installation's ECR repositories; ECR tag immutability; the deployment bucket `vigil-{installation}-deploy` (named `vigil-{installation}-deploy-{account id}` by BP-A-21), readable by the main host's instance role; host user data that installs Docker Engine 28 or later with the pinned Compose plugin, the AWS CLI, `chrony` in UTC, and the `DOCKER-USER` rule as a persistent rule. `deploy/build.sh` runs in `release.yml`; `deploy/deploy.sh` transfers the deployment files through the bucket (Section 10.3). |
| BP-A-07 | `50-architecture` AR-R-02, AR-T-20, AR §19.2 | The folders `dev/`, `spikes/` and `tests/` hold test tooling; no product or demo-only unit includes them. End-to-end tests run against the local installation (`deploy/local/compose.yaml`) with the dev fakes and Mailpit as test doubles, and against the demo installation in the owner-run steps of P-32, P-34 and P-40. |
| BP-A-08 | `50-architecture` AR-T-21, AR-X-01, AR-U-07 | The `demo-lab`, `demo-faults`, `lab-zabbix-provision`, recorder, dev fakes and storm driver images are based on `ubuntu:24.04` with Ubuntu's Python 3.12 and a virtual environment `/opt/venv` for PyPI packages, and take Zabbix agent 2 and the Prometheus node exporter from Ubuntu's archive (replacing Debian 12 and `python:3.12-slim-bookworm`). |
| BP-A-09 | `50-architecture` AR-U-01, AR §10.3 | Two installation properties of `vigil-app`: `vigil.http.bind-address` (demo installation: its `vigil-net` address, as AR-U-01; local installation: `0.0.0.0`); `vigil.links.base` (default `https://{host}`; local installation `http://{host}:8080`), used for the links of AR-I-100 to AR-I-102. |
| BP-A-10 | `50-architecture` AR-S-04 | Adds (complete list): `vigil-app` and `vigil-migrate` read their database password from their secret file at start through Spring Boot's configuration tree import, outside `vigil-connectors`; `Secrets` adds `verifyInstallationToken(presented value)` → true or false, constant time (caller: `vigil-api`; AR-Q-09's caller check covers it); in a local installation, secret files are mode 0444 in folders 0555 owned by the user who runs `deploy/local.sh` (BP-R-26). |
| BP-A-11 | `30-jazz-storyboard` SB-T-02 | The driver builds the Acknowledge and Approval links with the forms of `50-architecture` Section 10.3 from the incident and approval request identifiers it reads through `vigil-api` (AR-I-21, AR-I-27), and in a local installation checks them against the links in the dev fakes' recorded messages; Teams and email delivery is checked in a local installation through the dev fakes and Mailpit, and on the demo installation by the owner in the channels and mailboxes, from a checklist the driver writes; it resets through the installation operations and `demo-faults` with the steps of AR-B-03. |
| BP-A-12 | `40-demo-cut` DC-U-04 | Adds: until SL-CP-3, the navigation lists only the screens already built (`60-build-plan` BP-R-22). |
| BP-A-13 | `01-scope-lock` Section 12 and SL-B-14 | SL-O-19 closed by `60-build-plan` Section 8.2. SL-B-14 clarification (no item added): "HTTPS only" applies to every installation reachable from outside its own host; a local installation that is reachable only from its own machine (`60-build-plan` Section 11) serves plain HTTP. |
| BP-A-14 | `00-context` | Records the adoption of this round and its decisions (CTX-D-029 to CTX-D-031); CTX-O-01 "Resolved in: when the senior manager sets a demo date; no build package depends on it"; CTX-O-05 "Resolved in: before P-32 (BP-D-11)"; Round 6 status; Section 9's Build row refers to `60-build-plan` Section 6; Section 9 and decision CTX-D-030 (refining CTX-D-009) state that once the repository is pushed to its private GitHub repository (BP-OT-01), the `main` branch there is the master copy. |
| BP-A-15 | `30-jazz-storyboard` SB-O-03; `31-demo-data` DD-O-03; `50-architecture` AR-O-01 | SB-O-03 and DD-O-03 "Resolved in": P-04 (development values) and P-32 (final values). AR-O-01 "Resolved in": BP-OT-07, before P-07. |
| BP-A-16 | `50-architecture` AR-S-08, AR-S-09 | Both apply to the demo installation. In a local installation (`60-build-plan` Section 11), which is reachable only from its own machine, there is no load balancer and no `DOCKER-USER` rule: browsers, webhooks and `demo-control` reach `vigil-app` by plain HTTP at `http://demo.vigil.test:8080`, and lab containers reach it on `lab-net` through that network alias. |
| BP-A-17 | `50-architecture` AR-X-01; this document BP-R-04, BP-ASM-09, Section 16 item 9, the P-03 entry | Ubuntu 24.04's archive has no Zabbix agent package. The `demo-lab` image copies Zabbix agent 2 of the 7.0 LTS line (the line of the lab's Zabbix server, AR-T-22; the newest tag whose image is built on Ubuntu 24.04, so the binary runs on the `ubuntu:24.04` base) from the official `zabbix/zabbix-agent2` image, pinned by tag and digest, in a build stage; everything else still comes from Ubuntu's archive and PyPI. Written on 15 October 2026 (CTX-D-034). |
| BP-A-18 | `31-demo-data` DD-F-10 `faults.SL-F-1.inject` and `lab_db_server`; `31-demo-data` README | SL-F-1 step (2) says that `df` shows 100 % in the `demo` profile and 99 % in the `test` profile (BP-R-30). `lab_db_server` adds `development_values`: the values that SB-T-01's development run measures on the owner's Mac in the `demo` profile (P-04), each written `measured` until the session "Record the owner-run results of P-04" replaces it; P-32 records the final values in the existing fields (`baseline_used_pct`, `injected_size`, `expected_diagnostics_after_SL-F-1`, `expected_action_result`). Written on 15 October 2026 (CTX-D-035). |
| BP-A-19 | `50-architecture` AR-H-11 | The deployment waits for the one-shot `lab-zabbix-provision` with `docker wait lab-zabbix-provision` instead of `docker compose wait`, which in the pinned Compose lists only running containers and fails when the provision has already ended (found by the P-04 readiness test in Compose 2.40.3's source). Written on 15 October 2026 (CTX-D-035). |
| BP-A-20 | `50-architecture` AR-I-03 | Owner decision CTX-D-039 (16 Oct 2026, for P-09). Adds 500 `internal_error` (an unexpected error; its cause is logged) to the complete list; an unreachable database stays 503 `dependency_failed`. A method that a path does not offer answers 404 `not_found` (no 405). Methods other than GET, HEAD, POST, PUT, DELETE, PATCH and OPTIONS are refused before `vigil-api` runs: TRACE with 405 by the web server, the others with 400 `bad_request` by Spring Security's firewall, after the host check of AR-I-02 (on a host with no alias they answer `unknown_host`). Wrong credentials at login answer 401 `not_signed_in` (AR-I-12). When neither the documents nor the module give a text, `message` is the default text of its code (complete list): `bad_request` "The request is not valid."; `session_ended` "Your session ended. Sign in again."; `not_signed_in` "You are not signed in."; `bad_token` "The token is not valid."; `forbidden` "You do not have permission to do this."; `not_found` "Not found."; `unknown_host` "This address is not set up."; `conflict` "This was changed meanwhile. Reload and try again."; `too_large` "The request is too large."; `refused` "The request was refused."; `invalid` "The request has problems."; `internal_error` "Something went wrong. Try again."; `dependency_failed` "A service is not available. Try again later."; `starting` keeps the body of P-02 without a message. `details` is left out when empty. |
| BP-A-21 | `50-architecture` AR-H-06, AR-H-11 | The deployment bucket is named `vigil-{installation}-deploy-{account id}` (Example: `vigil-demo-deploy-123456789012`), because S3 bucket names are unique across all AWS accounts and `vigil-demo-deploy` could already be taken. Written on 16 October 2026 for P-05 (CTX-D-041). |
| BP-A-22 | `50-architecture` Section 12.1, AR-K-01, AR-K-02 | Owner decision CTX-D-043 (18 Oct 2026, for P-10). "The connection error text" of a monitoring source connection test becomes a complete list of failure reasons, written after the table of Section 12.1: "The address is not a valid http or https address."; "No credential is set."; "Secret {name} is not available."; "No connection to {host}:{port} within 5 s."; "No answer within 10 s."; "Host {host} not found."; "Could not connect to {host}:{port}."; "Connection failed: {message}."; and the kinds' own answer rules (`error.data` or "HTTP {status}"), at most 500 characters, never a secret value. Written on 18 October 2026 (`50-architecture` v1.6). |
| BP-A-23 | `50-architecture` AR-B-05 | Owner decision CTX-D-044 (19 Oct 2026, for P-11). Adds (complete list): (1) at upload (AR-I-96) a bundle is refused with 422 `invalid` when its entries unpack to more than 50 MiB in total, a logo entry has more than 512 KB, or a text entry is not UTF-8; (2) a bundle for a tenant that is not `provisioning` or already has a seed import record is refused with 422 `refused` "A seed bundle can be imported only into a new tenant." (`details.refusal` `tenant_not_new`, DM-E82-R1); (3) "refuses the part" for an unknown top-level section, and also for a data file that cannot be read as YAML, means the first part in DM-R-56 order whose entries include that file, at its turn in the import, whether that part is registered or not (BP-R-29). BP-R-29 is reworded to match. Written on 19 October 2026 (`50-architecture` v1.7). |

### 17.3 Assumptions (complete list)

| ID | Assumption | Verified by |
| --- | --- | --- |
| BP-ASM-01 | `tools/cloud-setup.sh` installs Temurin 25, the pinned Compose plugin, `shellcheck`, `gitleaks` and the Python tools and pulls the test images within the 5-minute cache limit; the script records its own run time (P-01 spec). | P-01 |
| BP-ASM-02 | Image builds by `deploy/images.sh` in cloud sessions (host network, the session's proxy variables, the host CA bundle as a build secret, BP-R-04) reach Ubuntu mirrors and PyPI, and Docker runs Testcontainers with PostgreSQL and pgvector and the lab in the `test` profile within 16 GB of memory and 30 GB of disk. If image builds cannot reach the mirrors, by owner decision: a workflow `images.yml` builds the test-tooling images (lab, faults, monitoring provisioner, recorder, dev fakes, driver; no product code and no secret) on GitHub Actions and publishes them to `ghcr.io` as public packages, and `deploy/images.sh --test` pulls them. | P-02 (Testcontainers), P-03 (image build and lab). Proved on 15 Oct 2026: Testcontainers with PostgreSQL and pgvector in P-02; in P-03 the image build reached Ubuntu's mirrors over HTTPS through the proxy with the CA bundle and PyPI directly (it is in the session's `NO_PROXY`), and the lab ran in the `test` profile (P-03 report Section 8). The `images.yml` fallback is not needed. |
| BP-ASM-03 | Playwright tests run in the pinned `mcr.microsoft.com/playwright` image in cloud sessions, CI and on the Mac, with `node_modules` installed on the host and mounted (BP-R-04). | P-26 |
| BP-ASM-04 | A GitHub-hosted runner for private repositories (2 CPU, 8 GB, 14 GB SSD) runs the local installation in the `test` profile with the storm. If it does not, `e2e.yml` runs only its smaller suites and the storm run moves to cloud sessions. | P-26, P-27 |
| BP-ASM-05 | The pinned third-party images publish `linux/arm64`, so the local installation runs on the Mac. | P-26 (owner-run). Proved on 16 Oct 2026 for the five images of P-04 (PostgreSQL 16, Zabbix server and web, Prometheus, Alertmanager), pulled and run on the Mac (P-04 report Section 8.2). |
| BP-ASM-06 | Docker in cloud sessions and Docker Desktop on the Mac run privileged containers with loop devices and XFS (BP-A-05). | P-03. Proved on 15 Oct 2026 in a cloud session, on a GitHub-hosted runner (`ci.yml` run 38734391944) and on the Mac (Docker Desktop 29.8.2, arm64) (P-03 report Section 8). |
| BP-ASM-07 | The build's CI use of about 3,300 minutes (Section 10.2) spreads over enough months to stay within the included minutes. | Monthly usage report (BP-O-02) |
| BP-ASM-08 | Ollama installed natively on the Mac serves `gemma4:12b` and `embeddinggemma` to the local installation through `host.docker.internal`. Optional: without it the dev fakes stand in. | P-26 (owner-run) |
| BP-ASM-09 | The Zabbix agent 2 program copied from the official `zabbix/zabbix-agent2` 7.0 image runs in the Ubuntu 24.04 `demo-lab` image (P-03) and works with the lab's Zabbix 7.0 server (P-04), and Ubuntu's node exporter exposes the filesystem metrics the Prometheus rules of DD-F-10 use (P-04). | P-03, P-04. P-03 part proved on 15 Oct 2026: agent 2 7.0.25 runs in the image on amd64 and arm64 and answers passive checks (P-03 report Section 8). P-04 part proved on 16 Oct 2026: agent 7.0.25 with server 7.0.31, all 32 items have values, and the node exporter's `/data` metrics drive LA-04 (P-04 report Section 8.3). |
| BP-ASM-10 | `demo-faults` on the host network listens on `172.31.0.1:8091` (the host's address on `lab-net`), is reached there from containers on `lab-net`, and runs commands in lab containers through the mounted Docker socket, in cloud sessions, on GitHub-hosted runners and in Docker Desktop on the Mac (where the host network is that of Docker Desktop's Linux VM). | P-04 (gate; owner-run step). Proved on 16 Oct 2026 in a cloud session, on a GitHub-hosted runner and on the Mac (P-04 report Section 8.3). |
| BP-ASM-11 | The Zabbix 7.0 features that P-04's provisioning uses behave as the P-04 spec uses them (complete list): the trigger function `last`, also with a time shift (`#1:now-20s`; `delta` was removed by the unified expression syntax of Zabbix 6.0, found by the P-04 readiness test); the agent items `vfs.fs.size` and `vfs.file.contents` with a trim preprocessing step on numeric items; a webhook media type whose script sends custom headers with `HttpRequest`; API tokens of a user that is not a Super admin; and the API methods that create these objects. The manual pages could not be read by the fetch tool when the P-04 spec was written (they render by script). | P-04 (gate). Proved on 16 Oct 2026, with the trim characters written `\s\t\r\n` in the API (P-04 report deviation 1, CTX-D-037). |
| BP-ASM-12 | The Deep Learning Base GPU AMI (Ubuntu 24.04) of `us-east-1` brings Docker Engine 28 or later, the NVIDIA container toolkit, the AWS CLI, the Systems Manager agent and `python3`, so the GPU host's bootstrap (P-05) adds only the time source, the Compose plugin and the log settings. | P-05 (the GPU host's bootstrap marker, `deploy/edge.sh`); P-06 (first start of the GPU host with `deploy/gpu.sh`, which checks Docker's version). |
| BP-ASM-13 | The Ubuntu 24.04 server image of `us-east-1` (Canonical's public parameter) brings `snapd` and the Systems Manager agent, and Ubuntu's archive offers `docker.io` 28.0 or later, so the main host's bootstrap (P-05) installs Docker from Ubuntu. | P-05 (the bootstrap marker names the Docker version). |

## 18. Open items

| ID | Item | Owner | Resolved in |
| --- | --- | --- | --- |
| BP-O-01 | Withdrawn (the target demo date is CTX-O-01). | — | — |
| BP-O-02 | What to do if GitHub Actions usage exceeds the included minutes in a month: paid minutes, or more of the checks in cloud sessions. | Abdul | When a monthly usage report shows the limit will be reached |
| BP-O-03 | Exact patch versions of every dependency and image, chosen by the package that introduces it (BP-R-08). | Claude Code | Each package report, starting with P-01 |
| BP-O-04 | Closed by CTX-D-037, 16 Oct 2026 (was: Docker Hub refusing anonymous image requests in cloud sessions; P-04 recorded at least 18 refusals): `tools/start-docker.sh` starts the Docker daemon with the registry mirror `https://mirror.gcr.io` (BP-R-12 step (0)); Docker Hub stays the fallback and the pulls of `deploy/images.sh` keep their retries. Built by P-08. | Claude | P-04 report |

Carried open items still open: CTX-O-01 (no date yet; owner answer 1; BP-A-14); CTX-O-05 (BP-OT-11, before P-32); AR-O-01 (BP-OT-07); SB-O-03 and DD-O-03 (development values recorded by P-04 in DD-F-10 `development_values`; final values P-32).

## 19. Quality-check results

| Check | Result |
| --- | --- |
| BP-Q-N1 Input versions | The repository held `00-context` v1.9, `01-scope-lock` v1.9, `10-product-map` v1.3, `20-domain-model` v1.3, `30-jazz-storyboard` v1.2, `31-demo-data` v1.2, `40-demo-cut` v1.1 and `50-architecture` v1.0 at the start of this round (commit 5cf1027); these are the inputs. |
| CTX-Q-1 Coverage both ways | Done by script on v0.1 and v0.2: every one of the 140 scope lock IDs is in Section 13.1; every BP ID referred to is defined; every ID of an earlier document cited exists. On v0.2 the script also checked that every ID of the families AR-R, AR-I, AR-K, AR-G, AR-S, AR-B, AR-X, AR-H, AR-TM, AR-Q, AR-E, AR-P, AR-W, AR-U, AR-T, DM-R, DM-S, DM-SM, DM-E, the PM-M module responsibilities, PM-S, PM-K, DC-U, DC-S, DC-TX and DC-OUT is assigned to a package in Section 7 (ranges expanded) or listed under cross-cutting items or items with nothing to build (Section 7 introduction). Result: 16 items were unassigned and are now assigned (SL-B-10, AR-R-03, AR-R-07, AR-R-10, AR-R-15, AR-R-28, AR-R-33, AR-P-02, AR-G-31, AR-T-21, AR-T-22, AR-TM-09, AR-U-03, AR-U-04, DM-R-24, PM-R-03); 0 remain (rerun on v0.3). Every one of the 869 IDs of earlier documents that this plan and `CLAUDE.md` cite exists in those documents. Over-building: no package builds anything no earlier document requires; the only additions are build tooling (Sections 5, 10, 11), which `00-context` Section 9 asks this round to define. |
| CTX-Q-2 Consistency | Compared with every earlier document in both review passes; conflicts resolved by the amendments of Section 17.2 (BP-A-01 to BP-A-16), not silently. |
| CTX-Q-3 Scenario walkthrough | Section 14. |
| CTX-Q-4 Independent review | Pass 1, 12 Oct 2026, on v0.1 by a separate Claude reviewer that read only `docs/`: 40 findings (5 High, 25 Medium, 10 Low), resolved in v0.2. High: (1) the headless driver needed API operations built later — API layer package P-25 before the driver; (2) the lab image could not be built in cloud sessions (Debian and Zabbix repositories unreachable) — Ubuntu base and host-built JARs (BP-R-04, BP-A-08, BP-ASM-02); (3) a 16 GiB lab did not fit CI or the cloud disk — size profiles (BP-R-30, BP-A-05); (4) `vigil-app` was not reachable from the lab locally — bind address property (BP-A-09); (5) branch protection needs a paid plan for private repositories — BP-D-13. Medium: local links (BP-A-09); driver timing modes (P-27); P-01 and the cloud environment (P-01 done-check, BP-OT-02, `CLAUDE.md`); placeholder changes written at round close (BP-A-01, BP-A-04); test fixtures allowed under `app/src/test` (BP-R-06); spikes before dependent connectors (BP-R-20, P-06/P-07 split); interim seed import rule (BP-R-29); unassigned items DC-U-10, DC-U-11, DC-U-14, DC-U-16, AR-B-10, DM-R-25, DM-R-26, DM-S-05, DM-S-06 assigned; local SL-F-4 dropped (Section 11.1); one container per fake (Section 11.3); Playwright in its image (BP-ASM-03); minutes estimate and artifact limits (Section 10); release without a commit (BP-W-03); spec as the branch's first commit (BP-L-3); owner-run steps (BP-L-5); deployment file transfer and host bootstrap (BP-A-06); owner tasks for secrets and values (BP-OT-14); SB-T-02 links (BP-A-11); primary-demo backup video at SL-CP-2 (P-34); CTX-O-05 before P-32 (BP-D-11); CTX-O-01 "Resolved in" (BP-A-14); amendments completed (BP-A-07 to BP-A-15); P-03 and P-23 split (now P-03/P-04 and P-26/P-27); gate skips absent compose files and pins Compose (BP-R-12, BP-R-01); fact check extended (Section 16). Low: phase order (Section 8.1); interim navigation amendment (BP-A-12); `CLAUDE.md` amendment wording and MUST wording; stale citations removed; P-06 runs SB-T-03 on the main host; SB-T-09 split (Section 9); "Runs in" aligned; lab secrets in P-03; `shellcheck` installed and cache staleness (BP-R-01). Pass 2, 12 Oct 2026, on v0.2 by the same reviewer: of the 40 pass-1 findings 27 resolved and 13 partly resolved; 16 new findings (1 High, 5 Medium, 10 Low). All resolved in v0.3 except N14, rejected after re-checking the source (the runner has 8 GB, Section 16 item 5). High: `vigil-embed` was deployed only in P-32 but P-06 needs it — added to P-05 (AR-U-03). Medium: P-10 now depends on P-04 and the recorder writes to `dev/fakes/recordings/monitoring/`; Python tools and image packages in virtual environments (BP-R-01, BP-R-04); image builds only through `deploy/images.sh` with proxy settings and CA bundle, tests use local tags, Playwright `node_modules` from the host, public `ghcr.io` fallback (BP-R-04, BP-R-06, BP-ASM-02, BP-ASM-03); `deploy/local.sh e2e` stops the AI fakes on the host (Section 11.3); the driver checks delivery through the dev fakes and Mailpit locally and by owner checklist on the demo installation (P-27, BP-A-11). Low: BP-R-25 and Section 5.1 lists completed; AR-S-08 and AR-S-09 amended (BP-A-16); PM-R-03 listed as cross-cutting; AR-SP-01's lab check moved to P-32; DD-R-05, DD-K-14, DD-K-16 and DD-F-12 README rows amended with the data; storm driver image named (BP-R-04); mode `fast` renamed `immediate` (CTX-W-02); AR-Q-09 covers `verifyInstallationToken` (P-10, BP-A-10); recording environments and the detached 10-storm spike (Section 9, P-04). |
| CTX-Q-5 Claude Code readiness test | Pass 1, 12 Oct 2026, on v0.1 by a fresh Claude given only `docs/` and `CLAUDE.md`: 27 questions, answered in v0.2 (complete list of where): branch protection (BP-D-13); spec commit (BP-L-3); required check name (BP-W-01, BP-OT-01); P-01 environment hand-off and the Java 21 stop rule (P-01, BP-OT-02, `CLAUDE.md`); Java default and `JAVA_HOME` (BP-R-01, BP-OT-02); setup script drift (BP-R-31); tool pins (BP-R-01, BP-R-08); gate order with web checks inside Maven (BP-R-12); absent compose files (BP-R-12); `trace.py` input format (BP-R-15); P-01 and P-02 boundary (P-01); pin timing (BP-R-08); pull requests through `gh api` (BP-R-07); owner-run steps (BP-L-5); lab image build (BP-R-04); lab secrets before P-26 (P-03); local secret permissions (BP-R-26, BP-A-10); Python test conventions (BP-R-10); amendments written before P-01 (Section 17.2); secrets outside connectors and the init script location (BP-A-10, Section 5.1); seed parts before the import framework (P-08, P-10, BP-R-29); lab image in `app` tests (BP-R-06); local links (BP-A-09); driver location and ports (P-27, Section 11); local bundle builder (`deploy/local/bundle.py`); SL-B-14 (BP-A-13); `.gitignore` (P-01). Conflicts in `CLAUDE.md` corrected (reading order includes `00-context`; amendment wording; reachable hosts; `local.sh` commands; web pages allowed for test tooling; data-driven tests; JSON logging from P-02; pull requests). Pass 2, 12 Oct 2026, on v0.2 as P-01 and P-13: all 27 pass-1 questions answered; 9 new questions, answered in v0.3 (complete list of where): `trace.py --check` reads every spec in the working tree, so the current package counts (BP-R-15); cloud session detection by `CLAUDE_CODE_REMOTE` (BP-R-12, BP-E-01); the setup script does not read the repository and pre-fetches no project dependencies (BP-R-01); Python tools in `/opt/vigil-tools` (BP-R-01, `CLAUDE.md`); the "Verify P-01" session writes and commits its result file (P-01, BP-L-5, `CLAUDE.md`); pin date is the spec commit's date (`CLAUDE.md`, BP-R-08); AR-E-14 listeners per module that register scheduled work in P-02's registry (Section 7 introduction, P-13); display reads built with the API operation that uses them (Section 7 introduction, BP-D-09); recorder output location (P-04, P-10). Cloud session limits noted in the plan (BP-E-01): sessions that start on another branch check out the package branch (BP-R-07); the first Maven build of P-01 runs in the background; the cache-time proof of BP-ASM-01 is the verify session (P-01 done-check). |
| CTX-Q-6 Fact check | Section 16. |
| CTX-Q-7 Owner review | Done, 12 Oct 2026. Seven items put to Abdul: (1) package list and order (BP-D-01 with BP-D-09): agree; (2) SL-CP-1 content (BP-D-02): agree, closes SL-O-19; (3) local installation and its amendments (BP-D-04): agree; (4) release and deploy (BP-D-05): agree; (5) package protocol (BP-D-06): agree; (6) GitHub plan (BP-D-13): changed to stay on GitHub Free with `main` unprotected; (7) CI minutes approach (BP-D-07): agree. Recorded as CTX-D-029 to CTX-D-031. |

## 20. Change history

| Version | Date | Change |
| --- | --- | --- |
| 0.1 | 12 Oct 2026 | First draft (Round 6). |
| 0.2 | 12 Oct 2026 | Resolved independent review pass 1 and readiness test pass 1 (Section 19): 40 packages (lab, spikes and local installation split; API layer package added); build environments corrected from the cloud environment documentation; owner-run steps; lab size profiles; local installation properties; amendments BP-A-07 to BP-A-15 added. |
| 0.3 | 12 Oct 2026 | Resolved independent review pass 2 and readiness test pass 2 (Section 19); amendment BP-A-16 added; data amendments BP-A-01, BP-A-04, BP-A-05 applied and tested on working copies. |
| 1.0 | 12 Oct 2026 | Owner review recorded (CTX-D-029 to CTX-D-031): six items agreed, GitHub plan changed to GitHub Free (BP-D-13, BP-R-07, BP-OT-01); amendments BP-A-01 to BP-A-16 written into the earlier documents; Round 6 closed. |
| 1.1 | 14 Oct 2026 | Found while writing the P-01 spec (BP-L-1): BP-R-09 and BP-R-12 step (3) cover every Python file outside `docs/` with `ruff`, and run the pytest tests of `tools/` (the gate's own tools, `trace.py` and `loc.sh`, have tests); BP-ASM-01 no longer says the script pre-fetches dependencies (BP-R-01 says it does not) and is proved by the run time the script records (BP-R-01 and the P-01 done-check (2) amended to match). No behaviour of Vigil changes. |
| 1.2 | 14 Oct 2026 | Found while writing the P-02 spec (BP-L-1) and from the P-01 report: P-02 builds the task scheduler and the per-installation task instead of a per-tenant registry type, because `vigil.shared` holds only the types AR-R-06 lists; each module registers its own per-tenant tasks (P-02 entry, Section 7 introduction). BP-R-07: the pull request is opened with the GitHub tool the session offers (P-01 report, document feedback 1). No behaviour of Vigil changes. |
| 1.3 | 15 Oct 2026 | From the P-02 report (BP-L-8): BP-R-12 adds step (0), which starts the Docker daemon in a cloud session when it is not running (`tools/start-docker.sh`, built by P-03; document feedback 1); Section 5.1 lists the script; the P-03 entry names `deploy/images.sh --test` and the script; open item BP-O-04 (Docker Hub limits, document feedback 2). From the P-03 readiness test: amendment BP-A-17 (Zabbix agent 2 from the official image, newest 7.0 tag built on Ubuntu 24.04; BP-R-04, BP-ASM-09, Section 16 item 9 and `50-architecture` AR-X-01 v1.2); the P-03 entry lists every requirement its spec builds, the `deploy/local.sh` options and BP-ASM-09; BP-OT-10 adds Python 3.12 on the Mac. CTX-D-032, CTX-D-034. No behaviour of Vigil changes. |
| 1.4 | 15 Oct 2026 | From the P-03 report (BP-L-8, CTX-D-035): BP-R-04 mounts the build CA secret readable by apt's `_apt` user (document feedback 3); BP-R-30 lists the `df` percentage after SL-F-1 as size-dependent (document feedback 2); BP-ASM-02 and BP-ASM-06 proved, BP-ASM-09 proved for its P-03 part; BP-O-04 stays open (P-03 recorded no refused pull). For the P-04 spec (BP-L-1): BP-R-12 and BP-R-15 include `spikes/`; BP-R-04 names the storm timing spike's image and the pulls of third-party images with retries; Section 5.1 `spikes/`; Section 11.1 adds the recorder and the spike (compose profiles `lab-only` and `spike`) and BP-R-25 the user of `lab-zabbix-provision` in a local installation; the P-04 entry names what `deploy/local.sh up --lab-only`, the recorder and the spike do and every requirement its spec builds; amendments BP-A-18 (DD-F-10 `development_values`, `df` in the `test` profile) and BP-A-19 (`docker wait` in AR-H-11, `50-architecture` v1.3); BP-R-12 step (3) runs the lab tests only when the branch changes the lab (owner decision CTX-D-036); assumptions BP-ASM-10 and BP-ASM-11. No behaviour of Vigil changes. |
| 1.5 | 16 Oct 2026 | The P-08 entry names every requirement its spec builds and the registry mirror (BP-L-1). From the P-04 report (BP-L-8, CTX-D-037): BP-ASM-05 proved for P-04's images, BP-ASM-09 (P-04 part), BP-ASM-10 and BP-ASM-11 proved; BP-O-04 closed by the owner's decision for a registry mirror in `tools/start-docker.sh` (BP-R-12 step (0), built by P-08). No behaviour of Vigil changes. |
| 1.6 | 16 Oct 2026 | For the P-09 spec (BP-L-1): the P-09 entry names the session cookie, `internal_error`, the test-only permission check, shortened stream timings and the contract test, and every requirement its spec builds; the P-28 entry names the TypeScript types generated from the OpenAPI file (BP-R-13); amendment BP-A-20 (AR-I-03 errors, owner decision CTX-D-039, `50-architecture` v1.4); BP-R-08 chooses a dependency's latest release that needs no newer minor of a library Spring Boot manages (CTX-D-038). P-08 report (BP-L-8, CTX-D-038): no document change. |
| 1.7 | 16 Oct 2026 | For the P-05 spec (BP-L-1, CTX-D-041): the gate's pytest step and `tools/trace.py` cover `deploy/` (BP-R-12, BP-R-15); the P-05 entry's tests (secret directories of AR-ASM-13 in P-32; `deploy/gpu.sh` first run in P-06; gate tests of the scripts); the P-06 entry adds the first GPU host start and the Jira token expiry print; the P-32 entry adds the AR-ASM-13 check; BP-R-04 names the SSE probe's image; Section 10.3 names `secrets.sh`'s installation argument and the uploaded host scripts; P-05 is size L (Section 7.7); amendment BP-A-21 (deployment bucket name with the account ID, `50-architecture` v1.5); assumptions BP-ASM-12 (GPU host image) and BP-ASM-13 (main host image). |
| 1.8 | 18 Oct 2026 | From the P-05 report (BP-L-8, CTX-D-042): Section 5.1 lists `.gitleaksignore`; Section 16 item 8 notes GitHub's OIDC subject with immutable IDs (P-05 amendment 1). No behaviour of Vigil changes. |
| 1.9 | 18 Oct 2026 | For the P-10 spec (BP-L-1): amendment BP-A-22 (connection test failure reasons, owner decision CTX-D-043, `50-architecture` v1.6). P-05 amendment 1 document feedback 1 (`tools/trace.py` reads only part 4): no change (CTX-D-043). No other behaviour of Vigil changes. |
| 1.10 | 19 Oct 2026 | For the P-11 spec (BP-L-1): amendment BP-A-23 (bundle size limits at upload, refusal `tenant_not_new`, which part an unreadable file or unknown section refuses; owner decision CTX-D-044, `50-architecture` v1.7). |
