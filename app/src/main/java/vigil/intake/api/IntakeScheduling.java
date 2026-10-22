package vigil.intake.api;

/** Intake's scheduled work for tenants (AR-R-23). */
public interface IntakeScheduling {

  /** Matches the scheduled health work to the active tenants (AR-R-19, AR-R-24 (6)). */
  void matchActiveTenants();
}
