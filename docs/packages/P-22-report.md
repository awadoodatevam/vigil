# P-22 — Report: Notifications, Teams and email connectors, Escalation step

| Field | Value |
| --- | --- |
| Package | P-22 Notifications, Teams and email connectors, Escalation step (spec `docs/packages/P-22.md`) |
| Branch | `p-22-notifications-escalation` |
| Pin date (BP-R-08) | 11 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command result is in Section 10. No owner-run steps. |

## 1. What was built

The `@ApplicationModule` declarations of `vigil-notifications` and `vigil-routing` are unchanged. Every new public type is in an `api` package; every other new class is package-private and in the spec's complete list (spec 6.4), except nested records inside those classes. Only `vigil.connectors` uses the HTTP client and mail. Every statement filters by `tenant_id`; nothing is deleted. No module gets an event listener (AR-Q-05, checked by review: no `@ApplicationModuleListener` or `@TransactionalEventListener` under `vigil.notifications` or `vigil.routing`). No `vigil-api` operation, screen or setting is added.

| Part | Requirement IDs |
| --- | --- |
| `vigil.connectors.api` (spec 6.2): `MessageLine` (compact constructor as `TicketLine`, `of`), `Delivery` (`Sent`, `Failed` with the reason cut to 500), `NotificationChannelConnector` | AR-K-00d |
| `ConnectorTimings` gains `teams` and `smtp` (15 s each) after `jira`, set in `ConnectorConfiguration` | AR-TM-01 |
| `TeamsSender` (spec 6.2): one bean, a fair `ReentrantLock` held from the wait to the answer, each post at least 300 ms after the previous post's answer or failure, the wait by `Thread.sleep` on the caller's thread; a package-private constructor with a monotonic time source and a sleeper | AR-K-19, AR-R-32 |
| `TeamsConnector` (spec 6.2): guard, `null` checks, kind; the webhook from the address's credential reference of purpose `webhook_url` (`No credential is set.`, `Secret {name} is not available.`, `Secret {name} is not a valid webhook address.`); the Adaptive Card payload of AR-K-18 with key order as written and the link line as Markdown; `POST` with `Content-Type: application/json`, timeout 15 s, through the sender; 2xx `Sent`, other `HTTP {status}`, no answer `FailureReasons` with only the scheme, host and port shown and the address, path and query hidden | PM-K-05, AR-K-05, AR-K-17, AR-K-18 |
| `EmailConnector` (spec 6.2): the `smtp://{host}:{port}` address, `from_address`, `starttls`, the instance's `smtp` credential `{username, password}`; a Jakarta Mail `Session` per send with the properties of spec 6.2 item 3; `From` with the product name, `To`, `Subject` line 1 (UTF-8), `Date` from the `Clock`, the body the lines joined and ended by CRLF, `text/plain; charset=UTF-8`, then `Content-Transfer-Encoding: 8bit`; `Transport.send` with the credentials; the outcomes of item 4 in their order | PM-K-06, AR-K-06, AR-K-20, AR-T-14 |
| `app/pom.xml`: `spring-boot-starter-mail` (version from the Spring Boot parent), test `com.icegreen:greenmail` 2.1.15 | AR-T-14, BP-R-08 |
| Migrations (spec 6.3): `notifications/V20261112100000__notifications_create_notifications_tables.sql` (`delivery_address` `configuration`, `notification_record` `immutable`, the checks and indexes of the table; the `sent` unique index of DM-R-65) and `routing/V20261112100100__routing_create_step_escalation.sql` (`step_escalation` `immutable`, unique `(tenant_id, step_execution_id, round)`); three rows in `table-classes.csv` | DM-E-77, DM-E-78, AR-Q-06, AR-Q-08 |
| `vigil.notifications.api` (spec 6.4, as written): `Channel`, `RecipientKind`, `DeliveryStatus` (`key`, `fromKey`), `DeliveryAddressView`, `NotificationRecordView`, `ApprovalRecipient`, `SendOutcome`, `NotificationsService`, `NotificationsPart` with `AddressPart`, `NotificationsImport` | PM-M20-01 |
| `NotificationRecords`: the two tables (addresses by subject kind, key, channel, id; a subject's active addresses `teams` before `email`; records by `at`, id; the DM-R-65 check by origin, recipient and channel) | DM-E-77, DM-E-78, DM-R-65 |
| `MessageTexts` (spec 6.7): the prefix, `INC-` number, the cause and duration (own copies), outbound times, the lines of DC-OUT-05, DC-OUT-06 (with the line `Evidence:` and one line per entry) and DC-OUT-07, the record content (lines joined by `\n`, cut to 4,000), the audit summary and details | DC-OUT-00, DC-OUT-05, DC-OUT-06, DC-OUT-07, DM-E02-R2, PM-M20-03, SL-DR-03 |
| `Deliveries` (spec 6.5, 6.6, 6.13): per recipient and active address one message; the DM-R-65 check, the link base, the first active instance of the channel's kind; the send with no transaction open; then one transaction with the record, the `notification_sent` event and, when sent to an `open` or `resolved` incident, `recordNotifiedTeam` for the team (or the user's `via_team_key`); one `WARN` line per `failed` record; the summary read in its own transaction before any send | PM-M20-02, PM-M20-04, PM-M20-05, DM-E37-R1, DM-E08-R3, AR-I-101, AR-I-102 |
| `NotificationStep` (spec 6.8): parameters, the recipient teams (owner, informed alphabetical, named as written, no repeats), DC-OUT-05 with origin (`vigil-engine`, `StepExecution`, step execution id), `Completed(sent or failed)` by all the origin's records with the first record as result reference; `cancel` does nothing | PM-M20-01, PM-S-09, DM-S-09 |
| `Notifications` (spec 6.9): `deliveryAddresses`, `records`, `notifyEscalation` (origin `vigil-incidents`, `IncidentEscalation`), `notifyApprovalRequest` (origin `vigil-actions`, `ApprovalRequest`; users with `via_team_key`) | PM-M20-01, DC-OUT-06, DC-OUT-07, DM-E77-R1 |
| `NotificationsImporter` (spec 6.11): the checks in their order, the refusal of the part as a whole, `delivery addresses already exist`, every address stored `active` | DM-E-77, DM-R-57 |
| `EscalationStep` and `StepEscalations` (spec 6.10): the parameters; no policy or no level: `next`; the target `min(L + 1, last)`; recording a round (the escalation and its row in one transaction); sending a round (messages, then the ticket comment with the team names, a `FAILED` answer logged); a recorded round first; `escalate_now`; `wait_for_acknowledgement` with its state `{round, wait_started_at, last_level_at_start}`; continuing from a round | PM-M10-05, PM-S-08, PM-D-08, DM-S-08, DM-D-09, DM-R-64, SL-A2-03 |
| `vigil-setup` (spec 6.11): `NotificationsAdapter` (part 15, section `delivery_addresses`, counts `{"delivery_addresses": n}`) and `NotificationsTeamCheck` (part 15 against part 2) | DM-R-56, DM-R-57, DM-R-60, AR-K-21 |
| Recordings (spec 6.12): `dev/fakes/recordings/teams/` and `email/` copied byte for byte with their `README.md` to `app/src/test/resources/recordings/` | BP-R-06 |
| `README.md` "Module code": the P-22 bullet of spec 6.12. `CLAUDE.md` unchanged. | BP-DC-06 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `connectors/TeamsSenderTest` (2 methods) | AR-K-19, AR-R-32 |
| `connectors/TeamsConnectorIT` (5 methods) | AR-K-18, AR-K-05, AR-K-17, PM-K-05, AR-K-00d |
| `connectors/EmailConnectorIT` (4 methods) | AR-K-20, AR-K-06, AR-T-14, PM-K-06 |
| `notifications/MessageTextsTest` (6 methods) | DC-OUT-05, DC-OUT-06, DC-OUT-07, DC-OUT-00, DM-E02-R2, PM-M20-03 |
| `notifications/NotificationStepIT` (14 methods) | SL-A1-05, PM-M20-01, PM-M20-02, PM-M20-05, PM-S-09, DM-S-09, DM-E-78, DM-E08-R3, AR-I-101, SL-DR-03, PM-M20-04 (2 methods), DM-R-65, AR-K-17 |
| `routing/EscalationStepIT` (8 methods) | SL-A2-03, PM-M10-05, PM-S-08, DM-S-08 (2 methods), DM-D-09, PM-D-08, DM-R-64 |
| `notifications/NotifyApprovalRequestIT` (2 methods) | AR-I-102, DM-E77-R1 |
| `setup/NotificationsImportIT` (5 methods) | DM-E-77, AR-K-21, DM-R-56, DM-R-57, DM-R-60 |
| `notifications/NotificationsIncidentsIT` | DM-E37-R1 |
| `connectors/ExternalCallGuardIT` (one method added) | AR-Q-04 |
| `TableClasses` rows (existing privilege and tenant tests) | AR-Q-06, AR-Q-08 |

Test support: `notifications/NotificationsFixture` (spec 6.12) and the helper `notifications/NotificationsApp` (WireMock for the Teams webhooks and Jira, GreenMail with a generated user, vigil-app with the generated secrets). Existing tests changed only as spec 6.12 lists: `EngineAbsentHandlersIT` uses `approval` and expects `no step handler for approval`; `SeedImportIT` counts part 15 as imported and its not-started list is 13, 14 and 17 (display name changed accordingly); `AiTimeoutsIT`, `ConnectionTestIT` and `FailureReasonsTest` pass the two new `ConnectorTimings` components; `KnowledgeFixture.create`, `InvestigationFixture.create` and `TicketingFixture.create` gain the overload with a `UnaryOperator<TestTenants.Builder>`; `TicketingFixture` and the members the spec names are `public`; `InvestigationFixture` gains an `openIncident` overload that calls a `Consumer<UUID>` while the incident is `forming` (the spec's "overload of `InvestigationFixture`'s helpers"). `TestHandlers` and `TableClasses` are unchanged.

## 3. Deviations from the spec

None in behaviour. Readings of points the spec leaves open (no document text changes):

1. `EmailConnector` recognises the connection timeout by the class name `MailConnectException` in the cause chain: Angus Mail is a runtime dependency of the mail starter, so the class is not on the compile class path.
2. `EmailConnector` parses the delivery address strictly (`InternetAddress.parse(address, true)`); an address Jakarta Mail cannot parse gives `The SMTP server refused the message: {message}` (the last case of spec 6.2 item 4). Part 15 refuses such addresses before (CTX-D-071 (11)).
3. `EmailConnector` does not check the purpose of the instance's credential reference, as `JiraConnector` does not; a missing `from_address` or `starttls` setting is an `IllegalStateException`, as a missing Jira setting is.
4. `TeamsConnector` also hides the decoded path and query of the webhook, besides the raw ones; the readable channel name (`address`) MUST NOT be `null` but is not used for the post.
5. `TeamsSender`: an interrupted wait sets the interrupt flag again and throws `IllegalStateException("Teams post interrupted")` (a failed call).
6. Both channel connectors refuse an empty line list with `IllegalArgumentException("lines: at least one")` (spec: "`lines` at least one").
7. `NotificationsImporter`: the email address form is checked only when the length check passed, and the duplicate check only for an entry whose kind, key and channel are valid, so one fault gives one problem.
8. `NotificationStep`: a `named_teams` entry must be a string in the key form; a named team without addresses (or unknown) makes no record.
9. `notifyEscalation` sends to each team key once (repeats in `teamKeys` are dropped); `level` is passed through unchecked. `notifyApprovalRequest` treats a recipient with a `null` username or team as a missing argument.
10. `EscalationStep` takes the first level whose number is at or above the target, so a policy with a gap in its numbers still escalates; the demo policy has levels 1 and 2. A call with step state `null` (a `start`, or an `evaluate` without state) builds the first state.
11. `NotificationRecords.lock` serialises the import of one tenant's addresses with an advisory lock, as `RoutingRecords.lock` does.
12. The migrations start with a one-line comment, then `GRANT USAGE …` (notifications), as every earlier migration does; the routing migration does not grant the schema again.

## 4. Document feedback (CTX-Q-8)

1. `docs/packages/P-22.md` Section 7, `EmailConnectorIT`: "the body the lines joined by CRLF and ended by CRLF" cannot be read back from GreenMail as written: the body's last CRLF is also the first CRLF of SMTP's end-of-data marker (`CRLF.CRLF`), and GreenMail stores the message without it. The test checks the body the connector builds (`EmailConnector.body`, ended by CRLF) and the received body plus that CRLF. No effect on the build.
2. `greenmail` 2.1.15 (the plain artifact, as spec 6.12 asks) brings `junit:junit` 4.13.2 and `org.eclipse.angus:jakarta.mail` 2.0.5 onto the test class path. No JUnit 5 artifact enters; the tests run on Spring Boot's JUnit as before.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 11 November 2026)

| Dependency | Version | Note |
| --- | --- | --- |
| `org.springframework.boot:spring-boot-starter-mail` | 4.1.1 (from the Spring Boot parent) | Brings `spring-boot-mail` 4.1.1 and `org.eclipse.angus:angus-mail` 2.0.5 (Jakarta Mail API 2.1.5), versions managed by Spring Boot |
| `com.icegreen:greenmail` (test) | 2.1.15 | The latest release on Maven Central on the pin date (published 25 Oct 2026) |

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None: the spec lists no owner-run steps. The connectors are tested on the P-07 recordings, WireMock and GreenMail; the real Teams channels and mailboxes are reached first by P-32.

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **2,519** (size L; estimate 2,500 to 3,300; at most 3,500 by spec Section 8).

## 10. Gate command result

GATE_RESULT

Done-check (spec Section 8): (1) BP-DC-01 to BP-DC-07: every part 4 row with proof `test` has a test that names it (`tools/trace.py --check` passes); AR-Q-05 checked by review (Section 1); nothing beyond the spec (no API operation, screen or setting); AR-Q-01 to AR-Q-09 pass, the new entry points are in `ExternalCallGuardIT`; two migrations named as AR-P-05; the `README.md` bullet; this report; no owner-run steps. (2) Section 9: 2,519. (3) `diff -r` of `dev/fakes/recordings/teams` and `email` against `app/src/test/resources/recordings/teams` and `email` is empty; the tests generate the webhook addresses (with a random `sig`), the SMTP user and password and the Jira credential at start and hold no secret.

## 11. Follow-up items for later packages

1. P-24: the Approval step calls `NotificationsService.notifyApprovalRequest(tenant, incident, approval request id, recipients as username and team, action name, target name, expires_at)` with no transaction open; `EngineAbsentHandlersIT` then needs a step type that still has no handler.
2. P-25: `NotificationsService.records` and `IncidentService.notifiedTeams` for the delivery status and notified teams of DC-S03-13 (DC-L-09 stays Later).
3. P-26: the dev fakes `teams-fake` and Mailpit; the local installation's `teams-team-*`, `teams-user-*` and `smtp` secrets and `endpoint.smtp` / `smtp_starttls` values.
4. P-32: the demo values `endpoint.smtp` and `smtp_starttls: true`; the first deliveries to the real channels and mailboxes check the `8bit` body against SES and Exchange Online (spec Section 11).
5. P-36: the test replay texts "Simulated: would notify …" and "Simulated escalation …".
