package vigil.incidents.api;

import java.util.Arrays;
import java.util.Optional;

/** The data categories of DM-R-42 (complete list, in its order). */
public enum DataCategory {
  ALERT_DATA("alert_data"),
  SERVICE_CONTEXT("service_context"),
  DIAGNOSTIC_OUTPUT("diagnostic_output"),
  RUNBOOK_TEXT("runbook_text"),
  INCIDENT_HISTORY("incident_history"),
  INCIDENT_RECORD("incident_record");

  private final String key;

  DataCategory(String key) {
    this.key = key;
  }

  public String key() {
    return key;
  }

  /** The value with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<DataCategory> fromKey(String key) {
    return Arrays.stream(values()).filter(value -> value.key.equals(key)).findFirst();
  }
}
