package vigil.setup;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import vigil.aigateway.api.AiGatewayService;
import vigil.knowledge.api.KnowledgeService;
import vigil.knowledge.api.KnowledgeSettingsView;
import vigil.shared.TenantId;

/** DM-R-60: the embedding provider of the knowledge settings of part 8 exists in part 7. */
@Component
class KnowledgeProviderCheck implements SeedKeyCheck {

  private final KnowledgeService knowledge;
  private final AiGatewayService gateway;

  KnowledgeProviderCheck(KnowledgeService knowledge, AiGatewayService gateway) {
    this.knowledge = knowledge;
    this.gateway = gateway;
  }

  @Override
  public int referringPart() {
    return 8;
  }

  @Override
  public int targetPart() {
    return 7;
  }

  @Override
  public List<String> problems(
      TenantId tenantId,
      SeedBundle bundle,
      Map<String, JsonNode> files,
      Map<String, UUID> handles) {
    return knowledge
        .settings(tenantId)
        .map(KnowledgeSettingsView::embeddingProviderKey)
        .filter(key -> gateway.provider(tenantId, key).isEmpty())
        .map(key -> List.of("knowledge settings: provider '" + key + "' does not exist"))
        .orElse(List.of());
  }
}
