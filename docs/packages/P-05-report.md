# P-05 — Report: AWS edge, hosts and deployment scripts (spike AR-SP-01)

| Field | Value |
| --- | --- |
| Package | P-05 AWS edge, hosts and deployment scripts (spike AR-SP-01) (spec `docs/packages/P-05.md`) |
| Branch | `p-05-aws-edge-hosts-deployment` |
| Pin date (BP-R-08) | 17 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.8.2, Compose 2.40.3) |
| State | Built; the gate command passes in this session (with the lab tests, CTX-D-036); the `ci.yml` job `gate` runs on the pull request. The owner-run steps of spec Section 6.13 (AWS, Cloudflare, the browser in Pakistan) are pending; their results are recorded by the session "Record the owner-run results of P-05". |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| `deploy/lib/aws.sh` (sourced by every Mac script): `AWS_REGION=us-east-1`, `AWS_PAGER=""`; `pause` (no sleep with `VIGIL_TEST_FAST=1`); JSON with `python3` (no `--query`); the identity check and its message; the installation and release patterns; the values file reader; the bucket name `vigil-{installation}-deploy-{account id}`; finding an instance by its `Name` tag; `require_running_host` with the messages of spec 6.1; `run_on_host` (wait for `Online`, 60 × 5 s; the POSIX `sh` command with the script and files inline as base64, at most 16 KiB each, in `{VIGIL_ROOT}/run/{random}/` with mode 0700, removed afterwards; `executionTimeout` 3,600 s; polling 730 × 5 s with `InvocationDoesNotExist` as pending; standard output and error printed; the exit code returned; the messages for another final status or no final status) | AR-T-23, AR-H-11, BP-A-21 |
| `deploy/edge.sh {installation}` (spec 6.4, steps 1 to 17 in order): Availability Zones A and B; VPC, internet gateway, subnets with public IPv4 on launch, the CIDR reservations `10.40.1.8/29` and `10.40.1.16/29`, route table with route and associations, all reconciled; the three security groups with their inbound rules reconciled to exactly the spec's lists; roles `vigil-{i}-main` (with the explicit `Deny` of parameter reads outside `/vigil/{i}/`), `vigil-{i}-gpu` (with the `Deny` of every parameter), their instance profiles, the GitHub OIDC provider and the push role limited to `repo:awadoodatevam/vigil:ref:refs/heads/main`; six ECR repositories with `IMMUTABLE` tags; the bucket with the public access block, `BucketOwnerEnforced` and SSE-S3; the hosted zone and its four name servers; the SES identity with Easy DKIM (RSA 2048) and its three DKIM records; the ACM certificate and its validation records; the target groups `vigil-app` and `demo-control`, the load balancer `vigil-alb` (idle timeout 120 s) and listener 80; the main host (`m7i.xlarge`, Canonical's Ubuntu 24.04 image, `10.40.1.10`, 100 GiB `gp3`, IMDSv2, user data, retries while the instance profile propagates, the bootstrap wait) and its two targets; the delegation check (exit 4); the certificate wait; listener 443 with `ELBSecurityPolicy-TLS13-1-2-2021-06` and its three rules reconciled; the alias records; the GPU host (`g6.xlarge`, the Deep Learning Base GPU AMI, `10.40.1.20`, bootstrap wait, stop; exit 3 for `VcpuLimitExceeded` and `InsufficientInstanceCapacity`); the summary. One line per resource as spec 6.4 defines; tags `Name` and `vigil:installation` | AR-H-01, AR-H-02, AR-H-03, AR-H-04, AR-H-05, AR-H-06, AR-H-08, AR-S-09, AR-T-19, AR-T-23, BP-A-06, BP-A-21, SL-A3-05 |
| `deploy/edge.md`: costs; prerequisites; BP-OT-09; `deploy/edge.sh demo` with exit codes 0, 3 and 4; the delegation at Cloudflare; the repository variable `VIGIL_PUSH_ROLE_ARN`; SES production access; starting and stopping the main host until P-32; the order of the first deployment | AR-H-05, AR-H-08, BP-A-06 |
| `deploy/hosts/main-user-data.sh` (UTC; `docker.io`, `chrony`, `python3-yaml`, `python3-bcrypt`, `curl`, `ca-certificates` with `DPkg::Lock::Timeout=600`; Docker 28.0 or later or the bootstrap fails; `apt-mark hold docker.io`; the Amazon Time Sync source; the Compose plugin 2.40.3 checked against the release's checksum file; `daemon.json` with the `local` log driver 10m × 5; `snap install aws-cli --classic` after `snap wait system seed.loaded`; the folders; the unit `vigil-docker-user.service` with the `DOCKER-USER` rule; Docker enabled and restarted; the marker), `deploy/hosts/gpu-user-data.sh` (UTC; `chrony` when missing; the same time source; the Compose plugin; the log settings merged into the image's `daemon.json` by a Python heredoc; Docker restarted; the folders; the marker), `deploy/hosts/marker.sh` | AR-H-09, AR-H-11, AR-H-12, AR-S-08, BP-A-06 |
| `deploy/values/demo.yaml` (`demo_domain: vigil.solviclabs.com`), `deploy/units.yaml` (the 12 units with users and secrets), `deploy/models.lock` (both models `unrecorded`) | AR-S-04, AR-B-07, AR-H-11 |
| `deploy/main/compose.yaml` (project `vigil`: `vigil-net` and `lab-net`; `sse-probe` at `172.30.0.10` / `172.31.0.10` with `gw_priority` 1 / 0, published on `10.40.1.10:8080`; `vigil-embed`), `deploy/gpu/compose.yaml` (project `vigil-gpu`: `vigil-llm` on `10.40.1.20:11434` with one NVIDIA GPU) | AR-U-03, AR-U-04, AR-S-08, AR-H-03 |
| `deploy/new-installation.sh {installation}`: the 21 `SecureString` parameters of spec 6.6 (tokens, 20- and 24-character passwords, the key pairs and the host key made with `ssh-keygen`), only when missing, values by `file://` from a 0700 folder, the pair check before anything is created; `{name}: created` or `kept` | AR-S-13 |
| `deploy/secrets.sh {installation} [--generated]` and `deploy/web_config.py`: the served units from `docker compose config --services`; folders 0500 and files 0400 owned by the unit's user, written through a temporary file and a rename, other files removed except `zabbix-api`; `alertmanager-web-config` derived with bcrypt (rounds 10); the missing-secret message; `generated/` (10001, 0700); with `--generated` the copy of `zabbix-api` and `values/derived.yaml` with the host key's SHA-256 fingerprint; the non-root fallback | AR-S-04, AR-B-07 |
| `deploy/build.sh {release}` (the existing-tag check before any build; login, `docker buildx build --platform linux/amd64 --provenance=false --push`, logout), `deploy/deploy.sh {installation} {release}` (host running, image tags present, the seven files to `s3://{bucket}/releases/{release}/`, `run_on_host` with `deploy/main/host-fetch.sh`), `deploy/main/host-fetch.sh`, `deploy/main/host-deploy.sh` (the 12 steps of spec 6.8: `.env`, `config --quiet`, `secrets.sh`, login, `pull --quiet`, logout, `up -d --remove-orphans` without `vigil-app` and `demo-control`, `docker wait lab-zabbix-provision`, `secrets.sh --generated`, the rest, `deployed.yaml`, the models check) | AR-H-11, AR-T-18, BP-A-21 |
| `deploy/status.sh {installation}` and `deploy/main/host-status.sh` (host states; marker, Docker and Compose versions, deployed release, containers, secret directories, `vigil-app`'s health, the models) | AR-H-12 |
| `deploy/gpu.sh start|stop|status`, `deploy/gpu/host-gpu.sh` (Docker 28 check, `config --quiet`, `pull`, `up -d`, the models check for `vigil-llm`, the warm-up 15 × 10 s), `deploy/gpu/host-gpu-status.sh`, `deploy/models.sh update {model}` with `deploy/hosts/model-update.sh` | AR-G-31, AR-H-11, AR-T-18 |
| `spikes/sse-probe/` (`probe.py`, `index.html`, `Dockerfile`, `README.md`): the probe server of spec 6.11 and the test page with its six criteria and result file | AR-SP-01, SL-A3-05 |
| `tests/acceptance/p05_aws_check.sh` and `p05_host_check.sh` (the 16 parts and the 10 checks of spec 6.12) | AR-H-01 to AR-H-09, AR-S-09, AR-T-19, SL-A3-05, BP-A-06 (owner-run) |
| `.github/workflows/release.yml` (manual run on `main` only, `id-token: write`, the push role from `vars.VIGIL_PUSH_ROLE_ARN`, `deploy/build.sh "$RELEASE"` with the input passed through `env`) | BP-W-03 |
| `tools/check-all.sh` step (3) and `tools/trace.py` read `deploy/` in place of `deploy/local/`; `deploy/images.sh --test` builds `vigil/sse-probe:local`; `README.md` (gate, local images, section "Demo installation on AWS"); `spikes/README.md`, `spikes/results/README.md` | BP-R-12, BP-R-15, BP-R-04, BP-DC-06 |
| Nothing in this package creates a backup | AR-H-13 |

The rows of spec part 4 with proof "checked by review" (AR-H-01 to AR-H-06, AR-H-08, AR-H-09, AR-H-13, AR-S-09, AR-T-19, SL-A3-05, BP-W-03, BP-A-06, BP-R-12) are built as listed above; their review evidence is the owner-run result file `tests/acceptance/results/P-05-aws.txt` (pending) and, for BP-W-03 and BP-R-12, the files themselves.

## 2. Tests added

All in the gate (step 3), with the fakes of `deploy/tests/` (`fake_aws.py` with `bin/aws`, `bin/docker`, `bin/curl`, `keygen-bin/ssh-keygen`) and the shared support `deploy/conftest.py`.

| File | Test | IDs |
| --- | --- | --- |
| `deploy/test_new_installation.py` | `test_first_and_second_run`, `test_incomplete_pair`, `test_usage_and_sign_in` | AR-S-13 |
| `deploy/test_secrets.py` | `test_secret_files`, `test_alertmanager_web_config` (skipped where the test's Python has no `bcrypt`, as in the gate; it passed in this session with a virtual environment that has `bcrypt` 5.0.0) | AR-S-04 |
| `deploy/test_host_deploy.py` | `test_sequence_of_this_package`, `test_sequence_with_provision_and_the_rest`, `test_failed_provision`, `test_models_check` | AR-H-11 |
| | `test_compose_file_rejected` | AR-T-18 |
| `deploy/test_build.py` | `test_build`, `test_deploy_on_the_host` | AR-H-11 |
| | `test_deploy_uploads` | BP-A-21 |
| `deploy/test_models.py` | `test_gpu_host_missing_stop_and_status`, `test_gpu_start`, `test_gpu_start_warm_up_fails` | AR-G-31 |
| | `test_old_docker` | AR-T-18 |
| | `test_models_update` | AR-H-11 |
| `deploy/test_compose_files.py` | `test_vigil_embed` | AR-U-03 |
| | `test_vigil_llm` | AR-U-04 |
| | `test_network_exposure` | AR-S-08 |
| | `test_log_settings`, `test_gpu_daemon_merge` | AR-H-12 |
| `deploy/test_units.py` | `test_units_and_secrets` | AR-S-04 |
| | `test_region` | AR-T-23 |
| `deploy/test_status.py` | `test_stopped_main_host`, `test_running_main_host` | AR-H-12 |
| `spikes/sse-probe/test_probe.py` | `test_fixed_answers`, `test_streams`, `test_image` | AR-SP-01 |
| `tools/test_trace.py` | `test_deploy_tests_count` | BP-R-15 |

The deploy tests also passed as a non-root user in this session (27 passed; the owner checks are skipped then, the "Not root" line is checked). The test page `index.html` was also run once in the session's Chromium against the probe with short timings (200 notices, two reconnections, result file downloaded with every key of spec 6.11); this is not a gate test.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `deploy/conftest.py` holds the shared test support (temporary repository root, fakes on `PATH`, script runs); `deploy/tests/bin/curl` is a symbolic link to the `docker` fake, which takes its command name from how it was called. | Spec Section 7 lists the test files and fakes, not where shared helpers live; neither is production code. |
| 2 | Labels in `deploy/main/host-status.sh`'s output where spec 6.9 names a value without its text: `docker {version}`, `compose {version}`, `vigil-app health: {answer}`, and `no bootstrap marker` when the marker file is missing. | Spec 6.9 lists the parts, not their wording; the labels say what each value is. |
| 3 | `deploy/edge.sh`: a quota or capacity refusal of the main host's launch prints the AWS CLI error and exits 1; the marker line found by the bootstrap wait is not printed. | Spec 6.4 defines these messages and exit 3 for the GPU host only, and lists the lines `edge.sh` prints. |
| 4 | `deploy/edge.sh` reconciles a subnet that is explicitly associated with another route table with `replace-route-table-association`. | Part of reconciling the route table's "two subnet associations" (spec 6.4); `associate-route-table` fails for such a subnet. |
| 5 | When listener 443 is created in a run, `edge.sh` prints `listener 443: created` and no `listener-rules 443` line. | Spec 6.4: "A resource created in this run prints only `created` (its reconciled parts and rules print no further line)". |
| 6 | `deploy/gpu.sh stop` on a `pending` host waits for `running` at most 60 × 10 s, then stops it. | Spec 6.10 names the wait but not its limit; every time limit is counted in attempts (spec 6.1), so the stop wait's limit is used. |
| 7 | The user data reads the installed Docker server version from `dockerd --version`; on a failed step it logs `bootstrap failed at line {n}`, and it ends with the log line `bootstrap done`. | The daemon need not be running before the bootstrap restarts it; the two log lines go only to `/var/log/vigil-bootstrap.log` and help find a failed step. |
| 8 | Ollama image: `0.40.5`, although Docker Hub has a tag `0.40.6` pushed at 00:53 UTC on the pin date. | Ollama's `latest` tag still points to 0.40.5: Ollama retags `latest` only when a GitHub release is published (`.github/workflows/latest.yaml`, `on: release: types: [released]`), so 0.40.6 was a draft, not a release, on the pin date. |
| 9 | A root file `.gitleaksignore` holds one fingerprint: the `private-key` match of `gitleaks` on commit `a2dddaa` (`deploy/test_new_installation.py` line 66), where the test asserted the first and last line of a generated key; the test now builds those lines from two parts. | A false positive (no key is committed) that failed the `ci.yml` step `gitleaks`, which scans every commit of the pull request; the ignore file is gitleaks' own mechanism and avoids rewriting the branch's history. `.gitleaksignore` is not in the layout of `60-build-plan` Section 5.1 (document feedback 2). |

## 4. Document feedback (CTX-Q-8)

1. Spec 6.4 step 11 has no message for a quota or capacity refusal of the main host (step 16 has them for the GPU host). Proposal: none needed for P-05 (deviation 3 prints the AWS CLI error); a later spec may add a message.
2. `.gitleaksignore` (deviation 9) is a top-level entry that `60-build-plan` Section 5.1 (complete list) does not name. Proposal: add it to Section 5.1 as "Other" (false positives of `gitleaks`, one fingerprint per line).

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 17 October 2026)

| Item | Version | Where |
| --- | --- | --- |
| Ollama image | `ollama/ollama:0.40.5@sha256:5d3a2f25f9825628daba94dc0b1d421c7d57d6c03ae5faa9ba050e058bab8241` (index of `linux/amd64` `sha256:0e8ff22ff86b4718cfa886844062117af13b50eb5689cca78279778e15744980` and `linux/arm64` `sha256:fd0266bb2cad4b778c653c58155467bc02de5cbcac679c18e9aefe777aa54478`; deviation 8) | `deploy/main/compose.yaml`, `deploy/gpu/compose.yaml` |
| `aws-actions/configure-aws-credentials` | v6.3.0, `e1253824e5c10ff9df46874f81ed3ec929e19cfd` (15 September 2026) | `.github/workflows/release.yml` |
| `docker/setup-buildx-action` | v4.4.1, `f87e5991a6d7451dcb8d9637bfbc97413f497069` (16 September 2026) | `.github/workflows/release.yml` |
| `actions/checkout` | v7.0.1, `3d3c42e5aac5ba805825da76410c181273ba90b1` (the pin of `ci.yml`) | `.github/workflows/release.yml` |
| Docker Compose plugin on the hosts | 2.40.3 (the pin of `tools/cloud-setup.sh`), checked against the release's `checksums.txt` | `deploy/hosts/*-user-data.sh` |
| `ubuntu:24.04` | the digest of `demo/faults/Dockerfile` | `spikes/sse-probe/Dockerfile` |

For information: `apt-cache policy docker.io` in this cloud session: candidate `29.1.3-0ubuntu3~24.04.2` (the main host's own version is in its bootstrap marker).

## 7. Values recorded under `docs/`

None (spec Section 9: only this report).

## 8. Owner-run results

Pending: spec Section 6.13 steps 0 to 11 on the Mac against AWS, at Cloudflare and in a browser in Pakistan. The session "Record the owner-run results of P-05" adds here, from `tests/acceptance/results/P-05-aws.txt`, `spikes/results/P-05-AR-SP-01.json` and `deploy/models.lock`: the AR-SP-01 values (`max_delay_ms`, `rtt_min_ms`, `first_error_after_s`, the reconnection gaps, `passed`), the instance image IDs and names, the zones A and B, the `PASS`/`FAIL` lines, the `embeddinggemma` digest, and the states of AR-ASM-03 (probe part), AR-ASM-09, AR-ASM-12 (`embeddinggemma`), BP-ASM-12 (bootstrap part) and BP-ASM-13.

## 9. Lines of production code added and changed (P05-DC-01)

`tools/loc.sh origin/main HEAD` prints **2,531** (at most 3,500).

## 10. Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, setup version 1): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1a builds `vigil/sse-probe:local` with the other test images; step 2: `BUILD SUCCESS` (`vigil-app` 108 `*IT`, `demo-control` unit and `*IT` tests; no Java code changed); step 3: `ruff` clean, 115 Python tests passed and 1 skipped (`test_alertmanager_web_config`, no `bcrypt` in the gate's tools), lab tests included because the branch changes `deploy/`, `spikes/` and `tools/` (CTX-D-036); step 4: `shellcheck` on every `*.sh`; step 5: 49 data checks, 0 failed; step 6: `deploy/local/compose.yaml`, `deploy/main/compose.yaml` and `deploy/gpu/compose.yaml` valid; step 7: `trace.py --check` exits 0.

GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review are built as Section 1 lists), BP-DC-03 (nothing beyond the spec; deviations in Section 3), BP-DC-04 (no Java module, table, listener or external call changed), BP-DC-05 (no migration, OpenAPI entry or seed part), BP-DC-06 (`README.md`; `CLAUDE.md` changed by the spec commit) hold; BP-DC-02 holds in the session, the runner pending; BP-DC-07 and P05-DC-02 wait for the owner-run steps. P05-DC-01 holds (2,531). P05-DC-03: the session part holds, the runner pending.

## 11. Follow-up items for later packages

1. Owner: run spec Section 6.13 on the branch before the merge (BP-L-5); then "Record the owner-run results of P-05" in a cloud session.
2. After the merge (CTX-D-041): set `VIGIL_PUSH_ROLE_ARN` (if not done in step 4) and run `release.yml` once on `main`.
3. P-06: the first `deploy/gpu.sh start` (it stops at "No digest recorded for gemma4:12b" until `deploy/models.sh update gemma4:12b` records it), and the Jira token expiry that `deploy/secrets.sh` prints (AR-H-07).
4. P-32: the other units in `deploy/main/compose.yaml` (the deployment sequence already has their steps: `docker wait lab-zabbix-provision`, `--generated`, `vigil-app` and `demo-control` last), their images in `deploy/build.sh`'s image list and in `release.yml`'s run, `deploy/values/demo.yaml` beyond `demo_domain`, the AR-ASM-13 check of the secret directories (already in `deploy/status.sh`), the lab-to-`vigil-app` check of AR-SP-01; `--remove-orphans` removes `sse-probe` when it leaves the file.
5. P-07: SES production access (BP-OT-08) and mailbox verification; `deploy/edge.md` describes the request.
