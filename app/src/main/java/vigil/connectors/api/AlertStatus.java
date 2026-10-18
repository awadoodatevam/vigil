package vigil.connectors.api;

/** The status an entry reports (DM-E-19 {@code reported_status}). */
public enum AlertStatus {
  FIRING("firing"),
  RESOLVED("resolved");

  private final String key;

  AlertStatus(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }
}
