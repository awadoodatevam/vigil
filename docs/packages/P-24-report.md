# P-24 — Report: Actions and approvals

| Field | Value |
| --- | --- |
| Package | P-24 Actions and approvals (spec `docs/packages/P-24.md`) |
| Branch | `p-24-actions-approvals` |
| Pin date (BP-R-08) | 12 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command passes in this session (Section 10). No owner-run steps. |

## 1. What was built

The `@ApplicationModule` declaration of `vigil-actions` is unchanged. Every new public type is in `vigil.actions.api`; every other new class is package-private and in the spec's complete list (spec 6.3), except records and an enum nested inside those classes (`ActionRecords.Proposal`, `ActionRecords.Execution`, `ActionRecords.ExecutionState`, `StateChecks.Outcome`, the private records of the steps). `ActionsStepHandler`, `ActionRuns` and `ApprovalDecided` are unchanged. No production code of `vigil-engine`, `vigil-incidents`, `vigil-knowledge`, `vigil-runner`, `vigil-notifications`, `vigil-recovery` or `vigil-identity` changes. No `vigil-api` operation, screen or setting is added, and `vigil-actions` gets no event listener (AR-Q-05). `ActionSteps` and `ActionRunReads` are injected by no other bean (spec 6.1).

| Part | Requirement IDs |
| --- | --- |
| Migration `actions/V20261112130000__actions_create_actions_tables.sql` (spec 6.2): the seven tables with their columns, checks, unique indexes (the two partial policy indexes of DM-E56-R1, one request and one execution per step execution of DM-R-64, one proposal per run of DM-R-29, one decision per request) and grants by class; seven rows in `table-classes.csv` | DM-E-55 to DM-E-60, DM-E56-R1, DM-R-64, AR-Q-06, AR-Q-08 |
| `vigil.actions.api` (spec 6.3, as written): `PolicyOutcome`, `RequestState`, `RefusalReason`, `Decision`, `StateCheckOperator`, `StateCheck`, `RegisteredActionView`, `ActionPolicyView`, `ApprovalRequestView`, `ActionsService`, `ActionsPart` with `ActionPart` and `PolicyPart`, `ActionsImport` | DM-V-08, AR-B-11 |
| `ActionRecords`: the seven tables, every statement filtered by `tenant_id`, no `DELETE`; the run's earlier approvers and latest executed execution | DM-E-55 to DM-E-60 |
| `StateChecks` (spec 6.5): the check run with diagnostic permission, the value of `result_field` of a `succeeded` run, the comparison as numbers when both sides are decimals, else by code point; a run not `succeeded`, a missing or `null` value, or a `running` run is no value | DM-V-08 |
| `Snapshots` (spec 6.5): the action snapshot, the approval snapshot with every key of DM-V-12 (evidence of the three kinds by `collected_at`, then id; `score` with two decimals; `observation_window` in seconds) and its SHA-256 hash of the RFC 8785 form | DM-V-12, AR-P-03, PM-M14-06 |
| `Proposals` (spec 6.4): the proposal from the latest similarity result with every "no proposal" case; the policy check, most restrictive over the affected services | DM-R-29, DM-E-57, DM-E56-R2, PM-M14-02, PM-M14-03 |
| `ApprovalStep` (spec 6.6): parameters; earlier calls; the first call in one transaction (proposal or "no proposal" with its technical row and audit event; sequence, teams, excluded users, expiry, snapshot; refusal before the policy check; the request with its audit events and evidence); the messages to every eligible approver through `notifyApprovalRequest`, with no transaction open, on each call before the expiry; the answer with the request locked, expiry; `cancel` | PM-M14-04, PM-S-05, DM-S-05, DM-R-30, DM-R-64, DM-SM-11, SL-B-03, SL-B-04, SL-A2-04, AR-Q-11 |
| `Decisions` (spec 6.7): one transaction with the request locked; the checks in order (`FORBIDDEN`, `NOT_FOUND`, then the seven refusals of DC-S05-06 with their details); the decision row, the request decided, its audit event (actor the user), its evidence entry and `ApprovalDecided` published in the transaction | PM-M14-05, DM-E-59, DM-E59-R1, DM-R-63, AR-E-09 |
| `ActionStep` (spec 6.8): one procedure for `start`, `evaluate` and `reconcile`: the execution recorded and committed before any runner request; the four rechecks in order, stopping at the first that fails, each stored when done; `executing` committed before the runner call; the result; reconciliation with three attempts 10 s apart; the final state with its evidence and audit event | PM-M14-07, PM-M14-08, PM-S-06, DM-S-06, DM-R-32, DM-R-33, DM-E-60, DM-E60-R1, DM-E60-R2, DM-SM-12, DM-E14-R1, AR-TM-06, SL-B-11 |
| `ActionTexts` (spec 6.9, 6.10): the state texts, recheck names and details of DC-TX-01; the evidence summaries and sources of DC-TX-03; the audit summaries of DC-TX-10 | DC-TX-01, DC-TX-03, DC-TX-10, PM-M14-09, DM-E08-R1, DM-E08-R3 |
| `ActionSteps` (the step handler), `ActionRunReads` (the six members of `ActionRuns`), `Actions` (the reads and `decide`) | DM-R-31, DM-R-34 |
| `ActionsImporter` (spec 6.11): every check in its order, the part refused as a whole, `registered actions already exist`, every action and policy stored `active` with `updated_by` `vigil-setup` | DM-E-55, DM-E-56, DM-E56-R1, DM-R-57, DD-F-04 |
| `vigil-setup`: `ActionsAdapter` (part 14, counts `{"registered_actions": n, "action_policies": m}`), `ActionOperationCheck` (14 → 4), `ActionRecoveryCheck` (14 → 13), `ActionServiceCheck` (14 → 5), `KnowledgeActionCheck` (8 → 14); with part 14 every part of DM-R-56 is registered | DM-R-56, DM-R-57, DM-R-60, AR-B-11, PM-M14-01 |
| `README.md` "Module code": the P-24 bullet and the four `- P-24:` bullets marked `(built)` (Section 3, item 12). `CLAUDE.md` unchanged. | BP-DC-06 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `actions/ActionTextsTest` (3 methods) | DC-TX-03, DC-TX-10, DC-TX-01 |
| `actions/StateChecksTest` | DM-V-08 |
| `actions/SnapshotsTest` (2 methods) | AR-P-03, DM-V-12 |
| `actions/ApprovalStepIT` (18 methods; five fixture tenants: the demo part 14, `preauthorized`, `blocked` for `svc-balance-check`, attempt limit 2, `preauthorized` with `approval_required` for `svc-balance-check`) | SL-A1-06, DM-E-58, DM-E-57, PM-M14-06, PM-M14-04, PM-S-05, DM-S-05, DM-R-29, PM-M14-02, SL-B-04, PM-M14-03, DM-E56-R2, DM-R-30, SL-A2-04, DM-SM-11, SL-B-03, DM-R-64, DM-E08-R3 |
| `actions/DecisionIT` (5 methods) | DM-E59-R1, PM-M14-05, DM-E-59, DM-R-63, AR-E-09 |
| `actions/ActionStepIT` (15 methods; the lab target `lab-db-01` injected before each method and cleared after; a `@Primary` test `RunnerService`) | PM-M14-07, PM-S-06, DM-S-06, DM-E-60, DM-E60-R1, DM-E14-R1, PM-M14-09, DM-R-32, DM-R-33, AR-TM-06, PM-M14-08, DM-E60-R2, DM-SM-12, SL-B-11, DM-E08-R1 |
| `actions/ActionRunsIT` (2 methods) | DM-R-31, DM-R-34 |
| `engine/ActionsEngineIT` (2 methods; the engine with the real beans of `vigil-actions` and the test handlers of the other modules) | AR-Q-11, DM-R-34 |
| `setup/ActionsImportIT` (9 methods) | DM-E-55, DM-E-56, DM-E56-R1, PM-M14-01, DD-F-04, DM-R-56, DM-R-57, DM-R-60, AR-B-11 |
| `connectors/ExternalCallGuardIT.actionsEntryPoints` | AR-Q-04 |
| `TableClasses` rows (existing privilege and tenant tests) | AR-Q-06, AR-Q-08 |

Test support: `actions/ActionsFixture` (public, spec 6.12): a `NotificationsFixture` tenant with the lab target's secrets written with `TestSecrets.replace`, its two credential rows and connector instance as rows, part 4 through `RunnerImport` with those handles, part 13 through `RecoveryImport`, part 14 through `ActionsImport` (the demo part read from `04-operations-actions-recovery.yaml`, or a given one), the grant `approve_action` of `noc-l1`; helpers for incidents (with and without affected services, without hypothesis), the PI-1001 and `no_match` results, evidence of the three snapshot kinds, step calls, inserted executions and the rows, audit events and evidence entries of a run. The test applications use a `MutableClock` as the `Clock` bean (as `routing/EscalationStepIT`).

Existing tests changed only as spec 6.12 lists: `SeedImportIT` (`LatchImporter` becomes `HeldKeyCheck`, the `noReporting` application with `NoReportingBeans` and the `RecordingCheck` targeting part 17, `registeredPartsImport`, `handleMapIsPassedOn` with the grants of part 4 and part 14's counts, the BP-R-29 test on `noReporting` with `baseline_values: 3`, `keyCheckSkipped` on `noReporting`, `incidentsPartRegistered` renamed `DM-R-56 every part of the demo bundle is imported`); `NotificationsImportIT` (`inOrder`); `EngineAbsentHandlersIT` (both applications remove `actionSteps` and `actionRunReads`); `ExternalCallGuardIT` (one method); `table-classes.csv`. `TestHandlers`, `EngineRunIT`, `EngineWakeIT`, `EngineRestartIT`, `WorkflowRunIT`, `RecoveryImportIT`, `SeedImportRestartIT` and `ReportingImportIT` are unchanged.

## 3. Deviations from the spec

None in behaviour. Readings of points the spec leaves open (no document text changes):

1. `ActionStep` treats a runner answer `running` to the execute request (a repeated request id of a run a restart did not end, CTX-D-058 (9)) as `unknown`: the execution goes to `reconciling` (DM-R-33). Spec 6.8 step 3 names only `succeeded`, `failed`, `refused` and `unknown`; with step 3's own rule an `executing` execution is never executed again, so this answer can only come from an earlier, interrupted run.
2. A later Approval step execution of a run whose first found no proposal also stores its own row in `actions.step_without_proposal` and its own `approval_decided` audit event "No action was proposed." with its step execution as subject (spec 6.6 step 3.1 for each such step execution), so a second call of it writes nothing.
3. The `approval_valid` detail lists each approver once (`approved by policy` once for several preauthorized requests), sorted by code point (DC-TX-00 "Lists").
4. The Approval step parameters also refuse a team entry that is not a key (spec 6.6 step 1 "an array of distinct keys"), with the same message.
5. `ActionSteps.cancel` of a step type other than Approval and Action is `IllegalArgumentException("approval or action step expected")`, as `start` and `evaluate`.
6. The recipients of the messages are sent in username order (code point), each with the first eligible team in code point order of which the user is a member.
7. `ActionsImporter` checks the "second policy" problem for every policy, also when its action key is not an action of the part; `parameter_values`, `precondition` and `reconciliation` given as YAML `null` count as absent.
8. `KnowledgeActionCheck` accepts any registered action of the tenant (part 14 stores every action `active`).
9. The comparison of the `unchanged` recheck compares the action snapshot built now and the stored one as JSON trees after both are written as text (Jackson keeps `int` and `long` number nodes apart, so a tree built in memory and one read back from `jsonb` would otherwise differ for the same numbers).
10. The execution evidence of a reconciled execution carries the output values of the execute run when the runner stored them (spec 6.9 reads them through `RunnerService.operationRun`), so an `unresolved` execution whose command did run may read `Result unknown; {n} files removed ({g} GiB).`.
11. `ActionsEngineIT` is in the package `vigil.engine`, next to `TestHandlers`, `TestContents` and `EngineFixture` that it uses.
12. `README.md`: four bullets begin `- P-24:` (service map, engine, runner, knowledge) and are marked `(built)`; the incidents list has the joint bullet `- P-23, P-24: IncidentService.addEvidence with their kinds.`, which does not begin `- P-24:` and stays as P-23 left it.
13. The migration also checks the Key format of every key column, the JSON types of the `jsonb` columns and `jsonb_array_length(limits) >= 1`, as earlier migrations do.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 12 November 2026)

None: no dependency is added or changed.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None: the spec has no owner-run steps. The Action step ran the demo action on the `demo-lab` image in the `test` profile (`ActionStepIT`, `ActionsEngineIT`); exact `demo` values are checked by P-27's Mac run and P-32.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

LOC_RESULT (BP-R-05 limit 3,500; spec estimate about 3,350, between 3,100 and 3,600). The first complete build counted 3,586; `ActionStep` and `ActionRecords` were then simplified without a change in behaviour (the execution's changeable columns as fields instead of eight-argument copies; the reads of a request, a proposal and a "no proposal" row by column), and every test was run again.

## 10. Gate command result

GATE_RESULT

Done-check 3 (spec Section 8): the log lines of `vigil.actions` in the `ActionStepIT` run are only the two `WARN` lines of spec 6.1 (ids and the state key); none holds a parameter value, an output value or a secret (LOG_CHECK).

## 11. Follow-up items for later packages

- P-25: the API operations AR-I-27, AR-I-34 and AR-I-35, the topic `approval`, and the display reads of proposals, requests, decisions and executions (DC-S03-10, DC-S05: the page content from the stored snapshot, whether the signed-in user may act, the refusal that applies before a decision); `ActionsService.decide` is the decision operation's request (its refusals carry the DC-S05-06 text and the details `refusal`).
- P-29 (Response section) and P-30 (Approval page) show the stored snapshot, the policy result, the approvals and the executions with their rechecks.
- P-35: the second Approval step `s-approve-2` of workflow version 2 (the run's proposal, sequence 2 and the excluded earlier approvers are built and tested with test content).
- P-36: the test replay of the Approval and Action steps.
- P-26 ends BP-R-29 formally: from P-24 every part of DM-R-56 is registered, so the demo bundle skips no section.
- P-27 (Mac run) and P-32: the demo values of the action on the `demo` profile.
