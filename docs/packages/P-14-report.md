# P-14 — Report: Incidents

| Field | Value |
| --- | --- |
| Package | P-14 Incidents (spec `docs/packages/P-14.md`) |
| Branch | `p-14-incidents` |
| Pin date (BP-R-08) | 22 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1, Docker 29.9.1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-incidents` is in `vigil.incidents.api`; every other class is package-private in `vigil.incidents`. The `@ApplicationModule(allowedDependencies = {"shared", "tenancy :: api", "identity :: api", "audit :: api"})` of `vigil.incidents` and the declarations of `vigil.setup` and `vigil.api` are unchanged. No public type of `vigil.incidents.api` names a type of another module's `api` package. Only `ClosureWork` and `IncidentsAliasListener` carry `@Profile("!migrate")`.

| Part | Requirement IDs |
| --- | --- |
| `vigil.incidents.api` (spec 6.4, complete list): the enums `IncidentState`, `Severity`, `AiStatus`, `AiUnavailableReason`, `EscalationCause`, `NotifiedPurpose`, `EvidenceKind`, `DataCategory`, `TimelineEntryType` (each with `key()` and `fromKey`); the records `IdentityPairCopy`, `AlertCopy`, `GroupingReason`, `FinalReason`, `HypothesisCopy`, `AffectedServiceCopy` (each checks its value rules in its constructor), `IncidentView`, `MembershipView`, `TransitionView`, `AcknowledgementView`, `EscalationView`, `NotifiedTeamView`, `EvidenceView`, `TimelineView`, `IncidentSettingsView`, `IncidentSettingsPart`; the events `IncidentCreated`, `IncidentAcknowledged`, `IncidentStateChanged`, `IncidentChanged`; the interfaces `IncidentService`, `IncidentImport`, `IncidentScheduling` | PM-M09-01 to PM-M09-09, PM-M09-11, PM-M09-12, AR-E-05 to AR-E-08, BP-A-27, DM-V-01, DM-V-09, DM-V-10 |
| Migration `incidents/V20261022091643__incidents_create_incident_tables.sql` (spec 6.3): the nine tables with their `CHECK`s, `UNIQUE (tenant_id, sequence)`, `UNIQUE (tenant_id, number)`, the partial unique index `(tenant_id, alert_id) WHERE ended_at IS NULL`, `UNIQUE (tenant_id, incident_id, episode)`, `UNIQUE (tenant_id, incident_id, team_key)`, `UNIQUE (tenant_id, producer_module, producer_record_id)`, `UNIQUE (tenant_id)` of the settings, the indexes of spec 6.3 and the grants of AR-P-06; nine rows in `table-classes.csv` | DM-E-32 to DM-E-40, DM-E34-R1, DM-E37-R1, DM-E40-R1, DM-R-65, AR-R-12, AR-P-06, DM-C-04 |
| `Incidents` (`IncidentService`): the requests of spec 6.6 with the requesting-module check, the order of checks, the state guard, the row lock (`merge`: rows locked in ascending `sequence`), the advisory locks of `sequence` and `number`, the derived fields and the title after every change of members, hypothesis or impact, the transition records, timeline entries and audit events of spec 6.8, and the events in the order of spec 6.6; a request that changes nothing writes and publishes nothing; the acknowledgement of spec 6.7 under the row lock with its four checks in order; the reads of spec 6.4 | PM-M09-01 to PM-M09-09, PM-M09-11, PM-M09-12, DM-E-32 to DM-E-39, DM-E32-R1 to DM-E32-R3, DM-E35-R1, DM-SM-05, DM-SM-16, DM-R-12, DM-R-44, DM-R-63, DM-R-65, AR-R-12, AR-E-05 to AR-E-08, SL-B-12, BP-A-27, DM-E08-R3, PM-R-06, PM-R-09 |
| `IncidentTitle` (pure): the title of DM-R-20 over the active members | DM-R-20 |
| `IncidentTexts` (pure): the number format, state names, durations, the outbound time, the timeline texts of DC-TX-04 and the audit summaries of DC-TX-10 | DC-TX-01, DC-TX-04, DC-TX-10 |
| `IncidentRecords`, `IncidentRow`, `IncidentJson`: the tables (every statement filters by `tenant_id`) and the stored JSON shapes of spec 6.3 | DM-C-06, DM-C-10, PM-R-05 |
| `IncidentImporter` (`IncidentImport`): the checks of spec 6.5 in order with their texts, refusal `INVALID` "Part incidents refused." with `problems`; no audit event, no event | DM-E-40, DM-E40-R1, DM-R-57, DD-F-07, DM-C-07 |
| `ClosureWork` (`IncidentScheduling`): one fixed-delay task per active tenant with settings, with the tenant's `closure_check_interval`; each check reads now once and closes each `resolved` incident whose `resolved_at + reopen_window` is earlier than now, each in its own transaction that locks the row and checks again; `scheduledTenants()` for tests | DM-R-23, AR-R-19, AR-R-23, DM-R-70 |
| `IncidentsAliasListener` (`@ApplicationModuleListener` on `TenantAliasChanged`) and `RestartProcedure.step6StartScheduledWork()` calling `IncidentScheduling.matchActiveTenants()` after intake's | AR-R-19, AR-R-24, AR-Q-05 |
| `vigil.setup.IncidentsAdapter` (part 9): the mapping `incident_settings` with `SeedShapes`, `IncidentImport.importPart`, counts `{"incident_settings": 1}` | AR-B-11, DD-F-07, BP-R-29 |
| `vigil.intake.Intake`: after the alert key length check, an entry whose alert key, title, source severity or identity pair name or value holds U+0000 is `failed` with "the entry holds the character U+0000" and `not_applied` | DM-C-11, BP-A-26 |
| `README.md` "Module code": the lines of spec 6.11 | P14-DC-02 |

Checked by review (spec part 4): AR-P-06 (immutable tables `SELECT, INSERT`, stateful and configuration tables `SELECT, INSERT, UPDATE`, `PrivilegeIT` with the nine rows); DM-C-04 (no `DELETE`); DM-C-06 (alerts, grouping decisions, hypothesis computations, routing evaluations, users, verifications and producer records held by id; teams and components by key; the alert, hypothesis and service copies named as copies; no foreign key across schemas, `SchemaRuleTest`); DM-C-07 (the importer writes no audit event, asserted by `IncidentImportIT` DM-E-40); DM-C-10 (every statement of `IncidentRecords` filters by `tenant_id`; `IncidentLifecycleIT` DM-E08-R3 reads tenant A's incidents from tenant B); PM-R-01 (`SchemaRuleTest`); PM-R-02, PM-R-03 (`ModuleStructureTest`, dependencies unchanged); PM-R-05 (`TenantColumnIT`); PM-R-06 (the five audit event types, `IncidentLifecycleIT` DM-E08-R3, `IncidentRequestsIT` PM-M09-03 and PM-M09-08, `AcknowledgementIT` PM-M09-04); PM-R-08 (`SeparationTest`); PM-R-09 (`AcknowledgementIT` DM-E-35 and PM-M09-04: the user on the record, the incident, the timeline entry and the audit event); DM-V-10 (`AffectedServiceCopy` with its rules; stored by `service_key`, via keys sorted, `IncidentLifecycleIT` DM-E-32).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.incidents.IncidentTitleTest` (new) | DM-R-20 (seven methods: the cases of spec 7) |
| `vigil.incidents.IncidentTextsTest` (new) | DC-TX-01, DC-TX-04, DC-TX-10 |
| `vigil.incidents.IncidentLifecycleIT` (new) | PM-M09-01, DM-E-32, DM-E-34, BP-A-27, AR-E-05, DM-E-33, DM-V-01, SL-B-12, PM-M09-02, DM-SM-05, DM-E32-R3, AR-E-07, AR-E-08, PM-M09-12, DM-R-12, DM-SM-16, DM-E34-R1, PM-M09-06, DM-E32-R1, PM-M09-09, DM-E-39, DM-E08-R3, DM-R-23, AR-R-23, DM-R-70, AR-R-19, AR-Q-05, AR-R-24 |
| `vigil.incidents.IncidentRequestsIT` (new) | PM-M09-03, DM-V-09, PM-M09-08, DM-E-36, DM-E-37, DM-E37-R1, PM-M09-05, DM-E-38, DM-R-65, DM-R-44, PM-M09-11, PM-M09-07, DM-E32-R2 |
| `vigil.incidents.AcknowledgementIT` (new) | DM-E35-R1, DM-E-35, PM-M09-04, AR-E-06, DM-R-63, AR-R-12 |
| `vigil.incidents.IncidentImportIT` (new) | DM-E-40, DM-E40-R1, DM-R-57 |
| `vigil.incidents.IncidentFixture` (new test helper) | the incident fixture of spec 7, the storm-like incident, the event recorder |
| `vigil.intake.WebhookIT` (changed: two methods, `@Order(21)` and `@Order(22)`) | DM-C-11, BP-A-26 |
| `vigil.setup.SeedImportIT` (changed as spec 6.11 (1) to (3); the main app's listener also records the four incident events) | new: DD-F-07, AR-B-11, BP-R-29 |
| `vigil.setup.SeedImportRestartIT` (changed as spec 6.11) | AR-R-24 |
| `vigil.tenancy.TenancyIT` (changed as spec 6.11: three publications of `TenantAliasChanged`) | AR-E-14 |

Every row of spec part 4 with proof `test` has a method whose display name starts with its ID; `tools/trace.py --check` passes. Unchanged tests that cover this package pass: `ModuleStructureTest`, `SchemaRuleTest`, `PrivilegeIT` and `TenantColumnIT` (with the nine new rows), `SeparationTest`, `MigrateIT`, `AppStartIT`, `SessionExpiryIT`, `DoubleDeliveryIT`, `ContractIT`, `EntryPointTest`.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Package-private classes not named in the spec: `Incidents` (implements `IncidentService`), `IncidentImporter` (`IncidentImport`), `IncidentRecords` (the tables), `IncidentRow` (an incident row as a request changes it), `IncidentJson` (the stored JSON shapes), `IncidentTexts` (the texts of spec 6.8), `IncidentTitle` (DM-R-20). | Internal structure only. |
| 2 | The value rules of `IdentityPairCopy`, `AlertCopy`, `GroupingReason`, `FinalReason`, `HypothesisCopy` and `AffectedServiceCopy` are checked in their constructors (`IllegalArgumentException`), so a request never receives a value that breaks them; `AffectedServiceCopy` keeps `viaComponentKeys` sorted. Non-public members on public types: `FinalReason.ONLY_ALERT` (public constant), `GroupingReason.checkedRelation` and `HypothesisCopy.CRITICALITIES` (package-private, shared by the copies). | The spec gives the rules with the records and lets them fail as `IllegalArgumentException`; checking at construction keeps every copy valid. |
| 3 | A `null` argument is refused with the message "a required argument is missing". | The spec gives no message for `null` arguments. |

Readings of the spec where it gives no detail (no behaviour beyond the spec): `recordEscalation` refuses a negative `timer` (`IllegalArgumentException`, DM-E-36 durations are `>= 0`); timeline entries carry a reference only where spec 6.6 and 6.7 name one; the one `INFO` line per state transition includes the creation (`DM-SM05-T1`, from `none`); the incident row's `updated_at` changes when the row changes (a notified team or an evidence entry changes only its own table); a repeated evidence entry is recognised after the state guard (step (4) of the order of checks); when only the hypothesis snapshot changes but its new name changes the title, the request writes `title_changed` (no `hypothesis_changed`, the component is the same); a closure uses the check's `now` as `closed_at` and checks the state and the window again under the row lock; the user display name of a refusal falls back to the user id when the user does not exist (as a team falls back to its key).

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

Assumption of spec 11: `pg_advisory_xact_lock` with `hashtext` serialises the tenant's `sequence` and `number` assignment: holds (`IncidentLifecycleIT` DM-E32-R3: incidents X and Y created in that order and opened in the order Y, X get numbers 3 and 2).

## 9. Lines of production code added and changed (P14-DC-01)

`tools/loc.sh origin/main HEAD` prints **3,484** (at most 3,500).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 2: `vigil-app` 107 unit tests and 282 `*IT` (every earlier test passes; `WebhookIT`, `SeedImportIT`, `SeedImportRestartIT` and `TenancyIT` changed only as spec 6.11 and Section 7), `demo-control` 1 and 2; the web build, lint, type check and Vitest pass; step 3: `ruff` clean, 81 Python tests passed, 1 skipped and 35 deselected (no file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` changed, so the lab tests did not run, CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose file valid; step 7: `trace.py --check`.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Environment note: during this session `repo.maven.apache.org` answered every request with 429 (Too Many Requests), so the Maven wrapper could not download Maven or the dependencies. The session used `https://repo1.maven.org/maven2` (the same Maven Central content) through `MVNW_REPOURL` and a mirror in `~/.m2/settings.xml` of the container; nothing in the repository changed for it.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no API operation, no live topic; the module operations of spec 6.4 only); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT` pass; one listener added, `IncidentsAliasListener`, in AR-Q-05 through `IncidentLifecycleIT`; no external call); BP-DC-05 (one migration named as AR-P-05; no OpenAPI operation; the part 9 adapter follows AR-B-11); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report); P14-DC-01 (Section 9); P14-DC-02 (`README.md`); P14-DC-03 (Section 10).

## 11. Follow-up items for later packages

1. P-16: the alert copy needs `alertTypeName` (from `IntakeService.alertTypes`), `alertKey` and the first identity pair; `create`, `addAlert` and `reopen` refuse an alert with an active membership (`IllegalStateException`); `setHypothesisAndImpact` takes `null` hypothesis and computation id together and refuses to remove a hypothesis; `open` needs a final reason for exactly the active members' alert ids; `merge` needs every incident `forming`.
2. P-15: `setAssignment` only while `forming`; a repeated `routingEvaluationId` changes nothing; every other applied evaluation writes its entry and audit event.
3. P-22: `recordEscalation` needs `timer` for `ACKNOWLEDGEMENT_TIMEOUT` and none for `ESCALATE_NOW`; `recordNotifiedTeam` once per team (states `open` and `resolved`).
4. P-23: `resolve` needs the verification's `recovery_verification` evidence entry from `vigil-recovery` first (both MAY be made in one transaction).
5. P-20: `setAiStatus` and `addEvidence` of kind `ai_investigation` are accepted on a `closed` incident (DM-E68-R2).
6. P-25 and P-30: `acknowledge` answers `FORBIDDEN` without message, and `REFUSED` with details `refusal` (`not_open` with `state`, `already_acknowledged` with `user_id` and `at`, `not_notified`); its messages use the outbound time format, the screen texts with viewer times are P-30's.
7. P-17: `IncidentCreated`, `IncidentAcknowledged`, `IncidentStateChanged` are published in the request's transaction; creation is not an `IncidentStateChanged`.
