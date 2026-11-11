# P-21 — Report: Ticketing and the Jira connector

| Field | Value |
| --- | --- |
| Package | P-21 Ticketing and the Jira connector (spec `docs/packages/P-21.md`) |
| Branch | `p-21-ticketing-jira` |
| Pin date (BP-R-08) | 11 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command passes in this session (Section 10). No owner-run steps. |

## 1. What was built

`vigil-ticketing` keeps its `@ApplicationModule` declaration unchanged (`shared`, `connectors :: api`, `intake :: api`, `incidents :: api`, `investigation :: api`, `tenancy :: api`, `audit :: api`); no other declaration changes. Every public type is in an `api` package; every other new class is package-private. Only `vigil.connectors` uses the HTTP client. Every statement filters by `tenant_id`; nothing is deleted. `vigil-ticketing` has no event listener (AR-Q-05, checked by review: no `@ApplicationModuleListener` or `@TransactionalEventListener` under `vigil.ticketing`).

| Part | Requirement IDs |
| --- | --- |
| `vigil-tenancy` (spec 6.2): `TenantService.publicBaseAddress` (`vigil.links.base` with `{host}` replaced by `publicHostName`, no trailing `/`, empty without an alias; read-only transaction); `application.properties` line `vigil.links.base=${VIGIL_LINKS_BASE:https://{host}}` | AR-I-100, BP-A-09 |
| `vigil-connectors` (spec 6.2): `ConnectorService.activeInstances` (new query in `ConnectorRecords`, name in code point order, then id); `ConnectorTimings.jira` (15 s, set in `ConnectorConfiguration`); the public types `TicketLine`, `TicketOutcome`, `TicketingConnector` (spec 6.4) | AR-K-00c, AR-TM-01 |
| `JiraConnector` (spec 6.5): the order of checks (guard, `null` arguments, kind, address, credential, secret `{email, api_token}`, settings), the Basic header, `Accept`, `Content-Type` on `POST`s, timeout 15 s, the failure reasons (`FailureReasons` with the token, the email and the base64 text hidden, `HTTP {status}`, the four answer reasons); `create`, `addComment`, `status`, `transition` (by target name, the id as the answer gives it), `findByLabel` (one search, the query encoded as the P-06 recording) | PM-K-04, AR-K-04, AR-K-14, AR-K-16, BP-A-29 |
| `Adf` (spec 6.5): one paragraph per line, the link as a second text node with a link mark, an empty line as `{"type":"paragraph"}`, key order as written | AR-K-15, DC-OUT-00 |
| Migration `ticketing/V20261111120000__ticketing_create_ticketing_tables.sql` (first statement `GRANT USAGE ON SCHEMA ticketing TO vigil_app;`): `ticket_link` and `ticket_sync_record` with the columns, checks and indexes of spec 6.3, grants `SELECT, INSERT, UPDATE`; two rows in `table-classes.csv` | DM-E-75, DM-E-76, AR-Q-06, AR-Q-08 |
| `vigil.ticketing.api` (spec 6.4, as written): the four enums with `key()` and `fromKey`, `TicketLinkView`, `SyncRecordView`, `TicketingService` | PM-M19-01 |
| `TicketTexts` (spec 6.6): incident number, the prefixed title cut to 255, the seven description parts, the reopen, resolve and escalation lines, durations by DC-U-07 rule 2, outbound times, `content_sent` of each operation, the audit summary and details | DC-OUT-00 to DC-OUT-04, DM-E02-R2, SL-DR-03 |
| `TicketStep` (spec 6.7): the step's own record first (finished: same answer, no call; `pending`: restart rules); create with an `open` link `done` without record; create with a `resolved` link reopens (comment, then transition to `reopened_status`); create without a link (first active instance, public address, the content, the `pending` record, the label search when an earlier create is `pending` or `failed` with content, the create, the link, superseding the other `pending` creates); resolve (no link `no ticket`, `resolved` link `done` without record, otherwise the recovery evidence, comment, transition to `resolved_status`); the restart rules (label search before a create; status before a reopen or resolve); the label search at most 4 times 5 s apart; failures at once stored directly `failed`; the answer `Completed` with `ResultRef("vigil-ticketing", "TicketSyncRecord", id)` | PM-M19-01 to PM-M19-04, PM-S-10, DM-S-10, DM-E76-R1, DM-E76-R2, DM-SM14-T1 to DM-SM14-T3, AR-K-14, AR-K-16, AR-I-100, SL-A1-05, SL-A1-07 |
| `Ticketing` (spec 6.8, 6.9): `ticketLink`, `syncRecords`, `recordEscalation` (one record per escalation, `pending` sent again with the lines built again, finished not sent again, `no ticket` without a link, the link's state unchanged); storing and finishing records with one `ticket_changed` audit event each and the `WARN` line for each record finished `failed` | PM-M19-01, PM-M19-03, DC-OUT-02, DM-E76-R2, DM-E08-R3 |
| `JiraInstances` (spec 6.7 "instance"): the first active `jira_cloud` instance, the link's own instance (inactive: `the Jira connector instance is not active`), the settings, the `TicketingConnector` bean of the instance's kind | AR-K-00c |
| Recordings (spec 6.10): `dev/fakes/recordings/jira/` copied byte for byte with its `README.md` to `app/src/test/resources/recordings/jira/`; `Recordings` public, reading `request.method` and `request.url` and replacing `${endpoint.jira}` | BP-R-06 |
| `README.md` "Module code": the P-21 bullet of spec 6.10. `CLAUDE.md` unchanged. | BP-DC-06 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `connectors/JiraConnectorIT` (3 methods) | AR-K-14, AR-K-16, AR-K-04 |
| `connectors/AdfTest` | AR-K-15 |
| `connectors/ConnectorInstancesIT` | AR-K-00c |
| `ticketing/TicketTextsTest` (6 methods) | DC-OUT-01, DC-OUT-02, DC-OUT-03, DC-OUT-04, DC-OUT-00, DM-E02-R2 |
| `ticketing/TicketStepIT` (21 methods) | PM-M19-02, SL-A1-05, SL-DR-03, DM-E-75, DM-E-76, DM-SM14-T1, PM-S-10, DM-S-10, AR-I-100, DM-E08-R3, DM-E76-R1, DM-SM14-T2, SL-A1-07, PM-M19-03, AR-K-16, PM-M19-04 (3 methods), DM-SM14-T3, DM-E76-R2, PM-M19-01 |
| `ticketing/RecordEscalationIT` (4 methods) | DC-OUT-02, PM-M19-01, AR-K-00c, PM-K-04 |
| `tenancy/TenancyIT` (one method added) | AR-I-100 |
| `connectors/ExternalCallGuardIT` (one method added) | AR-Q-04 |
| `TableClasses` rows (existing privilege and tenant tests) | AR-Q-06, AR-Q-08 |

Existing tests changed only as spec 6.10 lists: `EngineAbsentHandlersIT` uses `notification` for its step without a handler; `AiTimeoutsIT`, `ConnectionTestIT` and `FailureReasonsTest` pass the new `ConnectorTimings` component (15 s); `Recordings` as above. `TestHandlers` is unchanged.

## 3. Deviations from the spec

1. **Size.** `tools/loc.sh` counts 2,102 lines, above M's 2,000 (estimate about 1,950). Recorded as spec Section 1 says, for the reclass to L when this report is resolved. The excess is mostly in `TicketStep` (533 lines with Javadoc, estimate about 400) and `Ticketing` (308, which also holds the record storing and finishing that `TicketStep` and `recordEscalation` share).
2. Readings of points the spec leaves open (no document text changes):
   1. `findByLabel`: a 2xx whose first issue has no string `key` gives `Failed` `The answer has no issues.` (treated as unreadable, not as "none", so no second issue is made).
   2. `transition`: when the first entry whose `to.name` matches has no string `id`, the result is `No transition to status '{name}'.`.
   3. `create` and `findByLabel` read `project_key` (and `issue_type`) before checking the address, because the address of `findByLabel` contains it; a missing setting is an `IllegalStateException` either way.
   4. The issue key is put into the request paths as Jira gives it (Jira keys hold only letters, digits and `-`).
   5. A restarted record's rebuilt content replaces `content_sent` in the transaction that finishes it, not before the call.
   6. Finishing a record that is no longer `pending` throws `IllegalStateException` (a failed call), so a record is never finished twice and gets exactly one audit event.
   7. Restart of a `reopen` or `resolve`: the content is built again first (so a condition that now fails at once finishes it), then the status is read.
   8. `recordEscalation` with a `pending` record whose link is gone or whose instance is inactive finishes that record `failed` (`no ticket`, `the Jira connector instance is not active`) instead of storing a second one.
   9. An incident without a `number` makes the create step throw `IllegalStateException` (a failed call); a Ticket step runs only for open incidents, which always have one.
   10. `ConnectorService.activeInstances` uses the message of spec 6.1 (`a required argument is missing`); the older `ConnectorService` methods keep `{name}: required`.
   11. The `WARN` line is written for every record finished `failed`, including records that fail at once and records superseded by another create.
   12. The migration file starts with a one-line comment, then `GRANT USAGE …`, as every earlier migration does.

## 4. Document feedback (CTX-Q-8)

1. `docs/packages/P-21.md` Section 6.6.1 and CTX-D-069 (5): the demo title "Demo environment — fictional data - Subscriber DB server: Disk full — 3 services affected" has 89 characters, not 88. No effect on the build (the cut is at 255); `TicketTextsTest` checks 89.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 11 November 2026)

None. No dependency or pin changed.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None: the spec lists no owner-run steps. The connector is tested on the P-06 recordings; the real Jira is reached first by P-32.

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **2,102** (size M; estimate about 1,950; above 2,000, deviation 1 of Section 3).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1a built the local images. Step 2: `vigil-app` 179 unit tests and 607 `*IT` (every earlier test passes; the changed tests only as spec 6.10); `demo-control` 1 and 2; the web build, lint, type check and Vitest pass. Step 3: `ruff` clean; 341 Python tests passed, 1 skipped, 35 deselected (the lab tests: this package changes no lab path, BP-R-12 step (3)). Step 4: `shellcheck`. Step 5: 49 data checks, 0 failed. Step 6: compose validation. Step 7: `tools/trace.py --check`.

The gate ran on the tree of commit `874ff0f`; the later commit changes only this report. An earlier full run in this session failed once in `deploy/test_new_installation.py::test_first_and_second_run` (the AWS fake answered `ParameterAlreadyExists` on the second run, exit 254); the test passed alone and in the next full run. This package changes nothing under `deploy/` (follow-up item 7). GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check (spec Section 8): (1) BP-DC-01 to BP-DC-07: every part 4 row with proof `test` has a test that names it (`tools/trace.py --check` passes); AR-Q-05 checked by review (Section 1); nothing beyond the spec (no API operation, no screen, no setting beyond `vigil.links.base`, which BP-A-09 names); AR-Q-01 to AR-Q-09 pass, the new entry points are in `ExternalCallGuardIT`, no new listener; one migration named as AR-P-05; no OpenAPI operation; the `README.md` bullet of spec 6.10; this report; no owner-run steps. (2) Section 9: 2,102, recorded as spec Section 1 says. (3) `diff -r dev/fakes/recordings/jira app/src/test/resources/recordings/jira` is empty; the tests generate the `jira` secret (email and token) at start and hold no secret, token or email address.

## 11. Follow-up items for later packages

1. P-22: `TicketingService.recordEscalation(tenant, incident, escalation id, level, team names, cause, timer)` after the escalation is committed, with no transaction open (the team display names from `vigil-identity`; `timer` only for `acknowledgement_timeout`); `TenantService.publicBaseAddress` for the links of AR-I-101 and AR-I-102; the Teams and email parts of DM-E02-R2.
2. P-23: the `recovery_verification` evidence entry's `content` MUST hold `definition_snapshot.observation_window_seconds` (integer ≥ 1), `window_started_at` and `decided_at` (ISO 8601 instants) and `last_evaluation.conditions[].detail` (strings), as spec 6.6.3 and CTX-D-069 (6) say; until then the resolve step is exercised only by tests.
3. P-25: `TicketingService.ticketLink` and `syncRecords` for AR-I-21 and AR-I-22 (the key, the URL, the state).
4. P-26: the local installation sets `VIGIL_LINKS_BASE=http://{host}:8080` (BP-R-25).
5. P-36: the test replay text "Simulated: no ticket change." stays with that package; the Ticket step has no test mode.
6. P-32: the demo installation reaches the real Jira; the `jira-demo` instance and the secret `jira` are those of P-06.
7. Owner: `deploy/test_new_installation.py::test_first_and_second_run` failed once under the load of a full gate run (`ParameterAlreadyExists` from the AWS fake on the second run) and passed alone and on the next run; a flaky test for the package that next changes `deploy/`.
