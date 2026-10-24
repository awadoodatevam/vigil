package vigil.correlation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vigil.correlation.api.TieBreak;
import vigil.servicemap.api.Criticality;

class HypothesisTest {

  /** The dependents of the demo service map (DD-F-02), directly or indirectly. */
  private static final Map<String, List<String>> DEMO =
      Map.of(
          "srv-db-01",
          List.of(
              "api-balance", "api-bundle", "db-subscriber", "sms-queue", "ussd-gw", "ussd-probe"),
          "db-subscriber",
          List.of("api-balance", "api-bundle", "sms-queue", "ussd-gw", "ussd-probe"),
          "api-balance",
          List.of("ussd-gw", "ussd-probe"),
          "api-bundle",
          List.of("ussd-gw", "ussd-probe"),
          "ussd-gw",
          List.of("ussd-probe"),
          "sms-queue",
          List.of(),
          "ussd-probe",
          List.of());

  private static Collection<String> demo(String key) {
    return DEMO.getOrDefault(key, List.of());
  }

  @Test
  @DisplayName("DM-R-18 the hypothesis: largest dependent count, then criticality, then key")
  void hypothesisRule() {
    Hypothesis.Result storm =
        Hypothesis.compute(
            List.of(
                "ussd-probe",
                "sms-queue",
                "api-balance",
                "api-bundle",
                "ussd-gw",
                "db-subscriber",
                "srv-db-01"),
            HypothesisTest::demo,
            key -> Criticality.CRITICAL);
    assertThat(storm.component()).isEqualTo("srv-db-01");
    assertThat(storm.dependentCounts().get("srv-db-01")).isEqualTo(6);
    assertThat(storm.tieBreak()).isEqualTo(TieBreak.NONE);
    assertThat(storm.affected())
        .containsExactly(
            "api-balance",
            "api-bundle",
            "db-subscriber",
            "sms-queue",
            "srv-db-01",
            "ussd-gw",
            "ussd-probe");

    Map<String, Criticality> criticality = Map.of("p", Criticality.MEDIUM, "q", Criticality.HIGH);
    Hypothesis.Result byCriticality =
        Hypothesis.compute(List.of("p", "q"), key -> List.of(), criticality::get);
    assertThat(byCriticality.component()).isEqualTo("q");
    assertThat(byCriticality.tieBreak()).isEqualTo(TieBreak.CRITICALITY);

    Hypothesis.Result byKey =
        Hypothesis.compute(List.of("y", "x"), key -> List.of(), key -> Criticality.HIGH);
    assertThat(byKey.component()).isEqualTo("x");
    assertThat(byKey.tieBreak()).isEqualTo(TieBreak.COMPONENT_KEY);

    Hypothesis.Result none =
        Hypothesis.compute(
            List.of("a-none", "b-low"),
            key -> List.of(),
            key -> key.equals("b-low") ? Criticality.LOW : null);
    assertThat(none.component()).isEqualTo("b-low");
    assertThat(none.tieBreak()).isEqualTo(TieBreak.CRITICALITY);

    Hypothesis.Result empty = Hypothesis.compute(List.of(), key -> List.of(), key -> null);
    assertThat(empty.component()).isNull();
    assertThat(empty.tieBreak()).isEqualTo(TieBreak.NONE);
    assertThat(empty.dependentCounts()).isEmpty();

    Hypothesis.Result counted =
        Hypothesis.compute(
            List.of("db-subscriber", "sms-queue"), HypothesisTest::demo, key -> null);
    assertThat(counted.dependentCounts())
        .containsExactly(Map.entry("db-subscriber", 1L), Map.entry("sms-queue", 0L));
    assertThat(counted.component()).isEqualTo("db-subscriber");
  }
}
