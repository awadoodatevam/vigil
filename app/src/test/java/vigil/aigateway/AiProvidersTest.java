package vigil.aigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiPromptPart;
import vigil.connectors.api.AiProviderConnector;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.shared.TenantId;

class AiProvidersTest {

  /** A connector of one kind that does nothing. */
  private record TestConnector(ConnectorKind kind) implements AiProviderConnector {

    @Override
    public AiCompletion complete(
        TenantId tenantId,
        ConnectorInstanceView instance,
        String model,
        String systemText,
        List<AiPromptPart> parts,
        String outputSchemaName,
        JsonNode outputSchema,
        int maxOutputUnits) {
      throw new UnsupportedOperationException();
    }

    @Override
    public AiEmbedding embed(
        TenantId tenantId, ConnectorInstanceView instance, String model, List<String> texts) {
      throw new UnsupportedOperationException();
    }
  }

  @Test
  @DisplayName("AR-K-00e the registry refuses a non-AI kind and a second bean of one kind")
  void registry() {
    TestConnector claude = new TestConnector(ConnectorKind.CLAUDE);
    AiProviders registry = new AiProviders(List.of(claude));
    assertThat(registry.find(ConnectorKind.CLAUDE)).containsSame(claude);
    assertThat(registry.find(ConnectorKind.OPENAI)).isEmpty();
    assertThatThrownBy(
            () ->
                new AiProviders(
                    List.of(
                        new TestConnector(ConnectorKind.OPENAI),
                        new TestConnector(ConnectorKind.OPENAI))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("two connectors for openai");
    assertThatThrownBy(() -> new AiProviders(List.of(new TestConnector(ConnectorKind.ZABBIX))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("connector for zabbix is not an AI provider kind");
  }
}
