package vigil.identity;

import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.identity.api.SessionExpiry;
import vigil.tenancy.api.TenantAliasChanged;

/**
 * Identity's AR-E-14 listener: matches the session expiry sweeps to the active tenants. It makes no
 * tenant check (installation-level event, AR-R-14) and writes no record, so a second delivery gives
 * the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class TenantAliasListener {

  private final SessionExpiry sessionExpiry;

  TenantAliasListener(SessionExpiry sessionExpiry) {
    this.sessionExpiry = sessionExpiry;
  }

  @ApplicationModuleListener
  public void on(TenantAliasChanged event) {
    sessionExpiry.matchActiveTenants();
  }
}
