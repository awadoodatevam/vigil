package vigil.knowledge;

import java.time.Clock;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.TaskProviderChanged;
import vigil.knowledge.api.EmbeddingState;
import vigil.knowledge.api.KnowledgeScheduling;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantService;

/**
 * The listeners of vigil-knowledge (AR-E-11, AR-E-14): "task provider changed" for SL-T-3 starts
 * re-embedding (DM-SM15-T1); "tenant alias changed" matches the scheduled work. A second delivery
 * of either event gives the same result (AR-Q-05).
 */
@Component
@Profile("!migrate")
class KnowledgeListeners {

  private final TenantService tenants;
  private final KnowledgeRecords records;
  private final Reembedding reembedding;
  private final KnowledgeScheduling scheduling;
  private final Clock clock;

  KnowledgeListeners(
      TenantService tenants,
      KnowledgeRecords records,
      Reembedding reembedding,
      KnowledgeScheduling scheduling,
      Clock clock) {
    this.tenants = tenants;
    this.records = records;
    this.reembedding = reembedding;
    this.scheduling = scheduling;
    this.clock = clock;
  }

  @ApplicationModuleListener
  public void on(TaskProviderChanged event) {
    TenantId tenantId = event.tenantId();
    if (!tenants.isActive(tenantId) || event.task() != AiTask.SL_T_3) {
      return;
    }
    String providerKey = event.providerKey();
    while (true) {
      Optional<KnowledgeSettingsView> settings = records.settings(tenantId);
      if (settings.isEmpty()) {
        return;
      }
      KnowledgeSettingsView current = settings.get();
      if (current.embeddingState() == EmbeddingState.READY
          ? current.embeddingProviderKey().equals(providerKey)
          : providerKey.equals(current.reembeddingProviderKey())) {
        return;
      }
      if (records.startReembedding(tenantId, current, providerKey, Timestamps.now(clock))) {
        break;
      }
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              reembedding.start(tenantId);
            }
          });
    } else {
      reembedding.start(tenantId);
    }
  }

  @ApplicationModuleListener
  public void on(TenantAliasChanged event) {
    scheduling.matchActiveTenants();
  }
}
