package vigil.knowledge;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.DataCategory;
import vigil.aigateway.api.EmbeddingRequest;
import vigil.aigateway.api.EmbeddingResult;
import vigil.knowledge.KnowledgeRecords.Embedding;
import vigil.knowledge.api.EmbeddingState;
import vigil.knowledge.api.KnowledgeScheduling;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.knowledge.api.PastIncidentView;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.tenancy.api.TenantService;

/**
 * Re-embedding of the past incidents after the SL-T-3 provider changed (DM-SM-15, AR-R-23, AR-R-30,
 * AR-TM-07; P-20 spec Section 6.9): a task per active tenant with knowledge settings every 30 s
 * starts a pass on the tenant's virtual thread while the settings are {@code reembedding}. Not in
 * the {@code migrate} profile, which has no task scheduler.
 */
@Component
@Profile("!migrate")
class Reembedding implements KnowledgeScheduling {

  static final Duration INTERVAL = Duration.ofSeconds(30);
  static final int BATCH = 50;
  private static final Logger LOG = LoggerFactory.getLogger(Reembedding.class);

  private final TaskScheduler taskScheduler;
  private final TenantService tenants;
  private final KnowledgeRecords records;
  private final AiGatewayService gateway;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Map<TenantId, ScheduledFuture<?>> schedules = new HashMap<>();
  private final Map<TenantId, Thread> workers = new HashMap<>();

  Reembedding(
      TaskScheduler taskScheduler,
      TenantService tenants,
      KnowledgeRecords records,
      AiGatewayService gateway,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.taskScheduler = taskScheduler;
    this.tenants = tenants;
    this.records = records;
    this.gateway = gateway;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public synchronized void matchActiveTenants() {
    Set<TenantId> active = tenants.activeTenants();
    schedules
        .entrySet()
        .removeIf(
            entry -> {
              if (active.contains(entry.getKey())) {
                return false;
              }
              entry.getValue().cancel(false);
              return true;
            });
    for (TenantId tenantId : active) {
      if (!schedules.containsKey(tenantId) && records.settings(tenantId).isPresent()) {
        schedules.put(
            tenantId,
            taskScheduler.scheduleWithFixedDelay(
                () -> tick(tenantId), taskScheduler.getClock().instant(), INTERVAL));
      }
    }
  }

  /** The tenants whose task is registered (for tests). */
  synchronized Set<TenantId> scheduledTenants() {
    return Set.copyOf(schedules.keySet());
  }

  /** The last worker thread started for the tenant, kept after it ended (for tests). */
  synchronized Optional<Thread> workerThread(TenantId tenantId) {
    return Optional.ofNullable(workers.get(tenantId));
  }

  /** One tick of the tenant's task: a pass when the settings are {@code reembedding}. */
  void tick(TenantId tenantId) {
    try {
      Optional<KnowledgeSettingsView> settings = records.settings(tenantId);
      if (settings.isPresent() && settings.get().embeddingState() == EmbeddingState.REEMBEDDING) {
        start(tenantId);
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "re-embedding of tenant {} with provider {} did not succeed ({}); retried within 30 s",
          tenantId.value(),
          "unknown",
          e.getClass().getSimpleName());
    }
  }

  /** Starts a pass on the tenant's virtual thread unless one is running (AR-R-30). */
  synchronized void start(TenantId tenantId) {
    Thread worker = workers.get(tenantId);
    if (worker != null && worker.isAlive()) {
      return;
    }
    workers.put(
        tenantId,
        Thread.ofVirtual().name("reembed-" + tenantId.value()).start(() -> pass(tenantId)));
  }

  /** One pass, repeated until it ends (DM-SM15-T2, DM-SM15-T3). */
  private void pass(TenantId tenantId) {
    String key = "unknown";
    try {
      while (true) {
        if (!tenants.isActive(tenantId)) {
          return;
        }
        Optional<KnowledgeSettingsView> settings = records.settings(tenantId);
        if (settings.isEmpty() || settings.get().embeddingState() != EmbeddingState.REEMBEDDING) {
          return;
        }
        String target = settings.get().reembeddingProviderKey();
        key = target;
        List<PastIncidentView> missing = records.withoutEmbedding(tenantId, target);
        if (missing.isEmpty()) {
          boolean finished =
              Boolean.TRUE.equals(
                  tx.execute(
                      status ->
                          records.finishReembedding(tenantId, target, Timestamps.now(clock))));
          if (finished) {
            LOG.info(
                "re-embedding of tenant {} finished with provider {}", tenantId.value(), target);
            return;
          }
          continue;
        }
        List<PastIncidentView> batch = missing.subList(0, Math.min(BATCH, missing.size()));
        List<String> texts =
            batch.stream()
                .map(
                    p ->
                        SearchTexts.of(
                            p.alertTypes(), p.affectedServiceKeys(), p.hypothesisComponentKey()))
                .toList();
        EmbeddingResult answer =
            gateway.embed(
                new EmbeddingRequest(
                    tenantId,
                    target,
                    Ids.newId(),
                    null,
                    Set.of(DataCategory.INCIDENT_HISTORY),
                    texts));
        String failure =
            switch (answer) {
              case EmbeddingResult.Unavailable unavailable -> unavailable.outcome().key();
              case EmbeddingResult.Embedded embedded ->
                  embedded.vectors().size() == texts.size() && Vectors.usable(embedded.vectors())
                      ? null
                      : "unusable vectors";
            };
        if (failure != null) {
          warn(tenantId, target, failure);
          return;
        }
        EmbeddingResult.Embedded embedded = (EmbeddingResult.Embedded) answer;
        tx.executeWithoutResult(
            status -> {
              Optional<KnowledgeSettingsView> now = records.lockSettings(tenantId);
              if (now.isEmpty()
                  || now.get().embeddingState() != EmbeddingState.REEMBEDDING
                  || !target.equals(now.get().reembeddingProviderKey())) {
                return;
              }
              for (int i = 0; i < batch.size(); i++) {
                records.insertEmbedding(
                    tenantId,
                    new Embedding(
                        "past_incident",
                        batch.get(i).pastIncidentKey(),
                        texts.get(i),
                        embedded.vectors().get(i)),
                    target,
                    embedded.modelName(),
                    Timestamps.now(clock),
                    true);
              }
            });
      }
    } catch (RuntimeException e) {
      warn(tenantId, key, e.getClass().getSimpleName());
    }
  }

  private static void warn(TenantId tenantId, String providerKey, String outcome) {
    LOG.warn(
        "re-embedding of tenant {} with provider {} did not succeed ({}); retried within 30 s",
        tenantId.value(),
        providerKey,
        outcome);
  }
}
