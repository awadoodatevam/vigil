# P-25 — Report: Incident list and workspace API operations

| Field | Value |
| --- | --- |
| Package | P-25 Incident list and workspace API operations (spec `docs/packages/P-25.md`) |
| Branch | `p-25-list-workspace-api` |
| Pin date (BP-R-08) | 12 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command passes in this session (Section 10). No owner-run steps. |

## 1. What was built

`vigil-api`'s `@ApplicationModule` declaration is unchanged. No migration, table, event, setting or screen is added. The diff adds no SQL and no `JdbcClient` use under `vigil/api/` (done-check 3: `git diff origin/main -- app/src/main/java/vigil/api` holds no `JdbcClient`, `SELECT`, `INSERT` or `UPDATE`). Every answer is a record in `vigil.api` with snake-case names; every listed field is always written (empty values as `null`, empty lists as `[]`).

| Part | Requirement IDs |
| --- | --- |
| `vigil-intake` (spec 6.2): `IntakeService.alertsReceivedSince` (count with `first_received_at >= from`) and `latestAlerts` (by `first_received_at`, then `sequence`, descending; `limit` 1 to 50), one query each in `IntakeRecords` | DC-R-08, PM-R-10 |
| `vigil-incidents` (spec 6.2): `IncidentService.incidentPage` (numbered incidents in the states, `number < beforeNumber`, by `number` descending, `limit` 1 to 51) and `formingSummary` with the new record `FormingSummary(incidents, alerts)` | DC-R-08, PM-R-10 |
| `vigil-engine` (spec 6.2): the enum `Stage`; `EngineService.stages` (one query: the latest live run of each incident and its latest `running` or `waiting` step execution; the step type mapped as DC-TX-01 "Stage", other types no entry); `pendingTimers` with the new record `TimerView` (by `due_at`, then id) | DC-R-08, PM-R-10 |
| `LiveProducers` (spec 6.3): one `@ApplicationModuleListener` per event (nine), each doing nothing for a tenant that is not active and otherwise publishing the row's topics in order with the record's id; test-mode run events publish nothing | AR-I-04, AR-R-19, PM-M23-04, AR-Q-05 |
| Shared answer helpers (spec 6.1): `TeamName`, `UserName`, `ActorName`, `ComponentName`; `Names` (each team, user and component asked once per answer; name order by code point, ties by key); `IncidentGuard` (not a UUID, unknown, `forming`: `not_found`; `merged`: header only); `IncidentCursor` (`limit`, `after` and the base64url cursor) | AR-I-06, DC-U-06 |
| `AlertStripController` AR-I-20 `GET /api/v1/alerts/strip` (`AlertStrip`) | AR-I-20, AR-I-05 |
| `IncidentsController` AR-I-21 `GET /api/v1/incidents` (`IncidentPage` with rows, paged by `limit` + 1) and AR-I-22 `GET /api/v1/incidents/{incident_id}` (`IncidentHeader`, or `MergedIncident` for a merged incident) | AR-I-21, AR-I-01, DC-U-12, AR-I-22 |
| `WorkspaceController` AR-I-23 `/alerts` (`IncidentAlerts`), AR-I-24 `/evidence` (`EvidenceLines`), AR-I-29 `/run` (`LatestRun`), AR-I-30 `/timeline` (`IncidentTimeline`), AR-I-31 `/notified-teams` (`NotifiedTeams`) | AR-I-23, AR-I-24, AR-I-29, AR-I-30, AR-I-31 |
| Every one of the eight operations is `@RequiresPermission(Permission.VIEW)` | AR-I-07 |
| `vigil-api.yaml`: the eight paths with `security: - session: []`, their parameters and responses, and their schemas (`additionalProperties: false`, every field required, nullable fields with `'null'`, enumerations with keys; `content` an open object; `output_values[].value` untyped; AR-I-22's `200` `oneOf` `IncidentHeader` and `MergedIncident`) | AR-I-08 |
| Test support (spec 6.13): `CorrelationFixture.create` with users and the field `identity`; `EngineFixture.create` with users; `KnowledgeFixture.identity`; `NotificationsFixture.password`; the new `ApiFixture` with the fields and helpers of the spec | — |
| `README.md` "Module code": the exact replacements of spec 6.13 | BP-DC-06 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `api/LiveProducersIT` (4 methods: every row of spec 6.3 through an HTTP stream within 1 s of the commit, a second tenant's stream and test-mode run events silent; a tenant that is not active; an acknowledgement and a step change; double delivery for each schema of `TableClasses.SCHEMAS` with each notice twice) | AR-I-04, AR-R-19, PM-M23-04, AR-Q-05 |
| `api/IncidentListIT` (5 methods) | AR-I-05, AR-I-20, AR-I-21, AR-I-01, DC-U-12 |
| `api/WorkspaceIT` (7 methods, on the recorded storm) | AR-I-22, AR-I-23, AR-I-24, AR-I-29, AR-I-30, AR-I-31, AR-I-07 |
| `api/ContractIT` (existing methods; new scenarios: every response of the eight operations, in the tenant "Contract API" of `NotificationsFixture`) | AR-I-08 |
| `intake/IntakeReadsIT` (2 methods) | DC-R-08, PM-R-10 |
| `incidents/IncidentReadsIT` (2 methods) | PM-R-10, DC-R-08 |
| `engine/EngineReadsIT` (2 methods) | DC-R-08, PM-R-10 |

## 3. Deviations from the spec

1. **`IncidentListIT`: `zabbix-lab` ends `disconnected`, not `stale`.** With the spec's mechanics (its connection test answers 500, the clock moves 121 s), the health evaluation first sets `stale` (contact age), but the connection test is then due and fails, and DM-R-40 as built in P-13 (`HealthRule`: a failed test with no contact after it) sets `disconnected` within a fraction of a second. The test therefore waits until `zabbix-lab` is `disconnected` and `alertmanager-lab` `healthy` again, and asserts `zabbix-lab` in `not_healthy` with health `disconnected`. Every other source assertion is as specified (the inactive third row `zabbix-extra` left out; `healthy` 1 of `total` 2). See Section 4, item 1.
2. **`IncidentListIT` DC-U-12: 45 more incidents, not 46.** The forming incident of the AR-I-21 row is the survivor of `mergedIncident()`; it opens when the clock first moves 41 s and so takes a number. The test opens incidents until exactly 51 are numbered (45 more) and asserts 51 before the paging check.
3. **`testsupport/DoubleDelivery` compares `bytea` values by content.** The spec requires the double-delivery check on every schema of `TableClasses.SCHEMAS`; `intake.webhook_call` stores its body as `bytea`, which JDBC reads as `byte[]`, and `Map.equals` compared those arrays by reference, so two reads of unchanged rows differed. `readAll` now writes each `byte[]` as hex text before comparing. This is the only change to an existing test helper outside spec 6.13's list; no existing test changes its result (the gate runs all of them).
4. **Second test methods of the three module-read tests.** The spec asks for one more method with the other ID "proving the same reads". Each proves the reads answer only the requesting tenant's records (an empty answer for another tenant) and return unmodifiable collections.
5. **`EngineReadsIT` builds the stage cases from rows inserted as `postgres`** (runs and step executions of each mapping row, a finished run, two live runs of one incident, test runs), so each case is exact; `pendingTimers` uses a real live run of `ApiFixture` plus the inserted rows, as the spec states.
6. **`ApiFixture` helper details the spec leaves open:** `diagnostic` uses source system `lab-db-01 via runner, operation disk-usage` and summary `/data is 100% full (0.1 GiB free)` with content keys as `DiagnosticStep` writes them; `ticketLink`'s URL is the WireMock address with `/browse/NOC-1`; `mergedIncident` waits until each alert is decided before posting the next (the clock does not move) and returns the absorbed incident; `acknowledge` uses the user's one team. These are fixture values only.

Readings (no document change): `received_last_10_minutes` carries an explicit JSON property name, since Jackson's snake case writes `received_last10_minutes`; the OpenAPI file holds, besides the schema names of the spec, the component schemas `AlertStrip`, `IncidentPage`, `IncidentRow`, `Acknowledged`, `Ticket`, `ComponentName`, `IncidentAlerts`, `Reason`, `EvidenceLines`, `EvidenceLine`, `LatestRun`, `LiveRun`, `IncidentTimeline` and `NotifiedTeams`; `not_healthy` ties of name are broken by source key, and groups of AR-I-23 with equal type names by alert type.

## 4. Document feedback (CTX-Q-8)

1. P-25 spec Section 7 row `api/IncidentListIT` ("zabbix-lab made `stale`: its connection test answers 500 … waits until zabbix-lab is `stale`") cannot hold with DM-R-40 as built: a failed connection test without later contact makes a source `disconnected` (`HealthCause.TEST_FAILED`), and the test is due as soon as the clock moves past `health_check_interval`. A stable `stale` source needs a source whose contact ages while no test runs (or whose test passes without counting as contact), which the product does not have. The test asserts `disconnected` (Section 3, item 1); DC-S02-02's "not healthy" list covers both healths.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 12 November 2026)

None: no dependency is added or changed.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None: the spec has no owner-run steps.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

1,540 (BP-R-05 limit 3,500; spec estimate about 2,300, between 2,000 and 2,700). The answers are records and the composition is plain Java, which came out smaller than estimated.

## 10. Gate command result

`tools/check-all.sh` passed in this session (exit 0, "all checks passed"): step (0) Docker, (1) setup version, (1a) test images, (2) `./mvnw -B verify` (197 unit tests and 757 integration tests, 0 failures, 0 errors; Spotless and `ApplicationModules.verify()` included; 24 min 3 s), (3) Python checks and tests (345 passed, 1 skipped; the lab tests skipped: no lab path changes, BP-R-12 step (3)), (4) shellcheck, (5) data checks (49 checks, 0 failed), (6) compose validation, (7) traceability.

## 11. Follow-up items for later packages

- P-43: AR-I-25, AR-I-27, AR-I-28, AR-I-32 to AR-I-35, AR-I-80 and the polled reads of AR-I-05 for the recovery section, the Approval page and the Acknowledge page; `ApiFixture` is ready for its tests (users, the storm, the workspace helpers).
- P-28 and P-29: the screens build the display texts from these answers (DC-TX-01 names, DC-TX-02 sentences from `IncidentAlerts.Reason` and `grouping_window_seconds`, DC-TX-05 card summaries from `team_names`, the collapsing of DC-S03-13 from `stable_grouping`, viewer times). The TypeScript types come from the OpenAPI file.
- P-35 (`workflow`) and P-36 (`test-replay`): the remaining live topics; test-mode run events publish nothing until P-36.
- P-38: a publisher of `TenantSettingsChanged`; the producer of the topic `tenant` is built and tested with a published event.
