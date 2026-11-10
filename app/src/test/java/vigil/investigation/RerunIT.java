package vigil.investigation;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import tools.jackson.databind.node.ObjectNode;
import vigil.aigateway.AiFixture;
import vigil.investigation.api.InvestigationResultView;
import vigil.investigation.api.InvestigationService;
import vigil.investigation.api.Origin;
import vigil.investigation.api.Validation;
import vigil.setup.KnowledgeParts;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.VigilError;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RerunIT {

  private WireMockServer wiremock;
  private VigilTestApp app;
  private InvestigationService investigation;
  private Investigations investigations;
  private int count;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    ObjectNode body = AiTaskStepIT.JSON.createObjectNode();
    body.putObject("message")
        .put("role", "assistant")
        .put("content", AiTaskStepIT.slT1Output("confirms", "RB-DB-01"));
    body.put("done_reason", "stop");
    body.put("prompt_eval_count", 300);
    body.put("eval_count", 30);
    wiremock.stubFor(
        post(urlEqualTo(AiTaskStepIT.LOCAL))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(AiTaskStepIT.JSON.writeValueAsString(body))
                    .withFixedDelay(1000)));
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = TestSecrets.create(database, AiFixture.secrets());
    app = VigilTestApp.start(database, secrets.folder(), List.of());
    app.awaitStarted();
    investigation = app.context().getBean(InvestigationService.class);
    investigations = app.context().getBean(Investigations.class);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  /** A tenant whose SL-T-1 is assigned to local-llm only, at WireMock. */
  private InvestigationFixture tenant() {
    count++;
    return InvestigationFixture.create(
        app,
        "Rerun " + count,
        "r" + count + ".rerun.test",
        Map.of("local-llm", "http://127.0.0.1:" + wiremock.port()),
        part ->
            AiFixture.assignments(
                part,
                assignments -> {
                  assignments.put("SL-T-1", List.of("local-llm"));
                  return assignments;
                }),
        KnowledgeParts.demoPart());
  }

  private InvestigationResultView awaitResult(InvestigationFixture f, UUID incident, UUID group) {
    Instant end = Instant.now().plusSeconds(10);
    while (Instant.now().isBefore(end)) {
      for (InvestigationResultView result : investigation.results(f.tenant, incident)) {
        if (result.requestGroupId().equals(group)) {
          return result;
        }
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError(e);
      }
    }
    throw new AssertionError("no result of group " + group + " within 10 s");
  }

  private void assertRerun() {
    InvestigationFixture f = tenant();
    UUID incident = AiTaskStepIT.storm(f);
    UUID user = Ids.newId();
    Instant before = Instant.now();
    UUID group = investigation.requestRerun(f.tenant, user, incident);
    assertThat(Duration.between(before, Instant.now())).isLessThan(Duration.ofMillis(900));
    assertThat(investigation.results(f.tenant, incident)).isEmpty();
    InvestigationResultView result = awaitResult(f, incident, group);
    assertThat(result.origin()).isEqualTo(Origin.RERUN);
    assertThat(result.requestedBy()).isEqualTo(Actor.user(user));
    assertThat(result.runId()).isNull();
    assertThat(result.validation()).isEqualTo(Validation.ACCEPTED);
    assertThat(result.providerKey()).isEqualTo("local-llm");
    List<String> locations =
        f.postgres()
            .sql(
                "SELECT provider_location FROM ai_gateway.ai_usage_record WHERE tenant_id = :t"
                    + " AND request_group_id = :g")
            .param("t", f.tenant.value())
            .param("g", group)
            .query(String.class)
            .list();
    assertThat(locations).isNotEmpty().allMatch("in_house"::equals);
  }

  @Test
  @Order(1)
  @DisplayName("PM-M17-07 the re-run request and its worker")
  void rerun() {
    assertRerun();
  }

  @Test
  @Order(2)
  @DisplayName("AR-R-29 the re-run runs on its own virtual thread")
  void thread() throws InterruptedException {
    InvestigationFixture f = tenant();
    UUID incident = AiTaskStepIT.storm(f);
    UUID group = investigation.requestRerun(f.tenant, Ids.newId(), incident);
    Thread thread = investigations.rerunThread(group).orElseThrow();
    assertThat(thread.isVirtual()).isTrue();
    assertThat(thread.getName()).isEqualTo("ai-rerun-" + group);
    thread.join(Duration.ofSeconds(10));
    assertThat(investigations.rerunThread(group)).containsSame(thread);
  }

  @Test
  @Order(3)
  @DisplayName("DM-E-68 the re-run only for an open, resolved or closed incident")
  void refusals() throws InterruptedException {
    InvestigationFixture f = tenant();
    UUID forming =
        app.context()
            .getBean(vigil.incidents.api.IncidentService.class)
            .create(
                f.tenant,
                Actor.module("vigil-correlation"),
                Ids.newId(),
                new vigil.incidents.api.AlertCopy(
                    Ids.newId(),
                    "zabbix",
                    "k-" + UUID.randomUUID(),
                    "disk_full",
                    "Disk full",
                    "Disk full",
                    "srv-db-01",
                    null,
                    Instant.now()),
                new vigil.incidents.api.GroupingReason("created_incident", null, null, null, null));
    assertThatThrownBy(() -> investigation.requestRerun(f.tenant, Ids.newId(), forming))
        .isInstanceOfSatisfying(
            VigilError.class,
            e -> {
              assertThat(e.code()).isEqualTo(VigilError.Code.REFUSED);
              assertThat(e.message())
                  .isEqualTo(
                      "This incident is forming. The AI investigation can be re-run only for an"
                          + " open, resolved or closed incident.");
              assertThat(e.details())
                  .isEqualTo(Map.of("refusal", "not_rerunnable", "state", "forming"));
            });
    assertThatThrownBy(() -> investigation.requestRerun(f.tenant, Ids.newId(), Ids.newId()))
        .isInstanceOfSatisfying(
            VigilError.class, e -> assertThat(e.code()).isEqualTo(VigilError.Code.NOT_FOUND));
    UUID closed = AiTaskStepIT.storm(f);
    f.close(closed);
    UUID group = investigation.requestRerun(f.tenant, Ids.newId(), closed);
    assertThat(awaitResult(f, closed, group).validation()).isEqualTo(Validation.ACCEPTED);
  }

  @Test
  @Order(4)
  @DisplayName("SL-A2-07 the re-run request")
  void slA207() {
    assertRerun();
  }
}
