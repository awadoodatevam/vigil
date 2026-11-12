package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import vigil.actions.ActionsFixture;
import vigil.reporting.api.DashboardView;
import vigil.reporting.api.MetricView;
import vigil.reporting.api.ReportingService;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DashboardApiIT {

  private ApprovalApiFixture api;

  @BeforeAll
  void start() {
    api = new ApprovalApiFixture();
  }

  @AfterAll
  void stop() {
    api.close();
  }

  private JsonNode read(ActionsFixture tenant) {
    Response response = api.as(tenant, "presenter", api.client.get("/api/v1/dashboard")).send();
    assertThat(response.status()).isEqualTo(200);
    return response.json();
  }

  @Test
  @DisplayName("AR-I-80 the dashboard: four metrics with baselines, counts and record labels")
  void dashboard() {
    ActionsFixture f = api.fixture;
    api.baselines(f);
    f.openIncident();
    f.notifications.acknowledge(f.openIncident());
    DashboardView view =
        api.servers.app.context().getBean(ReportingService.class).dashboard(f.tenant);
    JsonNode body = read(f);
    JsonNode metrics = body.get("metrics");
    assertThat(metrics.valueStream().map(m -> m.get("key").stringValue()).toList())
        .containsExactly(
            "time_to_acknowledge", "time_to_recover", "alerts_suppressed", "incidents_merged");
    for (int i = 0; i < 4; i++) {
      MetricView expected = view.metrics().get(i);
      JsonNode metric = metrics.get(i);
      assertThat(metric.get("key").stringValue()).isEqualTo(expected.key().key());
      assertThat(metric.get("unit").stringValue()).isEqualTo(expected.unit().key());
      if (expected.value() == null) {
        assertThat(metric.get("value").isNull()).isTrue();
      } else {
        assertThat(metric.get("value").decimalValue()).isEqualByComparingTo(expected.value());
      }
      assertThat(metric.get("baseline_value").decimalValue())
          .isEqualByComparingTo(expected.baselineValue());
      assertThat(metric.get("baseline_label").stringValue()).isEqualTo("Illustrative baseline");
    }
    assertThat(metrics.get(0).get("unit").stringValue()).isEqualTo("minutes");
    assertThat(metrics.get(0).get("baseline_value").decimalValue()).isEqualByComparingTo("11.0");
    assertThat(metrics.get(1).get("baseline_value").decimalValue()).isEqualByComparingTo("72.0");
    assertThat(metrics.get(2).get("unit").stringValue()).isEqualTo("count");
    assertThat(metrics.get(2).get("baseline_value").decimalValue()).isEqualByComparingTo("0");
    assertThat(body.get("live_incidents").longValue()).isEqualTo(view.liveIncidents());
    assertThat(body.get("past_incidents").longValue()).isEqualTo(view.pastIncidents());
    assertThat(body.get("record_labels").valueStream().map(JsonNode::stringValue).toList())
        .isEqualTo(view.recordLabels())
        .isNotEmpty();
    assertThat(view.pastIncidents()).isPositive();

    ActionsFixture without = api.tenant("Dashboard without baselines");
    JsonNode none = read(without);
    assertThat(none.get("metrics")).hasSize(4);
    for (JsonNode metric : none.get("metrics")) {
      assertThat(metric.get("baseline_value").isNull()).isTrue();
      assertThat(metric.get("baseline_label").isNull()).isTrue();
    }
    assertThat(List.of(none.get("live_incidents").longValue())).doesNotContainNull();
  }
}
