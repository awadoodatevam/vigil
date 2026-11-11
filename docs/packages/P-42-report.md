# P-42 — Report: Faster gate

| Field | Value |
| --- | --- |
| Package | P-42 Faster gate (spec `docs/packages/P-42.md`) |
| Branch | `p-42-faster-gate` |
| Pin date (BP-R-08) | 11 November 2026 |
| Built by | Claude Code, cloud session in the environment `vigil` |
| State | Built; the gate command passes in this session; on the pull request, the full run passed (after one re-run of the failed lab job) and the data-only run was light and passed (Section 10) |

## 1. What was built

| Part | Requirement IDs |
| --- | --- |
| `tools/gate_scope.py` (spec 6.1, Python 3.12, standard library only): `LAB_PATHS`, `DATA_PATHS`, `DATA_EXCLUDED` as the spec's complete lists; `lab` (prints `lab` or `no-lab`; `lab` without `origin/main`, when a path of `git diff --name-only --no-renames origin/main` or of `git ls-files --others --exclude-standard` matches `LAB_PATHS`, or when one of those `git` commands fails, its standard error passed on); `ci` (the variables checked in the order `EVENT_NAME`, `GITHUB_OUTPUT`, then for a pull request `BASE_SHA`, `HEAD_SHA`, `MERGE_SHA`, `GITHUB_REPOSITORY`, `Missing {name}.` and exit 2; rules 1 to 4 in order; `could not read {command}` for a failing `git` or `gh` or an answer that is not a JSON object with a `check_runs` list; `mode=…` appended to `GITHUB_OUTPUT`; `gate: {mode} ({reason})`); every `git diff` with `--no-renames`; any other arguments: `Usage: tools/gate_scope.py ci|lab`, exit 2 | BP-W-01, BP-R-12 |
| `tools/check-all.sh [--part java|rest]` (spec 6.2): arguments checked before any step (usage line, exit 2); `--part java`: steps (0), (1), (1a) with `deploy/images.sh --test --java`, (2); `--part rest`: steps (0), (1), (1a) with `deploy/images.sh --test`, (3) to (7); each part ends with `== all checks passed`; step (3)'s lab decision is `"$PY" tools/gate_scope.py lab`, and `no-lab` adds `and not lab` and prints `lab tests skipped: no change to the lab (BP-R-12 step (3))`. What each step checks is unchanged. | BP-R-12 |
| `deploy/images.sh --test [--java]` (spec 6.3): `--test --java` builds only `vigil/demo-lab:local`, pulling only the base images of `demo/lab/Dockerfile`, none of `deploy/local/compose.yaml`; `--test` unchanged (the compose file's images, then the six images); usage line `Usage: deploy/images.sh --test [--java]` for any other arguments, before anything else | BP-R-12 |
| `.github/workflows/ci.yml` (spec 6.4): triggers, `concurrency` and the pinned actions unchanged; `permissions` `contents: read`, `checks: read`; the jobs `scope` (5 min; `python3 tools/gate_scope.py ci`, output `mode`), `light` (15 min; data checks and `tools/trace.py --check`), `java` (60 min; Temurin 25, Node.js 22, `tools/check-all.sh --part java`, test reports on failure), `rest` (60 min; Python 3.12, the tools virtual environment, `shellcheck`, `tools/check-all.sh --part rest`), `secrets` (10 min; gitleaks as before) and `gate` (5 min; `if: always()`, needs all five; passes only when `scope` and `secrets` succeeded and the jobs of the chosen mode succeeded, otherwise prints each job's result and exits 1) | BP-W-01 |
| `CLAUDE.md` (the row of `tools/check-all.sh`) and `README.md` (the gate paragraph: the two parts, the lab path list, the light and full gate of `ci.yml`; `deploy/images.sh --test --java`) as spec 6.5 | BP-DC-06 |

Checked by review (spec part 4): CTX-D-067, the two runs of the pull request (Section 10).

## 2. Tests added

| Test file | Tests (docstrings start with) |
| --- | --- |
| `tools/test_gate_scope.py` (new) | BP-W-01: manual run full; data-only pull request light "data only" without asking GitHub; `docs/31-demo-data/`, a `.py` under `docs/`, a `.sh` under `spikes/results/`, a `Dockerfile` and a `compose.yaml` make it full; only data since a passed commit light; data plus `README.md` full naming `README.md`; `docs/31-demo-data/` since the passed commit full; a file moved from `app/` to `docs/` full (since the passed commit, and as the whole pull request); `check_runs` missing full "could not read …"; `gh` failing, answering text or a JSON list full "could not read …"; a failing `git` full "could not read git diff …"; `failure` and `cancelled` skipped for an older `success`; a `success` of another app does not count; no passed commit full; at most 20 commits asked, newest first, the head not; `main` advanced with code full; each required variable unset or empty exits 2 with `Missing {name}.` and writes nothing; `EVENT_NAME` checked first. BP-R-12: every entry of `LAB_PATHS` gives `lab` (and the tested list covers each entry); `spikes/providers/x.py`, `deploy/values/demo.yaml`, `dev/fakes/recordings/x.json`, `tools/trace.py`, `app/src/main/x.java` give `no-lab`; a file moved from `demo/` to `docs/` gives `lab`; an untracked file under `demo/` gives `lab`; no `origin/main` gives `lab` |
| `tools/test_ci_workflow.py` (new) | BP-W-01: exactly the six jobs on `ubuntu-24.04`; `needs`, `if` and timeouts of each job; `scope`'s step and its variables and output; `java` runs `--part java`, `rest` `--part rest`, `light` the data checks and `trace.py`; `permissions`; every `uses:` pinned to a full commit SHA; the `gate` job's step run with `bash` for combinations of job results |
| `tools/test_check_all.py` (new) | BP-R-12: without arguments every step in order; `--part java` only `images.sh --test --java` and `mvnw -B verify`; `--part rest` only `images.sh --test` and steps (3) to (7); `no-lab` adds `and not lab`; wrong arguments (`--part web` and four others) print the usage line and exit 2 before any call; `tools/gate_scope.py` without `ci` or `lab` exits 2 |
| `deploy/test_images.py` (new) | BP-R-12: `--test --java` builds only `vigil/demo-lab:local` and pulls only its Dockerfile's base images, none of `compose.yaml`; `--test` builds the six images, the compose file's images pulled first; `--java`, `--java --test`, `--test --java x` and no argument print the usage line and exit 2 |

Every row of spec part 4 with proof `test` has tests whose docstrings start with its ID; `tools/trace.py --check` passes.

## 3. Deviations from the spec

| # | Deviation | Reason |
| --- | --- | --- |
| 1 | `deploy/test_images.py` uses its own `docker` stub (written by the test), not the fake of `deploy/tests/bin`. | That fake reads its standard input, so inside the `while read` loops of `deploy/images.sh` its first call swallows the remaining image names; `docker pull` and `docker image inspect` do not read standard input. The stub records and answers as the spec says (a missing image; success otherwise). |
| 2 | Tests beyond the cases the spec lists: further non-data paths (`.sh`, `Dockerfile`, `compose.yaml`), a failing `git`, a JSON list from `gh`, the moved file as the whole pull request, and running the `gate` job's step for combinations of results. | They prove parts of spec 6.1 and 6.4 that the listed cases do not reach; no behaviour is added. |

Readings of the spec where it gives no detail: `Missing {name}.` and the usage lines are printed on standard output. The `gate` job prints the mode and each job's result only when it fails (spec 6.4), nothing when it passes. A `check_runs` entry that is not an object, or without `app`, does not count as a passed gate.

## 4. Document feedback (CTX-Q-8)

1. The storm check `resolve_after_clear` (SB-T-05, DD-F-10 `timing_targets`, 40 s) has almost no margin on GitHub's runners. In the first attempt of run 43027492232, the Zabbix trigger "Database write errors on schema bundle_catalog" (`last(…)>last(…,#1:now-20s)`, which recovers only once the counter has stayed unchanged for 20 s) did not resolve within 40 s after the clear, in both storm runs of that job. The re-run passed. Earlier results under `spikes/results/` record times of 37.3 s, 38.0 s and 39.4 s. Since P-42, every package that changes a lab path runs these tests in the `rest` job, so this check can fail gates that have nothing to do with the lab. For the Claude project: decide whether the limit, the trigger's 20 s window or the gate's handling of the storm tests changes.

## 5. Blocking questions

None.

## 6. Versions introduced (BP-R-08, pin date 11 November 2026)

None. `tools/gate_scope.py` uses the standard library only; the `scope` job uses the runner's Python 3.12 (spec 6.4); every action keeps its pin.

## 7. Values recorded under `docs/`

None (spec Section 9).

## 8. Owner-run results

None (no owner-run step).

## 9. Lines of production code added and changed

`tools/loc.sh origin/main HEAD` prints **17** (size S; at most 800; the spec estimated about 15 lines in `deploy/images.sh`).

## 10. Gate command result

Cloud session (`tools/check-all.sh`, no arguments): steps 0 to 7 pass, last line `== all checks passed`, exit code 0. Step 1a built the six images; step 2: `vigil-app` 172 unit tests and 576 `*IT`, `demo-control` 1 and 2, all passing; the web build, lint, type check and Vitest pass; step 3: `ruff` clean, 376 Python tests passed, 1 skipped (101 of them new; the lab tests ran, because this package changes `tools/check-all.sh`); step 4: `shellcheck`; step 5: 49 data checks, 0 failed; step 6: compose validation; step 7: `tools/trace.py --check`.

Done-check: BP-DC-01 (every part 4 row with proof `test` has tests that name it; CTX-D-067 below); BP-DC-02 (above, and the runs below); BP-DC-03 (nothing beyond spec Section 6: no change to the tests of earlier packages, `tools/trace.py`, `release.yml` or what a step checks); BP-DC-04 and BP-DC-05 (no Java code, migration, OpenAPI entry or seed part); BP-DC-06 (`README.md`, `CLAUDE.md`); BP-DC-07 (this report; no owner-run step). Spec Section 8 item 3: in run 43027492232 the `rest` job's log shows the lab tests ran (`test_storm` and the other tests marked `lab`; no "lab tests skipped" line), and the `java` job's log shows one image built, `vigil/demo-lab:local` (`naming to docker.io/vigil/demo-lab:local`), then `== all checks passed`.

### Runs of the pull request (spec Section 8 item 2, CTX-D-067)

Times are UTC on 11 November 2026, from `gh api repos/awadoodatevam/vigil/actions/runs?branch=p-42-faster-gate` and each run's `jobs`.

**Run 1: 43027492232, the build push (commit `2f0c934`): full.** `scope` printed `gate: full (no earlier passed gate)`.

| Job | Attempt 1 | Attempt 2 (re-run of the failed jobs) |
| --- | --- | --- |
| `scope` | 13:15:36 to 13:15:41, success | (kept) |
| `secrets` | 13:15:36 to 13:15:41, success | (kept) |
| `light` | skipped | skipped |
| `java` | 13:15:44 to 13:31:36 (15 min 52 s), success | (kept) |
| `rest` | 13:15:44 to 13:31:54 (16 min 10 s), **failure** | 13:32:59 to 13:45:11 (12 min 12 s), success |
| `gate` | 13:31:57 to 13:31:59, failure | 13:45:13 to 13:45:16, **success** |

`java` and `rest` started at the same second (13:15:44) and ran in parallel. Attempt 1 took 16 min 26 s (13:15:33 to 13:31:59), against 21 to 30 minutes for the single job of P-06 to P-07. In attempt 1, `rest` failed in three lab tests of `demo/monitoring/test_monitoring.py` (`test_storm`, `test_timing_targets`, `test_clear_all_faults`). In both storm runs, the check `resolve_after_clear` failed with `not resolved within 40 s 1: lab-db-01 Database write errors on schema bundle_catalog`; 373 tests passed. P-42 changes nothing that the lab or the storm runs (document feedback 1). I re-ran the failed jobs once (`rerun-failed-jobs`; no commit pushed). In attempt 2, `rest` passed with 376 tests passed and 1 skipped, the lab tests included, and `gate` passed. From the start of the run to the passed `gate` took 29 min 43 s; without the failed attempt the full gate takes about 16.5 minutes, the time of its longest job.

**Run 2: 43032175222, the report commit (commit `f94f40c`, data only): light.** `scope` printed `gate: light (only data since 2f0c934 passed)`.

| Job | Start to end | Result |
| --- | --- | --- |
| `scope` | 13:46:35 to 13:46:41 | success |
| `secrets` | 13:46:35 to 13:46:42 | success |
| `light` | 13:46:42 to 13:46:56 (14 s) | success |
| `java`, `rest` | skipped | skipped |
| `gate` | 13:46:59 to 13:47:02 | **success** |

Total: 30 s (13:46:32 to 13:47:02), against about two minutes expected (CTX-D-067) and a full run before P-42. `main` had gained no code since the branch was made, so no rebase was needed.

**Run 3**: the commit that adds run 2 to this report changes only this file, so its run is light as well. It is not recorded here.

## 11. Follow-up items for later packages

1. `deploy/tests/bin/docker` reads its standard input; a later test of a script that calls `docker` inside a `while read … done < <(…)` loop needs a stub that does not (deviation 1).
