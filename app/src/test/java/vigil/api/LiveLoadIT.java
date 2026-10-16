package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.identity.api.Permission;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Line;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class LiveLoadIT {

  private static final int STREAMS = 250;
  private static final int BATCH = 50;
  private static final Duration WAIT = Duration.ofSeconds(10);

  /** A heartbeat every 1 s and the end after 60 s. */
  static class LongStreams {

    @Bean
    @Primary
    LiveTimings testLiveTimings() {
      return new LiveTimings(Duration.ofSeconds(1), Duration.ofSeconds(60));
    }
  }

  @Test
  @DisplayName("AR-R-33 one stream per open connection on virtual threads, 250 at once")
  void manyStreams() throws Exception {
    VigilTestApp app = VigilTestApp.start(VigilDatabase.fresh(), LongStreams.class);
    List<ApiClient.Stream> streams = new ArrayList<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      app.awaitStarted();
      ApiClient client = new ApiClient(app);
      TestTenants.Tenant tenant =
          new TestTenants(app)
              .tenant("Tenant A")
              .host("a.test")
              .user("alice", "Alice", Permission.VIEW)
              .create();
      String session =
          client.login("a.test", "alice", tenant.password("alice")).cookieValue("vigil_session");
      LiveHub hub = app.context().getBean(LiveHub.class);

      for (int batch = 0; batch < STREAMS / BATCH; batch++) {
        List<Future<ApiClient.Stream>> opening = new ArrayList<>();
        for (int i = 0; i < BATCH; i++) {
          opening.add(
              executor.submit(
                  () -> {
                    ApiClient.Stream stream =
                        client
                            .get("/api/v1/live?topics=tenant")
                            .host("a.test")
                            .cookie("vigil_session", session)
                            .open();
                    assertThat(stream.status()).isEqualTo(200);
                    assertThat(stream.nextLine(WAIT).map(Line::text)).hasValue("retry: 1000\n");
                    assertThat(stream.nextLine(WAIT).map(Line::text)).hasValue("\n");
                    return stream;
                  }));
        }
        for (Future<ApiClient.Stream> stream : opening) {
          streams.add(stream.get(60, TimeUnit.SECONDS));
        }
      }
      assertThat(hub.openStreams()).isEqualTo(STREAMS);

      UUID id = UUID.randomUUID();
      long published = System.nanoTime();
      hub.publish(tenant.id(), new LiveTopic(LiveTopic.Kind.TENANT, null), id, Instant.now());
      List<Future<Long>> arrivals = new ArrayList<>();
      for (ApiClient.Stream stream : streams) {
        arrivals.add(
            executor.submit(
                () -> {
                  boolean pinged = false;
                  long arrived = -1;
                  while (arrived < 0 || !pinged) {
                    Optional<Line> line = stream.nextLine(WAIT);
                    assertThat(line).isPresent();
                    if (line.get().text().equals(": ping\n")) {
                      pinged = true;
                    } else if (line.get().text().contains(id.toString())) {
                      arrived = line.get().arrivedNanos();
                    }
                  }
                  return arrived;
                }));
      }
      for (Future<Long> arrival : arrivals) {
        assertThat(arrival.get(60, TimeUnit.SECONDS) - published)
            .isLessThan(TimeUnit.SECONDS.toNanos(1));
      }

      long closing = System.nanoTime();
      app.close();
      assertThat(System.nanoTime() - closing).isLessThan(TimeUnit.SECONDS.toNanos(5));
      for (ApiClient.Stream stream : streams) {
        while (stream.nextLine(WAIT).isPresent()) {
          // Reads what the server wrote before it ended the stream.
        }
        assertThat(stream.ended()).isTrue();
      }
    } finally {
      streams.forEach(ApiClient.Stream::close);
      app.close();
    }
  }
}
