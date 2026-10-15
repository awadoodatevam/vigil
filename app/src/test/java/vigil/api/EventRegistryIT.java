package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import vigil.shared.Ids;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class EventRegistryIT {

  private static final Duration WAIT = Duration.ofSeconds(20);

  private static VigilDatabase database;
  private static VigilTestApp app;
  private static ApplicationEventPublisher publisher;
  private static TransactionTemplate tx;
  private static JdbcClient appJdbc;
  private static ProbeListener listener;

  /** A test-only event. */
  public record ProbeEvent(UUID id, boolean fail) {}

  /** A test-only listener: records the thread of each call and fails when the event asks. */
  public static class ProbeListener {

    final Map<UUID, List<String>> threads = new ConcurrentHashMap<>();

    @ApplicationModuleListener
    public void on(ProbeEvent event) {
      threads
          .computeIfAbsent(event.id(), id -> new CopyOnWriteArrayList<>())
          .add(Thread.currentThread().getName());
      if (event.fail()) {
        throw new IllegalStateException("probe listener fails as asked");
      }
    }

    int calls(UUID id) {
      return threads.getOrDefault(id, List.of()).size();
    }
  }

  /** Registers the probe listener. */
  static class ProbeConfiguration {

    @Bean
    ProbeListener probeListener() {
      return new ProbeListener();
    }
  }

  @BeforeAll
  static void start() {
    database = VigilDatabase.fresh();
    app = VigilTestApp.start(database, ProbeConfiguration.class);
    app.awaitStarted();
    publisher = app.context();
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
    appJdbc = app.context().getBean(JdbcClient.class);
    listener = AopTestUtils.getUltimateTargetObject(app.context().getBean(ProbeListener.class));
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  @Test
  @DisplayName("AR-R-13 a publication is stored in the publishing transaction and kept completed")
  void publicationIsStoredWithTheChange() {
    UUID id = Ids.newId();
    tx.executeWithoutResult(
        status -> {
          publisher.publishEvent(new ProbeEvent(id, false));
          assertThat(publications(appJdbc, id)).as("inside the transaction").isEqualTo(1);
          assertThat(publications(database.postgres(), id)).as("outside, uncommitted").isZero();
        });
    awaitTrue(() -> completedPublications(id) == 1);
    assertThat(publications(database.postgres(), id)).isEqualTo(1);

    UUID rolledBack = Ids.newId();
    tx.executeWithoutResult(
        status -> {
          publisher.publishEvent(new ProbeEvent(rolledBack, false));
          status.setRollbackOnly();
        });
    assertThat(publications(database.postgres(), rolledBack)).isZero();
    assertThat(listener.calls(rolledBack)).isZero();
  }

  @Test
  @DisplayName("AR-T-03 listeners run on the async executor, not on the scheduler")
  void listenerRunsOnTheAsyncExecutor() {
    UUID id = Ids.newId();
    tx.executeWithoutResult(status -> publisher.publishEvent(new ProbeEvent(id, false)));
    awaitTrue(() -> listener.calls(id) == 1);
    assertThat(listener.threads.get(id).getFirst())
        .startsWith("vigil-async-")
        .doesNotStartWith("vigil-scheduler-");
  }

  @Test
  @DisplayName("AR-R-14 the per-installation task resubmits only publications older than 60 s")
  void resubmitsOnlyOldPublications() throws InterruptedException {
    UUID old = Ids.newId();
    UUID young = Ids.newId();
    tx.executeWithoutResult(status -> publisher.publishEvent(new ProbeEvent(old, true)));
    tx.executeWithoutResult(status -> publisher.publishEvent(new ProbeEvent(young, true)));
    awaitTrue(() -> listener.calls(old) == 1 && listener.calls(young) == 1);
    database
        .postgres()
        .sql(
            "UPDATE platform.event_publication"
                + " SET publication_date = now() - interval '120 seconds'"
                + " WHERE serialized_event LIKE ?")
        .param("%" + old + "%")
        .update();

    app.context().getBean(RestartProcedure.class).resubmitOldPublications();

    awaitTrue(() -> listener.calls(old) == 2);
    Thread.sleep(1000);
    assertThat(listener.calls(young)).isEqualTo(1);
    assertThat(completedPublications(old)).isZero();
  }

  private static int publications(JdbcClient jdbc, UUID id) {
    return jdbc.sql("SELECT count(*) FROM platform.event_publication WHERE serialized_event LIKE ?")
        .param("%" + id + "%")
        .query(Integer.class)
        .single();
  }

  private static int completedPublications(UUID id) {
    return database
        .postgres()
        .sql(
            "SELECT count(*) FROM platform.event_publication"
                + " WHERE serialized_event LIKE ? AND completion_date IS NOT NULL")
        .param("%" + id + "%")
        .query(Integer.class)
        .single();
  }

  private static void awaitTrue(BooleanSupplier condition) {
    long deadline = System.nanoTime() + WAIT.toNanos();
    while (!condition.getAsBoolean()) {
      assertThat(System.nanoTime()).as("condition met within %s", WAIT).isLessThan(deadline);
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
  }
}
