# P-06 — Report: Spikes: Jira and AI providers

| Field | Value |
| --- | --- |
| Package | P-06 Spikes: Jira and AI providers (spec `docs/packages/P-06.md`) |
| Branch | `p-06-spikes-jira-ai` |
| Pin date (BP-R-08) | 3 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1; the environment's setup script failed at container start and was run again by hand, Section 4 item 1) |
| State | Built; the gate command passes in the build session (Section 10). Owner-run steps of spec Section 6.11 done on 10 November 2026 (commit `6adecfa`): all five spikes passed, the GPU host was created and started for the first time, and `deploy/models.lock` records the `gemma4:12b` digest. Results recorded on 10 November 2026 by the session "Record the owner-run results of P-06" (Section 8). |

## 1. What was built

| ID | Part built | Where | Proof |
| --- | --- | --- | --- |
| SB-T-07 | The Jira spike and its checks (spec 6.4): token expiry, `myself`, create with label, label search with the lag rule, ADF description read back, comment, status, resolve and reopen by target status name; result file `P-06-SB-T-07.json`, recordings `dev/fakes/recordings/jira/` | `spikes/providers/jira_spike.py`, `common.py` | `test_jira_spike.py`, `test_recordings.py` |
| SB-T-03 | Runtime part: the similarity spike (spec 6.6): four `/api/embed` calls (50 past texts batched, storm text alone, twice each), `same-vectors`, `best-match` with half-up rounding and ties by key; result `P-06-SB-T-03.json`, recordings `local-embed/`; `run.sh similarity` with `host-address.sh` and the port forward | `spikes/providers/similarity_spike.py`, `run.sh`, `host-address.sh` | `test_similarity_spike.py`, `test_request_data.py`, `test_providers_run.py` |
| SB-T-04 | Local model part: the local model spike (spec 6.7): `model-present`, warm-up (not recorded), five measured SL-T-1 calls, `gpu-fit`; result `P-06-SB-T-04.json`, recordings `local-llm/`; `run.sh local-model` with the port forward; the SL-T-1 and SL-T-2 parts of spec 6.3 | `spikes/providers/local_model_spike.py`, `request_data.py`, `run.sh` | `test_local_model_spike.py`, `test_request_data.py`, `test_providers_run.py` |
| AR-G-20 | The Claude calls: `sl-t-1`, `sl-t-2`, `truncated`, `bad-key`, with the checks of spec 6.5 and the cost of AR-G-14; `valid_output` | `spikes/providers/ai_spike.py`, `request_data.py` | `test_ai_spike.py`, `test_request_data.py`, `test_recordings.py` |
| AR-G-21 | The OpenAI Responses calls, the output text joined from every `output_text` item of the `message` items | `ai_spike.py` | `test_ai_spike.py` |
| AR-G-22 | The OpenAI embeddings call (storm and PI-1001 search texts), vectors by `index`, 1,536 numbers, `prompt_tokens` | `ai_spike.py` | `test_ai_spike.py` |
| AR-G-23 | The local model chat calls (body with the `local-llm` options) | `local_model_spike.py` | `test_local_model_spike.py` |
| AR-G-24 | The local embedding calls (`keep_alive` -1) | `similarity_spike.py` | `test_similarity_spike.py` |
| AR-G-32 | The 45 s check of each measured call (`chat-{k}`), measured with `common.monotonic` | `local_model_spike.py` | `test_local_model_spike.py` |
| AR-K-14 | The Jira calls and the lag rule (first search at once, then up to 3 repeats 5 s apart, CTX-D-060 (6)); transitions found by `to.name` | `jira_spike.py` | `test_jira_spike.py` |
| AR-K-15 | `adf(lines)`: one paragraph per line, a link line as the text before it and the address with a `link` mark | `jira_spike.py` | `test_jira_spike.py` |
| AR-H-07 | `deploy/secrets.sh` reads `expires_on` of the fetched `jira` value with `python3` (nothing of the value printed, no traceback), once per run: prints `jira token expires on {date} ({n} days left)`, adds the renewal line below 30 days, stops with exit 1 before writing on a missing, invalid or past date or a value that is no JSON object | `deploy/secrets.sh` | `deploy/test_secrets.py` |
| AR-S-13 | The secret `jira` with `expires_on` written to `vigil-app`'s folder unchanged (all three keys); the operator's commands to store it (`deploy/edge.md` "Operator secrets") | `deploy/secrets.sh`, `deploy/edge.md` | `deploy/test_secrets.py` |
| AR-G-31 | The warm-up body of `host-gpu.sh` with `"keep_alive":-1` and `"options":{"num_ctx":16384}` (CTX-D-060 (5)); nothing else in the script changed | `deploy/gpu/host-gpu.sh` | `deploy/test_models.py` |
| AR-H-02 | `tests/acceptance/p06_log.sh` for the owner-run steps 4, 5 and 7; the GPU host's creation and first start are owner-run | `tests/acceptance/p06_log.sh` | checked by review (`P-06-gpu.txt`, owner-run); the script: `tests/acceptance/test_p06_log.py` |
| AR-H-11 | The `gemma4:12b` digest in `deploy/models.lock` | owner-run (`deploy/models.sh update gemma4:12b`) | checked by review |
| PM-ASM-03, DM-ASM-07, DM-ASM-09, AR-ASM-08, AR-ASM-11, AR-ASM-10, SL-ASM-04, DD-ASM-04, AR-ASM-12, AR-ASM-06 | The checks that prove them are built (spec part 4); the proofs are the owner-run result files | `spikes/providers/` | checked by review (Section 8) |

Other changes of spec 6.10 (complete): `deploy/values/demo.yaml` (`endpoint.jira: https://techfinity-noc.atlassian.net` after `demo_domain`); `.gitignore` (`spikes/providers/.venv/`); `spikes/providers/requirements.txt` (`PyYAML==6.0.3`); `spikes/providers/host-address.sh`; `tests/acceptance/p06_log.sh`; `deploy/edge.md` section "Operator secrets (BP-OT-05, BP-OT-14)" after "SES production access" (the store block, the names, renewing the Jira token, the Session Manager plugin); `deploy/tests/fake_aws.py` (`ssm start-session` with `AWS-StartPortForwardingSessionToRemoteHost`, docstring updated); `spikes/README.md` row `providers/`; `spikes/providers/README.md`; `spikes/results/README.md` rows for the five result files; the five recording folders with their `README.md` (`dev/fakes/recordings/jira/`, `claude/`, `openai/`, `local-embed/`, `local-llm/`); `README.md` (the `run.sh` command and `tests/acceptance/p06_log.sh` next to `p05_aws_check.sh`) and `CLAUDE.md` (Commands table row).

## 2. Tests added

All pytest; the first word of each docstring is the requirement ID (BP-R-10). The spike tests run in-process against `http.server` fakes on `127.0.0.1` (`spikes/providers/spike_fakes.py`), with the fake AWS CLI of `deploy/tests/bin/aws` and temporary result and recording folders; durations come from a replaced `common.monotonic`.

| File | Tests (IDs) |
| --- | --- |
| `spikes/providers/test_request_data.py` | SB-T-04 SL-T-1 parts (126 member lines in `lab_alerts` order, hypothesis line of `srv-db-01`, 3 impact lines, E1 to E3, the three runbooks with their DD-R-09 texts, rendering); SB-T-04 SL-T-2 parts; SB-T-03 search texts (storm equals PI-1001 only, 51 texts); AR-G-20 `valid_output` accepted object and each of the reasons; AR-G-20 system texts and schema key order |
| `spikes/providers/test_jira_spike.py` | SB-T-07 the run against a fake Jira (calls, bodies, headers, label, result file, recordings); AR-K-15 ADF with and without a link and an empty line; AR-K-14 transitions found by target name; AR-K-14 found at the fourth search (`seconds_after_create` 1.6); AR-K-14 not found after four (null); SB-T-07 token expiry today passes, yesterday fails; SB-T-07 seven invalid `jira` values stop with "not valid" and change no file; SB-T-07 missing secret and usage line |
| `spikes/providers/test_ai_spike.py` | AR-G-20 Claude run (bodies, headers, checks, `truncated`, `bad-key`, `calls` with cost); AR-G-20 a refusal (SL-T-2 not run, `bad-key` with the truncated body); AR-G-20 cost half-up (0.0000005 → 0.000001); AR-G-21 OpenAI run (output text from two `output_text` items, `reasoning_units`); AR-G-21 `status` not completed names `incomplete_details.reason`; AR-G-22 embeddings body, vectors by index, cost; AR-G-22 wrong vector length and missing `prompt_tokens`; AR-G-20 usage line, missing and invalid key |
| `spikes/providers/test_similarity_spike.py` | SB-T-03 run (same vectors, PI-1001 1.00, result and recordings); AR-G-24 bodies of the four calls; SB-T-03 a changed number in `past-2` or `storm-2` fails `same-vectors`; SB-T-03 a tie goes to the first key, another best key fails; SB-T-03 half-up rounding (0.995 → 1.00); AR-G-24 a failed `past-1` leaves `same-vectors` and `best-match` "not run"; AR-G-24 usage |
| `spikes/providers/test_local_model_spike.py` | SB-T-04 run (warm-up first and not recorded, five measured calls, `gpu-fit`, result keys); AR-G-23 the chat body; AR-G-32 a call of 45.5 s by the fake clock fails `chat-3`; SB-T-04 `done_reason` `length` and `size_vram` below `size` fail; SB-T-04 model missing stops after `model-present`; AR-G-23 usage |
| `spikes/providers/test_recordings.py` | SB-T-07 no site, email (any case), account ID (literal and percent-encoded in `self`) or token in any Jira recording or the result; the four personal keys redacted; SB-T-07 an answer holding the token stops the spike with nothing written; AR-G-20 a Claude answer holding the key stops at `truncated`, earlier recordings stay, no result; AR-G-20 a connection failure is recorded with `response` null |
| `spikes/providers/test_providers_run.py` | SB-T-03 usage line and exit 2, no identity; SB-T-04 no `python3.12` (a `PATH` of links to `bash`, `dirname`, `python3` and the `aws` fake only); SB-T-04 no plugin, GPU host stopped; SB-T-04 `local-model` through the fake port forward to a fake Ollama, the forward's process group ended and its port closed after `run.sh` ends; SB-T-03 `similarity` through `host-address.sh` and the forward; SB-T-03 stopped `vigil-embed` printed before exit 1; SB-T-03 `host-address.sh` prints the address or its message |
| `deploy/test_secrets.py` | AR-H-07 valid date, expiry line once, secret unchanged; AR-S-13 below 30 days adds the renewal line, file unchanged with all three keys, 0 days left; AR-H-07 eight refused values (past date, missing, non-string, invalid calendar date, other formats, JSON array, not JSON): exit 1, no file, no traceback, nothing of the value printed |
| `deploy/test_models.py` | AR-G-31 the warm-up body holds `keep_alive` -1 and `options.num_ctx` 16384 (new test); the existing `test_gpu_start` expects the new body |
| `tests/acceptance/test_p06_log.py` | AR-H-02 output and `exit {code}` appended to `VIGIL_P06_LOG`, the command's code returned; AR-H-02 usage line |

## 3. Deviations from the spec

None in behaviour. Points the spec leaves open and how they were built:

1. Test support module `spikes/providers/spike_fakes.py` (the HTTP fake server, the fake clock, the prepared environment): spec 6.1 lists the folder's files as the README, `run.sh`, the modules of 6.2 to 6.8 and their tests; this module is test support, like `deploy/conftest.py`. It is not named `conftest.py` because `deploy/conftest.py` is imported by name (`from conftest import …`) in the same pytest run.
2. `deploy/tests/fake_aws.py` records the fake's process ID as `pid` in the `start-session` call, so the `run.sh` tests can check that the process group ended (spec 7 "the process group ended at exit").
3. Texts the spec does not give: the details of failed checks (Example: `status 500`, `stop_reason is refusal`, `NOC-1 not found in 4 searches`, `45500 ms, more than 45000 ms (AR-G-32)`); the stop message when `endpoint.jira` or `demo_domain` is missing in `deploy/values/demo.yaml` ("endpoint.jira and demo_domain are needed in deploy/values/demo.yaml."). For `model-present`, a connection failure gives the error text followed by the spec's detail.
4. Where an answer is missing or partial: `gpu-fit` compares the `prompt_eval_count`s that exist (at least one is needed); `scores` of `P-06-SB-T-03.json` is `[]` when `best-match` is not run; `search` is `{attempts: 0, seconds_after_create: null}` when `create` failed; `seconds_after_create` is a JSON number rounded half-up to one decimal.
5. Recording and result files are written as indented JSON (`indent=2`, `ensure_ascii=False`) with a final newline.
6. `.gitleaksignore` (not in spec 6.10's list): gitleaks 8.30.1 reports the fixed invalid key of the `bad-key` calls (spec 6.5) as `generic-api-key` in `spikes/providers/ai_spike.py`, `spikes/providers/test_ai_spike.py` and in the spec itself (`docs/packages/P-06.md` line 171, the owner's commit `3826413`), so the `ci.yml` gitleaks step would fail on this branch. The three fingerprints are added with a comment, as P-05 did for its false positive (BP layout row `.gitleaksignore`, CTX-D-042). The value is the spec's text, not a key.
7. Review fixes (10 Nov 2026), owner decision, complete list: (1) `deploy/edge.md` "Operator secrets" block: wrapped in `{ … }` so the shell reads all of it before the first `read` runs, which makes it work when pasted into zsh (the macOS default shell) and into macOS bash 3.2; no end-of-line comments (the text above the block says to set `NAME` to `claude`, `openai` or `jira`); each prompt is `printf '%s' "{prompt}: "` followed by `read -rs VAR` and `echo` (no `read -p`, which means "coprocess" in zsh); when any value read is empty, the block prints "Empty value; nothing stored.", removes the folder, unsets the variables and skips `put-parameter`. Checked here by pasting the block and values on standard input into zsh 5 and bash 5, against the fake AWS CLI. (2) `deploy/edge.md` "Operator secrets" and the prerequisites of `spikes/providers/README.md`: the Claude key MUST be created inside a workspace (an organisation-level key is refused with "This API key is not scoped to a workspace, so this request must include the anthropic-workspace-id header"); the Jira token is made with "Create API token", not "Create API token with scopes" (a scoped token fails basic authentication against the site address), with the longest expiry Atlassian allows; `python3.12` is Homebrew's, or python.org's after "Install Certificates.command" was run. (3) `spikes/providers/common.py` `call_http`: when the base address's host is `127.0.0.1`, the request goes through an opener built with `urllib.request.ProxyHandler({})`, as `run.sh`'s readiness probe does; test SB-T-04 `test_no_proxy_for_port_forward` (fails without the change). (4) `spikes/providers/run.sh`: the virtual environment is removed, created and installed again also when `"$PY" -c 'import yaml'` fails; the readiness wait stops at once with the log and "The port forward to {unit} did not open." when `kill -0` finds that the forward process has ended. Tests: SB-T-03 `test_venv_rebuilt`, SB-T-03 `test_venv_install_fails`, SB-T-04 `test_forward_ended` (the first and last fail without the change). (5) `deploy/tests/fake_aws.py` `ssm start-session`: also starts a child process in the same process group (standing in for `session-manager-plugin`) and records it as `child_pid`; `test_providers_run.py` checks that the child has ended too after `run.sh` ends.

## 4. Document feedback (CTX-Q-8)

1. The environment's setup script (`tools/cloud-setup.sh`, BP-R-01) failed when this session's container was built: `curl` got HTTP 503 from a GitHub release download (the Temurin asset), so the session started with OpenJDK 21 and without `/etc/vigil-cloud-setup.version`. The GitHub release hosts answered 502 and 503 through the session's proxy for about 15 minutes; the script, run again by hand every 2 minutes, succeeded on the eighth attempt (`/etc/vigil-cloud-setup.seconds` 97). The script has no retries, so a short GitHub outage fails the environment. Proposal for the package that next changes `tools/cloud-setup.sh`: `curl --retry 5 --retry-all-errors` on its downloads (a script change needs a new setup version, BP-R-31).
2. BP-E-01 says the cloud environment has "Python 3" and `CLAUDE.md` names Python 3.12 for `demo/`, `dev/`, `spikes/`, `tests/acceptance/` and `deploy/`; in this session `python3` is 3.13.18 (Ubuntu's alternatives point it at `python3.13`; `python3.12` is also installed), so `/opt/vigil-tools` runs Python 3.13. The P-06 code uses nothing newer than Python 3.10 (`zip(strict=True)`), so it runs on the owner's `python3.12` as spec 6.9 requires; no change is needed for P-06.
3. A spec text shaped like `key: value` with a key-like word (here the invalid key of spec 6.5) is a gitleaks `generic-api-key` finding in the spec commit itself, which `ci.yml` scans; the fix needs a `.gitleaksignore` line (Section 3 item 6). Specs that give such fixed test values (Example: P-07) can expect the same.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 3 November 2026)

| Dependency | Version | Where | Note |
| --- | --- | --- | --- |
| PyYAML | 6.0.3 | `spikes/providers/requirements.txt` | The latest release on 3 November 2026 (released 25 September 2025); the same pin as `tools/requirements-dev.txt`. |

## 7. Values recorded under `docs/`

None (spec Section 9). The measurements go into Section 8; `deploy/models.lock` gets the `gemma4:12b` digest in the owner-run step 5.

## 8. Owner-run results

Spec Section 6.11, run by the owner on 10 November 2026 from the Mac. The result files were committed to the branch in commit `6adecfa`: the five files `spikes/results/P-06-*.json`, `tests/acceptance/results/P-06-gpu.txt`, the recordings under `dev/fakes/recordings/` (`jira/` 12 files, `claude/` 4, `openai/` 5, `local-embed/` 4, `local-llm/` 5), and `deploy/models.lock`. Every spike passed; no owner decision item is needed.

### 8.1 Steps

| Step | Result |
| --- | --- |
| 1 | Prerequisites done (the spikes read the secrets `jira`, `claude` and `openai`; the port forwards ran). |
| 2 | `run.sh jira`: passed, run `20261110T044230Z` (Section 8.2). |
| 3 | `run.sh claude`: passed, run `20261110T044300Z`. `run.sh openai`: passed, run `20261110T044321Z` (Section 8.3). |
| 4 | `deploy/edge.sh demo`: the first run exited 3 with "The GPU host was not created: InsufficientInstanceCapacity. …" and `gpu host: not created`. Two runs then exited 1 with "The GPU host does not exist yet (deploy/edge.sh, BP-OT-09)." (the GPU host scripts before it existed). The next `deploy/edge.sh demo` exited 0 with `instance vigil-demo-gpu: created` and `gpu host: i-076737f05bf4a7c7a stopped`. All runs are in `P-06-gpu.txt` (AR-ASM-06). |
| 5 | `deploy/gpu.sh start`: two runs exited 1 with "AWS has no g6.xlarge capacity now; try again in a few minutes (AR-ASM-06)." The next two runs started the host and `vigil-llm` and exited 1 with "No digest recorded for gemma4:12b: …". `deploy/models.sh update gemma4:12b` exited 0 (`gemma4:12b: unrecorded -> 6114515d…6436ed0b`). `deploy/gpu.sh start` then exited 0 with `vigil-llm ready after 154 s`: two warm-up attempts ended at curl's 60 s limit while the model loaded, and the third answered. |
| 6 | `run.sh local-model`: passed, run `20261110T062103Z` (Section 8.4). |
| 7 | `deploy/gpu.sh status` exited 0: `gpu host: i-076737f05bf4a7c7a running`, `vigil-llm` running (`ollama/ollama:0.40.5@sha256:5d3a2f25…`, `10.40.1.20:11434->11434/tcp`), `gemma4:12b 6114515d… matches models.lock`, `NVIDIA L4, 9392 MiB, 23034 MiB`. `deploy/gpu.sh stop` exited 0. |
| 8 | `run.sh similarity`: passed, run `20261110T045117Z` (Section 8.5). By the run times it ran before step 6, not after step 7 as the spec orders. The two steps use different hosts and share nothing, so the order does not affect either result. |
| 9 | Stopping the main host is the owner's step and is not in the result files. |
| 10 | Committed in `6adecfa`. In `deploy/models.lock` only the `gemma4:12b` line changed (`unrecorded` → `6114515d63c17436a7c0417d82820ac65ad643e2806c5a3c89cb62846436ed0b`), as `deploy/models.sh update` wrote it (AR-H-11). |

Secret check (review list item 1): no file under `dev/fakes/recordings/`, `spikes/results/` or in `P-06-gpu.txt` contains `sk-`, `ATATT`, `@techfinity`, `techfinity.ae` or `atlassian.net`. gitleaks 8.30.1 finds no leaks in each of the three folders, nor in the branch's commits (`origin/main..HEAD`, as `ci.yml`). The Jira recordings hold `${endpoint.jira}`, `<email>`, `<account-id>` and `"<redacted>"` in place of the site, the email, the account ID and the four personal keys.

### 8.2 SB-T-07 Jira (`P-06-SB-T-07.json`)

| Item | Value |
| --- | --- |
| Checks | `token-expiry`, `myself`, `create`, `search`, `read-description`, `comment`, `status-before`, `resolve`, `reopen`: all passed |
| Token | `token_expires_on` 2027-11-03, `token_days_left` 358 |
| Issue | `NOC-1` |
| Search (AR-ASM-08) | `attempts` 1, `seconds_after_create` 2.4: the first search, made right after the create answer, found the issue |
| Proves | PM-ASM-03 (`resolve` passed), DM-ASM-07 (`reopen` passed), DM-ASM-09 (`create` and `search` passed), AR-ASM-08 (`search` passed within the 4 searches of CTX-D-060 (6)), AR-ASM-11 (`read-description` passed: the ADF description read back with its three lines and the link mark) |

### 8.3 Claude and OpenAI (`P-06-claude.json`, `P-06-openai.json`)

All 10 Claude checks and all 13 OpenAI checks passed (AR-G-20 to AR-G-22; AR-ASM-10: `sl-t-1-usage` and `embed-usage` passed).

| Call | Status | Duration | Input / output units | Reasoning units | Cost (USD) | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| Claude `sl-t-1` | 200 | 5,594 ms | 8,969 / 472 | — | 0.022658 | cites RB-DB-01: yes |
| Claude `sl-t-2` | 200 | 3,366 ms | 1,082 / 243 | — | 0.004594 | summary length 613 characters |
| Claude `truncated` | 200 | 2,086 ms | 8,969 / 16 | — | 0.018098 | `stop_reason` `max_tokens` |
| Claude `bad-key` | 401 | 390 ms | — | — | — | |
| OpenAI `sl-t-1` | 200 | 10,375 ms | 5,192 / 270 | 64 | 0.013084 | cites RB-DB-01: yes |
| OpenAI `sl-t-2` | 200 | 4,349 ms | 476 / 146 | 0 | 0.002412 | summary length 528 characters |
| OpenAI `truncated` | 200 | 4,485 ms | 5,192 / 16 | 16 | 0.010544 | `status` `incomplete` |
| OpenAI `embed` | 200 | 1,649 ms | 116 / 0 | — | 0.000002 | two vectors of 1,536 numbers |
| OpenAI `bad-key` | 401 | 837 ms | — | — | — | |

Costs of the runs: Claude 0.045350 USD, OpenAI 0.026042 USD. Both SL-T-1 answers cite RB-DB-01. Claude's SL-T-2 summary has 613 characters, more than the 600 its system text asks for. Spec 6.3 records this length and does not check it (P-20, SB-T-02); see Section 11 item 4.

### 8.4 SB-T-04 local model part (`P-06-SB-T-04.json`; AR-G-32, AR-ASM-12)

| Item | Value |
| --- | --- |
| Checks | `model-present`, `warm-up`, `chat-1` to `chat-5`, `gpu-fit`: all passed |
| Durations (`durations_ms`) | 15,847; 16,658; 16,363; 16,315; 16,465 ms. The maximum, 16,658 ms, is within the 45 s of AR-G-32. The times include the port forward from the Mac, well under one second. |
| `prompt_eval_counts` / `eval_counts` | 5,704 for each call / 951, 982, 953, 951, 951 |
| Ollama durations | `load_duration` 0.94 to 1.14 ms (the model stayed loaded from the warm-up); `prompt_eval_duration` 3.68 to 3.78 s; `eval_duration` 11.61 to 12.04 s |
| `ps` | `gemma4:12b`, `size` 1,471,330,384, `size_vram` 1,471,330,384, `context_length` 16384 (whole model on the GPU, `gpu-fit` passed) |
| RB-DB-01 cited | in all five answers |
| Proves | AR-G-32 (measurement), AR-ASM-12 (`gemma4:12b` part: `gpu-fit` passed and `deploy/models.lock` records the digest) |

Observation (no check depends on it): Ollama's `/api/ps` reports `size` 1,471,330,384 bytes (about 1.4 GiB) for `gemma4:12b`, while `nvidia-smi` in step 7 shows 9,392 MiB used on the L4. `gpu-fit` compares `size_vram` with `size`, and they are equal, so the check holds. The absolute `size` value looks too small for a 12B model; it is recorded as Ollama 0.40.5 reported it.

### 8.5 SB-T-03 runtime part (`P-06-SB-T-03.json`; SL-ASM-04, DD-ASM-04)

| Item | Value |
| --- | --- |
| Checks | `past-1`, `storm-1`, `past-2`, `storm-2`, `same-vectors`, `best-match`: all passed |
| Dimensions | 768 |
| Calls | `past-1` 1,598 prompt units, 24,075 ms; `storm-1` 74, 426 ms; `past-2` 1,598, 7,097 ms; `storm-2` 74, 1,617 ms |
| Scores (five highest) | PI-1001 1.00, PI-1011 0.98, PI-1012 0.96, PI-1021 0.92, PI-1039 0.92 |
| Proves | SL-ASM-04 runtime part (`same-vectors`: equal vectors in both calls, number for number), DD-ASM-04 (`best-match`: PI-1001 with 1.00) |

### 8.6 GPU host (AR-H-02, AR-H-11, AR-ASM-06, BP-ASM-12)

`P-06-gpu.txt` shows the following. `deploy/edge.sh demo` exited 0 with `instance vigil-demo-gpu: created` after one capacity refusal (exit 3). `deploy/gpu.sh start` was refused twice for capacity (AR-ASM-06), stopped twice at the missing digest, and after `deploy/models.sh update gemma4:12b` printed `vigil-llm ready after 154 s`. `deploy/gpu.sh status` shows the NVIDIA L4. The host's bootstrap allowed `host-gpu.sh` to pass its Docker version check (28.0 or later) and run Compose, so BP-ASM-12's GPU host part holds. The digest is `6114515d63c17436a7c0417d82820ac65ad643e2806c5a3c89cb62846436ed0b`.

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD`: 54 (production code under `deploy/`: `secrets.sh` and the warm-up line of `host-gpu.sh`; `spikes/`, `tests/` and `deploy/tests/` are not counted, BP-R-05). Size class M (spec estimate about 60 lines).

## 10. Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, Docker 29.9.2): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1 found setup version 1 (after the setup script was run again by hand, Section 4 item 1). Step 1a built the local images. Step 2: `vigil-app` 140 unit tests and 479 `*IT`, `demo-control` 1 and 2, all passing (no Java or web file changed); the web build, lint, type check and Vitest pass. Step 3: `ruff check` and `ruff format --check` clean; pytest 189 passed, 1 skipped (the bcrypt test of `deploy/test_secrets.py`, as before), with the lab tests, because the branch changes files under `deploy/`, `dev/` and `spikes/` (CTX-D-036). Step 4: `shellcheck` on every `*.sh`. Step 5: 49 data checks, 0 failed. Step 6: compose validation. Step 7: `tools/trace.py --check` (every part 4 row with proof `test` has a test that names it).

The gate ran on the working tree of the final commit: the last code changes (`run.sh` taking its temporary folder from `temp_folder` of `deploy/lib/aws.sh`, and `.gitleaksignore`) were in the tree before step 3 started. `gitleaks git --log-opts="origin/main..HEAD"` (8.30.1, as `ci.yml`) finds no leaks with the fingerprints of Section 3 item 6.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (Section 1; every row with proof `test` has its test, the rows checked by review wait for the owner-run files); BP-DC-02 (above); BP-DC-03 (nothing beyond spec 6: no product code, no API operation, no setting); BP-DC-04 (no product code changed; AR-Q-01 to AR-Q-09 pass in step 2); BP-DC-05 (no migration, OpenAPI operation or seed import part); BP-DC-06 (`README.md` and `CLAUDE.md` list `spikes/providers/run.sh`, `README.md` also `tests/acceptance/p06_log.sh`); BP-DC-07 (this report; the owner-run result files follow, Section 8). P-06 done-check items 2 to 4 wait for the owner-run steps and the `gate` job after the owner's commit.

## 11. Follow-up items for later packages

1. P-19 and P-21: the connector tests use the recordings the owner commits under `dev/fakes/recordings/claude/`, `openai/`, `local-embed/`, `local-llm/` and `jira/` (their `README.md` files describe the format; the bodies hold the placeholders `${endpoint.*}`, `<email>`, `<account-id>` and `"<redacted>"`).
2. The package that next changes `tools/cloud-setup.sh`: retries on its downloads (Section 4 item 1).
3. The Jira issues the spike creates stay in the project (spec 5: nothing in Vigil deletes); each carries the label `vigil-spike-{run_id}`.
4. P-20 (prompt texts, AR-G-12) and SB-T-02: in this run Claude's SL-T-2 summary had 613 characters, more than the 600 the system text asks for (Section 8.3). OpenAI's had 528. SB-T-02 measures this in the product.
