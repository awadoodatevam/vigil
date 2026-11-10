package vigil.aigateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.aigateway.api.AiGatewayPart;
import vigil.aigateway.api.AiGatewayPart.ProviderConfigurationPart;
import vigil.connectors.api.ConnectorsPart;
import vigil.connectors.api.ConnectorsPart.ConnectorInstancePart;
import vigil.connectors.api.ConnectorsPart.CredentialReferencePart;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;

/**
 * Seed parts for the gateway tests: part 3 with the five AI instances of the demo (kinds, handles
 * and settings of DD-F-07) at the addresses a test needs, and part 7 as DD-F-07 has it, or changed.
 */
public final class AiFixture {

  static final YAMLMapper YAML = YAMLMapper.builder().build();
  private static final Path DD_F_07 = Path.of("../docs/31-demo-data/07-settings.yaml");
  static final List<String> HANDLES =
      List.of("claude-api", "openai-api", "openai-embed-api", "local-llm", "local-embed");

  private AiFixture() {}

  /** The secrets {@code claude} and {@code openai}, generated now. */
  public static Map<String, String> secrets() {
    return Map.of("claude", TestSecrets.randomToken(), "openai", TestSecrets.randomToken());
  }

  /** Part 3 with the five AI instances, each at {@code address}. */
  public static ConnectorsPart connectors(String address) {
    Map<String, String> addresses = new LinkedHashMap<>();
    HANDLES.forEach(handle -> addresses.put(handle, address));
    return connectors(addresses);
  }

  /** Part 3 with the five AI instances, each at its address in {@code addresses} by handle. */
  public static ConnectorsPart connectors(Map<String, String> addresses) {
    JsonNode file = file();
    List<CredentialReferencePart> references = new ArrayList<>();
    for (JsonNode reference : file.get("credential_references")) {
      String handle = reference.get("handle").stringValue();
      if (handle.equals("cred-claude") || handle.equals("cred-openai")) {
        references.add(
            new CredentialReferencePart(
                handle,
                reference.get("purpose").stringValue(),
                reference.get("locator").stringValue()));
      }
    }
    List<ConnectorInstancePart> instances = new ArrayList<>();
    for (JsonNode instance : file.get("connector_instances")) {
      String handle = instance.get("handle").stringValue();
      if (!HANDLES.contains(handle)) {
        continue;
      }
      JsonNode credential = instance.get("credential_ref");
      instances.add(
          new ConnectorInstancePart(
              handle,
              instance.get("name").stringValue(),
              instance.get("kind").stringValue(),
              addresses.get(handle),
              instance.get("settings"),
              credential == null || credential.isNull() ? null : credential.stringValue()));
    }
    assertThat(instances).hasSize(5);
    return new ConnectorsPart(references, instances);
  }

  /** Part 7 as DD-F-07 has it. */
  public static AiGatewayPart demoPart() {
    JsonNode file = file();
    List<ProviderConfigurationPart> configurations = new ArrayList<>();
    for (JsonNode c : file.get("ai_provider_configurations")) {
      configurations.add(
          new ProviderConfigurationPart(
              c.get("provider_key").stringValue(),
              c.get("provider_kind").stringValue(),
              c.get("connector_instance").stringValue(),
              c.get("location").stringValue(),
              c.get("model_name").stringValue(),
              c.get("price_input_per_million"),
              c.get("price_output_per_million")));
    }
    return new AiGatewayPart(
        configurations,
        lists(file.get("allowed_providers")),
        lists(file.get("task_assignments")),
        lists(file.get("data_routing_rules")));
  }

  /** The part with the configuration {@code providerKey} changed. */
  public static AiGatewayPart configuration(
      AiGatewayPart part, String providerKey, UnaryOperator<ProviderConfigurationPart> change) {
    List<ProviderConfigurationPart> configurations = new ArrayList<>();
    boolean found = false;
    for (ProviderConfigurationPart c : part.providerConfigurations()) {
      boolean match = !found && c.providerKey().equals(providerKey);
      found |= match;
      configurations.add(match ? change.apply(c) : c);
    }
    assertThat(found).as("configuration %s", providerKey).isTrue();
    return new AiGatewayPart(
        configurations, part.allowedProviders(), part.taskAssignments(), part.dataRoutingRules());
  }

  /** The part with its configurations replaced. */
  public static AiGatewayPart configurations(
      AiGatewayPart part, List<ProviderConfigurationPart> configurations) {
    return new AiGatewayPart(
        configurations, part.allowedProviders(), part.taskAssignments(), part.dataRoutingRules());
  }

  /** The part with the allowed providers changed. */
  public static AiGatewayPart allowed(
      AiGatewayPart part, UnaryOperator<Map<String, List<String>>> change) {
    return new AiGatewayPart(
        part.providerConfigurations(),
        change.apply(new LinkedHashMap<>(part.allowedProviders())),
        part.taskAssignments(),
        part.dataRoutingRules());
  }

  /** The part with the task assignments changed. */
  public static AiGatewayPart assignments(
      AiGatewayPart part, UnaryOperator<Map<String, List<String>>> change) {
    return new AiGatewayPart(
        part.providerConfigurations(),
        part.allowedProviders(),
        change.apply(new LinkedHashMap<>(part.taskAssignments())),
        part.dataRoutingRules());
  }

  /** The part with the data routing rules changed. */
  public static AiGatewayPart rules(
      AiGatewayPart part, UnaryOperator<Map<String, List<String>>> change) {
    return new AiGatewayPart(
        part.providerConfigurations(),
        part.allowedProviders(),
        part.taskAssignments(),
        change.apply(new LinkedHashMap<>(part.dataRoutingRules())));
  }

  static ProviderConfigurationPart withKey(ProviderConfigurationPart c, String key) {
    return new ProviderConfigurationPart(
        key,
        c.providerKind(),
        c.connectorInstance(),
        c.location(),
        c.modelName(),
        c.priceInputPerMillion(),
        c.priceOutputPerMillion());
  }

  static ProviderConfigurationPart withKind(ProviderConfigurationPart c, String kind) {
    return new ProviderConfigurationPart(
        c.providerKey(),
        kind,
        c.connectorInstance(),
        c.location(),
        c.modelName(),
        c.priceInputPerMillion(),
        c.priceOutputPerMillion());
  }

  static ProviderConfigurationPart withInstance(ProviderConfigurationPart c, String instance) {
    return new ProviderConfigurationPart(
        c.providerKey(),
        c.providerKind(),
        instance,
        c.location(),
        c.modelName(),
        c.priceInputPerMillion(),
        c.priceOutputPerMillion());
  }

  static ProviderConfigurationPart withLocation(ProviderConfigurationPart c, String location) {
    return new ProviderConfigurationPart(
        c.providerKey(),
        c.providerKind(),
        c.connectorInstance(),
        location,
        c.modelName(),
        c.priceInputPerMillion(),
        c.priceOutputPerMillion());
  }

  static ProviderConfigurationPart withModel(ProviderConfigurationPart c, String model) {
    return new ProviderConfigurationPart(
        c.providerKey(),
        c.providerKind(),
        c.connectorInstance(),
        c.location(),
        model,
        c.priceInputPerMillion(),
        c.priceOutputPerMillion());
  }

  /** The configuration with its prices given as YAML text ({@code null} for absent). */
  static ProviderConfigurationPart withPrices(
      ProviderConfigurationPart c, String input, String output) {
    return new ProviderConfigurationPart(
        c.providerKey(),
        c.providerKind(),
        c.connectorInstance(),
        c.location(),
        c.modelName(),
        input == null ? null : yaml(input),
        output == null ? null : yaml(output));
  }

  /** The tree of a YAML value as the seed import reads it. */
  static JsonNode yaml(String text) {
    return YAML.readTree("value: " + text).get("value");
  }

  /** The problems of an import refusal of part 7. */
  @SuppressWarnings("unchecked")
  static List<String> problems(VigilError error) {
    assertThat(error.code()).isEqualTo(VigilError.Code.INVALID);
    assertThat(error.message()).isEqualTo("Part AI gateway refused.");
    return (List<String>) error.details().get("problems");
  }

  private static Map<String, List<String>> lists(JsonNode section) {
    Map<String, List<String>> lists = new LinkedHashMap<>();
    for (Map.Entry<String, JsonNode> entry : section.properties()) {
      List<String> items = new ArrayList<>();
      entry.getValue().forEach(item -> items.add(item.asString()));
      lists.put(entry.getKey(), items);
    }
    return lists;
  }

  private static JsonNode file() {
    return YAML.readTree(DD_F_07.toFile());
  }
}
