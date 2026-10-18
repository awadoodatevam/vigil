package vigil.connectors.api;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The code catalogue of connector kinds (DM-E-09, complete list in the order of {@code
 * 50-architecture} Section 12.2): each kind's connector type, settings structure and the purpose of
 * the instance's own credential reference.
 */
public enum ConnectorKind {
  ZABBIX(
      "zabbix",
      ConnectorType.MONITORING_SOURCE,
      settings("api_path", SettingType.STRING),
      CredentialPurpose.EXTERNAL_API),
  ALERTMANAGER(
      "alertmanager",
      ConnectorType.MONITORING_SOURCE,
      settings("username", SettingType.STRING),
      CredentialPurpose.EXTERNAL_API),
  LINUX_SERVER(
      "linux_server",
      ConnectorType.TARGET,
      settings("port", SettingType.INTEGER, "host_key_sha256", SettingType.STRING),
      null),
  JIRA_CLOUD(
      "jira_cloud",
      ConnectorType.TICKETING,
      settings(
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
      CredentialPurpose.EXTERNAL_API),
  MS_TEAMS("ms_teams", ConnectorType.NOTIFICATION_CHANNEL, settings(), null),
  EMAIL(
      "email",
      ConnectorType.NOTIFICATION_CHANNEL,
      settings("from_address", SettingType.STRING, "starttls", SettingType.BOOLEAN),
      CredentialPurpose.SMTP),
  CLAUDE(
      "claude",
      ConnectorType.AI_PROVIDER,
      settings("anthropic_version", SettingType.STRING),
      CredentialPurpose.EXTERNAL_API),
  OPENAI("openai", ConnectorType.AI_PROVIDER, settings(), CredentialPurpose.EXTERNAL_API),
  LOCAL_MODEL(
      "local_model",
      ConnectorType.AI_PROVIDER,
      settings("keep_alive", SettingType.INTEGER, "num_ctx", SettingType.INTEGER),
      null),
  LOCAL_EMBEDDING(
      "local_embedding",
      ConnectorType.AI_PROVIDER,
      settings("keep_alive", SettingType.INTEGER),
      null);

  private final String key;
  private final ConnectorType connectorType;
  private final Map<String, SettingType> settingsSchema;
  private final CredentialPurpose instanceCredential;

  ConnectorKind(
      String key,
      ConnectorType connectorType,
      Map<String, SettingType> settingsSchema,
      CredentialPurpose instanceCredential) {
    this.key = key;
    this.connectorType = connectorType;
    this.settingsSchema = settingsSchema;
    this.instanceCredential = instanceCredential;
  }

  public String key() {
    return key;
  }

  public ConnectorType connectorType() {
    return connectorType;
  }

  /** The keys of the kind's settings and their types, in the order written. */
  public Map<String, SettingType> settingsSchema() {
    return settingsSchema;
  }

  /** The purpose the instance's own credential reference MUST have; empty when it has none. */
  public Optional<CredentialPurpose> instanceCredential() {
    return Optional.ofNullable(instanceCredential);
  }

  /** The kind with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<ConnectorKind> fromKey(String key) {
    return Arrays.stream(values()).filter(kind -> kind.key.equals(key)).findFirst();
  }

  private static Map<String, SettingType> settings(Object... keysAndTypes) {
    Map<String, SettingType> schema = new LinkedHashMap<>();
    for (int i = 0; i < keysAndTypes.length; i += 2) {
      schema.put((String) keysAndTypes[i], (SettingType) keysAndTypes[i + 1]);
    }
    return Collections.unmodifiableMap(schema);
  }
}
