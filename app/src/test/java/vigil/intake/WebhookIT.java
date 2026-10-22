package vigil.intake;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import vigil.intake.IntakeFixture.Recording;
import vigil.intake.api.IntakeService;
import vigil.intake.api.SourceView;
import vigil.intake.api.TestOutcome;
import vigil.shared.TenantId;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.LogLines;
import vigil.testsupport.MutableClock;
import vigil.testsupport.TestSecrets;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(OutputCaptureExtension.class)
class WebhookIT {

  private static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-10-21T09:00:00Z"));
  private static final String HOST_A = "a.hooks.test";
  private static final String HOST_B = "b.hooks.test";
  private static final String HOST_C = "c.hooks.test";
  private static final String FIRST = "0001-zabbix-lab.json";
  private static final int LIMIT = 5 * 1024 * 1024;

  private WireMockServer wiremock;
  private VigilDatabase database;
  private TestSecrets secrets;
  private VigilTestApp app;
  private ApiClient client;
  private JdbcClient postgres;
  private IntakeService intake;
  private TenantId tenantA;
  private TenantId tenantB;
  private TenantId tenantC;
  private List<Recording> recordings;

  private UUID firstCallId;
  private final Map<String, Response> refusals = new LinkedHashMap<>();
  private final Map<String, Boolean> unchanged = new LinkedHashMap<>();
  private Instant callTime;
  private UUID zabbixCallId;
  private UUID alertmanagerCallId;
  private Recording zabbixRecording;
  private Recording alertmanagerRecording;
  private UUID threeEntryCallId;
  private List<String> threeFingerprints;
  private List<UUID> twoCallIds;

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
    secrets = IntakeFixture.secrets(database, "", "-b", "-c");
    app = VigilTestApp.start(database, secrets.folder(), List.of(), TestBeans.class);
    app.awaitStarted();
    client = new ApiClient(app);
    postgres = database.postgres();
    intake = app.context().getBean(IntakeService.class);
    String address = "http://127.0.0.1:" + wiremock.port();
    tenantA = IntakeFixture.create(app, "Tenant A", HOST_A, address, "");
    tenantB = IntakeFixture.create(app, "Tenant B", HOST_B, address, "-b");
    tenantC = IntakeFixture.create(app, "Tenant C", HOST_C, address, "-c");
    for (TenantId tenant : List.of(tenantA, tenantB, tenantC)) {
      IntakeFixture.awaitFirstTests(intake, tenant);
    }
    recordings = IntakeFixture.recordings();
  }

  @AfterAll
  void stop() {
    app.close();
    wiremock.stop();
  }

  private String token(String sourceKey, String suffix) {
    String kind = sourceKey.equals(IntakeFixture.ZABBIX) ? "zabbix" : "alertmanager";
    return secrets.value("webhook-" + kind + suffix);
  }

  private ApiClient.Request webhook(String host, String sourceKey, byte[] body) {
    return client
        .post("/api/v1/webhooks/" + sourceKey)
        .host(host)
        .header("Content-Type", "application/json")
        .body(body);
  }

  private Response post(String host, String suffix, Recording recording) {
    return webhook(host, recording.sourceKey(), recording.bytes())
        .header("Content-Type", recording.contentType())
        .header("Authorization", "Bearer " + token(recording.sourceKey(), suffix))
        .send();
  }

  private Response postBody(String host, String suffix, String sourceKey, byte[] body) {
    return webhook(host, sourceKey, body)
        .header("Authorization", "Bearer " + token(sourceKey, suffix))
        .send();
  }

  private long calls(TenantId tenant) {
    return postgres
        .sql("SELECT count(*) FROM intake.webhook_call WHERE tenant_id = :tenantId")
        .param("tenantId", tenant.value())
        .query(Long.class)
        .single();
  }

  private UUID lastCall(TenantId tenant) {
    return postgres
        .sql(
            "SELECT id FROM intake.webhook_call WHERE tenant_id = :tenantId"
                + " ORDER BY id DESC LIMIT 1")
        .param("tenantId", tenant.value())
        .query(UUID.class)
        .single();
  }

  private Timestamp lastContact(TenantId tenant, String sourceKey) {
    return postgres
        .sql(
            "SELECT last_contact_at FROM intake.monitoring_source WHERE tenant_id = :tenantId"
                + " AND source_key = :key")
        .param("tenantId", tenant.value())
        .param("key", sourceKey)
        .query(Timestamp.class)
        .single();
  }

  private Map<String, Object> callRow(UUID id) {
    return postgres
        .sql("SELECT * FROM intake.webhook_call WHERE id = :id")
        .param("id", id)
        .query()
        .singleRow();
  }

  private List<Map<String, Object>> raws(UUID callId) {
    return postgres
        .sql("SELECT * FROM intake.raw_alert WHERE webhook_call_id = :id ORDER BY entry_index")
        .param("id", callId)
        .query()
        .listOfRows();
  }

  private List<Map<String, Object>> normalised(UUID callId) {
    return postgres
        .sql(
            "SELECT n.*, n.identity_pairs::text AS pairs FROM intake.normalised_alert n"
                + " JOIN intake.raw_alert r ON r.id = n.raw_alert_id"
                + " WHERE r.webhook_call_id = :id ORDER BY r.entry_index")
        .param("id", callId)
        .query()
        .listOfRows();
  }

  private UUID sourceId(TenantId tenant, String sourceKey) {
    return intake.sources(tenant).stream()
        .filter(s -> s.sourceKey().equals(sourceKey))
        .map(SourceView::id)
        .findFirst()
        .orElseThrow();
  }

  private Recording firstOf(String sourceKey, String status) {
    return recordings.stream()
        .filter(r -> r.sourceKey().equals(sourceKey))
        .filter(
            r ->
                sourceKey.equals(IntakeFixture.ZABBIX)
                    ? r.tree()
                        .get("event_value")
                        .stringValue()
                        .equals(status.equals("firing") ? "1" : "0")
                    : r.tree().get("alerts").get(0).get("status").stringValue().equals(status))
        .findFirst()
        .orElseThrow();
  }

  /** An Alertmanager message with the one alert {@code alert}. */
  private byte[] alertmanagerMessage(ObjectNode alert) {
    ObjectNode message = (ObjectNode) firstOf(IntakeFixture.ALERTMANAGER, "firing").tree();
    ArrayNode alerts = message.putArray("alerts");
    alerts.add(alert);
    return IntakeFixture.JSON.writeValueAsBytes(message);
  }

  private ObjectNode alertmanagerAlert() {
    return (ObjectNode) firstOf(IntakeFixture.ALERTMANAGER, "firing").tree().get("alerts").get(0);
  }

  @Test
  @Order(1)
  @DisplayName("AR-I-90 POST /api/v1/webhooks/{source key} answers 200 with the number of entries")
  void webhookOperation() {
    Response answer = post(HOST_A, "", IntakeFixture.recording(FIRST));
    assertThat(answer.status()).as(answer.text()).isEqualTo(200);
    assertThat(answer.json()).isEqualTo(IntakeFixture.JSON.readTree("{\"entries\":1}"));
    firstCallId = lastCall(tenantA);
    Response get = client.get("/api/v1/webhooks/" + IntakeFixture.ZABBIX).host(HOST_A).send();
    assertThat(get.status()).isEqualTo(404);
    assertThat(get.json().get("error").get("code").stringValue()).isEqualTo("not_found");
  }

  private void refusal(String name, String host, String sourceKey, String authorization) {
    long before = calls(tenantA);
    Timestamp contact = lastContact(tenantA, IntakeFixture.ZABBIX);
    ApiClient.Request request = webhook(host, sourceKey, IntakeFixture.recording(FIRST).bytes());
    if (authorization != null) {
      request.header("Authorization", authorization);
    }
    refusals.put(name, request.send());
    unchanged.put(
        name,
        calls(tenantA) == before && contact.equals(lastContact(tenantA, IntakeFixture.ZABBIX)));
  }

  @Test
  @Order(2)
  @DisplayName("PM-M06-01 webhook calls are accepted only with the source's own token")
  void onlyTheSourcesToken() {
    String zabbix = IntakeFixture.ZABBIX;
    refusal("no header", HOST_A, zabbix, null);
    refusal("basic", HOST_A, zabbix, "Basic " + token(zabbix, ""));
    refusal(
        "other source's token", HOST_A, zabbix, "Bearer " + token(IntakeFixture.ALERTMANAGER, ""));
    refusal("wrong token", HOST_A, zabbix, "Bearer " + TestSecrets.randomToken());
    refusal("unknown source", HOST_A, "nope", "Bearer " + token(zabbix, ""));
    postgres
        .sql(
            "UPDATE intake.monitoring_source SET active = false WHERE tenant_id = :t AND source_key = :k")
        .param("t", tenantA.value())
        .param("k", zabbix)
        .update();
    try {
      refusal("inactive source", HOST_A, zabbix, "Bearer " + token(zabbix, ""));
    } finally {
      postgres
          .sql(
              "UPDATE intake.monitoring_source SET active = true WHERE tenant_id = :t AND source_key = :k")
          .param("t", tenantA.value())
          .param("k", zabbix)
          .update();
    }
    refusal("A's token on B", HOST_B, zabbix, "Bearer " + token(zabbix, ""));
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("no header", "401 not_signed_in");
    expected.put("basic", "401 not_signed_in");
    expected.put("other source's token", "401 bad_token");
    expected.put("wrong token", "401 bad_token");
    expected.put("unknown source", "404 not_found");
    expected.put("inactive source", "404 not_found");
    expected.put("A's token on B", "401 bad_token");
    Map<String, String> actual = new LinkedHashMap<>();
    refusals.forEach(
        (name, response) ->
            actual.put(
                name,
                response.status() + " " + response.json().get("error").get("code").stringValue()));
    assertThat(actual).isEqualTo(expected);
    assertThat(calls(tenantB)).isZero();
  }

  @Test
  @Order(3)
  @DisplayName("DM-E18-R1 a call with a missing or wrong token is refused and not stored")
  void refusedCallsAreNotStored() {
    assertThat(unchanged).hasSize(7).allSatisfy((name, same) -> assertThat(same).as(name).isTrue());
    assertThat(refusals.get("no header").status()).isEqualTo(401);
    assertThat(refusals.get("wrong token").status()).isEqualTo(401);
  }

  @Test
  @Order(4)
  @DisplayName("AR-S-05 the webhook token is checked per source with Secrets.verify")
  void tokenPerSource() {
    for (String name : List.of("other source's token", "wrong token", "A's token on B")) {
      assertThat(refusals.get(name).json().get("error").get("code").stringValue())
          .as(name)
          .isEqualTo("bad_token");
    }
  }

  @Test
  @Order(5)
  @DisplayName("AR-TM-02 the body limit is 5 MiB, with or without Content-Length")
  void bodyLimit() throws IOException, InterruptedException {
    byte[] atLimit = new byte[LIMIT];
    java.util.Arrays.fill(atLimit, (byte) 'x');
    Response accepted = postBody(HOST_A, "", IntakeFixture.ZABBIX, atLimit);
    assertThat(accepted.status()).as(accepted.text()).isEqualTo(200);
    assertThat(accepted.json()).isEqualTo(IntakeFixture.JSON.readTree("{\"entries\":0}"));
    Map<String, Object> stored = callRow(lastCall(tenantA));
    assertThat(stored.get("parse_outcome")).isEqualTo("failed");
    assertThat((byte[]) stored.get("body")).isEqualTo(atLimit);
    assertThat(raws(lastCall(tenantA))).isEmpty();

    long before = calls(tenantA);
    byte[] over = new byte[LIMIT + 1];
    Response tooLarge = postBody(HOST_A, "", IntakeFixture.ZABBIX, over);
    assertThat(tooLarge.status()).isEqualTo(413);
    assertThat(tooLarge.json().get("error").get("code").stringValue()).isEqualTo("too_large");

    HttpClient http =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();
    HttpRequest chunked =
        HttpRequest.newBuilder(
                URI.create(app.baseUrl() + "/api/v1/webhooks/" + IntakeFixture.ZABBIX))
            .header("Host", HOST_A)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token(IntakeFixture.ZABBIX, ""))
            .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(over)))
            .build();
    HttpResponse<String> chunkedAnswer = http.send(chunked, HttpResponse.BodyHandlers.ofString());
    assertThat(chunkedAnswer.statusCode()).isEqualTo(413);
    assertThat(chunkedAnswer.body()).contains("\"too_large\"");
    assertThat(calls(tenantA)).isEqualTo(before);
  }

  @Test
  @Order(6)
  @DisplayName("DM-E-18 the webhook call: body as received, content type, receive time, source")
  void webhookCall() {
    callTime = CLOCK.advance(Duration.ofSeconds(1));
    zabbixRecording = firstOf(IntakeFixture.ZABBIX, "firing");
    alertmanagerRecording = firstOf(IntakeFixture.ALERTMANAGER, "firing");
    assertThat(post(HOST_A, "", zabbixRecording).status()).isEqualTo(200);
    zabbixCallId = lastCall(tenantA);
    assertThat(post(HOST_A, "", alertmanagerRecording).status()).isEqualTo(200);
    alertmanagerCallId = lastCall(tenantA);
    for (Map.Entry<UUID, Recording> call :
        Map.of(zabbixCallId, zabbixRecording, alertmanagerCallId, alertmanagerRecording)
            .entrySet()) {
      Map<String, Object> row = callRow(call.getKey());
      assertThat((byte[]) row.get("body")).isEqualTo(call.getValue().bytes());
      assertThat(row.get("content_type")).isEqualTo(call.getValue().contentType());
      assertThat(((Timestamp) row.get("received_at")).toInstant()).isEqualTo(callTime);
      assertThat(row.get("source_id")).isEqualTo(sourceId(tenantA, call.getValue().sourceKey()));
      assertThat(row.get("entry_count")).isEqualTo(1L);
      assertThat(row.get("parse_outcome")).isEqualTo("parsed");
    }
  }

  @Test
  @Order(7)
  @DisplayName("DM-E-19 each raw alert holds its entry exactly, with the call's receive time")
  void rawAlerts() {
    Map<String, Object> zabbix = raws(zabbixCallId).getFirst();
    assertThat(zabbix.get("payload")).isEqualTo(zabbixRecording.body());
    assertThat(zabbix.get("entry_index")).isEqualTo(0L);
    assertThat(((Timestamp) zabbix.get("received_at")).toInstant()).isEqualTo(callTime);
    Map<String, Object> alertmanager = raws(alertmanagerCallId).getFirst();
    String payload = (String) alertmanager.get("payload");
    assertThat(alertmanagerRecording.body()).contains(payload);
    assertThat(IntakeFixture.JSON.readTree(payload))
        .isEqualTo(alertmanagerRecording.tree().get("alerts").get(0));
    assertThat(payload).startsWith("{").endsWith("}");
    assertThat(((Timestamp) alertmanager.get("received_at")).toInstant()).isEqualTo(callTime);
  }

  @Test
  @Order(8)
  @DisplayName(
      "SL-B-02 raw entries and bodies are stored exactly as received, with source and time")
  void storedAsReceived() {
    for (UUID call : List.of(zabbixCallId, alertmanagerCallId)) {
      Map<String, Object> row = callRow(call);
      Map<String, Object> raw = raws(call).getFirst();
      assertThat(raw.get("source_id")).isEqualTo(row.get("source_id"));
      assertThat(raw.get("received_at")).isEqualTo(row.get("received_at"));
      assertThat(new String((byte[]) row.get("body"), StandardCharsets.UTF_8))
          .contains((String) raw.get("payload"));
    }
  }

  @Test
  @Order(9)
  @DisplayName("PM-M06-02 a call with several alerts is split into one entry per alert")
  void severalAlertsInOneCall() {
    List<Recording> firing =
        recordings.stream()
            .filter(r -> r.sourceKey().equals(IntakeFixture.ALERTMANAGER))
            .filter(r -> r.tree().get("alerts").get(0).get("status").stringValue().equals("firing"))
            .limit(3)
            .toList();
    ObjectNode message = (ObjectNode) firing.getFirst().tree();
    ArrayNode alerts = message.putArray("alerts");
    threeFingerprints = new ArrayList<>();
    for (Recording recording : firing.reversed()) {
      JsonNode alert = recording.tree().get("alerts").get(0);
      alerts.add(alert);
      threeFingerprints.add(alert.get("fingerprint").stringValue());
    }
    assertThat(threeFingerprints).doesNotHaveDuplicates();
    Response answer =
        postBody(
            HOST_B,
            "-b",
            IntakeFixture.ALERTMANAGER,
            IntakeFixture.JSON.writeValueAsBytes(message));
    assertThat(answer.json()).isEqualTo(IntakeFixture.JSON.readTree("{\"entries\":3}"));
    threeEntryCallId = lastCall(tenantB);
    assertThat(raws(threeEntryCallId)).hasSize(3);
    List<Long> sequences = new ArrayList<>();
    for (String fingerprint : threeFingerprints) {
      sequences.add(
          postgres
              .sql("SELECT sequence FROM intake.alert WHERE tenant_id = :t AND alert_key = :k")
              .param("t", tenantB.value())
              .param("k", fingerprint)
              .query(Long.class)
              .single());
    }
    assertThat(sequences).containsExactly(1L, 2L, 3L);

    twoCallIds = new ArrayList<>();
    for (String name : List.of("0001-zabbix-lab.json", "0002-zabbix-lab.json")) {
      CLOCK.advance(Duration.ofSeconds(1));
      assertThat(post(HOST_B, "-b", IntakeFixture.recording(name)).status()).isEqualTo(200);
      twoCallIds.add(lastCall(tenantB));
    }
  }

  @Test
  @Order(10)
  @DisplayName("PM-M06-03 every call and entry is stored unchanged with source and receive time")
  void storedUnchanged() {
    Map<String, Object> first = callRow(firstCallId);
    assertThat((byte[]) first.get("body")).isEqualTo(IntakeFixture.recording(FIRST).bytes());
    assertThat(raws(firstCallId).getFirst().get("payload"))
        .isEqualTo(IntakeFixture.recording(FIRST).body());
    for (UUID call : List.of(firstCallId, zabbixCallId, alertmanagerCallId, threeEntryCallId)) {
      Map<String, Object> row = callRow(call);
      assertThat(row.get("source_id")).isNotNull();
      assertThat(raws(call))
          .allSatisfy(
              raw -> {
                assertThat(raw.get("received_at")).isEqualTo(row.get("received_at"));
                assertThat(raw.get("source_id")).isEqualTo(row.get("source_id"));
              });
    }
  }

  @Test
  @Order(11)
  @DisplayName("DM-R-03 entries in entry_index order; calls of one source in received_at order")
  void order() {
    List<Map<String, Object>> raws = raws(threeEntryCallId);
    for (int i = 0; i < 3; i++) {
      assertThat(raws.get(i).get("entry_index")).isEqualTo((long) i);
      assertThat(
              IntakeFixture.JSON
                  .readTree((String) raws.get(i).get("payload"))
                  .get("fingerprint")
                  .stringValue())
          .isEqualTo(threeFingerprints.get(i));
    }
    Instant first = ((Timestamp) callRow(twoCallIds.get(0)).get("received_at")).toInstant();
    Instant second = ((Timestamp) callRow(twoCallIds.get(1)).get("received_at")).toInstant();
    assertThat(second).isAfter(first);
  }

  @Test
  @Order(12)
  @DisplayName("DM-E-20 the normalised alert, stored apart from the raw alert")
  void normalisedAlert() {
    Map<String, Object> row = normalised(firstCallId).getFirst();
    assertThat(row.get("outcome")).isEqualTo("normalised");
    assertThat(row.get("alert_key")).isEqualTo("36");
    assertThat(row.get("reported_status")).isEqualTo("firing");
    assertThat(row.get("alert_type")).isEqualTo("db_write_errors");
    assertThat(IntakeFixture.JSON.readTree((String) row.get("pairs")))
        .isEqualTo(
            IntakeFixture.JSON.readTree(
                "[{\"name\":\"component\",\"value\":\"db-subscriber\"},"
                    + "{\"name\":\"host\",\"value\":\"lab-db-01\"}]"));
    assertThat(row.get("title")).isEqualTo("Database write errors on schema bundle_catalog");
    assertThat(((Timestamp) row.get("source_time")).toInstant())
        .isEqualTo(Instant.parse("2026-10-15T19:00:43Z"));
    assertThat(row.get("source_severity")).isEqualTo("Not classified");
    assertThat(row.get("application")).isEqualTo("created_alert");
    assertThat(row.get("applied_to_alert_id")).isNotNull();

    long alerts = alerts(tenantA);
    ObjectNode alert = alertmanagerAlert();
    alert.remove("fingerprint");
    assertThat(
            postBody(HOST_A, "", IntakeFixture.ALERTMANAGER, alertmanagerMessage(alert)).status())
        .isEqualTo(200);
    Map<String, Object> failed = normalised(lastCall(tenantA)).getFirst();
    assertThat(failed.get("outcome")).isEqualTo("failed");
    assertThat(failed.get("failure_reason")).isEqualTo("no fingerprint");
    assertThat(failed.get("application")).isEqualTo("not_applied");
    assertThat(failed.get("alert_key")).isNull();
    assertThat(failed.get("applied_to_alert_id")).isNull();
    assertThat(alerts(tenantA)).isEqualTo(alerts);
  }

  private long alerts(TenantId tenant) {
    return postgres
        .sql("SELECT count(*) FROM intake.alert WHERE tenant_id = :t")
        .param("t", tenant.value())
        .query(Long.class)
        .single();
  }

  @Test
  @Order(13)
  @DisplayName(
      "DM-C-11 over-long texts are cut to the field limit; a long alert key fails the entry")
  void sizes() {
    ObjectNode alert = alertmanagerAlert();
    alert.put("fingerprint", "cut-" + UUID.randomUUID());
    ((ObjectNode) alert.get("annotations")).put("summary", "s".repeat(301));
    ((ObjectNode) alert.get("labels")).put("severity", "v".repeat(51));
    ((ObjectNode) alert.get("labels")).put("instance", "i".repeat(201));
    String contentType = "application/json; charset=utf-8; x=" + "p".repeat(66);
    assertThat(contentType).hasSize(101);
    Response answer =
        webhook(HOST_A, IntakeFixture.ALERTMANAGER, alertmanagerMessage(alert))
            .header("Content-Type", contentType)
            .header("Authorization", "Bearer " + token(IntakeFixture.ALERTMANAGER, ""))
            .send();
    assertThat(answer.status()).isEqualTo(200);
    UUID call = lastCall(tenantA);
    assertThat(callRow(call).get("content_type")).isEqualTo(contentType.substring(0, 100));
    Map<String, Object> row = normalised(call).getFirst();
    assertThat(row.get("outcome")).isEqualTo("normalised");
    assertThat(row.get("title")).isEqualTo("s".repeat(300));
    assertThat(row.get("source_severity")).isEqualTo("v".repeat(50));
    String instance = "i".repeat(200);
    assertThat(values((String) row.get("pairs"))).contains(instance);
    String alertPairs =
        postgres
            .sql("SELECT identity_pairs::text FROM intake.alert WHERE id = :id")
            .param("id", row.get("applied_to_alert_id"))
            .query(String.class)
            .single();
    assertThat(values(alertPairs)).contains(instance);

    ObjectNode longKey = alertmanagerAlert();
    longKey.put("fingerprint", "k".repeat(201));
    assertThat(
            postBody(HOST_A, "", IntakeFixture.ALERTMANAGER, alertmanagerMessage(longKey)).status())
        .isEqualTo(200);
    Map<String, Object> failed = normalised(lastCall(tenantA)).getFirst();
    assertThat(failed.get("outcome")).isEqualTo("failed");
    assertThat(failed.get("failure_reason"))
        .isEqualTo("the alert key is longer than 200 characters");
    assertThat(failed.get("application")).isEqualTo("not_applied");
  }

  private static List<String> values(String pairs) {
    List<String> values = new ArrayList<>();
    IntakeFixture.JSON.readTree(pairs).forEach(pair -> values.add(pair.get("value").stringValue()));
    return values;
  }

  @Test
  @Order(14)
  @DisplayName("PM-I-06 entries are parsed and sources tested through vigil-connectors")
  void throughConnectors() {
    assertThat(normalised(firstCallId).getFirst().get("alert_key")).isEqualTo("36");
    assertThat(intake.sources(tenantA))
        .allSatisfy(source -> assertThat(source.lastTestOutcome()).isEqualTo(TestOutcome.PASSED));
  }

  @Test
  @Order(15)
  @DisplayName(
      "SL-A1-01 the recorded storm's calls become its 126 storm alerts and the SL-F-3 alert")
  void recordedStorm() {
    for (Recording recording : recordings) {
      Response answer = post(HOST_C, "-c", recording);
      assertThat(answer.status()).as(recording.name() + " " + answer.text()).isEqualTo(200);
    }
    assertThat(calls(tenantC)).isEqualTo(254);
    assertThat(
            count(
                "SELECT count(*) FROM intake.webhook_call c JOIN intake.monitoring_source s"
                    + " ON s.id = c.source_id WHERE c.tenant_id = :t AND s.source_kind = 'zabbix'"))
        .isEqualTo(64);
    assertThat(count("SELECT count(*) FROM intake.raw_alert WHERE tenant_id = :t")).isEqualTo(254);
    assertThat(count("SELECT count(*) FROM intake.normalised_alert WHERE tenant_id = :t"))
        .isEqualTo(254);
    assertThat(
            count(
                "SELECT count(*) FROM intake.normalised_alert WHERE tenant_id = :t"
                    + " AND outcome = 'failed'"))
        .isZero();
    assertThat(
            postgres
                .sql("SELECT sequence FROM intake.alert WHERE tenant_id = :t ORDER BY sequence")
                .param("t", tenantC.value())
                .query(Long.class)
                .list())
        .isEqualTo(java.util.stream.LongStream.rangeClosed(1, 127).boxed().toList());
    assertThat(
            count(
                "SELECT count(*) FROM intake.alert WHERE tenant_id = :t AND source_kind = 'zabbix'"))
        .isEqualTo(32);
    assertThat(
            count(
                "SELECT count(*) FROM intake.alert WHERE tenant_id = :t"
                    + " AND alert_type <> 'report_job_failed'"))
        .isEqualTo(126);
    assertThat(
            count(
                "SELECT count(*) FROM intake.alert WHERE tenant_id = :t"
                    + " AND alert_type = 'report_job_failed'"))
        .isEqualTo(1);
  }

  private long count(String sql) {
    return postgres.sql(sql).param("t", tenantC.value()).query(Long.class).single();
  }

  @Test
  @Order(16)
  @DisplayName("DM-R-01 alerts are counted as firing episodes, apart from the entries")
  void alertsAreEpisodes() {
    assertThat(count("SELECT count(*) FROM intake.raw_alert WHERE tenant_id = :t")).isEqualTo(254);
    assertThat(count("SELECT count(*) FROM intake.alert WHERE tenant_id = :t")).isEqualTo(127);
    assertThat(
            count(
                "SELECT count(*) FROM intake.normalised_alert WHERE tenant_id = :t"
                    + " AND reported_status = 'firing'"))
        .isEqualTo(127);
    assertThat(
            count(
                "SELECT count(*) FROM intake.normalised_alert WHERE tenant_id = :t"
                    + " AND reported_status = 'resolved'"))
        .isEqualTo(127);
    assertThat(
            count(
                "SELECT count(*) FROM intake.alert WHERE tenant_id = :t"
                    + " AND (status <> 'resolved' OR entry_count <> 2)"))
        .isZero();
  }

  @Test
  @Order(17)
  @DisplayName(
      "DM-R-02 the recorded storm has no repeated firing entry: each firing entry is a new alert")
  void noRepeatedFiring() {
    assertThat(
            count(
                "SELECT count(*) FROM intake.normalised_alert WHERE tenant_id = :t"
                    + " AND application IN ('repeat_firing', 'orphan_resolved')"))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM intake.normalised_alert WHERE tenant_id = :t"
                    + " AND application = 'created_alert'"))
        .isEqualTo(127);
  }

  @Test
  @Order(18)
  @DisplayName("PM-M06-04 normalisation through the connector and the alert type mappings")
  void alertTypeCounts() {
    List<Map<String, Object>> rows =
        postgres
            .sql(
                "SELECT s.source_kind || ' ' || n.alert_type AS type, count(*) AS n"
                    + " FROM intake.normalised_alert n JOIN intake.raw_alert r ON r.id = n.raw_alert_id"
                    + " JOIN intake.monitoring_source s ON s.id = r.source_id"
                    + " WHERE n.tenant_id = :t GROUP BY 1")
            .param("t", tenantC.value())
            .query()
            .listOfRows();
    Map<String, Long> counts = new LinkedHashMap<>();
    rows.forEach(row -> counts.put((String) row.get("type"), (Long) row.get("n")));
    assertThat(counts)
        .isEqualTo(
            Map.of(
                "zabbix db_write_errors", 12L,
                "zabbix disk_full", 2L,
                "zabbix report_job_failed", 2L,
                "zabbix sms_queue_growth", 48L,
                "alertmanager balance_api_errors", 32L,
                "alertmanager bundle_api_errors", 32L,
                "alertmanager ussd_timeouts", 24L,
                "alertmanager disk_full", 2L,
                "alertmanager ussd_synthetic_failed", 100L));
  }

  @Test
  @Order(19)
  @DisplayName("AR-R-25 each call is answered after its transaction committed")
  void answeredAfterCommit() {
    for (int i = 0; i < 5; i++) {
      long before = calls(tenantA);
      long rawsBefore =
          postgres
              .sql("SELECT count(*) FROM intake.raw_alert WHERE tenant_id = :t")
              .param("t", tenantA.value())
              .query(Long.class)
              .single();
      assertThat(post(HOST_A, "", recordings.get(i)).status()).isEqualTo(200);
      assertThat(calls(tenantA)).isEqualTo(before + 1);
      assertThat(
              postgres
                  .sql("SELECT count(*) FROM intake.raw_alert WHERE tenant_id = :t")
                  .param("t", tenantA.value())
                  .query(Long.class)
                  .single())
          .isEqualTo(rawsBefore + 1);
    }
  }

  @Test
  @Order(20)
  @DisplayName("AR-S-11 no token, Authorization header or body in a log line")
  void nothingSecretLogged(CapturedOutput output) {
    Recording recording = IntakeFixture.recording("0003-zabbix-lab.json");
    assertThat(post(HOST_A, "", recording).status()).isEqualTo(200);
    String wrong = TestSecrets.randomToken();
    Response refused =
        webhook(HOST_A, recording.sourceKey(), recording.bytes())
            .header("Authorization", "Bearer " + wrong)
            .send();
    assertThat(refused.status()).isEqualTo(401);
    String marker = UUID.randomUUID().toString();
    Response unread =
        postBody(
            HOST_A,
            "",
            IntakeFixture.ZABBIX,
            ("not json " + marker).getBytes(StandardCharsets.UTF_8));
    assertThat(unread.json()).isEqualTo(IntakeFixture.JSON.readTree("{\"entries\":0}"));
    assertThat(LogLines.application(output.getAll()))
        .anySatisfy(
            line -> {
              assertThat(line.path("log").path("level").asString()).isEqualTo("WARN");
              assertThat(line.path("message").asString())
                  .contains("webhook body not read")
                  .contains(tenantA.value().toString())
                  .contains(IntakeFixture.ZABBIX);
            });
    LogLines.assertNoneContains(
        output.getAll(),
        List.of(token(IntakeFixture.ZABBIX, ""), wrong, "Bearer", recording.body(), marker));
  }
}
