# P-32 — Report: Demo installation and SL-CP-1 acceptance

| Field | Value |
| --- | --- |
| Package | P-32 Demo installation and SL-CP-1 acceptance (spec `docs/packages/P-32.md`) |
| Branch | `p-32-demo-installation` |
| Pin date (BP-R-08) | 15 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | Built. The gate command passes (Section 10). Done-check item 3 in this session (lab profile `test`): `deploy/local.sh up`; `deploy/local.sh e2e` 45 passed; `deploy/local.sh e2e control` 5 passed; `tests/acceptance/storm/with-playwright.sh` passed (driver 33 of 33 checks, the suite `storm` 32 passed, AR-Q-07 passed); `deploy/local.sh down --volumes`; `docker compose -f deploy/main/compose.yaml config --quiet` passes with `COMPOSE_PROFILES` `vigil` and `spike`. The owner-run steps of spec Section 6.11 are open (Section 8). |

## 1. What was built

No change to `app/`, `web/`, the OpenAPI file, the database, `demo/control` or `demo/faults` (spec Section 5). The local installation changes only in `vigil-db`'s health check and the Playwright tests' URLs and tags (spec Section 6.1).

| Part | Requirement IDs |
| --- | --- |
| **Images** (spec 6.2): `deploy/build.sh {release}` checks all seven repositories for the release first, then builds the JARs (`./mvnw -B -q -pl app,demo/control -DskipTests package`), then the images `vigil-app:app`, `demo-control:demo/control`, `demo-lab:demo/lab`, `lab-zabbix-provision:demo/monitoring/provision`, `demo-faults:demo/faults`, `dev-recorder:dev/recorder`, `storm-spike:spikes/storm-timing` for `linux/amd64` with `--build-arg UBUNTU_MIRROR` when `VIGIL_UBUNTU_MIRROR` is set; `sse-probe` left the list. `release.yml`: `actions/setup-java` (Temurin `25.0.4+101.0.LTS`) and `actions/setup-node` (`22.23.3`) with `ci.yml`'s SHAs and cache settings, and `VIGIL_UBUNTU_MIRROR` in the job's environment. `deploy/edge.sh` step 6: the repositories `dev-recorder` and `storm-spike` (immutable tags). `dev/recorder`: `GET /actuator/health/liveness` answers 200 `{"status":"UP"}`, not recorded (README row added). | BP-W-03, BP-R-04, AR-R-02, AR-H-05, AR-H-11 |
| **`deploy/main/compose.yaml`** (spec 6.3): the 17 units of AR Section 6 on the main host plus `dev-recorder`; profile `vigil` for `vigil-app` and `demo-control`, `spike` for `dev-recorder`, none for the others; each unit as in the local compose file except BP-R-25's differences: images built here as `${VIGIL_REGISTRY:-registry.invalid}/${VIGIL_INSTALLATION:-demo}/{image}:${VIGIL_RELEASE:-unset}`, third-party images with the local file's pins, `restart: unless-stopped` (the one-shots `vigil-migrate` and `lab-zabbix-provision` keep `"no"`), secret folders `/srv/vigil/secrets/{unit}` read-only at `/run/secrets/vigil` with `create_host_path: false`; `vigil-app` and `dev-recorder` at `172.30.0.10` (`gw_priority: 1`) and `172.31.0.10` (`gw_priority: 0`), port `10.40.1.10:8080:8080`; `vigil-app`'s command `--vigil.http.bind-address=172.30.0.10 --management.endpoint.health.show-details=always`; `demo-control` on `lab-net` only, `10.40.1.10:8090:8090`, `--demo-control.vigil-url=https://demo.${VIGIL_DEMO_DOMAIN:-demo.invalid}`, `derived.yaml` and the volume `demo-control-state`, the local file's `/dev/tcp` health check; `demo-faults` on the host network as root with `NET_ADMIN`, `NET_RAW`, the Docker socket and `demo-faults-state`; `lab-db-01` `LAB_PROFILE: ${VIGIL_LAB_PROFILE:-demo}`; `lab-zabbix-provision` user 10001 with `WEBHOOK_BASE_URL: ${VIGIL_WEBHOOK_BASE_URL:-https://demo.invalid}` and `/srv/vigil/generated`; Prometheus from `/srv/vigil/deploy/monitoring/prometheus/`, Alertmanager from `/srv/vigil/config/alertmanager.yml`; `dev-recorder` with `vigil-app`'s secret folder and `/srv/vigil/spike/recordings`; the six volumes of item 5. `vigil-embed` is unchanged. | AR-U-01, AR-U-02, AR-U-03, AR-U-06 to AR-U-10, AR-R-02, AR-H-01, AR-H-03, BP-R-08, BP-R-09 |
| **`vigil-db`'s health check** (spec 6.3 item 3), in both compose files: `CMD-SHELL` `psql -h 127.0.0.1 -U postgres -d vigil -tAc "select 1 from pg_roles where rolname = 'vigil_app'" \| grep -qx 1`, every 2 s, 60 retries; the init script from `/srv/vigil/deploy/db/init` (main) and `../db/init` (local). | AR-U-01, AR-U-02 |
| **Deployment** (spec 6.4): `deploy/deploy.sh {installation} {release} [--spike]` checks the images of the profile's services (no profile or the chosen one) and uploads, besides the P-05 files, `db/init/10-vigil-roles.sh`, `monitoring/prometheus/{prometheus.yml,lab-rules.yml}`, `monitoring/alertmanager/alertmanager.yml`, and with `--spike` `spike/demo-data/` (deviation 2); `host-fetch.sh` passes the profile on. `host-deploy.sh {installation} {release} {registry} {profile}`: step 1 writes `.env` with `COMPOSE_PROFILES`, `VIGIL_DEMO_DOMAIN`, `VIGIL_WEBHOOK_BASE_URL`, `VIGIL_LAB_PROFILE` from `values/{installation}.yaml` ("The value {name} is missing in values/{installation}.yaml."); step 1a creates `/srv/vigil/generated` (10001, 0700) and `/srv/vigil/config` (0755) and renders `alertmanager.yml` with `webhook_base_url` (0644, temporary file and rename); with `spike` also `/srv/vigil/spike/recordings` (deviation 3); step 6 first runs `docker compose --profile {other} rm -s -f {services}`; with `spike` step 9 starts nothing. The `printf … \| grep` pipes of `host-deploy.sh`, `host-status.sh`, `host-gpu-status.sh` and `edge.sh` read here-strings. `deploy/fetch.sh` with `deploy/main/host-read.sh` (pieces of at most 18,000 base64 characters of the file's gzip, `END`, the SHA-256 with offset 0; paths outside the three folders refused on both sides). `deploy/secrets.sh` counts the services of every profile (deviation 1). | AR-H-11, AR-H-12 |
| **Values** (spec 6.5): `deploy/values/demo.yaml` holds exactly the 20 values in the spec's order; the header names AR-B-07 and the operator as their owner. | AR-B-07, AR-U-04 |
| **SB-T-01 on the main host** (spec 6.6): `spikes/storm-timing/main-host.sh {installation} {release} [--storms N] [--extra LIST]` refuses unless `deploy/status.sh` shows `dev-recorder` running; `host-spike.sh start` pulls the release's `storm-spike`, removes an earlier container, runs it on `lab-net` with `VIGIL_LAB_PROFILE` of `.env`, the secret folders of `vigil-app` and `demo-control`, `/srv/vigil/deploy/spike/demo-data` and `/srv/vigil/spike/results`; `host-spike.sh state` every 5 minutes; when exited, `deploy/fetch.sh` copies the result to `spikes/results/` and the script exits with the spike's code. `storm_timing.py` is unchanged. | SB-T-01 |
| **The storm driver** (spec 6.7): `storm.py --via local\|control`, `--measured FILE`; with `control` the reset and the storm button through the control panel (`storm_api.Control`), every 1 s, limits 120 s and 135 s, nothing of the lab reached and none of the four lab secrets read; `--via control` needs `--deliveries checklist`; `--acts pages` in mode `script` with `--via control`, printing "acknowledge at 07:30 (T0 + 390 s)" and "approve at 09:00 (T0 + 480 s)"; `VIGIL_JIRA_URL` replaces the Jira fake's fixed address; `storm_checks.measured_values`; the result file gains `measured`; `times` gains `SB-TL-03`, and `SB-TL-04` with `--deliveries checklist` (deviation 5). | SB-T-02 |
| **Playwright** (spec 6.8, 6.10): `control.spec.ts`'s `CONTROL` and `VIGIL` from `VIGIL_CONTROL_URL` and `VIGIL_BASE_URL`; `storm.spec.ts`'s two ticket-link patterns from `VIGIL_JIRA_URL` (escaped); tag `@local` on the four tests of `smoke.spec.ts`, "AR-B-10 a host with no alias shows the error state" and "VD-S-03 the error states"; `live.spec.ts` (`@live`, "AR-SP-01 live updates of vigil-app through the load balancer") with the clock-free delay of BP-A-31 and the criteria `https_origin`, `notices_200`, `max_delay_1000`, `idle_open_295`, `reconnect_5000`, written to `/e2e/run/AR-SP-01.json` (deviation 4); `deploy/local.sh e2e` leaves out `@live`. | BP-R-24, AR-SP-01, AR-ASM-03, AR-TM-10, SB-T-05 |
| **Demo-host tooling** (spec 6.9, `tests/acceptance/demo-host/`): `secrets.sh` (seven secrets, 0444 in 0555, names only, `--remove`), `e2e.sh` (the three URLs, the secrets, `lab-profile`, `--grep-invert "@local\|@ai-down\|@storm\|@control\|@live"` or `--grep @{suite}`), `storm.sh` (`--via control --deliveries checklist`, `--with-playwright`, `--live`, `--measured`), `check.sh` with `check-host.sh` (the 15 checks of item 4), `ai-cut.sh` with `ai-cut-host.sh` (SB-T-09 part one), `sample.sh` with `sample-host.sh` (AR-SP-03). | SL-A3-05, AR-S-08, AR-S-09, AR-ASM-13, AR-H-05, AR-H-09, AR-SP-03, AR-ASM-05, SB-T-09, SB-ASM-03 |
| **Documents of the repository**: `README.md` (the AWS scripts, the demo-host runs, the tags `@local` and `@live`), `CLAUDE.md` (Commands: `fetch.sh`, `--spike`, the runs against the demo installation), `deploy/edge.md` (the P-32 steps), the READMEs of `dev/recorder`, `spikes/storm-timing` and `tests/acceptance/storm`. | BP-DC-06 |

## 2. Tests added

Python (pytest, docstrings start with the ID):

| Test | IDs |
| --- | --- |
| `deploy/test_compose_files.py`: `test_vigil_app`, `test_vigil_db_health`, `test_demo_control`, `test_demo_faults`, `test_lab_hosts`, `test_zabbix`, `test_prometheus_and_alertmanager`, `test_units_and_profiles`, `test_third_party_pins`, `test_validates_without_values` (with `COMPOSE_PROFILES` empty, `vigil` and `spike`); `test_vigil_embed` and `test_network_exposure` changed to `vigil-app` | AR-U-01, AR-U-02, AR-U-03, AR-U-06 to AR-U-10, AR-R-02, AR-S-08, BP-R-08, BP-R-09 |
| `deploy/test_build.py`: `test_build_refusals`, `test_build`, `test_build_mirror`, `test_deploy_uploads`, `test_deploy_images`, `test_deploy_on_the_host` | AR-H-11, BP-W-03, BP-R-04, BP-A-21 |
| `deploy/test_host_deploy.py`: `test_values_in_env`, `test_alertmanager_configuration`, `test_profile_switch`; the sequence tests with profile `vigil` | AR-H-11 |
| `deploy/test_fetch.py`: `test_pieces`, `test_refused_paths` | AR-H-11 |
| `deploy/test_status.py`: `test_health_with_components`, `test_here_strings` | AR-H-12, CTX-D-084 |
| `deploy/test_values.py`: `test_every_value`, `test_gpu_host_ip` | AR-B-07, AR-U-04 |
| `deploy/test_local_script.py`: `test_e2e_leaves_out_live`; the default run's expectation with `@live` | BP-R-24 |
| `dev/recorder/test_recorder.py`: `test_target_group_health` | AR-H-05 |
| `spikes/storm-timing/test_main_host.py`: `test_starts_polls_and_fetches`, `test_spike_exit_code`, `test_refused_without_the_spike_deployment` | SB-T-01 |
| `tests/acceptance/storm/test_storm.py`: `test_control_run`, `test_control_reset_failed`, `test_measured_values`, `test_pages_in_mode_script`; the usage and result-file key tests updated | SB-T-02 |
| `tests/acceptance/demo-host/test_demo_host.py`: `test_e2e_urls`, `test_secrets`, `test_storm_starts_the_suites_first`, `test_ai_cut_verdicts`, `test_sample_criteria`, `test_sampler_on_the_host`, `test_check_verdicts` | BP-R-24, SL-B-14, SB-T-02, SB-T-09, AR-SP-03, AR-S-08 |

Playwright: `tests/e2e/live.spec.ts` (`@live`, demo host only); the lint, Prettier check and type check of `tests/e2e/` pass. Test support: `deploy/conftest.py` copies the new deployment files and a `mvnw` stub; `deploy/tests/fake_aws.py` answers `elbv2 describe-load-balancers`, `describe-target-groups` and `describe-target-health`.

## 3. Deviations from the spec

1. **`deploy/secrets.sh` counts the services of every compose profile** (`docker compose … --profile "*" config --services`). With `COMPOSE_PROFILES=spike` in `.env`, `config --services` leaves out `vigil-app` and `demo-control`, so the spike deployment would not write `/srv/vigil/secrets/vigil-app` and `/srv/vigil/secrets/demo-control`, which the recorder (spec 6.3 item 1: its secret mount as in the local file, `vigil-app`'s folder) and the storm spike (spec 6.6 item 1) mount; the first spike deployment on a host that never ran Vigil (owner-run step 3) could not start `dev-recorder`. The profile `vigil` deployment writes the same folders as before.
2. **`spike/demo-data/` also holds `10-lab-and-faults.yaml`.** Spec 6.4 item 1 names "the README and the nine data files of AR-B-05, the folder the storm spike reads at `/demo-data`", but `storm_timing.py` (unchanged, spec 6.6) reads `10-lab-and-faults.yaml` (the timing targets) besides `03` and `04`; without it the spike stops at its start. Document feedback 1.
3. **Step 1a also creates `/srv/vigil/spike/recordings` (owner 10001, 0755) with the profile `spike`.** The recorder runs as 10001 and writes its recordings there (spec 6.3 item 2); a folder created by Docker would belong to root, and the recorder stops with "output folder … is not writable". Spec 6.4 item 3 lists only `generated` and `config`.
4. **`live.spec.ts` signs in again when the driver's reset ends its session.** Spec 6.10 item 1 opens the two streams before `live-ready`, and `storm.sh` starts the driver after the marker; the driver's reset gives Vigil a new tenant, which ends the session made before it (AR-B-02; P-31's test "AR-B-02 a reset gives Vigil a new tenant"), so the streams would stop with 401 and never reopen. As the suite `storm` does (P-28's "DC-S01-04 the reset ends the session …"), the test reads `GET /api/v1/session` every 1 s; at 401 it signs in again, reopens both streams and measures from them (the result file's `sessions` counts the sign-ins). The streams are opened on the page `/api/v1/public/branding` of the same origin, so `vigil-web`'s own streams and its sign-out page do not interfere.
5. **The driver records `SB-TL-03`, and `SB-TL-04` with `--deliveries checklist`.** Spec 6.9 item 6 takes SB-TL-02 to SB-TL-04 from each result file's `times` for AR-SP-03's criteria `sb_tl_03` and `sb_tl_04`, but the driver recorded no `SB-TL-03` and, without the Teams fake, no `SB-TL-04`. `SB-TL-03` is the latest `collected_at` of the storm's diagnostic results; with `--deliveries checklist` `SB-TL-04` is the `started_at` of the step `s-wait-ack`, which starts when the messages have been sent (the step executions of AR-I-29 carry no end time). With `--deliveries fakes` `SB-TL-04` stays the first Acknowledge message, as before.

Readings that change no stated behaviour (complete list):

1. The result file's `reset` keeps its four keys with `--via control`; `total_seconds` is the control panel's `seconds`.
2. With `--acts pages` in mode `script`, each page's 300 s limit counts from its script time (T0 + 390 s and T0 + 480 s), so the owner's acknowledgement at 07:30 and approval at 09:00 fall inside it; the "Acknowledge link by 07:45" and "Approval link by 09:30" checks are those of mode `script`.
3. `e2e.sh … live` uses its own run folder `~/.vigil-demo/{installation}/e2e-live`, because it runs beside the suite `storm` and Playwright empties its output folder at start; the containers are named `vigil-demo-e2e-{suite or default}` so `storm.sh` can stop them.
4. `check.sh`: `secrets-readable` expects every unit of `units.yaml` with secrets except the one-shots `vigil-migrate` and `lab-zabbix-provision` (they have ended; `deploy/status.sh` checks running units only); `release` compares `deployed.yaml`'s release with `.env`'s `VIGIL_RELEASE`, requires the profile `vigil` and lists every service of the deployment except the one-shots; the TLS check uses `/usr/bin/curl` as P-05 (`VIGIL_TLS_CURL` for tests).
5. `ai-cut-host.sh`: 20 rounds (at 0, 30, …, 570 s), each attempt with 5 s in all (connection and handshake); a blocked kind passes when every attempt failed, a reachable kind when every attempt connected; the Teams host is passed to the container on its standard input, and its attempts print only the error's class.
6. `sample.sh`: a storm's window ends at T0 + `SB-TL-10` (else the result's `finished_at`); `sb_tl_02` is SB-TL-02 at most 45 s after SB-TL-01 and not before T0 + 40 s, `sb_tl_03` SB-TL-03 at most 15 s after SB-TL-02, `sb_tl_04` SB-TL-04 at most 60 s after SB-TL-03 (SB-TL-02 to SB-TL-04 of `30-jazz-storyboard` Section 4); `storms_3` at least three storms with samples; the CSV is fetched into a temporary folder and the samples are summarised per storm in the JSON.
7. `main-host.sh` keeps polling when one state read fails, and stops after 12 failed reads in a row (an hour).

## 4. Document feedback (CTX-Q-8)

1. P-32 spec 6.4 item 1: the storm spike reads `10-lab-and-faults.yaml` from `/demo-data` (`storm_timing.load_data`), so `spike/demo-data/` needs it beside the README and the nine data files of AR-B-05 (deviation 2).
2. P-32 spec 6.9 item 6 and 6.7: the driver's `times` had no SB-TL-03 and, with `--deliveries checklist`, no SB-TL-04 (deviation 5); the step executions of AR-I-29 carry only `started_at`.
3. P-32 spec 6.10 with 6.9 item 3: a page signed in before the driver's reset loses its session at the reset (deviation 4).
4. P-32 spec 6.3/6.4: the profile `spike` needs `vigil-app`'s and `demo-control`'s secret folders, which `secrets.sh` wrote only for the services of the active profile (deviation 1); and the recorder's folder (deviation 3).

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 15 November 2026)

None. `release.yml` uses the pins of `ci.yml` (`actions/setup-java` `de7274f…` v6.0.1 with Temurin `25.0.4+101.0.LTS`, `actions/setup-node` `949feb2…` v7.1.0 with Node.js `22.23.3`); `deploy/main/compose.yaml` uses the pins of `deploy/local/compose.yaml`.

## 7. Values recorded under `docs/`

None yet: the DD-F-10 `lab_db_server` final values come from the owner-run SB-T-01 (spec Section 9) and are recorded by the session "Record the owner-run results of P-32".

## 8. Owner-run results

Open. The owner runs spec Section 6.11 steps 1 to 11 on the Mac and AWS and commits the result files; then a cloud session "Record the owner-run results of P-32" fills this section, DD-F-10 and the SL-CP-1 section.

## 9. Lines of production code (`tools/loc.sh origin/main HEAD`)

316 (size S; spec estimate about 450). `deploy/` 314 (of them `deploy/conftest.py` 21, test support that `tools/loc.sh` counts), `dev/recorder/recorder.py` 2. Not counted (BP-R-05): the compose files, `release.yml`, and the tooling under `tests/` and `spikes/`.

## 10. Gate command result

`tools/check-all.sh` in this session on `0e8c7a8` (the build commit; later commits change only this report and the argument loop of `tests/acceptance/demo-host/sample.sh`, whose tests and `shellcheck` were run again): **all checks passed** — step 2 `BUILD SUCCESS`, step 3 with the lab tests (the branch changes `deploy/local/`, `dev/recorder/` and `spikes/storm-timing/`): 605 passed, 1 skipped, 6 deselected (12 min 18 s); steps 4 to 7 passed.

Results of done-check item 3 (lab profile `test`): the suite `control`'s measured values: "Clear all faults" 40.196 s with quiet 28.026 s; reset after SL-F-1, SL-F-2 and SL-F-4 40.309 s (branch A 40.297 s, branch B 1.087 s); four more resets 12.161 to 12.187 s. The driver's run `storm-immediate-20261115T103448Z.json` (not committed: a cloud run).

## 11. Follow-up items for later packages

- The SL-CP-1 review's parts BP-CR-01, BP-CR-03, BP-CR-04 and BP-CR-06 (Claude project, after the recording session).
- `tests/acceptance/p05_host_check.sh` still names the container `sse-probe`, which left the main host's compose file; it is P-05's owner-run record and is not run again.

## 12. SL-CP-1 review (`60-build-plan` Section 8.3)

### BP-CR-02 (build session)

`tools/trace.py --check` passes: every row with proof `test` in the part 4 tables of the specs has a test that names its ID. `tools/trace.py` lists the IDs of the earlier documents that no test names and no spec of a package with a report on `main` marks "checked by review" (on this branch, 15 November 2026):

| Prefix | IDs listed |
| --- | --- |
| CTX | 106 |
| SL | 82 |
| PM | 203 |
| DM | 152 |
| DC | 147 |
| AR | 87 |
| SB | 112 |
| VD | 6 |
| DD | 30 |

<details><summary>The full listing</summary>

```text
CTX: 106 without a test or a review mark
  CTX-Q-1
  CTX-Q-2
  CTX-Q-3
  CTX-Q-4
  CTX-Q-5
  CTX-Q-6
  CTX-Q-7
  CTX-Q-8
  CTX-D-001
  CTX-D-002
  CTX-D-003
  CTX-D-004
  CTX-D-005
  CTX-D-006
  CTX-D-007
  CTX-D-008
  CTX-D-009
  CTX-D-010
  CTX-D-011
  CTX-D-012
  CTX-D-013
  CTX-D-014
  CTX-D-015
  CTX-D-016
  CTX-D-017
  CTX-D-018
  CTX-D-019
  CTX-D-020
  CTX-D-021
  CTX-D-022
  CTX-D-023
  CTX-D-024
  CTX-D-025
  CTX-D-026
  CTX-D-027
  CTX-D-028
  CTX-D-029
  CTX-D-030
  CTX-D-031
  CTX-D-032
  CTX-D-033
  CTX-D-034
  CTX-D-035
  CTX-D-036
  CTX-D-037
  CTX-D-038
  CTX-D-039
  CTX-D-040
  CTX-D-041
  CTX-D-042
  CTX-D-043
  CTX-D-044
  CTX-D-045
  CTX-D-046
  CTX-D-047
  CTX-D-048
  CTX-D-049
  CTX-D-050
  CTX-D-051
  CTX-D-052
  CTX-D-053
  CTX-D-054
  CTX-D-055
  CTX-D-056
  CTX-D-057
  CTX-D-058
  CTX-D-059
  CTX-D-060
  CTX-D-061
  CTX-D-062
  CTX-D-063
  CTX-D-064
  CTX-D-065
  CTX-D-066
  CTX-D-068
  CTX-D-069
  CTX-D-070
  CTX-D-071
  CTX-D-072
  CTX-D-073
  CTX-D-074
  CTX-D-075
  CTX-D-076
  CTX-D-077
  CTX-D-078
  CTX-D-079
  CTX-D-080
  CTX-D-081
  CTX-D-082
  CTX-D-083
  CTX-D-085
  CTX-D-086
  CTX-D-087
  CTX-D-088
  CTX-D-089
  CTX-D-090
  CTX-D-091
  CTX-D-092
  CTX-D-093
  CTX-D-094
  CTX-D-095
  CTX-O-01
  CTX-O-02
  CTX-O-03
  CTX-O-04
  CTX-O-05
SL: 82 without a test or a review mark
  SL-A2-01
  SL-A2-02
  SL-A2-06
  SL-A2-08
  SL-A3-08
  SL-A4-06
  SL-A4-07
  SL-A4-08
  SL-A4-09
  SL-A4-10
  SL-A4-11
  SL-WE-01
  SL-WE-02
  SL-WE-03
  SL-WE-04
  SL-WE-05
  SL-WE-06
  SL-WE-07
  SL-WE-08
  SL-WE-09
  SL-X-01
  SL-X-02
  SL-X-03
  SL-X-04
  SL-X-05
  SL-X-06
  SL-X-07
  SL-X-08
  SL-X-09
  SL-X-10
  SL-X-11
  SL-X-12
  SL-X-13
  SL-X-14
  SL-X-15
  SL-X-16
  SL-C-01
  SL-C-02
  SL-C-03
  SL-C-04
  SL-C-05
  SL-C-06
  SL-C-07
  SL-C-08
  SL-C-09
  SL-C-10
  SL-C-11
  SL-C-12
  SL-C-13
  SL-C-14
  SL-C-15
  SL-C-16
  SL-C-17
  SL-C-18
  SL-C-19
  SL-ASM-01
  SL-ASM-03
  SL-O-01
  SL-O-02
  SL-O-03
  SL-O-04
  SL-O-05
  SL-O-06
  SL-O-07
  SL-O-08
  SL-O-09
  SL-O-10
  SL-O-11
  SL-O-12
  SL-O-13
  SL-O-14
  SL-O-15
  SL-O-16
  SL-O-17
  SL-O-18
  SL-O-19
  SL-O-20
  SL-O-21
  SL-O-22
  SL-O-23
  SL-F-4
  SL-CR-01
PM: 203 without a test or a review mark
  PM-M01
  PM-M02
  PM-M03
  PM-M04
  PM-M05
  PM-M06
  PM-M07
  PM-M08
  PM-M09
  PM-M10
  PM-M11
  PM-M12
  PM-M13
  PM-M14
  PM-M15
  PM-M16
  PM-M17
  PM-M18
  PM-M19
  PM-M20
  PM-M21
  PM-M22
  PM-M23
  PM-M24
  PM-M25
  PM-M26
  PM-M27
  PM-M28
  PM-M01-L1
  PM-M01-L2
  PM-M02-L1
  PM-M02-L2
  PM-M03-L1
  PM-M04-L1
  PM-M04-L2
  PM-M04-L3
  PM-M05-L1
  PM-M05-L2
  PM-M06-L1
  PM-M06-L2
  PM-M07-L1
  PM-M08-L1
  PM-M08-L2
  PM-M08-L3
  PM-M09-10
  PM-M09-L1
  PM-M09-L2
  PM-M10-L1
  PM-M10-L2
  PM-M10-L3
  PM-M11-03
  PM-M11-04
  PM-M11-05
  PM-M11-06
  PM-M11-08
  PM-M11-L1
  PM-M11-L2
  PM-M11-L3
  PM-M11-L4
  PM-M12-09
  PM-M12-L1
  PM-M12-L2
  PM-M13-L1
  PM-M13-L2
  PM-M14-L1
  PM-M14-L2
  PM-M14-L3
  PM-M15-L1
  PM-M16-L1
  PM-M16-L2
  PM-M17-L1
  PM-M18-L1
  PM-M18-L2
  PM-M19-L1
  PM-M19-L2
  PM-M20-L1
  PM-M20-L2
  PM-M21-L1
  PM-M21-L2
  PM-M22-01
  PM-M22-02
  PM-M22-L1
  PM-M22-L2
  PM-M23-L1
  PM-M24-L1
  PM-M24-L2
  PM-K-11
  PM-K-12
  PM-K-13
  PM-K-14
  PM-WV-01
  PM-WV-02
  PM-WV-03
  PM-WV-04
  PM-I-01
  PM-I-02
  PM-I-03
  PM-I-04
  PM-I-05
  PM-I-08
  PM-I-09
  PM-I-10
  PM-I-11
  PM-I-12
  PM-I-13
  PM-I-14
  PM-I-15
  PM-I-16
  PM-I-17
  PM-I-18
  PM-I-19
  PM-I-20
  PM-I-21
  PM-I-22
  PM-I-23
  PM-I-24
  PM-I-25
  PM-I-26
  PM-I-27
  PM-I-28
  PM-I-29
  PM-I-30
  PM-I-31
  PM-I-32
  PM-I-33
  PM-I-34
  PM-I-35
  PM-I-36
  PM-I-37
  PM-I-38
  PM-I-39
  PM-I-40
  PM-I-41
  PM-I-42
  PM-I-43
  PM-EV-01
  PM-EV-02
  PM-EV-03
  PM-EV-04
  PM-EV-05
  PM-EV-06
  PM-EV-07
  PM-EV-08
  PM-EV-09
  PM-EV-10
  PM-EV-11
  PM-EV-12
  PM-EV-13
  PM-EV-14
  PM-T-L1
  PM-T-L2
  PM-T-L3
  PM-T-L4
  PM-T-L5
  PM-DC-01
  PM-DC-02
  PM-DC-03
  PM-DC-04
  PM-DC-05
  PM-DC-06
  PM-EX-01
  PM-EX-02
  PM-EX-03
  PM-EX-04
  PM-EX-05
  PM-EX-06
  PM-EX-07
  PM-EX-08
  PM-EX-09
  PM-EX-10
  PM-D-01
  PM-D-02
  PM-D-03
  PM-D-04
  PM-D-05
  PM-D-06
  PM-D-07
  PM-D-09
  PM-D-10
  PM-D-11
  PM-D-12
  PM-D-13
  PM-D-14
  PM-ASM-01
  PM-ASM-02
  PM-ASM-04
  PM-O-01
  PM-O-02
  PM-O-03
  PM-O-04
  PM-O-05
  PM-O-06
  PM-O-07
  PM-O-08
  PM-O-09
  PM-O-10
  PM-O-11
  PM-O-12
  PM-O-13
  PM-O-14
  PM-O-15
  PM-O-16
  PM-O-17
DM: 152 without a test or a review mark
  DM-A-01
  DM-A-02
  DM-A-03
  DM-A-04
  DM-A-05
  DM-A-06
  DM-A-07
  DM-A-08
  DM-A-09
  DM-A-10
  DM-A-11
  DM-A-12
  DM-A-13
  DM-A-14
  DM-A-15
  DM-A-16
  DM-A-17
  DM-A-18
  DM-A-19
  DM-A-20
  DM-A-21
  DM-A-22
  DM-A-23
  DM-A-24
  DM-A-25
  DM-A-26
  DM-A-27
  DM-A-28
  DM-C-09
  DM-V-04
  DM-V-05
  DM-E-46
  DM-E-48
  DM-E-49
  DM-E-81
  DM-R-46
  DM-R-47
  DM-R-48
  DM-R-49
  DM-R-68
  DM-R-59
  DM-SM01-T1
  DM-SM01-T2
  DM-SM01-T3
  DM-SM02-T1
  DM-SM02-T2
  DM-SM02-T3
  DM-SM03-T1
  DM-SM03-T2
  DM-SM03-T3
  DM-SM03-T4
  DM-SM04-T1
  DM-SM04-T2
  DM-SM05-T1
  DM-SM05-T2
  DM-SM05-T3
  DM-SM05-T4
  DM-SM05-T5
  DM-SM05-T6
  DM-SM06-T1
  DM-SM06-T2
  DM-SM06-T3
  DM-SM06-T4
  DM-SM07-T1
  DM-SM07-T2
  DM-SM07-T3
  DM-SM07-T4
  DM-SM07-T5
  DM-SM07-T6
  DM-SM08-T1
  DM-SM08-T2
  DM-SM08-T3
  DM-SM08-T4
  DM-SM08-T5
  DM-SM09-T1
  DM-SM09-T2
  DM-SM09-T3
  DM-SM10-T1
  DM-SM10-T2
  DM-SM10-T3
  DM-SM10-T4
  DM-SM10-T5
  DM-SM10-T6
  DM-SM11-T1
  DM-SM11-T2
  DM-SM11-T3
  DM-SM11-T4
  DM-SM11-T5
  DM-SM11-T6
  DM-SM11-T7
  DM-SM11-T8
  DM-SM12-T1
  DM-SM12-T2
  DM-SM12-T3
  DM-SM12-T4
  DM-SM12-T5
  DM-SM12-T6
  DM-SM12-T7
  DM-SM12-T8
  DM-SM12-T9
  DM-SM12-T10
  DM-SM13-T1
  DM-SM13-T2
  DM-SM13-T3
  DM-SM13-T4
  DM-SM15-T1
  DM-SM15-T2
  DM-SM15-T3
  DM-D-01
  DM-D-02
  DM-D-03
  DM-D-04
  DM-D-05
  DM-D-06
  DM-D-07
  DM-D-08
  DM-D-10
  DM-D-11
  DM-D-12
  DM-D-13
  DM-D-14
  DM-D-15
  DM-D-16
  DM-D-17
  DM-D-18
  DM-D-19
  DM-D-20
  DM-D-21
  DM-ASM-01
  DM-ASM-02
  DM-ASM-03
  DM-ASM-04
  DM-ASM-05
  DM-ASM-06
  DM-ASM-08
  DM-L-01
  DM-L-02
  DM-L-03
  DM-O-01
  DM-O-02
  DM-O-03
  DM-O-04
  DM-O-05
  DM-O-06
  DM-O-07
  DM-O-08
  DM-O-09
  DM-O-10
  DM-O-11
  DM-O-12
  DM-O-13
  DM-O-14
DC: 147 without a test or a review mark
  DC-A-01
  DC-A-02
  DC-A-08
  DC-A-09
  DC-A-10
  DC-A-11
  DC-A-12
  DC-A-18
  DC-R-01
  DC-R-02
  DC-R-03
  DC-R-04
  DC-R-05
  DC-R-07
  DC-M-01
  DC-M-02
  DC-M-03
  DC-M-04
  DC-M-05
  DC-M-06
  DC-M-07
  DC-M-08
  DC-M-09
  DC-M-10
  DC-M-11
  DC-M-12
  DC-M-13
  DC-M-14
  DC-M-15
  DC-M-16
  DC-M-17
  DC-M-18
  DC-M-19
  DC-M-20
  DC-M-21
  DC-M-22
  DC-M-23
  DC-M-24
  DC-M-25
  DC-M-26
  DC-M-27
  DC-M-28
  DC-M-29
  DC-M-30
  DC-M-31
  DC-M-32
  DC-M-33
  DC-M-34
  DC-M-35
  DC-M-36
  DC-M-37
  DC-M-38
  DC-M-39
  DC-M-40
  DC-M-41
  DC-M-42
  DC-M-43
  DC-U-14
  DC-U-16
  DC-S06-01
  DC-S06-02
  DC-S06-03
  DC-S06-04
  DC-S06-07
  DC-S06-08
  DC-S06-09
  DC-S06-10
  DC-S06-11
  DC-S06-12
  DC-S06-13
  DC-S06-14
  DC-S06-15
  DC-S06-16
  DC-S07-01
  DC-S07-02
  DC-S07-03
  DC-S07-04
  DC-S07-05
  DC-S08-01
  DC-S08-02
  DC-S08-03
  DC-S08-04
  DC-S08-05
  DC-S09-01
  DC-S09-02
  DC-S09-03
  DC-S09-04
  DC-S09-05
  DC-S10-01
  DC-S10-02
  DC-S10-03
  DC-S10-04
  DC-S10-05
  DC-S11-01
  DC-S11-02
  DC-S11-03
  DC-S11-04
  DC-L-01
  DC-L-02
  DC-L-03
  DC-L-04
  DC-L-05
  DC-L-06
  DC-L-07
  DC-L-08
  DC-L-09
  DC-L-10
  DC-L-11
  DC-L-12
  DC-L-13
  DC-L-14
  DC-D-01
  DC-D-02
  DC-D-03
  DC-D-04
  DC-D-05
  DC-D-06
  DC-D-07
  DC-D-08
  DC-D-09
  DC-D-10
  DC-D-11
  DC-D-12
  DC-D-13
  DC-D-14
  DC-D-15
  DC-D-16
  DC-D-17
  DC-A-03
  DC-A-04
  DC-A-05
  DC-A-06
  DC-A-07
  DC-A-13
  DC-A-14
  DC-A-15
  DC-A-16
  DC-A-17
  DC-A-19
  DC-ASM-01
  DC-ASM-02
  DC-ASM-03
  DC-ASM-04
  DC-O-01
  DC-O-02
  DC-O-03
  DC-O-04
AR: 87 without a test or a review mark
  AR-A-01
  AR-A-02
  AR-A-03
  AR-A-04
  AR-A-05
  AR-A-06
  AR-A-07
  AR-A-08
  AR-A-21
  AR-A-22
  AR-A-24
  AR-U-05
  AR-M-25
  AR-M-26
  AR-M-27
  AR-M-28
  AR-E-12
  AR-I-26
  AR-I-40
  AR-I-41
  AR-I-42
  AR-I-43
  AR-I-44
  AR-I-45
  AR-I-46
  AR-I-47
  AR-I-48
  AR-I-49
  AR-I-50
  AR-I-55
  AR-I-56
  AR-I-60
  AR-I-61
  AR-I-62
  AR-I-63
  AR-I-64
  AR-I-70
  AR-I-71
  AR-I-72
  AR-I-73
  AR-I-74
  AR-I-75
  AR-I-81
  AR-I-82
  AR-W-04
  AR-W-05
  AR-S-10
  AR-D-01
  AR-D-02
  AR-D-03
  AR-D-04
  AR-D-05
  AR-D-06
  AR-D-07
  AR-D-08
  AR-D-09
  AR-D-10
  AR-D-11
  AR-D-12
  AR-D-13
  AR-D-14
  AR-D-15
  AR-D-16
  AR-D-18
  AR-D-17
  AR-A-09
  AR-A-10
  AR-A-11
  AR-A-12
  AR-A-13
  AR-A-14
  AR-A-15
  AR-A-16
  AR-A-17
  AR-A-18
  AR-A-19
  AR-A-20
  AR-A-23
  AR-ASM-03
  AR-ASM-05
  AR-ASM-07
  AR-ASM-09
  AR-ASM-13
  AR-L-01
  AR-L-02
  AR-L-03
  AR-O-01
SB: 112 without a test or a review mark
  SB-SET-01
  SB-SET-02
  SB-SET-03
  SB-SET-04
  SB-SET-05
  SB-SET-06
  SB-SET-07
  SB-SET-08
  SB-S00
  SB-S01
  SB-S02
  SB-S03
  SB-S04
  SB-S05
  SB-S06
  SB-S07
  SB-S09
  SB-S10
  SB-TL-01
  SB-TL-02
  SB-TL-03
  SB-TL-04
  SB-TL-05
  SB-TL-06
  SB-TL-07
  SB-TL-08
  SB-TL-09
  SB-TL-10
  SB-R-01
  SB-R-02
  SB-R-03
  SB-R-04
  SB-R-05
  SB-R-06
  SB-V-01
  SB-V-02
  SB-V-03
  SB-V-04
  SB-V-05
  SB-V-06
  SB-V-07
  SB-V-08
  SB-V-09
  SB-V-10
  SB-V-11
  SB-V-12
  SB-V-13
  SB-V-14
  SB-V-15
  SB-V-16
  SB-V-17
  SB-V-18
  SB-V-19
  SB-V-20
  SB-V-21
  SB-V-22
  SB-V-23
  SB-V-24
  SB-V-25
  SB-V-26
  SB-V-27
  SB-V-28
  SB-V-29
  SB-V-30
  SB-V-31
  SB-V-32
  SB-V-33
  SB-V-34
  SB-V-35
  SB-V-36
  SB-V-37
  SB-V-38
  SB-V-39
  SB-V-40
  SB-V-41
  SB-V-42
  SB-T-08
  SB-T-10
  SB-D-01
  SB-D-02
  SB-D-03
  SB-D-04
  SB-D-05
  SB-D-06
  SB-D-07
  SB-D-08
  SB-D-09
  SB-D-10
  SB-D-11
  SB-D-12
  SB-D-13
  SB-A-01
  SB-A-02
  SB-A-03
  SB-A-04
  SB-A-05
  SB-A-06
  SB-A-08
  SB-A-07
  SB-ASM-01
  SB-ASM-02
  SB-ASM-03
  SB-O-01
  SB-O-02
  SB-O-03
  SB-D01
  SB-D02
  SB-D03
  SB-D04
  SB-D05
  SB-D06
  SB-D07
VD: 6 without a test or a review mark
  VD-D-01
  VD-D-02
  VD-D-03
  VD-D-04
  VD-D-05
  VD-ASM-01
DD: 30 without a test or a review mark
  DD-O-01
  DD-O-02
  DD-F-00
  DD-F-11
  DD-F-12
  DD-R-01
  DD-R-02
  DD-R-03
  DD-R-04
  DD-R-05
  DD-R-06
  DD-R-08
  DD-R-09
  DD-R-11
  DD-K-01
  DD-K-02
  DD-K-07
  DD-K-08
  DD-K-09
  DD-K-10
  DD-K-12
  DD-K-13
  DD-K-14
  DD-K-15
  DD-K-16
  DD-ASM-01
  DD-ASM-03
  DD-ASM-05
  DD-O-03
  DD-O-04
```

</details>

The review of this list is BP-CR-02's second half, made by the Claude project.

### BP-CR-05 (build session)

P-32 builds only what its spec lists: deployment files, scripts and test tooling of the demo installation, under `deploy/`, `dev/recorder/`, `spikes/storm-timing/`, `tests/` and `.github/workflows/release.yml`. It adds no screen element, operation, field, setting or behaviour to Vigil (`app/`, `web/` and the OpenAPI file are unchanged) or to the control panel and fault agent, and no item to list A or B of `01-scope-lock`. The five deviations of Section 3 change only how the test tooling and the spike deployment work; none adds a product behaviour.

### BP-CR-01, BP-CR-03, BP-CR-04, BP-CR-06

Open: written by the Claude project after the recording session, with the owner's decision on change requests (SL-CR-03).
