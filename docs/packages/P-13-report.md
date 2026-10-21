# P-13 — Report: Intake

| Field | Value |
| --- | --- |
| Package | P-13 Intake (spec `docs/packages/P-13.md`) |
| Branch | `p-13-intake` |
| Pin date (BP-R-08) | 21 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1+1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-intake` is in `vigil.intake.api`; every other class is package-private in `vigil.intake`. The `@ApplicationModule(allowedDependencies = {"shared", "tenancy :: api", "connectors :: api", "audit :: api"})` of `vigil.intake` and the declarations of `vigil.setup` and `vigil.api` are unchanged. Only `SourceHealthWork` and `IntakeAliasListener` carry `@Profile("!migrate")` (and, in `vigil.api`, `WebhookController` like the other controllers).

| Part | Requirement IDs |
| --- | --- |
| `vigil.intake.api` (spec 6.4, complete list): `MonitoringKind`, `AlertStatus`, `Health`, `TestOutcome` (each with `key()` and `fromKey`), `IdentityPair`, `AlertView`, `SourceView`, `AlertTypeView`, `SourceTestResult`, the events `AlertReceived`, `AlertStateChanged`, `SourceHealthChanged`, `IntakePart` (with `SourcePart`, `AlertTypePart`, `MappingPart`), `IntakeImport`, `IntakeScheduling`, `IntakeService` | PM-M06-01 to PM-M06-07, AR-E-01, AR-E-02, AR-E-03, PM-I-07 |
| Migration `intake/V20261021133920__intake_create_intake_tables.sql` (spec 6.3): the nine entity tables and `intake.alert_counter` with their `CHECK`s, `UNIQUE (tenant_id, source_key)`, `UNIQUE (tenant_id, webhook_token_ref_id)`, the partial unique index `alert_firing_idx` on `(tenant_id, source_id, alert_key) WHERE status = 'firing'`, the indexes of spec 6.3 and the grants of AR-P-06; ten rows in `table-classes.csv` | DM-E-15 to DM-E-23, AR-R-12, AR-P-06, DM-C-04, SL-B-14 |
| `AlertTypeRule.classify` (pure): the first mapping of the kind in ascending `order` whose `equals`, `starts_with` or `contains` comparison is true, case-sensitive; a missing field is false; else `unclassified` | DM-E17-R1, PM-M06-04 |
| `HealthRule.evaluate` (pure): the rule of DM-R-40 and the cause of a change (`contact`, `contact_age`, `test_failed`) | DM-R-40, DM-SM-03 |
| `IntakeImporter` (`IntakeImport`): the six rules of spec 6.5 in order with their texts, each distinct text once, refusal `INVALID` "Part intake refused." with `problems`; the stored sources (`disconnected`, actor `vigil-setup`) with one `source_created` change each and no event, `unclassified` "Unclassified" and the part's types, the mappings, the counter row at 0; no audit event; `startFirstTests` on virtual threads `intake-first-test-{source key}` | DM-E-15, DM-E-16, DM-E-17, DM-R-41, DM-R-57, BP-A-25, SL-B-14, AR-B-11, DD-F-03, DM-C-07 |
| `Intake` (`IntakeService`): `receiveWebhook` in the order of AR-R-16 (source, token with `Secrets.verify`, parse, then one transaction: counter row lock, `received_at` read after it, source row lock, call, raw and normalised alerts per entry, application (a) to (d), counter, contact and health evaluation, events in the order they arise); fields cut to their limits in code points, an alert key over 200 characters fails the entry; `alertsAfter`, `alert`, `sources`, `source`, `healthyThroughout`, `testSource`, `alertTypes` | AR-R-16, AR-R-25, PM-M06-01 to PM-M06-05, PM-M06-07, PM-I-06, PM-I-07, DM-E-18 to DM-E-22, DM-E18-R1, DM-E21-R1, DM-E21-R2, DM-E23-R1, DM-C-11, DM-R-01 to DM-R-03, AR-E-01, AR-E-02, AR-S-05, AR-S-11 |
| `SourceHealth` (evaluation under the source row lock: health, `health_changed_at`, the change record, `SourceHealthChanged`, the `INFO` line) and `SourceTests` (the connection test with no transaction open, then the result recorded under the row lock with the evaluation; the `WARN` line when a background test throws) | DM-R-39, DM-R-40, DM-E-23, AR-E-03, PM-M06-06, PM-M06-07, AR-R-09 |
| `SourceHealthWork` (`IntakeScheduling`): one fixed-delay task of 1 s per active tenant on the task scheduler; each tick reads now once, evaluates each active source in its own transaction, then starts each due test on a virtual thread `intake-test-{source key}`; `scheduledTenants()` for tests | AR-R-19, AR-R-23, DM-R-70 |
| `IntakeAliasListener` (`@ApplicationModuleListener` on `TenantAliasChanged`) and `RestartProcedure.step6StartScheduledWork()` calling `IntakeScheduling.matchActiveTenants()` after the session sweeps | AR-R-19, AR-R-24, AR-Q-05 |
| `vigil.api.WebhookController` with `@WebhookToken`: the body read from the input stream (at most 5,242,881 bytes, 413 `too_large` above 5 MiB), the bearer token taken as `@InstallationToken` takes it, `{"entries": n}`; `AccessInterceptor` lets `@WebhookToken` pass without a check; the OpenAPI operation, the security scheme `webhook` and the schema `WebhookEntries` | AR-I-90, AR-TM-02, PM-R-07 |
| `vigil.setup.IntakeAdapter` (part 6): the three sections with `SeedShapes`, `IntakeImport.importPart`, counts per section, `afterCommit` calling `startFirstTests` | AR-B-11, DD-F-03, BP-R-29 |
| `README.md` "Module code": the lines of spec 6.9 | P13-DC-02 |

Checked by review (spec part 4): AR-P-06 (`alert_counter` `SELECT, INSERT, UPDATE`, `PrivilegeIT`); AR-R-09 (`SourceTests.test` calls `ConnectorService.testConnection` outside any transaction: `testSource` has no transaction, the scheduled and first tests run on their own virtual threads, and `testConnection` throws inside one); AR-R-12 (partial unique index; the application runs under the counter row lock); DM-C-04 (immutable tables `SELECT, INSERT` only); DM-C-07 (the importer writes no audit event); DM-C-10 (every statement of `IntakeRecords` filters by `tenant_id`; `WebhookIT` uses three tenants); PM-R-01 (`SchemaRuleTest`); PM-R-02, PM-R-03 (`ModuleStructureTest`, dependencies unchanged); PM-R-04 (`ConnectorLibraryRuleTest`); PM-R-05 (`TenantColumnIT` with the ten rows); PM-R-07 (the webhook reaches intake only through `WebhookController`); PM-R-08 (`SeparationTest`); DM-C-06 (`connector_instance_id`, `webhook_token_ref_id` held as ids, no foreign key across schemas); DM-C-12; DM-V-01 (`updated_by_*` asserted by `IntakeImportIT` DM-E-15); DM-V-02 (pairs stored as `{"name","value"}` in the connector's order, `WebhookIT` DM-E-20).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.intake.AlertTypeMappingTest` (new) | DM-E17-R1 |
| `vigil.intake.SourceHealthRuleTest` (new) | DM-R-40, DM-SM-03 |
| `vigil.intake.IntakeImportIT` (new) | DM-R-57, DM-E-15, DM-E-16, DM-E-17, BP-A-25, DM-R-41, SL-B-14, DM-SM-03 |
| `vigil.intake.WebhookIT` (new) | AR-I-90, PM-M06-01, DM-E18-R1, AR-S-05, AR-TM-02, DM-E-18, DM-E-19, SL-B-02, PM-M06-02, PM-M06-03, DM-R-03, DM-E-20, DM-C-11, PM-I-06, SL-A1-01, DM-R-01, DM-R-02, PM-M06-04, AR-R-25, AR-S-11 |
| `vigil.intake.AlertStateIT` (new) | DM-E21-R1, DM-E-21, DM-E-22, DM-SM-04, PM-M06-05, DM-E21-R2, AR-E-01, AR-E-02, PM-I-07 |
| `vigil.intake.IntakeOrderIT` (new) | AR-R-16 |
| `vigil.intake.SourceHealthIT` (new) | DM-R-39, AR-R-23, AR-R-19, AR-Q-05, DM-R-40, PM-M06-06, AR-E-03, DM-E-23, DM-E23-R1, PM-M06-07, DM-R-70, AR-R-24 |
| `vigil.intake.IntakeFixture` (new test helper) | the intake fixture of spec 7, the recordings, the event recorder |
| `vigil.setup.SeedImportIT` (changed as spec 6.9 (1) to (4)) | new: DD-F-03, AR-B-11 |
| `vigil.setup.SeedImportRestartIT` (changed as spec 6.9) | AR-R-24 |
| `vigil.tenancy.TenancyIT` (changed as spec 6.9: two publications of `TenantAliasChanged`) | AR-E-14 |
| `vigil.identity.SessionExpiryIT` (changed as spec 6.9: waits for both tasks, counts the 60 s ones in a copy) | AR-R-23 |
| `vigil.api.ContractIT` (changed: webhook secrets, alias `hooks.test`, the `webhook` security set, scenarios 200, 401, 404, 413) | AR-I-08, BP-R-13 |
| `vigil.api.EntryPointTest` (changed: five annotations) | PM-M23-02 |

`tools/trace.py --check` passes. Unchanged tests that cover this package pass: `ModuleStructureTest`, `SchemaRuleTest`, `PrivilegeIT` and `TenantColumnIT` (with the ten new rows), `SecretCallerRuleTest`, `SeparationTest`, `MigrateIT`, `HostResolutionIT`, `SessionIT`, `DoubleDeliveryIT`, `ExternalCallGuardIT`.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Package-private classes not named in the spec: `Intake` (implements `IntakeService`), `IntakeImporter` (`IntakeImport`), `IntakeRecords` (the tables), `SourceHealth` (the evaluation of spec 6.7, shared by the webhook, the tests and the tick), `HealthCause` (the enum of DM-E-23 `cause`), `Texts` (cutting and counting in code points). | Internal structure only. |
| 2 | `TestOutcome` also has `fromKey`, like the other enums of 6.4. | Reading `last_test_outcome` from its row. |
| 3 | `README.md` "Module code": the bullet "Every `vigil-api` handler method carries …" also names `@WebhookToken`. | Otherwise the bullet would be wrong; `EntryPointTest` checks five annotations. |

Readings of the spec where it gives no order (no behaviour beyond the spec): within rules 2, 3 and 6, the checks of one entry are made in the order spec 6.5 lists them, entry after entry; a value that appears more than once (`source_key`, `webhook_token_ref`, `alert_type`, a mapping order) is reported at its second appearance (as CTX-D-046 (2) for part 5); two mapping orders are the same when their numbers are equal; a `null` `source_key`, `webhook_token_ref` or `alert_type` is not counted as a repeated value.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 21 October 2026)

None (spec 6.2).

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

Assumptions of spec 11: `DoubleDelivery`, `PrivilegeIT` and `TenantColumnIT` handle the tenant-scoped `technical_rw` table `intake.alert_counter` from its row in `table-classes.csv` alone: holds, with no code change. A handler that reads the request's input stream gets the body unread, Tomcat passes a chunked body of more than 5 MiB to it, and the 413 answered after reading 5,242,881 bytes reaches the client: holds (`WebhookIT` AR-TM-02, with and without `Content-Length`).

## 9. Lines of production code added and changed (P13-DC-01)

`tools/loc.sh origin/main HEAD` prints **LOC** (at most 3,500).

## 10. Gate command result

GATE

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: one API operation, AR-I-90; the module operations of spec 6.4 only); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT`, AR-Q-09 `SecretCallerRuleTest` pass; one listener added, `IntakeAliasListener`, in AR-Q-05 through `SourceHealthIT`; no external call entry point added); BP-DC-05 (one migration named as AR-P-05; one OpenAPI operation with its `ContractIT` scenarios; the part 6 adapter follows AR-B-11: one module import call in its own transaction, the first tests after the commit); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report).

## 11. Follow-up items for later packages

1. P-16: read the alerts with `alertsAfter(tenant, cursor, n)` (n at most 100) and wake on `AlertReceived`; `AlertView.identityPairs` are in the connector's order for `ServiceMapService.match`.
2. P-17: wake on `AlertStateChanged` and `SourceHealthChanged` (no listener exists yet).
3. P-23: `healthyThroughout` refuses an interval that ends after now (`IllegalArgumentException`) and answers `false` for an unknown source.
4. P-25: the live topics `alerts` (AR-E-01, AR-E-02) and `sources` (AR-E-03) react to the three intake events.
5. P-38: the setup screen's connection test calls `testSource` (no transaction; `NOT_FOUND` for an unknown or inactive key); `vigil-setup`'s reaction to `SourceHealthChanged` and the gap `source_not_healthy`.
6. A text from a monitoring source that holds the character U+0000 (JSON `\u0000` in a title, label or event name) cannot be stored in a PostgreSQL text column; such a call is answered 500 and nothing is stored. No recorded payload holds one; noted for the package that adds visible ingestion errors (PM-M06-L2, Later).
