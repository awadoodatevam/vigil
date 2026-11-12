# P-26 — Report: Local installation and dev fakes

| Field | Value |
| --- | --- |
| Package | P-26 Local installation and dev fakes (spec `docs/packages/P-26.md`) |
| Branch | `p-26-local-installation` |
| Pin date (BP-R-08) | 12 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the local installation, the smoke suite, the `installation` tests and `down --volumes` pass in this session (Section 8 item 3), and the gate command passes (Section 10). Owner-run steps of spec Section 6.10 are open (Section 8). |

## 1. What was built

No product code is changed (`app/src/main`, `web/src` untouched).

| Part | Requirement IDs |
| --- | --- |
| `deploy/local/compose.yaml` (spec 6.2): `vigil-db` (`VigilPostgres.IMAGE`, init scripts of `deploy/db/init/`, its secrets folder, volume `vigil-db-data`, TCP `pg_isready` every 2 s, `vigil-net` only); `vigil-migrate` (user 10001, `SPRING_PROFILES_ACTIVE=migrate`, after `vigil-db` is healthy, `restart: "no"`); `vigil-app` (user 10001, after `vigil-migrate` completed, the three command arguments of BP-R-25, alias `demo.vigil.test` on `vigil-net` and `lab-net`, port `127.0.0.1:8080:8080` only, no fixed address); the six dev fakes on both networks with their `*.fakes` aliases and a `GET /_fakes/calls` health check; `mailpit` (pinned, alias `mailpit` on both networks, any SMTP login over plain SMTP); `vigil-embed` in the profile `embed` (the image of `deploy/main/compose.yaml`, its line ending `# optional`, health check `ollama list`); `e2e` in the profile `e2e` (`vigil/e2e:local`, the user who runs the script, `tests/e2e/` read-only, the run folder, the `e2e` secrets folder and the state folder). Every new host path is a variable with the default `/nonexistent` and `create_host_path: false`. | BP-R-24, BP-R-25, BP-R-28 |
| `deploy/local.sh` (spec 6.3): `up` with the eight steps (checks and removal of a recorder; JAR and `deploy/images.sh --local` unless `--no-build`, `--test` when an image is missing; the lab's secrets as before, then the folders `vigil-migrate`, `vigil-db`, `e2e` and Vigil's secrets with one value per name; `derived.yaml` and the `embed` profile when `endpoint.local_embedding` is `http://vigil-embed:11434`; the lab without the recorder; Vigil, the fakes and Mailpit with the 300 s wait; the tenant; the closing line); `up --lab-only` unchanged in behaviour (still 10 folders and 21 files); `down` for every profile; `reset`; `logs [--no-follow]`; `e2e [SUITE]` with the `@ai-down` run between `stop` and `start` of the two AI fakes. Python runs with `${VIGIL_TOOLS:-/opt/vigil-tools}/bin/python`, else `python3.12`. | BP-R-24, BP-R-25, BP-R-26, BP-R-28, BP-R-30, AR-S-13 |
| `deploy/values/local.yaml`: the values of Section 11.4 as flat lines; `.gitignore`: `deploy/values/local-real.yaml`. | BP-R-27, BP-R-28, AR-B-07 |
| `deploy/local/make_secrets.py` (the seven kinds, standard library, mode 0600, no trailing newline, nothing printed). | BP-R-26, AR-S-13 |
| `deploy/local/bundle.py` (spec 6.4): the values merge as text, the leftover check with the text of AR-B-07, `bundle.yaml` as `SeedBundles.demoEntries` writes it, ten `STORED` entries in AR-B-05's order with the time 1980-01-01 00:00:00. | AR-B-07, AR-Q-10 |
| `deploy/local/install_tenant.py`: AR-I-95 (`local {UTC time}`), AR-I-96 (multipart field `bundle`), AR-I-97 every 2 s up to 300 s, `seed-import.json`, AR-I-98 for `demo.{demo_domain}`, `tenant.json`; the bearer header read from the token file. | AR-Q-10, BP-R-28 |
| `dev/fakes/` (spec 6.5): `fakes.py` (one program, `FAKE_ROLE` `jira`, `teams`, `claude`, `openai`, `ollama-llm`, `ollama-embed`; port 8000 or 11434; `GET /_fakes/calls`, `GET /_fakes/issues`; one log line per call) and its `Dockerfile` on `ubuntu:24.04` with Ubuntu's Python 3.12. The embedding vectors equal `TestEmbedding.vector` (checked against the Java code for two texts, Section 2). | BP-R-04 |
| `deploy/images.sh --local` (`vigil/vigil-app:local` from `app/`, refused without the JAR; `vigil/dev-fakes:local`; `vigil/e2e:local`); `--test` pulls leave out lines ending `# optional`; the new usage line. `third_party_images` of `deploy/local.sh` uses the same rule. | BP-R-04 |
| `tests/e2e/`: `Dockerfile` (spec 6.6), `package.json`, `package-lock.json` (`npm install --package-lock-only`), `playwright.config.ts`, `tsconfig.json`, `smoke.spec.ts` (four tests tagged `@smoke`). | BP-R-24, BP-R-25, AR-Q-10 |
| `.github/workflows/e2e.yml` without the storm driver step (spec 6.7). | BP-W-02 |
| `README.md` "Local installation" and `CLAUDE.md` "Commands" (BP-DC-06). | — |
| BP-R-29 (spec 6.9): no code change; the smoke suite's AR-Q-10 test shows all 17 parts `succeeded` on the bundle `bundle.py` built. | AR-Q-10 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `tests/e2e/smoke.spec.ts`: "BP-R-24 Vigil answers at demo.vigil.test:8080 inside the compose network", "BP-R-25 login over plain HTTP sets a session cookie without Secure", "AR-Q-10 the bundle built by bundle.py imported with all 17 parts succeeded", "AR-Q-10 the imported tenant answers its branding" (Playwright, `@smoke`) | BP-R-24, BP-R-25, AR-Q-10 |
| `deploy/test_local_script.py` (21 cases: "BP-R-28 one script for every host; local.yaml tracked, local-real.yaml ignored", 12 refused argument lists, `e2e` stop/start around the `@ai-down` run, also after a failure, no stop without `@ai-down` tests, the one pattern with a suite, `logs --no-follow`, `reset` without `state/values-file`, `third_party_images`) | BP-R-28 |
| `deploy/local/test_installation.py` (marker `installation`): "BP-R-26 every unit folder and secret file …", "AR-S-13 each secret name has one value …", "AR-B-04 reset makes a new tenant and moves the alias …" | BP-R-26, AR-S-13, BP-R-28 |
| `deploy/local/test_bundle.py` (7 functions) | AR-B-07 |
| `deploy/local/test_make_secrets.py` (6 functions, 12 cases) | BP-R-26 |
| `deploy/local/test_install_tenant.py` (4 functions) | AR-B-07 |
| `dev/fakes/test_fakes.py` (13 functions, 18 cases: the Dockerfile and imports, the calls list, unknown paths, Jira, Teams, SL-T-1 with and without its parts, SL-T-2, refused tasks per provider, OpenAI, Ollama chat, the embedding vectors against values computed by `TestEmbedding.vector`'s Java code, the role check) | BP-R-04 |
| `deploy/test_images.py` (new: the usage line, `--local` builds the three images from their contexts, `--local` without the JAR, the Playwright base version equals `package.json`, `--test` skips the optional and `e2e` images; `--test` still builds the same six images) | BP-R-04 |

`deploy/local/test_installation.py` runs after `deploy/local.sh up` (spec Section 8 item 3); the gate excludes the marker `installation` as before.

## 3. Deviations from the spec

None changes a behaviour the spec states. Readings and details the spec left open:

1. **`install_tenant.py` and proxies.** It opens its connections without the proxy variables of the environment (a cloud session sets `HTTPS_PROXY`, and `127.0.0.1` would otherwise go to the proxy); `deploy/local.sh` reads the readiness address with `curl --noproxy '*'`. Its tests set the module constants `BASE_URL` and `POLL_SECONDS` in-process; no command-line option was added.
2. **`tenant.json`** holds AR-I-98's answer (`host_name`, `revision`, `tenant_id`, `set_at`) and the tenant's `name`. The alias host is `demo.{demo_domain}` of the values (`demo.vigil.test` with `local.yaml`).
3. **Failed import output.** `The seed import ended {state}.`, then one line `{module}: {error}` per failed part.
4. **Secret copies.** A secret that exists is copied to every folder of its name that lacks it or holds another value (the first folder in the table's order gives the value: `vigil-db` for the database passwords, `vigil-app` for the seed passwords), so AR-S-13's "one value per name" holds also after a manual change.
5. **Arguments.** `up --lab-only` refuses `--values` and `--no-build` (they are not in its usage line); a relative `--values` path is read from the caller's folder; a missing values file stops `up` with `Values file {file} does not exist.`; `e2e` refuses a second suite name. The usage text lists the six usage lines.
6. **Fakes, details the spec leaves open.** Jira: issue ids `10001`, `10002`, …; an unknown issue answers 404 in Jira's error form, an unknown transition id 400; status ids as in the recordings (`10005`, `3`, `10006`). Input token counts are counted on the system text and the user message together. A request that is not one of the role's paths answers 404 `{"error": "not found"}` and is recorded; `/_fakes/` requests are not recorded.
7. **`e2e.yml`.** Pull request triggers `opened`, `synchronize`, `reopened` and `labeled` with the job's condition on the label `e2e`; `VIGIL_UBUNTU_MIRROR` as `ci.yml` sets it; `VIGIL_LOCAL_HOME` set on each step (the `runner` context is not available in a job's `env`).
8. **The smoke test's branding values** (`Techfinity`, `Jazz`) are written in the test, since `docs/` is not mounted into the `e2e` container.
9. **`vigil-embed`'s health check** has a 5 s timeout (`ollama list` can take longer than 2 s while the server starts); Mailpit uses the health check of its image.

## 4. Document feedback (CTX-Q-8)

1. **`vigil-db` health check.** AR-U-01 gives the check `psql … "select 1 from pg_roles where rolname = 'vigil_app'"`; the spec gives `pg_isready -h 127.0.0.1 …` (TCP). Built as the spec says; both pass only after the init script, because the image's first-start server listens only on its socket. AR-U-01 and the local compose file now differ in wording; P-32 may want the same check in both.
2. **`docker compose run` ignores `container_name`.** The `e2e` service has `container_name: e2e` like every other service, but `compose run --rm e2e` names its container `{project}-e2e-run-{id}`; nothing depends on the name.
3. **README notes for P-26** (from P-16 and P-11 reports): the health indicator `correlation` and `management.endpoint.health.show-details` are not in the P-26 spec's complete lists, so `vigil-app` gets only the three arguments of BP-R-25; the note stays for P-27 and P-32.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 12 November 2026)

| Dependency | Version | Where |
| --- | --- | --- |
| `@playwright/test` (npm, with `playwright` 1.65.0) | 1.65.0 | `tests/e2e/package.json`, `package-lock.json` |
| `typescript` (npm) | 7.0.2 | `tests/e2e/package.json`, `package-lock.json` |
| `mcr.microsoft.com/playwright` | `v1.65.0-noble@sha256:9beb546099e11f52d62e505537914870e01a67e0e6b00ae41da4775324e73dbe` (index; `linux/amd64` and `linux/arm64`) | `tests/e2e/Dockerfile` |
| `axllent/mailpit` | `v1.33.1@sha256:aef2aacc11f075ec0fb6874275ee4a421e688e11a96ebb296134d1735c7761ce` (index; includes `linux/arm64`) | `deploy/local/compose.yaml` |

Reused pins (no change): `pgvector/pgvector:0.8.7-pg17@sha256:ac08…` (`VigilPostgres.IMAGE`), `ollama/ollama:0.40.5@sha256:5d3a…` (`deploy/main/compose.yaml`), `ubuntu:24.04@sha256:534b…` (as `dev/recorder/Dockerfile`).

## 7. Values recorded under `docs/`

None yet: `tests/acceptance/results/P-26-mac.txt` is written by the owner-run step (Section 8).

## 8. Owner-run results

Open (spec Section 6.10): on the Mac, `deploy/local.sh up --profile demo`, `deploy/local.sh e2e smoke`, the branding page in a browser and `deploy/local.sh down`, each with `tee` into `tests/acceptance/results/P-26-mac.txt`; then the label `e2e` on the pull request, so that `e2e.yml` runs.

Cloud session run (spec Section 8 item 3), profile `test`:

| Step | Result |
| --- | --- |
| `deploy/local.sh up` (first run: JAR, every image built, lab, Vigil, tenant) | exit 0; `Tenant 01a1cffa-b49b-748c-a3bd-8edc150ae526 is ready at demo.vigil.test.`; `seed-import.json`: `succeeded`, 17 parts, all `succeeded`; `ollama-embed-fake` received the import's `/api/embed` call |
| `deploy/local.sh e2e smoke` | exit 0; `4 passed (1.6s)`: the four `@smoke` tests; no `@ai-down` test exists yet, so the AI fakes were not stopped |
| `pytest -m installation deploy/local` (runs `reset`) | `3 passed` |
| `deploy/local.sh up --no-build` a second time | exit 0 in 19 s; every secret file unchanged; a new tenant |
| `deploy/local.sh down --volumes` | exit 0; no container and no volume of the project left |

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

1,317 (BP-R-05 limit for M 2,000; spec estimate about 1,700).

## 10. Gate command result

GATE_RESULT

## 11. Follow-up items for later packages

- P-27: the fakes and Mailpit are on `lab-net` too; `vigil-app`'s links have the base `http://{host}:8080`; `/_fakes/calls` and Mailpit's API (port 8025, no port published) are read from inside the compose networks; the storm driver step goes into `e2e.yml` after `deploy/local.sh up`.
- P-28: adds the login page and the banner to `tests/e2e/smoke.spec.ts` (tag `@smoke`) and brings the lint of `tests/e2e/`.
- P-29: a ticket link of `jira-fake` (`http://jira.fakes:8000/browse/NOC-n`) does not open in a browser.
- P-31: `demo-control`'s `installation-token` and `control-password` (the folder `secrets/demo-control/` holds only the lab's secrets so far); `control.vigil.test`; the full reset of AR-B-03.
- P-32: may set the Playwright `baseURL` through `VIGIL_BASE_URL`; the `correlation` health details (Section 4 item 3).
