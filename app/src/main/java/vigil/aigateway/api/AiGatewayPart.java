package vigil.aigateway.api;

import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * Seed part 7 as read from {@code 07-settings.yaml}: provider configurations, allowed providers,
 * task assignments and data routing rules (DM-E-70 to DM-E-73). Prices are the YAML trees.
 */
public record AiGatewayPart(
    List<ProviderConfigurationPart> providerConfigurations,
    Map<String, List<String>> allowedProviders,
    Map<String, List<String>> taskAssignments,
    Map<String, List<String>> dataRoutingRules) {

  /** One entry of {@code ai_provider_configurations}; {@code connectorInstance} is a handle. */
  public record ProviderConfigurationPart(
      String providerKey,
      String providerKind,
      String connectorInstance,
      String location,
      String modelName,
      JsonNode priceInputPerMillion,
      JsonNode priceOutputPerMillion) {}
}
