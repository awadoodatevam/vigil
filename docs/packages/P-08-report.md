# P-08 — Report: Audit, tenancy and identity

| Field | Value |
| --- | --- |
| Package | P-08 Audit, tenancy and identity (spec `docs/packages/P-08.md`) |
| Branch | `p-08-audit-tenancy-identity` |
| Pin date (BP-R-08) | 16 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.8.2, Compose 2.40.3) |
| State | Built; the gate command passes in this session (with the lab tests of P-03 and P-04, because `tools/` changed); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| Migrations `audit/V20261016083659__audit_create_audit_event.sql`, `tenancy/V20261016083700__tenancy_create_tenancy_tables.sql`, `identity/V20261016083701__identity_create_identity_tables.sql`: the 9 tables of spec 6.2 with their columns, types, `CHECK`s, actor checks, unique constraints and indexes, the schema `USAGE` grant and the grants of each table's class; 9 rows added to `table-classes.csv` | DM-E-01 to DM-E-08, DM-E-83, DM-E02-R1, DM-E05-R1, DM-E06-R3, PM-M03-02, SL-B-06 |
| `vigil.audit.api` (`AuditEventType`, `ConfigurationArea`, `AuditEntry`, `AuditRecorded`, `AuditActorFilter`, `AuditPosition`, `AuditQuery`, `AuditEventView`, `AuditPage`, `AuditService` with only `append` and `query`); `JdbcAuditService`: `append` joins the caller's transaction, checks the entry (JSON object details through the application's Jackson 3 `JsonMapper`; `configuration_changed` needs exactly `area`, `before`, `after`), truncates `occurred_at` to milliseconds and returns id and sequence from the identity column; `query` with every filter, newest first (`occurred_at`, `sequence` descending), keyset paging after a position, `total` of all matches | PM-M03-01 to PM-M03-03, DM-E-08, DM-E08-R1, DM-E08-R2, DM-C-07, AR-R-11 |
| `vigil.tenancy.api` (`TenantState`, `TenantView`, `TenantSettingsView`, `TenantLogo`, `TenantSettingsPart`, `AliasRevision`, `TenantAliasChanged`, `TenantSettingsChanged`, `TenantService`, `TenantImport`); `Tenancy`: `createTenant` (`BAD_REQUEST` for the name), `markReady` / `markFailed` with the row lock and the refusals of spec 6.4.2 in their order, `tenant`, `settings`, `logo`; `setAlias` (host name lower-cased and checked, `REFUSED` `tenant_not_ready`, the two-key transaction advisory lock as its own statement, then the next revision, `TenantAliasChanged` published in the transaction); `resolveHost`, `activeTenants`, `isActive`, `publicHostName` (latest `set_at`, then higher revision, then smaller host name in code-point order); the import of part 1 with its problems list | PM-M01-01 to PM-M01-04, DM-E-01, DM-E-02, DM-E-83, DM-SM-01, DM-E02-R1, DM-E83-R1, AR-R-19, AR-E-14, AR-B-11, DM-C-03 |
| `vigil.identity.api` (`Permission`, `UserView`, `TeamView`, `NewSession`, `SessionCheck`, `IdentityPart`, `IdentityService`, `IdentityImport`, `SessionExpiry`); `Passwords` (`Argon2PasswordEncoder(16, 32, 1, 19456, 2)`, 32-byte tokens from one `SecureRandom`, base64url without padding, SHA-256 hash); `Identities`: `login` (empty for an invalid key, a tenant that is not `ready`, an unknown or inactive user, a wrong password; 12 h lifetime), `authenticate` (row lock, expiry at check time, activity written only when at least 60 s old), `logout`, `hasPermission`, `isMember`, `teamMembers` (sorted by `Key` in Java), `user`, `userByUsername`, `team`; `IdentityImporter`: the import of part 2 with every problem of spec 6.5.3 in its order (one text per distinct problem, first appearance in the part), passwords hashed | PM-M02-01 to PM-M02-04, DM-E-03 to DM-E-07, DM-E01-R1, DM-E06-R1 to DM-E06-R3, DM-SM-02, AR-S-01, AR-S-02, AR-S-06, AR-TM-03, AR-B-11, DD-F-01, SL-A3-07, SL-B-13 |
| `SessionSweeps` (`SessionExpiry`, `@Profile("!migrate")`): `matchActiveTenants()` registers `scheduleWithFixedDelay(…, now, 60 s)` per active tenant and cancels the others; the sweep ends the sessions past their limits in its own transaction; `TenantAliasListener` (`@ApplicationModuleListener`, `@Profile("!migrate")`); `RestartProcedure.step6StartScheduledWork()` calls `matchActiveTenants()` after the per-installation task | AR-R-23, AR-R-24, PM-R-11, DM-R-70, AR-E-14, AR-Q-05 |
| `app/pom.xml`: `spring-security-crypto` (Boot BOM) and `bcprov-jdk18on` 1.86 | AR-S-01 |
| `tools/start-docker.sh`: `dockerd --registry-mirror=https://mirror.gcr.io` in both forms | BP-R-12 |
| `README.md`: "The gate command" names the mirror; "Module code" has the two lines of spec 6.6 and the interfaces of spec 6.7; test support `MutableClock` | P08-DC-02 |

`TenantSettingsChanged` (AR-E-13) is defined and published by nothing, as spec part 4 says ("checked by review").

## 2. Tests added

| Class | Layer | Test methods (display names start with) |
| --- | --- | --- |
| `vigil.audit.AuditTypesTest` | unit | DM-E08-R1, DM-E08-R2, PM-M03-02 |
| `vigil.audit.AuditIT` | IT | PM-M03-01, DM-E-08, SL-B-06, AR-R-11, PM-M03-03, DM-C-07, DM-C-10 |
| `vigil.tenancy.TenancyIT` | IT | PM-M01-01, PM-M01-03, DM-E-01, DM-SM-01, DM-C-03, PM-M01-02, DM-E-02, DM-E02-R1, AR-B-11, PM-M01-04 (two methods), DM-E-83, DM-E83-R1, AR-R-19, AR-E-14, SL-B-01 |
| `vigil.identity.IdentityTypesTest` | unit | DM-E06-R1, AR-S-01 |
| `vigil.identity.IdentityIT` | IT | DD-F-01, SL-A3-07, PM-M02-01, DM-E-03, DM-E-04, DM-E-05, DM-E-06, AR-S-06, AR-B-11, DM-E05-R1, DM-E06-R3, DM-C-10, PM-M02-03, PM-M02-04, PM-M02-02, DM-E06-R2, DM-E01-R1, DM-E-07, AR-S-02, AR-TM-03, DM-SM-02, SL-B-01, SL-B-13 |
| `vigil.identity.SessionExpiryIT` | IT | AR-R-23, AR-E-14, AR-Q-05, PM-R-11, DM-R-70, AR-R-24 |

Unchanged tests that now cover the 9 tables and pass: `PrivilegeIT`, `TenantColumnIT`, `SchemaRuleTest`, `ModuleStructureTest`, `AppStartIT`. `tools/trace.py --check` passes.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `SessionExpiryIT` checks AR-E-14 and AR-Q-05 in two methods (the listener called directly; then `DoubleDelivery` with the listener called twice), and PM-R-11 and DM-R-70 in two ordered methods (the move of the host and the sweep of B; then the unchanged session of A). | Spec 7 lists one sequence of checks per pair; one method per ID keeps every ID at the start of a test name (BP-R-10) with the order of spec 7 kept by `@Order`. |
| 2 | A `NOT_FOUND` of `markReady`, `markFailed`, `settings` and `setAlias` carries no message. | The spec names the code and no text; no text was invented. |
| 3 | `setAlias` with an empty or over-long `setBy`, and `login`'s `null` password, are handled as a programming error (`IllegalArgumentException`) and as a non-matching password (empty result). | Spec 6.4.2 gives the limits of `setBy` without an error code (spec 6.1: a value outside its type is `IllegalArgumentException`); spec 6.5.3 lists a password that does not match as an empty result. |

No sequence grant was needed: `vigil_app` inserts into `audit.audit_event` with only `INSERT` on the table (the identity column's sequence is used by the table's owner; `AuditIT` proves it).

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 16 October 2026)

| Item | Version |
| --- | --- |
| `org.springframework.security:spring-security-crypto` | 7.1.1 (managed by the Spring Boot 4.1.1 BOM, `spring-security.version`) |
| `org.bouncycastle:bcprov-jdk18on` | 1.86 (latest release on the pin date, published 11 Sep 2026, Maven Central metadata) |

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

Recorded by this package (spec Section 9):

| Item | Result |
| --- | --- |
| Sequence grant for the identity column | Not needed (Section 3). |
| Time of one Argon2id hash in the cloud session | 47.8 ms per `encode` and 38.5 ms per `matches` (mean of 10 after 3 warm-up runs, `Argon2PasswordEncoder(16, 32, 1, 19456, 2)`, measured while the gate ran in parallel, so an upper value). |
| `docker info --format '{{json .RegistryConfig.Mirrors}}'` | `["https://mirror.gcr.io/"]`: the Docker daemon was not running at the start of this session's test run, and `tools/start-docker.sh` (this package's version) started it with the mirror. |
| Docker Hub refusals | None in this session (`/tmp/dockerd.log` holds no `429`). |

## 9. Lines of production code added and changed (P08-DC-01)

`tools/loc.sh origin/main HEAD` prints **1,909** (at most 2,000).

## 10. Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, setup version 1): steps 0 to 7 pass, last line `== all checks passed`, 741 s in total. Step 2: `vigil-app` 54 unit tests and 80 `*IT` (49 and 28 before this package; the P-01 and P-02 tests pass), `demo-control` 1 and 2; step 3: `ruff` clean, 85 Python tests in 617 s (the branch changes `tools/`, so the lab tests of P-03 and P-04 ran, CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose file valid; step 7: `trace.py --check` exits 0.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it), BP-DC-03, BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-05 with identity's listener in `SessionExpiryIT`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT` pass with the 9 tables), BP-DC-05 (three migrations as AR-P-05; no OpenAPI entry, no registered import part, as spec 5 says), BP-DC-06 (`README.md`; `CLAUDE.md` was updated by the spec commit), BP-DC-07 (this report; no owner-run step) hold; BP-DC-02 holds in the session, the runner pending. P08-DC-01 holds (1,909). P08-DC-02 holds. P08-DC-03: the session part holds, the runner pending.

## 11. Follow-up items for later packages

1. P-09: `TenantService.resolveHost`, `settings`, `logo`; `IdentityService.login`, `authenticate`, `logout`, `hasPermission`, `user` (listed in `README.md` "Module code"). `NewSession.token` is the only place the token appears; the cookie and its `Secure` attribute are P-09's.
2. P-11: `createTenant` throws `BAD_REQUEST` `name: 1 to 100 characters`; `setAlias` throws `REFUSED` with details `{"refusal": "tenant_not_ready"}` (AR-I-98: 422); the import interfaces throw `INVALID` with `{"problems": [...]}` (the part's `error`). The import's one `configuration_changed` event is P-11's (`AuditService.append`, area `seed_import`).
3. P-13, P-14, P-16, P-17, P-20: each adds its own `TenantAliasChanged` listener that matches its work to `TenantService.activeTenants()`, and its call in `RestartProcedure.step6StartScheduledWork()`, as `SessionSweeps` does.
4. Tests that need time to pass use `vigil.testsupport.MutableClock` through a source class with `@Bean @Primary Clock testClock()`.
