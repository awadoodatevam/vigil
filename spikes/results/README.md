# Spike results

Result files of the spikes under `spikes/`, committed to the package branch that ran them (`docs/60-build-plan.md` BP-R-03, BP-L-5).

| File | Written by | Who commits it |
| --- | --- | --- |
| `storm-timing-{YYYYMMDDTHHMMSSZ}.json` | `spikes/storm-timing/run.sh` (SB-T-01, development run; the UTC start time in the name) | Claude Code for the cloud session's 10-storm run (`test` profile); the owner for the run on the Mac (`demo` profile, `tests/acceptance/p04_mac_check.sh`) |
| `P-05-AR-SP-01.json` | The test page of `spikes/sse-probe/` (AR-SP-01, probe part), downloaded from the browser in Pakistan | The owner (P-05 spec Section 6.13 step 8) |
| `P-05-AR-SP-01-run1.json` | The first run of the same test (failed `max_delay_1000`), kept by the owner's decision under P05-DC-02 | The owner (P-05 report, deviation 11) |
| `P-06-SB-T-07.json` | `spikes/providers/run.sh jira` (SB-T-07) | The owner (P-06 spec Section 6.11 step 10) |
| `P-06-claude.json` | `spikes/providers/run.sh claude` (AR-G-20) | The owner (P-06 spec Section 6.11 step 10) |
| `P-06-openai.json` | `spikes/providers/run.sh openai` (AR-G-21, AR-G-22) | The owner (P-06 spec Section 6.11 step 10) |
| `P-06-SB-T-03.json` | `spikes/providers/run.sh similarity` (SB-T-03, runtime part) | The owner (P-06 spec Section 6.11 step 10) |
| `P-06-SB-T-04.json` | `spikes/providers/run.sh local-model` (SB-T-04, local model part) | The owner (P-06 spec Section 6.11 step 10) |
| `P-20-claude.json` | `spikes/providers/run.sh claude --package P-20` | The owner (P-20 spec Section 6.14) |
| `P-20-openai.json` | `spikes/providers/run.sh openai --package P-20` | The owner (P-20 spec Section 6.14) |
| `P-20-SB-T-04.json` | `spikes/providers/run.sh local-model --package P-20` | The owner (P-20 spec Section 6.14) |

The format of a storm timing result file is described in `spikes/storm-timing/README.md`, that of `P-05-AR-SP-01.json` in `spikes/sse-probe/README.md`, those of the `P-06-*.json` and `P-20-*.json` files in `spikes/providers/README.md`.
