package vigil.recovery.api;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A recovery condition (DM-V-07): {@code componentKey} only for {@code component_no_firing_alert};
 * {@code alertTypes} keys, stored sorted in code point order without repeats, empty for every alert
 * type and always empty for {@code incident_alerts_resolved}.
 */
public record RecoveryCondition(ConditionKind kind, String componentKey, List<String> alertTypes) {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final String INVALID = "recovery condition: kind, component and alert types";

  public RecoveryCondition {
    alertTypes = alertTypes == null ? List.of() : alertTypes;
    if (kind == null || alertTypes.stream().anyMatch(type -> type == null || !isKey(type))) {
      throw new IllegalArgumentException(INVALID);
    }
    alertTypes = List.copyOf(new TreeSet<>(alertTypes));
    boolean valid =
        kind == ConditionKind.INCIDENT_ALERTS_RESOLVED
            ? componentKey == null && alertTypes.isEmpty()
            : isKey(componentKey);
    if (!valid) {
      throw new IllegalArgumentException(INVALID);
    }
  }

  private static boolean isKey(String value) {
    return value != null && KEY.matcher(value).matches();
  }
}
