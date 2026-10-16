package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import vigil.identity.api.Permission;
import vigil.shared.TenantId;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Line;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.TestTenants;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class LiveStreamIT {

  private static final Logger LOG = LoggerFactory.getLogger(LiveStreamIT.class);
  private static final Duration WAIT = Duration.ofSeconds(5);

  private static VigilTestApp app;
  private static ApiClient client;
  private static LiveHub hub;
  private static TestTenants.Tenant tenantA;
  private static TenantId tenantB;
  private static String session;

  /** A heartbeat every 1 s and the end after 4 s. */
  static class ShortTimings {

    @Bean
    @Primary
    LiveTimings testLiveTimings() {
      return new LiveTimings(Duration.ofSeconds(1), Duration.ofSeconds(4));
    }
  }

  @BeforeAll
  static void start() {
    app = VigilTestApp.start(VigilDatabase.fresh(), ShortTimings.class);
    app.awaitStarted();
    client = new ApiClient(app);
    hub = app.context().getBean(LiveHub.class);
    TestTenants tenants = new TestTenants(app);
    tenantA =
        tenants.tenant("Tenant A").host("a.test").user("alice", "Alice", Permission.VIEW).create();
    tenantB = tenants.tenant("Tenant B").host("b.test").create().id();
    session =
        client.login("a.test", "alice", tenantA.password("alice")).cookieValue("vigil_session");
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static Request live(String query) {
    return client.get("/api/v1/live" + query).host("a.test").cookie("vigil_session", session);
  }

  /** Opens a stream and reads its retry line. */
  private static ApiClient.Stream open(String topics) {
    ApiClient.Stream stream = live("?topics=" + topics).open();
    assertThat(stream.status()).isEqualTo(200);
    assertThat(line(stream).text()).isEqualTo("retry: 1000\n");
    assertThat(line(stream).text()).isEqualTo("\n");
    return stream;
  }

  private static Line line(ApiClient.Stream stream) {
    Optional<Line> line = stream.nextLine(WAIT);
    assertThat(line).as("a line within %s", WAIT).isPresent();
    return line.get();
  }

  /** The next line that is not part of a heartbeat. */
  private static Line nextNoticeLine(ApiClient.Stream stream) {
    while (true) {
      Line line = line(stream);
      if (line.text().equals(": ping\n")) {
        assertThat(line(stream).text()).isEqualTo("\n");
      } else {
        return line;
      }
    }
  }

  /** Reads one notice: its three lines, returned joined. */
  private static String notice(ApiClient.Stream stream) {
    return nextNoticeLine(stream).text() + line(stream).text() + line(stream).text();
  }

  private static String expected(LiveTopic topic, UUID id, Instant at) {
    return "event: change\ndata: {\"topic\":\""
        + topic.text()
        + "\",\"id\":\""
        + id
        + "\",\"at\":\""
        + ApiTimes.format(at)
        + "\"}\n\n";
  }

  @Test
  @DisplayName("AR-I-16 the stream opens for a session with valid topics")
  void opens() {
    ApiClient.Stream stream = live("?topics=incidents,tenant").open();
    try (stream) {
      assertThat(stream.status()).isEqualTo(200);
      assertThat(stream.headers().firstValue("Content-Type").orElseThrow())
          .startsWith("text/event-stream");
      assertThat(line(stream).text()).isEqualTo("retry: 1000\n");
    }
    for (String query : List.of("", "?topics=", "?topics=nope", "?topics=incident:x")) {
      ApiClient.Response response = live(query).send();
      assertThat(response.status()).as(query).isEqualTo(400);
      assertThat(response.json().get("error").get("code").stringValue()).isEqualTo("bad_request");
    }
    assertThat(client.get("/api/v1/live?topics=tenant").host("a.test").send().status())
        .isEqualTo(401);
  }

  @Test
  @DisplayName("AR-T-10 notices arrive as Server-Sent Events, exactly, in publish order")
  void noticeBytes() {
    try (ApiClient.Stream stream = open("incidents,tenant")) {
      List<String> expected = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        LiveTopic topic =
            new LiveTopic(i % 2 == 0 ? LiveTopic.Kind.INCIDENTS : LiveTopic.Kind.TENANT, null);
        UUID id = UUID.randomUUID();
        Instant at = Instant.parse("2026-10-16T09:05:00.123456Z").plusSeconds(i);
        hub.publish(tenantA.id(), topic, id, at);
        expected.add(expected(topic, id, at));
      }
      List<String> arrived = new ArrayList<>();
      for (int i = 0; i < 5; i++) {
        arrived.add(notice(stream));
      }
      assertThat(arrived).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("AR-I-04 a stream receives only the notices of its tenant and topics")
  void onlyItsTopics() {
    UUID incident = UUID.randomUUID();
    try (ApiClient.Stream stream = open("incidents,incident:" + incident)) {
      LiveTopic incidents = new LiveTopic(LiveTopic.Kind.INCIDENTS, null);
      Instant at = Instant.now();
      hub.publish(tenantA.id(), new LiveTopic(LiveTopic.Kind.ALERTS, null), UUID.randomUUID(), at);
      hub.publish(tenantB, incidents, UUID.randomUUID(), at);
      hub.publish(
          tenantA.id(),
          new LiveTopic(LiveTopic.Kind.INCIDENT, UUID.randomUUID()),
          UUID.randomUUID(),
          at);
      UUID first = UUID.randomUUID();
      hub.publish(tenantA.id(), incidents, first, at);
      assertThat(notice(stream)).isEqualTo(expected(incidents, first, at));
      LiveTopic own = new LiveTopic(LiveTopic.Kind.INCIDENT, incident);
      hub.publish(tenantB, own, UUID.randomUUID(), at);
      UUID second = UUID.randomUUID();
      hub.publish(tenantA.id(), own, second, at);
      assertThat(notice(stream)).isEqualTo(expected(own, second, at));
    }
  }

  @Test
  @DisplayName("AR-TM-04 a heartbeat every interval and the end after the open time")
  void heartbeatAndEnd() throws InterruptedException {
    awaitNoOpenStreams(Duration.ofSeconds(10));
    long opened = System.nanoTime();
    ApiClient.Stream stream = live("?topics=tenant").open();
    try (stream) {
      Line retry = line(stream);
      assertThat(retry.text()).isEqualTo("retry: 1000\n");
      assertThat(line(stream).text()).isEqualTo("\n");
      List<Line> pings = new ArrayList<>();
      while (true) {
        Optional<Line> line = stream.nextLine(Duration.ofSeconds(8));
        if (line.isEmpty()) {
          break;
        }
        if (!line.get().text().equals("\n")) {
          pings.add(line.get());
        }
      }
      long ended = System.nanoTime();
      assertThat(stream.ended()).isTrue();
      assertThat(pings).extracting(Line::text).containsExactly(": ping\n", ": ping\n", ": ping\n");
      for (int k = 1; k <= 3; k++) {
        double seconds = (pings.get(k - 1).arrivedNanos() - retry.arrivedNanos()) / 1e9;
        assertThat(seconds).as("ping %d", k).isBetween(k - 0.5, k + 0.5);
      }
      double open = (ended - opened) / 1e9;
      assertThat(open).isBetween(3.5, 6.0);
    }
    awaitNoOpenStreams(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("PM-M23-04 after a reconnection each notice arrives within 1 s of its publish")
  void deliveryAfterReconnection() throws InterruptedException {
    try (ApiClient.Stream first = open("tenant")) {
      while (first.nextLine(Duration.ofSeconds(8)).isPresent()) {
        // Reads until the server ends the stream after its open time.
      }
      assertThat(first.ended()).isTrue();
    }
    try (ApiClient.Stream stream = open("tenant")) {
      LiveTopic tenant = new LiveTopic(LiveTopic.Kind.TENANT, null);
      Map<String, Long> published = new LinkedHashMap<>();
      for (int i = 0; i < 20; i++) {
        UUID id = UUID.randomUUID();
        published.put(id.toString(), System.nanoTime());
        hub.publish(tenantA.id(), tenant, id, Instant.now());
        Thread.sleep(100);
      }
      long largest = 0;
      for (String id : published.keySet()) {
        Line data = nextNoticeLine(stream);
        if (data.text().startsWith("event:")) {
          data = line(stream);
        }
        assertThat(data.text()).contains("\"id\":\"" + id + "\"");
        line(stream);
        long delay = data.arrivedNanos() - published.get(id);
        assertThat(delay).as("delay of %s", id).isLessThan(TimeUnit.SECONDS.toNanos(1));
        largest = Math.max(largest, delay);
      }
      LOG.info("largest delivery delay {} ms", TimeUnit.NANOSECONDS.toMillis(largest));
    }
  }

  private static void awaitNoOpenStreams(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (hub.openStreams() != 0) {
      assertThat(System.nanoTime()).as("no open stream within %s", timeout).isLessThan(deadline);
      Thread.sleep(50);
    }
  }
}
