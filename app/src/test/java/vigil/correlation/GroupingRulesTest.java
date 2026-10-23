package vigil.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.correlation.GroupingRules.Reason;
import vigil.correlation.GroupingRules.Relations;
import vigil.correlation.api.GroupingRelation;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.FinalReason;
import vigil.incidents.api.IdentityPairCopy;
import vigil.servicemap.api.Relation;
import vigil.servicemap.api.RelationKind;
import vigil.shared.Ids;

class GroupingRulesTest {

  private static final Instant T0 = Instant.parse("2026-10-22T10:00:00Z");
  private static final Duration WINDOW = Duration.ofSeconds(300);

  /** a depends on b, b depends on c; d stands alone. */
  private static final Relations RELATIONS =
      (a, b) -> {
        if (a.equals(b)) {
          return new Relation(RelationKind.SAME_COMPONENT, List.of(a));
        }
        List<String> chain = List.of("a", "b", "c");
        int i = chain.indexOf(a);
        int j = chain.indexOf(b);
        if (i < 0 || j < 0) {
          return new Relation(RelationKind.NONE, List.of());
        }
        List<String> path =
            i < j ? chain.subList(i, j + 1) : new ArrayList<>(chain.subList(j, i + 1).reversed());
        return new Relation(i < j ? RelationKind.DEPENDS_ON : RelationKind.DEPENDED_ON_BY, path);
      };

  private static AlertCopy alert(
      String kind, String key, String component, IdentityPairCopy pair, Instant firedAt) {
    return new AlertCopy(
        Ids.newId(), kind, key, "disk_full", "Disk full", "t", component, pair, firedAt);
  }

  private static AlertCopy known(String key, String component, Instant firedAt) {
    return alert("zabbix", key, component, new IdentityPairCopy("component", component), firedAt);
  }

  private static AlertCopy unknown(String kind, IdentityPairCopy pair, Instant firedAt) {
    return alert(kind, "k-" + UUID.randomUUID(), null, pair, firedAt);
  }

  private static Optional<Reason> relation(AlertCopy x, AlertCopy y) {
    return GroupingRules.relation(x, y, RELATIONS, WINDOW);
  }

  @Test
  @DisplayName("DM-R-08 the alert relation of two alerts, with the grouping window")
  void alertRelation() {
    AlertCopy a = known("1", "a", T0);
    assertThat(relation(a, known("2", "a", T0)).orElseThrow().relation())
        .isEqualTo(GroupingRelation.SAME_COMPONENT);
    Reason dependsOn = relation(a, known("3", "c", T0)).orElseThrow();
    assertThat(dependsOn.relation()).isEqualTo(GroupingRelation.DEPENDS_ON);
    assertThat(dependsOn.path()).containsExactly("a", "b", "c");
    Reason dependedOn = relation(known("4", "c", T0), a).orElseThrow();
    assertThat(dependedOn.relation()).isEqualTo(GroupingRelation.DEPENDED_ON_BY);
    assertThat(dependedOn.path()).containsExactly("c", "b", "a");
    assertThat(relation(a, known("5", "d", T0))).isEmpty();

    IdentityPairCopy pair = new IdentityPairCopy("component", "nowhere");
    AlertCopy u = unknown("alertmanager", pair, T0);
    Reason same = relation(u, unknown("alertmanager", pair, T0)).orElseThrow();
    assertThat(same.relation()).isEqualTo(GroupingRelation.SAME_UNKNOWN_IDENTITY);
    assertThat(same.path()).isEmpty();
    assertThat(relation(u, unknown("zabbix", pair, T0))).isEmpty();
    assertThat(relation(u, unknown("alertmanager", new IdentityPairCopy("component", "x"), T0)))
        .isEmpty();
    assertThat(relation(unknown("alertmanager", null, T0), unknown("alertmanager", null, T0)))
        .isEmpty();
    assertThat(relation(u, known("6", "a", T0))).isEmpty();
    assertThat(relation(known("7", "a", T0), u)).isEmpty();

    assertThat(relation(a, known("8", "b", T0.plusSeconds(300)))).isPresent();
    assertThat(relation(a, known("9", "b", T0.plusSeconds(301)))).isEmpty();
    assertThat(relation(known("10", "b", T0.plusSeconds(300)), a)).isPresent();

    assertThat(GroupingRules.timeDelta(T0, T0.plusMillis(1500))).isEqualTo(2);
    assertThat(GroupingRules.timeDelta(T0.plusMillis(2400), T0)).isEqualTo(2);
    assertThat(GroupingRules.timeDelta(T0, T0.plusMillis(500))).isEqualTo(1);
  }

  @Test
  @DisplayName("DM-R-11 the related alert: the earliest-fired related member, ties by kind and key")
  void relatedAlert() {
    AlertCopy x = known("x", "a", T0.plusSeconds(60));
    AlertCopy late = known("late", "b", T0.plusSeconds(30));
    AlertCopy earliest = known("early", "c", T0.plusSeconds(10));
    AlertCopy middle = known("middle", "a", T0.plusSeconds(20));
    AlertCopy unrelated = known("lone", "d", T0);
    Reason reason =
        GroupingRules.relatedAlert(x, List.of(late, middle, unrelated, earliest), RELATIONS, WINDOW)
            .orElseThrow();
    assertThat(reason.related()).isEqualTo(earliest);
    assertThat(reason.timeDelta()).isEqualTo(50);

    IdentityPairCopy pair = new IdentityPairCopy("component", "b");
    AlertCopy zabbix = alert("zabbix", "a-key", "b", pair, T0);
    AlertCopy alertmanager = alert("alertmanager", "z-key", "b", pair, T0);
    assertThat(
            GroupingRules.relatedAlert(x, List.of(zabbix, alertmanager), RELATIONS, WINDOW)
                .orElseThrow()
                .related())
        .isEqualTo(alertmanager);
    AlertCopy keyB = alert("zabbix", "key-b", "b", pair, T0);
    AlertCopy keyA = alert("zabbix", "key-a", "b", pair, T0);
    assertThat(
            GroupingRules.relatedAlert(x, List.of(keyB, keyA), RELATIONS, WINDOW)
                .orElseThrow()
                .related())
        .isEqualTo(keyA);
  }

  @Test
  @DisplayName("DM-R-58 the final reasons: only_alert when unrelated, the same in every order")
  void finalReasons() {
    AlertCopy lone = known("lone", "d", T0);
    assertThat(GroupingRules.finalReasons(List.of(lone), RELATIONS, WINDOW).get(lone.alertId()))
        .isEqualTo(new FinalReason(null, FinalReason.ONLY_ALERT, null, null));

    List<AlertCopy> members =
        List.of(
            known("m1", "a", T0.plusSeconds(5)),
            known("m2", "b", T0),
            known("m3", "c", T0.plusSeconds(5)),
            known("m4", "d", T0.plusSeconds(1)));
    Map<UUID, FinalReason> expected = GroupingRules.finalReasons(members, RELATIONS, WINDOW);
    assertThat(expected.get(members.get(3).alertId()).relation()).isEqualTo(FinalReason.ONLY_ALERT);
    assertThat(expected.get(members.get(0).alertId()).relatedAlertId())
        .isEqualTo(members.get(1).alertId());
    List<List<AlertCopy>> orders = permutations(members);
    assertThat(orders).hasSize(24);
    for (List<AlertCopy> order : orders) {
      assertThat(GroupingRules.finalReasons(order, RELATIONS, WINDOW)).isEqualTo(expected);
    }
  }

  private static List<List<AlertCopy>> permutations(List<AlertCopy> items) {
    if (items.isEmpty()) {
      return List.of(List.of());
    }
    List<List<AlertCopy>> result = new ArrayList<>();
    for (AlertCopy item : items) {
      List<AlertCopy> rest = new ArrayList<>(items);
      rest.remove(item);
      for (List<AlertCopy> tail : permutations(rest)) {
        List<AlertCopy> order = new ArrayList<>();
        order.add(item);
        order.addAll(tail);
        result.add(order);
      }
    }
    return result;
  }
}
