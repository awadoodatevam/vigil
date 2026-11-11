# P-23 — Report: Recovery and dashboard metrics

| Field | Value |
| --- | --- |
| Package | P-23 Recovery and dashboard metrics (spec `docs/packages/P-23.md`) |
| Branch | `p-23-recovery-dashboard` |
| Pin date (BP-R-08) | 12 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command passes in this session (Section 10). No owner-run steps. |

## 1. What was built

The `@ApplicationModule` declarations of `vigil-recovery` and `vigil-reporting` are unchanged. Every new public type is in an `api` package; every other new class is package-private and in the spec's complete list (spec 6.4), except records nested inside those classes. Neither module makes an external call or logs anything; no module gets an event listener (AR-Q-05: no `@ApplicationModuleListener` or `@TransactionalEventListener` under `vigil.recovery` or `vigil.reporting`). No `vigil-api` operation, screen or setting is added. The only production change outside `vigil-recovery`, `vigil-reporting` and `vigil-setup` is the two reads of `vigil-intake`.

| Part | Requirement IDs |
| --- | --- |
| Migrations (spec 6.2): `recovery/V20261112120000__recovery_create_recovery_tables.sql` (`recovery_check_definition` `configuration`, with the unique default index of DM-E61-R1; `recovery_verification` `stateful`, with the decided check, `max_wait_until > started_at`, the unique step execution index of DM-R-64 and the incident index) and `reporting/V20261112120100__reporting_create_baseline_value.sql` (`baseline_value` `configuration`); three rows in `table-classes.csv` | DM-E-61, DM-E-62, DM-E-80, AR-Q-06, AR-Q-08 |
| `IntakeService.alerts(tenant, ids)` (one query, by `sequence`) and `alertCount(tenant)` (spec 6.3) | PM-I-29, PM-I-39 |
| `vigil.recovery.api` (spec 6.4, as written): `ConditionKind`, `VerificationState`, `RecoveryCondition` (compact constructor, alert types sorted without repeats), `RecoveryCheckView`, `RecoveryVerificationView`, `RecoveryService`, `RecoveryPart` with `DefinitionPart`, `RecoveryImport` | DM-V-07, PM-M15-04 |
| `RecoveryRecords`: the two tables and the JSON forms of spec 6.5 (condition, snapshot) | DM-E-61, DM-E-62 |
| `RecoveryTexts` (spec 6.6): the condition texts in both forms, the guard, both evidence summaries with the CTX-D-073 (4) text, `source_system`, the audit summaries, the DC-U-07 rule (2) duration (own copy) | DC-TX-03, DC-TX-10 |
| `Evaluation` (spec 6.7 steps 3 to 5): the incident condition from the active memberships, the component condition from `CorrelationService.alertsOfComponent` with the alert type filter, both read with `IntakeService.alerts`; the guard from the active sources' health now; the window (cleared, started, restarted by `healthyThroughout`); the last evaluation JSON; the requirement texts | DM-R-35, DM-R-36, DM-R-37, DM-E23-R1 |
| `RecoveryStep` (spec 6.7): parameters; one transaction with the row read `FOR UPDATE`; the definition by key or the active default; `max_wait` above the window; the decision with, in one transaction, the audit event, the evidence entry and the resolve request when the incident is `open`; the answer (`Completed` with the `ResultRef`, or `Waiting` on the two events until the earlier of the window's end and `max_wait_until`); `cancel` | PM-M15-02, PM-M15-03, PM-S-07, DM-S-07, DM-R-34, DM-R-38, DM-R-64, DM-SM-13, DM-SM14-T2, DM-E08-R1, DM-E08-R3, SL-A1-07, SL-A2-04 |
| `Recovery` (reads: `definitions`, `definition` with the requirement texts, `verifications`) | PM-M15-04 |
| `RecoveryImporter` (spec 6.8): every check in its order, the part refused as a whole, `recovery check definitions already exist`, every definition stored `active` | DM-E-61, DM-E61-R1, DM-R-57, DD-F-04 |
| `vigil.reporting.api` (spec 6.4, as written): `MetricKey` (with `unit()`), `MetricUnit`, `MetricView`, `DashboardView`, `ReportingService`, `ReportingPart` with `BaselinePart`, `ReportingImport` | DM-E-79, PM-M21-02 |
| `Metrics` (pure): the two means from milliseconds, half-up to 1 place, `null` for no data; alerts suppressed (not clamped); incidents merged; each with its baseline (scale 1 for minutes, 0 for counts) | DM-R-53, DM-E-79, PM-M21-01, PM-M21-02 |
| `Reporting.dashboard` (one read-only transaction): the counted live incidents (`open`, `resolved`, `closed` with a number; episode 1's acknowledgement; the first transition to `resolved`), the active past incidents, alerts received with every past incident's `alert_count`, the counts and sorted record labels | DM-R-52, DM-R-53, SL-A1-09, DD-K-11 |
| `BaselineRecords`, `ReportingImporter` (spec 6.11): the checks in their order, `baseline values already exist`, stored as `numeric(12,1)` | DM-E-80, DM-R-57, DD-F-07 |
| `vigil-setup`: `RecoveryAdapter` (part 13, counts `{"recovery_check_definitions": n}`), `ReportingAdapter` (part 17, counts `{"baseline_values": n}`), `RecoveryServiceMapCheck` (13 → 5), `RecoveryAlertTypeCheck` (13 → 6) | DM-R-56, DM-R-57, DM-R-60, AR-B-11 |
| `README.md` "Module code": the P-23 bullet of spec 6.12. `CLAUDE.md` unchanged. | BP-DC-06 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `recovery/RecoveryTextsTest` (2 methods) | DC-TX-03, DC-TX-10 |
| `recovery/RecoveryStepIT` (19 methods; a new tenant per storyline) | SL-A1-07, PM-M15-02, PM-M15-03, PM-S-07, DM-E-62, DM-SM-13, DM-R-35, DM-R-37, DM-R-36 (2 methods: a failed connection test; sources stale between two calls), SL-A2-04, DM-S-07, DM-R-38, DM-R-34, DM-R-64, DM-E08-R3, DM-E08-R1, PM-M15-04, PM-M21-01 |
| `ticketing/RecoveryEvidenceIT` | DM-SM14-T2 |
| `reporting/MetricsTest` (2 methods) | DM-R-53, DM-E-79 |
| `reporting/DashboardIT` | SL-A1-09 |
| `setup/DashboardSeedIT` (4 methods) | DD-K-11, DM-R-52, PM-M21-02, DD-F-07 |
| `setup/ReportingImportIT` | DM-E-80 |
| `setup/RecoveryImportIT` (10 methods) | DM-E-61, DM-E61-R2, PM-M15-01, DD-F-04, DM-V-07, DM-E61-R1, DM-R-56, DM-R-57, DM-R-60, AR-B-11 |
| `TableClasses` rows (existing privilege and tenant tests) | AR-Q-06, AR-Q-08 |

Test support: `recovery/RecoveryFixture` (public, spec 6.12) with its nested `App` (WireMock answering the connection tests, vigil-app with the test class's `MutableClock` and no `TestHandlers`, the secrets of the suffixes `-1` to `-60`, one tenant per call of `tenant()`), the default intake part (stale after 3,600 s, disconnected after 7,200 s), the demo part 13 read from `04-operations-actions-recovery.yaml`, alerts and their resolves, the settle advance and the step calls. Existing tests changed only as spec 6.12 lists: `CorrelationFixture` gains the overload with an `IntakePart`; `SeedImportIT` (a) to (e); `SeedImportRestartIT` (`get(16)`); `NotificationsImportIT` (`inOrder`); `EngineAbsentHandlersIT` (second half uses `approval`); `table-classes.csv`. `TestHandlers` is unchanged.

## 3. Deviations from the spec

None in behaviour. Readings of points the spec leaves open (no document text changes):

1. The two new `IntakeService` reads refuse a `null` argument as every other `IntakeService` request does (`IllegalArgumentException("{name}: required")`); spec 6.1's text `a required argument is missing` is used for the public requests of `vigil-recovery` and `vigil-reporting`.
2. `RecoveryStep`'s first call inserts the `observing` row with the evaluation of step 3, then applies step 5 to it in the same transaction (so a first call where everything holds already starts the window, as step 5 says).
3. `max_wait` is read from the parameters object; other parameter fields are not refused (the stored content has only `max_wait`).
4. The evidence content's `definition_snapshot` is written again from the stored snapshot in the key order of spec 6.5, since `jsonb` does not keep key order.
5. `RecoveryImporter`: a condition whose `kind` is not valid gets only the `.kind` problem (its other fields cannot be checked against a kind); `alert_types` YAML `null` counts as absent, so as empty.
6. `ReportingImporter`: the missing metrics are joined by `, ` (DC-TX-00 "Lists"). A part with an unknown or repeated metric key also gets the "missing" problem for the key it lacks, so those two cases of `ReportingImportIT` expect two problems.
7. `RecoveryService.definitions` and `definition` read in a read-only transaction (they read `vigil-servicemap` and `vigil-intake` for the requirement texts).
8. `RecoveryFixture.open` advances the clock by 41 s (past the settle period of 40 s) before it waits for `open`.
9. The recovery migration also checks `jsonb_typeof(conditions) = 'array'` and the Key format of `recovery_check_key` on both tables, as earlier migrations check their lists and keys.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 12 November 2026)

None: no dependency is added or changed.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None: the spec lists no owner-run steps.

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **2,206** (size L; estimate about 2,150, between 1,900 and 2,500).

## 10. Gate command result

Cloud session (`tools/check-all.sh`), run on the tree of commit `8c6faae` (this report is the only later change): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 2: `vigil-app` 191 unit tests and 683 `*IT` (every earlier test passes; the changed tests only as spec 6.12); `demo-control` 1 and 2; the web build, lint, type check and Vitest pass. Step 3: `ruff` clean; 345 Python tests passed, 1 skipped, 35 deselected (no lab path changed, BP-R-12 step (3)). Step 4: `shellcheck`. Step 5: 49 data checks, 0 failed. Step 6: compose validation. Step 7: `tools/trace.py --check`.

Done-check (spec Section 8): (1) BP-DC-01 to BP-DC-07: every part 4 row with proof `test` has a test that names it (`tools/trace.py --check` passes); AR-Q-05 checked by review (Section 1); nothing beyond the spec (no API operation, screen or setting); AR-Q-01 to AR-Q-09 pass; no new entry point makes an external call (AR-Q-04 unchanged); two migrations named as AR-P-05; the `README.md` bullet; this report; no owner-run steps. (2) Section 9: 2,206, above 2,000: the package is L. (3) No file under `docs/31-demo-data/` changes; the data checks pass.

## 11. Follow-up items for later packages

1. P-24: `vigil-actions` answers `latestRecoveryCheckKey` for the engine, so the Recovery check step gets the registered action's key (DM-R-34); the key check of part 14 against part 13 can use `RecoveryService.definitions`; the Approval page reads the requirement texts with `RecoveryService.definition`. `SeedImportIT` then needs another unregistered part, or none, for its latch importer, its `RecordingCheck` target and the BP-R-29 skip test (all on part 14 now); `NotificationsImportIT` and `SeedImportIT`'s outcome checks expect part 14 `not_started`.
2. P-25: `RecoveryService.verifications` (with `lastEvaluationJson`) for DC-S03-11 and `ReportingService.dashboard` for AR-I-80.
3. P-33: the screen texts of DC-S10 (metric definitions, "Illustrative baseline" tag, the data line from `liveIncidents`, `pastIncidents` and `recordLabels`).
4. P-36: the test replay of a Recovery check step.
