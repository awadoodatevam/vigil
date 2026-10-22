package vigil.incidents.api;

import java.util.List;
import java.util.UUID;

/**
 * A membership's final grouping reason (DM-E-34 {@code final_reason}, DM-R-58): {@code only_alert}
 * with the other three fields {@code null}, or a relation with the rules of {@link GroupingReason}.
 */
public record FinalReason(
    UUID relatedAlertId, String relation, List<String> relationPath, Long timeDeltaSeconds) {

  public static final String ONLY_ALERT = "only_alert";

  public FinalReason {
    if (ONLY_ALERT.equals(relation)) {
      if (relatedAlertId != null || relationPath != null || timeDeltaSeconds != null) {
        throw new IllegalArgumentException("final reason: only_alert has no relation fields");
      }
    } else {
      relationPath =
          GroupingReason.checkedRelation(relatedAlertId, relation, relationPath, timeDeltaSeconds);
    }
  }
}
