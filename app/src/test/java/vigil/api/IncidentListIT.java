package vigil.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.JsonNode;
import vigil.engine.EngineFixture;
import vigil.engine.TestContents;
import vigil.identity.api.IdentityService;
import vigil.incidents.api.IncidentService;
import vigil.incidents.api.IncidentState;
import vigil.incidents.api.IncidentView;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertView;
import vigil.intake.api.Health;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceView;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall.StepType;
import vigil.shared.Waiting;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IncidentListIT {

  private static final String STRIP = "/api/v1/alerts/strip";
  private static final String LIST = "/api/v1/incidents";

  private ApiFixture fixture;
  private IntakeService intake;
  private IncidentService incidents;
  private final List<AlertView> posted = new ArrayList<>();
  private UUID acknowledged;
  private UUID running;
  private UUID resolved;
  private UUID closed;
  private UUID merged;

  @BeforeAll
  void start() {
    fixture = new ApiFixture();
    intake = fixture.app.context().getBean(IntakeService.class);
    incidents = fixture.app.context().getBean(IncidentService.class);
  }

  @AfterAll
  void stop() {
    fixture.close();
  }

  /** {@code GET path} as {@code presenter}; the answer MUST be 200; its body. */
  private JsonNode get(String path) {
    Response response = fixture.as("presenter", fixture.client.get(path)).send();
    assertThat(response.status()).as(response.text()).isEqualTo(200);
    return response.json();
  }

  private SourceView source(String key) {
    return intake.sources(fixture.tenant).stream()
        .filter(s -> s.sourceKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  /** zabbix-lab's test fails and its contact ages; a third, inactive source row. */
  private void sources() {
    fixture.wiremock.stubFor(
        post(urlEqualTo("/api_jsonrpc.php")).willReturn(aResponse().withStatus(500)));
    fixture.clock.advance(Duration.ofSeconds(121));
    IntakeFixture.await(
        () ->
            source(IntakeFixture.ZABBIX).health() != Health.HEALTHY
                && source(IntakeFixture.ALERTMANAGER).health() == Health.HEALTHY
                && source(IntakeFixture.ALERTMANAGER).lastTestAt() != null
                && !source(IntakeFixture.ALERTMANAGER)
                    .lastTestAt()
                    .isBefore(fixture.clock.instant().minusSeconds(1)));
    IntakeFixture.await(
        () ->
            source(IntakeFixture.ZABBIX).lastTestAt() != null
                && source(IntakeFixture.ZABBIX).health() == Health.DISCONNECTED);
    SourceView zabbix = source(IntakeFixture.ZABBIX);
    Timestamp now = Timestamp.from(fixture.clock.instant());
    fixture
        .postgres
        .sql(
            "INSERT INTO intake.monitoring_source (id, tenant_id, created_at, updated_at,"
                + " source_key, name, source_kind, connector_instance_id, webhook_token_ref_id,"
                + " health_check_interval, stale_after, disconnected_after, health,"
                + " health_changed_at, active, updated_by_kind, updated_by_module) VALUES (:id,"
                + " :t, :now, :now, 'zabbix-extra', 'Zabbix — extra', 'zabbix', :instance,"
                + " :token, :interval, :stale, :disconnected, 'healthy', :now, false, 'module',"
                + " 'vigil-setup')")
        .param("id", Ids.newId())
        .param("t", fixture.tenant.value())
        .param("now", now)
        .param("instance", zabbix.connectorInstanceId())
        .param("token", Ids.newId())
        .param("interval", zabbix.healthCheckInterval().toSeconds())
        .param("stale", zabbix.staleAfter().toSeconds())
        .param("disconnected", zabbix.disconnectedAfter().toSeconds())
        .update();
    assertThat(intake.sources(fixture.tenant)).hasSize(3);
  }

  @Test
  @Order(1)
  @DisplayName("AR-I-05 the strip's 10-minute count changes with time alone")
  void strip() {
    sources();
    for (int i = 1; i <= 12; i++) {
      fixture.clock.advance(Duration.ofMillis(10));
      posted.add(
          fixture.engine.correlation.postAlertmanager(
              "nowhere-strip", "i-" + i, i % 2 == 0 ? "HighLatency" : "NodeDown"));
    }
    fixture.engine.correlation.awaitDecided();
    fixture.engine.awaitPublications();
    JsonNode strip = get(STRIP);
    assertThat(strip.get("received_last_10_minutes").longValue()).isEqualTo(12);
    assertThat(strip.get("forming").get("incidents").longValue()).isEqualTo(1);
    assertThat(strip.get("forming").get("alerts").longValue()).isEqualTo(12);
    JsonNode sources = strip.get("sources");
    assertThat(sources.get("healthy").longValue()).isEqualTo(1);
    assertThat(sources.get("total").longValue()).isEqualTo(2);
    assertThat(sources.get("not_healthy")).hasSize(1);
    JsonNode zabbix = sources.get("not_healthy").get(0);
    assertThat(zabbix.get("source_key").stringValue()).isEqualTo("zabbix-lab");
    assertThat(zabbix.get("name").stringValue()).isEqualTo("Zabbix — Jazz lab");
    assertThat(zabbix.get("health").stringValue()).isEqualTo("disconnected");
    List<JsonNode> latest = new ArrayList<>();
    strip.get("latest").forEach(latest::add);
    assertThat(latest).hasSize(10);
    for (int i = 0; i < 10; i++) {
      AlertView alert = posted.get(11 - i);
      JsonNode row = latest.get(i);
      assertThat(row.get("alert_id").stringValue()).isEqualTo(alert.id().toString());
      assertThat(row.get("first_received_at").stringValue())
          .isEqualTo(ApiTimes.format(alert.firstReceivedAt()));
      assertThat(row.get("source_kind").stringValue()).isEqualTo("alertmanager");
      assertThat(row.get("alert_type_name").stringValue()).isEqualTo(typeName(alert));
      assertThat(row.get("title").stringValue()).isEqualTo(alert.title());
      assertThat(row.size()).isEqualTo(5);
    }
    assertThat(strip.size()).isEqualTo(4);

    fixture.clock.advance(Duration.ofMinutes(10).plusSeconds(1));
    JsonNode later = get(STRIP);
    assertThat(later.get("received_last_10_minutes").longValue()).isZero();
    assertThat(later.get("latest")).isEqualTo(strip.get("latest"));
  }

  private String typeName(AlertView alert) {
    return intake.alertTypes(fixture.tenant).stream()
        .filter(type -> type.alertType().equals(alert.alertType()))
        .map(type -> type.displayName() == null ? type.alertType() : type.displayName())
        .findFirst()
        .orElse(alert.alertType());
  }

  @Test
  @Order(2)
  @DisplayName("AR-I-20 the strip: latest ten newest first, forming counts, active sources")
  void stripParts() {
    EngineFixture.await(() -> incidents.formingSummary(fixture.tenant).incidents() == 0);
    JsonNode none = get(STRIP);
    assertThat(none.get("forming").get("incidents").longValue()).isZero();
    assertThat(none.get("forming").get("alerts").longValue()).isZero();

    AlertView newest =
        fixture.engine.correlation.postAlertmanager("nowhere-strip-2", "i-1", "NodeDown");
    fixture.engine.correlation.awaitDecided();
    JsonNode strip = get(STRIP);
    assertThat(strip.get("received_last_10_minutes").longValue()).isEqualTo(1);
    assertThat(strip.get("forming").get("incidents").longValue()).isEqualTo(1);
    assertThat(strip.get("forming").get("alerts").longValue()).isEqualTo(1);
    assertThat(strip.get("latest")).hasSize(10);
    assertThat(strip.get("latest").get(0).get("alert_id").stringValue())
        .isEqualTo(newest.id().toString());
    assertThat(strip.get("latest").get(1).get("alert_id").stringValue())
        .isEqualTo(posted.getLast().id().toString());
    assertThat(strip.get("latest").get(0).get("alert_type_name").stringValue())
        .isEqualTo(typeName(newest));
    assertThat(strip.get("sources").get("total").longValue()).isEqualTo(2);
    assertThat(strip.get("sources").get("healthy").longValue()).isEqualTo(1);
    assertThat(strip.get("sources").get("not_healthy").get(0).get("source_key").stringValue())
        .isEqualTo("zabbix-lab");

    fixture.clock.advance(Duration.ofSeconds(41));
    EngineFixture.await(() -> incidents.formingSummary(fixture.tenant).incidents() == 0);
    JsonNode after = get(STRIP);
    assertThat(after.get("forming").get("incidents").longValue()).isZero();
    assertThat(after.get("forming").get("alerts").longValue()).isZero();
  }

  private List<UUID> numbered() {
    return incidents
        .incidentPage(
            fixture.tenant,
            Set.of(IncidentState.OPEN, IncidentState.RESOLVED, IncidentState.CLOSED),
            null,
            51)
        .stream()
        .map(IncidentView::id)
        .toList();
  }

  @Test
  @Order(3)
  @DisplayName("AR-I-21 the rows of the incident list, highest number first")
  void rows() {
    assertThat(numbered()).hasSize(2);
    fixture.handlers.answer(
        StepType.DIAGNOSTIC,
        c -> new Waiting(Set.of(), fixture.clock.instant().plusSeconds(600), "{}"));
    UUID third = fixture.openIncident();
    resolved = fixture.openIncident();
    closed = fixture.openIncident();
    List<UUID> before = numbered();
    acknowledged = before.get(3);
    running = before.get(4);
    fixture.acknowledge(acknowledged, "dbops-engineer");
    fixture.ticketLink(acknowledged);
    UUID run = fixture.liveRun(running, TestContents.linear("diagnostic"));
    EngineFixture.await(() -> !fixture.engine.engine.pendingTimers(fixture.tenant, run).isEmpty());
    fixture.engine.correlation.resolve(resolved);
    fixture.close(closed);
    merged = fixture.mergedIncident();
    fixture.engine.awaitPublications();
    assertThat(third).isNotNull();

    JsonNode page = get(LIST);
    assertThat(page.get("next_cursor").isNull()).isTrue();
    List<JsonNode> items = new ArrayList<>();
    page.get("items").forEach(items::add);
    assertThat(items).hasSize(5);
    List<IncidentView> views =
        numbered().stream()
            .map(id -> incidents.incident(fixture.tenant, id).orElseThrow())
            .toList();
    assertThat(items.stream().map(i -> i.get("incident_id").stringValue()).toList())
        .isEqualTo(views.stream().map(v -> v.id().toString()).toList());
    assertThat(items.stream().map(i -> i.get("number").longValue()).toList())
        .isSortedAccordingTo((x, y) -> Long.compare(y, x));
    assertThat(items.stream().map(i -> i.get("incident_id").stringValue()))
        .doesNotContain(merged.toString());
    assertThat(incidents.incident(fixture.tenant, merged).orElseThrow().state())
        .isEqualTo(IncidentState.MERGED);
    assertThat(incidents.incidents(fixture.tenant, Set.of(IncidentState.FORMING))).hasSize(1);

    for (int i = 0; i < items.size(); i++) {
      JsonNode item = items.get(i);
      IncidentView view = views.get(i);
      assertThat(item.size()).isEqualTo(11);
      assertThat(item.get("number").longValue()).isEqualTo(view.number());
      assertThat(item.get("title").stringValue()).isEqualTo(view.title());
      assertThat(text(item.get("severity")))
          .isEqualTo(view.severity() == null ? null : view.severity().key());
      assertThat(item.get("state").stringValue()).isEqualTo(view.state().key());
      assertThat(item.get("alert_count").longValue()).isEqualTo(view.alertCount());
      assertThat(item.get("first_alert_fired_at").stringValue())
          .isEqualTo(ApiTimes.format(view.firstAlertFiredAt()));
      assertThat(view.ownerTeamKey()).isNotNull();
      assertThat(item.get("owner").get("team_key").stringValue()).isEqualTo(view.ownerTeamKey());
      assertThat(item.get("owner").get("name").stringValue())
          .isEqualTo(teamName(view.ownerTeamKey()));
      if (view.id().equals(acknowledged)) {
        JsonNode ack = item.get("acknowledged");
        assertThat(ack.get("user").get("display_name").stringValue())
            .isEqualTo("Usman Tariq (DB Ops)");
        assertThat(ack.get("user").get("user_id").stringValue())
            .isEqualTo(view.acknowledgedBy().toString());
        assertThat(ack.get("at").stringValue()).isEqualTo(ApiTimes.format(view.acknowledgedAt()));
        assertThat(item.get("ticket").get("key").stringValue()).isEqualTo("NOC-1");
        assertThat(item.get("ticket").get("url").stringValue())
            .isEqualTo("http://127.0.0.1:" + fixture.wiremock.port() + "/browse/NOC-1");
      } else {
        assertThat(item.get("acknowledged").isNull()).isTrue();
        assertThat(item.get("ticket").isNull()).isTrue();
      }
      if (view.id().equals(running)) {
        assertThat(item.get("stage").stringValue()).isEqualTo("investigation");
      } else {
        assertThat(item.get("stage").isNull()).as("stage of %s", view.state()).isTrue();
      }
    }
    Map<String, String> states =
        Map.of(resolved.toString(), "resolved", closed.toString(), "closed");
    for (JsonNode item : items) {
      String expected = states.getOrDefault(item.get("incident_id").stringValue(), "open");
      assertThat(item.get("state").stringValue()).isEqualTo(expected);
    }
  }

  private static String text(JsonNode node) {
    return node.isNull() ? null : node.stringValue();
  }

  private String teamName(String teamKey) {
    return fixture
        .app
        .context()
        .getBean(IdentityService.class)
        .team(fixture.tenant, new Key(teamKey))
        .orElseThrow()
        .name();
  }

  private Response list(String query) {
    return fixture.as("presenter", fixture.client.get(LIST + query)).send();
  }

  @Test
  @Order(4)
  @DisplayName("AR-I-01 the list is paged by limit and the opaque cursor; bad paging is refused")
  void paging() {
    List<String> all = new ArrayList<>();
    get(LIST).get("items").forEach(i -> all.add(i.get("incident_id").stringValue()));
    List<String> seen = new ArrayList<>();
    List<Integer> sizes = new ArrayList<>();
    String query = "?limit=2";
    while (true) {
      Response response = list(query);
      assertThat(response.status()).isEqualTo(200);
      JsonNode page = response.json();
      sizes.add(page.get("items").size());
      page.get("items").forEach(i -> seen.add(i.get("incident_id").stringValue()));
      if (page.get("next_cursor").isNull()) {
        break;
      }
      query = "?limit=2&after=" + page.get("next_cursor").stringValue();
    }
    assertThat(sizes).containsExactly(2, 2, 1);
    assertThat(seen).isEqualTo(all);
    for (String bad :
        List.of(
            "?limit=0",
            "?limit=51",
            "?limit=x",
            "?limit=02",
            "?limit=",
            "?after=",
            "?after=x",
            "?after=MDE",
            "?after=LTE",
            "?after=MQ==")) {
      Response response = list(bad);
      assertThat(response.status()).as(bad).isEqualTo(400);
      assertThat(response.json().get("error").get("code").stringValue()).isEqualTo("bad_request");
    }
  }

  @Test
  @Order(5)
  @DisplayName("DC-U-12 the list is paged by 50 on the server")
  void pagedBy50() {
    while (numbered().size() < 51) {
      fixture.openIncident();
    }
    assertThat(numbered()).hasSize(51);
    JsonNode first = get(LIST);
    assertThat(first.get("items")).hasSize(50);
    assertThat(first.get("next_cursor").isNull()).isFalse();
    JsonNode second = get(LIST + "?after=" + first.get("next_cursor").stringValue());
    assertThat(second.get("items")).hasSize(1);
    assertThat(second.get("next_cursor").isNull()).isTrue();
  }
}
