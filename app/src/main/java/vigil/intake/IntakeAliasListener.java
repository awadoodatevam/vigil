package vigil.intake;

import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.intake.api.IntakeScheduling;
import vigil.tenancy.api.TenantAliasChanged;

/**
 * Intake's AR-E-14 listener: matches the scheduled health work to the active tenants. It makes no
 * tenant check (installation-level event, AR-R-14) and writes no record, so a second delivery gives
 * the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class IntakeAliasListener {

  private final IntakeScheduling scheduling;

  IntakeAliasListener(IntakeScheduling scheduling) {
    this.scheduling = scheduling;
  }

  @ApplicationModuleListener
  public void on(TenantAliasChanged event) {
    scheduling.matchActiveTenants();
  }
}
