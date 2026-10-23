package vigil.correlation;

import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.correlation.api.CorrelationScheduling;
import vigil.tenancy.api.TenantAliasChanged;

/**
 * The AR-E-14 listener of vigil-correlation: matches the workers to the active tenants. It makes no
 * tenant check (installation-level event, AR-R-14) and writes no record, so a second delivery gives
 * the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class CorrelationAliasListener {

  private final CorrelationScheduling scheduling;

  CorrelationAliasListener(CorrelationScheduling scheduling) {
    this.scheduling = scheduling;
  }

  @ApplicationModuleListener
  public void on(TenantAliasChanged event) {
    scheduling.matchActiveTenants();
  }
}
