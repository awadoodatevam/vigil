# P-18 — Report: Runner and diagnostics

| Field | Value |
| --- | --- |
| Package | P-18 Runner and diagnostics (spec `docs/packages/P-18.md`) |
| Branch | `p-18-runner-diagnostics` |
| Pin date (BP-R-08) | 26 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command passes in this session (with `RunnerLabIT` and `DiagnosticStepIT` on `vigil/demo-lab:local`, without the pytest lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request |

## 1. What was built

Every public type of `vigil-runner` is in `vigil.runner.api`, of `vigil-diagnostics` in `vigil.diagnostics.api`; every other class is package-private, exactly the classes of spec 6.1. The `@ApplicationModule` declarations are unchanged. No class has a `@Profile`; nothing runs work in the background (the SSH client of `LinuxServerConnector` starts at its first run and stops at `@PreDestroy`). Logging is the complete list of spec 6.1: one `INFO` line per run that ends in `RunnerService.run` (refused runs included) and one `WARN` line per run ended by restart step (1); nothing else at `INFO` or above from `vigil.runner` and `vigil.diagnostics`; no secret, key or command text is logged.

| Part | Requirement IDs |
| --- | --- |
| `vigil.connectors.api.TargetConnector` (`kind()`, `run(…)`, Javadoc "external call: not inside a transaction") and the sealed `TargetResult` with `NotStarted`, `Finished`, `Interrupted` (spec 6.4) | AR-K-00b, PM-M04-02 |
| `vigil.runner.api` (spec 6.4, complete list): `PermissionKind`, `OperationRunState` (each with `key()` and `fromKey`), `OperationView`, `GrantView`, `OperationRunView`, `RunRequest`, `RunnerPart` (nested `OperationPart`, `GrantPart`), `RunnerService`, `RunnerImport`, `RunnerRestart` | DM-E-12, DM-E-13, DM-E-14, PM-M05-04 |
| Migration `runner/V20261026090000__runner_create_runner_tables.sql`: `runner.operation`, `runner.target_access_grant` (`configuration`), `runner.operation_run` (`stateful`) with the constraints of spec 6.3, among them the unique index `(tenant_id, target_key, permission_kind) WHERE active` and the unique index `(tenant_id, request_id)` | DM-E-12, DM-E-13, DM-E13-R2, DM-E-14, DM-E14-R1, AR-P-06, DM-C-04, DM-C-06 |
| `RunnerImporter` (seed part 4): the checks of spec 6.5 in their order with their texts, `Parameters.definitionProblems` for every `parameters` list; `INVALID` "Part runner refused." with `problems`, nothing stored; otherwise one transaction, `timeout` in seconds, everything `active`; no audit event, no event | DM-R-57, DD-R-07, DM-V-06, DM-E13-R1, DM-E13-R2, DD-F-04 |
| `Runner.run` (spec 6.6): argument checks; one transaction for the earlier run of the request id, the permission-kind exception, the refusals (a) to (c) and the `running` insert (`ON CONFLICT (tenant_id, request_id) DO NOTHING`, then the stored row read); the call with no transaction open through `TargetConnectors`; one transaction that ends the run when it is still `running`; outcomes as CTX-D-058 (2) | PM-M05-01 to PM-M05-03, DM-E12-R1, DM-E13-R2, DM-E14-R1, DM-SM-10, SL-B-05, AR-R-09, DM-C-11 |
| `Parameters` (value checks, texts), `Commands` (one-scan fill, bash quoting), `OutputParsers` (the five parsers, values as an ordered array of `{name, value}`, GiB as one-decimal texts) | DM-V-06, AR-K-12, DD-R-10, AR-K-13 |
| `LinuxServerConnector` (`TargetConnector` of `linux_server`) with Apache MINA SSHD: settings check, `Secrets.read` at each run, the `{"username", "private_key"}` credential with an OpenSSH key without passphrase, one connection per run without SSH configuration file, known-hosts file, agent or other identity; name resolution, connection and key exchange within 5 s; the host key accepted only when its `SHA256:` fingerprint equals `host_key_sha256` (refused before authentication); public-key authentication only; one exec channel; the timeout bounds the call from the start of the connection attempt; output decoded as UTF-8 with malformed input and U+0000 as U+FFFD, kept to 65,536 code points (error output 1,000) | AR-K-03, AR-K-11, AR-K-12, AR-K-13, AR-T-12, AR-TM-01, AR-B-07, AR-S-04, AR-S-13, AR-Q-09, PM-K-03 |
| `TargetConnectors` (the `TargetConnector` beans by `kind()`; two of one kind or a kind not of type `TARGET` is an `IllegalStateException`) | AR-K-00b, PM-M04-02 |
| `RunnerRestart.markInterruptedRuns` (per active tenant, one transaction: `running` → `unknown` for `action`, `failed` for `diagnostic`, `interrupted by restart`); `RestartProcedure.step1MarkInterruptedOperationRuns` calls it | AR-R-24, DM-R-70, SL-B-11 |
| `vigil.diagnostics.api` (spec 6.4, complete list): `DiagnosticOutcome`, `DiagnosticDefinitionView`, `DiagnosticResultView`, `DiagnosticsPart` (nested `DefinitionPart`), `DiagnosticsService`, `DiagnosticsImport`; `DiagnosticsStepHandler` unchanged | DM-E-53, DM-E-54 |
| Migration `diagnostics/V20261026090100__diagnostics_create_diagnostics_tables.sql`: `diagnostics.diagnostic_definition` (`configuration`), `diagnostics.diagnostic_result` (`immutable`, unique index `(tenant_id, step_execution_id, diagnostic_key)`) | DM-E-53, DM-E-54, DM-R-64, AR-P-06 |
| `DiagnosticsImporter` (seed part 12, spec 6.8) | DM-R-57, PM-M13-01, DD-F-04 |
| `DiagnosticStep` (`DiagnosticsStepHandler`, spec 6.9): `start` and `evaluate` the same; every definition in list order, also after a failure; a stored result is kept and not run again; the run with `DIAGNOSTIC` and the request id `{step execution id}:{diagnostic_key}:{evaluation_count}`; each result in its own transaction (`ON CONFLICT … DO NOTHING`), a succeeded one with its `diagnostic_result` evidence entry (content, source and summary of DC-TX-03); `Completed(done)` or `Completed(failed)` with no result reference; `cancel` does nothing | DM-S-03, PM-M13-02, PM-M13-03, PM-S-03, DM-R-64, DC-TX-03, AR-R-21, SL-A1-03, SL-B-05 |
| `Summaries` (one-scan fill of a summary template) | DD-R-10, DC-TX-03 |
| `vigil.setup`: `RunnerAdapter` (part 4), `DiagnosticsAdapter` (part 12), `ServiceMapTargetCheck` (5 → 4), `DiagnosticOperationCheck` (12 → 4), `WorkflowDiagnosticCheck` (16 → 12); `WorkflowKeys.of` also returns the keys of `diagnostics` (after `eligible_teams` and `named_teams`) | AR-B-11, BP-R-29, DM-R-60 |
| Test changes of spec 6.10: the eight `TestHandlers.Beans` adapter beans `@Primary`; `WorkflowRunIT` (tenant D removed, incident I8, the reworked DM-R-70 method); `SeedImportIT` changes (1) to (8); `SeedImportRestartIT`; `README.md` "Module code" | DM-R-70, CTX-D-057 (4), P18-DC-03 |
| `app/pom.xml`: `org.apache.sshd:sshd-core` 2.20.0 with `org.slf4j:jcl-over-slf4j` excluded | AR-T-12, P18-DC-02 |

Checked by review (spec part 4): AR-T-12 (`sshd-core` in `app/pom.xml`; `RunnerLabIT` connects with it); AR-R-08 and PM-R-04 (the only SSH code is in `vigil.runner`, `ConnectorLibraryRuleTest` passes; no class of `vigil.connectors` uses `org.apache.sshd`); AR-M-05, AR-M-17, PM-R-02, PM-R-03 (`ModuleStructureTest`; `vigil-runner` uses `tenancy`, `connectors`, `audit` only through their `api`, `vigil-diagnostics` `runner`, `servicemap`, `incidents` only); AR-P-06 and DM-C-04 (the five rows in `table-classes.csv`, `PrivilegeIT`; no `DELETE`); DM-C-06 (incidents, runs, step executions, connector instances and credentials held by id or key; no foreign key at all; `SchemaRuleTest`); DM-C-10 and PM-R-05 (every statement of `RunnerRecords` and `DiagnosticRecords` filters by `tenant_id`; `TenantColumnIT`; `RunnerRequestIT`, `RunnerLabIT` and `DiagnosticStepIT` use several tenants); PM-R-01 (`SchemaRuleTest`); PM-R-08 (`SeparationTest`); BP-R-06 (`LabTarget` uses only the local image `vigil/demo-lab:local`); BP-R-30 (the lab tests use the `test` profile and compare size-dependent values by form and relation).

## 2. Tests added

| Test class | Methods (display names start with) |
| --- | --- |
| `vigil.runner.ParametersTest` (new) | DM-V-06 |
| `vigil.runner.CommandsTest` (new) | DD-R-10, AR-K-12 |
| `vigil.runner.OutputParsersTest` (new) | AR-K-13 (two methods) |
| `vigil.diagnostics.SummariesTest` (new) | DD-R-10, DC-TX-03 |
| `vigil.runner.RunnerImportIT` (new) | DM-E-12, DM-E-13, DD-R-07, DM-E13-R2, DM-R-57 (every case of spec 7, each on a new tenant, nothing stored, then the unchanged part imported) |
| `vigil.runner.RunnerRequestIT` (new) | PM-M05-01, DM-E12-R1, DM-V-06, DM-E-14, DM-E14-R1, SL-B-05, PM-M05-02, AR-S-04 |
| `vigil.runner.RunnerLabIT` (new) | AR-K-11, AR-B-07, AR-S-13, DM-E13-R1, DD-F-04, PM-M05-03, PM-M05-04, AR-K-12, AR-K-13, DM-C-11, DM-SM-10, AR-TM-01, AR-R-09, AR-K-03, AR-K-00b, PM-K-03, PM-M04-02 |
| `vigil.runner.RunnerRestartIT` (new) | AR-R-24, DM-R-70, SL-B-11 |
| `vigil.diagnostics.DiagnosticsImportIT` (new) | DM-E-53, PM-M13-01, DM-R-57 |
| `vigil.diagnostics.DiagnosticStepIT` (new) | SL-A1-03, PM-M13-02, DM-E-54, DC-TX-03, DM-R-64, DM-S-03, PM-M13-03, PM-S-03, AR-R-21 |
| `vigil.setup.SeedImportIT` (changed as spec 6.10) | new: DD-F-04, AR-B-11 (two methods), DM-R-60 (five methods), BP-R-29 |
| `vigil.setup.SeedImportRestartIT`, `vigil.workflows.WorkflowRunIT` (changed as spec 6.10) | their methods; DM-R-70 reworked |
| `vigil.connectors.ExternalCallGuardIT` (changed) | new: AR-Q-04, AR-R-09 |
| `vigil.SecretCallerRuleTest` (changed) | new: AR-Q-09 |
| Test support (new, public) | `vigil.runner.LabTarget`, `vigil.runner.RunnerFixture`, `vigil.diagnostics.DiagnosticsFixture` |

Every row of spec part 4 with proof `test` has a method whose display name starts with its ID; `tools/trace.py --check` passes. The new `*IT` classes run their methods in the order of the Checks column (`@TestMethodOrder(OrderAnnotation)`, `@TestInstance(PER_CLASS)`), each on its own `VigilDatabase.fresh()` with `VigilTestApp.start(database, secrets folder, List.of())`, no extra configuration and the real clock. JSON expectations compare parsed trees. `RunnerLabIT` and `DiagnosticStepIT` use `LabTarget.shared()` (one privileged `vigil/demo-lab:local` container per test JVM, `LAB_PROFILE=test`).

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | Package-private static helpers besides the exact signatures of spec 6.1: `Parameters.JSON` (the module's `JsonMapper`), `Parameters.KEY`, `Parameters.readOrNull`, `Parameters.isKey`; `Runner.require` and `Runner.cut`; `LinuxServerConnector.fingerprint`; `DiagnosticRecords.JSON`, `DiagnosticsImporter.KEY`, `Diagnostics.require`. Nested private records and classes (`Runner.Started`, `RunnerRecords.NewRun`, `RunEnd`, the connector's `Target`, `Deadline`, `HostKeyCheck`, `Collector`). | Internal structure only; no other class was added. |
| 2 | Test support beyond the methods spec 7 names: `RunnerFixture.JSON`, `operationsFile()`, `definitions(key)` (the demo parameter definitions, used by the unit tests), and the key helpers `newKeyPair()`, `secret(…)`, `privateKeyText(…)`, `publicKeyLine(…)` shared with `LabTarget`. `DiagnosticsFixture.openIncident(null)` posts its alert with no component (there is no component to put it on). | Test helpers only. |

Readings of the spec where it gives no detail (no behaviour beyond the spec): a path "holds `..`" when the text contains `..`; an allowed prefix is "not an absolute path" when it has a `..` segment; `df_row` splits the last line on runs of spaces after removing its leading and trailing white space; a `removed_files` line without a space is not readable; parameters are stored as `{}` when a text anywhere in the JSON object holds U+0000; the parameters stored are the parsed object written again (the same JSON tree); a `null` argument of `TargetConnector.run` is an `IllegalArgumentException` ("a required argument is missing"); name resolution runs on a virtual thread within the 5 s connection time; a channel that cannot be opened is `Interrupted("the command ended without an exit status")` (from opening the channel on, the command may have run); the step type check of `DiagnosticStep` says "not a diagnostic step: {type}"; `grants` and `definitions` are ordered by code point (`COLLATE "C"`).

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 26 October 2026)

| Dependency | Version | Note |
| --- | --- | --- |
| `org.apache.sshd:sshd-core` | 2.20.0 | The latest release on the pin date (26 September 2026; `3.0.0-M1` to `-M6` are milestones). It declares `slf4j-api` 1.7.36, which the Spring Boot BOM manages to 2.0.18, so it needs no newer version of a BOM-managed library (CTX-D-038). `org.slf4j:jcl-over-slf4j` (a compile dependency of the `sshd` parent) is excluded. |

Ed25519: `RunnerLabIT` (AR-K-11, DM-E13-R1) and `LabTarget` prove that the pinned `sshd-core` with the already pinned `bcprov-jdk18on` 1.86 alone generates, writes and loads OpenSSH ed25519 private keys and verifies the `ssh-ed25519` host key on Java 25. Neither `bcpkix-jdk18on` nor `net.i2p.crypto:eddsa` was added (spec 6.2).

The dependency tree that `sshd-core` adds (`./mvnw -pl app dependency:tree -Dverbose`, the `sshd-core` branch):

```
+- org.apache.sshd:sshd-core:jar:2.20.0:compile
|  +- org.apache.sshd:sshd-common:jar:2.20.0:compile
|  |  \- (org.slf4j:slf4j-api:jar:2.0.18:compile - version managed from 1.7.36; omitted for duplicate)
|  \- org.slf4j:slf4j-api:jar:2.0.18:compile (version managed from 1.7.36)
```

`sshd-common` 2.20.0 is the only new artifact; `slf4j-api` was already on the class path. (The `jcl-over-slf4j` that remains on the class path comes from `spring-modulith-events-core`, not from `sshd-core`.)

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step). The exact `demo` values of DD-F-10 `expected_diagnostics_after_SL-F-1` stay with P-27's Mac run and P-32 (BP-R-30).

## 9. Lines of production code added and changed (P18-DC-01)

`tools/loc.sh origin/main HEAD` prints **3,250** (size L; at most 3,500).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1a built the local images, `vigil/demo-lab:local` among them. Step 2: `vigil-app` 140 unit tests and 479 `*IT` (every earlier test passes; the changed tests only as spec 6.10 and spec 7), among them `RunnerLabIT` and `DiagnosticStepIT` on `vigil/demo-lab:local` in the `test` profile; `demo-control` 1 and 2; the web build, lint, type check and Vitest pass. Step 3: `ruff` clean, 81 Python tests passed, 1 skipped and 35 deselected (no file under `demo/`, `deploy/`, `dev/`, `spikes/`, `tools/` or `docs/31-demo-data/` changed, so the lab tests did not run, CTX-D-036). Step 4: `shellcheck`. Step 5: 49 data checks, 0 failed. Step 6: compose validation. Step 7: `tools/trace.py --check`.

The gate ran on the tree of commit `276bbb9`. Commit `e17c9e4` changes only where `LinuxServerConnector` starts counting the operation's timeout: at the start of the connection attempt (step (4) of spec 6.6) instead of at the start of `run`. After it, `RunnerLabIT`, `RunnerRequestIT` and `ExternalCallGuardIT` were run again and pass (29 tests).

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1); BP-DC-03 (nothing beyond the spec: no API operation, no screen, no setting; the parts and checks of spec 6 only); BP-DC-04 (AR-Q-01 `ModuleStructureTest`, AR-Q-02 `SchemaRuleTest`, AR-Q-03 `ConnectorLibraryRuleTest`, AR-Q-06 `PrivilegeIT`, AR-Q-08 `TenantColumnIT`, AR-Q-09 `SecretCallerRuleTest` pass; no new event listener; `RunnerService.run`, `TargetConnector.run`, `DiagnosticsStepHandler.start` and `evaluate` are in AR-Q-04 in `ExternalCallGuardIT`); BP-DC-05 (two migrations named as AR-P-05; no OpenAPI operation; the part 4 and part 12 adapters and the three key checks follow AR-B-11); BP-DC-06 (`README.md` "Module code" updated; no command added or changed); BP-DC-07 (this report); P18-DC-01 (Section 9); P18-DC-02 (Section 6); P18-DC-03 (`README.md`); P18-DC-04 (this section).

## 11. Follow-up items for later packages

1. P-24: `RunnerService.run` with `ACTION` (and `DIAGNOSTIC` for state checks) and the request ids of DM-E14-R1; an action whose command was sent and which then timed out or lost its connection ends `unknown`; output values are an ordered array of `{name, value}`. Runs of a tenant that is not active at a restart stay `running` (CTX-D-058 (9)); the reconciliation must not wait on such an action run when the tenant becomes active again.
2. P-24: the key checks of part 14 against part 4 (operation keys of registered actions, preconditions and reconciliations) use `RunnerService.operation` and `parameterProblems`, as `DiagnosticOperationCheck` does.
3. P-20 to P-24: the real `DiagnosticStep` now runs in every live run of a demo-seeded active tenant (as in `ContractIT`, where its runs fail with `Secret lab-db-01-diag is not available.` and the step leaves by `failed`); the engine tests keep their test adapters through `@Primary`.
4. P-29: the `diagnostic_result` evidence content is `{diagnostic_key, diagnostic_name, component_key, target_key, operation_key, operation_run_id, values}`, `values` in parser order (DC-S03-08).
5. Logging (AR-S-11): Apache MINA SSHD writes a few `INFO` lines of its own under `org.apache.sshd` (the Bouncy Castle registration, the I/O service factory, a key-exchange extension) at the first run; they are in the JSON format and hold no secret. Spec 6.1 limits only `vigil.runner` and `vigil.diagnostics`, so P-18 leaves the library's logger as it is; noted for the owner.
