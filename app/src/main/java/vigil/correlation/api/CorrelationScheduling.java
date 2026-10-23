package vigil.correlation.api;

/** The correlation workers of the active tenants (AR-R-17, AR-R-26). */
public interface CorrelationScheduling {

  /** Matches the workers to the active tenants with correlation settings (AR-R-19, AR-R-24 (5)). */
  void matchActiveTenants();
}
