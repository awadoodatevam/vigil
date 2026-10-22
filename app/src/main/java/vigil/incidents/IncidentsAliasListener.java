package vigil.incidents;

import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.incidents.api.IncidentScheduling;
import vigil.tenancy.api.TenantAliasChanged;

/**
 * The AR-E-14 listener of vigil-incidents: matches the closure work to the active tenants. It makes
 * no tenant check (installation-level event, AR-R-14) and writes no record, so a second delivery
 * gives the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class IncidentsAliasListener {

  private final IncidentScheduling scheduling;

  IncidentsAliasListener(IncidentScheduling scheduling) {
    this.scheduling = scheduling;
  }

  @ApplicationModuleListener
  public void on(TenantAliasChanged event) {
    scheduling.matchActiveTenants();
  }
}
