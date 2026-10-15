# P-03 — Report: Lab hosts and mocks

| Field | Value |
| --- | --- |
| Package | P-03 Lab hosts and mocks (spec `docs/packages/P-03.md`) |
| Branch | `p-03-lab-hosts-and-mocks` |
| Pin date (BP-R-08) | 15 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.8.2, Compose 2.40.3, buildx 0.37.1) |
| State | Built; the gate command passes in this session and the `ci.yml` job `gate` passes on the pull request; the owner-run step (Section 6.10) passed on the owner's Mac (Section 8); deviation 1 and document feedback 1 need the owner's confirmation |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| `demo/lab/Dockerfile`: stage `agent` from `zabbix/zabbix-agent2:ubuntu-7.0.25@sha256:…` (only `/usr/sbin/zabbix_agent2` copied, then an `ldd` check that fails on `not found`); final stage `ubuntu:24.04@sha256:…` with the 12 Ubuntu packages of spec 6.1 step 3 (build CA as BuildKit secret, sources switched to `https://` only while the secret exists, then back to `http://`, CA file and package lists removed); `/opt/venv` with `prometheus-client==0.26.0`; `rm -f /etc/ssh/ssh_host_*`; users and groups `zabbix` 1997, `vigil-diag` 2001, `vigil-action` 2002 (in `vigil-diag`), password field `*`, shell `/bin/bash`; `labmocks/`, `labctl`, `lab-entrypoint`, the `sshd_config` drop-in; `ENV TZ=UTC PYTHONPATH=/opt/labmocks`, no `USER` | AR-X-01, AR-T-21, BP-R-04, AR-S-04, AR-H-09 |
| `demo/lab/labmocks/` (Python 3.12; behaviour modules standard library only, `metrics.py` the only importer of `prometheus_client`): `db` (6 schemas, read-only flag then `statvfs("/data")` < 256 MiB checked at every write, counts, status files by rename), `api` (8 instances on 8101–8104 and 8201–8204, the writes of spec 6.2 with 1 s deadlines, own stream 32 requests/s, counters per `api`, `instance`, `endpoint`), `vas` (USSD nodes `ussd-gw-a`/`-b` with the 6 menus, round-robin instance per node and kind, 1.5 s API and 2 s session deadlines, `POST /session`, own stream 12 sessions/s; SMS queue with 24 routes, 1 message/s each, dispatcher with ≤ 20 deliveries/s, retry every 1 s, depth status files), `probe` (50 sessions every 10 s, journey → menu and city → node mapping, 3 s deadline, gauge created at 1), `rpt` (mode `succeed` and a run at start, a run every 600 s and within 1 s of a run request); clock, sleep and HTTP client are arguments; fixed-rate streams with one thread per call; `ThreadingHTTPServer` with `request_queue_size = 128`; logging only of the events of spec 6.2 | DD-F-10 (`mock_behaviour`), SL-A3-01 |
| `demo/lab/entrypoint.sh`: `LAB_ROLE` check, removal of `/run/lab/ready` and old pid files, the processes of each role in the order of spec 6.3 (node exporter as `nobody`, agent as `zabbix` with `setpriv`), the agent configuration of spec 6.3, the ready check (TCP connect on every port, 1 s for the job without a port, every 0.5 s for at most 60 s), `wait -n`, `SIGTERM` handling with unmount of `/data`; `/data` set-up (sparse image, `mkfs.xfs -q`, loop device nodes, detach of stale loops, `losetup -f` with `mknod` when needed, `mount -o loop=…,nouuid`, folder owners and modes at every start, baseline files only for a new image: 8 + 4 `fallocate` files, `root:vigil-diag` 0640, modification time 30 days back); keys from `/run/secrets/vigil` at every start | AR-U-08, AR-X-01, DD-F-10 (`lab_db_server`), BP-R-30, AR-S-04 |
| `demo/lab/labctl.sh`: `db read-only [on|off]` on `lab-db-01`, `report [fail|succeed]` on `lab-rpt-01`, usage line and exit 2 otherwise; `LABCTL_ROOT` | DD-F-10 (control points of SL-F-2, SL-F-3) |
| `demo/lab/sshd_config`, `demo/lab/requirements.txt`, `demo/lab/README.md` (every interface of spec 6.4) | AR-X-01, AR-T-21 |
| `deploy/images.sh --test`: BuildKit check, base-image pulls with 5 attempts 30 s apart, `docker build --network host` with the set proxy variables as build arguments and the CA bundle as secret `ca_bundle` | BP-R-04 |
| `deploy/local/compose.yaml`: networks `vigil-net` 172.30.0.0/24 and `lab-net` 172.31.0.0/24, volume `lab-db-01-data`, the five lab services as spec 6.6 (only `lab-db-01` privileged and holding the secret bind mount, no published port, health check on `/run/lab/ready`); `deploy/values/local.yaml` (`lab_profile: test`) | AR-H-03, AR-U-08, AR-S-04 |
| `deploy/local.sh up --lab-only [--profile test|demo] [--project NAME]` and `down [--volumes] [--project NAME]`: Docker check, other-project check, image, secrets (0700 / 0555 / 0444, key pairs generated with `ssh-keygen` in the lab image, JSON by `awk`, incomplete-pair message, existing files never changed), `values/derived.yaml` every time, compose up, health wait (180 s); bash 3.2 compatible (macOS) | AR-S-13, AR-B-07, BP-R-26, BP-R-30 |
| `tools/start-docker.sh` and gate step (0) in `tools/check-all.sh` (only when `CLAUDE_CODE_REMOTE` is `true`) | BP-R-12 |
| `pytest.ini` markers `lab` and `mac`; `README.md` sections "The gate command" and "Local installation"; `tests/acceptance/p03_mac_check.sh` (owner-run step) | BP-DC-06, P03-DC-04 |

## 2. Tests added

Unit tests, `demo/lab/test_labmocks.py` (7 functions, 9 cases; standard library and pytest, fakes and a simulated clock):

| Test | IDs it proves |
| --- | --- |
| `test_database` | DD-F-10 (database) |
| `test_api_instances`, `test_call_without_time_left` (2 cases) | DD-F-10 (API instances) |
| `test_ussd_gateway` | DD-F-10 (USSD gateway) |
| `test_sms_queue` (discrete-event simulation, 24 routes, 30 s failing then 30 s working) | DD-F-10 (SMS queue) |
| `test_probes_and_report_job` | DD-F-10 (probes and report job) |
| `test_every_writer_rate` | DD-F-10 (every-writer rate) |
| `test_labctl` | DD-F-10 (labctl) |

Lab tests, `demo/lab/test_lab.py` (marker `lab`; one session fixture runs `deploy/local.sh up --lab-only --profile {VIGIL_LAB_PROFILE} --project vigil-labtest` and `down --volumes` at the end):

| Test | `mac` | IDs it proves |
| --- | --- | --- |
| `test_containers` | | AR-U-08 |
| `test_networks` | | AR-H-03 |
| `test_time_zone` | | AR-H-09 |
| `test_python` | | AR-T-21 |
| `test_image_and_data` (with a restart of `lab-db-01`) | yes | AR-X-01 |
| `test_size_profile` | yes | BP-R-30 |
| `test_secret_mounts` | | AR-S-04 |
| `test_secrets` | | AR-S-13 |
| `test_secret_files` | | BP-R-26 |
| `test_derived_value` | | AR-B-07 |
| `test_permissions` | yes | DD-F-10 (permissions) |
| `test_command_templates` | yes | DD-ASM-02 |
| `test_storm` | | SL-A3-01 |
| `test_fault_control_points` | | DD-F-10 (fault control points) |
| `test_image` | | BP-R-04 |
| `test_interfaces` | | DD-F-10 (interfaces) |

BP-R-12 is "checked by review" (spec part 4). `tools/trace.py --check` passes.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | An API request makes **every** write of its endpoint (each with its 1 s deadline) and fails when any of them fails; it does not stop at the first failed write (spec 6.2 "the request stops at the first write that does not answer 200"). The unit test checks that a request fails when its first or its second write fails or is late, and that both writes are made. | With "stop at the first write", `subscriber_profile` and `bundle_catalog` (always the second write) receive no write at all while writes fail, so their write errors never grow: that contradicts DD-F-10 `every_writer_rate` ("while writes fail every schema reports write errors (LA-02: 6 alerts)"), which outranks the spec, and the spec's own SL-A3-01 test ("write errors grow for all 6 schemas"). DD-F-10 `api_instances` says only "a request fails when any of its writes fails". See document feedback 1; the owner confirms or changes this. |
| 2 | DD-ASM-02 test: after the injection `fs_used_pct` MUST be 100 in the `demo` profile; in the `test` profile the test accepts 99 or 100. | Measured in this session: with 64 MiB free of the `test` profile's 4,227,858,432-byte `/data`, `df` shows 99 % (64 MiB is 1.6 % of 3.94 GiB, and `df` rounds up). The spec says size-dependent values are compared exactly only in `demo`. Document feedback 2. |
| 3 | The `ca_bundle` secret is mounted with `mode=0444` in both `RUN` steps that use it. | apt fetches as its sandbox user `_apt`, which cannot read the default 0400 secret ("Could not load certificates from /run/secrets/ca_bundle"). |
| 4 | `requirements.txt` reaches the `pip install` step through a BuildKit bind mount, not a `COPY`. | Keeps the file out of the image; the installed result is the same. |
| 5 | The USSD gateway writes `ussd_sessions` after the API call also when the API call failed (within what is left of the 2 s). | Spec 6.2 says "then write ussd_sessions" without a condition, and DD-F-10 says one row per session; without it `ussd_sessions` would get no writes while writes fail (same reason as deviation 1). Recorded so that the reading is visible. |

## 4. Document feedback (CTX-Q-8)

1. **Spec 6.2 API instances against DD-F-10 `every_writer_rate`** (deviation 1): "the request stops at the first write that does not answer 200" makes `subscriber_profile` and `bundle_catalog` silent while writes fail, so only 4 of the 6 LA-02 alerts (DD-F-03) could fire and the storm would have 124 instead of 126 alerts. The build makes every write. The spec's unit-test row "a request fails at the first failed or late (over 1 s) write" has the same wording. Proposed: "makes every write of the request; fails when any write fails".
2. **SL-F-1 "df shows 100%" in the `test` profile** (deviation 2): DD-F-10 SL-F-1 step (2) says that with 64 MiB free `df` shows 100 %; that holds for the 16 GiB `demo` profile only. In `test` (4 GiB) `df` shows 99 %. BP-R-30 MAY say that `df`'s percentage after SL-F-1 is a size-dependent value. The precondition (≥ 90) and the Zabbix and Prometheus thresholds are not affected.
3. **BP-R-04 build CA**: the CA bundle secret MUST be readable by apt's sandbox user `_apt` (`mode=0444` on the secret mount, or `APT::Sandbox::User "root"`).
4. **BP-ASM-02 detail**: in this cloud session only `HTTPS_PROXY`/`https_proxy` and `NO_PROXY` are set (no `HTTP_PROXY`), so the switch of the Ubuntu sources to `https://` is what makes apt work; `pypi.org` and `files.pythonhosted.org` are in `NO_PROXY` and are reached directly.
5. **Error counts at lab start (for P-04)**: the five lab containers start together (spec 6.6 has no start order), and the API, USSD and SMS mocks start their streams at once, while the database mock on `lab-db-01` starts only after the `/data` set-up and the key installation. Until it listens on port 7000 (in this session 1 to 2 s on a new `test` volume), every request, session and delivery fails with a connection error, so `lab_api_errors_total`, `lab_ussd_timeouts_total` and the SMS queue depths grow and the mocks log "failing" then "recovered". In the `demo` profile the first start also formats 16 GiB and allocates 7 GiB of baseline files, so the window is longer. Nothing monitors the lab in P-03, so nothing fails now; with P-04's Prometheus rules (error rate over 20 s above 0.2 per second, `for: 10s`) a lab start while Prometheus already runs could fire API and USSD alerts. Proposed for P-04: start `lab-api-01`, `lab-vas-01` and `lab-probe-01` after `lab-db-01` is healthy (`depends_on` with `condition: service_healthy` in `deploy/local/compose.yaml`), or start Prometheus after the lab is healthy. Not built here because spec 6.6 gives the complete list of service settings.
6. **Ready check noise**: the entrypoint's TCP connects to port 10050 and 22 from 127.0.0.1 are logged by the agent ("connection from 127.0.0.1 rejected, allowed hosts: 172.31.0.0/24") and by `sshd` ("Connection closed by 127.0.0.1"), a few lines at each start. Harmless; P-04 MAY add 127.0.0.1 to `Server` if it wants quiet logs.

## 5. Blocking questions

None stopped the build. Deviation 1 resolves a contradiction between the spec and DD-F-10 by the precedence of `CLAUDE.md` (DD-F-10 outranks the spec); the owner confirms it or decides otherwise before merging (document feedback 1).

## 6. Versions introduced (BP-R-08, pin date 15 October 2026)

| Item | Version |
| --- | --- |
| Image `ubuntu:24.04` | `ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55` (OCI index; tag last updated 4 Oct 2026) |
| Image `zabbix/zabbix-agent2` | `ubuntu-7.0.25@sha256:4a84f14b76fdc317dccfad1d92a8e09b650127adc79e3c7d40d4ca802dda3a1f` (OCI index for `linux/amd64`, `linux/arm64`, `linux/ppc64le`) |
| Zabbix agent 2 (copied program) | 7.0.25, revision d607227, 7 April 2026 (`zabbix_agent2 -V`) |
| `prometheus-client` (PyPI, `demo/lab/requirements.txt`) | 0.26.0 (latest release, 24 Jul 2026) |
| Ubuntu `prometheus-node-exporter` | 1.7.0-1ubuntu0.3 |
| Ubuntu `openssh-server` | 1:9.6p1-3ubuntu13.20 |
| Ubuntu `xfsprogs` | 6.6.0-1ubuntu2.1 |
| Ubuntu `python3` (and `libcurl4t64`) | 3.12.3-0ubuntu2.1 (8.5.0-2ubuntu10.16) |
| Packages added for the agent's libraries | None: `ldd` resolves `libpcre2-8.so.0`, `libz.so.1`, `libssl.so.3`, `libcrypto.so.3`, `libm`, `libc` from the 12 listed packages and their dependencies |

**Zabbix agent tag choice.** Tags checked on Docker Hub (`ubuntu-7.0.0` to `ubuntu-7.0.31`, newest `ubuntu-7.0.31` pushed 22 Sep 2026) and the `FROM` base of `Dockerfiles/agent2/ubuntu/Dockerfile` in `zabbix/zabbix-docker` at each release tag: 7.0.26 to 7.0.31 have `ARG OS_BASE_IMAGE=ubuntu:resolute`; 7.0.20 to 7.0.25 have `ARG OS_BASE_IMAGE=ubuntu:noble`. So `ubuntu-7.0.25` is the newest noble-based tag (patch ≥ 12, AR-T-22).

## 7. Values recorded under `docs/`

None other than this report (spec Section 9).

## 8. Owner-run results

The owner-run step (spec Section 6.10, `tests/acceptance/p03_mac_check.sh` on the Mac, profile `demo`) ran and passed. Result file: `tests/acceptance/results/P-03-mac-demo.txt` (commit `e534b00`), copied here by the session "Record the owner-run results of P-03".

| Item | Result |
| --- | --- |
| `uname -m` | `arm64` |
| `docker version --format '{{.Server.Version}}'` | `29.8.2` (Docker Desktop, builder `desktop-linux`) |
| `deploy/images.sh --test` | Passed. Pulled `zabbix/zabbix-agent2:ubuntu-7.0.25@sha256:4a84f14b76fdc317dccfad1d92a8e09b650127adc79e3c7d40d4ca802dda3a1f` and `ubuntu:24.04@sha256:534baea6a22c03a63003dbc8dbe78fe34bc0d7e595d9a9dc9834884ff530eb55` (the pinned digests); apt step from `ports.ubuntu.com` (arm64) 43.5 s; `ldd` check of the copied `zabbix_agent2` passed on arm64; `prometheus-client==0.26.0` installed from PyPI; image `vigil/demo-lab:local` (`sha256:7645f48321a8622ff286850eeccf9ffad27dafb29a345e5e1942413d723b4d08`). |
| `pytest -m "lab and mac" demo/lab -v` (`VIGIL_LAB_PROFILE=demo`) | Python 3.12.3, pytest 9.1.1 on `darwin`; 25 collected, 21 deselected, 4 selected: `test_image_and_data`, `test_size_profile`, `test_permissions`, `test_command_templates` all PASSED; `4 passed, 21 deselected in 20.05s`. |

`demo`-profile `df` and `du` lines of `lab-db-01` (information for P-04: DD-F-10 `baseline_used_pct` is measured there):

| Measure | Bytes |
| --- | --- |
| `df -B1 /data` (`/dev/loop0`): size | 17,112,760,320 |
| used | 7,877,840,896 |
| available | 9,234,919,424 |
| Use% | 47 % |
| `du -B1 --max-depth=1 /data`: `/data/db` | 6,442,450,944 |
| `/data/export` | 1,073,741,824 |
| `/data/txlog_archive` | 0 |
| `/data` | 7,516,192,768 |

Results of this session (spec Section 9):

| Item | Result |
| --- | --- |
| BP-ASM-02 (lab part) | Proved in the cloud session: `deploy/images.sh --test` pulled both base images from Docker Hub, reached `archive.ubuntu.com` and `security.ubuntu.com` over HTTPS through the proxy with the CA bundle, and PyPI. Build time: 45 s from an empty cache, base-image pulls included (35 s with the base images present). No pull was refused (BP-O-04). |
| BP-ASM-06 | Cloud session: proved (privileged `lab-db-01`, loop device, XFS mount of a 4 GiB image on the named volume, restart with unmount and remount). GitHub runner: proved by the `ci.yml` job `gate` on pull request awadoodatevam/vigil#2 (run 38734391944, head `b359e54`, 15 Oct 2026 12:24:30 to 12:29:08 UTC, conclusion `success`): the runner built `vigil/demo-lab:local` and ran all 16 lab tests in the `test` profile (step 3: 40 passed in 83 s), so privileged containers, loop devices and XFS work on GitHub-hosted `ubuntu-24.04` runners. Mac: proved by the owner-run step (Docker Desktop 29.8.2, arm64): `lab-db-01` loop-mounted the 16 GiB XFS image (`/dev/loop0` on `/data`) in the `demo` profile, and `test_image_and_data`, with its restart of `lab-db-01`, passed. |
| BP-ASM-09 (P-03 part) | Proved: the copied `zabbix_agent2` 7.0.25 passes the `ldd` check, runs as user `zabbix` in `lab-db-01`, `lab-vas-01`, `lab-rpt-01`, listens on 10050 and answers `vfs.fs.size[/data,free]` and `vfs.file.contents[…]` (`test_interfaces`). |
| Docker started by step (0) | Yes: at the start of this session the Docker daemon was not running (`docker info` failed). `tools/start-docker.sh`, the script of gate step (0), started it as root in about 2 s; it was run directly before the first image build. In the gate run that followed, step (0) found the daemon running and did nothing. No other Docker problem occurred apart from the build CA read error of deviation 3. |
| `demo`-profile `df` and `du` lines | From the owner's result file, above: `df` 47 % used (7,877,840,896 of 17,112,760,320 bytes); `du` `/data` 7,516,192,768. Information for P-04 (DD-F-10 `baseline_used_pct` is measured there). For comparison, `test` profile in this session: `df -B1 /data` size 4,227,858,432, used 1,081,274,368, available 3,146,584,064 (26 %); `du -B1`: `/data/db` 858,980,352, `/data/export` 107,364,352, `/data/txlog_archive` 0, `/data` 966,344,704. |

## 9. Lines of production code added and changed (P03-DC-02)

`tools/loc.sh origin/main HEAD` prints **1,337** (at most 2,000).

## 10. Gate command result

GitHub: the `ci.yml` job `gate` passes on pull request awadoodatevam/vigil#2 (run 38734391944, head `b359e54`, 4 min 38 s, conclusion `success`; step 3: 40 Python tests passed in 83 s, among them the 16 lab tests).

Cloud session: `tools/check-all.sh` (`CLAUDE_CODE_REMOTE=true`, setup version 1): steps 0 to 7 pass, last line `== all checks passed`. Step 0 found the daemon running (started earlier in the session by the same script); step 1a built `vigil/demo-lab:local` from cache; step 2: `vigil-app` 49 unit tests and 28 `*IT`, `demo-control` 1 unit test and 2 `*IT` (no Java change, P-01 and P-02 tests pass); step 3: 40 Python tests in 91 s (15 earlier tests, 9 mock unit tests, 16 lab tests in the `test` profile); step 4: `shellcheck` on every `*.sh`; step 5: 49 data checks, 0 failed; step 6: `deploy/local/compose.yaml` valid with no variable set; step 7: `trace.py --check` exits 0.

Done-check: BP-DC-01 to BP-DC-07 hold (BP-DC-07: the owner-run result file `tests/acceptance/results/P-03-mac-demo.txt` is committed); BP-DC-02 holds in the session and on the pull request; P03-DC-01 holds (lab tests in the session and the `gate` job on the runner); P03-DC-02 holds (1,337); P03-DC-03 holds (the 4 `lab and mac` tests passed in the `demo` profile on the Mac, Section 8); P03-DC-04 holds (`demo/lab/README.md`, `README.md`).

## 11. Follow-up items for later packages

1. P-04: the interfaces are in `demo/lab/README.md`; `labctl` is called with `docker exec {container} labctl …`; the agent configuration is written by `demo/lab/entrypoint.sh` (`start_agent`). The SL-F-1 fault script can reuse the injection of `INJECT` in `demo/lab/test_lab.py` (DD-F-10 steps 1 to 4); the files are created by root and `vigil-action` can delete them because it owns `/data/txlog_archive`.
2. P-04: in the `test` profile SL-F-1 injects 11 files, 3,079,475,200 bytes (measured in this session), leaving 67,104,768 bytes free.
3. P-02 follow-up 6 (build of `app/Dockerfile`) stays with P-05 / P-26; `deploy/images.sh` builds only `vigil/demo-lab:local` so far.
4. The lab tests add about 1.5 minutes to the gate (one lab start, a `lab-db-01` restart, the storm and recovery).
5. Document feedback 1 and 2 are for the Claude project (BP-L-8).
6. P-04: the start-order point of document feedback 5 (error counts at lab start), before Prometheus rules run against the lab.
