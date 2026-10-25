package vigil.correlation;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import vigil.correlation.api.CorrelationService;
import vigil.intake.IntakeFixture;
import vigil.intake.api.AlertView;
import vigil.routing.api.RoutingService;
import vigil.shared.TenantId;
import vigil.testsupport.LogLines;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class CorrelationRobustnessIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-24T10:00:00Z"));
  private static final AtomicReference<TenantId> FAILING = new AtomicReference<>();
  private static final Duration DECIDED_WAIT = Duration.ofSeconds(40);

  /** The test clock and a routing service whose evaluate throws an Error for one tenant. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }

    @Bean
    @Primary
    RoutingService failingRouting(@Qualifier("routing") RoutingService routing) {
      return (RoutingService)
          Proxy.newProxyInstance(
              RoutingService.class.getClassLoader(),
              new Class<?>[] {RoutingService.class},
              (proxy, method, args) -> {
                if (method.getName().equals("evaluate") && args[0].equals(FAILING.get())) {
                  throw new AssertionError("routing evaluation failed for the test");
                }
                try {
                  return method.invoke(routing, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
    }
  }

  private WireMockServer wiremock;
  private VigilTestApp app;
  private CorrelationFixture c;

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    VigilDatabase database = VigilDatabase.fresh();
    TestSecrets secrets = IntakeFixture.secrets(database, "-c");
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    c =
        CorrelationFixture.create(
            app,
            secrets,
            "Tenant C",
            "c.robustness.test",
            "http://127.0.0.1:" + wiremock.port(),
            "-c");
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private static List<JsonNode> lines(CapturedOutput output, String text) {
    return LogLines.application(output.getAll()).stream()
        .filter(line -> line.path("message").asString("").equals(text))
        .toList();
  }

  private JsonNode awaitLine(CapturedOutput output, String text) {
    List<JsonNode> found = new ArrayList<>();
    CorrelationFixture.await(
        () -> {
          found.clear();
          found.addAll(lines(output, text));
          return !found.isEmpty();
        },
        CorrelationFixture.WAIT);
    return found.getFirst();
  }

  private CorrelationService correlation() {
    return app.context().getBean(CorrelationService.class);
  }

  private Health health() {
    return app.context().getBean("correlationHealthIndicator", HealthIndicator.class).health();
  }

  @Test
  @Order(1)
  @DisplayName("AR-R-17 a decision that ends by an Error is retried and never skipped")
  void errorRetried(CapturedOutput output) {
    FAILING.set(c.tenant);
    AlertView x1 = c.postAlertmanager("nowhere-c", "c-1", "NodeFilesystemFull");
    String prefix = "correlation decision of alert " + x1.id() + " for tenant " + c.tenant.value();
    JsonNode first = awaitLine(output, prefix + " failed (attempt 1)");
    assertThat(first.path("log").path("level").asString()).isEqualTo("ERROR");
    AlertView x2 = c.postAlertmanager("nowhere-c", "c-2", "NodeFilesystemFull");
    JsonNode second = awaitLine(output, prefix + " failed (attempt 2)");
    FAILING.set(null);
    assertThat(Instant.parse(second.path("@timestamp").asString()))
        .isAfterOrEqualTo(Instant.parse(first.path("@timestamp").asString()).plusMillis(900));
    Health health = health();
    assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    assertThat(health.getDetails())
        .isEqualTo(
            Map.of(
                "retries",
                List.of(
                    Map.of(
                        "tenant_id",
                        c.tenant.value().toString(),
                        "alert_id",
                        x1.id().toString(),
                        "attempt",
                        2))));
    assertThat(correlation().decision(c.tenant, x2.id())).isEmpty();
    c.awaitDecided(DECIDED_WAIT);
    assertThat(correlation().decision(c.tenant, x1.id())).isPresent();
    assertThat(correlation().decision(c.tenant, x2.id()).orElseThrow().alertSequence())
        .isGreaterThan(correlation().decision(c.tenant, x1.id()).orElseThrow().alertSequence());
    assertThat(health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  @Order(2)
  @DisplayName("AR-R-26 no correlation worker starts once the application closes")
  void noWorkerAfterClose() {
    CorrelationWork work = CorrelationFixture.work(app);
    assertThat(work.runningTenants()).contains(c.tenant);
    work.stopAll();
    assertThat(work.runningTenants()).isEmpty();
    work.matchActiveTenants();
    assertThat(work.runningTenants()).isEmpty();
  }
}
