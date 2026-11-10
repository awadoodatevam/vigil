package vigil.aigateway.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * The data categories of DM-R-42 (complete list) with the display names of {@code 40-demo-cut}; the
 * gateway takes each part's category as given (DM-R-44 is applied by the caller).
 */
public enum DataCategory {
  ALERT_DATA("alert_data", "Alert data"),
  SERVICE_CONTEXT("service_context", "Service context"),
  DIAGNOSTIC_OUTPUT("diagnostic_output", "Diagnostic output"),
  RUNBOOK_TEXT("runbook_text", "Runbook text"),
  INCIDENT_HISTORY("incident_history", "Incident history"),
  INCIDENT_RECORD("incident_record", "Incident record");

  private final String key;
  private final String displayName;

  DataCategory(String key, String displayName) {
    this.key = key;
    this.displayName = displayName;
  }

  public String key() {
    return key;
  }

  public String displayName() {
    return displayName;
  }

  /** The category with exactly this key; empty for {@code null} or an unknown key. */
  public static Optional<DataCategory> fromKey(String key) {
    return Arrays.stream(values()).filter(category -> category.key.equals(key)).findFirst();
  }
}
