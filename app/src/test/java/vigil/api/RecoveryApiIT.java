package vigil.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import tools.jackson.databind.json.JsonMapper;
import vigil.engine.TestContents;
import vigil.engine.api.StepExecutionState;
import vigil.intake.IntakeFixture;
import vigil.intake.api.Health;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceView;
import vigil.recovery.api.RecoveryService;
import vigil.recovery.api.RecoveryVerificationView;
import vigil.shared.Ids;
import vigil.shared.StepCall.StepType;
import vigil.shared.Waiting;
import vigil.testsupport.ApiClient.Response;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RecoveryApiIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private ApiFixture fixture;
  private IntakeService intake;
  private RecoveryService recovery;
  private ApiFixture.Storm storm;
  private UUID run;
  private final UUID stepExecution = Ids.newId();

  @BeforeAll
  void start() {
    fixture = new ApiFixture();
    intake = fixture.app.context().getBean(IntakeService.class);
    recovery = fixture.app.context().getBean(RecoveryService.class);
    storm = fixture.storm();
    fixture.recoveryDefinitions();
    fixture.handlers.answer(
        StepType.DIAGNOSTIC,
        c -> new Waiting(Set.of(), fixture.clock.instant().plusSeconds(600), "{}"));
    run = fixture.liveRun(storm.storm(), TestContents.version1());
    IntakeFixture.await(
        () ->
            fixture.engine.engine.stepExecutions(fixture.tenant, run).stream()
                .anyMatch(e -> e.state() == StepExecutionState.WAITING));
  }

  @AfterAll
  void stop() {
    fixture.close();
  }

  private Response get(UUID incident) {
    return fixture
        .as("presenter", fixture.client.get("/api/v1/incidents/" + incident + "/recovery"))
        .send();
  }

  private JsonNode verifications() {
    Response response = get(storm.storm());
    assertThat(response.status()).isEqualTo(200);
    return response.json().get("verifications");
  }

  private RecoveryVerificationView stored() {
    return recovery.verifications(fixture.tenant, storm.storm()).getFirst();
  }

  /** The answer's conditions are the stored last evaluation's, in order. */
  private static void sameConditions(JsonNode answer, RecoveryVerificationView stored) {
    JsonNode evaluated = JSON.readTree(stored.lastEvaluationJson()).get("conditions");
    assertThat(answer.get("conditions")).hasSameSizeAs(evaluated);
    for (int i = 0; i < evaluated.size(); i++) {
      assertThat(answer.get("conditions").get(i).get("detail"))
          .isEqualTo(evaluated.get(i).get("detail"));
      assertThat(answer.get("conditions").get(i).get("held"))
          .isEqualTo(evaluated.get(i).get("held"));
    }
  }

  private SourceView source(String key) {
    return intake.sources(fixture.tenant).stream()
        .filter(s -> s.sourceKey().equals(key))
        .findFirst()
        .orElseThrow();
  }

  @Test
  @Order(1)
  @DisplayName("AR-I-28 the recovery section: none before the step, then one observing check")
  void recoverySection() {
    assertThat(verifications()).isEmpty();
    fixture.recoveryStep().start(fixture.recoveryCall(run, storm.storm(), stepExecution, 1));
    JsonNode all = verifications();
    assertThat(all).hasSize(1);
    RecoveryVerificationView stored = stored();
    JsonNode verification = all.get(0);
    assertThat(verification.get("recovery_verification_id").stringValue())
        .isEqualTo(stored.id().toString());
    assertThat(verification.get("check_name").stringValue())
        .isEqualTo("Subscriber DB services recovered");
    assertThat(verification.get("state").stringValue()).isEqualTo("observing");
    assertThat(verification.get("observation_window_seconds").longValue()).isEqualTo(60);
    assertThat(verification.get("started_at").stringValue())
        .isEqualTo(ApiTimes.format(stored.startedAt()));
    assertThat(verification.get("max_wait_until").stringValue())
        .isEqualTo(ApiTimes.format(stored.maxWaitUntil()));
    assertThat(verification.get("window_started_at").isNull()).isTrue();
    assertThat(verification.get("decided_at").isNull()).isTrue();
    assertThat(verification.get("conditions")).hasSize(2);
    assertThat(verification.get("conditions").get(0).get("held").booleanValue()).isFalse();
    assertThat(verification.get("conditions").get(0).get("detail").stringValue())
        .endsWith("of the incident are still firing");
    sameConditions(verification, stored);
    assertThat(verification.get("sources_healthy").booleanValue()).isTrue();
    assertThat(verification.get("unhealthy_sources")).isEmpty();
  }

  @Test
  @Order(2)
  @DisplayName("AR-I-05 the recovery read follows the evaluations, which publish no event")
  void followsTheEvaluations() {
    fixture.resolveAll();
    fixture.recoveryStep().evaluate(fixture.recoveryCall(run, storm.storm(), stepExecution, 2));
    JsonNode verification = verifications().get(0);
    RecoveryVerificationView stored = stored();
    assertThat(stored.windowStartedAt()).isNotNull();
    assertThat(verification.get("state").stringValue()).isEqualTo("observing");
    assertThat(verification.get("window_started_at").stringValue())
        .isEqualTo(ApiTimes.format(stored.windowStartedAt()));
    assertThat(verification.get("conditions").get(0).get("detail").stringValue())
        .isEqualTo("Every alert of the incident is resolved at its source");
    assertThat(verification.get("conditions").valueStream().map(c -> c.get("held").booleanValue()))
        .containsOnly(true);
    sameConditions(verification, stored);
  }

  @Test
  @Order(3)
  @DisplayName("AR-I-28 the recovery section names the unhealthy sources; merged incident 404")
  void unhealthySources() {
    fixture.wiremock.stubFor(
        post(urlEqualTo("/api_jsonrpc.php")).willReturn(aResponse().withStatus(500)));
    fixture.clock.advance(Duration.ofSeconds(121));
    IntakeFixture.await(
        () ->
            source(IntakeFixture.ZABBIX).health() == Health.DISCONNECTED
                && source(IntakeFixture.ALERTMANAGER).health() == Health.HEALTHY
                && source(IntakeFixture.ALERTMANAGER).lastTestAt() != null
                && !source(IntakeFixture.ALERTMANAGER)
                    .lastTestAt()
                    .isBefore(fixture.clock.instant().minusSeconds(1)));
    fixture.recoveryStep().evaluate(fixture.recoveryCall(run, storm.storm(), stepExecution, 3));
    JsonNode verification = verifications().get(0);
    assertThat(verification.get("sources_healthy").booleanValue()).isFalse();
    assertThat(verification.get("unhealthy_sources").valueStream().map(JsonNode::stringValue))
        .containsExactly(source(IntakeFixture.ZABBIX).name());
    sameConditions(verification, stored());

    Response merged = get(storm.merged().getFirst());
    assertThat(merged.status()).isEqualTo(404);
    assertThat(merged.json().get("error").get("code").stringValue()).isEqualTo("not_found");
  }
}
