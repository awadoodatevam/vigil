package vigil.incidents.api;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A membership's grouping reason (DM-E-34 {@code reason}): a copy of the DM-E-29 fields of the
 * grouping decision. For {@code created_incident} the other four fields are {@code null}.
 */
public record GroupingReason(
    String decision,
    UUID relatedAlertId,
    String relation,
    List<String> relationPath,
    Long timeDeltaSeconds) {

  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  private static final Set<String> DECISIONS = Set.of("created_incident", "joined", "reopened");
  private static final Set<String> RELATIONS =
      Set.of("same_component", "depends_on", "depended_on_by", "same_unknown_identity");

  public GroupingReason {
    if (!DECISIONS.contains(decision)) {
      throw new IllegalArgumentException("reason: decision created_incident, joined or reopened");
    }
    if (decision.equals("created_incident")) {
      if (relatedAlertId != null
          || relation != null
          || relationPath != null
          || timeDeltaSeconds != null) {
        throw new IllegalArgumentException("reason: created_incident has no relation");
      }
    } else {
      relationPath = checkedRelation(relatedAlertId, relation, relationPath, timeDeltaSeconds);
    }
  }

  /** The rules of a relation (P-14 spec Section 6.4); returns an unmodifiable copy of the path. */
  static List<String> checkedRelation(
      UUID relatedAlertId, String relation, List<String> relationPath, Long timeDeltaSeconds) {
    if (!RELATIONS.contains(relation)) {
      throw new IllegalArgumentException("reason: unknown relation");
    }
    if (relatedAlertId == null || timeDeltaSeconds == null || timeDeltaSeconds < 0) {
      throw new IllegalArgumentException("reason: relatedAlertId and timeDeltaSeconds >= 0");
    }
    if (relationPath == null || relationPath.stream().anyMatch(k -> k == null)) {
      throw new IllegalArgumentException("reason: relationPath required");
    }
    boolean unknown = relation.equals("same_unknown_identity");
    if (unknown != relationPath.isEmpty()
        || relationPath.stream().anyMatch(k -> !KEY.matcher(k).matches())) {
      throw new IllegalArgumentException("reason: relationPath does not fit the relation");
    }
    return List.copyOf(relationPath);
  }
}
