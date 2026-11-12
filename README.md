# Vigil

Vigil is a NOC automation product (working name; the audience sees "Techfinity"). This repository holds the Vigil product (`app/`, `web/`), the demo-only lab and control panel (`demo/`), test tooling (`dev/`, `spikes/`, `tests/`), deployment (`deploy/`) and the project documents (`docs/`). The documents are the specification; `CLAUDE.md` tells Claude Code how to build from them, one package at a time (`docs/60-build-plan.md` Section 6).

## The gate command

```bash
tools/check-all.sh
```

It runs every check a package MUST pass, in this order, and stops at the first failure: in a cloud session, step (0) starts the Docker daemon when it is not running (`tools/start-docker.sh`, with the registry mirror `mirror.gcr.io`; Docker Hub stays the fallback); the cloud setup version check (only in a cloud session); the build of the images that tests use (`deploy/images.sh --test`); `./mvnw -B verify` (Spotless, Java tests, and the web application's `npm ci`, lint, Prettier, type check, Vitest and build), `ruff` and `pytest` (the Python tests of `tools/`, `demo/`, `dev/`, `deploy/`, `spikes/` and `tests/acceptance/`; the tests marked `lab`, which start the lab with its monitoring and run one storm, only when the branch changes the lab: a path under `demo/`, `dev/recorder/`, `deploy/local/`, `spikes/storm-timing/` or `docs/31-demo-data/`, or one of `deploy/local.sh`, `deploy/images.sh`, `deploy/values/local.yaml`, `tools/check-all.sh`, `tools/gate_scope.py`, `tools/start-docker.sh`, `tools/requirements-dev.txt`, `pytest.ini` and `.github/workflows/ci.yml`, compared with `origin/main` and counting untracked files (`tools/gate_scope.py lab`)), `shellcheck`, the demo data checks, Docker Compose validation, and `tools/trace.py --check`.

The command has two parts, which together run every step: `tools/check-all.sh --part java` runs steps (0), (1), the image build with `deploy/images.sh --test --java` and `./mvnw -B verify`; `tools/check-all.sh --part rest` runs steps (0), (1), the image build with `deploy/images.sh --test` and the steps from `ruff` to `tools/trace.py --check`. A cloud session runs the command without arguments. On every pull request to `main`, `.github/workflows/ci.yml` first decides with `tools/gate_scope.py ci`: when the pull request changes only data (`docs/` except `docs/31-demo-data/`, `spikes/results/`, `tests/acceptance/results/`, `dev/fakes/recordings/`; never a `.py`, `.sh`, `Dockerfile` or `compose.yaml` file), or only data since the last commit of the pull request whose `gate` passed, it runs the light gate (the demo data checks and `tools/trace.py --check`); otherwise, and for a manual run, the full gate as the parallel jobs `java` and `rest`. The job `secrets` (`gitleaks`) runs in both, and the summary job `gate` passes only when the chosen jobs passed: it is the check to wait for before merging.

Other commands: `./mvnw -B verify` (Java and web build and tests only), `tools/loc.sh origin/main HEAD` (lines of production code added and changed), `tools/trace.py` (requirement IDs without tests).

## Cloud sessions

Claude Code builds in a cloud environment named `vigil`. Its setup script is the content of `tools/cloud-setup.sh`: it installs Eclipse Temurin 25, the Docker Compose plugin, `shellcheck`, the Python tools of `tools/requirements-dev.txt` in `/opt/vigil-tools`, and `gitleaks`, and finishes within 5 minutes. The owner creates the environment once and pastes the script into its setup script field (task BP-OT-02 in `docs/60-build-plan.md`), together with the environment variables `BASH_DEFAULT_TIMEOUT_MS=600000`, `BASH_MAX_TIMEOUT_MS=1800000` and `JAVA_HOME=/opt/temurin-25`. Whenever a package changes `tools/cloud-setup.sh`, the number in its line 2 goes up and the owner updates the environment; `tools/check-all.sh` stops with "The cloud environment vigil is out of date" until then.

## Database

`vigil-app` runs against PostgreSQL 17 with pgvector. At its first start the database runs `deploy/db/init/10-vigil-roles.sh`, which creates the owner role `vigil_owner`, the runtime role `vigil_app` and the extension `vector`. The same image runs as two units: `vigil-migrate` (Spring profile `migrate`, role `vigil_owner`) runs the Flyway migrations and stops with exit code 0; then `vigil-app` (no profile, role `vigil_app`) starts. Each reads only its own database password from its secret folder (`VIGIL_SECRETS_DIR`, default `/run/secrets/vigil`): `vigil-db-owner` for `vigil-migrate`, `vigil-db-app` for `vigil-app`. `VIGIL_DB_HOST`, `VIGIL_DB_PORT` and `VIGIL_DB_NAME` locate the database. The Java tests start the database in Testcontainers (`app/src/test/java/vigil/testsupport/`), so `./mvnw -B verify` needs Docker.

## Migrations

Migrations are Flyway SQL files in `app/src/main/resources/db/migration/{schema}/`, one folder per schema, named `V{yyyyMMddHHmmss}__{schema}_{description}.sql` (UTC date and time when the file is written; `{description}` in `a-z 0-9 _`). A merged migration is never edited; a change is a new migration. Rules for every migration:

- A module's first migration grants `USAGE` on its schema to `vigil_app`.
- Every `CREATE TABLE` is followed in the same file by the grant its mutability class gets: `SELECT, INSERT, UPDATE` for stateful, configuration and technical tables; `SELECT, INSERT` for immutable and revisioned tables; never `DELETE` or `TRUNCATE`.
- A migration names only its own schema.
- Every tenant-scoped table has `tenant_id uuid NOT NULL`, and every secondary index on it starts with `tenant_id`.
- The table's row is added to the class table `app/src/test/resources/db/table-classes.csv` (`table,class,tenant_scoped`), which the privilege test and the tenant test read.

## Module code

- A request is a method of a service interface in the callee's `api` package.
- Writing operations are `@Transactional(propagation = REQUIRED)`.
- An operation that makes an external call says "external call: not inside a transaction" in its Javadoc and calls `TransactionGuard.assertNoTransaction()` first.
- An event is a Java record in the publisher's `api` package carrying the tenant identifier (except AR-E-14) and the payload of `docs/50-architecture.md` Section 8.3, nothing else, published with `ApplicationEventPublisher` inside the transaction of the change.
- Listeners are `@ApplicationModuleListener` methods and are tested with `DoubleDelivery`.
- Every `*IT` that starts `vigil-app` uses `VigilTestApp` on a fresh database (`VigilDatabase.fresh()`), except a test that must run the JAR as a process.
- Time comes from the `Clock` bean through `Timestamps.now(clock)`; tests replace it with a `@Primary` test clock passed to `VigilTestApp`.
- Every module's scheduled work for tenants is matched to `TenantService.activeTenants()` at restart step (6) and on `TenantAliasChanged` (AR-R-23).
- Secrets are read only through `vigil.connectors.api.Secrets` (callers as `docs/50-architecture.md` AR-S-04, checked by `SecretCallerRuleTest`); monitoring payloads are parsed and sources tested only through `ConnectorService`; every entry point that makes an external call calls `TransactionGuard.assertNoTransaction()` first and is listed in `ExternalCallGuardIT`.
- Every `vigil-api` handler method carries `@Public`, `@LoggedIn`, `@RequiresPermission`, `@InstallationToken` or `@WebhookToken`; errors are `VigilError`s, mapped by `ApiErrors` (AR-I-03); timestamps are written with `ApiTimes`; live notices are published with `LiveHub.publish` after the commit; every operation is in `openapi/vigil-api.yaml` and in `ContractIT`.
- A seed part joins the import by a `SeedPartImporter` bean in `vigil.setup` (its sections and name are already in `SeedParts`; its refusal message is `Part {name} refused.`); a cross-module key check by a `SeedKeyCheck` bean; each package that adds one extends `SeedImportIT` (AR-Q-10).
- P-21 (built): `vigil-ticketing` (ticket link, sync record, the Ticket step, the escalation comment for P-22) and the `jira_cloud` connector; `vigil.links.base` read by `vigil-tenancy` (`TenantService.publicBaseAddress`).
- P-22 (built): `vigil-notifications` (delivery addresses of part 15, notification records, the Notification step, the escalation and approval request messages), the `ms_teams` and `email` connectors with the Teams sender, and `vigil-routing`'s Escalation step
- P-23 (built): `vigil-recovery` (recovery check definitions of part 13, the Recovery check step, recovery verifications with their evidence and the resolve request) and `vigil-reporting` (baseline values of part 17, the four dashboard metrics)
- P-24 (built): `vigil-actions` (registered actions and policies of part 14, proposals, the Approval and Action steps, decisions, executions and reconciliation, the reads of `ActionRuns`)

Interfaces of the platform modules for later packages (P-08):

- P-09: `TenantService.resolveHost`, `settings`, `logo`; `IdentityService.login`, `authenticate`, `logout`, `hasPermission`, `user`.
- P-11: `TenantService.createTenant`, `markReady`, `markFailed`, `setAlias`; `TenantImport` and `IdentityImport` (their problems lists become the part's `error`).
- P-13 to P-24: `AuditService.append` inside their transactions; `TenantService.activeTenants` and their own `TenantAliasChanged` listeners; `IdentityService.team`, `isMember`, `teamMembers`.
- P-39: `AuditService.query`.

Interfaces of `vigil-connectors` for later packages (P-10):

- P-11: `ConnectorImport` (its problems list becomes the part's `error`; its handle map goes to the later parts), `Secrets.readSeedPassword`, `Secrets.verifyInstallationToken`.
- P-13: `ConnectorService.parse`, `testConnection`, `instance` (to check that a source's connector instance has the source's kind), `credentialReference` (to check that a webhook token reference has purpose `webhook_token`), `Secrets.verify`.
- P-18 (built): `Secrets.read`, `ConnectorService.instance`, `credentialReference`; the target type interface and its registration (AR-K-00b).
- P-19, P-21, P-22: their type interfaces, kinds' implementations and registries, following `MonitoringConnectors`, and their entry points added to `ExternalCallGuardIT`.
- P-38: the address change of an instance.
- Test support: `TestSecrets` (a secret folder with generated values, changed with `replace` and `remove`) and `VigilTestApp.start(database, secretsFolder, extraArgs, configurations)`.

Interfaces of `vigil-api` for later packages (P-09):

- P-11 (built): the installation operations AR-I-95 to AR-I-98 with `@InstallationToken`.
- P-13 (built): the webhook operation AR-I-90 with `@WebhookToken`; `vigil-intake` checks the token.
- P-25 and later: `@RequiresPermission`, `TenantId` and `ApiCaller` handler parameters, `ApiTimes`, `VigilError` for refusals, `LiveHub.publish` for the topics of AR-I-04, the OpenAPI file and `ContractIT` scenarios for each new operation.
- P-25 (built): the live update producers of the topics `incidents`, `incident`, `run`, `alerts`, `sources`, `approval` and `tenant`; AR-I-20 to AR-I-24 and AR-I-29 to AR-I-31.
- P-43 (built): AR-I-25, AR-I-27, AR-I-28, AR-I-32 to AR-I-35 and AR-I-80.
- P-28: the operations AR-I-10 to AR-I-16, the cookies `vigil_session` and `vigil_csrf`, the header `X-Vigil-CSRF` and the default messages of AR-I-03.
- Test support: `VigilTestApp.start(database, extraArgs, configurations)`, `ApiClient` (requests with their own `Host` header, and streams read line by line) and `TestTenants` (`ready` tenants with users, teams and a host alias); `ApiFixture` (the application of `EngineWakeIT` with users, the storm and the workspace helpers); `ApprovalApiFixture` (the application of `ActionsEngineIT` with a `MutableClock` and the Approval helpers).

Interfaces of `vigil-setup` for later packages (P-11):

- P-12 to P-24: a `SeedPartImporter` for their part (with `afterCommit` where AR-B-11 needs it, P-13), their `SeedKeyCheck`s, and their part's checks in `SeedImportIT`.
- P-26: AR-Q-10 with all 17 parts (BP-R-29 ends).
- P-26, P-31: AR-I-95 to AR-I-98 with `Authorization: Bearer {installation-token}`; `bundle.yaml` written as `SeedBundles.demoEntries` writes it (quoted values); poll AR-I-97 until `state` is not `running`; a 422 refusal's `message` and a failed part's `error` are the texts to show (DC-S12-05).
- Test support: `SeedBundles` (the demo bundle built from `docs/31-demo-data/` with test placeholder values, `zip` of any entries, `form` for a multipart body) and `LogLines` (the application's ECS log lines in captured output).
- P-12 (built): part 5 (`vigil-servicemap`) is registered (`ServiceMapAdapter`), with its key check of the owner team keys against part 2 (`ServiceMapTeamCheck`, DM-R-60).
- P-13 (built): part 6 (`vigil-intake`) is registered (`IntakeAdapter`), with the first connection tests of its sources in `afterCommit` (AR-B-11).
- P-14 (built): part 9 (`vigil-incidents`) is registered (`IncidentsAdapter`).
- P-15 (built): part 11 (`vigil-routing`) is registered (`RoutingAdapter`), with its key checks against part 2 (`RoutingTeamCheck`) and part 5 (`RoutingKeyCheck`, DM-R-60).
- P-16 (built): part 10 (`vigil-correlation`) is registered (`CorrelationAdapter`).
- P-41 (built): part 16 (`vigil-workflows`) is registered (`WorkflowsAdapter`), with its key checks against part 2 (`WorkflowTeamCheck`) and part 5 (`WorkflowKeyCheck`); its check against part 12 (diagnostic keys) is P-18's.
- P-18 (built): parts 4 (`vigil-runner`, `RunnerAdapter`) and 12 (`vigil-diagnostics`, `DiagnosticsAdapter`) are registered, with the key checks of part 5 against part 4 (`ServiceMapTargetCheck`), part 12 against part 4 (`DiagnosticOperationCheck`) and part 16 against part 12 (`WorkflowDiagnosticCheck`).
- P-20 (built): part 8 (`vigil-knowledge`, `KnowledgeAdapter`, reading `08-runbooks.md` and `09-past-incidents.csv`) is registered, with the key checks of part 8 against parts 6 (`KnowledgeAlertTypeCheck`), 5 (`KnowledgeServiceMapCheck`) and 7 (`KnowledgeProviderCheck`); its embedding call reaches `TestEmbedding` in tests.

Interfaces of `vigil-intake` for later packages (P-13):

- P-16 (built): `IntakeService.alertTypes` (the alert type names in the incident alert copy, BP-A-27).
- P-21, P-22: `IntakeService.alertTypes` (display names).
- P-16 (built): `IntakeService.alertsAfter`, `alert`; `AlertReceived` (wakes the worker).
- P-17 (built): `AlertStateChanged`, `SourceHealthChanged` (wakes).
- P-23: `IntakeService.alert`, `sources`, `healthyThroughout`.
- P-25 (built): the topics `alerts` (AR-E-01, AR-E-02) and `sources` (AR-E-03); `IntakeService.alertsReceivedSince`, `latestAlerts`.
- P-38: `IntakeService.sources`, `testSource`; `SourceHealthChanged`.
- Test support: the webhook secrets `webhook-zabbix` and `webhook-alertmanager` in a `TestSecrets` folder.

Interfaces of `vigil-servicemap` for later packages (P-12):

- P-15 (built): `ServiceMapService.component`, `service` (owners, criticality).
- P-16 (built): `ServiceMapService.match`, `relation`, `dependents`, `affectedServices`, `component`, `service`, `currentGeneration`.
- P-18 (built): `ServiceMapService.target`; the key check of part 5 against part 4 (target keys in the service map against runner grants, DM-R-60).
- P-24 (built): `ServiceMapService.target`.
- P-38: `ServiceMapService.currentGeneration`, `services`, `components`, `importFile`; the reaction of `vigil-setup` to `ServiceMapImported`.

Interfaces of `vigil-incidents` for later packages (P-14):

- P-15 (built): `IncidentService.setAssignment`, `incident`, `members`.
- P-22: `IncidentService.recordEscalation` (the Escalation step).
- P-16 (built): `IncidentService.create`, `addAlert`, `merge`, `setHypothesisAndImpact`, `open`, `reopen`, `incidents`, `members`, `settings` (the alert copy carries `alertKey`, `alertTypeName`, `firstIdentityPair`, BP-A-27).
- P-17 (built): `IncidentAcknowledged`, `IncidentStateChanged`; `IncidentService.incident`.
- P-41 (built): `IncidentCreated`, `IncidentStateChanged` (the start of live runs).
- P-18 (built): `IncidentService.addEvidence` (`diagnostic_result`).
- P-20 (built): `IncidentService.addEvidence` (`ai_investigation`, `similarity_match`), `setAiStatus`, `recordAiRequestGroup`, `evidence`.
- P-23, P-24: `IncidentService.addEvidence` with their kinds.
- P-21, P-22: `IncidentService.incident`, `evidenceSummary`, `recordNotifiedTeam`.
- P-23: `IncidentService.resolve`, `members`, and for DM-R-53 `acknowledgements`, `transitions`, `incidents`.
- P-25 (built): `IncidentChanged`; `IncidentService.incidentPage`, `formingSummary` and the reads. P-43 (built): `IncidentService.acknowledge` (refusal keys `not_open`, `already_acknowledged`, `not_notified`) and `acknowledgeRefusal`.
- Test support: `IncidentFixture`.

Interfaces of `vigil-routing` for later packages (P-15):

- P-16 (built): `RoutingService.evaluate` with `CORRELATION` before `open` (DM-R-15).
- P-41 (built): `evaluate` with `WORKFLOWS` before a live run (PM-I-16).
- P-22: `escalationPolicy` (the Escalation step).
- P-25 (built), P-29: `evaluation`, `evaluations` (the rule of an assignment).
- P-36: `evaluate` with `TEST_REPLAY`, `ruleSetRevision` (DM-R-28).
- P-38: `ruleSet`, `saveRuleSet` (`FORBIDDEN`, `CONFLICT`, `INVALID` with `problems` and `places`; `IllegalArgumentException` → 400 `bad_request`), `escalationPolicy`, `RoutingField` (`displayName`, `allowedValues`, `operators`).
- Test support: `RoutingFixture`.

Interfaces of `vigil-correlation` for later packages (P-16):

- P-23: `CorrelationService.alertsOfComponent` (DM-R-35).
- P-25 (built), P-29: `CorrelationService.decisions`, `hypothesisComputations`, `latestHypothesisComputation`, `settings` (DC-TX-02, DC-S03-03, DC-S03-05).
- P-26, P-27: the health indicator `correlation` in `deploy/status.sh` (they set `management.endpoint.health.show-details` so its details show).
- Test support: `CorrelationFixture` (with public posting and waiting helpers), `IntakeFixture` (public); `CorrelationFixture.create` with users.

Interfaces of `vigil-engine` for later packages (P-17):

- P-41 (built): `EngineService.startLiveRun`, `hasLiveRun`.
- P-18 (built): `DiagnosticsStepHandler` (`vigil-diagnostics`).
- P-20 (built): `InvestigationStepHandler` (`vigil-investigation`).
- P-21: `TicketingStepHandler`.
- P-22: `NotificationsStepHandler`, `RoutingStepHandler`.
- P-23: `RecoveryStepHandler` (the recovery check key comes in `StepCall`).
- P-24 (built): `ActionsStepHandler`, `ActionRuns` (an absent bean means no proposal), `ApprovalDecided` (published in the decision's transaction).
- P-25 (built), P-29: `RunStarted`, `StepChanged`, `RunFinished`; `EngineService.run`, `runs`, `stepExecutions`, `stages`, `pendingTimers`.
- P-36: test-mode runs.
- Test support: `EngineFixture`, `TestHandlers` (its adapter beans are `@Primary`), `TestContents`; `EngineFixture.create` with users.

Interfaces of `vigil-workflows` for later packages (P-41):

- P-18 (built): the key check of part 16 against part 12 (diagnostic keys).
- P-29: `WorkflowService.version`.
- P-35: `WorkflowService.workflow`, `version`, `activeVersion`.
- P-37: `WorkflowService.stepTypes`.
- Test support: `WorkflowFixture`.

Interfaces of `vigil-runner` for later packages (P-18):

- P-24 (built): `RunnerService.run` with `PermissionKind.ACTION` (and `DIAGNOSTIC` for state checks) and the request IDs of DM-E14-R1 (an action that timed out or lost its connection after its command was sent ends `unknown`); `operation`, `operationRun`; output values as an ordered array of `{name, value}` (state checks, DM-V-08); the key checks of part 14 against part 4.
- Test support: `LabTarget` (`lab-db-01` of the `demo-lab` image, started once per test JVM), `RunnerFixture`.

Interfaces of `vigil-diagnostics` for later packages (P-18):

- P-29: the `diagnostic_result` evidence content (`values` in parser order).
- P-35, P-37: `DiagnosticsService.definitions`, `definition` (`parameter_valid`, DM-A-27; DC-M-13).
- P-36: `DiagnosticsService.results` (DM-R-49).
- Test support: `DiagnosticsFixture`.

Interfaces of `vigil-knowledge` for later packages (P-20):

- P-23: `KnowledgeService.pastIncidents` (the seeded history, DM-R-52, DM-R-53).
- P-24 (built): `KnowledgeService.latestSearchResult` (DM-E66-R2) and `pastIncident`; the key check of part 8 against part 14 (action keys).
- P-36: `KnowledgeService.latestSearchResult` (DM-R-48).
- P-39: `TaskProviderChanged` starts re-embedding; `KnowledgeService.settings` (the embedding state).
- Test support: `TestEmbedding`, `KnowledgeFixture`.

Interfaces of `vigil-investigation` for later packages (P-20):

- P-21, P-22: `InvestigationService.summaryForMessage` (DM-E69-R2).
- P-43 (built), P-29: `InvestigationService.latestAccepted`, `results`, `latestSummary` (DC-S03-09).
- P-39: `InvestigationService.requestRerun` (refusals `NOT_FOUND`, `REFUSED` `not_rerunnable`).
- Test support: `InvestigationFixture`.

Interfaces of `vigil-actions` for later packages (P-24):

- P-43 (built): `ActionsService.request`, `requests`, `proposal`, `withoutProposal`, `executions`, `decisionRefusal`; `decide` (AR-I-35).

## Local installation

The local installation runs the primary demo on one Docker host — the owner's Mac, a GitHub runner or a cloud session — with no external service (`docs/60-build-plan.md` Section 11): `vigil-db`, `vigil-migrate` and `vigil-app` built from this repository, the lab with its monitoring and `demo-faults`, the dev fakes of `dev/fakes/` (Jira, Teams, Claude, OpenAI, the local model, the local embedding model) and Mailpit for SMTP. On the Mac it needs Docker, `python3.12`, Eclipse Temurin 25 as the default `java` and the `/etc/hosts` line for `demo.vigil.test` (BP-OT-10).

```bash
deploy/local.sh up [--profile test|demo] [--values FILE] [--no-build] [--project NAME]   # the whole installation
deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]                     # the lab only, with the webhook recorder
deploy/local.sh down [--volumes] [--project NAME]                                         # stops everything; --volumes also removes the data
deploy/local.sh reset [--project NAME]                                                    # clears the faults and makes a new tenant
deploy/local.sh logs [--no-follow] [SERVICE...] [--project NAME]                          # the last 200 log lines, followed
deploy/local.sh e2e [SUITE] [--project NAME]                                              # the Playwright tests
```

`up` builds the JAR (`./mvnw -B -q -pl app -DskipTests package`) and the local images (`deploy/images.sh --local`) unless `--no-build`; generates the secrets; starts the lab, then `vigil-db`, `vigil-migrate`, `vigil-app`, the six fakes and `mailpit`; waits until Vigil is ready; builds the seed bundle from `docs/31-demo-data/` with the deployment values (`deploy/local/bundle.py`); and creates the tenant, imports the bundle and points `demo.vigil.test` at it through the installation operations (`deploy/local/install_tenant.py`). Vigil then answers at `http://demo.vigil.test:8080` (published on 127.0.0.1 only; the user `presenter` with the password in `~/.vigil-local/{project}/secrets/vigil-app/seed-password-presenter`). Inside the compose project `demo.vigil.test` is a network alias of `vigil-app`, so the lab's webhooks and Playwright use the same name. No other service publishes a port.

The values file is `deploy/values/local.yaml` (committed; it selects the dev fakes); `--values` names another, such as `deploy/values/local-real.yaml` on the owner's Mac (ignored by git, because it holds the owner's own mailbox addresses), which selects real services. When its `endpoint.local_embedding` is `http://vigil-embed:11434`, `up` also starts `vigil-embed` (compose profile `embed`) and pulls `embeddinggemma` into it; with `gpu_host_ip: host.docker.internal` Vigil uses an Ollama running natively on the Mac. The profile defaults to the values file's `lab_profile` (`test`: a 4 GiB `/data`; `demo`: 16 GiB).

Files under `~/.vigil-local/{project}/` (`VIGIL_LOCAL_HOME` replaces `~/.vigil-local`): `secrets/{unit}/` (files 0444 in folders 0555, one value per secret name in every folder that holds it; a file placed there by the owner, such as real credentials, is kept), `values/derived.yaml` (`lab_db_host_key_sha256`), `state/` (the values file and the lab profile `up` used, `bundle.zip`, `seed-import.json`, `tenant.json`), `e2e/` (the Playwright report and traces), and the lab's `generated/`, `config/` and `recordings/`. A new database secret with the database volume kept stops `up`: run `down --volumes`. Secrets never enter the repository.

`reset` clears the faults SL-F-1 to SL-F-3 through `demo-faults` and makes a new tenant as `up` does; the earlier tenant stays, not active. The alias moves at once, so run `reset` when no lab alert fires any more.

`e2e` runs the Playwright tests of `tests/e2e/` in the container `e2e` (image `vigil/e2e:local`, built by `deploy/images.sh --local` from the pinned Playwright image with its `node_modules`): first every test not tagged `@ai-down`, then, with `claude-fake` and `openai-fake` stopped, the tests tagged `@ai-down`; a suite name selects the tests tagged `@{suite}` (Example: `deploy/local.sh e2e smoke`). Every fake lists the calls it received at `GET /_fakes/calls` (and `jira-fake` its issues at `GET /_fakes/issues`); `.github/workflows/e2e.yml` runs `up` and `e2e` on pull requests labelled `e2e`.

`deploy/images.sh --test` builds the images that tests use, after pulling the pinned third-party images of `deploy/local/compose.yaml` (with retries; not the optional Ollama image of `vigil-embed`): `vigil/demo-lab:local` (the five lab hosts, `demo/lab/`), `vigil/demo-faults:local` (`demo/faults/`), `vigil/lab-zabbix-provision:local` (`demo/monitoring/provision/`), `vigil/recorder:local` (`dev/recorder/`), `vigil/storm-spike:local` (`spikes/storm-timing/`) and `vigil/sse-probe:local` (`spikes/sse-probe/`). `deploy/images.sh --test --java` builds only `vigil/demo-lab:local`, the image the Java tests use, pulling only the base images of its Dockerfile. `deploy/images.sh --local` builds `vigil/vigil-app:local` (from `app/target/vigil-app.jar`), `vigil/dev-fakes:local`, `vigil/e2e:local` and the storm driver `vigil/storm-driver:local`. It is the only way to build local images: it passes the proxy variables and the CA bundle the build needs.

`up --lab-only` starts the five lab hosts (in dependency order), Zabbix (`lab-zabbix-db`, `lab-zabbix-server`, `lab-zabbix-web`, then the one-shot `lab-zabbix-provision`), Prometheus, Alertmanager, `demo-faults` (on the host network at `http://172.31.0.1:8091`) and the webhook recorder, which answers at `http://demo.vigil.test:8080` on `lab-net` in place of Vigil (`webhook_base_url` of `deploy/values/local.yaml`). It generates only the lab's secrets: the `lab-db-01` key pairs and host key, and the monitoring and fault secrets `webhook-zabbix`, `webhook-alertmanager`, `alertmanager-password`, `alertmanager-web-config`, `zabbix-admin-password`, `zabbix-db-password` and `faults-token`; it copies the Zabbix API token that `lab-zabbix-provision` writes to `~/.vigil-local/{project}/generated/zabbix-api` into `secrets/vigil-app/` and `secrets/demo-control/`. The full `up` removes a running recorder, because `vigil-app` takes its alias. One local installation runs per Docker host, because its subnets and container names are fixed.

The storm driver (SB-T-02, P-27) runs the primary demo headless on the installation that is up and checks its result:

```bash
tests/acceptance/storm/run.sh [--mode immediate|script] [--deliveries fakes|checklist] [--project NAME]
```

It resets as the control panel will (a new tenant, the bundle `up` built, the lab cleared, the alias last), injects the storm, acknowledges as `noc-l1` and approves as `dbops-engineer` through the links it builds, waits until the storm incident is resolved, and checks the values of SB-S01 to SB-S09 that need no screen, with the Teams calls, emails and Jira issue of the dev fakes (`--deliveries checklist` writes a checklist of the expected messages instead). Mode `immediate` (default; `e2e.yml`) acts at once; mode `script` follows the script clock and also checks the exact timing and dashboard values, and with the `demo` profile the diagnostic and action values: start it at least 16 minutes after `up` (the 15-minute period of `disk_growth`). It writes `storm-{mode}-{UTC time}.json` to `VIGIL_STORM_RESULTS` (default `tests/acceptance/results/`), then scans the whole logs of `vigil-app` and `vigil-migrate`, kept in `~/.vigil-local/{project}/acceptance/`, for every secret of the installation (AR-Q-07). `down` and `logs` include its compose profile `acceptance`. See `tests/acceptance/storm/README.md`.

The storm timing spike (SB-T-01, development run) runs against the lab that is up:

```bash
spikes/storm-timing/run.sh [--storms N] [--extra LIST] [--save-recordings] [--foreground] [--project NAME]
```

It writes `spikes/results/storm-timing-{UTC time}.json` (see `spikes/storm-timing/README.md`).

## Demo installation on AWS

The demo installation runs on AWS in `us-east-1` (`docs/50-architecture.md` Section 17). Its scripts run on the owner's Mac with the AWS CLI (signed in with `aws login`); they act on the hosts through AWS Systems Manager. `deploy/edge.md` gives the steps, in order, and the prerequisites.

- `deploy/edge.sh {installation}`: creates, idempotently, the network, security groups, roles, ECR repositories, deployment bucket, Route 53 zone, SES identity, certificate, load balancer and the two hosts with their bootstrap.
- `deploy/new-installation.sh {installation}`: generates the installation's secrets into Parameter Store `/vigil/{installation}/`, never overwriting one.
- `deploy/build.sh {release}`: builds the release's images for `linux/amd64` and pushes them to ECR (run by `.github/workflows/release.yml` on `main`).
- `deploy/deploy.sh {installation} {release}`: uploads the deployment files to the deployment bucket and runs the deployment sequence on the main host (secrets through `deploy/secrets.sh`, pull, start, `deployed.yaml`, the models check).
- `deploy/status.sh {installation}`: the hosts' states and, on the main host, the containers, secret directories, health and models.
- `deploy/gpu.sh start|stop|status`: starts the GPU host and waits until `vigil-llm` answers, stops it, or shows it.
- `deploy/models.sh update {model}`: pulls a model again and writes its new digest into `deploy/models.lock`, which the operator commits.
- `tests/acceptance/p05_aws_check.sh {installation}`: records the resources and the HTTPS checks in `tests/acceptance/results/P-05-aws.txt` (owner-run step of P-05).
- `tests/acceptance/p06_log.sh {command} [{argument} ...]`: runs the command and appends its output and `exit {code}` to `tests/acceptance/results/P-06-gpu.txt` (owner-run steps of P-06).
- `spikes/providers/run.sh jira|claude|openai|similarity|local-model [--package P-20]`: the provider spikes of P-06 against Jira Cloud, the Claude and OpenAI APIs, `vigil-embed` and `vigil-llm` (owner-run; see `spikes/providers/README.md`). They write `spikes/results/P-06-*.json` and the recordings under `dev/fakes/recordings/`; with `--package P-20` they write `spikes/results/P-20-*.json` and keep their recordings in a temporary folder (P-20's owner-run step).
- `spikes/providers/run.sh teams|email` (from P-07): the spikes SB-T-06 (Teams, the nine channels' Workflows webhooks) and AR-SP-02 (email through Amazon SES to two shared mailboxes), which ask the owner what only a person can see (owner-run; see `spikes/providers/README.md`). They write `spikes/results/P-07-SB-T-06.json` and `P-07-AR-SP-02.json` and the recordings under `dev/fakes/recordings/teams/` and `email/`.

The tests of these scripts (`deploy/test_*.py`) run in the gate with fakes of the AWS CLI, Docker, `curl` and `ssh-keygen` (`deploy/tests/`); nothing in the gate calls AWS.
