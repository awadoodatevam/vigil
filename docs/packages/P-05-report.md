# P-05 — Report: AWS edge, hosts and deployment scripts (spike AR-SP-01)

| Field | Value |
| --- | --- |
| Package | P-05 AWS edge, hosts and deployment scripts (spike AR-SP-01) (spec `docs/packages/P-05.md`) |
| Branch | `p-05-aws-edge-hosts-deployment` |
| Pin date (BP-R-08) | 17 October 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` (setup version 1, Temurin 25.0.4.1+1, Docker 29.8.2, Compose 2.40.3) |
| State | Built and merged (pull request #6, squash-merged on 17 October 2026 before the owner-run steps, deviation 10); the gate command passed in the build session and the `ci.yml` job `gate` passed on #6. Owner-run steps of spec Section 6.13 done on 18 October 2026: every `PASS`/`FAIL` line of `P-05-aws.txt` reads `PASS`; AR-SP-01 passed on its second run (deviation 11); the GPU host is not created while BP-OT-09 is pending. Results recorded on 18 October 2026 by the session "Record the owner-run results of P-05"; they reach `main` through a second pull request from this branch. |

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

The rows of spec part 4 with proof "checked by review" (AR-H-01 to AR-H-06, AR-H-08, AR-H-09, AR-H-13, AR-S-09, AR-T-19, SL-A3-05, BP-W-03, BP-A-06, BP-R-12) are built as listed above; their review evidence is the owner-run result file `tests/acceptance/results/P-05-aws.txt` (Section 8) and, for BP-W-03 and BP-R-12, the files themselves. AR-H-02 shows that BP-OT-09 is pending (the GPU host is not created).

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

Changed in the results session (18 October 2026): `test_models_update` now writes its own copy of `models.lock` (spec Section 7: "a copy of `models.lock`") instead of reading the repository's, which holds the recorded `embeddinggemma` digest since step 11 and made the test fail.

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
| 10 | Pull request #6 was squash-merged into `main` on 17 October 2026, before the owner-run steps of spec Section 6.13, contrary to BP-L-5 ("They run on the branch before the merge", spec Section 1). The results are committed to `p-05-aws-edge-hosts-deployment` and reach `main` through a second pull request from this branch, "P-05: owner-run results" (with a merge of `main` into the branch, so that it carries only the results and this report). | Owner decision, 18 October 2026. |
| 11 | AR-SP-01 ran twice. Run 1 (`spikes/results/P-05-AR-SP-01-run1.json`) failed only `max_delay_1000`; run 2 (`spikes/results/P-05-AR-SP-01.json`, the file spec 6.13 step 8 names) passed every criterion. Both files are kept. | Owner decision under P05-DC-02 after run 1, 18 October 2026: run again and keep both files (document feedback 3). |

## 4. Document feedback (CTX-Q-8)

1. Spec 6.4 step 11 has no message for a quota or capacity refusal of the main host (step 16 has them for the GPU host). Proposal: none needed for P-05 (deviation 3 prints the AWS CLI error); a later spec may add a message.
2. `.gitleaksignore` (deviation 9) is a top-level entry that `60-build-plan` Section 5.1 (complete list) does not name. Proposal: add it to Section 5.1 as "Other" (false positives of `gitleaks`, one fingerprint per line).
3. AR-SP-01 run 1 (P05-DC-02): 4 of 200 notices took longer than 1 s (1,016.7, 1,190.5, 1,206.2 and 1,258.1 ms), all within about 35 s around 276 to 311 s after the start, around the server's close of the first connections at 300 s and the reconnections; the median was 141 ms, the 95th percentile 318.6 ms, the smallest round trip 233.2 ms, and every other criterion passed. Run 2, half an hour later from the same browser, passed with a largest delay of 399.2 ms (median 141 ms). The 1 s limit of AR-I-04 ("A notice MUST reach the browser within 1 s") is a maximum over every notice, so one short slow period on the path from Pakistan fails a run. Proposal: keep AR-I-04 and the criterion as they are for P-05 (run 2 proves AR-ASM-03 for the probe part); P-32 repeats AR-SP-01 with `vigil-app`, and when a run there fails only `max_delay_1000` again, the Claude project decides whether the criterion becomes a stated percentile with a separate maximum, or whether a run may be repeated.

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

Spec Section 6.13, run by the owner on 18 October 2026 from the Mac (`aws-cli/2.38.1`, as the BP-OT-03 administrator user), at Cloudflare, and in a browser in Pakistan (Chrome 155 on macOS). Result files committed to the branch: `tests/acceptance/results/P-05-aws.txt`, `spikes/results/P-05-AR-SP-01.json` (run 2), `spikes/results/P-05-AR-SP-01-run1.json` (run 1), and `deploy/models.lock` with the `embeddinggemma` digest.

### 8.1 Steps

| Step | Result |
| --- | --- |
| 0 | Prerequisites done. BP-OT-09: the quota "Running On-Demand G and VT instances" in `us-east-1` was 0; an increase to 4 was requested on 18 October 2026 (request `effc6d78f3bc48a0b112f78dc1debfc9yUH9mqXn`, state `PENDING`). |
| 1 to 3 | `deploy/edge.sh demo` exited 4 with the four name servers, the `NS` records were added at Cloudflare, and the runs after the delegation exited 3 (`The GPU host was not created: VcpuLimitExceeded.`, BP-OT-09 pending) with both targets registered (`target vigil-app/vigil-demo-main` and `target demo-control/vigil-demo-main`, not `skipped`). In part `edge` of `P-05-aws.txt` every resource line reads `exists` or `set`. |
| 4 | The push role that `VIGIL_PUSH_ROLE_ARN` takes is `arn:aws:iam::653271908574:role/vigil-demo-github-push` (summary of part `edge`); the variable itself is not in the result files. |
| 5 | `deploy/new-installation.sh demo`: the 21 parameters exist (part `parameters`: count 21, names only). |
| 6 | `deploy/build.sh p05-1`: `demo/sse-probe` holds the tag `p05-1` (part `ecr-and-bucket`). |
| 7 | `deploy/models.sh update embeddinggemma` recorded the digest in `deploy/models.lock`, and the deployment of `p05-1` completed: `deployed.yaml` holds `release: "p05-1"` and `deployed_at: "2026-10-18T11:08:22Z"`, and `deploy/status.sh` shows the digest matching `models.lock` (part `status`). |
| 8 | AR-SP-01: run 1 failed `max_delay_1000`, run 2 passed (Section 8.3). |
| 9 | `tests/acceptance/p05_aws_check.sh demo` (11:36 UTC): every check `PASS` (Section 8.2). |
| 10, 11 | Step 10 (stop the main host, CTX-D-041) is the owner's and is not in the result files; step 11: the result files and `deploy/models.lock` are in commit `0f6e417` on the branch. |

### 8.2 `tests/acceptance/results/P-05-aws.txt`

| Item | Value |
| --- | --- |
| Checks | `PASS http-redirect`, `PASS https-demo`, `PASS actuator-404`, `PASS control-502`, `PASS unknown-host-404`, `PASS tls-1.1-refused`, `PASS AR-ASM-09`, `PASS embedding`, `PASS DOCKER-USER`, `PASS clock` (10 of 10) |
| HTTPS answers (part `https`) | `http://demo…/` 301 with `Location: https://demo.vigil.solviclabs.com:443/`; `https://demo…/` 200; `https://demo…/actuator/health` 404; `https://control…/` 502; the load balancer's own name 404; `/usr/bin/curl --tlsv1.0 --tls-max 1.1` exit 35, `LibreSSL/3.3.6: error:1404B42E:SSL routines:ST_CONNECT:tlsv1 alert protocol version` |
| Zones A and B | `us-east-1a` (subnet `vigil-demo-a`, `10.40.1.0/24`, with the reservations `10.40.1.8/29` and `10.40.1.16/29`), `us-east-1b` (subnet `vigil-demo-b`, `10.40.2.0/24`) |
| Main host | `i-0512f130bee88b091`, `m7i.xlarge`, image `ami-02a9cdd4e98bc3c78` (`ubuntu/images/hvm-ssd-gp3/ubuntu-noble-24.04-amd64-server-20261017`), `us-east-1a`, `10.40.1.10`, root volume 100 GiB `gp3`, `HttpTokens required`, instance profile `vigil-demo-main` |
| GPU host | `gpu host: not created` (BP-OT-09 pending) |
| Bootstrap marker | `bootstrap main 1 docker 29.1.3 compose 2.40.3` |
| Clock | `chronyc tracking`: reference `A9FEA97B (169.254.169.123)`, stratum 2; `timedatectl`: `Time zone: UTC (UTC, +0000)`, synchronized |
| `DOCKER-USER`, `daemon.json` | `-A DOCKER-USER -s 172.31.0.0/24 -d 172.30.0.10/32 -m conntrack --ctstate NEW -j DROP`; `{"log-driver": "local", "log-opts": {"max-size": "10m", "max-file": "5"}}` |
| Load balancer | `vigil-alb`, internet-facing, both subnets, security group `vigil-demo-alb`, `idle_timeout.timeout_seconds = 120`; listener 443 HTTPS with `ELBSecurityPolicy-TLS13-1-2-2021-06`, default 404; listener 80 redirect `HTTP_301`; rules 10 (`demo…` and `/actuator*` → 404), 20 (`demo…` → `vigil-app`), 30 (`control…` → `demo-control`) |
| Targets | `vigil-app` `i-0512f130bee88b091:8080` `healthy` (the probe answers `/actuator/health/liveness`); `demo-control` `i-0512f130bee88b091:8090` `unhealthy`, by design until P-32: no unit listens on port 8090 yet, which is what the check `control-502` expects |
| Security groups | `vigil-demo-alb`: TCP 443 and 80 from `0.0.0.0/0`; `vigil-demo-main`: TCP 8080 and 8090 from `vigil-demo-alb`; `vigil-demo-gpu`: TCP 11434 from `10.40.1.10/32` |
| Roles | `vigil-demo-main` and `vigil-demo-gpu` with `AmazonSSMManagedInstanceCore` and their inline policies, each with the explicit `Deny` of parameter reads of spec 6.4; `vigil-demo-github-push` trusted only for `repo:awadoodatevam/vigil:ref:refs/heads/main` with audience `sts.amazonaws.com`; OIDC provider `token.actions.githubusercontent.com`, client ID `sts.amazonaws.com` |
| ECR and bucket | six repositories, each `IMMUTABLE`; bucket `vigil-demo-deploy-653271908574` with all four public access blocks, `BucketOwnerEnforced`, `AES256` |
| DNS, certificate, SES | zone `vigil.solviclabs.com` with the four name servers, the three DKIM `CNAME` records, the two validation records and the alias records of `demo` and `control`; certificate for `demo.vigil.solviclabs.com` and `control.vigil.solviclabs.com`, `ISSUED`, DNS validation; SES identity verified for sending, DKIM `SUCCESS` (production access: BP-OT-08, P-07) |
| `deploy/status.sh demo` | `sse-probe` and `vigil-embed` running; `no unit with secrets is running`; `vigil-app health: {"status": "UP"}` (the probe); `embeddinggemma 85462619ee721b466c5927d109d4cb765861907d5417b9109caebc4e614679f1 matches models.lock` |
| `embeddinggemma` | digest `85462619ee721b466c5927d109d4cb765861907d5417b9109caebc4e614679f1` in `deploy/models.lock`; `embedding length 768` from the probe's container over `vigil-net` |

### 8.3 AR-SP-01 (probe part)

Both runs from the same browser in Pakistan on `https://demo.vigil.solviclabs.com/`; `local_address` `172.30.0.10` (the load balancer reached the probe at `vigil-app`'s `vigil-net` address through the published port) and a public `forwarded_for` address in both.

| Value | Run 1 (`P-05-AR-SP-01-run1.json`, 11:19 to 11:29 UTC) | Run 2 (`P-05-AR-SP-01.json`, 11:48 to 11:58 UTC) |
| --- | --- | --- |
| Notices | 200 | 200 |
| `max_delay_ms` | 1,258.1 (4 notices over 1,000 ms: 1,016.7, 1,190.5, 1,206.2, 1,258.1, within about 35 s around 276 to 311 s) | 399.2 |
| Median delay | 141 ms | 141 ms |
| `rtt_min_ms` | 233.2 | 222.4 |
| `offset_ms_start`, `offset_ms_end` | 13.2, 14.1 | −3.2, 3.2 |
| `idle.first_error_after_s` | 300.4 | 300.0 |
| Reconnection gaps (ms) | notices 1,237.8 and 1,237.8; idle 1,237.9 and 1,236.6 | notices 2,022.3 and 1,239.2; idle 2,020.7 and 1,239.8 |
| Criteria | `max_delay_1000` false; `local_address`, `https_origin`, `notices_200`, `idle_open_295`, `reconnect_5000` true | all six true |
| `passed` | false | true |

### 8.4 Assumptions

| Item | State |
| --- | --- |
| AR-ASM-03 (probe part) | Proved by run 2: 200 notices through the load balancer to a browser in Pakistan, each within 1 s (largest 399.2 ms); the idle stream stayed open for 300 s with heartbeats; reconnections within 2.1 s. Run 1 had 4 notices over 1 s (document feedback 3). P-32 repeats it with `vigil-app`. |
| AR-ASM-09 | Proved: `dig +short NS vigil.solviclabs.com @1.1.1.1` names exactly the zone's four name servers (`PASS AR-ASM-09`). |
| AR-ASM-12 (`embeddinggemma`) | Proved for `embeddinggemma`: Ollama's library served it (digest above) and it returns 768 dimensions. `gemma4:12b`: P-06. |
| AR-ASM-13 | P-32 (no unit with secrets runs in P-05). |
| BP-ASM-12 (bootstrap part) | Pending: the GPU host is not created while BP-OT-09 is pending (`gpu host: not created`); a later `deploy/edge.sh demo` run after the quota increase creates it, waits for its bootstrap marker and stops it. |
| BP-ASM-13 | Proved: Canonical's Ubuntu 24.04 image `ami-02a9cdd4e98bc3c78` brought `snapd` and the Systems Manager agent (the bootstrap finished and `run_on_host` reached the host), and the archive offered `docker.io` 29.1.3 (marker `bootstrap main 1 docker 29.1.3 compose 2.40.3`). |

## 9. Lines of production code added and changed (P05-DC-01)

`tools/loc.sh origin/main HEAD` prints **2,531** (at most 3,500).

## 10. Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, setup version 1): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1a builds `vigil/sse-probe:local` with the other test images; step 2: `BUILD SUCCESS` (`vigil-app` 108 `*IT`, `demo-control` unit and `*IT` tests; no Java code changed); step 3: `ruff` clean, 115 Python tests passed and 1 skipped (`test_alertmanager_web_config`, no `bcrypt` in the gate's tools), lab tests included because the branch changes `deploy/`, `spikes/` and `tools/` (CTX-D-036); step 4: `shellcheck` on every `*.sh`; step 5: 49 data checks, 0 failed; step 6: `deploy/local/compose.yaml`, `deploy/main/compose.yaml` and `deploy/gpu/compose.yaml` valid; step 7: `trace.py --check` exits 0.

GitHub: on pull request #6 the `ci.yml` job `gate` first failed only at its `gitleaks` step (deviation 9; `tools/check-all.sh` itself passed), then passed on the head `3b367ee` (17 October 2026, 13:38 to 13:53 UTC).

Done-check: BP-DC-01 (every part 4 row with proof `test` has a test that names it; the rows checked by review have their evidence in `P-05-aws.txt`, Section 8.2), BP-DC-02 (the session and the runner), BP-DC-03 (nothing beyond the spec; deviations in Section 3), BP-DC-04 (no Java module, table, listener or external call changed), BP-DC-05 (no migration, OpenAPI entry or seed part), BP-DC-06 (`README.md`; `CLAUDE.md` changed by the spec commit) and BP-DC-07 (this report; `tests/acceptance/results/P-05-aws.txt` and `spikes/results/P-05-AR-SP-01.json` exist on the branch) hold. P05-DC-01 holds (2,531). P05-DC-02 holds: AR-SP-01 `passed` is true in `P-05-AR-SP-01.json` (run 2, after the owner's decision on run 1, deviation 11 and document feedback 3) and every `PASS`/`FAIL` line of `P-05-aws.txt` reads `PASS`. P05-DC-03 holds.

## 11. Follow-up items for later packages

1. Owner: merge the second pull request "P-05: owner-run results", which carries the result files, `deploy/models.lock` and this report to `main` (deviation 10).
2. Owner, after BP-OT-09 is granted (request `effc6d78f3bc48a0b112f78dc1debfc9yUH9mqXn`): with the main host running, run `deploy/edge.sh demo` again; it creates the GPU host, waits for its bootstrap marker and stops it, which proves BP-ASM-12 (bootstrap part). P-06 needs it.
3. Owner, after the merge (CTX-D-041): run `release.yml` once on `main` (`VIGIL_PUSH_ROLE_ARN` is set). The release name MUST differ from `p05-1`, which the immutable tag of `demo/sse-probe` already holds.
4. P-06: the first `deploy/gpu.sh start` (it stops at "No digest recorded for gemma4:12b" until `deploy/models.sh update gemma4:12b` records it), and the Jira token expiry that `deploy/secrets.sh` prints (AR-H-07).
5. P-32: the other units in `deploy/main/compose.yaml` (the deployment sequence already has their steps: `docker wait lab-zabbix-provision`, `--generated`, `vigil-app` and `demo-control` last), their images in `deploy/build.sh`'s image list and in `release.yml`'s run, `deploy/values/demo.yaml` beyond `demo_domain`, the AR-ASM-13 check of the secret directories (already in `deploy/status.sh`), AR-SP-01 with `vigil-app` and the lab-to-`vigil-app` check (document feedback 3 if `max_delay_1000` fails again); `--remove-orphans` removes `sse-probe` when it leaves the file; the `demo-control` target becomes healthy once `demo-control` answers `GET /health` on port 8090.
6. P-07: SES production access (BP-OT-08) and mailbox verification; `deploy/edge.md` describes the request.

## Amendment 1 (spec Section 12; CTX-D-042): the push role's OIDC subject

| Field | Value |
| --- | --- |
| Branch | `p-05-a1-oidc-subject` |
| Built by | Claude Code, cloud session in the environment `vigil`, 18 October 2026 |
| State | Built; the gate command passes in this session (with the lab tests, CTX-D-036); the owner-run steps of spec Section 12.5 follow the merge |

### What was built

| Part | Requirement IDs |
| --- | --- |
| `deploy/edge.sh` step 5: the trust document `trust-github` of the role `vigil-{i}-github-push` has the condition `token.actions.githubusercontent.com:sub` = `repo:awadoodatevam@100553969/vigil@1420372759:ref:refs/heads/main` (in place of `repo:awadoodatevam/vigil:ref:refs/heads/main`); the operator `StringEquals` and the condition `token.actions.githubusercontent.com:aud` = `sts.amazonaws.com` are unchanged. `edge.sh` writes the trust policies on every run, so the next run updates the existing role (`role vigil-demo-github-push: set`). | BP-A-06 |
| `release.yml` is unchanged; it assumes the role once the trust policy carries the subject GitHub sends (owner-run steps of spec Section 12.5) | BP-W-03 |

No other file under `deploy/`, `.github/` or `tests/` changed. `tests/acceptance/results/P-05-aws.txt` keeps the old subject: it is the record of the 18 October 2026 run.

### Tests added

| File | Test | IDs |
| --- | --- | --- |
| `deploy/test_units.py` | `test_push_role_subject`: `deploy/edge.sh` contains `repo:awadoodatevam@100553969/vigil@1420372759:ref:refs/heads/main` exactly once and no `repo:awadoodatevam/vigil:` | BP-A-06 |

Also checked in the session (not a gate test): the Python program of `edge.sh` step 5, run with the demo values, writes `trust-github.json` with `{"StringEquals": {"token.actions.githubusercontent.com:aud": "sts.amazonaws.com", "token.actions.githubusercontent.com:sub": "repo:awadoodatevam@100553969/vigil@1420372759:ref:refs/heads/main"}}`.

### Deviations from the spec

None.

### Document feedback (CTX-Q-8)

1. `tools/trace.py --check` reads only the part 4 table ("## 4. Requirements built") of a spec, so the proof `test` of BP-A-06 in the table of Section 12.2 is not checked by it (the test exists and names BP-A-06). Proposal: when amendments with their own requirement tables become usual, `trace.py` (BP-R-15) also reads tables under a heading "Requirements built" inside an amendment section.

### Blocking questions

None.

### Versions introduced (BP-R-08)

None.

### Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **1** (the changed line of `deploy/edge.sh`; size class S).

### Gate command result

Cloud session (`CLAUDE_CODE_REMOTE=true tools/check-all.sh`, setup version 1, 18 October 2026): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 2: `BUILD SUCCESS`; step 3: `ruff` clean, 116 Python tests passed (one more than before: `test_push_role_subject`) and 1 skipped (`test_alertmanager_web_config`, no `bcrypt` in the gate's tools), lab tests included because the branch changes `deploy/` (CTX-D-036); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: the three compose files valid; step 7: `trace.py --check` exits 0. GitHub: the `ci.yml` job `gate` runs on the pull request.

Done-check (spec Section 12.6): BP-DC-01 (BP-A-06 has its test; BP-W-03 waits for the owner-run steps), BP-DC-02 (the session; the runner pending), BP-DC-03 (only the subject and its test changed), BP-DC-04 and BP-DC-05 (nothing of the product changed), BP-DC-06 (no command added or changed) and BP-DC-07 (this section) hold.

### Owner-run results

Recorded by the Claude project (BP-L-8) from the owner's outputs of 18 October 2026, after the merge of pull request #8 (`1af4a2e`):

| Step (spec 12.5) | Result |
| --- | --- |
| 2 | `deploy/edge.sh demo` with the main host stopped: `role vigil-demo-github-push: set`; every other resource line `exists` or `set`; `The GPU host was not created: VcpuLimitExceeded.`; summary `main host: i-0512f130bee88b091 stopped`, `gpu host: not created`; exit 3. `aws iam get-role --role-name vigil-demo-github-push` shows the condition `"token.actions.githubusercontent.com:sub": "repo:awadoodatevam@100553969/vigil@1420372759:ref:refs/heads/main"`. |
| 3 | `release.yml` on `main` with the release `p05-2`: the run succeeded (the owner's report; the run of 18 October 2026 before it, with the old subject, failed with "Not authorized to perform sts:AssumeRoleWithWebIdentity"). |
| 4 | `aws ecr describe-images --repository-name demo/sse-probe`: the tags `p05-2` and `p05-1`. |

BP-W-03 is proved for the probe image (every image: P-32). BP-OT-09: AWS refused the increase of the G quota to 4 on 18 October 2026 (the quota stays 0; the email invites an appeal with a detailed use case), so BP-ASM-12 (bootstrap part) stays pending and the GPU host is not created; P-06 needs it.
