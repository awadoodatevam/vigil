# P-10 — Report: Connectors, secrets and monitoring connectors

| Field | Value |
| --- | --- |
| Package | P-10 Connectors, secrets and monitoring connectors (spec `docs/packages/P-10.md`) |
| Branch | `p-10-connectors-secrets-monitoring` |
| Pin date (BP-R-08) | 18 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.8.2) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type is in `vigil.connectors.api`; every other class is package-private in `vigil.connectors`, without `@Profile`. The module's `@ApplicationModule(allowedDependencies = {"shared", "audit :: api"})` is unchanged; the code uses only `shared`, the JDK, Spring and Jackson 3.

| Part | Requirement IDs |
| --- | --- |
| `ConnectorType`, `SettingType`, `CredentialPurpose` (with `fromKey`), `ConnectorKind` (the ten kinds of spec 6.4 in order, with `key()`, `connectorType()`, `settingsSchema()` as an unmodifiable ordered map, `instanceCredential()`, `fromKey`) | DM-E-09, PM-M04-02 |
| `ConnectorInstanceView`, `CredentialReferenceView` (id and purpose only), `AlertStatus`, `IdentityPair`, the sealed `ParsedEntry` (`Normalised`, `Failed`), `ParseResult` (`Parsed`, `Failed`) and `ConnectionTestResult` (`Passed`, `Failed`), with unmodifiable lists and maps | DM-E-10, DM-E-11, AR-K-00a |
| `MonitoringSourceConnector` (`kind`, `parse`, `test`; `test` says "external call: not inside a transaction" in its Javadoc) | PM-M04-01, AR-K-00a, AR-R-10 |
| `MonitoringConnectors`: the registry by kind; a bean of a non-monitoring kind or a second bean of one kind stops the start with the texts of spec 6.5 | PM-M04-02, SL-B-07 |
| `ConnectorService` (`Connectors`): `instance`, `credentialReference`, `parse` (through the registry; not guarded), `testConnection` (guard first, then `NOT_FOUND` "Connector instance {id} not found.", then `IllegalArgumentException` for a non-monitoring kind; Javadoc "external call: not inside a transaction") | PM-M04-04, DM-C-10, AR-R-09, AR-R-10, SL-B-07 |
| `ConnectorRecords`: reads by `tenant_id` and `id`, `settings` read from `jsonb` into a new `JsonNode` on every read | DM-C-10, DM-E-10, DM-E-11 |
| `ConnectorImport` (`ConnectorImporter`, `@Transactional(REQUIRED)`): the eleven rules of spec 6.5 in order, every problem collected, `INVALID` "Part connectors refused." with `{"problems": […]}`; stores credential references then instances (active, `updated_by` module `vigil-setup`, settings as the JSON text of the tree), returns the unmodifiable handle map; reads no secret, writes no audit event, publishes no event | DD-F-07, AR-B-11, DM-E-10, DM-E-11, PM-M04-03, SL-B-14, DM-C-07 |
| `ConnectorsPart` with `CredentialReferencePart` and `ConnectorInstancePart` | AR-B-11 |
| `ZabbixPayloads` / `ZabbixConnector` (spec 6.6; one entry, the whole decoded text as payload, `uuuu.MM.dd HH:mm:ss` strict as UTC, pairs `component` then `host`, fields `event_name`, `host`, `severity`, `tag.{tag}`) | PM-K-01, AR-K-01, AR-T-15 |
| `AlertmanagerPayloads` / `AlertmanagerConnector` (spec 6.6; one entry per element of `alerts`, the payload cut from the decoded text by the character offsets of a Jackson 3 streaming parser over the `String`; title fallback; pairs `component` then `instance`; fields `label.{name}`, `annotation.{name}`) | PM-K-02, AR-K-02, AR-T-15 |
| `JsonBodies`: the common parse rules (strict UTF-8, one JSON object, strings only, `present`), shared by both parsers | AR-K-01, AR-K-02 |
| `HttpSourceConnector` (the abstract base of both beans): the connection test steps of spec 6.6 (guard; kind; address check; credential and secret; request with `.timeout(monitoringTest)`; answer; every reason cut to 500 code points); Zabbix: `POST` JSON-RPC with `Content-Type: application/json-rpc` and `Authorization: Bearer`, a secret that a header cannot hold is "not available" (logged at `WARN` with the name); Alertmanager: `GET /api/v2/status` with `Basic` | AR-K-01, AR-K-02, BP-A-22, AR-R-09, PM-R-04 |
| `ConnectorTimings`, `ConnectorConfiguration.connectorTimings()` (5 s, 10 s) | AR-TM-01 |
| `ConnectorHttp`: one `HttpClient` (HTTP/1.1, connect timeout, `Redirect.NEVER`, `NO_PROXY`), `send` with `sendAsync` and a bounded `get`, `cancel(true)` on timeout and interrupt, closed with the context | AR-T-13, AR-TM-01 |
| `FailureReasons.of` (the five rules of spec 6.8 over the unwrapped cause chain; a message holding a hidden value is replaced by the class name) | BP-A-22 |
| `Secrets` (`FileSecrets`): `verify`, `read`, `readSeedPassword`, `verifyInstallationToken` as spec 6.7; `readNamed` reads the file at each use as strict UTF-8, an empty, missing, unreadable or non-UTF-8 file is "not available" (logged at `WARN` with the name only); `ConstantTimeMatch` (SHA-256 digests compared with `MessageDigest.isEqual`) | AR-S-04, AR-S-05, BP-A-10, AR-S-11 |
| Migration `connectors/V20261018170054__connectors_create_connector_tables.sql` (spec 6.3); the two rows of `table-classes.csv` | DM-E-10, DM-E-11, PM-R-01, AR-P-06 |
| `app/pom.xml`: test scope `org.wiremock:wiremock-standalone` with property `wiremock.version`; `application.properties`: `vigil.secrets.dir` | AR-T-20 |
| Recordings copied unchanged to `app/src/test/resources/recordings/monitoring/` (254 files and the README) | P10-DC-03 |
| Test support: `TestSecrets`; `VigilTestApp.start(database, secretsFolder, extraArgs, configurations)`; `VigilDatabase.appPassword()` (package-private, for `TestSecrets`) | — |
| `README.md` "Module code": the line of spec 6.10, the interfaces of spec 6.11, the new P-13 line | P10-DC-02 |

Checked by review (spec part 4): AR-R-08 (`ConnectorLibraryRuleTest` passes; `java.net.http` only in `vigil.connectors`); AR-R-10 (the Javadoc of `ConnectorService.testConnection` and `MonitoringSourceConnector.test`); AR-T-15 (both parsers use Jackson 3's tree and streaming parser); SL-B-07 (other modules reach a monitoring connector only through `ConnectorService`; the connectors are package-private beans); PM-R-03 and PM-R-02 (`ModuleStructureTest` passes, allowed dependencies unchanged); PM-R-06 and DM-C-07 (no call of `AuditService`); PM-R-01 (`SchemaRuleTest` passes; only the two `connectors` tables are written); PM-R-04 (the connection tests are the only external calls, in `vigil.connectors`); PM-R-05 (every operation takes a `TenantId` except `parse`, `readSeedPassword` and `verifyInstallationToken`; `TenantColumnIT` passes with the two new rows); PM-R-08 (`SeparationTest` passes).

## 2. Tests added

| Class | Layer | Test methods (display names start with) |
| --- | --- | --- |
| `vigil.connectors.ConnectorKindTest` | unit | DM-E-09, AR-TM-01 |
| `vigil.connectors.ZabbixParseTest` | unit | AR-K-01, PM-K-01 |
| `vigil.connectors.AlertmanagerParseTest` | unit | AR-K-02, PM-K-02 |
| `vigil.connectors.MonitoringConnectorsTest` | unit | PM-M04-02, PM-M04-01, AR-K-00a |
| `vigil.connectors.ConstantTimeMatchTest` | unit (with ArchUnit) | AR-S-05 |
| `vigil.connectors.FailureReasonsTest` | unit | BP-A-22 |
| `vigil.SecretCallerRuleTest` | unit (ArchUnit) | AR-Q-09 |
| `vigil.connectors.ConnectorImportIT` | IT | DD-F-07, AR-B-11 (single faults; every fault), DM-E-10, DM-E-11, PM-M04-03, SL-B-14, DM-C-10 |
| `vigil.connectors.SecretsIT` | IT | AR-S-05, AR-S-04, BP-A-10, AR-S-11 |
| `vigil.connectors.ConnectionTestIT` | IT | PM-M04-04, AR-K-01, AR-K-02, AR-T-13, AR-TM-01, BP-A-22, AR-S-11 |
| `vigil.connectors.ExternalCallGuardIT` | IT | AR-Q-04, AR-R-09 |

Test helpers in `vigil.connectors` (test sources): `Recordings` (reads a recording's `body`), `TestParts` (the DD-F-07 part with the placeholder values of spec 7, changed copies, small parts), `LogLines` (the application's ECS lines of captured output and the "as written or JSON-escaped" check).

Unchanged tests that cover this package and pass: `ModuleStructureTest`, `SchemaRuleTest`, `ConnectorLibraryRuleTest`, `PrivilegeIT` and `TenantColumnIT` (with the two new class-table rows), `SeparationTest`, `MigrateIT`, `NoSystemOutTest`. `tools/trace.py --check` passes.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Two package-private classes not named in the spec: `JsonBodies` (the common parse rules of spec 6.6, used by `ZabbixPayloads` and `AlertmanagerPayloads`, no dependencies) and `HttpSourceConnector` (the abstract base of `ZabbixConnector` and `AlertmanagerConnector` holding the connection test steps of spec 6.6; each bean's `test` is the base's `final test`, which calls `TransactionGuard.assertNoTransaction()` first). `ConnectorRecords` (reads) and `ConnectorImporter` (the import) are separate from `Connectors` (the service). | Internal structure only: one copy of the rules both kinds share. No behaviour differs from the spec. |
| 2 | `AlertmanagerPayloads.parse` gives `ParseResult.Failed` "the body is not a JSON object" if the streaming pass does not find as many elements as the tree has. | Defensive only: both passes read the same text, so it cannot happen. |
| 3 | `ConnectorHttp` implements `AutoCloseable`, so Spring closes the client when the context closes. | Spec 6.8 "closed when the context closes". |
| 4 | `VigilDatabase` has a new package-private `appPassword()`. | `TestSecrets.create` writes the database's `vigil-db-app` file into its own folder (spec 6.10) and needs its value. |

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 18 October 2026)

| Item | Version |
| --- | --- |
| `org.wiremock:wiremock-standalone` (test) | 3.13.2 (the latest release on Maven Central on 18 Oct 2026 that is not a pre-release; the 4.0.0 line has only betas, the latest 4.0.0-beta.40 of 7 Oct 2026). It shades its Jetty and Jackson; `com.github.tomakehurst.wiremock.WireMockServer` with `WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort()` works as spec 6.2 says. |

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

Assumptions of spec 11:

- Jackson 3's streaming parser over a `String` reports token starts as character offsets, an object or array ends at its end token's offset plus 1, and after `finishToken()` a scalar ends at `currentLocation().getCharOffset()`: holds (`AlertmanagerParseTest` passes on the 190 recordings, the three-element body with whitespace, the scalar `42`, the escapes `\u0026` and `\"`).
- The JDK HTTP client of Java 25 (Temurin 25.0.4.1+1): a refused connection gives "Could not connect to 127.0.0.1:{port}." (rule 4), `nowhere.invalid` gives "Host nowhere.invalid not found." (rule 3), a 3 s answer delay with a 2 s test timeout gives "No answer within 2 s." (rule 2): `ConnectionTestIT` passes, so `FailureReasons` needed no change.
- `wiremock-standalone` 3.13.2 offers the API of spec 6.2: holds (the build).
- `nowhere.invalid` does not resolve in the cloud session: holds (`ConnectionTestIT`).

## 9. Lines of production code added and changed (P10-DC-01)

`tools/loc.sh origin/main HEAD` prints **1,876** (at most 2,000).

## 10. Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, setup version 1): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 2: `vigil-app` 73 unit tests and 129 `*IT` (61 and 108 before this package; every earlier test passes), `demo-control` 1 and 2; step 3: `ruff` clean, 81 Python tests passed, 1 skipped and 35 deselected (no file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` changed, so the lab tests did not run, CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose file valid; step 7: `trace.py --check` exits 0.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no operation, field, setting or screen element; no OpenAPI entry); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-03 `ConnectorLibraryRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT`, AR-Q-09 `SecretCallerRuleTest` pass; `ExternalCallGuardIT` lists the three entry points; no event listener added); BP-DC-05 (one migration named as AR-P-05; no OpenAPI entry; the part 3 import follows AR-B-11); BP-DC-06 (`README.md`; no command added or changed); BP-DC-07 (this report; no owner-run step). P10-DC-01 holds (1,876). P10-DC-02 holds. P10-DC-03 holds (`diff -r dev/fakes/recordings/monitoring app/src/test/resources/recordings/monitoring` is empty). P10-DC-04: the session part holds, the runner pending.

## 11. Follow-up items for later packages

1. P-11: `ConnectorImport.importPart` (problems in `VigilError.details().get("problems")`; the returned handle map goes to the later parts); `Secrets.readSeedPassword` (`IllegalArgumentException` for a username that cannot name a secret, `DEPENDENCY_FAILED` when the file is missing) and `Secrets.verifyInstallationToken`, whose callers `SecretCallerRuleTest` allows only in `vigil.setup` and `vigil.api`. The seed YAML mapping to `ConnectorsPart` (settings as a `JsonNode` with integers and booleans kept) and the first connection test after the part commits (AR-B-11) are P-11's and P-13's.
2. P-13: `ConnectorService.parse`, `testConnection` (call with no transaction open), `instance`, `credentialReference`, and `Secrets.verify` (only from `vigil.intake`). The stored payload of an entry is `ParsedEntry.payload()`; truncation and limits of stored fields (DM-C-11) are P-13's.
3. P-18: `Secrets.read` (only from `vigil.runner`; purposes `target_diagnostic` and `target_action`).
4. P-19, P-21, P-22: a new connector type follows `MonitoringConnectors` (a registry by kind that refuses a wrong or second bean); each new external call entry point calls `TransactionGuard.assertNoTransaction()` first and is added to the table in `ExternalCallGuardIT`. They MAY reuse `ConnectorHttp` or build their own client (spec 6.8).
5. Every later `*IT` that needs secrets passes `TestSecrets.create(database, values).folder()` to `VigilTestApp.start(database, secretsFolder, extraArgs, configurations)`.
