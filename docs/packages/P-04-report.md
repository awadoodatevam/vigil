# P-04 — Report: Lab monitoring, faults and storm timing

| Field | Value |
| --- | --- |
| Package | P-04 Lab monitoring, faults and storm timing (spec `docs/packages/P-04.md`) |
| Branch | `p-04-lab-monitoring-faults-storm-timing` |
| Pin date (BP-R-08) | 15 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.8.2, Compose 2.40.3) |
| State | Built; the gate command passes in this session (twice, P04-DC-02 steps 1 and 6); the cloud 10-storm run of SB-T-01 passed (10 of 10 standard storms, the `read-only` and the `clear` run; Section 8); the owner-run step (Section 6.13, `tests/acceptance/p04_mac_check.sh`) is open |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| `demo/monitoring/provision/provision.py` (`lab-zabbix-provision`): waits for `apiinfo.version` (120 s) and the login (60 s more); logs in as `Admin`, changing the default password `zabbix` to `zabbix-admin-password` on the first run (`user.update` with `current_passwd`), or stops with the spec's message when both passwords are refused; creates or updates, in the order of spec 6.2.2, the host group `Lab hosts`, the three agent hosts (DNS interface, port 10050, no templates, no tags), the 32 items (agent, numeric unsigned, `10s`, one "Trim" step on every `vfs.file.contents` item), the 32 triggers (expressions of spec 6.2.2, tags `component` and `vigil=lab`, single problem event, OK by expression, no manual close), the media type `Vigil webhook` (the 10 parameters of DD-F-10 in order, the script of spec 6.2.3 holding the address and the token as JSON string literals, message templates for problem and recovery), the user group `Vigil` (read on `Lab hosts`), the user `vigil` (`User role`, a 32-character random password set only on create, one webhook media), the action `Vigil lab webhook` (one tag condition, one operation steps 1–1, one recovery operation, no update operations); keeps the token in `/srv/vigil/generated/zabbix-api` when `host.get` with it answers, otherwise deletes the old `vigil` tokens and writes a new one (0600, no newline, temporary file and rename); logs out. One line per step; no secret, token or session identifier printed | AR-X-02, AR-U-09, DD-F-10 (`monitoring_configuration`) |
| `demo/monitoring/provision/make_secret.py` (`token`, `password`, `web-config` with `bcrypt.hashpw(…, gensalt(rounds=10))`), `Dockerfile`, `requirements.txt` (`bcrypt==5.0.0`) | AR-S-13, BP-R-04 |
| `demo/monitoring/prometheus/prometheus.yml` (5 s scrape and evaluation, 4 s timeout, the four jobs, `honor_labels` on `lab-api`, Alertmanager with `basic_auth` and `password_file`), `lab-rules.yml` (the 5 rules of LA-04 to LA-08, `for: 10s`, `component` label, DC-A-17 summaries) | AR-U-10, AR-X-02, DD-F-10 |
| `demo/monitoring/alertmanager/alertmanager.yml` (template with `${webhook_base_url}`: route `discard` with one child route for the 5 catalogue names, `group_by ['...']`, 5 s / 10 s / 12 h; receiver `vigil` with `send_resolved`, `max_alerts: 0`, bearer `credentials_file`) | AR-U-10, AR-X-02 (AR §12.4) |
| `demo/faults/` (`demo-faults`): `faults.py` (standard library; `ThreadingHTTPServer` on `FAULTS_LISTEN` with one lock; bearer token with `hmac.compare_digest`; `POST /faults/{id}/inject` and `POST /faults/clear` exactly as spec 6.3, 404 and 500 answers, idempotent inject, clear in the order SL-F-1, SL-F-2, SL-F-3; state file by temporary file and rename; commands through the Docker Engine API on the socket: exec create as `root`, attached start, demultiplexed output, exit code, 60 s deadline, API version from `GET /_ping`; one log line per request with the commands' output after it); `scripts/sl-f-1-inject.sh`, `scripts/sl-f-1-clear.sh`; `Dockerfile` | SL-A3-02, SL-F-1, SL-F-2, SL-F-3, AR-U-07, AR-X-03, AR-T-21, DD-F-10 (`faults`) |
| `dev/recorder/` (webhook recorder): the operations and the recorded call object of spec 6.4 (numbering after existing files, 413 over 5 MiB, `Authorization` kept only as `Bearer <redacted>`, files by rename, `request_queue_size = 128`) | SB-T-01, AR-X-02 |
| `spikes/storm-timing/` (`storm_timing.py`, `run.sh`, `Dockerfile`, `requirements.txt` with `PyYAML==6.0.3`): readiness, connection checks, versions, start clear, baseline window, the three run kinds and their nine steps, every check of spec 6.5, failure of a step, result file, saved recordings; the DD-F-04 parsers and AR-K-12 quoting in Python; evaluation as a pure function of the recorded calls | SB-T-01, SB-T-05, DD-F-10 (`timing_targets`) |
| `deploy/local/compose.yaml`: `lab-zabbix-db`, `lab-zabbix-server`, `lab-zabbix-web`, `lab-zabbix-provision`, `lab-prometheus`, `lab-alertmanager`, `demo-faults`, `dev-recorder` (profile `lab-only`, alias `demo.vigil.test`), `storm-spike` (profile `spike`) with the settings of spec 6.6; volumes `lab-zabbix-db-data`, `demo-faults-state`; start order of the lab hosts; `deploy/values/local.yaml` `webhook_base_url` | AR-U-08, AR-U-09, AR-U-10, AR-U-07, AR-T-22, AR-S-04 |
| `deploy/local.sh`: other-project check over the 14 fixed names; images; the monitoring and fault secrets of spec 6.7 (one `make_secret.py` call, copies between folders, `alertmanager-web-config` remade with a new password, the stop when `zabbix-db-password` is new but the database volume was kept); `generated/`, `config/`, `recordings/`; `config/alertmanager.yml` rendered every time; start, wait (180 s), provision with `docker wait`, `zabbix-api` copied into `vigil-app` and `demo-control`; `down` with both profiles and `--remove-orphans` | AR-S-04, AR-S-13, BP-R-26, AR-X-02 |
| `deploy/images.sh --test`: pulls of the compose file's digest-pinned images with 5 attempts 30 s apart, then the five builds in the order of spec 6.8 | BP-R-04 |
| `demo/lab/entrypoint.sh` (`Server=172.31.0.0/24,127.0.0.1`), `demo/lab/README.md` (section "Monitoring and faults (P-04)"), `demo/lab/test_lab.py` (module fixture; 10 folders and 21 files) | AR-U-08, BP-R-26 |
| `tools/check-all.sh` step 3 (`spikes`; lab tests only when the branch changes the lab, CTX-D-036), `.github/workflows/ci.yml` (`timeout-minutes: 60`), `tools/trace.py` (`spikes`) | BP-R-12, BP-R-15 |
| READMEs: `README.md`, `demo/monitoring/README.md`, `demo/faults/README.md`, `dev/recorder/README.md`, `spikes/README.md`, `spikes/results/README.md`, `spikes/storm-timing/README.md`, `dev/fakes/recordings/monitoring/README.md`; `tests/acceptance/p04_mac_check.sh` (owner-run step) | BP-DC-06, P04-DC-05 |

## 2. Tests added

Unit tests (no container):

| File | Test | IDs |
| --- | --- | --- |
| `demo/faults/test_faults.py` | `test_interface`, `test_failures`, `test_restart`, `test_docker_exec_stream` | AR-X-03 |
| | `test_standard_library_and_scripts` | AR-T-21 |
| `dev/recorder/test_recorder.py` | `test_recorder`, `test_missing_secret` | SB-T-01 (recorder) |
| `demo/monitoring/provision/test_provision.py` | `test_provision` (fake JSON-RPC server: first run, second run, replaced token, no secret in the output), `test_refusals` | AR-X-02 |
| `spikes/storm-timing/test_storm_timing.py` | `test_expected_sets`, `test_good_runs` (3 kinds), `test_failing_lists`, `test_zabbix_message_checks` (5 cases), `test_alertmanager_message_checks`, `test_measure_checks`, `test_stopped_run`, `test_parsers_and_quoting`, `test_result_file` | SB-T-01 |
| | `test_numbers_and_summaries` | DD-F-10 (timing_targets) |
| `tools/test_trace.py` | `test_spikes_tests_count` | BP-R-15 |

Lab tests, `demo/monitoring/test_monitoring.py` (marker `lab`, project `vigil-labtest`, in the order of spec Section 7):

| Test | IDs |
| --- | --- |
| `test_start_order` | AR-U-08 |
| `test_zabbix` | AR-U-09 |
| `test_prometheus_and_alertmanager` | AR-U-10 |
| `test_versions` | AR-T-22 |
| `test_secrets` | AR-S-13 |
| `test_secret_mounts` | AR-S-04 |
| `test_secret_files` | BP-R-26 |
| `test_storm` | SB-T-01 |
| `test_monitoring_configuration` | DD-F-10 (monitoring_configuration) |
| `test_timing_targets` | DD-F-10 (timing_targets) |
| `test_sl_f_1`, `test_sl_f_2`, `test_sl_f_3` | SL-F-1, SL-F-2, SL-F-3 |
| `test_clear_all_faults` | SB-T-05 |
| `test_messages` | AR-X-02 |
| `test_faults_unit` | AR-U-07 |
| `test_faults_interface` | AR-X-03 |
| `test_faults_on_demand` | SL-A3-02 |
| `test_images` | BP-R-04 |

BP-R-12 is "checked by review" (spec part 4): the skip rule is in `tools/check-all.sh` step 3. `tools/trace.py --check` passes.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | The "Trim" preprocessing step's characters are written `\s\t\r\n` (Zabbix's escape sequences for space, tab, CR, LF), not as the four characters themselves. | The Zabbix 7.0.31 API splits a preprocessing step's `params` at a newline: `item.create` with the literal characters failed with `Invalid parameter "/1/preprocessing/1/params": unexpected parameter "2"`. Zabbix's trim steps read `\s`, `\t`, `\r`, `\n` as these characters, so the meaning of spec 6.2.2 is kept; every item has a value (`test_zabbix`). The smallest change that keeps the meaning (BP-ASM-11). |
| 2 | The measured `avail_bytes_after_injection` is taken from the `disk_usage` diagnostic, which is the operation `fs_usage` with `mount` `/data`, instead of a second identical `fs_usage` run. | Spec 6.5 step 5 names both; they run the same command with the same parameter, so one run gives the value. |

## 4. Document feedback (CTX-Q-8)

1. **Zabbix preprocessing parameters** (deviation 1): spec 6.2.2 (and any later text that lists trim characters) MAY say that the characters are given as `\s\t\r\n`, because the API reads a newline in `params` as a parameter separator.
2. **Docker Hub refusals in cloud sessions (BP-O-04)**: this session's anonymous pulls were refused with `429 Too Many Requests` at least 18 times (8 times in a row for `postgres:16.15`, and again for `zabbix-server-pgsql`, `zabbix-web-nginx-pgsql` and `prometheus`), and Testcontainers' pull of `pgvector/pgvector` was refused once during the gate before it succeeded. All pulls succeeded within the retries. This is the first package report after P-03 that records refused pulls, so BP-O-04's "Resolved in" applies: the pre-pull in `tools/cloud-setup.sh` (or a registry mirror) is for the owner to decide. `mirror.gcr.io` is reachable from the session (HTTP 401 on `/v2/`, the normal answer) and could serve as the daemon's `registry-mirrors`.
3. **Admin's API view**: as `Admin`, `item.get` and `trigger.get` also return the objects of Zabbix's own host "Zabbix server" (from its default template). The lab tests and the spike read the lab's objects by host group or as user `vigil` (read on `Lab hosts` only). For P-31: `demo-control` reads Zabbix as `vigil`, so its problem list holds only lab problems.

## 5. Blocking questions

None. Every check of the cloud development run passed, so no timing target needs an owner decision (DD-ASM-03).

## 6. Versions introduced (BP-R-08, pin date 15 October 2026)

| Item | Version |
| --- | --- |
| `postgres` | `16.15@sha256:ca0bd484cb98bf4b24eb1010e73fb3fcbd6714d240fbc1a10eea5b7dbecb641d` (OCI index; newest 16.x, pushed 7 Oct 2026) |
| `zabbix/zabbix-server-pgsql` | `alpine-7.0.31@sha256:bdfe90847bd259a6da448db1e6ae83f5f439224654ed767296a8d57a088975b9` (OCI index; newest 7.0 patch, 22 Sep 2026) |
| `zabbix/zabbix-web-nginx-pgsql` | `alpine-7.0.31@sha256:d9ce0774cb6036ce5d49c9c8d46bf1474dad98aae810350de0d5068dd679b92d` (OCI index) |
| `prom/prometheus` | `v3.15.0@sha256:efd719c99d83b060d9daefdcf00360461adf279f45ef5391f8d111892118753e` (manifest list; newest 3.x release, 25 Sep 2026) |
| `prom/alertmanager` | `v0.34.1@sha256:e9733bafb1bdef9b00e25a21f8f99dc26a22224bf16641ad754d1649f4c3357a` (manifest list; newest release, 17 Sep 2026) |
| Zabbix server (`apiinfo.version`) | 7.0.31 |
| `bcrypt` (PyPI, `demo/monitoring/provision/requirements.txt`) | 5.0.0 (latest release) |
| `PyYAML` (`spikes/storm-timing/requirements.txt`) | 6.0.3 (the pin of `tools/requirements-dev.txt`) |
| Ubuntu packages of the new images | `python3` 3.12.3-0ubuntu2.1, `openssh-client` 1:9.6p1-3ubuntu13.20 (from the pinned `ubuntu:24.04`) |

The five third-party images publish `linux/amd64` and `linux/arm64` (checked with `docker buildx imagetools inspect`).

## 7. Values recorded under `docs/`

None in this session. DD-F-10 `lab_db_server.development_values` are recorded from the Mac run by the session "Record the owner-run results of P-04" (spec Section 9).

## 8. Owner-run results

### 8.1 Cloud session: SB-T-01 development run (P04-DC-02, profile `test`)

The order of P04-DC-02 was kept: (1) the gate passed; (2) `deploy/local.sh up --lab-only` (project `vigil-local`, profile `test`); (3) `spikes/storm-timing/run.sh --storms 10 --save-recordings` (detached; 18:45:56 to 20:40:36 UTC, 1 h 55 min); (4) the result file and the saved recordings committed; (5) `deploy/local.sh down --volumes`; (6) the gate passed again; (7) this report and the pull request.

Result file: `spikes/results/storm-timing-20261015T184555Z.json`, `passed` true, `machine` `x86_64`, versions Zabbix 7.0.31, Prometheus 3.15.0, Alertmanager 0.34.1. Checks: `zabbix_connection_test` (HTTP 200 with a result array), `alertmanager_connection_test` (HTTP 200), `alertmanager_requires_auth` (HTTP 401), `prometheus_targets_up`, `start_clear` and `baseline_window` (873 s) passed.

| Run | `passed` | first arrival (s) | last arrival (s) | largest gap (s) | SL-F-3 arrival (s) | last resolve after action (s) | last resolve after clear (s) | storm firing (Zabbix + AM) | SL-F-3 firing | still firing after action |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| standard 1 | true | 1.5 | 30.5 | 11.0 | 9.5 | 39.9 | 8.0 | 126 (31 + 95) | 1 | — |
| standard 2 | true | 2.5 | 31.5 | 14.0 | 3.5 | 33.8 | 8.0 | 126 (31 + 95) | 1 | — |
| standard 3 | true | 2.6 | 30.6 | 13.0 | 3.6 | 35.9 | 6.0 | 126 (31 + 95) | 1 | — |
| standard 4 | true | 0.6 | 30.7 | 15.0 | 1.6 | 35.0 | 5.0 | 126 (31 + 95) | 1 | — |
| standard 5 | true | 2.6 | 31.0 | 9.4 | 10.6 | 30.9 | 8.0 | 126 (31 + 95) | 1 | — |
| standard 6 | true | 2.6 | 31.6 | 14.0 | 3.6 | 31.9 | 10.0 | 126 (31 + 95) | 1 | — |
| standard 7 | true | 4.6 | 30.6 | 10.3 | 5.6 | 35.1 | 9.0 | 126 (31 + 95) | 1 | — |
| standard 8 | true | 3.6 | 30.6 | 12.0 | 4.6 | 36.1 | 7.0 | 126 (31 + 95) | 1 | — |
| standard 9 | true | 1.6 | 30.6 | 14.0 | 2.6 | 36.1 | 5.0 | 126 (31 + 95) | 1 | — |
| standard 10 | true | 1.6 | 31.6 | 10.3 | 0.6 | 40.0 | 9.0 | 126 (31 + 95) | 1 | — |
| read-only 1 | true | 3.6 | 30.6 | 12.0 | 4.6 | 13.5 (the later disk_full resolve) | 38.0 | 126 (31 + 95) | 1 | 124 |
| clear 1 | true | 1.6 | 30.6 | 11.0 | 8.6 | — | 37.3 | 126 (31 + 95) | 1 | — |

Measured values of the `test` profile (identical in all 10 standard storms): `baseline_used_pct` 26; `used_pct_after_injection` 99; `avail_bytes_after_injection` 67,104,768; `injected_files` 11; `injected_bytes` 3,079,475,200; `injected_gib` 2.9; `disk_usage` "/data is 99% full (3.9 of 3.9 GiB used, 0.1 GiB free)"; `largest_folders` "Largest folder under /data: /data/txlog_archive (2.9 GiB)"; `disk_growth` "2.9 GiB written to 11 files under /data in the last 15 minutes"; `action_files_removed` 11; `action_gib_removed` 2.9; `used_pct_after_action` 26.

Margins: every alert arrived within 32 s of the injection (target 120 s) with gaps of at most 15 s (target 25 s); the storm resolved within 40 s of the action (target 60 s); no alert fired again within 300 s. The clear is the tightest target: with the storm alerts still firing (`read-only`, `clear`) the last resolve came 37.3 to 38.0 s after the clear (target 40 s), set by the 20 s windows of the LA-02 trigger and the Prometheus `rate()` rules plus the 10 s item interval or the 10 s `group_interval`. In the gate's storm of this session it was 36.3 s. The gate retry rule covers a slower machine; P-31's reset (SB-T-05) has 12 s more (52 s for branch A).

Saved recordings: the 254 calls of standard storm 1 (64 Zabbix: 32 firing and 32 resolved, including LA-09; 190 Alertmanager: 95 firing and 95 resolved) under `dev/fakes/recordings/monitoring/`. No file holds a secret (each secret file of the installation was searched for in them).

### 8.2 Owner-run step (Section 6.13): open

The owner runs `tests/acceptance/p04_mac_check.sh` on the Mac from the repository root (about 55 minutes plus the image build) and commits `tests/acceptance/results/P-04-mac-demo.txt` and the result file under `spikes/results/`; a follow-up session "Record the owner-run results of P-04" copies the `demo` values into DD-F-10 `lab_db_server.development_values` and into this section.

### 8.3 Assumptions

| Item | Result |
| --- | --- |
| DD-ASM-03 | Proved in the cloud session (`test` profile): every timing target of DD-F-10 that does not need Vigil was met in 10 of 10 standard storms and in the `read-only` and `clear` runs (8.1). Mac run open. |
| DD-ASM-05 | Open (measured by the Mac run in the `demo` profile: `baseline_used_pct` against 43 to 45 %, injection between 7 and 12 GiB). Information: `test` profile 26 % baseline, 2.9 GiB injected; P-03 measured 47 % with `df` in `demo`. |
| SL-ASM-01, DM-ASM-05 | Proved: 126 individual entries (95 from Alertmanager, one alert per call) within 32 s, each storm alert exactly once (`arrival`, `counts`, `exactly_once` in all 12 runs). |
| DM-ASM-08 | Proved for the lab: every lab alert resolved within 40 s of the clear (`resolve_after_clear`, at most 38.0 s). |
| AR-ASM-07 | Proved: `{EVENT.TAGSJSON}` parses to an array of `{tag, value}` objects (`messages` passed in all runs; the recordings). |
| BP-ASM-05 | Open (Mac run). The five images publish `linux/arm64` (registry index). |
| BP-ASM-09 (P-04 part) | Proved: agent 2 7.0.25 with server 7.0.31: all 32 items have values (`test_zabbix`), and the node exporter's `node_filesystem_avail_bytes{mountpoint="/data"}` drives LA-04. |
| BP-ASM-10 | Proved in the cloud session: `demo-faults` on the host network listens on 172.31.0.1:8091, is reached from `storm-spike` on `lab-net` and from the test process, and runs commands in `lab-db-01` and `lab-rpt-01` through the socket. GitHub runner: by the `gate` job of the pull request. Mac: open (owner-run step). |
| BP-ASM-11 | Proved with one deviation: `last(…,#1:now-20s)`, `vfs.fs.size` and `vfs.file.contents` with a trim step on numeric items, a webhook script with custom headers through `HttpRequest`, a token of a non-Super-admin user (`token.create` for `vigil` by `Admin`) and every create and update method work; the trim characters are written as `\s\t\r\n` (deviation 1). |
| BP-O-04 | Docker Hub refused pulls in this session (document feedback 2); all pulls succeeded within the retries. |

## 9. Lines of production code added and changed (P04-DC-03)

`tools/loc.sh origin/main HEAD` prints **1,202** (at most 3,500). `spikes/` is not production code under BP-R-05 and is not counted.

## 10. Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, setup version 1): steps 0 to 7 pass, last line `== all checks passed`, twice: before the development run (P04-DC-02 step 1) and after `down --volumes` (step 6, 670 s in total). Step 1a pulled the five third-party images (after the refusals of document feedback 2) and built the five local images; step 2: `vigil-app` 49 unit tests and 28 `*IT`, `demo-control` 1 and 2 (no Java change; the P-01 to P-03 tests pass); step 3: `ruff` clean, 85 Python tests in 585 s, among them the 16 lab tests of P-03 and the 19 lab tests of P-04 with one storm (`--storms 0 --extra read-only`), which passed without the retry in both gate runs; step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: `deploy/local/compose.yaml` valid with no variable set; step 7: `trace.py --check` exits 0. This branch changes `demo/`, `deploy/`, `dev/`, `spikes/` and `tools/`, so step 3 ran the lab tests (CTX-D-036).

GitHub: the `ci.yml` job `gate` runs on the pull request; its result is added here when it has finished.

Done-check: BP-DC-01 (every part 4 row has its test, `trace.py --check`), BP-DC-03, BP-DC-04 (no Java change), BP-DC-05 (nothing of that kind added), BP-DC-06 (`README.md`; `CLAUDE.md` was updated by the spec commit) hold; BP-DC-02 holds in the session, the runner pending; BP-DC-07: the owner-run result file is open (Section 8.2). P04-DC-02 holds (Section 8.1). P04-DC-03 holds (1,202). P04-DC-04 is open (owner-run step). P04-DC-05 holds: the READMEs of spec 6.11 with the interfaces of 6.12; under `docs/` only this report changed.

## 11. Follow-up items for later packages

1. P-10: the recordings of one standard storm are under `dev/fakes/recordings/monitoring/` (format in its README).
2. P-26: `vigil-app` takes the alias `demo.vigil.test` on `lab-net`; `dev-recorder` is in the profile `lab-only` and is not started by the full `up`; `deploy/local.sh down` already stops the profiles `lab-only` and `spike`.
3. P-31: `demo-faults` adds SL-F-4 (and `NET_ADMIN`, `NET_RAW`); `demo-control` reads Zabbix as `vigil` with `secrets/demo-control/zabbix-api` and Alertmanager with `alertmanager-password`, as `storm_timing.py`'s quiet check does (`Lab.zabbix_problems`, `Lab.alertmanager_alerts`).
4. P-32: `lab-zabbix-provision` runs unchanged with `user: 10001`, the demo `webhook_base_url` and `/srv/vigil/generated`.
5. BP-O-04: see document feedback 2.
