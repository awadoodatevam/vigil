# P-43 — Report: Workspace sections, Acknowledge, Approval and dashboard API operations

| Field | Value |
| --- | --- |
| Package | P-43 Workspace sections, Acknowledge, Approval and dashboard API operations (spec `docs/packages/P-43.md`) |
| Branch | `p-43-sections-ack-approval-api` |
| Pin date (BP-R-08) | 12 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command passes in this session (Section 10). No owner-run steps. |

## 1. What was built

`vigil-api`'s `@ApplicationModule` declaration is unchanged. No migration, table, event, setting or screen is added. The diff adds no SQL and no `JdbcClient` use under `vigil/api/` (done-check 3: `git diff origin/main -- app/src/main/java/vigil/api` holds no `JdbcClient`, `SELECT`, `INSERT` or `UPDATE`). Every answer is a record in `vigil.api` with snake-case names; every listed field is always written (empty values as `null`, empty lists as `[]`).

| Part | Requirement IDs |
| --- | --- |
| `vigil-actions` (spec 6.2): the public enum `ExecutionState` (replaces `ActionRecords.ExecutionState`; `isFinal()` removed); the records `ProposalView`, `ActionExecutionView`, `DecisionRefusal`; `ActionsService.request`, `requests` (by `sequence_in_run`), `proposal` (with `created_at`, own row mapper), `withoutProposal`, `executions` (by `attempt_number`, `started_at`, id), one query each in `ActionRecords` | DC-R-08, PM-R-10 |
| `vigil-actions`: `ActionsService.decisionRefusal` runs checks 1 to 6 of P-24 Section 6.7 without a lock; `Decisions.decide` runs the same check method after its permission check and lock, before check 7 (one implementation of the checks) | DM-E59-R1, AR-I-03 |
| `vigil-incidents`: the record `AcknowledgeRefusal`; `IncidentService.acknowledgeRefusal` runs checks (2) to (4) of P-14 Section 6.7 without a lock; `acknowledge` runs the same check method after its lock and permission check | DM-E35-R1, AR-I-03 |
| Shared answer parts: `Refusal` `{key, message}`, `ParameterValue`, `Target`, `ActionParameters` (parameters in the order of the operation's definitions, others in code point order); `IncidentGuard.UUID_TEXT` made package-visible for the request guard | AR-I-06, AR-I-03 |
| `SectionsController` AR-I-25 `GET …/investigation` (`InvestigationSection`), AR-I-27 `GET …/response` (`ResponseSection`), AR-I-28 `GET …/recovery` (`RecoverySection`) | AR-I-25, AR-I-27, AR-I-28, AR-I-05 |
| `AcknowledgeController` AR-I-32 `GET …/acknowledgement` (`AcknowledgePage`; `acknowledge` or `view`) and AR-I-33 `POST …/acknowledgements` (`201` `NewAcknowledgement`) | AR-I-32, AR-I-33, AR-I-05, AR-I-07 |
| `ApprovalController` AR-I-34 `GET /api/v1/approval-requests/{request_id}` (`ApprovalPage`; `approve_action` or `view`) and AR-I-35 `POST …/decisions` (body read as a `JsonNode`, exactly `{decision, snapshot_hash_shown}`, else 400 before the request guard; `201` `NewDecision`) | AR-I-34, AR-I-35, AR-I-05, AR-I-07 |
| `DashboardController` AR-I-80 `GET /api/v1/dashboard` (`Dashboard`) | AR-I-80 |
| `vigil-api.yaml`: the eight operations with their responses (complete lists of spec 6.11), the request body schema `DecisionRequest`, and the schemas of the answers (`additionalProperties: false`, every field required, nullable fields with `'null'`, enumerations with their keys; `parameters[].value` untyped; `Refusal` nullable; `decided` and `acknowledged` the existing `Acknowledged`) | AR-I-08 |
| Test support (spec 6.12): `ApprovalApiFixture` with the fields and helpers of the spec; `ApiFixture.recoveryDefinitions`, `recoveryCall`, `recoveryStep`, `resolveAll`; `ContractIT` records and scenarios (spec 6.11) | — |
| `README.md` "Module code": the exact edits of spec 6.12 | BP-DC-06 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `api/ResponseApiIT` (1 method: before and after the SL-T-3 step, the proposal, the pending approval, the approval through AR-I-35 and the execution on the `demo-lab` image; `unavailable`; `no_match`; no live run; a merged incident) | AR-I-27 |
| `api/InvestigationApiIT` (1 method) | AR-I-25 |
| `api/RecoveryApiIT` (3 methods in a fixed order, on the recorded storm; the unhealthy-source case last) | AR-I-28, AR-I-05 |
| `api/AcknowledgeApiIT` (5 methods) | AR-I-32, AR-I-33, AR-I-05, DM-E35-R1, AR-I-07 |
| `api/ApprovalApiIT` (6 methods) | AR-I-34, AR-I-35, AR-I-05, DM-E59-R1, AR-I-03, AR-I-07 |
| `api/DashboardApiIT` (1 method) | AR-I-80 |
| `api/ContractIT` (existing methods; new scenarios: every response of the eight operations in the "Contract API" tenant) | AR-I-08 |
| `actions/ActionReadsIT` (2 methods) | DC-R-08, PM-R-10 |

Changed tests (spec 6.12): `actions/ActionTextsTest` imports `vigil.actions.api.ExecutionState`. `DecisionIT`, `ApprovalStepIT`, `ActionStepIT` and the acknowledgement tests of P-14 are unchanged and pass (done-check 4).

## 3. Deviations from the spec

None: every part of Sections 6 and 7 is built as specified.

Readings (no document change):

1. **Schema names.** The spec names only `Refusal`, `Acknowledged` and "new schemas" for the `201` answers; the file uses `NewAcknowledgement` and `NewDecision` for those, `DecisionRequest` for AR-I-35's body, and for the answers `InvestigationSection`, `InvestigationResult`, `ResponseSection`, `SimilarIncident`, `ProposedAction`, `RunApproval`, `RunExecution`, `ApprovalRequestState`, `ApprovalRefusalReason`, `RecoverySection`, `AcknowledgePage`, `ApprovalPage`, `Dashboard`, `ParameterValue` and `Target`.
2. **Nullable fields that the records make non-null in practice.** `Target.name` is nullable (AR-I-27 takes it from the run's first request, which a proposal always has); `RunExecution.attempt_limit` is nullable (from the run's proposal, which an execution always has); `ApprovalPage.incident.number` and `title` are nullable (the incident of a request is always numbered). These only make the schemas safe for records that cannot occur.
3. **`recheck`** is answered as typed items `{check, passed, detail}` parsed from the stored recheck JSON (P-24 Section 6.9), which is the stored JSON itself.
4. **The 400 of AR-I-35** includes a body that is not JSON or has another content type (Spring's own errors, mapped to `bad_request` by `ApiErrors`), after the permission check of the interceptor, as for every operation.
5. **`ContractIT`**: the request snapshots hold one limit and one recovery condition (fixed test values of the right types); the "unknown id" scenario of each operation is sent as a user who holds its permission (`presenter` for the `view` reads, `dbops-engineer` for the Acknowledge and Approval operations), so that it reaches the guard.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 12 November 2026)

None: no dependency is added or changed.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None: the spec has no owner-run steps.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

1,523 (BP-R-05 limit 3,500; spec estimate about 2,300, between 2,000 and 2,700). As in P-25 (CTX-D-078 (1)), the answers are records and the composition is plain Java; the refusal checks moved into shared methods rather than being added twice. By count the package is M (BP-R-05).

## 10. Gate command result

`tools/check-all.sh` passed in this session (exit 0, "all checks passed"): step (0) Docker, (1) setup version, (1a) test images, (2) `./mvnw -B verify` (197 unit tests and 776 integration tests in `vigil-app`, 0 failures, 0 errors; Spotless and `ApplicationModules.verify()` included; 25 min 12 s), (3) Python checks and tests (345 passed, 1 skipped, 35 deselected: the lab tests, since no lab path changes, BP-R-12 step (3)), (4) shellcheck, (5) data checks (49 checks, 0 failed), (6) compose validation, (7) traceability.

## 11. Follow-up items for later packages

- P-29: the AI investigation, Response and Recovery sections build their texts from AR-I-25, AR-I-27 and AR-I-28 (DC-TX-01 names, scores, the "Holding for" counter and the countdowns with the viewer's clock); the Response section is shown when `similar` is not `null`.
- P-30: the Acknowledge and Approval pages from AR-I-32 to AR-I-35 (`may_act` for the buttons and "View only.", `refusal` replacing them, viewer times from `acknowledged`, `decided`, `expires_at`).
- P-33: the dashboard tiles and the data line from AR-I-80.
- P-39: the re-run control's data in AR-I-25's answer and AR-I-26; the usage link uses `ai_last_request_group_id`.
