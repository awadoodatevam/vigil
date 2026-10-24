# P-17 — Report: Engine

| Field | Value |
| --- | --- |
| Package | P-17 Engine (spec `docs/packages/P-17.md`) |
| Branch | `p-17-engine` |
| Pin date (BP-R-08) | 24 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built; the gate command passes in this session (without the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-engine` is in `vigil.engine.api`; every other class is package-private in `vigil.engine`. The `@ApplicationModule` declarations of every module are unchanged. The public types of `vigil.engine.api` name no type of another module's `api` package (only `vigil.shared` types: `StepCall.StepType`, `Waiting.WakeEvent`, `Completed.ResultRef`, `Actor`, `TenantId`). `@Profile("!migrate")` is on exactly `RunExecutors`, `EngineWork` and the four listeners; `Engine` (the `EngineService`), `Runs` (the cancellation) and `HandOffs` obtain `RunExecutors` through `ObjectProvider`, so in the `migrate` profile nothing is handed off.

| Part | Requirement IDs |
| --- | --- |
| `vigil.engine.api` (spec 6.4, complete list): the enums `RunMode`, `RunState`, `WaitReason`, `EndReason`, `StepExecutionState` (each with `key()` and `fromKey`); the records `RunView`, `StepExecutionView`; the events `RunStarted`, `StepChanged`, `RunFinished` (published on every end); the interfaces `EngineService`, `EngineScheduling` | DM-E-50, DM-E-51, AR-E-10, PM-M12-08 |
| The step handler interfaces (spec 6.4, complete list) with `start`, `evaluate` ("external call: not inside a transaction") and `cancel`: `DiagnosticsStepHandler`, `InvestigationStepHandler`, `ActionsStepHandler`, `RecoveryStepHandler`, `RoutingStepHandler`, `NotificationsStepHandler`, `TicketingStepHandler`; in `vigil.actions.api` the interface `ActionRuns` and the event `ApprovalDecided`. No implementation | AR-R-21, PM-M12-02, PM-S-12 |
| Migration `engine/V20261024111930__engine_create_engine_tables.sql` (spec 6.3): `workflow_run`, `step_execution`, `durable_timer`, all `stateful` (`SELECT, INSERT, UPDATE`), with the listed `CHECK`s, the two partial unique indexes of DM-E50-R1, the partial unique index of DM-E52-R1 and the listed indexes, each starting with `tenant_id`; foreign keys only `step_execution.run_id`, `durable_timer.run_id` and `durable_timer.step_execution_id`; three rows in `table-classes.csv` | DM-E-50, DM-E-51, DM-E-52, DM-E50-R1, DM-E52-R1, AR-P-06 |
| `EngineRecords` (the three tables; every statement filters by `tenant_id`; JSON as `jsonb`, `wake_events` as an array of keys in code point order; the wake marking as one `UPDATE … WHERE state IN ('running', 'waiting') AND (…) RETURNING id`) | DM-C-06, DM-C-10, PM-R-05, AR-R-20 |
| `Engine` (`EngineService`): `startLiveRun` as spec 6.5 (refusals, an existing episode run → empty, the reopen cancellation in the same transaction, the Trigger execution `completed` at once, `RunStarted`, `StepChanged`, `RunFinished` when the Trigger's `next` has no connection, the `INFO` lines, the hand-off after commit); the four reads | DM-E50-R1, DM-R-22, DM-SM-07, DM-S-01, PM-S-01, PM-M12-11 |
| `RunExecutors`: one queue per run processed by one virtual thread `run-{run id}`; hand-offs dropped before `resumeRuns()` and after `@PreDestroy`; scheduled hand-offs (retry, 5 s after a failed executor transaction) with the `TaskScheduler` in real time; the `ERROR` line of a failed executor transaction | AR-R-20, AR-R-27 |
| `RunSteps`: the cases (a) to (h) of AR-R-20 in one transaction that locks the run; part (1) starting a step (tenant check, incident state read for live runs: `merged`/`closed` → cancellation, `forming` → `waiting` with `incident_forming`) and re-evaluating; part (2) with no transaction open (the `StepCall` of spec 6.6 with the recovery check key and the earlier approvers from `ActionRuns`; operation `start` until a `Waiting` was stored; the engine's own Trigger, Condition and Wait steps; the Action step guard before the first call of a new Action step execution; `reconcile` for an interrupted Action step; the `ERROR` line per failed call); part (3) (discard and handler `cancel` when the run is no longer `running`; the pending timer cancelled; `Completed` with the checkpoint or the end of the run; `Waiting` with its timer and the hand-off when `wake_pending` is set; retries after 5 s, 15 s and 45 s; the run `failed` after the 4th attempt) | PM-M12-01, PM-M12-02, PM-M12-04, PM-M12-05, PM-M12-06, PM-M12-07, PM-M12-10, PM-S-12, PM-S-13, PM-S-14, DM-S-12, DM-R-16, DM-R-64, DM-R-67, DM-SM-07, DM-SM-08, DM-SM-09, AR-R-19, AR-R-21, AR-R-22, AR-TM-05, SL-B-03, SL-B-11 |
| `Conditions` (the 16 fields of DM-R-25 read from `IncidentView`; the operators of DM-R-26; no value, an unknown field or operator, or a value of the wrong form → false; branches in list order, else `otherwise`); `WaitStep` (modes `duration` and `until_event`, the due time kept in `{"due_at"}`); `ActionGuard` (the four conditions in DM-R-31's order with the texts of DC-TX-11 and the path rule over the engine's table of outputs) | DM-S-02, PM-S-02, PM-M12-03, DM-R-25, DM-R-26, DM-S-11, PM-S-11, DM-R-31, PM-M12-06, SL-B-04, DC-TX-11 |
| `RunContent` (the run's content as the engine reads it and the engine's own table of named outputs per step type; it does not use `vigil-workflows`), `StepHandlers` (each handler and `ActionRuns` through `ObjectProvider`; an absent handler is a failed call "no step handler for {step type key}" and gets no `cancel`; without `ActionRuns` no proposal, no approvers, no recovery check key, and `reconcile` is a failed call) | AR-R-21, CTX-D-055 (2), (3) |
| `Runs`: `StepChanged`, the end of a run (`RunFinished`, `INFO` line) and the cancellation of DM-SM07-T5 (each unfinished live run of the incident locked, its current step execution and its pending timer cancelled, the handler's `cancel` in the same transaction) | DM-SM07-T5, DM-R-22, PM-M12-11 |
| `EngineIncidentListener`, `EngineIntakeListener`, `EngineApprovalListener` (wakes per spec 6.7; the incident listener cancels on `merged`/`closed`; nothing for a tenant that is not active), `EngineAliasListener` (`matchActiveTenants`) | AR-R-20, DM-R-66, DM-R-70, AR-Q-05 |
| `EngineWork` (`EngineScheduling`): the timer poll of each active tenant every 1 s (`scheduleWithFixedDelay`; due timers `fired` in `due_at`, then id, order; their runs marked `timer`; the `WARN` line on failure); `resumeRuns()` once (restart step 4: accept hand-offs, the set of active tenants, overdue timers fired, waiting runs woken with `restart`, retries of running runs from zero, every unfinished run handed off); `matchActiveTenants()` (timer polls; a tenant active again resumed as at restart); `scheduledTenants()`; `ActiveTenants` (the `volatile` set) | AR-R-23, AR-R-24, AR-R-19, DM-E52-R1, DM-R-66, DM-R-70 |
| `RestartProcedure`: `step4StartRunExecutors()` calls `EngineScheduling.resumeRuns()`; `step6StartScheduledWork()` also calls `EngineScheduling.matchActiveTenants()` | AR-R-24 |
| `vigil-correlation` (spec 6.8): `CorrelationWork`'s worker loop and decision retry catch `Throwable`; `stopAll()` is `synchronized` and marks the work closed; `matchActiveTenants()` of closed work starts no worker | AR-R-17, AR-R-26 |
| `README.md` "Module code": the lines of spec 6.9 | P17-DC-02 |

Checked by review (spec part 4): AR-P-06 and DM-C-04 (three `stateful` tables with `SELECT, INSERT, UPDATE`, no `DELETE`; `PrivilegeIT` with the three rows); DM-C-06 (incidents, versions, approval requests and other modules' records held by id; `current_step_execution_id` and `timer_id` have no foreign key; no foreign key across schemas, `SchemaRuleTest`); DM-C-10 and PM-R-05 (every statement of `EngineRecords` filters by `tenant_id`; every request takes a `TenantId`; `EngineWakeIT` uses three tenants; `TenantColumnIT`); PM-R-01, PM-R-02, PM-R-03 (`SchemaRuleTest`, `ModuleStructureTest`); PM-R-08 (`SeparationTest`).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.engine.ConditionsTest` (new) | DM-R-25, DM-R-26, PM-M12-03, DM-S-02, PM-S-02 |
| `vigil.engine.WaitStepTest` (new) | DM-S-11 |
| `vigil.engine.ActionGuardTest` (new) | DM-R-31, DC-TX-11 |
| `vigil.engine.EngineRunIT` (new) | PM-M12-01, SL-B-03, DM-S-01, PM-S-01, PM-M12-02, AR-E-10, PM-M12-08, DM-R-16, PM-M12-11, DM-R-22, DM-E50-R1, DM-E-50, DM-E-51, DM-SM-07, DM-SM-08, AR-R-21, AR-R-27, PM-S-12, PM-S-13, PM-S-14, DM-S-12, DM-E-52, DM-SM-09, PM-M12-05, DM-S-11, PM-S-11, DM-R-64, AR-R-22, AR-TM-05, PM-M12-06, SL-B-04, PM-M12-07, AR-R-23 |
| `vigil.engine.EngineWakeIT` (new) | AR-Q-11 (cases (a), (b), (d), (f)), DM-R-70, AR-R-19, DM-R-67, AR-R-20, AR-Q-05 |
| `vigil.engine.EngineRestartIT` (new) | AR-R-24 (also AR-Q-11 (c)), PM-M12-04, PM-M12-10, SL-B-11, DM-R-66, DM-E52-R1 |
| `vigil.engine.EngineAbsentHandlersIT` (new) | AR-R-21 (two methods: an absent handler; no `ActionRuns`) |
| `vigil.correlation.CorrelationRobustnessIT` (new) | AR-R-17, AR-R-26 |
| `vigil.engine.EngineFixture`, `TestHandlers`, `TestContents` (new public test support) | the test support of spec 7 |
| `vigil.correlation.CorrelationFixture` (changed as spec 6.9) | the listed methods public; nothing else changed |
| `vigil.tenancy.TenancyIT` (changed as spec 6.9) | AR-E-14: five publications, with `vigil.engine.EngineAliasListener` |

Every row of spec part 4 with proof `test` has a method whose display name starts with its ID; `tools/trace.py --check` passes. The new `*IT` classes run their methods in the order of the Checks column, each on its own fresh database with the secrets of `IntakeFixture.secrets`, a WireMock server stubbed with `IntakeFixture.stub`, a `MutableClock` from `2026-10-24T10:00:00Z` as the `@Primary` `Clock`, and (except `EngineAbsentHandlersIT`) `TestHandlers.Beans`. `CorrelationRobustnessIT` fails on the code before spec 6.8 (an `AssertionError` from `evaluate` ends the worker thread, so no "(attempt 1)" line is written). Unchanged tests that cover this package pass: `ModuleStructureTest`, `SchemaRuleTest`, `PrivilegeIT` and `TenantColumnIT` (with the three new rows), `SeparationTest`, `MigrateIT`, `AppStartIT`, `DoubleDeliveryIT`, `ContractIT`, `EntryPointTest`, and the intake, incident, routing, correlation and seed import tests.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Package-private classes not named in the spec: `Engine` (implements `EngineService`), `EngineRecords` (the tables), `RunSteps` (spec 6.6, used by `RunExecutors`), `Runs` (events, the end of a run, the cancellation), `RunContent` (the content and the table of outputs), `StepHandlers` (the handler and `ActionRuns` beans), `HandOffs` (hand-offs after commit through `ObjectProvider<RunExecutors>`). | Internal structure only. |
| 2 | `matchActiveTenants()` writes the engine's set of active tenants before it resumes a tenant that is active again, where spec 6.7 lists the resumption first. | The resumption hands the runs off after its commit; an executor that read the set before it was written would find the tenant not active (case (a)) and the runs would stay where they were. The order changes no record. |
| 3 | A step call that ends by an `Error` (not only an `Exception`) is a failed call, retried as AR-R-22; an executor transaction that ends by an `Error` gets the `ERROR` line and the hand-off after 5 s. | As CTX-D-054 (3) for the correlation worker: an `Error` would otherwise end the executor thread and leave the run until the next restart. |
| 4 | `startLiveRun` refuses a `null` `workflowVersionId` with "a required argument is missing". | A live run is started on a version (DM-E-50). |
| 5 | Part (3) also discards the answer (and calls `cancel`) when the run is `running` but its current step execution is no longer the one called or not `running`. | Defensive only: no path of the engine reaches it. |
| 6 | Test helpers beyond the ones spec 7 names: `EngineFixture.WORKFLOWS`, `run`, `executions`, `execution`, `awaitPublications`; `TestHandlers.calls(runId)`, `completed(output)`, `PROPOSAL`, the setter `reconcile`; `EngineFixture.formingIncident` waits until the alert is decided with `CorrelationFixture.awaitDecided`. | Test support only. |

Readings of the spec where it gives no detail (no behaviour beyond the spec): when a run fails after the 4th attempt, the step execution keeps `failed_attempts` 4. A timer's due time is stored truncated to milliseconds (AR-P-01). A Trigger step reached as a checkpoint (not possible in valid content) is answered `next` by the engine. Restart step (4) changes only the current step executions of `running` runs whose `retry_due_at` or `failed_attempts` is set. When a cancellation cancels a step execution, its `timer_id` is kept (spec 6.6 clears it only when a step answers). A `null` argument of a read is refused with "a required argument is missing" (as P-14 to P-16). In a closed application, a call that returns after the close cannot store its answer (the connection pool is closed): the executor writes its `ERROR` line and schedules nothing; `EngineRestartIT` shows these lines for R1 and R2.

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

## 9. Lines of production code added and changed (P17-DC-01)

`tools/loc.sh origin/main HEAD` prints **2,989** (size L, at most 3,500).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0, on the first run. Step 2: `vigil-app` 130 unit tests and 405 `*IT` (every earlier test passes; `TenancyIT` and `CorrelationFixture` changed only as spec 6.9), `demo-control` 1 and 2; the web build, lint, type check and Vitest pass; step 3: `ruff` clean, 81 Python tests passed, 1 skipped and 35 deselected (no file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` changed, so the lab tests did not run, CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose validation; step 7: `tools/trace.py --check`.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no API operation, no screen, no setting; no handler implementation); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT` pass; four listener classes with six listener methods, each in AR-Q-05 in `EngineWakeIT`; the step calls run with no transaction open, as every call recorded by `EngineRunIT` shows); BP-DC-05 (one migration named as AR-P-05; no OpenAPI operation; no seed part); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report); P17-DC-01 (Section 9); P17-DC-02 (`README.md`); P17-DC-03 (this section).

## 11. Follow-up items for later packages

1. P-41: `EngineService.startLiveRun` checks only the shape of spec 6.5 (a list of steps with exactly one Trigger). A run whose content names a step with an unknown type, or connects to a step id that is not in the content, fails each executor transaction that starts that step (the `ERROR` line and a new hand-off every 5 s); seed part 16 and P-35's validation keep such content out.
2. P-18 to P-24: each handler's `start` and `evaluate` are called on the run's virtual thread with no transaction open, with `evaluationCount` already stored; `cancel` joins the engine's transaction, is called again by part (3) when an answer arrives after a cancellation, and MUST give the same result twice. A throw is a failed call (retries 5 s, 15 s, 45 s).
3. P-24: `ActionRuns.reconcile` is called with wake reason `resume` for an Action step execution without a named output after a restart, also when the step was interrupted before its first call; `proposalOfRequest` is read for the `result_ref` id of the run's latest completed Approval step execution; `ApprovalDecided` wakes a waiting run only at its current step execution.
4. P-25, P-29: `StepChanged` is published for `running` (each start and each re-evaluation), `waiting`, `completed` and `cancelled`; `RunFinished` on every end.
5. P-36: the run columns of test mode exist and stay empty; `RunSteps` treats every run as live except for the incident state read, which is skipped for test-mode runs.
