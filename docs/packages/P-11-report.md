# P-11 — Report: Seed import and installation operations

| Field | Value |
| --- | --- |
| Package | P-11 Seed import and installation operations (spec `docs/packages/P-11.md`) |
| Branch | `p-11-seed-import-installation` |
| Pin date (BP-R-08) | 19 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.9.1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-setup` is in `vigil.setup.api`; every other class is package-private in `vigil.setup`, without `@Profile`. The `@ApplicationModule(allowedDependencies = …)` declarations of `vigil.setup` and `vigil.api` are unchanged; `vigil.setup` uses only `shared`, `tenancy :: api`, `identity :: api`, `connectors :: api` and `audit :: api`. The new classes of `vigil.api` are `@Profile("!migrate")`.

| Part | Requirement IDs |
| --- | --- |
| `vigil.setup.api`: `SeedImports` (`start`, `seedImport`, `failInterrupted`), `SeedImportView`, `PartView`, `SeedImportState`, `PartOutcome` (each enum with `key()`), `Installation` (`createTenant`, `setAlias`) | PM-M22-03, PM-M22-05, DM-E-82 |
| `SeedBundleReader` and `SeedBundle`: the three stages of spec 6.3 — stage A (ZIP read with `ZipInputStream`; 50 MiB unpacked limit; allowed, repeated and missing entries; 512 KB logo limit; strict UTF-8 of the ten text entries), stage B (the placeholder refusal with the exact AR-B-07 message), stage C (`bundle.yaml`); refusals are `INVALID` with `details.problems` | AR-B-05, AR-B-07, BP-A-23 |
| `SeedYaml`: the one Jackson 3 `YAMLMapper` with `STRICT_DUPLICATE_DETECTION`; the parser message (first line of `getOriginalMessage()` and ` (line l, column c)`) | AR-T-15, AR-B-05 |
| `SeedParts`: the entries of AR-B-05, the 17 parts of DM-R-56 with names, modules, entries and sections (spec 6.4), the four ignored sections, the first-reader map, and the file checks (`could not be read`, `not a mapping`, `unknown section`) | DM-R-56, AR-B-05, BP-A-23, BP-R-29 |
| `SeedPartImporter` (with `PartResult` and the `afterCommit` hook) and `SeedPartRegistry` ("two importers for part {n}", "no part {n} in the catalogue") | PM-M22-03, AR-B-11 |
| `SeedKeyCheck` and `SeedKeyChecks` (bean order; a check runs only when its referring and target parts succeeded; no check is registered) | DM-R-60, PM-M22-04, BP-R-29 |
| Adapters of parts 1 to 3 (`TenantSettingsAdapter`, `IdentityAdapter`, `ConnectorsAdapter`) with the shape rules of spec 6.5 (`SeedShapes`); the seed passwords read with `Secrets.readSeedPassword` (a missing secret or a username that cannot name one gives no password, which `IdentityImport` reports) | PM-M22-03, PM-M22-05, DM-R-57, AR-B-11, DM-C-10 |
| `SeedImporter` (`SeedImports`): the checks of `start` in the order of spec 6.7 (`NOT_FOUND`; `REFUSED` "A seed bundle can be imported only into a new tenant." `tenant_not_new`, also on a unique violation; the bundle checks), the record inserted `running` in its own transaction, the worker on `Thread.ofVirtual().name("seed-import-" + id)`; the worker's steps 1 to 4 with no transaction around module calls, each part's success in its own transaction, the failure transaction (record and tenant `failed`, no audit event) and the success transaction (record `succeeded`, the one `configuration_changed` event, tenant `ready`); errors cut to 500 code points; `failInterrupted` for restart step (2) | DM-E-82, DM-E82-R1, DM-SM-16, DM-E08-R3, DM-C-07, DM-C-03, AR-R-31, AR-R-24, AR-B-11, CTX-D-044 |
| `SeedImportRecords`: `setup.seed_import` with `JdbcClient`; every statement filters by `tenant_id` except the two installation-level reads `find` (AR-I-97) and `running` (AR-R-24 (2)) | DM-E-82, PM-R-05 |
| `InstallationService` (`Installation`): `createTenant` with `Actor.module("vigil-setup")`, `setAlias` with `set_by` "installation token" | AR-I-95, AR-I-98, AR-S-07, PM-R-09 |
| Migration `setup/V20261021030935__setup_create_seed_import.sql` (spec 6.2); `table-classes.csv` row `setup.seed_import,stateful,yes` | DM-E-82, DM-V-01, PM-R-01 |
| `vigil.api`: `@InstallationToken`; `AccessInterceptor` checks it first (`Bearer` in any case, one space, a token without spaces; `NOT_SIGNED_IN` or `BAD_TOKEN`; `Secrets.verifyInstallationToken`, constant time) | AR-S-07, PM-M23-02, PM-M23-06 |
| `InstallationController`: AR-I-95 to AR-I-98 as spec 6.8 (path ids in the UUID form, any case; JSON bodies read as trees; multipart field `bundle`) | AR-I-95, AR-I-96, AR-I-97, AR-I-98, SL-A3-03, AR-B-02 |
| `InstallationLogFilter`: one `INFO` line per installation request after the answer, `installation operation {method} {pattern}: {status}` or `(no operation)` | AR-S-07, AR-S-11 |
| `ApiErrors`: `MaxUploadSizeExceededException` → 413 `too_large`, `MultipartException` → 400 `bad_request`, right after the database rule | AR-I-03 |
| `RestartProcedure.step2FailInterruptedSeedImports` calls `SeedImports.failInterrupted()` | AR-R-24 |
| `openapi/vigil-api.yaml`: security scheme `installation`, schemas `NewTenant`, `AliasSet`, `TenantCreated`, `SeedImportStarted`, `SeedImport`, `Alias`, the four operations with the responses of spec 6.10 | AR-I-08, BP-R-13 |
| `app/pom.xml`: `tools.jackson.dataformat:jackson-dataformat-yaml` (version from the Spring Boot BOM); `application.properties`: `spring.servlet.multipart.max-file-size=10MB`, `spring.servlet.multipart.max-request-size=11MB` | AR-T-15, AR-I-96 |
| Test support: `SeedBundles` (`testValues`, `demoEntries`, `demoBundle`, `zip` written by hand with `STORED` entries, `MultipartBody` and `form`); `LogLines` moved to `vigil.testsupport` as a public class (the P-10 tests import it from there) | AR-Q-10, SL-DR-04 |
| `README.md` "Module code": the access annotation line names `@InstallationToken`; the seed part line; the P-11 line of the `vigil-api` interfaces; the interfaces of spec 6.12 in a new list "Interfaces of `vigil-setup` for later packages (P-11)" | P11-DC-02 |

Checked by review (spec part 4): SL-DR-04 (`SeedBundles.demoEntries` reads the nine files of `docs/31-demo-data/` and only replaces `${name}` by its value, as text); AR-T-15 (`SeedYaml.MAPPER` is a Jackson 3 `YAMLMapper`); DM-C-03 (`started_by` and the event's actor are `Actor.module("vigil-setup")`, read in `SeedImportIT` DM-E-82 and DM-E08-R3); DM-C-10 (every adapter passes the import's `TenantId` to its module); DM-V-01 (the `started_by_*` columns and their `CHECK` as `tenancy.tenant_settings.updated_by_*`); DM-V-11 (`ConfigurationArea.SEED_IMPORT`); DC-R-06 (the summary "Seed bundle jazz-demo {Version} imported." compared in `SeedImportIT`); PM-R-01 to PM-R-03 (`SchemaRuleTest`, `ModuleStructureTest` pass; allowed dependencies unchanged); PM-R-04 (no connector type, HTTP client, mail or SSH in `vigil.setup` or the installation handlers); PM-R-05 (`TenantColumnIT` passes with the new row; the two installation-level reads are the ones of spec 6.1); PM-R-06 (proved under DM-E08-R3); PM-R-07 (the four operations are the only way in); PM-R-08 (`SeparationTest` passes; `jazz-demo` and the data appear only in tests); PM-R-09 (records of installation operations carry the module actor `vigil-setup`).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.setup.SeedBundleReaderTest` (new) | AR-B-05, BP-A-23, AR-B-07 |
| `vigil.setup.SeedPartsTest` (new) | DM-R-56 |
| `vigil.setup.SeedImportIT` (new; three apps: main with the recording key check, latch with the part 4 latch importer, failing-check) | AR-Q-10, PM-M22-03, PM-M22-05, AR-B-11, DM-E-82, DM-SM-16, DM-E08-R3, DM-C-07, BP-R-29, AR-B-05, DM-E82-R1, DM-R-57, DM-R-60, PM-M22-04, AR-R-31 |
| `vigil.setup.SeedImportRestartIT` (new) | AR-R-24 |
| `vigil.api.InstallationIT` (new) | AR-I-95, AR-I-96, AR-I-97, AR-I-98, AR-I-03, AR-S-07, PM-M23-06, SL-A3-03, AR-B-02, AR-S-11 |
| `vigil.api.ContractIT` (changed: secrets folder with the installation token and the five seed passwords; check 2 covers `security: [{installation: []}]` ⇔ `@InstallationToken`; 18 scenarios for every (operation, status) of the four operations) | AR-I-08, BP-R-13 |
| `vigil.api.EntryPointTest` (changed: exactly one of the four annotations) | PM-M23-02 |
| `vigil.api.ApiErrorsTest` (changed: the BP-A-20 method adds the two multipart exceptions) | BP-A-20 |
| `vigil.SchemaRuleTest` (changed: lookbehind `(?<![A-Za-z0-9_-])`; the AR-R-07 literal scanner method checks the three data file names and `identity.team`) | AR-R-07 |

`tools/trace.py --check` passes. Unchanged tests that cover this package pass: `ModuleStructureTest`, `PrivilegeIT` and `TenantColumnIT` (with the new row), `SeparationTest`, `SecretCallerRuleTest`, `MigrateIT`, `AppStartIT`, `HostResolutionIT` (its `GET /api/v1/installation/tenants` still answers 404 `not_found`).

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Package-private classes not named in the spec: `SeedYaml` (the one mapper and the parser message), `SeedShapes` (the shape rules shared by the three adapters), `SeedImportRecords` (the record's SQL), `SeedImporter` (implements `SeedImports`, with the worker as its inner class `Run`), `InstallationService` (implements `Installation`); the adapters are named `TenantSettingsAdapter`, `IdentityAdapter`, `ConnectorsAdapter`. | Internal structure only. |
| 2 | `InstallationLogFilter` writes the request's `BEST_MATCHING_PATTERN_ATTRIBUTE` only when Spring mapped the request to a handler method (`BEST_MATCHING_HANDLER_ATTRIBUTE` is a `HandlerMethod`); otherwise `(no operation)`. | The static resource mapping `/**` of `WebApplicationConfiguration` also sets the pattern attribute (to `/**`) for an unknown path under `/api/v1/installation/`, which spec 6.9 wants logged as `(no operation)`. A request refused before handler mapping (a body that is not `multipart/form-data`, a file over 10 MiB) also logs `(no operation)`, as spec 6.9 says. |
| 3 | Stage A rule 4 also reports a name that is not allowed and appears twice, after the allowed names, in ZIP order. | Spec 6.3 gives the order for repeated allowed names only (entry order); "no name appears twice" covers every name. |
| 4 | When several key checks find problems, "the first such part" of spec 6.6 is the referring part with the lowest number (DM-R-56 order). | Spec 6.6 does not say which order "first" means; every referring part with problems becomes `failed` either way. No check is registered in this package. |
| 5 | Test helpers outside `SeedBundles`: `vigil.setup.SeedTests` (secrets, changed bundles, waiting for an import) and the static helpers `InstallationIT.secrets` and `InstallationIT.demoBundle`, which `ContractIT` reuses. | Spec 6.11 gives `SeedBundles` as a complete list; helpers that only the tests of one package need stay in that package. |

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 19 October 2026)

| Item | Version |
| --- | --- |
| `tools.jackson.dataformat:jackson-dataformat-yaml` | 3.1.5, from the Spring Boot 4.1.1 BOM (no version in `app/pom.xml`); it brings `org.snakeyaml:snakeyaml-engine` 3.0.1. Before this package it was on the test classpath only, through `json-schema-validator`. |

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

Assumptions of spec 11:

- Jackson 3's YAML data format (3.1.5): the seven data files read with their values as written for parts 1 to 3 (`null`, integers such as `22` and `-1`, booleans such as `starttls: false`, quoted strings); `STRICT_DUPLICATE_DETECTION` refuses a duplicate key ("Duplicate Object property … (line l, column c)"), so the fallback setting was not needed; read failures carry a location (`SeedBundleReaderTest`, `SeedImportIT` AR-B-05). Seen while testing, not used by parts 1 to 3: a plain `~` reads as the string `~`, not `null` (no data file uses `~`), and `2.00` reads as the number 2.0, whose `asString()` is `2.0` (see follow-up 3).
- Spring's `MaxUploadSizeExceededException` reaches the `@RestControllerAdvice` and a client that sends 10,485,761 bytes reads the 413: holds (`InstallationIT` AR-I-03, `ContractIT`); no `Expect: 100-continue` needed.
- `Thread.ofVirtual().name(…)`: holds (`SeedImportIT` AR-R-31).

## 9. Lines of production code added and changed (P11-DC-01)

`tools/loc.sh origin/main HEAD` prints **1,987** (at most 2,000).

## 10. Gate command result

GATE_RESULT

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: the four operations, the one table, no screen element); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT`, AR-Q-09 `SecretCallerRuleTest` pass; no event listener and no external call entry point added); BP-DC-05 (one migration named as AR-P-05; four OpenAPI operations; the adapters of parts 1 to 3 follow AR-B-11: one module import call each, in its own transaction, handles passed on); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report).

## 11. Follow-up items for later packages

1. P-12 to P-24: a `SeedPartImporter` bean for the package's part in `vigil.setup` (read the sections from `files.get({entry})`, the tree checked at the turn of the file's first reader; shape problems with `SeedShapes`; refuse with `INVALID` "Part {name} refused." and `problems`), its `SeedKeyCheck` beans (referring part, target part, problems), and the part's checks in `SeedImportIT` (the latch importer of `SeedImportIT` holds part 4: P-18, which registers the runner part, moves the latch to a later unregistered part or replaces it).
2. P-13: `afterCommit` of the intake part starts the first connection test of each monitoring source (AR-B-11, DM-SM03-T1).
3. P-19 (part 7) and the package that builds part 17 (`vigil-reporting`): Jackson's YAML reads `2.00` and `72.0` as numbers; a single value's text is `asString()` (`2.0`), so an adapter that needs the decimal as written takes the `JsonNode` (`decimalValue()`) instead of the text.
4. P-20: the checks of `08-runbooks.md` and `09-past-incidents.csv` (the bundle holds their decoded text in `SeedBundle.texts()`).
5. P-26: AR-Q-10 with all 17 parts (BP-R-29 ends). P-26 and P-31: the four installation operations with `Authorization: Bearer {installation-token}`; `bundle.yaml` written as `SeedBundles.demoEntries` writes it (quoted values); poll AR-I-97 until `state` is not `running`; show a 422 refusal's `message` and a failed part's `error` (DC-S12-05).
