package vigil.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.mockito.AdditionalAnswers;
import org.mockito.MockMakers;
import org.mockito.Mockito;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.dataformat.yaml.YAMLMapper;
import vigil.actions.ActionsFixture;
import vigil.actions.api.ActionsService;
import vigil.actions.api.ActionsStepHandler;
import vigil.actions.api.ApprovalRequestView;
import vigil.engine.EngineFixture;
import vigil.engine.TestContents;
import vigil.engine.TestHandlers;
import vigil.engine.api.EngineService;
import vigil.identity.api.IdentityService;
import vigil.incidents.api.AlertCopy;
import vigil.incidents.api.GroupingReason;
import vigil.incidents.api.IdentityPairCopy;
import vigil.notifications.NotificationsApp;
import vigil.reporting.api.ReportingImport;
import vigil.reporting.api.ReportingPart;
import vigil.reporting.api.ReportingPart.BaselinePart;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.Key;
import vigil.shared.StepCall;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;
import vigil.testsupport.ApiClient;
import vigil.testsupport.ApiClient.Request;
import vigil.testsupport.ApiClient.Response;
import vigil.testsupport.MutableClock;

/**
 * The API fixture of P-43 spec Section 6.12: the application of {@code engine/ActionsEngineIT} (the
 * real vigil-actions beans serve the engine, the other step types are test handlers) with a test
 * clock that stands still, a default {@link ActionsFixture} tenant, and the helpers of the
 * Response, Approval, Acknowledge, AI investigation and dashboard tests.
 */
public final class ApprovalApiFixture implements AutoCloseable {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final Path SETTINGS = Path.of("../docs/31-demo-data/07-settings.yaml");
  private static final Actor CORRELATION = Actor.module("vigil-correlation");
  private static final DateTimeFormatter OUTBOUND =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

  /** The real vigil-actions beans and the test clock. */
  static class ApprovalApiBeans {

    static volatile MutableClock clock;

    @Bean
    static BeanDefinitionRegistryPostProcessor withRealActions() {
      return registry -> {
        registry.removeBeanDefinition("testActionsHandler");
        registry.removeBeanDefinition("testActionRuns");
      };
    }

    @Bean
    @Primary
    Clock testClock() {
      return clock;
    }

    /**
     * Wraps the {@link IdentityService} bean so that, while {@link Membership} is armed for a user,
     * {@code isMember} for that user gives the real answer at its first call and false at later
     * calls (P-30 spec Section 6.9 item 3); otherwise every call goes to the bean.
     */
    @Bean
    static BeanPostProcessor membershipWrapper() {
      return new BeanPostProcessor() {
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
          if (!(bean instanceof IdentityService identity)
              || Mockito.mockingDetails(bean).isMock()) {
            return bean;
          }
          IdentityService wrapper =
              Mockito.mock(
                  IdentityService.class,
                  Mockito.withSettings()
                      .mockMaker(MockMakers.PROXY)
                      .defaultAnswer(AdditionalAnswers.delegatesTo(identity)));
          Mockito.doAnswer(
                  call ->
                      Membership.isMember(
                          identity,
                          call.getArgument(0, TenantId.class),
                          call.getArgument(1, UUID.class),
                          call.getArgument(2, Key.class)))
              .when(wrapper)
              .isMember(Mockito.any(), Mockito.any(), Mockito.any());
          return wrapper;
        }
      };
    }
  }

  /**
   * The membership reads of one armed user: the first answers as the bean does, later ones false.
   */
  public static final class Membership {

    private static volatile UUID armed;
    private static final AtomicInteger CALLS = new AtomicInteger();

    private Membership() {}

    /** Arms the wrapper for {@code user}; {@link #disarm} in {@code finally}. */
    public static void arm(UUID user) {
      CALLS.set(0);
      armed = user;
    }

    public static void disarm() {
      armed = null;
    }

    static boolean isMember(IdentityService identity, TenantId tenant, UUID user, Key team) {
      if (user != null && user.equals(armed) && CALLS.getAndIncrement() > 0) {
        return false;
      }
      return identity.isMember(tenant, user, team);
    }
  }

  /** A run of {@link #responseRun} and its incident. */
  public record ResponseRun(UUID incident, UUID run) {}

  /** A {@code pending} request of {@link #request} and the step call that made it. */
  public record PendingRequest(StepCall call, ApprovalRequestView view) {}

  public final NotificationsApp servers;
  public final MutableClock clock;
  public final ApiClient client;
  public final TestHandlers handlers;
  public final EngineService engine;
  public final ActionsService actions;
  public final ActionsFixture fixture;
  private int tenants;

  public ApprovalApiFixture() {
    clock = new MutableClock(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    ApprovalApiBeans.clock = clock;
    servers = NotificationsApp.start(TestHandlers.Beans.class, ApprovalApiBeans.class);
    client = new ApiClient(servers.app);
    handlers = servers.app.context().getBean(TestHandlers.class);
    engine = servers.app.context().getBean(EngineService.class);
    actions = servers.app.context().getBean(ActionsService.class);
    fixture = tenant("Approval API");
  }

  /** The outbound time format of the refusal texts (DC-U-07 rule 4). */
  public static String outbound(Instant instant) {
    return OUTBOUND.format(instant);
  }

  /** A new {@link ActionsFixture} tenant. */
  public ActionsFixture tenant(String name) {
    tenants++;
    return ActionsFixture.create(servers, name + " " + tenants);
  }

  @Override
  public void close() {
    servers.close();
  }

  /** The request with a new logged-in session of the tenant's user, as {@code ApiFixture.as}. */
  public Request as(ActionsFixture tenant, String username, Request request) {
    String host = tenant.notifications.host;
    Response login = client.login(host, username, tenant.notifications.password(username));
    assertThat(login.status()).isEqualTo(200);
    String csrf = login.cookieValue("vigil_csrf");
    return request
        .host(host)
        .cookie("vigil_session", login.cookieValue("vigil_session"))
        .cookie("vigil_csrf", csrf)
        .header("X-Vigil-CSRF", csrf);
  }

  /**
   * A live run of an AI task (task SL-T-3), Approval, Action and Recovery check on a new open
   * incident after a {@code match} or {@code no_match} search; the AI task answers {@code
   * aiOutput}. Returns once the run has started.
   */
  public ResponseRun responseRun(ActionsFixture tenant, boolean match, String aiOutput) {
    return responseRun(tenant, match, aiOutput, 1);
  }

  /**
   * As {@link #responseRun(ActionsFixture, boolean, String)} with {@code approvals} Approval steps
   * (1 or 2) one after the other before the Action step (P-30 spec Section 6.9 item 3).
   */
  public ResponseRun responseRun(
      ActionsFixture tenant, boolean match, String aiOutput, int approvals) {
    UUID incident = tenant.openIncident();
    if (match) {
      tenant.match(incident);
    } else {
      tenant.noMatch(incident);
    }
    handlers.answer(StepType.AI_TASK, call -> TestHandlers.completed(aiOutput));
    String content =
        TestContents.with(
            approvals == 2
                ? TestContents.linear("ai_task", "approval", "approval", "action", "recovery_check")
                : TestContents.linear("ai_task", "approval", "action", "recovery_check"),
            node -> {
              ObjectNode step = (ObjectNode) node.get("steps").get(1);
              step.putObject("parameters").put("task", "SL-T-3");
              return node;
            });
    UUID run =
        engine
            .startLiveRun(
                tenant.tenant, EngineFixture.WORKFLOWS, incident, 1, Ids.newId(), content, false)
            .orElseThrow();
    return new ResponseRun(incident, run);
  }

  /**
   * A {@code pending} request with no engine run and no timer: the search's match, then the real
   * Approval step's {@code start}, as {@code actions/DecisionIT} makes it.
   */
  public PendingRequest request(ActionsFixture tenant, UUID incident) {
    tenant.match(incident);
    StepCall call = tenant.approval(Ids.newId(), incident, ActionsFixture.APPROVAL, List.of());
    servers.app.context().getBean(ActionsStepHandler.class).start(call);
    ApprovalRequestView view = actions.requests(tenant.tenant, call.runId()).getFirst();
    return new PendingRequest(call, view);
  }

  /** A forming incident with one alert, created as vigil-correlation. */
  public UUID formingIncident(ActionsFixture tenant) {
    return tenant.incidents.create(
        tenant.tenant,
        CORRELATION,
        Ids.newId(),
        new AlertCopy(
            Ids.newId(),
            "zabbix",
            "k-" + UUID.randomUUID(),
            "disk_full",
            "Disk full",
            "Disk full on srv-db-01",
            "srv-db-01",
            new IdentityPairCopy("component", "srv-db-01"),
            clock.instant()),
        new GroupingReason("created_incident", null, null, null, null));
  }

  /** An incident merged into another. */
  public UUID mergedIncident(ActionsFixture tenant) {
    return tenant.notifications.ticketing.investigation.mergedIncident();
  }

  /** {@code admin}'s {@code view} grant set inactive as postgres. */
  public void noView(ActionsFixture tenant) {
    tenant
        .postgres()
        .sql(
            "UPDATE identity.permission_grant SET active = false WHERE tenant_id = :t"
                + " AND user_id = :u AND permission = 'view'")
        .param("t", tenant.tenant.value())
        .param("u", tenant.userId("admin"))
        .update();
  }

  /**
   * An AI investigation result inserted as postgres as {@code InvestigationRecords} writes it: the
   * requester as a user for {@code rerun}, else the module {@code vigil-engine}; accepted or with
   * one rejection reason.
   */
  public UUID result(
      ActionsFixture tenant,
      UUID incident,
      String origin,
      UUID requester,
      boolean accepted,
      List<UUID> evidenceIds,
      Instant createdAt) {
    UUID id = Ids.newId();
    boolean rerun = origin.equals("rerun");
    ArrayNode supplied = JSON.createArrayNode();
    for (String key : List.of("RB-DB-01", "RB-API-01", "RB-USSD-01")) {
      supplied.addObject().put("runbook_key", key).put("version", 1);
    }
    ArrayNode cited = JSON.createArrayNode();
    for (String key : List.of("RB-USSD-01", "RB-DB-01")) {
      cited.addObject().put("runbook_key", key).put("version", 1);
    }
    tenant
        .postgres()
        .sql(
            "INSERT INTO investigation.ai_investigation_result (id, tenant_id, created_at,"
                + " incident_id, origin, run_id, requested_by_kind, requested_by_user_id,"
                + " requested_by_module, request_group_id, provider_key, model_name,"
                + " likely_cause, verdict, evidence_entry_ids, uncertainty, runbooks_supplied,"
                + " runbook_refs, raw_output, raw_output_truncated, validation,"
                + " rejection_reasons) VALUES (:id, :t, :now, :incident, :origin, :run, :kind,"
                + " :user, :module, :group, 'claude', 'claude-sonnet-5-5', :cause, 'confirms',"
                + " CAST(:ids AS jsonb), 'Whether the archive job will run again.',"
                + " CAST(:supplied AS jsonb), CAST(:cited AS jsonb), '{}', false, :validation,"
                + " CAST(:reasons AS jsonb))")
        .param("id", id)
        .param("t", tenant.tenant.value())
        .param("now", Timestamp.from(createdAt))
        .param("incident", incident)
        .param("origin", origin)
        .param("run", rerun ? null : Ids.newId())
        .param("kind", rerun ? "user" : "module")
        .param("user", rerun ? requester : null)
        .param("module", rerun ? null : "vigil-engine")
        .param("group", Ids.newId())
        .param("cause", "The archive catch-up run filled /data (" + id + ").")
        .param("ids", JSON.writeValueAsString(evidenceIds.stream().map(UUID::toString).toList()))
        .param("supplied", JSON.writeValueAsString(supplied))
        .param("cited", JSON.writeValueAsString(cited))
        .param("validation", accepted ? "accepted" : "rejected")
        .param("reasons", accepted ? "[]" : "[\"The output is not valid JSON.\"]")
        .update();
    return id;
  }

  /**
   * An incident summary inserted as postgres; {@code provider_key} {@code claude} for {@code ai}.
   */
  public UUID summary(
      ActionsFixture tenant, UUID incident, String source, String text, Instant createdAt) {
    UUID id = Ids.newId();
    tenant
        .postgres()
        .sql(
            "INSERT INTO investigation.incident_summary (id, tenant_id, created_at, incident_id,"
                + " run_id, text, source, provider_key) VALUES (:id, :t, :now, :incident, NULL,"
                + " :text, :source, :provider)")
        .param("id", id)
        .param("t", tenant.tenant.value())
        .param("now", Timestamp.from(createdAt))
        .param("incident", incident)
        .param("text", text)
        .param("source", source)
        .param("provider", source.equals("ai") ? "claude" : null)
        .update();
    return id;
  }

  /** The four {@code baseline_values} of {@code 07-settings.yaml} imported as part 17. */
  public void baselines(ActionsFixture tenant) {
    JsonNode file;
    try {
      file =
          YAMLMapper.builder().build().readTree(Files.readString(SETTINGS, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    List<BaselinePart> baselines = new ArrayList<>();
    for (JsonNode baseline : file.get("baseline_values")) {
      baselines.add(
          new BaselinePart(
              baseline.get("metric_key").asString(),
              baseline.get("value").asString(),
              baseline.get("unit").asString(),
              baseline.get("label").asString()));
    }
    servers
        .app
        .context()
        .getBean(ReportingImport.class)
        .importPart(
            tenant.tenant, new ReportingPart(List.copyOf(baselines)), clock.instant(), Map.of());
  }
}
