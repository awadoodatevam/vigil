# P-02 — Report: Platform foundation

| Field | Value |
| --- | --- |
| Package | P-02 Platform foundation (spec `docs/packages/P-02.md`) |
| Branch | `p-02-platform-foundation` |
| Pin date (BP-R-08) | 14 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1) |
| State | Built; the gate command passes in this session; no owner-run steps |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| `vigil.shared`: exactly the 13 public top-level types of spec Section 6.2 (`Ids`, `Key`, `Actor`, `TenantId`, `Timestamps` with the nested `ClockConfiguration`, `CanonicalJson`, `VigilError` with the nested `Code`, `TransactionGuard`, `StepCall` with the nested `StepType` and `WakeReason`, `StepCancel`, `StepAnswer`, `Completed` with the nested `ResultRef`, `Waiting` with the nested `WakeEvent`); no sub-package, no module dependency | AR-R-06, AR-P-01, AR-P-03, AR-R-09, DM-C-01, DM-C-08 |
| `app/pom.xml`: the dependencies of spec 6.1; `finalName` `vigil-app` (JAR `app/target/vigil-app.jar`). `demo/control/pom.xml`: `archunit-junit5` 1.5.1 (test) | AR-T-03, AR-T-04, AR-T-05, AR-T-06 |
| `application.properties` (the complete list of spec 6.3: config tree import of `VIGIL_SECRETS_DIR`, data source as `vigil_app` with `${vigil-db-app}`, Hikari 20, Flyway off, the four settings of AR-R-13, bind address, proxy and Actuator settings, ECS logs, no banner) and `application-migrate.properties` (no web server, `vigil_owner` with `${vigil-db-owner}`, Flyway on, the 23 schemas, `default-schema=platform`, `create-schemas`, 30 connection retries 2 s apart) | AR-U-01, AR-T-04, AR-R-13, AR-S-11, AR-P-05 |
| `VigilApplication.main`: exits with `SpringApplication.exit(context)` when the profile `migrate` is active | AR-U-01 |
| Migration `db/migration/platform/V20261015084943__platform_create_event_publication.sql`: the Spring Modulith 2.1.1 PostgreSQL DDL, qualified with `platform.`, plus `GRANT USAGE ON SCHEMA platform` and `GRANT SELECT, INSERT, UPDATE ON platform.event_publication` to `vigil_app` | AR-R-13, AR-P-04, AR-P-05, AR-P-06 |
| `deploy/db/init/10-vigil-roles.sh` (mode 0755): one `psql` session from a quoted here-document; passwords read inside psql with backquoted `cat` of the secret files; `log_min_error_statement = panic` and `log_statement = 'none'` first; the two roles, the two database grants, `CREATE EXTENSION IF NOT EXISTS vector` | AR-U-02, AR-P-06 |
| `vigil.api` (internal, `@Profile("!migrate")`): `AdvisoryLock` (own `DriverManager` connection, `pg_try_advisory_lock(4835711)`, ERROR line and failed start when held, closed at shutdown); `ReadyGate` (servlet filter, 503 `{"error":{"code":"starting"}}` with `application/json` for every path except `/actuator/health/liveness` until the ready flag is set); `RestartProcedure` (`ApplicationRunner` with the seven step methods in order, one INFO line `restart step {n} done` each; step 3 resubmits every incomplete publication, step 6 registers the per-installation task, step 7 sets the flag; steps 1, 2, 4, 5 are empty with comments naming P-18, P-11, P-17, P-16); `SchedulingConfiguration` (`ThreadPoolTaskScheduler` with 4 threads `vigil-scheduler-`, and `taskExecutor` = `SimpleAsyncTaskExecutor` on virtual threads `vigil-async-`). No `@Scheduled` and no `@EnableScheduling` anywhere | AR-R-01, AR-R-24, AR-R-23, AR-R-28, AR-R-14, AR-TM-08, AR-T-03 |
| `app/Dockerfile`: `eclipse-temurin:25.0.4.1_1-jre-noble` by tag and index digest, copies the JAR only, `USER 10001`, `EXPOSE 8080`, `java -jar`. Not built in this package (spec Section 5) | AR-U-01 |
| `demo/control/src/main/resources/application.properties`: name, ECS console logs, no banner | BP-R-16 |
| `tests/acceptance/log_scan.py` (standard library) | AR-Q-07 |
| Test support `app/src/test/java/vigil/testsupport/`: `VigilPostgres` (one pgvector container per JVM, environment as spec 6.7, init folder and a secrets folder with three random 24-character passwords, files 0444 in a folder 0555, waits for the ready line twice), `VigilDatabase` (`main()`, `fresh()`, migration by running the JAR as `vigil-migrate`, a `postgres` `JdbcClient`), `VigilTestApp`, `DoubleDelivery`, `TableClasses` and `app/src/test/resources/db/table-classes.csv` with the two rows | AR-Q-05, AR-Q-06, AR-Q-08 |
| `README.md`: new sections "Database", "Migrations" (convention of spec 6.3) and "Module code" (conventions of spec 6.10) | AR-R-10, AR-R-15 (convention only; no module operation and no event record exists in this package) |

## 2. Tests added

| Test | IDs it proves |
| --- | --- |
| `vigil.shared.SharedTypesTest` (5) | AR-R-06 (exact 13 types; step protocol keys and checks), AR-P-01, DM-C-01, DM-C-08 |
| `vigil.shared.CanonicalJsonTest` (3) | AR-P-03 (RFC 8785 Section 3.2.3 sorting example, the 24 finite numbers of Appendix B, hash form and key-order independence) |
| `vigil.shared.TransactionGuardTest` | AR-R-09 |
| `vigil.shared.ErrorCodeTest` | AR-R-06 (the 13 codes of AR-I-03) |
| `vigil.SchemaRuleTest` (4) | AR-Q-02, AR-R-07 (also a self-test of the literal scanner), AR-P-02 |
| `vigil.NoSystemOutTest`, `democontrol.NoSystemOutTest` | BP-R-16 |
| `vigil.MigrateIT` (6) | AR-U-01, AR-P-04, AR-P-05, AR-T-05, AR-T-06, AR-U-02 |
| `vigil.api.AppStartIT` (5) | AR-R-24, AR-T-04, AR-R-28, AR-R-23, AR-TM-08 |
| `vigil.api.AdvisoryLockIT` | AR-R-01 |
| `vigil.api.EventRegistryIT` (3) | AR-R-13, AR-T-03, AR-R-14 |
| `vigil.PrivilegeIT` (4) | AR-Q-06, AR-P-06, DM-C-05, DM-C-04 |
| `vigil.TenantColumnIT` (2) | AR-Q-08, DM-C-02 |
| `vigil.DoubleDeliveryIT` (2) | AR-Q-05 |
| `vigil.JsonLogIT` (2) | AR-S-11, BP-R-16 |
| `democontrol.DemoControlApplicationIT` (new method) | BP-R-16 |
| `vigil.WebAppServingIT` (rewritten on `VigilTestApp`, checks unchanged) | AR-W-01 |
| `tests/acceptance/test_log_scan.py` (5) | AR-Q-07 |

`tools/trace.py --check` passes: every row of part 4 with proof `test` has a test that names its ID.

Checked once in this session with a throw-away test (not committed): inside the container `/run/secrets/vigil` is mode 0555 with three files of mode 0444, `/docker-entrypoint-initdb.d/10-vigil-roles.sh` is 0755, and none of the three passwords occurs in the database container's log; the log shows `CREATE ROLE` twice, `GRANT` twice and `CREATE EXTENSION`.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `AppStartIT` reads the scheduler's pool size as `getScheduledThreadPoolExecutor().getCorePoolSize()`. | `ThreadPoolTaskScheduler.getPoolSize()` returns the number of threads created so far (3 at that moment), not the configured size. |
| 2 | `AppStartIT` checks "the scheduler holds the resubmission task with fixed delay 60 s" by a test-only `BeanPostProcessor` that wraps the `ThreadPoolTaskScheduler` bean in a class proxy recording calls of `scheduleWithFixedDelay(Runnable, Instant, Duration)`; the test then runs the recorded task and sees it call `resubmitIncompletePublicationsOlderThan(60 s)`. | The JDK's scheduled tasks do not expose their delay, so the registration is observed instead. The proxy is a subclass, so the pool-size and thread-name checks read the same bean. |
| 3 | `RestartProcedure.resubmitOldPublications()` (package-private) is the per-installation task; `EventRegistryIT` calls it directly and lives in package `vigil.api` (as do `AppStartIT` and `AdvisoryLockIT`). | Spec 7 says the task is "called directly" in `EventRegistryIT`; package access keeps the class internal. |
| 4 | The test wrapper of `IncompleteEventPublications` also implements `resubmitIncompletePublications(ResubmissionOptions)`. | Spring Modulith 2.1.1's interface has this third abstract method; the wrapper passes it on. |
| 5 | `Ids.newId()`: after the counter passes 4095 and the millisecond is increased, the counter starts again at a random value below 2048. | The spec says the millisecond is increased; a new millisecond starts its counter as every new millisecond does. Ids stay strictly increasing. |
| 6 | `VigilDatabase` also offers `appDataSource()`, `appSecretsFolder()`, `jdbcUrl()` and `javaExecutable()`; `VigilTestApp` offers `awaitStarted()` and `baseUrl()`. | Used by `DoubleDeliveryIT`, `JsonLogIT`, `AdvisoryLockIT` and the app tests; test code only. |
| 7 | Test configuration classes passed to `VigilTestApp` (in `AppStartIT`, `EventRegistryIT`) carry no `@Configuration` and the test listener no `@Component`. | `@SpringBootApplication` scans the package `vigil` in the test JVM, test classes included; an annotated test configuration would be picked up by every other `VigilTestApp`. The classes are passed as sources, where `@Bean` methods work without the annotation. |

## 4. Document feedback (CTX-Q-8)

1. **Docker in the cloud session (CLAUDE.md "Environment notes")**: in this session the Docker daemon was not running (`/var/run/docker.sock` missing although the client is installed). It was started by hand with `dockerd` (as root) and then worked for every test. If this repeats, the setup script or the environment could start the daemon, or CLAUDE.md could say how to start it.
2. **Docker Hub limits**: one anonymous manifest request to Docker Hub (`eclipse-temurin`) was answered `429 Too Many Requests`; it passed a few minutes later. Every `*IT` pulls `pgvector/pgvector` and Testcontainers' `testcontainers/ryuk` from Docker Hub. `tools/cloud-setup.sh` has an empty `TEST_IMAGES` list; adding the pgvector image (and the Ryuk image Testcontainers 2.0.5 uses) there in a later package that may change the setup script would make sessions independent of the limit at test time.
3. **AR-R-13 DDL**: `spring-modulith-events-jdbc` 2.1.1 ships two PostgreSQL schema sets: `schemas/v1/` (the legacy structure, six columns) and `schemas/v2/` (the current structure, adding `status`, `completion_attempts`, `last_resubmission_date`). With `spring.modulith.events.jdbc.use-legacy-structure` unset (default `false`) 2.1.1 uses v2, so the migration copies `org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql`.
4. **ECS log form**: Spring Boot 4.1.1 writes the level as the nested object `"log":{"level":…,"logger":…}`, as spec 7 expects; a failing `@ApplicationModuleListener` call is logged by Spring Modulith at level INFO ("Leaving event publication uncompleted").
5. **AR-Q-08 and DM-C-02 today**: no tenant-scoped table exists yet, so `TenantColumnIT`'s tenant check has no row to check; it reads the class table and the database and starts checking with the first `yes` row (P-08).

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 14 October 2026)

| Item | Version |
| --- | --- |
| `spring-boot-starter-data-jdbc`, `spring-boot-starter-flyway`, `spring-boot-starter-actuator` | 4.1.1 (Spring Boot BOM); Spring Data JDBC / Relational 4.1.1 |
| Flyway (`flyway-core` through the starter, `flyway-database-postgresql`) | 12.4.0 (Spring Boot BOM) |
| PostgreSQL JDBC driver `org.postgresql:postgresql` | 42.7.13 (Spring Boot BOM) |
| HikariCP | 7.0.2 (Spring Boot BOM) |
| `spring-modulith-starter-jdbc` (with `spring-modulith-events-jdbc`, `-events-jackson`) | 2.1.1 (Spring Modulith BOM) |
| `io.github.erdtman:java-json-canonicalization` | 1.1 (the latest release; no newer one exists) |
| `org.testcontainers:testcontainers` (test) | 2.0.5 (Spring Boot BOM; same artifact name as spec 6.1) |
| `com.tngtech.archunit:archunit-junit5` in `demo/control` (test) | 1.5.1 (as `app`) |
| Jackson 3 (`tools.jackson.core:jackson-databind`, used by `JsonLogIT`, already on the classpath through the web starter) | 3.1.5 (Spring Boot BOM; not added) |
| Image `pgvector/pgvector` (test support) | `0.8.7-pg17@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d` (latest `0.8.x-pg17` tag on the pin date, pushed 1 Oct 2026; OCI index digest for `linux/amd64` and `linux/arm64`) |
| Image `eclipse-temurin` (`app/Dockerfile`) | `25.0.4.1_1-jre-noble@sha256:d9a39a23634650173f1e2bbc176227af9728587ecf0f4b62d53e9355cd7a19ab` (JRE, Ubuntu Noble, the Temurin release of `tools/cloud-setup.sh`; OCI index digest) |
| Event Publication Registry DDL resource copied | `org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql` from `spring-modulith-events-jdbc-2.1.1.jar` |

Every artifact name of spec 6.1 exists as written for Spring Boot 4.1 and Testcontainers 2.x; no renamed starter was needed.

## 7. Values recorded under `docs/`

None other than this report (spec Section 9).

## 8. Owner-run results

The spec lists no owner-run step. The `ci.yml` job `gate` runs on the pull request (BP-DC-02).

## 9. Lines of production code added and changed (P02-DC-02)

`tools/loc.sh origin/main HEAD` prints **704** (at most 2,000).

## 10. Gate command result

`tools/check-all.sh` in this session (`CLAUDE_CODE_REMOTE=true`, setup version 1): steps 1 to 7 pass, last line `== all checks passed`. Java: 49 unit tests and 28 `*IT` tests in `vigil-app`, 1 unit test and 2 `*IT` tests in `demo-control`; Python: 15 tests; `check_data.py`: 49 checks, 0 failed; `trace.py --check` exits 0. Steps 1a and 6 had nothing to run (no `deploy/images.sh`, no compose file). The Maven build takes about 3 minutes with a warm cache, most of it the `*IT` tests.

Done-check: P02-DC-01 holds (`MigrateIT` and `AppStartIT` pass: `vigil-migrate` then `vigil-app` start against the pgvector container with the two roles and their passwords as secret files); P02-DC-02 holds (704); P02-DC-03 holds (`README.md` "Migrations" and "Module code").

## 11. Follow-up items for later packages

1. Every later `*IT` that starts `vigil-app` uses `VigilTestApp.start(VigilDatabase.fresh(), …)`; extra configuration classes are passed as sources and MUST NOT carry `@Configuration`, `@Component` or other stereotypes (deviation 7). `VigilTestApp.awaitStarted()` waits for the restart procedure; `port()` is available before it.
2. A package that adds a table adds its row to `app/src/test/resources/db/table-classes.csv`; `PrivilegeIT` fails until it does. P-08 adds `tenancy.tenant` and `tenancy.tenant_alias` with `tenant_scoped = no`.
3. Restart steps 1, 2, 4 and 5 of `vigil.api.RestartProcedure` are empty slots for P-18, P-11, P-17 and P-16; step 6 is where modules register the tasks of the active tenants with Spring's `TaskScheduler`.
4. Listener tests: obtain the listener bean's target with `AopTestUtils.getUltimateTargetObject` (listeners are proxied for `@Async`) and use `DoubleDelivery.assertSameResult` with the module's schema.
5. P-27 runs `tests/acceptance/log_scan.py --log {log} --secrets-dir {secrets}` on the end-to-end log (AR-Q-07).
6. The first package that builds the `vigil-app` image (`deploy/images.sh`, P-03 or later) records the result of building `app/Dockerfile`.
7. Document feedback items 1 and 2 (Docker daemon, Docker Hub limits) concern the cloud environment.
