# P-27 — Report: Headless storm acceptance

| Field | Value |
| --- | --- |
| Package | P-27 Headless storm acceptance (spec `docs/packages/P-27.md`) |
| Branch | `p-27-headless-storm-acceptance` |
| Pin date (BP-R-08) | 12 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25) |
| State | Built; in this session `deploy/local.sh up`, the `installation` tests, two driver runs in mode `immediate` (every check passed, AR-Q-07 passed), the smoke suite and `down --volumes` pass (Section 8), and the gate command passes (Section 10). Owner-run Mac step done on 13 Nov 2026: the driver in mode `script` with the `demo` profile passed every check, 37 of 37, and AR-Q-07 passed (Section 8). Open: the label `e2e` on the pull request (`e2e.yml` has run only as skipped). |

## 1. What was built

No product code is changed (`app/src/main`, `web/src` and `docs/31-demo-data/` untouched).

| Part | Requirement IDs |
| --- | --- |
| `tests/acceptance/storm/storm.py` (the run of spec Section 6.2: steps 1 to 9, the wait loops with their limits and end entries, the script clock of steps 6 and 7, the result file of Section 6.4, the printed lines, exit codes 0, 1 and 2); `storm_api.py` (the HTTP client without the environment's proxy, the cookie store and `X-Vigil-CSRF` per user, the installation operations with the bearer token, `demo-faults`, Zabbix `problem.get` with tag `vigil=lab`, Alertmanager `/api/v2/alerts` filtered to the five catalogue names, the reset `Reset` with branches A and B in two threads, their 180 s and 300 s limits, the 12 s settle, AR-I-98 last, the Jira status read with the basic header of the `jira` secret); `storm_checks.py` (the 37 checks of Section 6.3 in the table's order, the Teams card and Mailpit parsing, the ADF paragraphs, DM-R-53 with `Decimal` and integer milliseconds rounded half-up as `Metrics.mean`, the development values reader, the checklist). | SB-T-02, SL-A3-04, AR-B-03 |
| `tests/acceptance/storm/Dockerfile` (the `ubuntu:24.04` pin of `dev/fakes/Dockerfile`, the same `RUN` with `ca_bundle` and `UBUNTU_MIRROR`, `python3 ca-certificates tzdata`, `COPY *.py /opt/storm/`, `TZ=UTC`, the entrypoint), `.dockerignore` (`test_*.py`), `README.md`. | BP-R-04 |
| `tests/acceptance/storm/run.sh` (spec Section 6.6: usage, `Run deploy/local.sh up first.`, the image check, the exports, `VIGIL_STORM_RESULTS`, the compose run in the profile `acceptance`, the whole logs of `vigil-app` and `vigil-migrate` into `{base}/{project}/acceptance/vigil-{UTC time}.log` (folder 0700, file 0600), the scan, the AR-Q-07 lines, the exit code). | AR-Q-07, SB-T-02 |
| `tests/acceptance/log_scan.py --ignore-fields` (the string values of the named keys inside a secret file's JSON object are left out; the file's whole content is always scanned). | AR-Q-07 |
| `deploy/local/compose.yaml`: the service `storm-driver` of Section 6.5. | SB-T-02 |
| `deploy/local.sh`: `down` and `logs` add `--profile acceptance`; full `up` writes `state/lab-profile`. `deploy/images.sh --local` builds `vigil/storm-driver:local` (four images). | BP-R-04, BP-R-28 |
| `.github/workflows/e2e.yml`: the step `storm driver` (`tests/acceptance/storm/run.sh --mode immediate`, `VIGIL_STORM_RESULTS: ${{ runner.temp }}/storm`) between `local installation` and `playwright suites`; the failure step copies the results folder. | BP-W-02 |
| `deploy/new-installation.sh`: `has` reads its list from a here-string instead of a pipe (the cause of the flaky `test_first_and_second_run`, Section 2 last row and Section 3 item 9). | — |
| `README.md` "Local installation", `CLAUDE.md` "Commands" (the row "Storm driver (from P-27)"). | BP-DC-06 |

## 2. Tests added

| Test | IDs |
| --- | --- |
| `tests/acceptance/storm/test_storm.py` (62 cases, no Docker, network only on `127.0.0.1`): an in-process server plays `vigil-api`, `demo-faults`, Zabbix, Alertmanager, the Teams and Jira fakes and Mailpit with answers in the OpenAPI shapes and the connectors' payloads; the driver runs against it with an injected clock. Full runs: "SB-T-02 mode immediate …" (every check passes, the result file has exactly the keys of Section 6.4, exit 0, no secret value in the result or the output), a failing value (exit 1), a wait limit ("step 5 Incidents finished within T0 + 360 s"), deliveries `checklist` (no fake read, the F checks under `not_run`, the checklist file), the script clock (acknowledgement at T0 + 390 s, approval at T0 + 480 s, the S and SD checks made), the two script aborts, the CSRF header and the three logins, the usage line. Each of the 36 checks with a passing and a failing answer (parametrised), "SL-A3-04 the SB-R-06 result passes only when its eight checks pass", missing data after a failing step; the Teams card and Mailpit links; the ADF paragraphs; DM-R-53 on the CSV alone (5.7, 41.1, 525, 46) and with two live incidents (5.7, 40.5, 650, 47 for 6.0 and 9.8 minutes), half-up rounding; the development values from `10-lab-and-faults.yaml` and the reader's rules; "AR-B-03 …" (branches at once with the alias last, the 12 s wait with a per-thread fake clock, a failed import and a branch limit each without AR-I-98, a failed reset in a run); the checklist lines; "BP-R-04 the storm driver image is based on ubuntu:24.04 and the program imports only the standard library". | SB-T-02, SL-A3-04, AR-B-03, BP-R-04 |
| `tests/acceptance/storm/test_run_script.py` (12 cases, a fake `docker` on `PATH`): the usage line for six refused argument lists; `Run deploy/local.sh up first.`; the image message; the compose call with the profile `acceptance`, the mode, the deliveries and the exported variables, the logs call, folder 0700 and file 0600, a clean log; defaults and a relative results folder; a secret of the project's secrets folder in the log (the file name printed, never the value, `AR-Q-07 failed`, exit 1, while `vigil-diag` of a lab key's `username` in `vigil-diagnostics` is not reported); a failed driver run (exit 1, the log still scanned). | AR-Q-07 |
| `tests/acceptance/test_log_scan.py`: "AR-Q-07 --ignore-fields leaves out only the named JSON keys' values". | AR-Q-07 |
| `deploy/test_local_script.py`: `down` (with and without `--volumes`, another project) and `logs` with `--profile acceptance`. | BP-R-28 |
| `deploy/local/test_installation.py` (marker `installation`): "BP-R-28 up writes the lab profile it used to state/lab-profile" (also compared with `LAB_PROFILE` of `lab-db-01`). | BP-R-28 |
| `deploy/test_images.py`: `--local` builds the four images, each from its context. | BP-R-04 |
| `deploy/test_new_installation.py`: "AR-S-13 has() of new-installation.sh finds an existing name also when grep stops reading at its match" (the cause of the flaky `test_first_and_second_run`, Section 8). | AR-S-13 |
| The flaky-test recipe of spec Section 6.8 (Section 8). | — |

## 3. Deviations from the spec

None changes a behaviour the spec states. Readings and details the spec left open:

1. **End entries of a failing step.** A wait that runs out gives "step {k} {name} finished within {limit}" as the spec writes it; a branch of the reset at its limit gives "step 1 Reset finished within 180 s" (or 300 s). A step that fails with a message (an error answer of `demo-faults` or an installation operation, a failed import, a login other than 200, AR-I-33 or AR-I-35 other than 201, a read outside a wait other than 200, a connection error outside a wait) adds the entry `SB-T-02` "step {k} {name} finished", not passed, with the message as its detail. A read that fails during step 9 adds "step 9 Checks finished", not passed, so a finished run cannot pass with a check left out.
2. **Checks after a failing step.** A check whose data was not read (the run ended before) is not made and not listed; `not_run` lists only the checks left out by mode, profile or deliveries, as Section 6.4 says. `SB-R-06 repeatability result` is always made and fails when one of its eight checks is missing.
3. **Mode `script`, step 6.** The wait ends at T0 + 405 s instead of T0 + 420 s: a link found after T0 + 405 s ends the run as a script that cannot be followed anyway, so the driver does not wait the last 15 s. Reaching the limit of step 7 in mode `script` (T0 + 510 s) is the script abort "Approval link by 09:30".
4. **Which incident a message belongs to.** A message belongs to the incident (or approval request) whose identifier ends the path of its Acknowledge (or Approve) link, `/m/acknowledge/{id}` or `/m/approve/{id}`; the check then compares the whole link with the link the driver built, so a wrong link base fails `SB-S05 incident messages` with its detail instead of running out a wait. "Exactly one incident message" counts every message to the recipient that carries the incident's Acknowledge link.
5. **Step 6's Acknowledge line** is the line `Acknowledge: [L](L)` of a card on `/teams-team-noc-l1` whose `L` ends with the storm's identifier; step 7's likewise on `/teams-user-dbops-engineer`.
6. **The approval page's expiry.** "Later than the read" uses the driver's clock just before AR-I-34 (the driver and Vigil run on one host).
7. **`incidents.*.number`** in the result file is the integer of AR-I-22; messages and checklist lines show it as `INC-` and four digits.
8. **`--ignore-fields`** leaves out the value of a named key at any depth of the secret file's JSON object, only when that value is a string.
9. **`deploy/new-installation.sh`** (outside the spec's file list, allowed by Section 6.8 "fix the cause … in the script"): see Section 8.
10. **Step 3** prints a line in both modes ("mode immediate follows no script clock"), so every step has its line.

## 4. Document feedback (CTX-Q-8)

1. **Incident numbers of the storm and the report.** In both cloud runs the report incident was `INC-0001` and the storm incident `INC-0002` (the report's single alert opens its incident before the storm's settle period ends). No document gives the order and no check depends on it; the checklist and the messages use the numbers read. Noted for P-29/P-33, where a screenshot or a narrator line might assume the storm is `INC-0001`.
2. **The same pipe pattern elsewhere in `deploy/`.** `printf '%s\n' "$list" | grep -qx "$name"` under `set -o pipefail` (the cause of the flaky test, Section 8) also appears in `deploy/edge.sh` (lines 72, 124, 175, 700), `deploy/main/host-deploy.sh` (54, 123), `deploy/main/host-status.sh` (41, 72) and `deploy/gpu/host-gpu-status.sh` (19). With short lists it fails only when `grep` exits before the last write; not changed here (outside P-27's scope); a later package that changes those scripts may use a here-string.

3. **The 16-minute wait of spec Section 6.9 can be a check.** The wait exists only so that no file under `/data` has a change time within the 15-minute `disk_growth` period (`recent_growth` uses `find … -cmin -15`). `lab-db-01` writes its baseline files only when its disk image is new, so after `up` with a kept `lab-db-01-data` volume no wait is needed. The owner used `docker exec lab-db-01 find /data -xdev -type f -cmin -15 | wc -l` (`0`: ready) instead of waiting. P-32 and P-34 (SB-T-02 runs in mode `script`) may state this check in place of the fixed wait; the driver itself could also refuse a `script` run with the `demo` profile while the count is not 0. No document text changed here.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 12 November 2026)

None. The driver image reuses the `ubuntu:24.04@sha256:534baea6…` pin of `dev/fakes/Dockerfile`; the driver uses Python's standard library only.

## 7. Values recorded under `docs/`

The files of spec Section 9, committed by the owner (commit `b8ba6e3`): `tests/acceptance/results/P-27-mac.txt` and `tests/acceptance/results/storm-script-20261113T054808Z.json`. No other file under `docs/` or `docs/31-demo-data/` is changed: the Mac run reproduced the DD-F-10 development values, so nothing needs recording there (P-32 records the final values).

## 8. Owner-run results

**Mac run (spec Section 6.9), 13 Nov 2026, branch at `4c73aeb`, profile `demo`** (`tests/acceptance/results/P-27-mac.txt`, result file `storm-script-20261113T054808Z.json`):

| Step | Result |
| --- | --- |
| `deploy/local.sh up --profile demo` | `Tenant 01a1d364-1c70-76e0-8c67-a1672265a766 is ready at demo.vigil.test.`; the four local images built, `vigil/storm-driver:local` included |
| Wait for the `disk_growth` period | Instead of a fixed 16 minutes, the owner checked `docker exec lab-db-01 find /data -xdev -type f -cmin -15 \| wc -l`, which gave `0` (the lab volume was kept from earlier runs, so its baseline files were already older than 15 minutes); this is what the wait ensures (Section 4 item 3) |
| `tests/acceptance/storm/run.sh --mode script` | `finished` true, `passed` true; **37 of 37 checks passed**, `not_run` empty (the S and SD checks all made); `AR-Q-07 passed: no secret of the installation in the log of this run.` |
| `deploy/local.sh down` | every container removed |

Values of the script run:

| Item | Value |
| --- | --- |
| Reset | branch A 12.099 s (`quiet_seconds` 0.032), branch B 1.053 s, total 12.113 s; tenant `01a1d372-f2c4-74c9-8ecd-817fc2ab6903` |
| T0 | 2026-11-13T05:48:20.524Z |
| Times after T0 (recorded, not checked) | SB-TL-01 30.5 s (latest storm `fired_at`); SB-TL-02 71.0 s (stable grouping); SB-TL-04 72.3 s (first storm incident message); SB-TL-06 390.1 s (acknowledgement; the script's 07:30); SB-TL-07 390.2 s (first approval request message); SB-TL-08 480.7 s (action finished; approval sent at T0 + 480 s, 09:00); SB-TL-10 575.4 s (resolved, 10:35 on the script clock, before SB-TL-10's 11:50) |
| Incidents | storm `INC-0002` (`NOC-2`), report `INC-0001` (`NOC-1`), as in the cloud runs (Section 4 item 1) |
| `SB-S01 storm within 120 s` | latest `fired_at` T0 + 30.5 s, none after T0 + 120 s |
| `SB-S03 diagnostic values` (exact, `demo`) | the three summaries equal `development_values`: "/data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)", "Largest folder under /data: /data/txlog_archive (8.5 GiB)", "8.5 GiB written to 34 files under /data in the last 15 minutes" |
| `SB-S06 action values` (exact, `demo`) | 34 files, 8.5 GiB, precondition value 100 (expected 34, `8.5`, 100) |
| `SB-S09 dashboard values` (exact) | 5.7, 40.5, 650, 47; DM-R-53 over the run's own times gives the same values |
| `SB-S04 investigation` | `runbooks_cited` [RB-DB-01]; `likely_cause` "Subscriber DB server (srv-db-01) is the likely cause: Disk usage of /data: /data is 100% full (15.9 of 15.9 GiB used, 0.1 GiB free)." (the dev fake's wording, CTX-D-083 (5)) |

Assumptions of spec Section 11 checked by this run (complete list): the SB-S09 exact values hold with the script clock (acknowledgement at T0 + 390 s, approval at T0 + 480 s: 5.7, 40.5, 650, 47); the DD-F-10 development values of the P-04 Mac run are reproduced by the same lab image on the same Mac (`SB-S03 diagnostic values` and `SB-S06 action values` passed, so there is no document feedback on them). The similarity assumption (score `1.00` for PI-1001) held in the cloud runs and on the Mac. BP-ASM-04's storm part is still open: it is checked when `e2e.yml` runs on this pull request.

Notes on the Mac record (complete list):
1. The owner ran the three commands without `tee` and copied the terminal output into `P-27-mac.txt` (its header says so); Docker's build output and compose progress lines are shortened there.
2. The three `[ERROR] include: **/*.{test,spec}.?(c|m)[jt]s?(x)` lines at the start of `up` also appear in this session's `up` log. They come from the web build inside `./mvnw -B -q -pl app -DskipTests package` (Vitest finds no test files under `web/src` yet). The build exits 0 and they predate P-27 (P-28 brings the web tests).

**Still open (spec Section 6.9): the label `e2e` on [awadoodatevam/vigil#31](https://github.com/awadoodatevam/vigil/pull/31).** `e2e.yml` ran twice on this pull request (runs 43299165912 and 43376607752), both `skipped` because the label is missing.

Cloud session run (spec Section 8 item 3), profile `test`:

| Step | Result |
| --- | --- |
| `deploy/local.sh up` | exit 0 (JAR and the four local images built, `vigil/storm-driver:local` included; `state/lab-profile` = `test`) |
| `VIGIL_STORM_RESULTS=$HOME/storm-results tests/acceptance/storm/run.sh --mode immediate` (run 1) | exit 0; `storm-immediate-20261112T192958Z.json`: `finished` true, `passed` true, 33 checks made, 33 passed; `not_run`: `SB-S01 storm within 120 s`, `SB-S03 diagnostic values`, `SB-S06 action values`, `SB-S09 dashboard values`; reset: branch A 12.242 s (`quiet_seconds` 0.083), branch B 1.065 s, total 12.264 s; times after T0: SB-TL-01 30.6 s, SB-TL-02 71.0 s, SB-TL-04 72.9 s, SB-TL-06 74.8 s, SB-TL-07 74.9 s, SB-TL-08 77.6 s, SB-TL-10 174.2 s; `runbooks_cited` [RB-DB-01]; `SB-S09 dashboard formulas`: computed 5.6, 40.4, 650, 47, equal to AR-I-80; storm `INC-0002` (`NOC-2`), report `INC-0001` (`NOC-1`); `AR-Q-07 passed: no secret of the installation in the log of this run.` |
| `pytest -m installation deploy/local` (its `reset` runs after run 1, with SL-F-3 active) | `4 passed` |
| `run.sh --mode immediate` (run 2, its reset clears the report's SL-F-3 alert of run 1) | exit 0; `storm-immediate-20261112T193327Z.json`: 33 of 33 passed; reset: branch A 16.23 s (`quiet_seconds` 4.091), branch B 1.044 s, total 16.243 s; SB-TL-10 173.1 s; AR-Q-07 passed |
| `deploy/local.sh e2e smoke` (after the driver's reset; its seed import file is the one `up` wrote) | `4 passed (1.0s)` |
| `deploy/local.sh down --volumes` | exit 0; no container and no volume of the project left |

Every check passed on its first real run (`SB-S02 storm header`: 126 alerts, hypothesis `srv-db-01` with 6 and 6; `SB-S03 diagnostics` by form, `/data` 99 % full in the `test` profile; `SB-S06 similar incident`: PI-1001, score `1.00`, success count 4; `SB-S06 action result`: the precondition value 99, as BP-R-30 expects in the `test` profile; `SB-S07 recovery`; `SB-S08 storm run`: the thirteen steps `completed` with visit 1). The cloud run's result files are not committed (spec Section 8 item 3).

**Flaky-test recipe** (spec Section 6.8; `deploy/test_new_installation.py::test_first_and_second_run`, 20 runs while `./mvnw -B verify` runs):

| Run | Result |
| --- | --- |
| 20 runs on the unchanged script, started 90 s after `./mvnw -B verify` (19:38:43 to 19:39:55 UTC, the build running throughout) | 20 passed, 0 failed: not reproduced |
| 15 more runs with the test and three busy loops pinned to one CPU (`taskset -c 0`) | 15 passed, 0 failed: not reproduced |
| 20 runs on the fixed script during the gate's step (2) (`./mvnw -B verify`), ending 19:46:19 UTC | 20 passed, 0 failed |

**Cause, found by reading the second run's path rather than by a failure.** The only way the second run calls `put-parameter` (the call the AWS fake refuses with `ParameterAlreadyExists`) is `has "$name"` answering false for a name that `describe-parameters` listed. `has` was `printf '%s\n' "$existing" | grep -qx "$1"` under `set -o pipefail`. `strace` shows that bash's builtin `printf` makes one `write()` per line (21 writes for the 21 names). `grep -q` exits at its match, so when it is scheduled between two of those writes (more likely while another build loads the CPU), the next write gets SIGPIPE: `printf` ends with 141, the pipeline fails, `has` answers "missing", and the script tries to create a parameter that exists. With a list long enough that the writer is always still writing, the same pipeline fails every time (`printf … | grep -qx name-1` over 200,000 names: exit 141). **Fix**: `has` reads a here-string (`grep -qx "$1" <<< "$existing"`), so no writer can be killed. The new test "AR-S-13 has() of new-installation.sh finds an existing name also when grep stops reading at its match" runs the script's own `has` over 200,000 names: it fails on the old function and passes on the new one.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

11, in `deploy/local.sh`, `deploy/images.sh` and `deploy/new-installation.sh` (BP-R-05 limit for S 800; spec estimate about 40). The driver and its tests are under `tests/acceptance/storm/`, which BP-R-05 does not count (CTX-D-083 (9)).

## 10. Gate command result

`tools/check-all.sh` passed in this session (exit 0, "all checks passed"): step (0) Docker, (1) setup version, (1a) test images, (2) `./mvnw -B verify` (197 unit tests and 776 integration tests in `vigil-app`, 3 tests in `demo-control`, 0 failures, 0 errors; `BUILD SUCCESS`), (3) `ruff` and pytest with the lab tests (the branch changes lab paths of CTX-D-067 (3)): 527 passed, 1 skipped, 4 deselected (the `installation` tests) in 11 min 45 s, (4) shellcheck, (5) data checks (49 checks, 0 failed), (6) compose validation, (7) traceability. The gate ran while the recipe's 20 runs of Section 8 ran beside its step (2).

## 11. Follow-up items for later packages

- P-28 to P-30, P-33: their Playwright suites check the screens during a driver run (`run.sh` or the compose service `storm-driver`); the storm incident's number is not fixed (Section 4 item 1).
- P-31: `demo-control`'s reset is AR-B-03 with the 58 s limit and the 60 s target; the driver's `Reset` (`storm_api.py`) and its tests show the reads and the order; SB-T-05's clear margin (CTX-D-083 (10)) is measured there; `deploy/local.sh reset` is unchanged.
- P-32: the driver against the demo installation with `--deliveries checklist` (its own compose service on the main host, `VIGIL_BASE_URL`, the real `jira` secret for the status read); the `correlation` health details (CTX-D-082 (6)).
- P-34: SB-T-02's five runs (`run.sh --mode script`), the RB-DB-01 citation count (`investigation.runbooks_cited` of each result file).
- The `printf | grep -q` pattern in other `deploy/` scripts (Section 4 item 2).
