package vigil.intake;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.intake.IntakeFixture.Recording;
import vigil.intake.api.AlertView;
import vigil.intake.api.IntakeService;
import vigil.shared.TenantId;
import vigil.testsupport.ApiClient;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntakeOrderIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-21T11:00:00Z"));
  private static final String HOST = "order.hooks.test";
  private static final int THREADS = 8;
  private static final int CALLS = 25;

  private WireMockServer wiremock;
  private VigilDatabase database;
  private TestSecrets secrets;
  private VigilTestApp app;
  private IntakeService intake;
  private TenantId tenant;

  /** The test clock. */
  static class TestBeans {

    @Bean
    @Primary
    Clock testClock() {
      return CLOCK;
    }
  }

  @BeforeAll
  void start() {
    wiremock = new WireMockServer(options().bindAddress("127.0.0.1").dynamicPort());
    wiremock.start();
    IntakeFixture.stub(wiremock);
    database = VigilDatabase.fresh();
    secrets = IntakeFixture.secrets(database, "");
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    intake = app.context().getBean(IntakeService.class);
    tenant = IntakeFixture.create(app, "Tenant A", HOST, "http://127.0.0.1:" + wiremock.port(), "");
    IntakeFixture.awaitFirstTests(intake, tenant);
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  /** The sequences visible now, read from 0 in pages of 100. */
  private List<Long> visible() {
    List<Long> sequences = new ArrayList<>();
    long after = 0;
    while (true) {
      List<AlertView> page = intake.alertsAfter(tenant, after, 100);
      page.forEach(alert -> sequences.add(alert.sequence()));
      if (page.size() < 100) {
        return sequences;
      }
      after = page.getLast().sequence();
    }
  }

  @Test
  @DisplayName("AR-R-16 concurrent calls commit their alerts in sequence order with no gap")
  void gapFreeUnderConcurrency() throws Exception {
    Recording recording =
        IntakeFixture.recordings().stream()
            .filter(r -> r.sourceKey().equals(IntakeFixture.ALERTMANAGER))
            .filter(r -> r.tree().get("alerts").get(0).get("status").stringValue().equals("firing"))
            .findFirst()
            .orElseThrow();
    String token = secrets.value("webhook-alertmanager");
    ApiClient client = new ApiClient(app);
    AtomicBoolean done = new AtomicBoolean();
    List<String> gaps = new CopyOnWriteArrayList<>();
    int[] reads = new int[1];
    Thread reader =
        Thread.ofPlatform()
            .start(
                () -> {
                  while (!done.get()) {
                    List<Long> seen = visible();
                    reads[0]++;
                    List<Long> expected = LongStream.rangeClosed(1, seen.size()).boxed().toList();
                    if (!seen.equals(expected)) {
                      gaps.add(seen.toString());
                    }
                  }
                });
    CountDownLatch go = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<List<Integer>>> results = new ArrayList<>();
    for (int t = 0; t < THREADS; t++) {
      int thread = t;
      results.add(
          pool.submit(
              () -> {
                go.await();
                List<Integer> statuses = new ArrayList<>();
                for (int i = 0; i < CALLS; i++) {
                  statuses.add(
                      client
                          .post("/api/v1/webhooks/" + IntakeFixture.ALERTMANAGER)
                          .host(HOST)
                          .header("Content-Type", "application/json")
                          .header("Authorization", "Bearer " + token)
                          .body(IntakeFixture.withFingerprint(recording, "t" + thread + "-" + i))
                          .send()
                          .status());
                }
                return statuses;
              }));
    }
    go.countDown();
    List<Integer> statuses = new ArrayList<>();
    for (Future<List<Integer>> result : results) {
      statuses.addAll(result.get(120, TimeUnit.SECONDS));
    }
    pool.shutdown();
    done.set(true);
    reader.join(TimeUnit.SECONDS.toMillis(30));
    assertThat(statuses).hasSize(THREADS * CALLS).containsOnly(200);
    assertThat(gaps).isEmpty();
    assertThat(reads[0]).isPositive();
    assertThat(visible()).isEqualTo(LongStream.rangeClosed(1, 200).boxed().toList());
    assertThat(
            database
                .postgres()
                .sql("SELECT last_sequence FROM intake.alert_counter WHERE tenant_id = :t")
                .param("t", tenant.value())
                .query(Long.class)
                .single())
        .isEqualTo(200);
  }
}
