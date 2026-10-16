package vigil.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import vigil.audit.api.AuditActorFilter;
import vigil.audit.api.AuditEntry;
import vigil.audit.api.AuditEventType;
import vigil.audit.api.AuditEventView;
import vigil.audit.api.AuditPage;
import vigil.audit.api.AuditQuery;
import vigil.audit.api.AuditRecorded;
import vigil.audit.api.AuditService;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.testsupport.VigilDatabase;
import vigil.testsupport.VigilTestApp;

class AuditIT {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Instant BASE = Instant.parse("2026-10-16T08:00:00Z");

  private static VigilDatabase database;
  private static VigilTestApp app;
  private static AuditService audit;
  private static JdbcClient appJdbc;
  private static TransactionTemplate tx;

  @BeforeAll
  static void start() {
    database = VigilDatabase.fresh();
    app = VigilTestApp.start(database);
    app.awaitStarted();
    audit = app.context().getBean(AuditService.class);
    appJdbc = app.context().getBean(JdbcClient.class);
    tx = new TransactionTemplate(app.context().getBean(PlatformTransactionManager.class));
  }

  @AfterAll
  static void stop() {
    app.close();
  }

  private static TenantId tenant() {
    return new TenantId(Ids.newId());
  }

  private static AuditEntry entry(TenantId tenant, String summary) {
    return new AuditEntry(
        tenant,
        BASE,
        Actor.module("vigil-incidents"),
        AuditEventType.INCIDENT_CREATED,
        "Incident",
        Ids.newId(),
        null,
        summary,
        null);
  }

  private static AuditPage query(TenantId tenant, AuditQuery query) {
    return audit.query(tenant, query);
  }

  private static AuditQuery all(int limit) {
    return new AuditQuery(null, null, null, null, null, null, null, limit, null);
  }

  @Test
  @DisplayName(
      "PM-M03-01 an appended event is stored with every field; occurred_at in milliseconds")
  void appendStoresEveryField() throws Exception {
    TenantId tenant = tenant();
    UUID user = Ids.newId();
    UUID subject = Ids.newId();
    UUID incident = Ids.newId();
    Instant occurred = BASE.plusNanos(123_456_789);
    AuditRecorded recorded =
        audit.append(
            new AuditEntry(
                tenant,
                occurred,
                Actor.user(user),
                AuditEventType.APPROVAL_DECIDED,
                "ActionProposal",
                subject,
                incident,
                "Approved by DB Ops",
                "{\"decision\": \"approved\", \"user\": \"" + user + "\"}"));
    AuditEventView view = query(tenant, all(50)).items().getFirst();
    assertThat(view.id()).isEqualTo(recorded.id());
    assertThat(view.sequence()).isEqualTo(recorded.sequence());
    assertThat(view.occurredAt()).isEqualTo(occurred.truncatedTo(ChronoUnit.MILLIS));
    assertThat(view.actor()).isEqualTo(Actor.user(user));
    assertThat(view.eventType()).isEqualTo(AuditEventType.APPROVAL_DECIDED);
    assertThat(view.subjectType()).isEqualTo("ActionProposal");
    assertThat(view.subjectId()).isEqualTo(subject);
    assertThat(view.incidentId()).isEqualTo(incident);
    assertThat(view.summary()).isEqualTo("Approved by DB Ops");
    assertThat(JSON.readTree(view.detailsJson()))
        .isEqualTo(JSON.readTree("{\"user\": \"" + user + "\", \"decision\": \"approved\"}"));
    Map<String, Object> row =
        database
            .postgres()
            .sql("SELECT tenant_id, created_at FROM audit.audit_event WHERE id = ?")
            .param(recorded.id())
            .query()
            .singleRow();
    assertThat(row.get("tenant_id")).isEqualTo(tenant.value());
    assertThat(row.get("created_at")).isNotNull();
  }

  @Test
  @DisplayName("DM-E-08 sequences are distinct and increase per tenant, also under concurrency")
  void sequencePerTenant() throws Exception {
    TenantId tenant = tenant();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try {
      List<Future<List<Long>>> threads = new ArrayList<>();
      for (int t = 0; t < 8; t++) {
        threads.add(
            pool.submit(
                () -> {
                  List<Long> sequences = new ArrayList<>();
                  for (int i = 0; i < 25; i++) {
                    sequences.add(audit.append(entry(tenant, "event " + i)).sequence());
                  }
                  return sequences;
                }));
      }
      List<Long> all = new ArrayList<>();
      for (Future<List<Long>> thread : threads) {
        List<Long> sequences = thread.get();
        assertThat(sequences).isSorted().doesNotHaveDuplicates();
        all.addAll(sequences);
      }
      assertThat(all).hasSize(200).doesNotHaveDuplicates();
    } finally {
      pool.shutdownNow();
    }
    TenantId other = tenant();
    audit.append(entry(other, "other tenant"));
    assertThat(query(other, all(50)).total()).isEqualTo(1);
    assertThat(query(tenant, all(50)).total()).isEqualTo(200);
  }

  @Test
  @DisplayName("SL-B-06 the runtime role cannot update or delete audit events")
  void appendOnly() {
    TenantId tenant = tenant();
    AuditRecorded recorded = audit.append(entry(tenant, "kept"));
    assertThatThrownBy(
            () ->
                appJdbc
                    .sql("UPDATE audit.audit_event SET summary = 'changed' WHERE id = ?")
                    .param(recorded.id())
                    .update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThatThrownBy(
            () ->
                appJdbc
                    .sql("DELETE FROM audit.audit_event WHERE id = ?")
                    .param(recorded.id())
                    .update())
        .isInstanceOf(DataAccessException.class)
        .rootCause()
        .hasMessageContaining("permission denied");
    assertThat(query(tenant, all(50)).items())
        .extracting(AuditEventView::summary)
        .containsExactly("kept");
  }

  @Test
  @DisplayName(
      "AR-R-11 append joins the caller's transaction: rolled back with it, committed with it")
  void appendJoinsTheTransaction() {
    TenantId tenant = tenant();
    tx.executeWithoutResult(
        status -> {
          audit.append(entry(tenant, "rolled back"));
          status.setRollbackOnly();
        });
    assertThat(query(tenant, all(50)).total()).isZero();
    tx.executeWithoutResult(status -> audit.append(entry(tenant, "committed")));
    assertThat(query(tenant, all(50)).items())
        .extracting(AuditEventView::summary)
        .containsExactly("committed");
  }

  /** One appended event of the query test with the values its filters read. */
  private record Made(
      UUID id,
      long sequence,
      Instant occurredAt,
      Actor actor,
      AuditEventType type,
      UUID incident,
      UUID subject) {}

  @Test
  @DisplayName(
      "PM-M03-03 every filter alone and together, newest first, paging with next and total")
  void queryFilters() {
    TenantId tenant = tenant();
    UUID user = Ids.newId();
    UUID incidentA = Ids.newId();
    UUID incidentB = Ids.newId();
    UUID subject = Ids.newId();
    List<Made> made = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      Instant occurred = BASE.plusSeconds(60L * (i / 2));
      Actor actor = i % 3 == 0 ? Actor.user(user) : Actor.module("vigil-incidents");
      AuditEventType type =
          i % 2 == 0 ? AuditEventType.INCIDENT_CREATED : AuditEventType.TICKET_CHANGED;
      UUID incident = i % 4 == 0 ? incidentA : (i % 4 == 1 ? incidentB : null);
      UUID subjectId = i % 5 == 0 ? subject : Ids.newId();
      AuditRecorded recorded =
          audit.append(
              new AuditEntry(
                  tenant, occurred, actor, type, "Incident", subjectId, incident, "e" + i, null));
      made.add(
          new Made(recorded.id(), recorded.sequence(), occurred, actor, type, incident, subjectId));
    }
    Instant from = BASE.plusSeconds(60L * 3);
    Instant to = BASE.plusSeconds(60L * 10);
    check(
        tenant,
        made,
        new AuditQuery(from, null, null, null, null, null, null, 50, null),
        m -> !m.occurredAt().isBefore(from));
    check(
        tenant,
        made,
        new AuditQuery(null, to, null, null, null, null, null, 50, null),
        m -> !m.occurredAt().isAfter(to));
    check(
        tenant,
        made,
        new AuditQuery(
            null,
            null,
            new AuditActorFilter(AuditActorFilter.Kind.USER, user),
            null,
            null,
            null,
            null,
            50,
            null),
        m -> m.actor().equals(Actor.user(user)));
    check(
        tenant,
        made,
        new AuditQuery(
            null,
            null,
            new AuditActorFilter(AuditActorFilter.Kind.AUTOMATIC, null),
            null,
            null,
            null,
            null,
            50,
            null),
        m -> m.actor().kind() == Actor.Kind.MODULE);
    check(
        tenant,
        made,
        new AuditQuery(
            null, null, null, Set.of(AuditEventType.TICKET_CHANGED), null, null, null, 50, null),
        m -> m.type() == AuditEventType.TICKET_CHANGED);
    check(
        tenant,
        made,
        new AuditQuery(null, null, null, null, Set.of(incidentA, incidentB), null, null, 50, null),
        m -> m.incident() != null);
    check(
        tenant,
        made,
        new AuditQuery(null, null, null, null, null, "Incident", subject, 50, null),
        m -> m.subject().equals(subject));
    check(
        tenant,
        made,
        new AuditQuery(
            from,
            to,
            new AuditActorFilter(AuditActorFilter.Kind.AUTOMATIC, null),
            Set.of(AuditEventType.INCIDENT_CREATED),
            Set.of(incidentA),
            null,
            null,
            50,
            null),
        m ->
            !m.occurredAt().isBefore(from)
                && !m.occurredAt().isAfter(to)
                && m.actor().kind() == Actor.Kind.MODULE
                && m.type() == AuditEventType.INCIDENT_CREATED
                && incidentA.equals(m.incident()));

    List<UUID> paged = new ArrayList<>();
    AuditQuery page = all(10);
    int pages = 0;
    while (true) {
      AuditPage result = query(tenant, page);
      assertThat(result.total()).isEqualTo(30);
      result.items().forEach(item -> paged.add(item.id()));
      pages++;
      if (result.next() == null) {
        break;
      }
      page = new AuditQuery(null, null, null, null, null, null, null, 10, result.next());
    }
    assertThat(pages).isEqualTo(3);
    assertThat(paged).containsExactlyElementsOf(expected(made, m -> true));

    List<AuditEventView> sameTime =
        query(tenant, new AuditQuery(BASE, BASE, null, null, null, null, null, 50, null)).items();
    assertThat(sameTime).hasSize(2);
    assertThat(sameTime.get(0).sequence()).isGreaterThan(sameTime.get(1).sequence());
  }

  private static void check(
      TenantId tenant, List<Made> made, AuditQuery query, Predicate<Made> filter) {
    AuditPage page = AuditIT.query(tenant, query);
    List<UUID> expected = expected(made, filter);
    assertThat(page.items()).extracting(AuditEventView::id).containsExactlyElementsOf(expected);
    assertThat(page.total()).isEqualTo(expected.size());
    assertThat(page.next()).isNull();
  }

  private static List<UUID> expected(List<Made> made, Predicate<Made> filter) {
    return made.stream()
        .filter(filter)
        .sorted(Comparator.comparing(Made::occurredAt).thenComparingLong(Made::sequence).reversed())
        .map(Made::id)
        .toList();
  }

  @Test
  @DisplayName("DM-C-07 configuration_changed needs details with exactly area, before and after")
  void configurationChanged() {
    TenantId tenant = tenant();
    for (String details :
        new String[] {
          null,
          "{\"area\": \"no_such_area\", \"before\": 1, \"after\": 2}",
          "{\"area\": \"routing_rules\", \"before\": 1, \"after\": 2, \"extra\": 3}",
          "{\"area\": \"routing_rules\", \"before\": 1}",
          "[1, 2]"
        }) {
      assertThatThrownBy(() -> audit.append(configurationChanged(tenant, details)))
          .as("details %s", details)
          .isInstanceOf(IllegalArgumentException.class);
    }
    audit.append(
        configurationChanged(
            tenant,
            "{\"area\": \"routing_rules\", \"before\": {\"revision\": 1}, \"after\": null}"));
    assertThat(query(tenant, all(50)).items()).hasSize(1);
  }

  private static AuditEntry configurationChanged(TenantId tenant, String details) {
    return new AuditEntry(
        tenant,
        BASE,
        Actor.module("vigil-setup"),
        AuditEventType.CONFIGURATION_CHANGED,
        "RoutingRuleSet",
        Ids.newId(),
        null,
        "Routing rules changed",
        details);
  }

  @Test
  @DisplayName("DM-C-10 a query of tenant B returns none of tenant A's events")
  void tenantScope() {
    TenantId tenantA = tenant();
    TenantId tenantB = tenant();
    audit.append(entry(tenantA, "of A"));
    audit.append(entry(tenantB, "of B"));
    assertThat(query(tenantB, all(50)).items())
        .extracting(AuditEventView::summary)
        .containsExactly("of B");
    assertThat(query(tenant(), all(50)).items()).isEmpty();
  }
}
