package vigil.engine.api;

/** The engine's part of the restart procedure (AR-R-24) and of AR-E-14 (AR-R-19, AR-R-23). */
public interface EngineScheduling {

  /**
   * Restart step (4), once: run executors accept hand-offs; overdue timers fire; every unfinished
   * run of an active tenant is handed to its executor.
   */
  void resumeRuns();

  /**
   * Restart step (6) and AR-E-14: the timer polls match the active tenants; a tenant active again
   * gets its runs handed to their executors.
   */
  void matchActiveTenants();
}
