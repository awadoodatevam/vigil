# Storm driver (P-27)

The storm driver runs the primary demo headless on a local installation and checks its result (SB-T-02 with amendment BP-A-11, SL-A3-04; `docs/packages/P-27.md`). It runs as the container `storm-driver` (image `vigil/storm-driver:local`, built by `deploy/images.sh --local`) in the compose profile `acceptance` on `lab-net`.

```bash
tests/acceptance/storm/run.sh [--mode immediate|script] [--deliveries fakes|checklist] [--acts driver|pages] [--project NAME]
```

`run.sh` needs a local installation (`deploy/local.sh up`). It runs the driver with the lab profile `up` used (`state/lab-profile`), then writes the whole logs of `vigil-app` and `vigil-migrate` to `~/.vigil-local/{project}/acceptance/vigil-{UTC time}.log` and scans them for every value of the project's secrets folder (AR-Q-07, `tests/acceptance/log_scan.py --ignore-fields username,email,expires_on`). It exits 0 when the driver passed and no secret is in the log. The result file `storm-{mode}-{UTC time}.json` goes to `VIGIL_STORM_RESULTS` (default `tests/acceptance/results/`).

## What the driver does

1. **Reset** (AR-B-03): at once (A) `demo-faults` clears SL-F-1 to SL-F-3, then Zabbix (`problem.get`, tag `vigil=lab`) and Alertmanager (the five catalogue alert names) are read every 2 s until the lab is quiet, then 12 s (at most 180 s); (B) a new tenant `jazz-demo {UTC time}`, the import of `state/bundle.zip`, the import read every 1 s (at most 300 s); then the alias of the host of `VIGIL_BASE_URL`. `state/tenant.json` and `state/seed-import.json` are not changed.
2. **Starting state**: logs in `presenter` (every read), `noc-l1` and `dbops-engineer`; waits until both sources are healthy; records the Teams fake's calls and Mailpit's messages so far.
3. **Script start** (mode `script`): T0 is the script's 01:00.
4. **Storm**: injects SL-F-1 and SL-F-3.
5. **Incidents**: waits for the storm and the report incident (until T0 + 360 s).
6. **Acknowledge**: waits until the storm run's `s-notify` is completed and its message reached `/teams-team-noc-l1`, builds `{VIGIL_BASE_URL}/m/acknowledge/{incident id}` and acknowledges as `noc-l1` (mode `script`: at T0 + 390 s, 07:30; a link later than T0 + 405 s ends the run: "Acknowledge link by 07:45").
7. **Approve**: waits for the pending approval request and its message on `/teams-user-dbops-engineer`, builds `{VIGIL_BASE_URL}/m/approve/{request id}` and approves as `dbops-engineer` (mode `script`: at T0 + 480 s, 09:00; no request by T0 + 510 s ends the run: "Approval link by 09:30").
8. **Recovery**: waits until the storm incident is resolved (480 s), then until its Jira ticket is `Done` and its run is finished (120 s).
9. **Checks** (below), the result file and the exit code. SL-F-3 stays active and the report incident open, as in the demo; the next reset clears them.

A wait that reaches its limit ends the run with the entry `SB-T-02` "step {k} {name} finished within {limit}".

## Modes and deliveries

- `immediate` (CI, cloud sessions): acts as soon as possible; checks every value that does not depend on the timing or the size profile, the diagnostic summaries by form and the dashboard by the formulas of DM-R-53 over the run's own times.
- `script` (the owner's Mac): follows the script clock and also checks SB-S01's 120 s and SB-S09's exact dashboard values; with the lab profile `demo` also the diagnostic summaries and the action's values against `development_values` of `10-lab-and-faults.yaml`. Wait at least 16 minutes after `deploy/local.sh up` (the 15-minute period of `disk_growth`).
- `--deliveries fakes` (default): checks the Teams calls, the emails in Mailpit and the Jira issue of the dev fakes. `--deliveries checklist` (P-32, the demo installation): skips those checks and writes `storm-checklist-{UTC time}.md` with the messages the owner checks in the channels and mailboxes.

- `--acts driver` (default): the driver acknowledges (step 6) and approves (step 7) as above. `--acts pages` (mode `immediate` only; P-30, `with-playwright.sh` in mode `immediate`): the driver sends no acknowledgement or decision; step 6 prints `step 6 Acknowledge: waiting for the page at {link}` and reads the Acknowledge page as `noc-l1` every 2 s until it is acknowledged (300 s from the link); step 7 takes the run's first approval request in any state, prints `step 7 Approve: waiting for the page at {link}` and reads the Approval page as `dbops-engineer` every 2 s until it is approved (300 s). `SB-S05 acknowledge page`, `SB-S06 approval page` and `SB-S06 decision` then compare the reads after the action. The result file records `acts`.

- `--via local` (default): the driver resets with AR-B-03's steps itself and injects the storm through `demo-faults` (above). `--via control` (P-32, the demo installation; needs `--deliveries checklist`; BP-A-32): step 1 signs in to the control panel at `VIGIL_CONTROL_URL` as `operator` (`/run/secrets/demo-control/control-password`), presses "Reset" (`POST /api/buttons/reset`) and reads `/api/state` every 1 s until the last result is a Reset finished after the press (120 s); a failed reset ends the run with its error; the result file's `reset` takes the control panel's branch, quiet and total seconds (`total_seconds` is its `seconds`). Step 4 presses the storm button (T0 = the press) and reads `/api/state` every 1 s until SL-F-1 and SL-F-3 are active (135 s). The driver then reaches nothing of the lab and reads none of `installation-token`, `faults-token`, `zabbix-api` and `alertmanager-password`. With `--via control`, `--acts pages` is allowed in mode `script` too (the SL-CP-1 run): step 6 also prints `acknowledge at 07:30 (T0 + 390 s)` and step 7 `approve at 09:00 (T0 + 480 s)`; the Acknowledge link by 07:45 and the Approval link by 09:30 are checked as in mode `script`, and each page's 300 s count from its script time.
- `--measured FILE` (P-32): a storm timing result file; `SB-S03 diagnostic values` and `SB-S06 action values` compare with the `measured` values of its first `standard` run instead of `development_values`; the result file records the file's name under `measured`.
- `VIGIL_JIRA_URL` (default `http://jira.fakes:8000`) is the Jira site; the Jira fake's issues are read from it with `--deliveries fakes`.

The SB-TL times are recorded in the result file (`times`, seconds after T0), not checked; `SB-TL-03` is the last diagnostic result's `collected_at`, and with `--deliveries checklist` `SB-TL-04` is the start of the step `s-wait-ack`, which follows the messages (P-32, for AR-SP-03). Two more values are recorded, not checked (P-34, for SB-T-04 and its series, `tests/acceptance/demo-host/series.py`): `times.approved`, the seconds after T0 of the decision's `at` (with `--acts driver` and `--acts pages`; `null` without a decision), placed before `SB-TL-08`; and `investigation.summary_length`, the number of characters of the storm workspace's summary text that `SB-S04 investigation` reads (`null` when there is none).

## Checks (complete list)

`SB-SET-07 incident list empty`, `SB-SET-07 sources healthy`, `SB-V-38 seeded dashboard`, `SB-V-39 baselines`, `SB-R-06 two incidents`, `SB-S01 storm alerts`, `SB-S01 storm alert types`, `SB-S01 report alert`, `SB-S01 storm within 120 s` (script), `SB-S02 storm header`, `SB-S02 grouping reasons`, `SB-S02 report incident`, `SB-S03 diagnostics`, `SB-S03 diagnostic values` (script, demo), `SB-S04 routing`, `SB-S04 investigation`, `SB-S05 ticket` (fakes), `SB-S05 incident messages` (fakes), `SB-S05 acknowledge page`, `SB-S05 timeline acknowledgement`, `SB-S06 similar incident`, `SB-S06 proposal`, `SB-S06 approval messages` (fakes), `SB-S06 approval page`, `SB-S06 decision`, `SB-S06 action executed once`, `SB-S06 action result`, `SB-S06 action values` (script, demo), `SB-S07 recovery`, `SB-S07 resolved`, `SB-S07 ticket resolved`, `SB-S07 ticket comment` (fakes), `SB-S08 storm run`, `SB-S09 dashboard formulas`, `SB-S09 dashboard values` (script), `SB-TL-05 no escalation`, `SB-R-06 repeatability result`.

Each is written to the result file as `{id, name, passed, detail}`; the checks a mode, profile or delivery leaves out are listed under `not_run`.

## Files

`storm.py` (the run and the result file), `storm_api.py` (the HTTP calls, sessions and the reset), `storm_checks.py` (the checks, message parsing, DM-R-53, the checklist), `Dockerfile`, `run.sh`, `with-playwright.sh` (P-28: `run.sh` alongside the Playwright suite `storm`, see the repository's `README.md`; P-29: it first writes the driver's mode into `{VIGIL_LOCAL_HOME or ~/.vigil-local}/{project}/e2e/storm-mode`, which the suite reads as `/e2e/run/storm-mode`); tests `test_storm.py` (against an in-process server), `test_run_script.py` (a fake `docker`) and `test_with_playwright.py` (fake `deploy/local.sh`, `run.sh` and `docker`).
