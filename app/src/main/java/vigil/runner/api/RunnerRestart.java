package vigil.runner.api;

/** Restart step (1) of AR-R-24, called by the restart procedure. */
public interface RunnerRestart {

  /**
   * Ends the runs of active tenants still {@code running}: {@code unknown} for an action, {@code
   * failed} for a diagnostic operation (DM-SM10-T6).
   */
  void markInterruptedRuns();
}
