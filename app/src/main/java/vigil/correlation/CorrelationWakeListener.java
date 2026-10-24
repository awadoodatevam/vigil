package vigil.correlation;

import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import vigil.intake.api.AlertReceived;

/**
 * The AR-E-01 listener of vigil-correlation: only wakes the tenant's worker (AR-R-17); a tenant
 * without a worker is ignored. It writes no record, so a second delivery gives the same result
 * (AR-Q-05).
 */
@Component
@Profile("!migrate")
class CorrelationWakeListener {

  private final CorrelationWork work;

  CorrelationWakeListener(CorrelationWork work) {
    this.work = work;
  }

  @ApplicationModuleListener
  public void on(AlertReceived event) {
    work.wake(event.tenantId());
  }
}
