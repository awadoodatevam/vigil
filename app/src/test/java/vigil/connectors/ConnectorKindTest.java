package vigil.connectors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorType;
import vigil.connectors.api.CredentialPurpose;
import vigil.connectors.api.SettingType;

class ConnectorKindTest {

  private record Row(
      String key,
      ConnectorType type,
      Map<String, SettingType> schema,
      Optional<CredentialPurpose> credential) {}

  private static Map<String, SettingType> schema(Object... keysAndTypes) {
    Map<String, SettingType> schema = new LinkedHashMap<>();
    for (int i = 0; i < keysAndTypes.length; i += 2) {
      schema.put((String) keysAndTypes[i], (SettingType) keysAndTypes[i + 1]);
    }
    return schema;
  }

  @Test
  @DisplayName("DM-E-09 the ten connector kinds with their types, settings and credentials")
  void theTenKinds() {
    Optional<CredentialPurpose> api = Optional.of(CredentialPurpose.EXTERNAL_API);
    List<Row> expected =
        List.of(
            new Row(
                "zabbix",
                ConnectorType.MONITORING_SOURCE,
                schema("api_path", SettingType.STRING),
                api),
            new Row(
                "alertmanager",
                ConnectorType.MONITORING_SOURCE,
                schema("username", SettingType.STRING),
                api),
            new Row(
                "linux_server",
                ConnectorType.TARGET,
                schema("port", SettingType.INTEGER, "host_key_sha256", SettingType.STRING),
                Optional.empty()),
            new Row(
                "jira_cloud",
                ConnectorType.TICKETING,
                schema(
                    "project_key",
                    SettingType.STRING,
                    "issue_type",
                    SettingType.STRING,
                    "resolved_status",
                    SettingType.STRING,
                    "reopened_status",
                    SettingType.STRING,
                    "label_prefix",
                    SettingType.STRING),
                api),
            new Row("ms_teams", ConnectorType.NOTIFICATION_CHANNEL, schema(), Optional.empty()),
            new Row(
                "email",
                ConnectorType.NOTIFICATION_CHANNEL,
                schema("from_address", SettingType.STRING, "starttls", SettingType.BOOLEAN),
                Optional.of(CredentialPurpose.SMTP)),
            new Row(
                "claude",
                ConnectorType.AI_PROVIDER,
                schema("anthropic_version", SettingType.STRING),
                api),
            new Row("openai", ConnectorType.AI_PROVIDER, schema(), api),
            new Row(
                "local_model",
                ConnectorType.AI_PROVIDER,
                schema("keep_alive", SettingType.INTEGER, "num_ctx", SettingType.INTEGER),
                Optional.empty()),
            new Row(
                "local_embedding",
                ConnectorType.AI_PROVIDER,
                schema("keep_alive", SettingType.INTEGER),
                Optional.empty()));
    assertThat(Arrays.stream(ConnectorKind.values()).map(Enum::name))
        .containsExactly(
            "ZABBIX",
            "ALERTMANAGER",
            "LINUX_SERVER",
            "JIRA_CLOUD",
            "MS_TEAMS",
            "EMAIL",
            "CLAUDE",
            "OPENAI",
            "LOCAL_MODEL",
            "LOCAL_EMBEDDING");
    List<Row> actual =
        Arrays.stream(ConnectorKind.values())
            .map(
                kind ->
                    new Row(
                        kind.key(),
                        kind.connectorType(),
                        kind.settingsSchema(),
                        kind.instanceCredential()))
            .toList();
    assertThat(actual).isEqualTo(expected);
    for (int i = 0; i < expected.size(); i++) {
      assertThat(ConnectorKind.values()[i].settingsSchema().keySet())
          .containsExactlyElementsOf(expected.get(i).schema().keySet());
    }
    for (ConnectorKind kind : ConnectorKind.values()) {
      assertThat(ConnectorKind.fromKey(kind.key())).contains(kind);
    }
    for (String key : Arrays.asList("Zabbix", "jira", "", null)) {
      assertThat(ConnectorKind.fromKey(key)).isEmpty();
    }
    assertThat(Arrays.stream(ConnectorType.values()).map(ConnectorType::key))
        .containsExactly(
            "monitoring_source", "target", "ticketing", "notification_channel", "ai_provider");
    assertThat(Arrays.stream(CredentialPurpose.values()).map(CredentialPurpose::key))
        .containsExactly(
            "webhook_token",
            "external_api",
            "target_diagnostic",
            "target_action",
            "smtp",
            "webhook_url");
  }

  @Test
  @DisplayName("AR-TM-01 connect 5 s and monitoring source connection test 10 s")
  void timings() {
    ConnectorTimings timings = new ConnectorConfiguration().connectorTimings();
    assertThat(timings.connect()).isEqualTo(Duration.ofSeconds(5));
    assertThat(timings.monitoringTest()).isEqualTo(Duration.ofSeconds(10));
  }
}
