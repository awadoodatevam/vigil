# Spikes

Re-runnable spike scripts (`docs/60-build-plan.md` Section 9), one folder per spike, each with its README and its pytest tests (`test_*.py`, run by the gate command; tests that need the lab have the marker `lab`). A spike writes one JSON result file per run under `results/` (`spikes/results/`, or the folder in `VIGIL_SPIKE_RESULTS`); the owner commits the result files of owner-run steps, and Claude Code commits those of the runs a package specifies for a cloud session.

| Folder | Spike | Package |
| --- | --- | --- |
| `storm-timing/` | SB-T-01 storm timing, development run against the webhook recorder | P-04 |
| `sse-probe/` | AR-SP-01 probe part: live updates through the load balancer from a browser in Pakistan (owner-run) | P-05 |
