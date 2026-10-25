# P-41 — Report: Workflow definitions and live-run start

| Field | Value |
| --- | --- |
| Package | P-41 Workflow definitions and live-run start (spec `docs/packages/P-41.md`) |
| Branch | `p-41-workflows` |
| Pin date (BP-R-08) | 24 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-workflows` is in `vigil.workflows.api`; every other class is package-private in `vigil.workflows`. The `@ApplicationModule` declarations of every module are unchanged. The public types of `vigil.workflows.api` name no type of another module's `api` package (only `vigil.shared` types: `StepCall.StepType`, `Actor`, `TenantId`). `@Profile("!migrate")` is on `WorkflowsIncidentListener` only. Nothing is logged at `INFO` or above.

| Part | Requirement IDs |
| --- | --- |
| `vigil.workflows.api` (spec 6.4, complete list): the enums `VersionOrigin`, `StepParameterType` (each with `key()` and `fromKey`); the records `StepOutput`, `RequiredWhen`, `StepParameter`, `StepTypeDefinition`, `WorkflowView`, `WorkflowVersionView`, `WorkflowPart`; the interfaces `WorkflowService` (`stepTypes`, `workflow`, `version`, `activeVersion`) and `WorkflowImport` (`importPart`) | DM-E-44, DM-E-45, DM-E-47, AR-B-11 |
| `StepCatalogue` (`all()`, the table of spec 6.4 in its order, returned by `stepTypes()`) | DM-E-44, PM-M11-02 |
| Migration `workflows/V20261025093000__workflows_create_workflow_tables.sql` (spec 6.3): `workflows.workflow` (`configuration`: `SELECT, INSERT, UPDATE`; `UNIQUE (tenant_id)`; `active_version_id` without a foreign key) and `workflows.workflow_version` (`immutable`: `SELECT, INSERT`; `workflow_id` referencing `workflows.workflow`; `number >= 1`; `origin` `CHECK`; the three `published_by` Actor columns with their `CHECK`; `UNIQUE (tenant_id, workflow_id, number)`); two rows in `table-classes.csv` | DM-E-45, DM-E45-R1, DM-E-47, AR-P-06, DM-C-04, DM-C-06 |
| `WorkflowRecords` (the two tables; every statement filters by `tenant_id`; content and change list as `jsonb`) and `Workflows` (`WorkflowService`, the reads) | DM-C-10, PM-R-05 |
| `WorkflowImporter` (`WorkflowImport`) with `WorkflowPartCheck`: check (1) "workflow already exists", then checks (2) to (9) and the parameter checks (a) to (c) of spec 6.5 with the texts of the spec, all problems collected; `INVALID` "Part workflows refused." with `problems`, nothing stored; otherwise in one transaction the workflow and version 1 (`origin` `seed`, `published_by` `vigil-setup`, `change_list` `[]`) with the stored content of spec 6.5 (Durations without `_s`; `in`/`not_in` values as lists); no audit event, no event | DD-F-06, DM-R-57, PM-M11-01, SL-B-03, DM-E45-R1 |
| `WorkflowsIncidentListener` (spec 6.6): `onCreated` (`IncidentCreated`) and `onStateChanged` (`IncidentStateChanged` `RESOLVED` → `OPEN`), each an `@ApplicationModuleListener` in its own transaction; nothing for a tenant that is not active, a tenant without a workflow, or an incident that is not `forming` or `open`; on "created" nothing when `reopenCount` is above 0 or episode 1 has a live run, otherwise `RoutingService.evaluate(…, WORKFLOWS)` then `EngineService.startLiveRun(…, 1, active version, false)`; on a reopen episode `reopenCount + 1`, nothing when it has a live run, otherwise `startLiveRun(…, true)`, no routing evaluation | PM-M11-07, DM-E50-R1, DM-R-16, DM-R-22, DM-R-66, DM-R-70, AR-Q-05 |
| `vigil.setup.WorkflowsAdapter` (part 16: `SeedShapes.section(file, "workflow")`, `refuseIfAny`, the section as JSON text to `WorkflowImport.importPart`; counts `{"workflows": 1, "workflow_versions": 1}`); `WorkflowTeamCheck` (16 → 2) and `WorkflowKeyCheck` (16 → 5), both reading the active version's content through `WorkflowService.activeVersion` (with the helper `WorkflowKeys`), problems in step order, then value order, with the texts of spec 6.5 | AR-B-11, BP-R-29, DM-R-60, DD-F-06 |
| `README.md` "Module code": the lines of spec 6.7 | P41-DC-02 |

Checked by review (spec part 4): AR-P-06 and DM-C-04 (`workflow` `configuration` with `SELECT, INSERT, UPDATE`, `workflow_version` `immutable` with `SELECT, INSERT`, no `DELETE`; `PrivilegeIT` with the two rows); DM-C-06 (incidents and versions held by id; `active_version_id` has no foreign key; the only foreign key is `workflow_version.workflow_id` inside the schema; `SchemaRuleTest`); DM-C-10 and PM-R-05 (every statement of `WorkflowRecords` filters by `tenant_id`; every request takes a `TenantId`; `WorkflowRunIT` uses three tenants; `TenantColumnIT`); PM-R-01, PM-R-02, PM-R-03 (`SchemaRuleTest`, `ModuleStructureTest`: `vigil-workflows` uses `tenancy`, `incidents`, `routing` and `engine` through their `api` packages, all in its declaration); PM-R-08 (`SeparationTest`).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.workflows.StepCatalogueTest` (new) | DM-E-44, PM-M11-02 |
| `vigil.workflows.WorkflowImportIT` (new) | DM-E-45, DM-E-47, PM-M11-01, DM-E45-R1, DM-R-57 (every case of spec 7, 45 refusals, each on a new tenant with nothing stored) |
| `vigil.workflows.WorkflowRunIT` (new) | PM-M11-07, DM-R-16, DM-R-22, SL-B-03, DM-E50-R1, DM-R-66, AR-Q-05, DM-R-70 |
| `vigil.setup.SeedImportIT` (changed as spec 6.7) | `registeredPartsImport` (part 16 `SUCCEEDED`), `seedImportRecord` (part 16 counts), `incidentsPartRegistered` (renamed, list 7, 8, 12 to 15, 17); new: DD-F-06, AR-B-11, DM-R-60 (two methods), BP-R-29 |
| `vigil.setup.SeedImportRestartIT` (changed as spec 6.7) | part 16 (`parts().get(15)`) is the part failed "interrupted by restart" |
| `vigil.workflows.WorkflowFixture` (new public test support) | `create`, `demoPart`, `awaitRun`, fields `engineFixture`, `tenant` |

Every row of spec part 4 with proof `test` has a method whose display name starts with its ID; `tools/trace.py --check` passes. The new `*IT` classes run their methods in the order of the Checks column (`@TestMethodOrder(OrderAnnotation)`, `@TestInstance(PER_CLASS)`). `WorkflowRunIT` runs on its own fresh database with the secrets of `IntakeFixture.secrets` for fixtures A and B, a WireMock server stubbed with `IntakeFixture.stub`, a `MutableClock` from `2026-10-24T10:00:00Z` as the `@Primary` `Clock`, and `TestHandlers.Beans`; every JSON expectation compares parsed trees. In `WorkflowRunIT`, DM-E50-R1 makes the repeated deliveries and records the runs and evaluations before and after each; DM-R-66 asserts on those records.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Package-private classes not named in the spec: `Workflows` (implements `WorkflowService`), `WorkflowImporter` (implements `WorkflowImport`), `WorkflowRecords` (the tables), `WorkflowPartCheck` (the checks of spec 6.5 and the stored content); `StepCatalogue.ofKey` besides `all()`; in `vigil.setup` the helper `WorkflowKeys` (the keys of the active version's content, read once for both key checks). | Internal structure only. |

Readings of the spec where it gives no detail (no behaviour beyond the spec): the keys of `StepParameterType` are the lower-case names (`enum`, `enum_set`, `key_list`, `key_set`, `duration`, `condition_branches`). A part text that is not JSON counts as "not a JSON object" (`workflow: missing`). Identical problem texts are listed once (only possible for an `in`/`not_in` comparison with several values of the wrong form, whose texts carry no element position). In a list parameter an element gets one problem: "one of …" or "not a key" first, otherwise "used more than once". A branch label counts as an earlier label for "used more than once" only when it is a key other than `otherwise`. "The `step_id` of a step" is the `step_id` of a step that is a mapping with a `Key` `step_id`. The second-connection check compares connections whose `from_step_id` and `from_output` are both text, whether or not the step exists. A parameter whose value is `null` is not stored. `importPart` refuses a `null` `tenantId`, `part`, `importStartedAt` or `handles` with "a required argument is missing" (`WorkflowPart(null)` itself is `workflow: missing`). The listener reads the active version before the incident (the three "nothing" conditions give the same result in any order).

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 24 October 2026)

None (spec 6.2).

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

## 9. Lines of production code added and changed (P41-DC-01)

`tools/loc.sh origin/main HEAD` prints **LOC** (size M; at most 2,000).

## 10. Gate command result

GATE

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no API operation, no screen, no setting, no draft, publish or replay); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT` pass; one listener class with two methods, in AR-Q-05 in `WorkflowRunIT`; no external call); BP-DC-05 (one migration named as AR-P-05; no OpenAPI operation; the part 16 adapter and its two key checks follow AR-B-11); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report); P41-DC-01 (Section 9); P41-DC-02 (`README.md`); P41-DC-03 (this section).

## 11. Follow-up items for later packages

1. P-18: the key check of part 16 against part 12 (the `diagnostics` keys of Diagnostic steps); `vigil.setup.WorkflowKeys` lists the keys of the active version and can return them too.
2. P-35: `WorkflowPartCheck` checks only what seed part 16 checks (structure, parameters, exactly one Trigger, CTX-D-055 (5)); the draft validation of DM-E48-R1 is P-35's. `vigil-workflows` keeps its own table of the Condition fields and operators (spec 6.1), next to the engine's (`Conditions`) and routing's (`RoutingField`).
3. P-18 to P-24: with part 16 registered, every incident of a demo-seeded active tenant starts a live run; until the handlers exist, the run's first step that needs a handler fails after 4 attempts with `ERROR` lines and the run ends `failed` (as in `ContractIT`, spec 7).
4. P-36: test replays start runs through `vigil-engine` directly; `WorkflowsIncidentListener` starts live runs only.
