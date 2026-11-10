package vigil.knowledge;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.DataCategory;
import vigil.aigateway.api.EmbeddingRequest;
import vigil.aigateway.api.EmbeddingResult;
import vigil.knowledge.KnowledgeRecords.Embedding;
import vigil.knowledge.KnowledgeRecords.Score;
import vigil.knowledge.api.EmbeddingState;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.knowledge.api.PastIncidentView;
import vigil.knowledge.api.RunbookView;
import vigil.knowledge.api.SearchOutcome;
import vigil.knowledge.api.SearchRequest;
import vigil.knowledge.api.SearchResult;
import vigil.knowledge.api.SimilarityResultView;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;

/**
 * Runbooks by rules (DM-R-50), the similar-incident search with its stored result (DM-R-51,
 * DM-E-65, DM-E-66, AR-P-07) and the reads of PM-M16 (P-20 spec Section 6.8).
 */
@Service
class Knowledge implements KnowledgeService {

  private final KnowledgeRecords records;
  private final AiGatewayService gateway;
  private final TransactionTemplate tx;
  private final Clock clock;

  Knowledge(
      KnowledgeRecords records,
      AiGatewayService gateway,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.gateway = gateway;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public SearchResult search(SearchRequest request) {
    TransactionGuard.assertNoTransaction();
    if (request == null
        || request.tenantId() == null
        || request.incidentId() == null
        || request.alertTypes() == null
        || request.serviceKeys() == null
        || request.alertTypes().stream().anyMatch(Objects::isNull)
        || request.serviceKeys().stream().anyMatch(Objects::isNull)) {
      throw missing();
    }
    TenantId tenantId = request.tenantId();
    KnowledgeSettingsView settings =
        records
            .settings(tenantId)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "knowledge settings of tenant " + tenantId.value() + " not found"));
    if (settings.embeddingState() == EmbeddingState.REEMBEDDING) {
      return new SearchResult.Unavailable(SearchResult.Reason.REEMBEDDING);
    }
    String providerKey = settings.embeddingProviderKey();
    String text =
        SearchTexts.of(
            request.alertTypes(), request.serviceKeys(), request.hypothesisComponentKey());
    EmbeddingResult answer =
        gateway.embed(
            new EmbeddingRequest(
                tenantId,
                providerKey,
                Ids.newId(),
                request.incidentId(),
                Set.of(DataCategory.ALERT_DATA, DataCategory.SERVICE_CONTEXT),
                List.of(text)));
    EmbeddingResult.Embedded embedded;
    switch (answer) {
      case EmbeddingResult.Unavailable unavailable -> {
        return new SearchResult.Unavailable(
            switch (unavailable.outcome()) {
              case UNREACHABLE -> SearchResult.Reason.UNREACHABLE;
              case REFUSED_BY_DATA_ROUTING -> SearchResult.Reason.REFUSED_BY_DATA_ROUTING;
              default -> SearchResult.Reason.PROVIDER_ERROR;
            });
      }
      case EmbeddingResult.Embedded e -> embedded = e;
    }
    if (embedded.vectors().size() != 1 || !Vectors.usable(embedded.vectors())) {
      return new SearchResult.Unavailable(SearchResult.Reason.PROVIDER_ERROR);
    }
    float[] query = embedded.vectors().get(0);
    return tx.execute(
        status -> {
          Optional<KnowledgeSettingsView> now = records.settings(tenantId);
          if (now.isEmpty()
              || now.get().embeddingState() == EmbeddingState.REEMBEDDING
              || !now.get().embeddingProviderKey().equals(providerKey)) {
            return new SearchResult.Unavailable(SearchResult.Reason.REEMBEDDING);
          }
          Instant searchedAt = Timestamps.now(clock);
          records.insertEmbedding(
              tenantId,
              new Embedding("incident", request.incidentId().toString(), text, query),
              providerKey,
              embedded.modelName(),
              searchedAt,
              false);
          Score best = null;
          for (Score score : records.scores(tenantId, providerKey, query)) {
            if (best == null || score.score().compareTo(best.score()) > 0) {
              best = score;
            }
          }
          BigDecimal minSimilarity = now.get().minSimilarity();
          PastIncidentView match = null;
          SimilarityResultView result;
          String stored = SearchTexts.cut(text, KnowledgeRecords.SEARCH_TEXT_MAX);
          if (best != null && best.score().compareTo(minSimilarity) >= 0) {
            match = records.pastIncident(tenantId, best.pastIncidentKey()).orElseThrow();
            result =
                new SimilarityResultView(
                    Ids.newId(),
                    request.incidentId(),
                    request.runId(),
                    stored,
                    providerKey,
                    SearchOutcome.MATCH,
                    match.pastIncidentKey(),
                    best.score(),
                    match.fixSuccessCount(),
                    match.fixActionKey(),
                    minSimilarity,
                    searchedAt);
          } else {
            result =
                new SimilarityResultView(
                    Ids.newId(),
                    request.incidentId(),
                    request.runId(),
                    stored,
                    providerKey,
                    SearchOutcome.NO_MATCH,
                    null,
                    best == null ? null : best.score(),
                    null,
                    null,
                    minSimilarity,
                    searchedAt);
          }
          records.insertResult(tenantId, result, searchedAt);
          return new SearchResult.Searched(result, match);
        });
  }

  @Override
  public List<RunbookView> applicableRunbooks(
      TenantId tenantId, Collection<String> alertTypes, String componentKind) {
    if (tenantId == null || alertTypes == null) {
      throw missing();
    }
    Optional<KnowledgeSettingsView> settings = records.settings(tenantId);
    if (settings.isEmpty()) {
      return List.of();
    }
    Set<String> types = new HashSet<>(alertTypes);
    record Candidate(RunbookView runbook, long shared) {}
    List<Candidate> candidates = new ArrayList<>();
    for (RunbookView runbook : records.runbooks(tenantId)) {
      if (!runbook.active()) {
        continue;
      }
      long shared = runbook.applicableAlertTypes().stream().filter(types::contains).count();
      boolean kindFits =
          runbook.applicableComponentKinds().isEmpty()
              || (componentKind != null
                  && runbook.applicableComponentKinds().contains(componentKind));
      if (shared > 0 && kindFits) {
        candidates.add(new Candidate(runbook, shared));
      }
    }
    return candidates.stream()
        .sorted(
            Comparator.comparingLong(Candidate::shared)
                .reversed()
                .thenComparing(c -> c.runbook().runbookKey(), SearchTexts.CODE_POINTS))
        .limit(settings.get().runbookLimit())
        .map(Candidate::runbook)
        .toList();
  }

  @Override
  public List<RunbookView> runbooks(TenantId tenantId) {
    required(tenantId);
    return records.runbooks(tenantId);
  }

  @Override
  public List<PastIncidentView> pastIncidents(TenantId tenantId) {
    required(tenantId);
    return records.pastIncidents(tenantId);
  }

  @Override
  public Optional<PastIncidentView> pastIncident(TenantId tenantId, String pastIncidentKey) {
    required(tenantId, pastIncidentKey);
    return records.pastIncident(tenantId, pastIncidentKey);
  }

  @Override
  public Optional<KnowledgeSettingsView> settings(TenantId tenantId) {
    required(tenantId);
    return records.settings(tenantId);
  }

  @Override
  public Optional<SimilarityResultView> latestSearchResult(TenantId tenantId, UUID incidentId) {
    required(tenantId, incidentId);
    return records.latestResult(tenantId, incidentId);
  }

  private static void required(Object... values) {
    for (Object value : values) {
      if (value == null) {
        throw missing();
      }
    }
  }

  private static IllegalArgumentException missing() {
    return new IllegalArgumentException("a required argument is missing");
  }
}
