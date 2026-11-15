# P-32 — Report: Demo installation and SL-CP-1 acceptance

| Field | Value |
| --- | --- |
| Package | P-32 Demo installation and SL-CP-1 acceptance (spec `docs/packages/P-32.md`) |
| Branch | `p-32-demo-installation` |
| Pin date (BP-R-08) | 15 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (Temurin 25.0.4.1) |
| State | GATE_STATE |

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

LOC_LINE

## 10. Gate command result

GATE_RESULT

## 11. Follow-up items for later packages

- The SL-CP-1 review's parts BP-CR-01, BP-CR-03, BP-CR-04 and BP-CR-06 (Claude project, after the recording session).
- `tests/acceptance/p05_host_check.sh` still names the container `sse-probe`, which left the main host's compose file; it is P-05's owner-run record and is not run again.

## 12. SL-CP-1 review (`60-build-plan` Section 8.3)

### BP-CR-02 (build session)

TRACE_SECTION

### BP-CR-05 (build session)

P-32 builds only what its spec lists: deployment files, scripts and test tooling of the demo installation, under `deploy/`, `dev/recorder/`, `spikes/storm-timing/`, `tests/` and `.github/workflows/release.yml`. It adds no screen element, operation, field, setting or behaviour to Vigil (`app/`, `web/` and the OpenAPI file are unchanged) or to the control panel and fault agent, and no item to list A or B of `01-scope-lock`. The five deviations of Section 3 change only how the test tooling and the spike deployment work; none adds a product behaviour.

### BP-CR-01, BP-CR-03, BP-CR-04, BP-CR-06

Open: written by the Claude project after the recording session, with the owner's decision on change requests (SL-CR-03).
