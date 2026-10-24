package vigil.engine;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vigil.shared.TenantId;

/**
 * Hands runs to their executors after the current transaction commits (AR-R-20). In the {@code
 * migrate} profile there are no executors and nothing is handed off.
 */
@Component
class HandOffs {

  private final ObjectProvider<RunExecutors> executors;

  HandOffs(ObjectProvider<RunExecutors> executors) {
    this.executors = executors;
  }

  /** Hands the run off after the commit of the current transaction. */
  void afterCommit(TenantId tenantId, UUID runId) {
    later(executors -> executors.handOff(tenantId, runId));
  }

  /** Schedules a hand-off of the run {@code delay} of real time after the commit. */
  void afterCommit(TenantId tenantId, UUID runId, Duration delay) {
    later(executors -> executors.scheduleHandOff(tenantId, runId, delay));
  }

  private void later(Consumer<RunExecutors> action) {
    RunExecutors available = executors.getIfAvailable();
    if (available == null) {
      return;
    }
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.accept(available);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.accept(available);
          }
        });
  }
}
