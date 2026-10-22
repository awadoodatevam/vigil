package vigil.incidents.api;

/** The scheduled closure work of vigil-incidents for tenants (AR-R-23). */
public interface IncidentScheduling {

  /** Matches the closure work to the active tenants (AR-R-19, AR-R-24 (6)). */
  void matchActiveTenants();
}
