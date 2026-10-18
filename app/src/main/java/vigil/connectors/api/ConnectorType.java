package vigil.connectors.api;

/** The connector types of DM-E-09 {@code connector_type} (complete list, in order). */
public enum ConnectorType {
  MONITORING_SOURCE("monitoring_source"),
  TARGET("target"),
  TICKETING("ticketing"),
  NOTIFICATION_CHANNEL("notification_channel"),
  AI_PROVIDER("ai_provider");

  private final String key;

  ConnectorType(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
