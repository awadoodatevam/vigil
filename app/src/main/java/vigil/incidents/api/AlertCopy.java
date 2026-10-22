package vigil.incidents.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The copy of an alert held by a membership (DM-E-34 {@code alert_snapshot} with BP-A-27), passed
 * by vigil-correlation; {@code alertId} is the membership's {@code alert_id}. {@code componentKey}
 * is {@code null} for an unknown component; {@code firstIdentityPair} {@code null} when the alert
 * has none.
 */
public record AlertCopy(
    UUID alertId,
    String sourceKind,
    String alertKey,
    String alertType,
    String alertTypeName,
    String title,
    String componentKey,
    IdentityPairCopy firstIdentityPair,
    Instant firedAt) {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Set<String> SOURCE_KINDS = Set.of("zabbix", "alertmanager");

  public AlertCopy {
    if (alertId == null || alertKey == null || title == null || firedAt == null) {
      throw new IllegalArgumentException("alert: alertId, alertKey, title and firedAt required");
    }
    if (!SOURCE_KINDS.contains(sourceKind)) {
      throw new IllegalArgumentException("alert: sourceKind zabbix or alertmanager");
    }
    if (alertType == null || !KEY.matcher(alertType).matches()) {
      throw new IllegalArgumentException("alert: alertType not a valid key");
    }
    if (alertTypeName == null
        || alertTypeName.isEmpty()
        || alertTypeName.codePointCount(0, alertTypeName.length()) > 100) {
      throw new IllegalArgumentException("alert: alertTypeName 1 to 100 characters");
    }
    if (title.codePointCount(0, title.length()) > 300) {
      throw new IllegalArgumentException("alert: title at most 300 characters");
    }
    if (componentKey != null && !KEY.matcher(componentKey).matches()) {
      throw new IllegalArgumentException("alert: componentKey not a valid key");
    }
  }
}
