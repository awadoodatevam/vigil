# Storm timing spike (SB-T-01, development run)

Proves, against the webhook recorder (`dev/recorder/`) in place of `vigil-app`, every timing target of DD-F-10 `timing_targets` that does not need Vigil, and measures the development values of DD-F-10 `lab_db_server` (package P-04; the final run through the load balancer is P-32).

```bash
deploy/local.sh up --lab-only [--profile test|demo]
spikes/storm-timing/run.sh [--storms N] [--extra LIST] [--save-recordings] [--foreground] [--project NAME]
```

`run.sh` starts the container `storm-spike` (image `vigil/storm-spike:local`, compose profile `spike`) on `lab-net`, detached by default (`--foreground` waits and exits with the spike's exit code), and prints `result file {path}`. Arguments: `--storms N` standard storms (default 10), `--extra` a subset of `read-only,clear` or `none` (default `read-only,clear`), `--save-recordings` writes the calls of standard storm 1 to `dev/fakes/recordings/monitoring/` (refused when that folder already holds a `*.json` file). `VIGIL_SPIKE_RESULTS` and `VIGIL_SPIKE_SAVE` change the results and recordings folders. A run of 10 storms takes about 2 hours: a new lab first waits up to 16 minutes until its baseline files are older than the 15-minute `disk_growth` period, and each standard storm waits 300 s after its last resolve.

## What it does

Once: waits until the recorder, the 32 Zabbix items, the 4 Prometheus targets and Alertmanager are ready (300 s); records the connection checks (AR-K-01 test call with the `vigil` token, AR-K-02 test, Alertmanager refuses a request without credentials, Prometheus targets up) and the versions; clears SL-F-1 to SL-F-3; with at least one standard storm, waits until `disk_growth` reports 0 files (the baseline window).

Each run (`standard`: SL-F-1 and SL-F-3 with the action; `read-only`: SL-F-1, SL-F-2, SL-F-3 with the action; `clear`: SL-F-1 and SL-F-3 without the action): wait until no lab alert fires (Zabbix `problem.get` with tag `vigil=lab`, Alertmanager `/api/v2/alerts`) plus 12 s; measure `fs_used_pct`; inject through `demo-faults` (time T0); read the recorder for 150 s; run the diagnostics of DD-F-04 over SSH as `vigil-diag` and count the injected files; run the registered action `purge-archived-txlogs` as `vigil-action` (time TA); wait for the resolves (and 300 s without re-firing) or read the open problems and alerts (`read-only`); clear the run's faults (time TC); wait until quiet again. Commands are the DD-F-04 templates with AR-K-12 quoting; outputs are read by the DD-F-04 parsers, written in Python here.

Checks of a run (P-04 spec Section 6.5): `arrival` (every one of the 126 storm alerts fires within 120 s), `exactly_once`, `counts` (126 = 31 Zabbix + 95 Alertmanager, 1 SL-F-3), `no_unexpected`, `max_gap` (25 s), `sl_f_3` (30 s), `precondition`, `disk_growth` (after the baseline window), `action`, `resolve_after_action` (60 s), `no_refire` (300 s), `sl_f_2` (124 keep firing), `resolve_after_clear` (40 s, SB-T-05 lab part), `messages` (the formats of AR §12.3 and §12.4), `quiet_before`, `quiet_after`, `steps`. A failed inject, clear or SSH command stops the spike after clearing the run's faults.

## Result file

JSON with the keys `spike`, `started_at`, `finished_at`, `finished`, `profile`, `machine`, `arguments`, `versions`, `checks` (connection checks, `start_clear`, `baseline_window`), `runs` and `passed`. Each run has `kind`, `index`, `faults`, `t0`, `action_end`, `clear_at`, `measured` (the DD-F-10 development values: `baseline_used_pct`, `used_pct_after_injection`, `avail_bytes_after_injection`, `injected_files`, `injected_bytes`, `injected_gib`, the three diagnostic summaries, `action_files_removed`, `action_gib_removed`, `used_pct_after_action`), `timing` (seconds after T0, TA or TC), `counts`, `checks` and `passed`. Exit code 0 when `passed`.

## Tests

`test_storm_timing.py`: the checks on synthetic call lists, the parsers, the quoting and the result file's keys. The gate runs one storm with SL-F-2 (`--storms 0 --extra read-only`) in `demo/monitoring/test_monitoring.py`.
