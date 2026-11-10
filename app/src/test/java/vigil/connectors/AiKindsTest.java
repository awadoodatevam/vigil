package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import vigil.connectors.api.AiCompletion;
import vigil.connectors.api.AiEmbedding;
import vigil.connectors.api.AiFailure;
import vigil.connectors.api.AiPromptPart;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorType;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.SettingType;
import vigil.shared.Ids;
import vigil.shared.TenantId;

class AiKindsTest {

  private static final TenantId TENANT = new TenantId(Ids.newId());
  private static final JsonNode SCHEMA = Recordings.tree("{\"type\":\"object\"}");
  private static final List<AiPromptPart> PARTS =
      List.of(new AiPromptPart("Member alerts", "Alert data", "text"));

  /** Without records, secrets or HTTP client: any network call would fail with an exception. */
  private static final ConnectorTimings TIMINGS = new ConnectorConfiguration().connectorTimings();

  private static ConnectorInstanceView instance(ConnectorKind kind) {
    return new ConnectorInstanceView(
        TENANT,
        Ids.newId(),
        kind.key(),
        kind,
        "http://127.0.0.1:1",
        Recordings.tree("{\"keep_alive\":-1,\"num_ctx\":16384}"),
        null,
        true);
  }

  @Test
  @DisplayName("AR-K-00e operations a kind does not offer fail as provider_error without a call")
  void notOffered() {
    assertThat(
            new ClaudeConnector(null, null, null, TIMINGS)
                .embed(TENANT, instance(ConnectorKind.CLAUDE), "m", List.of("text")))
        .isEqualTo(new AiEmbedding.Failed(AiFailure.PROVIDER_ERROR, "claude does not offer embed"));
    assertThat(
            new LocalModelConnector(null, null, null, TIMINGS)
                .embed(TENANT, instance(ConnectorKind.LOCAL_MODEL), "m", List.of("text")))
        .isEqualTo(
            new AiEmbedding.Failed(AiFailure.PROVIDER_ERROR, "local_model does not offer embed"));
    assertThat(
            new LocalEmbeddingConnector(null, null, null, TIMINGS)
                .complete(
                    TENANT,
                    instance(ConnectorKind.LOCAL_EMBEDDING),
                    "m",
                    "system",
                    PARTS,
                    "sl_t_1",
                    SCHEMA,
                    10))
        .isEqualTo(
            new AiCompletion.Failed(
                AiFailure.PROVIDER_ERROR, "local_embedding does not offer complete"));
    LocalEmbeddingConnector embedding = new LocalEmbeddingConnector(null, null, null, TIMINGS);
    assertThatThrownBy(
            () ->
                embedding.complete(
                    TENANT, instance(ConnectorKind.LOCAL_MODEL), "m", "s", PARTS, "n", SCHEMA, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                embedding.complete(
                    TENANT,
                    instance(ConnectorKind.LOCAL_EMBEDDING),
                    "m",
                    "s",
                    PARTS,
                    "n",
                    SCHEMA,
                    0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> embedding.embed(TENANT, instance(ConnectorKind.LOCAL_EMBEDDING), "m", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("AR-K-08 the four AI kinds' settings and credential rules as ConnectorKind declares")
  void kinds() {
    Map<ConnectorKind, Map<String, SettingType>> settings =
        Map.of(
            ConnectorKind.CLAUDE, Map.of("anthropic_version", SettingType.STRING),
            ConnectorKind.OPENAI, Map.of(),
            ConnectorKind.LOCAL_MODEL,
                Map.of("keep_alive", SettingType.INTEGER, "num_ctx", SettingType.INTEGER),
            ConnectorKind.LOCAL_EMBEDDING, Map.of("keep_alive", SettingType.INTEGER));
    settings.forEach(
        (kind, schema) -> {
          assertThat(kind.connectorType()).isEqualTo(ConnectorType.AI_PROVIDER);
          assertThat(kind.settingsSchema()).isEqualTo(schema);
        });
    assertThat(ConnectorKind.CLAUDE.instanceCredential()).contains(CredentialPurpose.EXTERNAL_API);
    assertThat(ConnectorKind.OPENAI.instanceCredential()).contains(CredentialPurpose.EXTERNAL_API);
    assertThat(ConnectorKind.LOCAL_MODEL.instanceCredential()).isEqualTo(Optional.empty());
    assertThat(ConnectorKind.LOCAL_EMBEDDING.instanceCredential()).isEqualTo(Optional.empty());
  }

  @Test
  @DisplayName("AR-K-09 local_model takes the settings keep_alive and num_ctx and no credential")
  void localModel() {
    assertThat(ConnectorKind.LOCAL_MODEL.settingsSchema().keySet())
        .containsExactly("keep_alive", "num_ctx");
    assertThat(ConnectorKind.LOCAL_MODEL.instanceCredential()).isEmpty();
    assertThat(new LocalModelConnector(null, null, null, TIMINGS).sendsKey()).isFalse();
  }

  @Test
  @DisplayName("AR-K-10 local_embedding takes the setting keep_alive and no credential")
  void localEmbedding() {
    assertThat(ConnectorKind.LOCAL_EMBEDDING.settingsSchema().keySet())
        .containsExactly("keep_alive");
    assertThat(ConnectorKind.LOCAL_EMBEDDING.instanceCredential()).isEmpty();
    assertThat(new LocalEmbeddingConnector(null, null, null, TIMINGS).sendsKey()).isFalse();
  }
}
