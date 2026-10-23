# P-16 — Report: Correlation

| Field | Value |
| --- | --- |
| Package | P-16 Correlation (spec `docs/packages/P-16.md`) |
| Branch | `p-16-correlation` |
| Pin date (BP-R-08) | 22 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-correlation` is in `vigil.correlation.api`; every other class is package-private in `vigil.correlation`. The `@ApplicationModule` declarations of `vigil.correlation`, `vigil.setup` and `vigil.api` are unchanged. The public types of `vigil.correlation.api` name no type of another module's `api` package (only `vigil.shared.TenantId`). `@Profile("!migrate")` is on exactly `CorrelationWork`, `CorrelationHealthIndicator`, `CorrelationAliasListener` and `CorrelationWakeListener`.

| Part | Requirement IDs |
| --- | --- |
| `vigil.correlation.api` (spec 6.4, complete list): the enums `Decision`, `GroupingRelation`, `TieBreak` (each with `key()` and `fromKey`); the records `CorrelationSettingsView`, `CorrelationSettingsPart`, `GroupingDecisionView`, `HypothesisComputationView`; the interfaces `CorrelationService`, `CorrelationImport`, `CorrelationScheduling` | DM-E-29, DM-E-30, DM-E-31, PM-M08-10 |
| Migration `correlation/V20261023054913__correlation_create_correlation_tables.sql` (spec 6.3): `grouping_decision` (`immutable`; `UNIQUE (tenant_id, alert_id)`; the four reason columns `NULL` together exactly for `created_incident`; indexes `(tenant_id, incident_id)` and `(tenant_id, component_key)`), `hypothesis_computation` (`immutable`; index `(tenant_id, incident_id, computed_at)`), `correlation_settings` (`configuration`; the four bounds; `UNIQUE (tenant_id)`), `cursor` (`technical_rw`, as `intake.alert_counter`, with its index on `tenant_id`, which AR-Q-08 requires); grants per AR-P-06; four rows in `table-classes.csv` | DM-E-29, DM-E-30, DM-E-31, DM-E31-R1, DM-R-61, AR-R-17 |
| `CorrelationRecords` (the four tables; every statement filters by `tenant_id`; the cursor inserted or updated with `ON CONFLICT`), `Correlation` (`CorrelationService`, the seven reads of spec 6.4, `IllegalArgumentException` for a `null` argument) | DM-C-06, DM-C-10, PM-R-05, PM-M08-10 |
| `CorrelationImporter` (`CorrelationImport`): the checks of spec 6.5 in their order with their texts (an existing record stops the other checks), refusal `INVALID` "Part correlation refused." with `problems`; no audit event, no event, no worker | DD-F-07, DM-R-57, DM-E31-R1 |
| `vigil.setup.CorrelationAdapter` (part 10): `SeedShapes.mapping` of `correlation_settings` with the four fields, `refuseIfAny`, `CorrelationImport.importPart`, counts `{"correlation_settings": 1}`, no handles, no `afterCommit` | AR-B-11, BP-R-29, DD-F-07 |
| `GroupingRules`: the alert relation of spec 6.6 (5) (component relation by `ServiceMapService.relation`, `same_unknown_identity`, the window compared exactly, `time_delta` rounded halves up), the related alert of (6) (earliest `fired_at`, then source kind, then alert key in code point order), the final reasons of DM-R-58 (`only_alert` when no other member is related); relations asked once per component pair within one decision or transition | DM-R-07, DM-R-08, DM-R-11, DM-R-58, PM-M08-02 |
| `Hypothesis`: the rule of DM-R-18 with `dependent_counts` over the affected components only and `tie_break` the last rule needed | DM-R-18, DM-E-30 |
| `CorrelationTexts`: the summaries of `alert_grouped` (with "; 1 group merged" / "; {k} groups merged") and `hypothesis_changed`, cut to 300 code points; source kind names `Zabbix`, `Alertmanager` | DC-TX-10, DM-E08-R3 |
| `CorrelationDecisions`: the decision of an alert in one transaction, steps 1 to 12 of spec 6.6 (repeat → cursor only; match once; the alert copy with the alert type's display name and first identity pair; candidates from `forming`/`open` incidents; cases (a) reopen rule or create, (b) the lowest `open` candidate, (c) the lowest forming candidate and the merge of the others in ascending sequence; the record after the request; hypothesis computation when the affected set or the generation changed, the impact with each service's name and criticality, `setHypothesisAndImpact`, the kept hypothesis copy when the result component is not in the current generation; the routing evaluation on creation, merge or a change of components, hypothesis component or services; `alert_grouped` and, on a change of hypothesis component, `hypothesis_changed`; the cursor); the stability check (DM-R-15: evaluate, final reasons, `open`), each stable incident in its own transaction with `now` read in it | PM-M08-01 to PM-M08-09, DM-E-29, DM-E-30, DM-R-05, DM-R-06, DM-R-09, DM-R-10, DM-R-12, DM-R-13, DM-R-14, DM-R-15, DM-R-17, DM-R-19, DM-R-21, SL-B-12, DM-R-61, DM-E08-R3, SL-A1-02 |
| `CorrelationWork` (`CorrelationScheduling`): `synchronized matchActiveTenants()` (stops the workers of inactive tenants, starts one per active tenant with settings; waits at most 4 s in all for a still-ending worker of the same tenant, `WARN` and no new worker when it is still alive); one virtual thread `correlation-{tenant id}` per worker; the loop of spec 6.7 (batches of 100 from the cursor, stability, wait for a wake or the check interval; other exceptions: `ERROR` and 5 s); a failed decision: `ERROR` per attempt, retries after 1 s, 5 s, 30 s, then 60 s, never skipped, no stability check while pending, wakes do not shorten the delay; stop ends the worker after its current transaction or at once from a wait; `@PreDestroy` stops every worker and waits at most 10 s each; `wake`, `runningTenants`, `workerThread` | AR-R-17, AR-R-18, AR-R-19, AR-R-26, DM-R-62, DM-R-70 |
| `CorrelationHealthIndicator` (bean `correlationHealthIndicator`): `UP` without details; `DOWN` with `{"retries": [{"tenant_id", "alert_id", "attempt"}]}` by tenant id; the entry updated before the `ERROR` line, removed on success or stop | AR-H-12 |
| `CorrelationAliasListener` (`TenantAliasChanged` → `matchActiveTenants`), `CorrelationWakeListener` (`AlertReceived` → `wake`); `RestartProcedure.step5StartCorrelationWorkers()` calls `CorrelationScheduling.matchActiveTenants()` | AR-R-19, AR-R-24, AR-Q-05 |
| `README.md` "Module code": the lines of spec 6.8 | P16-DC-02 |

Checked by review (spec part 4): DM-R-62 and AR-R-18 (the worker thread is the only caller of `CorrelationDecisions.decide` and `checkStability`; the stability check runs only between alert passes, and not while a retry is pending); AR-P-06 (two `immutable` tables `SELECT, INSERT`, `correlation_settings` and `cursor` `SELECT, INSERT, UPDATE`; `PrivilegeIT` with the four rows); DM-C-04 (no `DELETE`); DM-C-06 (alerts, incidents and components held by id or key; no foreign key); DM-C-10 and PM-R-05 (every statement of `CorrelationRecords` filters by `tenant_id`; every request takes a `TenantId`; `GroupingIT` uses two tenants; `TenantColumnIT`); PM-R-01, PM-R-02, PM-R-03 (`SchemaRuleTest`, `ModuleStructureTest`); PM-R-08 (`SeparationTest`).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.correlation.GroupingRulesTest` (new) | DM-R-08, DM-R-11, DM-R-58 |
| `vigil.correlation.HypothesisTest` (new) | DM-R-18 |
| `vigil.correlation.CorrelationTextsTest` (new) | DC-TX-10 |
| `vigil.correlation.CorrelationImportIT` (new) | DM-E-31, DM-E31-R1, DM-R-57 |
| `vigil.correlation.GroupingIT` (new) | PM-M08-01, DM-E-29, DM-R-05, PM-M08-02, PM-M08-03, DM-R-10, DM-R-12, PM-M08-09, DM-R-09, PM-M08-07, DM-E-30, PM-M08-04, PM-M08-05, DM-R-19, DM-R-15, DM-R-58, DM-R-17, DM-R-06, DM-R-21, PM-M08-08, SL-B-12, DM-R-61, PM-M08-10, DM-E08-R3 |
| `vigil.correlation.StormOrderIT` (new) | PM-M08-06, SL-A1-02, DM-R-13, DM-R-14, DD-K-04, DD-K-06 |
| `vigil.correlation.CorrelationWorkerIT` (new) | AR-R-26, DM-R-70, AR-R-19, AR-R-17, AR-H-12, AR-Q-05, AR-R-24 |
| `vigil.correlation.CorrelationFixture` (new public test helper) | the correlation fixture of spec 7 |
| `vigil.intake.IntakeFixture` (changed as spec 6.8) | public class and the listed members public; nothing else changed |
| `vigil.setup.SeedImportIT` (changed as spec 6.8 (1) to (4)) | new: DD-F-07, AR-B-11, BP-R-29 |
| `vigil.tenancy.TenancyIT` (changed as spec 6.8) | AR-E-14: four publications, with `vigil.correlation.CorrelationAliasListener` |

Every row of spec part 4 with proof `test` has a method whose display name starts with its ID; `tools/trace.py --check` passes. The new `*IT` classes run their methods in the order of the Checks column, each on its own fresh database with the secrets of `IntakeFixture.secrets`, a WireMock server stubbed with `IntakeFixture.stub` and `MutableClock` (from `2026-10-22T10:00:00Z`) as the `@Primary` `Clock`. Unchanged tests that cover this package pass: `ModuleStructureTest`, `SchemaRuleTest`, `PrivilegeIT` and `TenantColumnIT` (with the four new rows), `SeparationTest`, `MigrateIT`, `AppStartIT`, `DoubleDeliveryIT`, `ContractIT`, `EntryPointTest`, and the intake, incident and routing tests.

Spec 11 (assumption): `StormOrderIT` (20 tenants, 127 alerts each, in 20 seeded orders) took **70 s** in this session (`Tests run: 6, … Time elapsed: 70.41 s`).

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Package-private classes not named in the spec: `Correlation` (implements `CorrelationService`), `CorrelationImporter` (`CorrelationImport`), `CorrelationRecords` (the tables), `CorrelationDecisions` (spec 6.6), `GroupingRules`, `Hypothesis`, `CorrelationTexts`. | Internal structure only. |
| 2 | Test helpers beyond the ones spec 7 names: `CorrelationFixture.of` (the fixture of an existing tenant after the restart of `CorrelationWorkerIT`), `postZabbix`, `postAlertmanager` (post and return the created alert), `alerts`, `highestSequence`, `resolve` (an evidence entry of kind `recovery_verification` and `resolve`, both from `vigil-recovery`), `importSettings`, `importServiceMap`. | Test support only. |
| 3 | Two constraints in the migration beyond the listed ones: `time_delta >= 0`, and a `CHECK` that the four reason columns are all `NULL` for `created_incident` and all set otherwise. | Spec 6.3 says the four columns are `NULL` together for `created_incident`. |
| 4 | In the stability check, no further transaction starts once the worker is stopped. | Spec 6.7 "the worker ends after its current transaction"; AR-R-19. |

Readings of the spec where it gives no detail (no behaviour beyond the spec): step 9 calls `setHypothesisAndImpact` only when it records a new DM-E-30 record; when the affected set and the generation are unchanged, the hypothesis and the impact are unchanged too, and `vigil-incidents` would write nothing (P-14). Step 10 compares the affected components with the latest computation's set (an incident without a computation counts as changed). The health indicator orders the retries by the text of the tenant id. A `null` argument is refused with "a required argument is missing" (as P-14 and P-15). In `GroupingIT` DM-R-21, the reopen reason's `time_delta` is 40 s (D fired at T0 + 15 s, the reopening alert at T0 + 55 s); the spec does not state the value.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 22 October 2026)

None (spec 6.2).

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

## 9. Lines of production code added and changed (P16-DC-01)

`tools/loc.sh origin/main HEAD` prints **1,825** (size L, at most 3,500).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 2: `vigil-app` 122 unit tests and 356 `*IT` (every earlier test passes; `SeedImportIT` and `TenancyIT` changed only as spec 6.8 and Section 7), `demo-control` 1 and 2; the web build, lint, type check and Vitest pass; step 3: `ruff` clean, 81 Python tests passed, 1 skipped and 35 deselected (no file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` changed, so the lab tests did not run, CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose validation; step 7: `tools/trace.py --check`.

The first run failed in `TenantColumnIT` (AR-Q-08): `correlation.cursor` had no index besides its primary key, and the test requires a non-primary index starting with `tenant_id`. The index of `intake.alert_counter` was added (`cursor_tenant_idx`); the second run passed.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no API operation, no screen, no actuator property, no `TaskScheduler` task; the operations of spec 6.4 only); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT` pass; the two new listeners are in AR-Q-05 in `CorrelationWorkerIT`; no external call); BP-DC-05 (one migration named as AR-P-05; no OpenAPI operation; the part 10 adapter follows AR-B-11); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report); P16-DC-01 (Section 9); P16-DC-02 (`README.md`); P16-DC-03 (this section).

## 11. Follow-up items for later packages

1. P-17: the incident events this package causes (`IncidentCreated`, `IncidentStateChanged` for `open`, `merged` and the reopen, `IncidentChanged`) are published by `vigil-incidents` within the correlation transaction; an `evaluate` with `WORKFLOWS` MUST NOT race with the worker's `open` (P-15 report item 2).
2. P-23: `CorrelationService.alertsOfComponent(tenant, componentKey)` gives the alert ids whose matched component is the key, by alert `sequence` (DM-R-35).
3. P-26, P-27: `/actuator/health` shows the `correlation` indicator's `DOWN` without details until they set `management.endpoint.health.show-details` for `deploy/status.sh` (CTX-D-053 (12)).
4. P-29: `decisions`, `hypothesisComputations`, `latestHypothesisComputation` (`dependent_counts` in key order) and `settings` for DC-TX-02, DC-S03-03 and DC-S03-05; the membership reasons and final reasons are stored by `vigil-incidents` as given in spec 6.6.
