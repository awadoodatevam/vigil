# P-01 — Report: Repository, toolchain and CI skeleton

| Field | Value |
| --- | --- |
| Package | P-01 Repository, toolchain and CI skeleton (spec `docs/packages/P-01.md`) |
| Branch | `p-01-repo-toolchain-ci` |
| Pin date (BP-R-08) | 14 October 2026 |
| Built by | Claude Code, cloud session in the default environment |
| State | Built; gate command passes in this session; `ci.yml` run and the owner-run steps are listed below |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| Root aggregator `pom.xml` (`vigil:vigil-build`, modules `app` and `demo/control`), Maven Wrapper for Maven 3.9.16, `.gitignore` entries, `ruff.toml`, `pytest.ini`, `README.md` | AR-T-07, BP-R-08, BP-R-09 |
| `app/` (`vigil-app`): Spring Boot 4.1.1 on Java 25 with virtual threads, Spring Modulith, `application.properties`, `VigilApplication` with `@Modulithic(sharedModules = "shared")`, Spotless with google-java-format, Failsafe, `frontend-maven-plugin`, copy of `web/dist/` into `static/` | AR-T-01, AR-T-02, AR-T-03, AR-T-07, AR-T-20, BP-R-09 |
| 23 module packages (`vigil.audit` to `vigil.api`) each with `@ApplicationModule(allowedDependencies = …)` and an `api` sub-package declared `@NamedInterface("api")`; `vigil.shared` as an OPEN module with no dependencies. The dependency lists were generated from the table of `50-architecture` Section 7.1 by a script that parses the document (not committed), so the packages follow the document, not memory. | AR-M-01 to AR-M-23, AR-R-04, AR-R-05, AR-R-06 |
| `vigil.api.WebApplicationConfiguration`: static files from `classpath:/static/`, `index.html` fallback with `Cache-Control: no-cache`, content-hashed files under `/assets/` with `max-age=31536000, immutable`, 404 for a missing `/assets/` file, no fallback below `/api/` and `/actuator/` | AR-W-01 |
| `web/` (`vigil-web`): Vite, React 19, TypeScript 5, Vitest (no test file), ESLint, Prettier, exact versions and `package-lock.json` | AR-T-08, AR-T-20, AR-M-24 |
| `demo/control/` (`demo-control`): empty Spring Boot application in package `democontrol` | AR-R-02, BP-R-06 |
| `tools/cloud-setup.sh` (version 1), `tools/requirements-dev.txt`, `tools/check-all.sh`, `tools/trace.py`, `tools/loc.sh` and their tests | BP-R-01, BP-R-05, BP-R-12, BP-R-15, BP-R-31 |
| `.github/workflows/ci.yml`, job `gate`, actions pinned by commit SHA, `gitleaks` step | BP-W-01, BP-R-14 |

## 2. Tests added

| Test | IDs it proves |
| --- | --- |
| `ModuleStructureTest` (29 tests, `app`) | AR-Q-01, AR-T-03, AR-M-01 to AR-M-23 (one test each), AR-R-04, AR-R-05, AR-R-06, PM-R-03 |
| `ConnectorLibraryRuleTest` (`app`, ArchUnit) | AR-Q-03 |
| `SeparationTest` (4 tests, `app`) | BP-R-06, SL-B-10, AR-R-03, AR-R-02 |
| `WebAppServingIT` (3 tests, `app`) | AR-W-01 |
| `DemoControlApplicationIT` (`demo/control`) | BP-R-06 |
| `tools/test_trace.py` (9 tests) | BP-R-15 |
| `tools/test_loc.py` (1 test) | BP-R-05 |

The expected dependency table in `ModuleStructureTest` is a literal copy of the table in `50-architecture` Section 7.1 (with `shared`), not generated from the code. `tools/trace.py --check` finds a test for each of the 37 requirement rows with proof `test`.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | google-java-format is pinned to **1.36.1**, not the latest 1.37.0. | With `spotless-maven-plugin` 3.10.4 on JDK 25, 1.37.0 fails on every file with `InvocationTargetException` (also with the `--add-exports` flags for `jdk.compiler`). 1.35.0, 1.36.0 and 1.36.1 work. No `.mvn/jvm.config` is needed. |
| 2 | The root POM and both modules have the version `0.0.1-SNAPSHOT`. | The spec names no project version; Maven needs one. |
| 3 | `archunit-junit5` is pinned to 1.5.1 (latest on the pin date) although Spring Modulith 2.1.1 brings ArchUnit 1.4.2. | Spec 6.2 lists `archunit-junit5` and the module-version rule says to take the latest. Maven's nearest-wins rule resolves ArchUnit 1.5.1 for both; the tests pass. |
| 4 | `ConnectorLibraryRuleTest` and `SeparationTest` use JUnit `@Test` methods that call `ArchRule.check(...)` with a `ClassFileImporter`, not `@AnalyzeClasses` and `@ArchTest` fields. | `@DisplayName` cannot annotate a field and the ArchUnit engine ignores it, and `tools/trace.py` reads literal `@DisplayName` strings (BP-R-10). |
| 5 | `vite.config.ts` imports `defineConfig` from `vitest/config`. | So that the `test` key (`environment: "node"`) type checks; the spec asks for both settings in this file. |
| 6 | `ModuleMarker` and `ApiMarker` (spec 6.3 fallbacks) were **not needed**. | `ApplicationModules.of(VigilApplication.class)` detects all 24 modules and the `api` named interfaces with only `package-info.java` in the packages. A throw-away experiment (a class in `audit` using a `tenancy.api` type, a class in `tenancy` using an internal `audit` type, a class in `tenancy` using an `audit.api` type) showed that `verify()` reports the first two and the cycle and accepts the third. The experiment files were removed. |

Not deviations, recorded for completeness: the versions of the Maven plugins that `app` and `demo-control` use (Failsafe, Surefire, Resources, Compiler, Jar, Spring Boot plugin) come from the managed versions of `spring-boot-starter-parent` 4.1.1 and are exact; no `pluginManagement` was added.

## 4. Document feedback (CTX-Q-8)

1. **CLAUDE.md, step 6 of "How you work"**: it says to open the pull request with `gh api`. In this cloud session the `gh` command is not installed and the session offers the GitHub MCP tools instead; the pull request was opened with `mcp__github__create_pull_request`. Suggest saying "the GitHub tool of the session (`gh api` where it exists)".
2. **P-01 spec 6.7 / BP-R-01 and Docker Compose**: the default environment already has Docker Compose v5.6.0. The script installs v2.40.3 into `/usr/local/lib/docker/cli-plugins`, which Docker looks in first, so `docker compose version` reports v2.40.3 after the script. This matches the spec; recorded because AR-T-18 says "Compose v2 release that accepts `gw_priority`" and the environment's own version is newer than the pinned one.
3. **Maven Central rate limit**: a series of about 25 quick metadata requests to `repo.maven.apache.org` from this session returned HTTP 429 ("Your ip has exceeded rate limits", Sonatype) for a few minutes. Later packages that download many artifacts in a fresh environment might see the same; it passed on retry.
4. **`60-build-plan` Section 7.1, P-01 estimate**: `tools/loc.sh` counts only `app/src/main`, `web/src`, `demo/`, `dev/` and `deploy/` (BP-R-05), so the files under `tools/` do not count; the count is 431 against the estimate of about 1,200 in the spec.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 14 October 2026)

| Item | Version |
| --- | --- |
| Eclipse Temurin (JDK, `tools/cloud-setup.sh` and `ci.yml`) | 25.0.4.1+1 (`jdk-25.0.4.1+1`) |
| Docker Compose plugin (`tools/cloud-setup.sh`) | 2.40.3 |
| gitleaks (`tools/cloud-setup.sh` and `ci.yml`) | 8.30.1 |
| ruff, pytest, PyYAML (`tools/requirements-dev.txt` and the here-document in `tools/cloud-setup.sh`) | 0.17.0, 9.1.1, 6.0.3 |
| Maven (wrapper) / `maven-wrapper-plugin` used to generate it | 3.9.16 / 3.3.4 |
| Spring Boot (`spring-boot-starter-parent`, `spring-boot-maven-plugin`) | 4.1.1 |
| Spring Modulith (`spring-modulith-bom`) | 2.1.1 |
| Managed by Spring Boot 4.1.1 and used: Spring Framework 7.0.9, JUnit Jupiter 6.0.3, maven-compiler-plugin 3.15.0, maven-surefire-plugin 3.5.6, maven-failsafe-plugin 3.5.6, maven-resources-plugin 3.5.0, maven-jar-plugin 3.5.1 | as listed |
| ArchUnit (`archunit-junit5`) | 1.5.1 |
| `spotless-maven-plugin` / google-java-format | 3.10.4 / 1.36.1 |
| `frontend-maven-plugin` | 2.0.2 |
| Node.js (in `app/pom.xml`, `ci.yml`) / its npm | 22.23.3 / 10.9.9 (bundled, not set separately) |
| npm: react, react-dom | 19.3.0 |
| npm: typescript | 5.9.3 (the latest 5.x; the latest overall is 7.0.2) |
| npm: vite, @vitejs/plugin-react, vitest | 8.3.4, 6.1.2, 5.0.4 |
| npm: @types/react, @types/react-dom | 19.3.0, 19.3.0 |
| npm: eslint, @eslint/js, typescript-eslint, eslint-plugin-react-hooks, globals, prettier | 10.12.0, 10.0.1, 8.71.2, 7.1.1, 17.13.0, 3.9.10 |
| actions/checkout | v7.0.1 `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| actions/setup-python | v7.0.0 `5fda3b95a4ea91299a34e894583c3862153e4b97` |
| actions/setup-java | v6.0.1 `de7274f081f381c8f8158605e0321c36c376e2e6` |
| actions/setup-node | v7.1.0 `949feb2413d6458794dcd2491c4babbbce0c15c1` |
| actions/upload-artifact | v7.0.2 `cf430e030ddbb5b0abf93d22962f4752f3646cd9` |

The `ci.yml` input `java-version: "25.0.4.1+1"` (four-part Temurin version) could not be checked against `actions/setup-java` in this session, because `api.adoptium.net` is not reachable from it. See section 8.

## 7. Values recorded under `docs/`

None other than this report (spec Section 9).

| Value | Result |
| --- | --- |
| Setup script run time in the default environment (P01-DC-01) | **39 seconds** (`/etc/vigil-cloud-setup.seconds` after `bash tools/cloud-setup.sh` as root in this session; exit 0; `java -version` then shows Temurin 25.0.4.1) |
| `ModuleMarker` / `ApiMarker` fallback needed | No (deviation 6) |
| BP-ASM-01 (setup script within 5 minutes) | Filled by the session "Record the owner-run results of P-01" |

## 8. Owner-run results and what the owner runs

| Step | Who | Result |
| --- | --- | --- |
| P01-DC-02 `ci.yml` job `gate` passes on the pull request | GitHub Actions | See the pull request; the first run also shows whether `java-version: "25.0.4.1+1"` is accepted by `actions/setup-java` |
| BP-OT-02: create the cloud environment `vigil` from `tools/cloud-setup.sh` (setup version 1), Trusted network access, variables `BASH_DEFAULT_TIMEOUT_MS=600000`, `BASH_MAX_TIMEOUT_MS=1800000`, `JAVA_HOME=/opt/temurin-25` | Owner | To do |
| P01-DC-03 session "Verify P-01" on this branch | Owner starts, Claude Code runs | To do; writes `tests/acceptance/results/P-01-verify.txt` |
| Session "Record the owner-run results of P-01" | Owner starts, Claude Code runs | To do; copies the run time into section 7 and answers BP-ASM-01 |

## 9. Lines of production code added and changed (P01-DC-04)

`tools/loc.sh origin/main HEAD` prints **431** (at most 2,000 for size class M).

## 10. Gate command result

`tools/check-all.sh` in this session (default environment after `bash tools/cloud-setup.sh`, `CLAUDE_CODE_REMOTE=true`): steps 1 to 7 pass, last line `== all checks passed`; it ran in about 30 seconds with a warm Maven cache. Java tests: 34 unit tests, 3 `WebAppServingIT` and 1 `DemoControlApplicationIT` tests pass; Python: 10 tests pass; `check_data.py`: 49 checks, 0 failed; `trace.py --check` exits 0. Steps 1a and 6 had nothing to run (no `deploy/images.sh`, no compose file).

## 11. Follow-up items for later packages

1. The frontend plugin logs the Vitest "No test files found" notice at ERROR level although the build passes; P-28 adds the first test file and the notice goes away.
2. P-02 adds the Event Publication Registry, so `AR-T-03` is then complete; it also fills `vigil.shared` and adds the first `Dockerfile` (`app/Dockerfile` copies the JAR, which contains `static/index.html`).
3. Packages that add a `*IT` test class that starts a Spring context should keep it under the pattern `*IT` so that Failsafe (not Surefire) runs it.
4. When a package changes `tools/cloud-setup.sh`, it increases the number in line 2 **and** `SETUP_VERSION` in the same file (both are 1 now), and lists the environment update as an owner-run step (BP-R-31).
5. Every later package that adds an `@ApplicationModule` dependency must change the package-info of the module and the expected table in `ModuleStructureTest` together; the test fails when they differ.
