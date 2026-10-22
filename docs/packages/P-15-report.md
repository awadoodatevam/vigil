# P-15 — Report: Routing

| Field | Value |
| --- | --- |
| Package | P-15 Routing (spec `docs/packages/P-15.md`) |
| Branch | `p-15-routing` |
| Pin date (BP-R-08) | 22 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-routing` is in `vigil.routing.api`; every other class is package-private in `vigil.routing`. The `@ApplicationModule` declarations of `vigil.routing`, `vigil.setup` and `vigil.api` are unchanged. The public types of `vigil.routing.api` name, of other modules' types, only `vigil.incidents.api.Severity` and the types of `vigil.shared`. No class has a `@Profile`; `vigil-routing` has no scheduled work, no listener, no restart step and no event.

| Part | Requirement IDs |
| --- | --- |
| `vigil.routing.api` (spec 6.4, complete list): the enums `RequestedBy`, `NotAppliedReason`, `OwnerMode`, `RoutingOperator` (each with `key()` and `fromKey`), `ValueType`, `RoutingField` (`key()`, `displayName()`, `valueType()`, `allowedValues()`, `operators()`, `fromKey`); the records `RoutingCondition`, `RoutingRule`, `RuleSetView`, `EscalationLevel`, `EscalationPolicyView`, `RoutingResult`, `EvaluationView`, `RoutingPart`, `RulePart`, `ConditionPart`, `LevelPart`; the interfaces `RoutingService` and `RoutingImport` | PM-M10-01 to PM-M10-04, DM-E-41 to DM-E-43, DM-V-03, DM-V-09, DM-R-25, DM-R-26, DC-TX-06 |
| Migration `routing/V20261022121632__routing_create_routing_tables.sql` (spec 6.3): `routing_rule_set_revision` and `escalation_policy_revision` (`revisioned`, `UNIQUE (tenant_id, revision)`, actors as three columns with their `CHECK`), `routing_evaluation` (`immutable`, the `applied` / `not_applied_reason` `CHECK`, index `(tenant_id, incident_id, at)`), `SELECT, INSERT` for all three; three rows in `table-classes.csv` | DM-E-41, DM-E-42, DM-E-43, AR-P-06, DM-C-04 |
| `Routing` (`RoutingService`): `evaluate` with its order of checks (null arguments, the caller of each `RequestedBy`, `NOT_FOUND`, no rule set), the inputs of DM-R-25, the first matching rule, the result (owner of the hypothesis component when it is an active team, owners of affected services when active teams, the owner removed from the informed parties, sorted), the not-applied reason (dry run, then not active, then frozen), the evaluation record and, when applied, `IncidentService.setAssignment` from `vigil-routing` with the record's id and the rule's name, in the caller's transaction; `saveRuleSet` with its order of checks (arguments, `MANAGE_ROUTING`, the advisory lock, no rule set, `CONFLICT`, `INVALID` with `problems` and `places`), keys `rule-{n}` for new rules, the stored form of the rules, the change summary, one `configuration_changed` audit event (area `routing_rules`, before and after) and one `INFO` line; the reads of spec 6.4 | PM-M10-01, PM-M10-02, PM-M10-03, DM-E-41, DM-E41-R1, DM-E41-R2, DM-E-43, DM-E43-R1, DM-V-01, DM-V-09, DM-R-16, DM-R-17, DM-R-25, DM-R-27, DM-R-28, DM-C-07, DM-E08-R3, SL-A1-04, BP-A-28, PM-R-06 |
| `RoutingInputs` (the nine routing fields of an incident and the `inputs` JSON), `RuleMatcher` (DM-E43-R1 with the operators of DM-R-26; a field with no value is false for every operator) | DM-R-25, DM-R-26, DM-E43-R1 |
| `RuleSetValidation`: the problems (a) to (f) and the last-rule text in the order and with the texts of spec 6.6 (DC-TX-11 with BP-A-28), each with its place (DC-S08-04) | DM-E41-R1, PM-M10-03, DC-TX-11, DC-TX-06, DM-V-03, BP-A-28 |
| `ChangeSummary`: the change summary of spec 6.8 (rules matched by key; parts in their order; position among the rules both revisions hold; removed rules last; "no rules changed"; cut to 300) and the audit summary | DC-TX-10, BP-A-28 |
| `RoutingJson`, `RoutingRecords`: the stored JSON shapes of spec 6.3 (`IN` / `NOT_IN` values sorted without repeats, integer values as JSON numbers without leading zeros, key lists sorted) and the tables (every statement filters by `tenant_id`); `matchedRuleName` read from the revision used | DM-E-41 to DM-E-43, DM-C-06, DM-C-10, PM-R-05 |
| `RoutingImporter` (`RoutingImport`): under the advisory lock, steps 0 to 4 of spec 6.5 with their texts in order (step 3 only when step 2 found nothing), refusal `INVALID` "Part routing refused." with `problems`; revision 1 of both with `saved_by` `vigil-setup`, the change summary against no earlier revision; no audit event, no event | DD-F-05, DM-R-57, DM-E-41, DM-E-42, PM-M10-04, SL-B-09, DM-C-07 |
| `vigil.setup.RoutingAdapter` (part 11): one walk over the schema of spec 6.5 with the six shape problem forms in the walk's order, `refuseIfAny`, `RoutingImport.importPart`, counts `{"rules": n, "escalation_levels": m}`; `SeedShapes.text` made package-private for it | AR-B-11, DD-F-05, BP-R-29 |
| `vigil.setup.RoutingTeamCheck` (part 11 → part 2) and `RoutingKeyCheck` (part 11 → part 5) with the texts of spec 6.7 | DM-R-60, BP-R-29 |
| `README.md` "Module code": the lines of spec 6.9 | P15-DC-02 |

Checked by review (spec part 4): AR-P-06 (all three tables `SELECT, INSERT`; `PrivilegeIT` with the three rows); DM-C-04 (no `DELETE`, no `UPDATE`; a revision is never changed, a save inserts the next one); DM-C-06 (incidents held by id, teams, components and services by key; no foreign key across schemas, `SchemaRuleTest`); DM-C-10 (every statement of `RoutingRecords` filters by `tenant_id`, the rule name lookup by the evaluation's own tenant; `RoutingEvaluationIT` DM-E-43 reads tenant A's evaluation from tenant B); PM-R-01 (`SchemaRuleTest`); PM-R-02, PM-R-03 (`ModuleStructureTest`; `vigil-routing` calls only `IncidentService`, `ServiceMapService`, `IdentityService` and `AuditService`); PM-R-05 (`TenantColumnIT`; every request takes a `TenantId`); PM-R-06 (`RoutingRulesIT` DM-C-07); PM-R-08 (`SeparationTest`).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.routing.RuleMatcherTest` (new) | DM-R-26, DM-E43-R1 |
| `vigil.routing.RuleSetValidationTest` (new) | DM-E41-R1, PM-M10-03, DC-TX-11, DC-TX-06, DM-V-03, BP-A-28 |
| `vigil.routing.ChangeSummaryTest` (new) | DC-TX-10, BP-A-28 |
| `vigil.routing.RoutingEvaluationIT` (new) | DM-R-25, PM-M10-02, DM-E-43, DM-V-09, SL-A1-04, DM-E43-R1, BP-A-28, DM-E41-R2, DM-R-17, DM-R-16, DM-R-28 |
| `vigil.routing.RoutingRulesIT` (new) | PM-M10-01, DM-V-01, DM-C-07, DM-E08-R3, DM-E-41, DM-R-27, PM-M10-03, DM-E41-R1, BP-A-28 |
| `vigil.routing.RoutingImportIT` (new) | DM-E-42, PM-M10-04, SL-B-09, DM-R-57 |
| `vigil.routing.RoutingFixture` (new public test helper) | the routing fixture of spec 7 |
| `vigil.setup.SeedImportIT` (changed as spec 6.9 (1) to (3)) | new: DD-F-05, DD-K-05, AR-B-11, DM-R-60 (three methods), BP-R-29 |
| `vigil.setup.SeedImportRestartIT` (changed as spec 6.9) | AR-R-24 |

Every row of spec part 4 with proof `test` has a method whose display name starts with its ID; `tools/trace.py --check` passes. The new `*IT` classes run their methods in the order of the Checks column, each on its own fresh database with `MutableClock` as the `@Primary` `Clock`. Unchanged tests that cover this package pass: `ModuleStructureTest`, `SchemaRuleTest`, `PrivilegeIT` and `TenantColumnIT` (with the three new rows), `SeparationTest`, `MigrateIT`, `AppStartIT`, `DoubleDeliveryIT`, `ContractIT`, `EntryPointTest` and the P-14 incident tests.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `tools/loc.sh origin/main HEAD` is 2,070, above the 2,000 of P15-DC-01 (Section 9). The files that make it: `Routing.java` 337, `RoutingRecords.java` 235, `RoutingImporter.java` 224, `setup/RoutingAdapter.java` 207, `RoutingJson.java` 171, `RuleSetValidation.java` 130, `api/RoutingField.java` 101; the other 24 files 665. | P15-DC-01: a count above 2,000 and at most 3,500 does not stop the package; the owner decides at review whether P-15 is reclassed L. |
| 2 | Package-private classes not named in the spec: `Routing` (implements `RoutingService`), `RoutingImporter` (`RoutingImport`), `RoutingRecords` (the tables), `RoutingJson` (the stored JSON shapes), `RoutingInputs` (the routing fields of an incident), `RuleMatcher`, `RuleSetValidation`, `ChangeSummary`. | Internal structure only. |
| 3 | A `null` argument is refused with the message "a required argument is missing"; `importPart` also refuses a `null` element of `rules`, `levels` or a rule's `conditions` with `IllegalArgumentException` (spec 6.1 "null arguments"; the adapter never passes one). The step (1) refusals of `saveRuleSet` carry short messages of their own. | The spec gives no message for these. |

Readings of the spec where it gives no detail (no behaviour beyond the spec): `RoutingField.fromKey` is provided with the other enums' `fromKey`; the `INFO` line per saved revision is also written for the import's revision 1; the view returned by `evaluate` carries the inputs JSON in the order of DM-R-25, while views read back carry PostgreSQL's `jsonb` text (the same JSON tree); in the import, a `level` with leading zeros whose value is i + 1 (for example `01`) is accepted, as "1 to 9 digits, value i + 1" reads.

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

Assumption of spec 11 (`evaluate` and `setAssignment` in the caller's transaction need no lock of their own in `vigil-routing`): built as stated; `evaluate` does not catch the `IllegalStateException` of `setAssignment`. The obligation on P-17 stands (Section 11 item 2).

## 9. Lines of production code added and changed (P15-DC-01)

`tools/loc.sh origin/main HEAD` prints **2,070** (above 2,000, at most 3,500: deviation 1 of Section 3).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 2: `vigil-app` 117 unit tests and 313 `*IT` (every earlier test passes; `SeedImportIT` and `SeedImportRestartIT` changed only as spec 6.9 and Section 7), `demo-control` 1 and 2; the web build, lint, type check and Vitest pass; step 3: `ruff` clean, 81 Python tests passed, 1 skipped and 35 deselected (no file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` changed, so the lab tests did not run, CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose file valid; step 7: `trace.py --check`.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no API operation, no screen, no listener, no event; the module operations of spec 6.4 only); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT` pass; no listener and no external call added); BP-DC-05 (one migration named as AR-P-05; no OpenAPI operation; the part 11 adapter follows AR-B-11); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report); P15-DC-01 (Section 9); P15-DC-02 (`README.md`); P15-DC-03 (this section).

## 11. Follow-up items for later packages

1. P-16: `RoutingService.evaluate(tenant, Actor.module("vigil-correlation"), incidentId, CORRELATION)` inside the correlation sequence, before `open` (DM-R-15); the evaluation is applied only while the incident is `forming` and not frozen.
2. P-17: `evaluate` with `WORKFLOWS` from `vigil-workflows` before a live run (PM-I-16). It MUST NOT race with the incident's `open` (for example by making it within the correlation sequence), or P-17 accepts the rollback of its transaction by `setAssignment`'s `IllegalStateException` (spec Section 11).
3. P-22: `escalationPolicy` gives the current revision's levels (sorted team keys) and its revision number for `IncidentService.recordEscalation`.
4. P-25, P-29: `evaluation(tenant, assignmentEvaluationId)` gives the rule (`matchedRuleKey`, `matchedRuleName`) of an incident's assignment; `evaluations` lists an incident's evaluations by `at`, then id.
5. P-36: `evaluate` with `TEST_REPLAY` from `vigil-workflows` is always a dry run, also on a closed or merged incident, and returns the revision used; `ruleSetRevision` reads that revision.
6. P-38: `saveRuleSet` answers `IllegalArgumentException` for a request the screen cannot send (→ 400 `bad_request`), `FORBIDDEN`, `CONFLICT` (default message) and `INVALID` (default message) with `problems` and `places` (`{"rule", "field", "condition"}`, `null` values for the list text); `RoutingField.displayName`, `allowedValues`, `operators` give the screen's choices; new rules are sent with `ruleKey` `null`.
