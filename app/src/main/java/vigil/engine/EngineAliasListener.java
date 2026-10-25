package vigil.engine;

import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.engine.api.EngineScheduling;
import vigil.tenancy.api.TenantAliasChanged;

/**
 * The AR-E-14 listener of vigil-engine: matches the timer polls and the set of active tenants to
 * the active tenants (AR-R-19). It makes no tenant check (installation-level event, AR-R-14); a
 * second delivery finds nothing to change (AR-Q-05).
 */
@Component
@Profile("!migrate")
class EngineAliasListener {

  private final EngineScheduling scheduling;

  EngineAliasListener(EngineScheduling scheduling) {
    this.scheduling = scheduling;
  }

  @ApplicationModuleListener
  public void on(TenantAliasChanged event) {
    scheduling.matchActiveTenants();
  }
}
