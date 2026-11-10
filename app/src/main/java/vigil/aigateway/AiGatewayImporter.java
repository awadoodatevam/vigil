package vigil.aigateway;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import vigil.aigateway.AiGatewayRecords.ProviderConfiguration;
import vigil.aigateway.api.AiGatewayImport;
import vigil.aigateway.api.AiGatewayPart;
import vigil.aigateway.api.AiGatewayPart.ProviderConfigurationPart;
import vigil.aigateway.api.AiTask;
import vigil.aigateway.api.DataCategory;
import vigil.aigateway.api.ProviderLocation;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;

/**
 * The seed import of part 7: provider configurations, allowed providers, task assignments and data
 * routing rules (DM-E-70 to DM-E-73), checked as a whole (DM-R-57; P-19 spec Section 6.8). It
 * writes no audit event and publishes no event.
 */
@Service
class AiGatewayImporter implements AiGatewayImport {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final List<String> KINDS =
      List.of("claude", "openai", "local_model", "local_embedding");
  private static final String CONFIGURATIONS = "ai_provider_configurations";
  private static final String ALLOWED = "allowed_providers";
  private static final String ASSIGNMENTS = "task_assignments";
  private static final String RULES = "data_routing_rules";
  private static final int MODEL_MAX = 100;
  private static final int PRICE_DIGITS = 6;
  private static final Actor SETUP = Actor.module("vigil-setup");
  private static final List<String> TASKS = List.of("SL-T-1", "SL-T-2", "SL-T-3");

  /** DM-E71-R1, in its order. */
  static final Map<AiTask, List<String>> ALLOWED_KINDS =
      Map.of(
          AiTask.SL_T_1, List.of("claude", "openai", "local_model"),
          AiTask.SL_T_2, List.of("claude", "openai", "local_model"),
          AiTask.SL_T_3, List.of("local_embedding", "openai"));

  private final AiGatewayRecords records;
  private final ConnectorService connectors;
  private final TransactionTemplate tx;
  private final Clock clock;

  AiGatewayImporter(
      AiGatewayRecords records,
      ConnectorService connectors,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.records = records;
    this.connectors = connectors;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  @Override
  public Map<String, UUID> importPart(
      TenantId tenantId, AiGatewayPart part, Instant importStartedAt, Map<String, UUID> handles) {
    required(tenantId, part, importStartedAt, handles);
    required(
        part.providerConfigurations(),
        part.allowedProviders(),
        part.taskAssignments(),
        part.dataRoutingRules());
    required(part.providerConfigurations().toArray());
    return tx.execute(
        status -> {
          List<String> problems = problems(tenantId, part, handles);
          if (!problems.isEmpty()) {
            throw new VigilError(
                VigilError.Code.INVALID,
                "Part AI gateway refused.",
                Map.of("problems", List.copyOf(problems)));
          }
          store(tenantId, part, handles);
          return Map.of();
        });
  }

  private List<String> problems(TenantId tenantId, AiGatewayPart part, Map<String, UUID> handles) {
    if (records.hasRecords(tenantId)) {
      return List.of("AI gateway records already exist");
    }
    List<String> problems = new ArrayList<>();
    Set<String> keys = new HashSet<>();
    Map<String, String> kindOfKey = new HashMap<>();
    List<ProviderConfigurationPart> configurations = part.providerConfigurations();
    for (int i = 0; i < configurations.size(); i++) {
      ProviderConfigurationPart configuration = configurations.get(i);
      configurationProblems(
          tenantId, CONFIGURATIONS + "[" + i + "]", configuration, handles, keys, problems);
      if (configuration.providerKey() != null) {
        kindOfKey.putIfAbsent(configuration.providerKey(), configuration.providerKind());
      }
    }
    allowedProblems(part.allowedProviders(), problems);
    assignmentProblems(part.taskAssignments(), kindOfKey, problems);
    ruleProblems(part.dataRoutingRules(), kindOfKey.keySet(), problems);
    return problems;
  }

  private void configurationProblems(
      TenantId tenantId,
      String at,
      ProviderConfigurationPart configuration,
      Map<String, UUID> handles,
      Set<String> keys,
      List<String> problems) {
    String key = configuration.providerKey();
    if (!isKey(key)) {
      problems.add(at + ".provider_key: not a key");
    } else if (!keys.add(key)) {
      problems.add(at + ".provider_key: used more than once");
    }
    String kind = configuration.providerKind();
    boolean kindValid = KINDS.contains(kind);
    if (!kindValid) {
      problems.add(at + ".provider_kind: not one of " + String.join(", ", KINDS));
    }
    Optional<ConnectorInstanceView> instance =
        handle(handles, configuration.connectorInstance())
            .flatMap(id -> connectors.instance(tenantId, id));
    if (instance.isEmpty()) {
      problems.add(at + ".connector_instance: not a connector instance of part 3");
    } else if (kindValid && !instance.get().kind().key().equals(kind)) {
      problems.add(at + ".connector_instance: not a " + kind + " instance");
    }
    if (kindValid) {
      String location = location(kind).key();
      if (!location.equals(configuration.location())) {
        problems.add(at + ".location: must be " + location + " for " + kind);
      }
    }
    if (!lengthBetween(configuration.modelName(), MODEL_MAX)) {
      problems.add(at + ".model_name: 1 to 100 characters");
    }
    BigDecimal input = price(configuration.priceInputPerMillion());
    BigDecimal output = price(configuration.priceOutputPerMillion());
    if (input == null) {
      problems.add(at + ".price_input_per_million: not a price");
    }
    if (output == null) {
      problems.add(at + ".price_output_per_million: not a price");
    }
    if (ProviderLocation.IN_HOUSE.key().equals(configuration.location())) {
      if (input != null && input.signum() != 0) {
        problems.add(at + ".price_input_per_million: must be 0 for in_house");
      }
      if (output != null && output.signum() != 0) {
        problems.add(at + ".price_output_per_million: must be 0 for in_house");
      }
    }
  }

  private static void allowedProblems(Map<String, List<String>> allowed, List<String> problems) {
    for (Map.Entry<String, List<String>> entry : allowed.entrySet()) {
      Optional<AiTask> task = AiTask.fromKey(entry.getKey());
      if (task.isEmpty()) {
        problems.add(ALLOWED + ": unknown key " + entry.getKey());
        continue;
      }
      List<String> expected = ALLOWED_KINDS.get(task.get());
      List<String> kinds = entry.getValue();
      if (kinds == null
          || kinds.size() != expected.size()
          || !new HashSet<>(kinds).equals(new HashSet<>(expected))) {
        problems.add(
            ALLOWED + "." + entry.getKey() + ": must be exactly " + String.join(", ", expected));
      }
    }
    missing(ALLOWED, TASKS, allowed, problems);
  }

  private static void assignmentProblems(
      Map<String, List<String>> assignments, Map<String, String> kindOfKey, List<String> problems) {
    for (Map.Entry<String, List<String>> entry : assignments.entrySet()) {
      Optional<AiTask> task = AiTask.fromKey(entry.getKey());
      if (task.isEmpty()) {
        problems.add(ASSIGNMENTS + ": unknown key " + entry.getKey());
        continue;
      }
      String at = ASSIGNMENTS + "." + entry.getKey();
      List<String> keys = entry.getValue() == null ? List.of() : entry.getValue();
      if (keys.isEmpty()) {
        problems.add(at + ": at least one provider");
        continue;
      }
      Set<String> seen = new HashSet<>();
      for (String key : keys) {
        if (!seen.add(key)) {
          problems.add(at + ": duplicate " + key);
        } else if (!kindOfKey.containsKey(key)) {
          problems.add(at + ": unknown provider " + key);
        } else if (!ALLOWED_KINDS.get(task.get()).contains(kindOfKey.get(key))) {
          problems.add(at + ": " + key + " is not allowed for " + entry.getKey());
        }
      }
      if (task.get() == AiTask.SL_T_3 && keys.size() != 1) {
        problems.add(at + ": SL-T-3 has exactly one provider (DM-E72-R2)");
      }
    }
    missing(ASSIGNMENTS, TASKS, assignments, problems);
  }

  private static void ruleProblems(
      Map<String, List<String>> rules, Set<String> providerKeys, List<String> problems) {
    List<String> categories = new ArrayList<>();
    for (DataCategory category : DataCategory.values()) {
      categories.add(category.key());
    }
    for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
      if (DataCategory.fromKey(entry.getKey()).isEmpty()) {
        problems.add(RULES + ": unknown key " + entry.getKey());
        continue;
      }
      String at = RULES + "." + entry.getKey();
      Set<String> seen = new HashSet<>();
      for (String key : entry.getValue() == null ? List.<String>of() : entry.getValue()) {
        if (!seen.add(key)) {
          problems.add(at + ": duplicate " + key);
        } else if (!providerKeys.contains(key)) {
          problems.add(at + ": unknown provider " + key);
        }
      }
    }
    missing(RULES, categories, rules, problems);
  }

  private static void missing(
      String section, List<String> keys, Map<String, ?> present, List<String> problems) {
    for (String key : keys) {
      if (!present.containsKey(key)) {
        problems.add(section + ": missing " + key);
      }
    }
  }

  private void store(TenantId tenantId, AiGatewayPart part, Map<String, UUID> handles) {
    Instant now = Timestamps.now(clock);
    for (ProviderConfigurationPart configuration : part.providerConfigurations()) {
      records.insertConfiguration(
          tenantId,
          Ids.newId(),
          new ProviderConfiguration(
              configuration.providerKey(),
              configuration.providerKind(),
              handles.get(configuration.connectorInstance()),
              configuration.modelName(),
              location(configuration.providerKind()),
              price(configuration.priceInputPerMillion()),
              price(configuration.priceOutputPerMillion()),
              true),
          now);
    }
    for (Map.Entry<String, List<String>> entry : part.allowedProviders().entrySet()) {
      AiTask task = AiTask.fromKey(entry.getKey()).orElseThrow();
      for (String kind : entry.getValue()) {
        records.insertAllowedProvider(tenantId, Ids.newId(), task, kind, now);
      }
    }
    for (Map.Entry<String, List<String>> entry : part.taskAssignments().entrySet()) {
      records.insertTaskAssignment(
          tenantId,
          Ids.newId(),
          AiTask.fromKey(entry.getKey()).orElseThrow(),
          List.copyOf(entry.getValue()),
          SETUP,
          now);
    }
    Map<String, List<String>> sorted = new LinkedHashMap<>();
    part.dataRoutingRules()
        .forEach(
            (category, keys) ->
                sorted.put(category, new LinkedHashSet<>(keys).stream().sorted().toList()));
    sorted.forEach(
        (category, keys) ->
            records.insertDataRoutingRule(tenantId, Ids.newId(), category, keys, SETUP, now));
  }

  /** The location a kind requires: external for Claude and OpenAI, in-house for local models. */
  private static ProviderLocation location(String kind) {
    return kind.equals("claude") || kind.equals("openai")
        ? ProviderLocation.EXTERNAL
        : ProviderLocation.IN_HOUSE;
  }

  /**
   * A price: a YAML number that is finite, not negative, with at most 6 digits after the point and
   * 6 before it once trailing zeros are removed; otherwise {@code null}.
   */
  static BigDecimal price(JsonNode node) {
    if (node == null || !node.isNumber()) {
      return null;
    }
    if ((node.isDouble() || node.isFloat()) && !Double.isFinite(node.doubleValue())) {
      return null;
    }
    BigDecimal value;
    try {
      value = node.decimalValue().stripTrailingZeros();
    } catch (RuntimeException e) {
      return null;
    }
    if (value.signum() < 0
        || value.scale() > PRICE_DIGITS
        || value.precision() - value.scale() > PRICE_DIGITS) {
      return null;
    }
    return value;
  }

  private static Optional<UUID> handle(Map<String, UUID> handles, String handle) {
    return handle == null ? Optional.empty() : Optional.ofNullable(handles.get(handle));
  }

  private static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }

  private static boolean lengthBetween(String value, int max) {
    if (value == null) {
      return false;
    }
    int length = value.codePointCount(0, value.length());
    return length >= 1 && length <= max;
  }

  private static void required(Object... arguments) {
    for (Object argument : arguments) {
      if (argument == null) {
        throw new IllegalArgumentException("a required argument is missing");
      }
    }
  }
}
