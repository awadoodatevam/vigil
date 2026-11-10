# P-19 — Report: AI gateway and AI connectors

| Field | Value |
| --- | --- |
| Package | P-19 AI gateway and AI connectors (spec `docs/packages/P-19.md`) |
| Branch | `p-19-ai-gateway-connectors` |
| Pin date (BP-R-08) | 10 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; GATE_STATE |

## 1. What was built

`vigil-ai-gateway` keeps its allowed dependencies (`shared`, `connectors :: api`, `audit :: api`; `audit` is not used). Every public type of the gateway is in `vigil.aigateway.api`, of the AI connector type in `vigil.connectors.api`; every other new class is package-private. HTTP code stays in `vigil.connectors`. No API key, request body, part text or answer text is logged or stored; the gateway logs a failed call once at `WARN` (`AI call failed: task {task}, provider {provider_key}, outcome {outcome}, reason {reason}`) and a refusal by data routing at `INFO` (`AI call refused by data routing: task {task}, provider {provider_key}`).

| Part | Requirement IDs |
| --- | --- |
| `vigil.aigateway.api` (spec 6.3, as written): `AiTask`, `DataCategory` (the six keys of DM-R-42 with the display names of `40-demo-cut`), `ProviderLocation`, `UsageOutcome`, `AnswerFinish`, `UnavailableReason`, `AiPart`, `CompletionRequest`, `EmbeddingRequest`, the sealed `CompletionResult` and `EmbeddingResult`, `AiGatewayService`, `AiGatewayImport`, `AiGatewayPart` (nested `ProviderConfigurationPart`), the event type `TaskProviderChanged` | DM-R-42, DM-R-44, AR-E-11, AR-G-10 |
| Migration `ai_gateway/V20261110090000__ai_gateway_create_ai_gateway_tables.sql` (starts with `GRANT USAGE ON SCHEMA ai_gateway TO vigil_app;`): the five tables of spec 6.2 with their columns, checks, unique constraints and the index `(tenant_id, request_group_id)`; grants by class; five rows in `table-classes.csv` | DM-E-70 to DM-E-74, AR-P-01, AR-P-02, AR-P-05, AR-P-06, DM-C-04, DM-C-06, DM-V-01 |
| `vigil.connectors.api` (spec 6.4, as written): `AiProviderConnector`, `AiPromptPart`, `AiFinish`, `AiFailure`, the sealed `AiCompletion` and `AiEmbedding` | AR-K-00e |
| `ClaudeConnector`, `OpenAiConnector`, `LocalModelConnector`, `LocalEmbeddingConnector` (package-private `@Component`s on the common base `AiConnector`): the order guard → arguments → instance kind → operation; operations a kind does not offer return `PROVIDER_ERROR` "{kind} does not offer {operation}" without a call; the address with trailing `/` removed and the path; the API key of the instance's credential reference read in `vigil-connectors` (`No credential is set.`, `Secret {name} is not available.` also for a key a header cannot carry); the request bodies of spec 6.5 with their key order; the rendering of the parts (AR-G-10); the answers read field by field (output text, finish, units), a body that is not UTF-8 JSON as `Answered(null, COMPLETE, 0, 0)`; embeddings with the three vector checks and their texts; reasons cut to 500 characters, the key hidden | PM-K-07 to PM-K-10, AR-K-07 to AR-K-10, AR-G-10, AR-G-13, AR-G-14, AR-G-20 to AR-G-24, AR-T-16, AR-T-17 |
| `ConnectorHttp.send(request, timeout)`; `ConnectorTimings` with `connect`, `monitoringTest`, `chat`, `localChat`, `embedOne`, `embedBatch` (5, 10, 90, 120, 30 and 90 s); `FailureReasons.of(…, callTimeout, …)` ("No answer within {n} s." with the call's timeout), the existing `of` calls it with `monitoringTest` | AR-TM-01 (BP-A-30), AR-G-13 |
| `AiProviders` (the registry of `AiProviderConnector` beans by kind; a non-AI kind and a second bean of a kind stop the start) | AR-K-00e |
| `AiGateway` (`AiGatewayService`): `complete` (spec 6.6) — one read-only transaction for the assignment, the configurations, the allowed kinds and the data routing rules; keys skipped without a record when their configuration is missing, inactive or of a kind not allowed; `refused_by_data_routing` records; the connector instance read, `PROVIDER_ERROR` "The connector instance is not available." for a missing or inactive one; one record per call in its own transaction after the call; a 2xx answer (also truncated, refused or without text) ends the attempts; `Unavailable(NO_PROVIDER_ALLOWED)` or `NO_PROVIDER_REACHABLE`. `embed` (spec 6.7) — the named provider only, no fallback, one record per call. Cost by AR-G-14 (`BigDecimal`, half-up to 6 places; units and cost 0 when the cost does not fit `numeric(12,6)`). The argument rules of spec 6.3. Both calls check that no transaction is open | PM-M18-01 to PM-M18-04, DM-R-45, DM-R-69, DM-E-72 (DM-E72-R2), DM-E-74, AR-G-13, AR-G-14, SL-B-08, SL-A2-05, AR-R-09 |
| `AiGatewayImporter` (`AiGatewayImport`, seed part 7, spec 6.8): the checks 1 to 5 with their texts and order, one transaction, everything `active`, assignments in file order and data routing keys sorted, `updated_by` the module `vigil-setup`; no audit event and no event | DM-E-70 to DM-E-73, DM-E71-R1, DM-R-57, PM-M18-05, AR-G-01 to AR-G-05, DM-C-07 (AR-B-11) |
| `vigil.setup.AiGatewayAdapter` (part 7): the four sections read with `SeedShapes` (the prices as YAML trees), counts 5, 3, 3 and 6 for the demo data, no handles | DM-R-56, DM-R-57, AR-B-11 |
| P-18 review fixes (spec 6.9): `Runner` closed flag set by `@PreDestroy` — a call that returns or throws while it is set leaves the run `running` and `run` throws `IllegalStateException("the application is closing")`; an unexpected `RuntimeException` of the call otherwise ends the run `failed` with its message or simple class name. `LinuxServerConnector` stopped flag (no new `SshClient`, `NotStarted("the application is closing")` from `run` and from `connectAndRun`) and `waitMillis` for every wait. `Parameters`: a non-finite `decimal` value, `min` or `max` is `not a number`. `application.properties`: `logging.level.org.apache.sshd=WARN` | DM-SM-10, AR-K-11, DM-E-12, DC-TX-03, DM-R-64, AR-S-11 |
| Recordings: `dev/fakes/recordings/claude/`, `openai/`, `local-llm/`, `local-embed/` copied byte for byte (with `README.md`) to `app/src/test/resources/recordings/`; `Recordings.provider(…)` reads the P-06 format | BP-R-06 |

Checked by review (spec part 4): DM-R-44 (`DataCategory` holds exactly the six keys of DM-R-42; no code of this package maps evidence kinds); PM-R-01 (`SchemaRuleTest`); PM-R-02, PM-R-03 (`ModuleStructureTest`, unchanged rows); PM-R-04 (all HTTP code in `vigil.connectors`, `ConnectorLibraryRuleTest`; `AiGatewayRuleTest`); PM-R-05 (every table has `tenant_id`, every statement of `AiGatewayRecords` filters by it, every request takes a `TenantId`; `TenantColumnIT`); PM-R-08 (`SeparationTest`); DM-C-04 (no `DELETE`; `PrivilegeIT`); DM-C-06 (connector instances held by id, providers and tasks by key; no foreign key); DM-V-01 (the Actor columns of `task_assignment` and `data_routing_rule`, written as the module `vigil-setup`); PM-R-06 (the importer writes no audit event).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `connectors.AiProviderTypeTest` | AR-K-00e |
| `aigateway.AiProvidersTest` | AR-K-00e |
| `connectors.ClaudeConnectorIT` | AR-G-20, AR-G-13, PM-K-07, AR-K-07 |
| `connectors.OpenAiConnectorIT` | AR-G-21, AR-G-22, PM-K-08 |
| `connectors.LocalModelConnectorIT` | AR-G-23, PM-K-09, AR-T-16 |
| `connectors.LocalEmbeddingConnectorIT` | AR-G-24, PM-K-10, AR-T-17 |
| `connectors.AiTimeoutsIT` | AR-TM-01 (two methods) |
| `connectors.AiRenderingTest` | AR-G-10 |
| `connectors.AiKindsTest` | AR-K-00e, AR-K-08, AR-K-09, AR-K-10 |
| `aigateway.AiGatewayIT` | PM-M18-04, PM-M18-03, SL-A2-05, DM-R-69, DM-R-45, PM-M18-02, PM-M18-01, DM-E-74, AR-G-10, DM-E-72 (two methods) |
| `aigateway.AiCostTest` | AR-G-14 |
| `aigateway.AiGatewayImportIT` | AR-G-01, AR-G-02, AR-G-03, AR-G-04, AR-G-05, PM-M18-05, DM-E-70, DM-E-71, DM-R-57, DM-E-73, DM-R-56 |
| `connectors.ExternalCallGuardIT` (new methods) | AR-Q-04 (`aiEntryPoints`), AR-R-09 (`aiOutsideTransaction`) |
| `aigateway.AiGatewayRuleTest` | SL-B-08 |
| `aigateway.TaskProviderChangedTest` | AR-E-11 |
| `aigateway.DataCategoryTest` | DM-R-42 |
| `runner.RunnerRestartIT` (new methods) | DM-SM-10 (`connectorThrows`, `stopDuringCall`) |
| `runner.LinuxServerConnectorTest` (new) | AR-K-11 (`afterStop`, `waitMillis`) |
| `runner.ParametersTest` (new method) | DM-E-12 |
| `diagnostics.DiagnosticStepIT` (changed and new methods) | DM-R-64 (`evaluatedAgain` with a new evaluation count, `insertConflict`, `evidenceRefused`) |
| `diagnostics.SummariesTest` (new method) | DC-TX-03 |
| `LoggingLevelTest` (new) | AR-S-11 |
| Test support (new) | `connectors.AiConnectorApp` (vigil-app, WireMock and the AI instances for the connector tests), `aigateway.AiFixture` |

Changed tests (spec 6.10): `SeedImportIT` (the `RecordingCheck` targets part 8; part 7 `SUCCEEDED` in `registeredPartsImport`; `unregisteredSectionsAreSkipped` replaces `knowledge_settings`; the "not started" case drops part 7; part 7 added to the success lists of the incidents, routing, correlation, diagnostics and workflows cases; `seedImportRecord` asserts part 7's counts 5, 3, 3, 6); `SeedImportRestartIT` (part 7 in the success list); `ConnectorKindTest`, `ConnectionTestIT`, `FailureReasonsTest` (the new `ConnectorTimings` fields); `PrivilegeIT` and `TenantColumnIT` read the five new rows of `table-classes.csv`. `ModuleStructureTest` is unchanged.

Every row of spec part 4 with proof `test` has a method whose display name starts with its ID; `tools/trace.py --check` passes.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `tools/loc.sh origin/main HEAD` prints 2,408, above the 2,000 of the size class. | Every part of spec 6 is built; the count includes the four connectors and their common base (about 760 lines), the gateway service with its argument rules and logging (about 440 lines after formatting), the importer with the five check groups (about 360 lines) and the records (about 260 lines). Nothing beyond the spec was added to reach it. |
| 2 | Package-private helpers and nested types besides the spec's classes: the base class `AiConnector` of the four connectors (with `render`, `units`, `string`, `setting`); `HttpSourceConnector.address` became package-private for reuse; `AiGateway.cost` and `AiGateway.UNAVAILABLE_INSTANCE`; `AiGatewayRecords` with its records `ProviderConfiguration` and `Usage`; `AiGatewayImporter.price` and `ALLOWED_KINDS`; private records `AiGateway.Settings` and `Call`. | Internal structure only. |
| 3 | Test support beyond the spec: `connectors.AiConnectorApp` (one app with WireMock and the AI instances, used by the four connector ITs and `AiTimeoutsIT`); `Recordings.ProviderCall`; `aigateway.AiFixture` is `public` (with public `secrets`, `connectors`, `demoPart`) because `ExternalCallGuardIT` (package `vigil.connectors`) imports the demo part 7 through it. | Test helpers only. |
| 4 | The AR-S-11 row is proved by the properties test `LoggingLevelTest` (the spec allows "`JsonLogIT` (or a properties test)"). | `JsonLogIT` runs the JAR as a separate process; a properties test proves the setting directly. |
| 5 | `RunnerRestartIT`'s new tenants have a host alias. | Restart step (1) handles active tenants only (DM-R-70), and a tenant is active once it has an alias. |

Readings of the spec where it gives no detail (no behaviour beyond the spec):

- Connectors: every trailing `/` of the address is removed; the request's own `HttpRequest.timeout` is also the call's whole-call timeout (as `HttpSourceConnector` does); a request the JDK refuses to build is "The address is not a valid http or https address."; a missing setting (`anthropic_version`, `keep_alive`, `num_ctx`) is an `IllegalStateException`, as `HttpSourceConnector.setting` does (seed import refuses such instances); `keep_alive` and `num_ctx` are sent as stored; a 2xx body that is valid UTF-8 JSON but not one object is read as an unreadable body; an OpenAI `output_text` item whose `text` is not a string is left out; a vector that is an empty array is a list of numbers.
- Gateway: a connector instance whose kind is not the configuration's kind, or whose kind has no connector bean, is "not available" like a missing or inactive one; a data category without a rule allows no provider; the `refused_by_data_routing` records are inserted in their own transaction like the others.
- Seed part 7: within an entry, both `not a price` problems come before both `must be 0 for in_house` problems (the order of the checks of item 2); in a mapping section the present keys are checked in file order (an unknown key gives `unknown key`), then the missing keys follow in the order of DM-E71-R1, the tasks, or DM-R-42; a provider key whose `provider_kind` is not one of the four is still a provider of the part, and its kind is not allowed for any task, so an assignment that lists it also gets `{key} is not allowed for {task}`; a list value `null` in `task_assignments` or `data_routing_rules` is an empty list.
- P-18 fixes: `insertConflict` arms the test `RunnerService` with a `failed` stored result for the same step execution and definition key; the `addEvidence` refusal is caused by an incident set `closed` through SQL (the guard of `addEvidence` allows `open` and `resolved`).

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 10 November 2026)

None. The package uses the JDK HTTP client, Jackson 3 (with `jackson-dataformat-yaml` in the tests) and WireMock, all already pinned.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step; the gate makes no call to a real provider).

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **2,408** (size M estimate about 1,800; above 2,000, deviation 1 of Section 3).

## 10. Gate command result

GATE_RESULT

Done-check (spec Section 8): (1) BP-DC-01 to BP-DC-07: every part 4 row with proof `test` has a test that names it, the rows checked by review are listed in Section 1; nothing beyond the spec (no API operation, no screen, no setting); AR-Q-01 to AR-Q-09 pass, the new external entry points are in `ExternalCallGuardIT`; one migration named as AR-P-05; no OpenAPI operation; no `README.md` or `CLAUDE.md` command change; this report. (2) Each copied recordings folder is byte-identical to its folder under `dev/fakes/recordings/` (`diff -r` per folder, no output). (3) `ApplicationModules.verify()` passes with the gateway's dependencies unchanged (`ModuleStructureTest`). (4) Section 9.

## 11. Follow-up items for later packages

1. P-20 (`vigil-investigation`): build `CompletionRequest` with the parts of DM-R-43 and their categories mapped from `vigil-incidents`' `DataCategory` to the gateway's (DM-R-44); reject an `Answered` whose `finish` is `TRUNCATED` or `REFUSED` or whose `outputText` is `null` (AR-G-11, AR-G-13); show `Unavailable(NO_PROVIDER_ALLOWED)` and `NO_PROVIDER_REACHABLE` as DM-E-32's first two reasons.
2. P-20 (`vigil-knowledge`): the seed import's batched embedding call (AR-B-09) goes through `AiGatewayService.embed` (90 s for more than one text, BP-A-30) and creates one usage record; `EmbeddingResult.Unavailable` carries the outcome (`provider_error`, `refused_by_data_routing`, `unreachable`); the key check of part 8 against part 7 (`embedding_provider_key`, DM-R-60) is P-20's.
3. P-39: the change operations of task assignments and data routing rules (with their validation texts, `configuration_changed` audit events and the publication of `TaskProviderChanged` when the SL-T-3 provider changes), the reads and the usage views; the tables keep the Actor columns for it.
4. P-26: the dev fakes of the AI providers answer at the paths `/v1/messages`, `/v1/responses`, `/v1/embeddings`, `/api/chat`, `/api/embed` with the bodies the connectors read (the recordings under `dev/fakes/recordings/` show them).
