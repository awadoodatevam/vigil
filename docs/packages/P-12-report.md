# P-12 — Report: Service map

| Field | Value |
| --- | --- |
| Package | P-12 Service map (spec `docs/packages/P-12.md`) |
| Branch | `p-12-service-map` |
| Pin date (BP-R-08) | 21 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.9.1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-servicemap` is in `vigil.servicemap.api`; every other class is package-private in `vigil.servicemap`, without `@Profile`. The `@ApplicationModule(allowedDependencies = {"shared", "audit :: api"})` of `vigil.servicemap` and the declarations of `vigil.setup` and `vigil.api` are unchanged.

| Part | Requirement IDs |
| --- | --- |
| `vigil.servicemap.api` (spec 6.4, complete list): `SourceKind`, `ComponentKind`, `Criticality` (each with `key()` and `fromKey`), `IdentityKey`, `Counts`, `GenerationView`, `ServiceView`, `ComponentView`, `Match`, `RelationKind`, `Relation`, `AffectedService`, `ImportResult`, `ServiceMapImported`, `ServiceMapPart`, `ServiceMapImport`, `ServiceMapService` | PM-M07-01 to PM-M07-06, AR-E-04, AR-B-11 |
| `ServiceMapFile` (no database): stage 1 with the fifteen parser messages of BP-A-24 in their order and with the lines of spec 6.7 (snakeyaml-engine `Parse` with default `LoadSettings`, then `Compose`); the scalar values (plain `null`, `Null`, `NULL`, `~` and the empty plain scalar are "empty" for an optional field and the text `null` for a required one); stage 2 with the eight rules of DM-R-55 and the texts of DC-TX-11, collected in rule order, each distinct text once, with the skip rules of spec 6.7 | AR-B-06, BP-A-24, DM-R-54, DM-R-55, DC-TX-11, DM-E24-R1, DM-E27-R1, DM-E28-R1, DM-E28-R2, DD-K-03 |
| `ServiceMapGraph`: the immutable graph of one generation (listed edges plus the implied `runs_on` edges, one edge per pair); `match`, `relation` (shortest path, then first by key order, along edges or reversed edges), `dependents`, `affectedServices`, `target`, `component`, `service`, `services`, `components`; the shortest-path and strongly-connected-group helpers (Kosaraju without recursion) that rule 7 of the file also uses | PM-M07-02 to PM-M07-06, DM-R-04, DM-R-05, DM-R-06, DM-R-07, DM-R-19, DM-E26-R1, DM-E28-R2, DD-K-04 |
| `ServiceMapGraphs`: the cache of at most 64 graphs (least recently used out); every question reads the tenant's current generation number with one query, then the cached graph | DM-R-06 |
| `ServiceMapRecords`: the five tables with `JdbcClient`, every statement with `tenant_id`; `component_keys` written as a JSON array sorted by code points | DM-E-24 to DM-E-28, DM-C-01 |
| `ServiceMaps` (`ServiceMapService`, `ServiceMapImport`): the questions of spec 6.6 (reads with no transaction of their own); `importFile` (spec 6.8: file name check, refusal `INVALID` "The file was not imported." with `problems`, `pg_advisory_xact_lock`, next generation, `file_hash` SHA-256, the rows, one `configuration_changed` event with the DC-TX-10 summary and singular counts, `before` and `after`, and `ServiceMapImported` in the transaction); `importPart` (seed part 5: "service map already exists" and the file's problems, refusal `INVALID` "Part service map refused.", generation 1 by `vigil-setup`, no audit event, no event); one `INFO` line per stored generation with the tenant, generation and counts | PM-M07-01, DM-E24-R1, AR-E-04, DM-C-07, DC-TX-10, AR-B-11, PM-R-06 |
| Migration `servicemap/V20261021080502__servicemap_create_service_map.sql` (spec 6.3); five rows `immutable,yes` in `table-classes.csv` | DM-E-24 to DM-E-28, AR-P-01, AR-P-05, AR-P-06 |
| `vigil.setup.ServiceMapAdapter` (part 5): passes `ServiceMapPart("02-service-map.yaml", bytes)` to `ServiceMapImport.importPart`; counts from the tree P-11 checked | DD-F-02, AR-B-11, BP-R-29 |
| `vigil.setup.ServiceMapTeamCheck` (referring part 5, target part 2): the owner team keys of the stored generation 1, services then components in key order, against `IdentityService.team` | DM-R-60 |
| `vigil.api.ApiWebConfiguration.extendMessageConverters` removes every converter whose supported media types include `application/yaml` | AR-I-01 (CTX-D-045) |
| `app/pom.xml`: `org.snakeyaml:snakeyaml-engine` 3.0.1 | AR-B-06 |
| `README.md` "Module code": the part 5 line under the `vigil-setup` interfaces and the new list "Interfaces of `vigil-servicemap` for later packages (P-12)" | P12-DC-02 |

Checked by review (spec part 4): DM-C-01 (`component_keys` stored as a sorted JSON array, `ServiceMapIT` DM-E-25; names 1 to 100 and values 1 to 200 code points, rule 1); DM-C-04 (the five tables are `SELECT, INSERT` only, `PrivilegeIT`; `ServiceMapIT` DM-E-28 shows generation 1 unchanged after generation 2); DM-C-10 (every statement of `ServiceMapRecords` filters by `tenant_id`; `ServiceMapIT` DM-R-06 uses two tenants); PM-R-01 (`SchemaRuleTest`); PM-R-02, PM-R-03 (`ModuleStructureTest`, allowed dependencies unchanged); PM-R-05 (`TenantColumnIT` with the five rows; every request takes a `TenantId`); PM-R-06 (proved under DM-C-07); PM-R-08 (`SeparationTest`; the demo data appear only in tests).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.servicemap.ServiceMapFileTest` (new) | DD-K-03, DM-R-54, AR-B-06, BP-A-24, DM-R-55, DC-TX-11, DM-E27-R1, DM-E28-R1 |
| `vigil.servicemap.ServiceMapGraphTest` (new) | DM-R-05, DM-R-04, DM-R-07, DM-E28-R2, DD-K-04, PM-M07-03, PM-M07-04, DM-E26-R1, PM-M07-05 |
| `vigil.servicemap.ServiceMapIT` (new) | PM-M07-01, DM-E-24, DM-E-25, DM-E-26, DM-E-27, DM-E-28, DM-E24-R1, DM-R-06, DM-C-07, DC-TX-10, AR-E-04, PM-M07-02, PM-M07-06, PM-M07-05 |
| `vigil.setup.SeedImportIT` (changed as spec 6.11: `RecordingCheck.targetPart()` 6; `registeredPartsImport` expects parts 1 to 3 and 5 `succeeded`; `seedImportRecord` asserts the part 5 counts; `fileChecksRefuseTheirFirstPart` expects part 5 `succeeded` for the unreadable `03-monitoring.yaml`; the main app's `@EventListener` on `ServiceMapImported`) | new: DD-F-02, AR-B-11, DM-R-60, BP-R-29 |
| `vigil.setup.SeedImportRestartIT` (changed as spec 6.11: the second record fails part 5, parts 1 to 3 `succeeded`) | AR-R-24 |
| `vigil.api.InstallationIT` (changed: two new methods) | AR-I-03, AR-I-01 |

`tools/trace.py --check` passes. Unchanged tests that cover this package pass: `ModuleStructureTest`, `SchemaRuleTest`, `PrivilegeIT` and `TenantColumnIT` (with the five new rows), `SeparationTest`, `MigrateIT`, `ContractIT`, `DoubleDeliveryIT`.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `Compose` (item 5 of stage 1) runs with `LoadSettings.builder().setAllowNonScalarKeys(true)`; `Parse` keeps the default `LoadSettings` as spec 6.7 item 3 says. | With the default settings, snakeyaml-engine 3.0.1's composer itself refuses a non-scalar key ("Non scalar key is detected but it is not configured to be allowed."), so a key `[a]` would never reach item 6 and its text `a key must be a single word (line {n})`. The smallest change that keeps the messages of spec 6.7 (spec 11, first row). |
| 2 | Package-private classes not named in the spec: `ServiceMapGraph` (the graph of one generation, which `ServiceMapGraphs` caches; it also holds the shortest-path and strongly-connected-group helpers that rule 7 of the file uses) and `ServiceMaps` (implements `ServiceMapService` and `ServiceMapImport`). | Internal structure only; `ServiceMapGraphTest` tests the graph without a database. |
| 3 | `importFile` with a `null` file name throws `IllegalArgumentException` (spec 6.1); `BAD_REQUEST` "file name: 1 to 255 characters" is for a name of 0 or more than 255 characters. | Spec 6.1: `null` arguments are `IllegalArgumentException` unless Section 6.4 says otherwise; 6.8 gives the length rule only. |

Readings of the spec where it gives no order or name (no behaviour beyond the spec): a value that appears more than once (rule 2, rule 6, rule 7 duplicate) gives its text at the entry where it appears the second time; the rule 1 text of a `component_keys` element names the field `component_keys`; a `YamlEngineException` from `Compose` (none was found for a file that `Parse` accepted) would be reported as the item 3 parser message; a quoted empty scalar (`''`) is not "empty" (spec 6.7 names only plain scalars), so it fails rule 1 for an optional field.

## 4. Document feedback (CTX-Q-8)

None. Observed: snakeyaml-engine's composer refuses non-scalar keys by default (deviation 1); its `Parse` reports a tag only when written (`!!str` as `tag:yaml.org,2002:str`, `!custom` as `!custom`) and the start mark of an anchored node at its anchor; its `Compose` in 3.0.1 has no nesting depth limit (a 60-deep flow sequence composes).

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 21 October 2026)

| Item | Version |
| --- | --- |
| `org.snakeyaml:snakeyaml-engine` | 3.0.1, the latest patch release of the 3.0 line on the pin date (Maven Central lists 3.0, 3.0.1, then 3.1, 3.1.1 and 3.2 of later lines); the version that `jackson-dataformat-yaml` 3.1.5 already brings; pinned in `app/pom.xml` as `snakeyaml-engine.version`. |

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

Assumptions of spec 11: snakeyaml-engine's `Parse` gives events with their anchors, tags written in the file (empty for an untagged node) and start marks, and `Compose` gives nodes with start marks: holds, with deviation 1 (`ServiceMapFileTest`). Removing the YAML converter makes Spring answer `application/yaml` with 415 and `Accept: application/yaml` with 406, both answered 400 `bad_request` in JSON by `ApiErrors`: holds (`InstallationIT` AR-I-01).

## 9. Lines of production code added and changed (P12-DC-01)

`tools/loc.sh origin/main HEAD` prints **1,915** (at most 2,000).

## 10. Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, setup version 1): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 2: `vigil-app` 94 unit tests and 175 `*IT` (77 and 155 before this package; every earlier test passes, `SeedImportIT` and `SeedImportRestartIT` changed only as spec 6.11), `demo-control` 1 and 2; the web build, lint, type check and Vitest pass; step 3: `ruff` clean, 81 Python tests passed, 1 skipped and 35 deselected (no file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` changed, so the lab tests did not run, CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose file valid; step 7: `trace.py --check`.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no API operation, no screen element; the module operations of spec 6.4 only); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT`, AR-Q-09 `SecretCallerRuleTest` pass; no event listener and no external call added); BP-DC-05 (one migration named as AR-P-05; no OpenAPI operation; the part 5 adapter follows AR-B-11: one module import call in its own transaction, no handles); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report).

## 11. Follow-up items for later packages

1. P-16: `ServiceMapService.match` takes the identity pairs in the connector's order; `Match.generation` is the generation to store in DM-E-29; `relation` gives `relation_path`; `affectedServices` and `component` (criticality, `null` ranks below `LOW`, DM-R-18) for the hypothesis and the impact.
2. P-18: register the key check of part 5 against part 4 (`ServiceMapService.components`, `targetKey` against the runner grants; BP-R-29); the latch importer of `SeedImportIT` holds part 4.
3. P-38: the setup screen's import calls `importFile` (its `INVALID` details `problems` are the lines of DC-S07-03) and `currentGeneration`; `vigil-setup` reacts to `ServiceMapImported` (no listener exists yet).
