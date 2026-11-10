# P-20 — Report: Knowledge and investigation

| Field | Value |
| --- | --- |
| Package | P-20 Knowledge and investigation (spec `docs/packages/P-20.md`) |
| Branch | `p-20-knowledge-investigation` |
| Pin date (BP-R-08) | 10 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; the gate command passes in this session (Section 10). Owner-run results recorded (Section 8): Claude and OpenAI pass; the local-model run is deferred to P-34's SB-T-04 by owner decision (CTX-D-065, to be recorded by the owner). |

## 1. What was built

`vigil-knowledge` and `vigil-investigation` keep their allowed dependencies (spec 6.1); `vigil-knowledge` uses `audit :: api` not at all. Every public type is in the modules' `api` packages; every other new class is package-private. Neither module uses an HTTP client; every AI call goes through `AiGatewayService`. Every statement filters by `tenant_id`; nothing is deleted.

| Part | Requirement IDs |
| --- | --- |
| Gateway read (spec 6.2): `ProviderView` and `AiGatewayService.provider` (a read of DM-E-70) | DM-R-60, DC-TX-03 |
| `IncidentService.recordAiRequestGroup` (spec 6.2): only from `vigil-investigation`, the incident locked, the guard of `setAiStatus`, no change for the same group, no timeline entry, `IncidentChanged` | AR-R-29, DM-E32-R2 |
| `RestartProcedure.step6StartScheduledWork` calls `KnowledgeScheduling.matchActiveTenants()` after `incidentScheduling`, before `engineScheduling` | AR-R-24 (review) |
| Migrations `knowledge/V20261110100000__knowledge_create_knowledge_tables.sql` and `investigation/V20261110100100__investigation_create_investigation_tables.sql` (each starting with `GRANT USAGE …`): the seven tables of spec 6.3 with their checks, indexes, `public.vector` without dimension and without index, grants by class; seven rows in `table-classes.csv` | DM-E-63 to DM-E-69, AR-P-01, AR-P-05, AR-P-06, AR-P-07, AR-Q-06, DM-C-04, DM-C-06, DM-V-01 |
| `vigil.knowledge.api` (spec 6.4, as written) | PM-M16-01 to PM-M16-05 |
| `KnowledgeImporter` (spec 6.6): checks 1 to 4 with their texts and the U+0000 rule; one batched `embed` call of the past incidents' search texts (`incident_history`) with no transaction open; the part refused with the embedding problem when the call does not give usable vectors; the store in one transaction opened after the call (check 1 again); times from the offsets and `importStartedAt`; no audit event, no event | PM-M16-01, DD-F-08, DD-F-09, DM-E-63 to DM-E-65, DM-E-67, DM-R-57, AR-B-08, AR-B-09, AR-G-30, DM-C-11 |
| `SearchTexts` (DM-R-51), `Vectors` (the checks and text form of spec 6.6) | DM-R-51, AR-G-30 |
| `Knowledge` (spec 6.8): `applicableRunbooks` by rules; `search` — `reembedding` refused without a call, one embedding with the settings' provider (`alert_data`, `service_context`), the settings read again in the store transaction, the live embedding, the exact pgvector scan with `OPERATOR(public.<=>)` on the query's number of dimensions, rounding half-up in Java, ties to the first key, `match` / `no_match` with the best score, the stored result; the reads | PM-M16-02, PM-M16-03, PM-M16-05, DM-R-50, DM-E-65, DM-E-66, DM-E-72, AR-P-07, SL-T-3, SL-A1-06, DM-R-52 (review) |
| `Reembedding` (spec 6.9): a 30 s task per active tenant with settings, one virtual thread `reembed-{tenant}` per tenant, passes of at most 50 past incidents per call with `reembedding_provider_key`, conditional `UPDATE`s, the `INFO` and `WARN` lines | DM-SM-15, PM-M16-04, AR-R-23, AR-R-30, AR-TM-07, AR-E-14 |
| `KnowledgeListeners` (spec 6.9): `TaskProviderChanged` (the no-change cases, then `reembedding` and `Reembedding.start` after commit), `TenantAliasChanged` | AR-E-11, AR-E-14, AR-Q-05 |
| `vigil.setup.KnowledgeAdapter`, `RunbookFile`, `PastIncidentFile` (spec 6.5): part 8 with counts 7, 50 and 1 for the demo data | DM-R-56, DM-R-57, DD-F-08, DD-F-09 |
| `KnowledgeAlertTypeCheck` (8 → 6), `KnowledgeServiceMapCheck` (8 → 5), `KnowledgeProviderCheck` (8 → 7) (spec 6.7) | DM-R-60 |
| `vigil.investigation.api` (spec 6.4, as written) | PM-M17-01 to PM-M17-07 |
| `Requests` (spec 6.10): the SL-T-1 and SL-T-2 parts with their categories, labels `E1` …, the system texts and the two schemas of P-06 | PM-M17-02, DM-R-43, AR-G-10, AR-G-11, AR-G-12 |
| `OutputChecks` (spec 6.11): the reasons of DC-TX-11 in order, the single reason for an unreadable output, U+0000 as absent, the values | PM-M17-03, AR-G-11, AR-G-13, DM-E-68, DC-TX-11 |
| `Texts` (spec 6.11): the two evidence summaries and sources, the provider kind names, the audit summaries, the template summary | DC-TX-03, DC-TX-10, DM-E-69 |
| `Investigations` (spec 6.11, 6.12): `investigate` (the request group recorded first in its own transaction, the call with no transaction open, then one transaction with the result, the audit event, the `ai_investigation` entry and `setAiStatus`); `summarise`; `summaryForMessage`; `requestRerun` on `ai-rerun-{group}`; the reads | PM-M17-03 to PM-M17-07, DM-E-68, DM-E-69, AR-R-29, AR-R-09, DM-C-11, SL-T-1, SL-T-2, SL-A1-04, SL-A2-07 |
| `AiTaskStep` (spec 6.11): SL-T-1 (`done` when accepted, else `unavailable`), SL-T-2 (always `done`), SL-T-3 (the `similarity_match` entry after a match; `unavailable` for every `Unavailable` reason); `cancel` does nothing | PM-S-04, DM-S-04, PM-M17-01 |
| `spikes/providers/` (spec 6.14): the product's member lines, hypothesis text and SL-T-1 system text; E4 and `Investigation` only after an accepted SL-T-1 answer; `rejection_reasons`; the checks `sl-t-1-accepted` and `accepted-{k}`; `--package P-20` (result files `P-20-*.json`, recordings in a removed temporary folder); `README.md` of the folder and `spikes/results/README.md` | AR-G-12, PM-M17-03 |
| `README.md` "Module code" and "Demo installation on AWS", `CLAUDE.md` Commands row, exactly as spec 6.13 | — |

Checked by review (spec part 4): DM-R-52 (one table `knowledge.past_incident`; `KnowledgeService.pastIncidents` is P-23's read); AR-G-33 (the tests use `TestEmbedding`); AR-R-24 (`RestartProcedure.step6StartScheduledWork`); PM-R-01 (`SchemaRuleTest`); PM-R-02, PM-R-03 (`ModuleStructureTest`, unchanged); PM-R-04 (no HTTP client in either module, `AiGatewayRuleTest`, `ConnectorLibraryRuleTest`); PM-R-05 (every table has `tenant_id`, every request takes a `TenantId`; `TenantColumnIT`); PM-R-06 (the two AI result events only; part 8 writes none); PM-R-08 (`SeparationTest`); DM-C-04 (`PrivilegeIT`); DM-C-06 (no foreign key; incidents, runs and entries by id, providers, past incidents, runbooks and actions by key); DM-V-01 (`requested_by_*` as the Actor columns of `ai_gateway.task_assignment`).

## 2. Tests added

| Test | IDs |
| --- | --- |
| `knowledge/SearchTextsTest` | DM-R-51 |
| `setup/RunbookFileTest` | DD-F-08, DM-R-57 |
| `setup/PastIncidentFileTest` | DD-F-09, DM-R-57 |
| `investigation/RequestsTest` | AR-G-12, DM-R-43, AR-G-10 |
| `investigation/OutputChecksTest` (with the recordings `claude/0001`, `openai/0001`, `local-llm/0001` to `0005`) | AR-G-11, DC-TX-11, AR-G-13, DM-E-68, PM-M17-03 |
| `investigation/TextsTest` | DC-TX-03, DC-TX-10, DM-E-69 |
| `knowledge/KnowledgeImportIT` | PM-M16-01, DM-E-63, DM-E-64, AR-B-08, AR-B-09, AR-G-30, DM-E-67, DM-R-57, DM-E-65 |
| `knowledge/SimilaritySearchIT` | SL-A1-06, SL-T-3, PM-M16-03, DM-E-66 (2), AR-P-07, DM-E-72, PM-M16-05, DM-R-50, PM-M16-02 |
| `knowledge/ReembeddingIT` | DM-SM-15, PM-M16-04, AR-TM-07, AR-R-30, AR-R-23, AR-E-11, AR-Q-05, AR-E-14 |
| `investigation/AiTaskStepIT` | SL-T-1, PM-M17-02, SL-A1-04, PM-S-04, DM-S-04, PM-M17-01, PM-M17-03, AR-G-13, PM-M17-06, DM-E-68 (2), DM-C-11, PM-M17-04, PM-M17-05, SL-T-2, DM-E-69, AR-R-29 |
| `investigation/RerunIT` | PM-M17-07, AR-R-29, DM-E-68, SL-A2-07 |
| `incidents/IncidentRequestsIT` new method; `IncidentLifecycleIT` DM-E32-R1 method extended | AR-R-29, DM-E32-R1 |
| `setup/SeedImportIT` (the changes of spec 6.13 and three new methods); `SeedImportRestartIT` | DM-R-56, DM-R-60, DM-R-57 |
| `connectors/ExternalCallGuardIT` two new methods | AR-Q-04, AR-R-09 |
| `PrivilegeIT`, `TenantColumnIT` through the seven new rows of `table-classes.csv` | AR-Q-06 |
| `spikes/providers/test_request_data.py`, `test_ai_spike.py`, `test_local_model_spike.py`, `test_providers_run.py` (and `test_recordings.py` adapted) | AR-G-12, PM-M17-03 |

Test support: `testsupport/TestEmbedding`, `setup/KnowledgeParts`, `knowledge/KnowledgeFixture`, `investigation/InvestigationFixture` (spec 7); `AiFixture`'s five part 7 helpers are `public`; `SeedTests.demoEntries` and `InstallationIT.demoEntries` point `endpoint.local_embedding` at `TestEmbedding`.

Every one of the 59 part 4 rows whose proof is `test` has a test whose display name or docstring starts with its ID (`tools/trace.py --check` passes).

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `tools/loc.sh origin/main HEAD` prints 3,914, above BP-R-05's 3,500 and 14 lines above the spec's upper estimate of 3,900. | Recorded as CTX-D-064 (1) requires. The count is made of `vigil-knowledge` 1,769 lines (`KnowledgeImporter` 438, `KnowledgeRecords` 418, `Knowledge` 253, `Reembedding` 223, `KnowledgeListeners` 87, `Vectors` 54, `SearchTexts` 40, the api 256), `vigil-investigation` 1,545 (`Investigations` 439, `Requests` 233, `InvestigationRecords` 208, `OutputChecks` 185, `AiTaskStep` 174, `Texts` 128, the api 178), `vigil-setup` 537 (`PastIncidentFile` 157, `RunbookFile` 129, `KnowledgeAdapter` 73, the three key checks 178), `vigil-incidents` 30 (`recordAiRequestGroup`), `vigil-ai-gateway` 28 (`ProviderView`, `provider`) and `vigil-api` 5 (`RestartProcedure`). Nothing of the spec was left out or shortened; nothing beyond it was added. |
| 2 | `InvestigationService.summaryForMessage` joins the caller's transaction through a `TransactionTemplate` with propagation `REQUIRED` instead of the annotation `@Transactional(propagation = REQUIRED)`. | Same propagation. `Investigations` is then not proxied, so `AiTaskStep` can call its package-private `investigate`, `summarise` and `kindName` on the bean without a class proxy. |
| 3 | Test support beyond the spec: `VigilTestApp.database()` (the fixtures read and write "as `postgres`" through it); `KnowledgeParts.withFields`; the package-private `investigation/TestViews` (views for the unit tests); `AiTaskStepIT`'s package-private static helpers, used by `RerunIT`. | Test helpers only. |
| 4 | `RerunIT`'s `forming` incident is created with `IncidentService.create` as `vigil-correlation` in the test. | `InvestigationFixture` (spec 7) has no helper for a `forming` incident. |
| 5 | The `SimilaritySearchIT` tie case gives PI-1011 the storm's fields and puts it before PI-1001 in the part. | So the tie is decided by the key, not by the order given. |
| 6 | `tenancy/TenancyIT.aliasChangedPublication` expects 6 publications of `TenantAliasChanged` instead of 5, one of them for `vigil.knowledge.KnowledgeListeners`. Spec 6.13 does not list this test. | The listener of spec 6.9 (AR-E-14) adds the sixth publication; the test counts every listener, as P-14, P-16 and P-17 extended it for theirs. |

Readings of the spec where it gives no detail (no behaviour beyond the spec):

- Seed part 8: check 1 ("knowledge records already exist") is reported alone, as "the one problem"; an identical problem text is listed once (problems are kept in insertion order without repeats, as `SeedShapes` does); a `null` element inside a runbook's two lists is an `IllegalArgumentException` ("an element of a list"); a `null` value for `alert_types` or `affected_service_keys` of a past-incident row is checked as one empty element (`'' is not a key`); a runbook table row that is not `| field | value |` does not by itself withhold the `RunbookPart` (rule 3 names the field problems), and the adapter refuses the part because the problem is added to `SeedShapes`; an embedding answer with another number of vectors than texts counts as unusable vectors.
- `RunbookFile`: a heading line `## ` with only white space after it gives the heading `''`. `PastIncidentFile`: a header record that cannot be read (unclosed quote, text after a quote) gives the header problem.
- Search: an `Embedded` answer with other than one vector is `PROVIDER_ERROR` (the gateway already guarantees one vector per text); a `no_match` result's `run_id` is the request's (`null` when the request has none).
- Re-embedding: an exception in `tick` (before any settings were read) logs the `WARN` line with the provider `unknown`; the insert transaction of a batch locks the settings row (`FOR UPDATE`) before checking that they are still `reembedding` with the key; the listener retries its conditional `UPDATE` after re-reading the settings when it changed no row.
- Investigation: "only white space" is Java's `String.isBlank`; the spikes' `rejection_reasons` uses Python's `str.strip`, which also strips U+00A0, U+2007, U+202F and U+0085 (a string made only of those is "absent" in the spike but not in the product; not reachable with the recorded answers). `raw_output_truncated` is true when the output (with U+0000 replaced) is longer than 20,000 code points. The template summary sorts the alert type display names by code point. The SL-T-2 `Alerts` part uses the incident's `alert_count` and its `alert_types`.
- Spikes (the subagent's readings): a JSON text with `NaN`, `Infinity` or trailing text is "not JSON" (Jackson 3's defaults reject both too); "without an answer" means no HTTP answer at all (a non-200 answer gets the reasons of its body, "no likely cause"); the detail of a failed `sl-t-1-accepted` or `accepted-{k}` check is its reasons joined with `; `; cited keys in the E4 text are de-duplicated in the answer's order.

## 4. Document feedback (CTX-Q-8)

None.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 10 November 2026)

None. `TestEmbedding` uses WireMock's `ResponseTransformerV2` of the pinned `wiremock-standalone` 3.13.2; pgvector is the extension already in every database.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

Owner-run step of spec Section 6.14 (BP-E-03, BP-E-05), run by the owner from the Mac on 10 Nov 2026 and committed as `11d8de4` (`spikes/results/P-20-claude.json`, `spikes/results/P-20-openai.json`).

| Provider, model as the spike sends it from `07-settings.yaml` (result file, run id) | SL-T-1 answer accepted (`sl-t-1-accepted`) | Rejection reasons | Cites RB-DB-01 | SL-T-2 summary length | Other checks |
| --- | --- | --- | --- | --- | --- |
| Claude, `claude-sonnet-5-5` (`P-20-claude.json`, `20261110T165700Z`) | Yes (passed) | none (`[]`) | Yes | 574 characters | all 11 checks passed (`passed` true); SL-T-1 5.3 s, 11,225 input and 412 output units |
| OpenAI, `gpt-6.1-sol` (`P-20-openai.json`, `20261110T165754Z`) | Yes (passed) | none (`[]`) | Yes | 496 characters | all 14 checks passed (`passed` true); SL-T-1 11.6 s, 7,226 input and 319 output units (112 reasoning) |
| Local model, `gemma4:12b` (`P-20-SB-T-04.json`) | **Not run — deferred** | — | — | — | — |

The cost of the two runs together is 0.090424 USD by the spikes' own computation (Claude 0.053918, OpenAI 0.036506).

**Local model: not run, deferred.** By owner decision of 10 Nov 2026 (to be recorded by the owner as CTX-D-065), the `local-model` run with `--package P-20` is deferred to P-34's SB-T-04. `spikes/results/P-20-SB-T-04.json` is therefore not on the branch, and the local model's acceptance and durations are not measured by this package.

Pass rule of spec Section 6.14 (CTX-D-064 (2)): Claude's and OpenAI's `sl-t-1-accepted` passed — **met**. The local-model part (at least 4 of the 5 answers accepted, each within 45 s) is **not judged here**; it is deferred to P-34 by the owner's decision above. No blocking question is raised (Section 5).

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **3,914** (size L; estimate about 3,600, between 3,100 and 3,900; above 3,500, deviation 1 of Section 3 with the files that make it). The changes under `spikes/` are not counted (BP-R-05).

## 10. Gate command result

Cloud session (`tools/check-all.sh`): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1a built the local images. Step 2: `vigil-app` 172 unit tests and 576 `*IT` (every earlier test passes; the changed tests only as spec 6.13, spec 7 and deviation 6); `demo-control` 1 and 2; the web build, lint, type check and Vitest pass. Step 3: `ruff` clean; 242 Python tests passed and 1 skipped, the pytest lab tests included (this package changes `spikes/`, CTX-D-036). Step 4: `shellcheck`. Step 5: 49 data checks, 0 failed. Step 6: compose validation. Step 7: `tools/trace.py --check`.

The gate ran on the tree of commit `0de0e8f`; the later commits change only this report and add the two owner-run result files `spikes/results/P-20-claude.json` and `P-20-openai.json` (no code). GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check (spec Section 8): (1) BP-DC-01 to BP-DC-07: every part 4 row with proof `test` has a test that names it; the rows checked by review are listed in Section 1; nothing beyond the spec (no API operation, no screen, no setting); AR-Q-01 to AR-Q-09 pass, the new entry points are in `ExternalCallGuardIT`, the two listeners in the double-delivery test of `ReembeddingIT`; two migrations named as AR-P-05; no OpenAPI operation; the `README.md` and `CLAUDE.md` lines of spec 6.13; this report. (2) `ApplicationModules.verify()` passes with every module's dependencies unchanged (`ModuleStructureTest`). (3) `git diff origin/main -- dev/fakes/recordings spikes/results/P-06-*.json` is empty. (4) `spikes/results/P-20-claude.json` and `P-20-openai.json` are on the branch and Section 8 records them against the pass rule of spec Section 6.14 (met); `P-20-SB-T-04.json` is not run, deferred to P-34's SB-T-04 by owner decision of 10 Nov 2026 (CTX-D-065, to be recorded by the owner). (5) Section 9.

## 11. Follow-up items for later packages

1. P-39: publish `TaskProviderChanged` when the SL-T-3 provider changes (DM-E72-R1); `KnowledgeService.settings` gives the embedding state for AR-I-60; `InvestigationService.requestRerun` behind AR-I-26 with the permission check `rerun_ai_investigation` (refusals `NOT_FOUND`, `REFUSED` `not_rerunnable`); a "re-run is running" read, if DC-S03-09's "Re-running…" needs one, is that package's.
2. P-25, P-29: `InvestigationService.latestAccepted`, `results`, `latestSummary` for the investigation section (DC-S03-09, DC-S03-10); the `ai_investigation` and `similarity_match` evidence contents of spec 6.11.
3. P-21, P-22: `InvestigationService.summaryForMessage` (DM-E69-R2) joins the caller's transaction.
4. P-23: `KnowledgeService.pastIncidents` (DM-R-52, DM-R-53).
5. P-24: `KnowledgeService.latestSearchResult` (DM-E66-R2) and `pastIncident`; the key check of part 8 against part 14 (`fix_action_key`).
6. P-36: `KnowledgeService.latestSearchResult` for test-mode outputs (DM-R-48).
7. P-34: the citation rate and summary length runs of SB-T-02 on the demo installation; and, by owner decision of 10 Nov 2026 (CTX-D-065), the local-model run of P-20's owner-run step as part of SB-T-04: `gemma4:12b` against the product's SL-T-1 request (spec 6.10) with PM-M17-03's checks, pass when at least 4 of 5 answers are accepted, each within 45 s (AR-G-12, AR-G-32); `spikes/providers/run.sh local-model --package P-20` gives that run.
8. Tests of later packages that import the full demo bundle now make part 8's embedding call: point `endpoint.local_embedding` at `TestEmbedding.shared().address()` (as `SeedTests.demoEntries` and `InstallationIT.demoEntries` do).
