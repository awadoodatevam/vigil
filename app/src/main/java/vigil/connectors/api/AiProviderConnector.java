package vigil.connectors.api;

import java.util.List;
import tools.jackson.databind.JsonNode;
import vigil.shared.TenantId;

/**
 * The AI provider connector type (AR-K-00e): one bean per AI provider kind in vigil-connectors;
 * vigil-ai-gateway calls it.
 */
public interface AiProviderConnector {

  /** The kind this connector serves; its type is {@link ConnectorType#AI_PROVIDER}. */
  ConnectorKind kind();

  /**
   * Asks the instance's provider for one structured answer of {@code model}.
   *
   * <p>external call: not inside a transaction
   */
  AiCompletion complete(
      TenantId tenantId,
      ConnectorInstanceView instance,
      String model,
      String systemText,
      List<AiPromptPart> parts,
      String outputSchemaName,
      JsonNode outputSchema,
      int maxOutputUnits);

  /**
   * Asks the instance's provider for one vector per text of {@code model}, in one call.
   *
   * <p>external call: not inside a transaction
   */
  AiEmbedding embed(
      TenantId tenantId, ConnectorInstanceView instance, String model, List<String> texts);
}
