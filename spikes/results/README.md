# Spike results

Result files of the spikes under `spikes/`, committed to the package branch that ran them (`docs/60-build-plan.md` BP-R-03, BP-L-5).

| File | Written by | Who commits it |
| --- | --- | --- |
| `storm-timing-{YYYYMMDDTHHMMSSZ}.json` | `spikes/storm-timing/run.sh` (SB-T-01, development run; the UTC start time in the name) | Claude Code for the cloud session's 10-storm run (`test` profile); the owner for the run on the Mac (`demo` profile, `tests/acceptance/p04_mac_check.sh`) |
| `P-05-AR-SP-01.json` | The test page of `spikes/sse-probe/` (AR-SP-01, probe part), downloaded from the browser in Pakistan | The owner (P-05 spec Section 6.13 step 8) |

The format of a storm timing result file is described in `spikes/storm-timing/README.md`, that of `P-05-AR-SP-01.json` in `spikes/sse-probe/README.md`.
