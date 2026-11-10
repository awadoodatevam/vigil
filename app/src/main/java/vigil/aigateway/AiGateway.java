package vigil.aigateway;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.aigateway.AiGatewayRecords.ProviderConfiguration;
import vigil.aigateway.AiGatewayRecords.Usage;
import vigil.aigateway.api.AiGatewayService;
import vigil.aigateway.api.AiPart;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.AnswerFinish;
import vigil.aigateway.api.CompletionRequest;
import vigil.aigateway.api.CompletionResult;
import vigil.aigateway.api.EmbeddingRequest;
import vigil.aigateway.api.EmbeddingResult;
import vigil.aigateway.api.UnavailableReason;
import vigil.aigateway.api.UsageOutcome;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiFailure;
import vigil.connectors.api.AiPromptPart;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.TransactionGuard;

/**
 * The only path from Vigil to AI providers (PM-M18, SL-B-08; P-19 spec Sections 6.6 and 6.7):
 * allowed providers, task assignments, data routing, fallback for SL-T-1 and SL-T-2, the named
 * provider of SL-T-3, and one usage record per provider call, inserted after the call (AR-R-09).
 */
@Service
class AiGateway implements AiGatewayService {

  static final String UNAVAILABLE_INSTANCE = "The connector instance is not available.";
  private static final Logger LOG = LoggerFactory.getLogger(AiGateway.class);
  private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal COST_LIMIT = new BigDecimal("1000000");
  private static final int COST_SCALE = 6;

  private final AiGatewayRecords records;
  private final AiProviders providers;
  private final ConnectorService connectors;
  private final TransactionTemplate tx;
  private final TransactionTemplate readTx;
  private final Clock clock;

  /** What step 1 reads, in one read-only transaction. */
  private record Settings(
      Optional<List<String>> assignment,
      Map<String, ProviderConfiguration> configurations,
      Set<String> allowedKinds,
      Map<String, Set<String>> rules) {}

  /** The outcome of one call: the connector's answer or failure, with its times. */
  private record Call(
      UsageOutcome outcome, String reason, long inputUnits, long outputUnits, Instant started) {}

  AiGateway(
      AiGatewayRecords records,
      AiProviders providers,
      ConnectorService connectors,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.providers = providers;
    this.connectors = connectors;
    this.tx = new TransactionTemplate(transactionManager);
    this.readTx = new TransactionTemplate(transactionManager);
    this.readTx.setReadOnly(true);
    this.clock = clock;
  }

  @Override
  public CompletionResult complete(CompletionRequest request) {
    TransactionGuard.assertNoTransaction();
    check(request);
    TenantId tenantId = request.tenantId();
    AiTask task = request.task();
    Settings settings = readTx.execute(status -> settings(tenantId, task));
    if (settings.assignment().isEmpty()) {
      return new CompletionResult.Unavailable(UnavailableReason.NO_PROVIDER_REACHABLE);
    }
    Set<String> categories = new TreeSet<>();
    request.parts().forEach(part -> categories.add(part.dataCategory().key()));
    List<AiPromptPart> parts =
        request.parts().stream()
            .map(
                part ->
                    new AiPromptPart(part.label(), part.dataCategory().displayName(), part.text()))
            .toList();
    boolean used = false;
    boolean allRefused = true;
    List<String> keys = settings.assignment().get();
    for (int i = 0; i < keys.size(); i++) {
      int position = i + 1;
      ProviderConfiguration configuration = usable(settings, keys.get(i));
      if (configuration == null) {
        continue;
      }
      used = true;
      if (!allowed(settings.rules(), configuration.providerKey(), categories)) {
        refused(
            tenantId,
            task,
            request.requestGroupId(),
            request.incidentId(),
            position,
            categories,
            configuration);
        continue;
      }
      allRefused = false;
      Optional<ConnectorInstanceView> instance = instance(tenantId, configuration);
      Instant started = Timestamps.now(clock);
      AiCompletion answer =
          instance.isEmpty()
              ? new AiCompletion.Failed(AiFailure.PROVIDER_ERROR, UNAVAILABLE_INSTANCE)
              : connector(configuration)
                  .complete(
                      tenantId,
                      instance.get(),
                      configuration.modelName(),
                      request.systemText(),
                      parts,
                      request.outputSchemaName(),
                      request.outputSchema(),
                      request.maxOutputUnits());
      Call call =
          switch (answer) {
            case AiCompletion.Answered answered ->
                new Call(
                    UsageOutcome.SUCCEEDED,
                    null,
                    answered.inputUnits(),
                    answered.outputUnits(),
                    started);
            case AiCompletion.Failed failed ->
                new Call(outcome(failed.failure()), failed.reason(), 0, 0, started);
          };
      store(
          tenantId,
          task,
          request.requestGroupId(),
          request.incidentId(),
          position,
          categories,
          configuration,
          call);
      if (answer instanceof AiCompletion.Answered answered) {
        return new CompletionResult.Answered(
            configuration.providerKey(),
            configuration.location(),
            configuration.modelName(),
            position,
            answered.outputText(),
            AnswerFinish.valueOf(answered.finish().name()),
            answered.inputUnits(),
            answered.outputUnits());
      }
    }
    return new CompletionResult.Unavailable(
        used && allRefused
            ? UnavailableReason.NO_PROVIDER_ALLOWED
            : UnavailableReason.NO_PROVIDER_REACHABLE);
  }

  @Override
  public EmbeddingResult embed(EmbeddingRequest request) {
    TransactionGuard.assertNoTransaction();
    check(request);
    TenantId tenantId = request.tenantId();
    Settings settings = readTx.execute(status -> settings(tenantId, AiTask.SL_T_3));
    ProviderConfiguration configuration = usable(settings, request.providerKey());
    if (configuration == null) {
      return new EmbeddingResult.Unavailable(UsageOutcome.PROVIDER_ERROR);
    }
    Set<String> categories = new TreeSet<>();
    request.dataCategories().forEach(category -> categories.add(category.key()));
    if (!allowed(settings.rules(), configuration.providerKey(), categories)) {
      refused(
          tenantId,
          AiTask.SL_T_3,
          request.requestGroupId(),
          request.incidentId(),
          1,
          categories,
          configuration);
      return new EmbeddingResult.Unavailable(UsageOutcome.REFUSED_BY_DATA_ROUTING);
    }
    Optional<ConnectorInstanceView> instance = instance(tenantId, configuration);
    Instant started = Timestamps.now(clock);
    AiEmbedding answer =
        instance.isEmpty()
            ? new AiEmbedding.Failed(AiFailure.PROVIDER_ERROR, UNAVAILABLE_INSTANCE)
            : connector(configuration)
                .embed(tenantId, instance.get(), configuration.modelName(), request.texts());
    Call call =
        switch (answer) {
          case AiEmbedding.Embedded embedded ->
              new Call(UsageOutcome.SUCCEEDED, null, embedded.inputUnits(), 0, started);
          case AiEmbedding.Failed failed ->
              new Call(outcome(failed.failure()), failed.reason(), 0, 0, started);
        };
    store(
        tenantId,
        AiTask.SL_T_3,
        request.requestGroupId(),
        request.incidentId(),
        1,
        categories,
        configuration,
        call);
    if (answer instanceof AiEmbedding.Embedded embedded) {
      return new EmbeddingResult.Embedded(
          configuration.providerKey(),
          configuration.modelName(),
          embedded.vectors(),
          embedded.inputUnits());
    }
    return new EmbeddingResult.Unavailable(call.outcome());
  }

  /**
   * The cost of AR-G-14: (input units × input price + output units × output price) ÷ 1,000,000,
   * rounded half-up to 6 decimal places.
   */
  static BigDecimal cost(
      long inputUnits, long outputUnits, BigDecimal inputPrice, BigDecimal outputPrice) {
    return BigDecimal.valueOf(inputUnits)
        .multiply(inputPrice)
        .add(BigDecimal.valueOf(outputUnits).multiply(outputPrice))
        .divide(MILLION, COST_SCALE, RoundingMode.HALF_UP);
  }

  private Settings settings(TenantId tenantId, AiTask task) {
    return new Settings(
        records.taskAssignment(tenantId, task),
        records.configurations(tenantId),
        records.allowedKinds(tenantId, task),
        records.dataRoutingRules(tenantId));
  }

  /** The key's configuration when it exists, is active and is of an allowed kind; else null. */
  private static ProviderConfiguration usable(Settings settings, String providerKey) {
    ProviderConfiguration configuration = settings.configurations().get(providerKey);
    if (configuration == null
        || !configuration.active()
        || !settings.allowedKinds().contains(configuration.providerKind())) {
      return null;
    }
    return configuration;
  }

  /** Whether the key is allowed for every category (DM-R-45). */
  private static boolean allowed(
      Map<String, Set<String>> rules, String providerKey, Set<String> categories) {
    return categories.stream()
        .allMatch(category -> rules.getOrDefault(category, Set.of()).contains(providerKey));
  }

  private Optional<ConnectorInstanceView> instance(
      TenantId tenantId, ProviderConfiguration configuration) {
    return connectors
        .instance(tenantId, configuration.connectorInstanceId())
        .filter(ConnectorInstanceView::active)
        .filter(instance -> instance.kind().key().equals(configuration.providerKind()))
        .filter(instance -> providers.find(instance.kind()).isPresent());
  }

  private AiProviderConnector connector(ProviderConfiguration configuration) {
    return providers
        .find(ConnectorKind.fromKey(configuration.providerKind()).orElseThrow())
        .orElseThrow();
  }

  private void refused(
      TenantId tenantId,
      AiTask task,
      UUID requestGroupId,
      UUID incidentId,
      int position,
      Set<String> categories,
      ProviderConfiguration configuration) {
    Instant now = Timestamps.now(clock);
    insert(
        tenantId,
        new Usage(
            task,
            configuration.providerKey(),
            configuration.location(),
            configuration.modelName(),
            requestGroupId,
            incidentId,
            position,
            List.copyOf(categories),
            UsageOutcome.REFUSED_BY_DATA_ROUTING,
            0,
            0,
            BigDecimal.ZERO.setScale(COST_SCALE),
            now,
            now));
    LOG.info(
        "AI call refused by data routing: task {}, provider {}",
        task.key(),
        configuration.providerKey());
  }

  private void store(
      TenantId tenantId,
      AiTask task,
      UUID requestGroupId,
      UUID incidentId,
      int position,
      Set<String> categories,
      ProviderConfiguration configuration,
      Call call) {
    Instant finished = Timestamps.now(clock);
    long input = call.inputUnits();
    long output = call.outputUnits();
    BigDecimal cost =
        cost(
            input,
            output,
            configuration.priceInputPerMillion(),
            configuration.priceOutputPerMillion());
    if (cost.compareTo(COST_LIMIT) >= 0) {
      input = 0;
      output = 0;
      cost = BigDecimal.ZERO.setScale(COST_SCALE);
    }
    insert(
        tenantId,
        new Usage(
            task,
            configuration.providerKey(),
            configuration.location(),
            configuration.modelName(),
            requestGroupId,
            incidentId,
            position,
            List.copyOf(categories),
            call.outcome(),
            input,
            output,
            cost,
            call.started(),
            finished));
    if (call.outcome() != UsageOutcome.SUCCEEDED) {
      LOG.warn(
          "AI call failed: task {}, provider {}, outcome {}, reason {}",
          task.key(),
          configuration.providerKey(),
          call.outcome().key(),
          call.reason());
    }
  }

  private void insert(TenantId tenantId, Usage usage) {
    tx.executeWithoutResult(
        status -> records.insertUsage(tenantId, Ids.newId(), usage, Timestamps.now(clock)));
  }

  private static UsageOutcome outcome(AiFailure failure) {
    return failure == AiFailure.UNREACHABLE
        ? UsageOutcome.UNREACHABLE
        : UsageOutcome.PROVIDER_ERROR;
  }

  /** The argument rules of P-19 spec Section 6.3 for {@code complete}. */
  private static void check(CompletionRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request: required");
    }
    if (request.task() != AiTask.SL_T_1 && request.task() != AiTask.SL_T_2) {
      throw new IllegalArgumentException("task: SL-T-1 or SL-T-2");
    }
    required(
        request.tenantId(),
        request.requestGroupId(),
        request.systemText(),
        request.parts(),
        request.outputSchemaName(),
        request.outputSchema());
    elements(request.parts());
    for (AiPart part : request.parts()) {
      required(part.label(), part.dataCategory(), part.text());
    }
    if (request.maxOutputUnits() < 1) {
      throw new IllegalArgumentException("maxOutputUnits: at least 1");
    }
  }

  /** The argument rules of P-19 spec Section 6.3 for {@code embed}. */
  private static void check(EmbeddingRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("request: required");
    }
    required(
        request.tenantId(),
        request.providerKey(),
        request.requestGroupId(),
        request.dataCategories(),
        request.texts());
    elements(request.texts());
    if (request.dataCategories().isEmpty()
        || request.dataCategories().stream().anyMatch(category -> category == null)) {
      throw new IllegalArgumentException("dataCategories: not empty");
    }
  }

  private static void required(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }

  private static void elements(List<?> list) {
    if (list.isEmpty() || list.stream().anyMatch(element -> element == null)) {
      throw new IllegalArgumentException("a list argument is empty or holds null");
    }
  }
}
