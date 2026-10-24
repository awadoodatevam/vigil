package vigil.correlation;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vigil.correlation.api.Decision;
import vigil.correlation.api.GroupingRelation;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.GroupingReason;
import vigil.servicemap.api.Relation;
import vigil.servicemap.api.RelationKind;

/**
 * The alert relation (DM-R-08), the related alert of a grouping reason (DM-R-11) and the final
 * reasons (DM-R-58), on the alert copies of incident members (P-16 spec Section 6.6 (5), (6)).
 */
final class GroupingRules {

  static final Comparator<String> CODE_POINTS =
      Comparator.comparing(text -> text.codePoints().toArray(), Arrays::compare);

  /** The earliest-fired first; ties: source kind, then alert key, in code point order. */
  static final Comparator<AlertCopy> EARLIEST =
      Comparator.comparing(AlertCopy::firedAt)
          .thenComparing(AlertCopy::sourceKind, CODE_POINTS)
          .thenComparing(AlertCopy::alertKey, CODE_POINTS);

  private static final long NANOS_PER_SECOND = 1_000_000_000L;

  private GroupingRules() {}

  /** The relation of two known components (DM-R-07), as {@code ServiceMapService.relation}. */
  interface Relations {
    Relation relation(String a, String b);
  }

  /** Relations that ask {@code relations} once per pair of components. */
  static Relations remembered(Relations relations) {
    Map<List<String>, Relation> known = new HashMap<>();
    return (a, b) -> known.computeIfAbsent(List.of(a, b), pair -> relations.relation(a, b));
  }

  /** The relation of an alert to a related member: the member, the relation, path and delta. */
  record Reason(AlertCopy related, GroupingRelation relation, List<String> path, long timeDelta) {

    GroupingReason grouping(Decision decision) {
      return new GroupingReason(decision.key(), related.alertId(), relation.key(), path, timeDelta);
    }

    FinalReason finalReason() {
      return new FinalReason(related.alertId(), relation.key(), path, timeDelta);
    }
  }

  /**
   * DM-R-08: the relation of {@code x} to {@code y}, or empty when they are not related. With
   * {@code window} {@code null} only the component relation (a) applies (DM-R-21).
   */
  static Optional<Reason> relation(AlertCopy x, AlertCopy y, Relations relations, Duration window) {
    if (window != null && Duration.between(x.firedAt(), y.firedAt()).abs().compareTo(window) > 0) {
      return Optional.empty();
    }
    long delta = timeDelta(x.firedAt(), y.firedAt());
    if (x.componentKey() != null && y.componentKey() != null) {
      Relation relation = relations.relation(x.componentKey(), y.componentKey());
      GroupingRelation kind =
          switch (relation.kind()) {
            case SAME_COMPONENT -> GroupingRelation.SAME_COMPONENT;
            case DEPENDS_ON -> GroupingRelation.DEPENDS_ON;
            case DEPENDED_ON_BY -> GroupingRelation.DEPENDED_ON_BY;
            case NONE -> null;
          };
      return relation.kind() == RelationKind.NONE
          ? Optional.empty()
          : Optional.of(new Reason(y, kind, relation.path(), delta));
    }
    if (x.componentKey() == null
        && y.componentKey() == null
        && x.sourceKind().equals(y.sourceKind())
        && x.firstIdentityPair() != null
        && x.firstIdentityPair().equals(y.firstIdentityPair())) {
      return Optional.of(new Reason(y, GroupingRelation.SAME_UNKNOWN_IDENTITY, List.of(), delta));
    }
    return Optional.empty();
  }

  /** The absolute difference in whole seconds, rounded to the nearest, halves up. */
  static long timeDelta(Instant a, Instant b) {
    Duration difference = Duration.between(a, b).abs();
    return (difference.toNanos() + NANOS_PER_SECOND / 2) / NANOS_PER_SECOND;
  }

  /**
   * DM-R-11: among the members related to {@code x} (other than {@code x} itself), the one with the
   * earliest {@code fired_at}; ties by source kind, then alert key.
   */
  static Optional<Reason> relatedAlert(
      AlertCopy x, Collection<AlertCopy> members, Relations relations, Duration window) {
    Reason best = null;
    for (AlertCopy member : members) {
      if (member.alertId().equals(x.alertId())) {
        continue;
      }
      Optional<Reason> reason = relation(x, member, relations, window);
      if (reason.isPresent() && (best == null || EARLIEST.compare(member, best.related()) < 0)) {
        best = reason.get();
      }
    }
    return Optional.ofNullable(best);
  }

  /** DM-R-58: the final reason of each member over the other members, by alert id. */
  static Map<UUID, FinalReason> finalReasons(
      List<AlertCopy> members, Relations relations, Duration window) {
    Map<UUID, FinalReason> reasons = new LinkedHashMap<>();
    for (AlertCopy member : members) {
      reasons.put(
          member.alertId(),
          relatedAlert(member, members, relations, window)
              .map(Reason::finalReason)
              .orElse(new FinalReason(null, FinalReason.ONLY_ALERT, null, null)));
    }
    return reasons;
  }
}
