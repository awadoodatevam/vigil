package vigil.setup;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.aigateway.api.AiGatewayImport;
import vigil.aigateway.api.AiGatewayPart;
import vigil.aigateway.api.AiGatewayPart.ProviderConfigurationPart;
import vigil.setup.SeedShapes.Row;
import vigil.shared.TenantId;

/** Seed part 7, AI gateway, imported through {@link AiGatewayImport} (DM-R-56 (7)). */
@Component
class AiGatewayAdapter implements SeedPartImporter {

  private static final String PRICE_INPUT = "price_input_per_million";
  private static final String PRICE_OUTPUT = "price_output_per_million";

  private final AiGatewayImport aiGatewayImport;

  AiGatewayAdapter(AiGatewayImport aiGatewayImport) {
    this.aiGatewayImport = aiGatewayImport;
  }

  @Override
  public int part() {
    return 7;
  }

  @Override
  public PartResult importPart(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    JsonNode file = files.get(SeedParts.SETTINGS);
    SeedShapes shapes = new SeedShapes();
    List<Row> configurations =
        shapes.list(
            file,
            "ai_provider_configurations",
            List.of(
                "provider_key",
                "provider_kind",
                "connector_instance",
                "location",
                "model_name",
                PRICE_INPUT,
                PRICE_OUTPUT),
            Set.of(PRICE_INPUT, PRICE_OUTPUT));
    Map<String, List<String>> allowed = shapes.lists(file, "allowed_providers");
    Map<String, List<String>> assignments = shapes.lists(file, "task_assignments");
    Map<String, List<String>> rules = shapes.lists(file, "data_routing_rules");
    shapes.refuseIfAny(SeedParts.part(part()).name());
    aiGatewayImport.importPart(
        tenantId,
        new AiGatewayPart(
            configurations.stream()
                .map(
                    c ->
                        new ProviderConfigurationPart(
                            c.get("provider_key"),
                            c.get("provider_kind"),
                            c.get("connector_instance"),
                            c.get("location"),
                            c.get("model_name"),
                            c.tree(PRICE_INPUT),
                            c.tree(PRICE_OUTPUT)))
                .toList(),
            allowed,
            assignments,
            rules),
        importStartedAt,
        handles);
    Map<String, Integer> counts = new LinkedHashMap<>();
    counts.put("ai_provider_configurations", configurations.size());
    counts.put("allowed_providers", allowed.size());
    counts.put("task_assignments", assignments.size());
    counts.put("data_routing_rules", rules.size());
    return new PartResult(counts, Map.of());
  }
}
