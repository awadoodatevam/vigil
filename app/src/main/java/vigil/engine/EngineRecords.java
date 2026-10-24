package vigil.engine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import vigil.engine.api.EndReason;
import vigil.engine.api.RunMode;
import vigil.engine.api.RunState;
import vigil.engine.api.RunView;
import vigil.engine.api.StepExecutionState;
import vigil.engine.api.StepExecutionView;
import vigil.engine.api.WaitReason;
import vigil.shared.Completed.ResultRef;
import vigil.shared.StepCall.StepType;
import vigil.shared.TenantId;
import vigil.shared.Waiting.WakeEvent;

/** The tables of {@code engine}; every statement filters by {@code tenant_id}. */
@Component
class EngineRecords {

  private static final String RUN_COLUMNS =
      "id, incident_id, mode, episode, workflow_version_id, draft_id, draft_revision,"
          + " content::text AS content, test_replay_id, state, wait_reason, wake_pending,"
          + " wake_reason, current_step_execution_id, next_step_id, simulated_now, started_at,"
          + " finished_at, end_reason, end_detail";
  private static final String STEP_COLUMNS =
      "id, run_id, step_id, step_type, visit, state, started_at, finished_at, named_output,"
          + " result_ref::text AS result_ref, wake_events::text AS wake_events, timer_id,"
          + " step_state::text AS step_state, evaluation_count, refusal_reason, test_note,"
          + " failed_attempts, retry_due_at";
  private static final String UNFINISHED = "state IN ('running', 'waiting')";

  /** A workflow run whose changeable fields a transaction sets before {@link #updateRun}. */
  static final class RunRow {
    TenantId tenantId;
    UUID id;
    UUID incidentId;
    RunMode mode;
    Long episode;
    UUID workflowVersionId;
    String content;
    RunState state;
    WaitReason waitReason;
    boolean wakePending;
    String wakeReason;
    UUID currentStepExecutionId;
    String nextStepId;
    Instant startedAt;
    Instant finishedAt;
    EndReason endReason;
    String endDetail;
    private UUID draftId;
    private Long draftRevision;
    private UUID testReplayId;
    private Instant simulatedNow;

    RunView view() {
      return new RunView(
          id,
          incidentId,
          mode,
          episode,
          workflowVersionId,
          draftId,
          draftRevision,
          content,
          testReplayId,
          state,
          waitReason,
          wakePending,
          wakeReason,
          currentStepExecutionId,
          nextStepId,
          simulatedNow,
          startedAt,
          finishedAt,
          endReason,
          endDetail);
    }

    UUID testReplayId() {
      return testReplayId;
    }
  }

  /** A step execution whose changeable fields a transaction sets before {@link #updateStep}. */
  static final class StepRow {
    UUID id;
    UUID runId;
    String stepId;
    StepType stepType;
    long visit;
    StepExecutionState state;
    Instant startedAt;
    Instant finishedAt;
    String namedOutput;
    ResultRef resultRef;
    Set<WakeEvent> wakeEvents;
    UUID timerId;
    String stepState;
    long evaluationCount;
    String refusalReason;
    long failedAttempts;
    Instant retryDueAt;
    private String testNote;

    StepExecutionView view() {
      return new StepExecutionView(
          id,
          runId,
          stepId,
          stepType,
          visit,
          state,
          startedAt,
          finishedAt,
          namedOutput,
          resultRef,
          wakeEvents,
          timerId,
          stepState,
          evaluationCount,
          refusalReason,
          testNote,
          failedAttempts,
          retryDueAt);
    }
  }

  /** A timer that fired: its run and its id. */
  record FiredTimer(UUID id, UUID runId) {}

  private final JdbcClient jdbc;

  EngineRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  // Runs.

  Optional<RunRow> run(TenantId tenantId, UUID id) {
    return runs(tenantId, "id = :id", Map.of("id", id), "");
  }

  /** The run, locked until the end of the transaction. */
  Optional<RunRow> lockRun(TenantId tenantId, UUID id) {
    return runs(tenantId, "id = :id", Map.of("id", id), " FOR UPDATE");
  }

  private Optional<RunRow> runs(
      TenantId tenantId, String where, Map<String, Object> params, String suffix) {
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM engine.workflow_run WHERE tenant_id = :tenantId AND "
                + where
                + suffix)
        .param("tenantId", tenantId.value())
        .params(params)
        .query((rs, n) -> run(tenantId, rs))
        .optional();
  }

  List<RunRow> runsOfIncident(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM engine.workflow_run WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId ORDER BY started_at, id")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query((rs, n) -> run(tenantId, rs))
        .list();
  }

  /** The unfinished live runs of the incident, locked in id order. */
  List<RunRow> lockUnfinishedLiveRuns(TenantId tenantId, UUID incidentId) {
    return jdbc.sql(
            "SELECT "
                + RUN_COLUMNS
                + " FROM engine.workflow_run WHERE tenant_id = :tenantId"
                + " AND incident_id = :incidentId AND mode = 'live' AND "
                + UNFINISHED
                + " ORDER BY id FOR UPDATE")
        .param("tenantId", tenantId.value())
        .param("incidentId", incidentId)
        .query((rs, n) -> run(tenantId, rs))
        .list();
  }

  boolean hasLiveRun(TenantId tenantId, UUID incidentId, long episode) {
    return jdbc.sql(
                "SELECT count(*) FROM engine.workflow_run WHERE tenant_id = :tenantId"
                    + " AND incident_id = :incidentId AND mode = 'live' AND episode = :episode")
            .param("tenantId", tenantId.value())
            .param("incidentId", incidentId)
            .param("episode", episode)
            .query(Long.class)
            .single()
        > 0;
  }

  /** The ids of the tenant's runs in the state. */
  List<UUID> runIds(TenantId tenantId, RunState state) {
    return jdbc.sql(
            "SELECT id FROM engine.workflow_run WHERE tenant_id = :tenantId AND state = :state"
                + " ORDER BY id")
        .param("tenantId", tenantId.value())
        .param("state", state.key())
        .query(UUID.class)
        .list();
  }

  void insertRun(RunRow row, Instant now) {
    Map<String, Object> params = runParams(row, now);
    params.put("incident_id", row.incidentId);
    params.put("mode", row.mode.key());
    params.put("episode", row.episode);
    params.put("workflow_version_id", row.workflowVersionId);
    params.put("content", row.content);
    params.put("started_at", Timestamp.from(row.startedAt));
    jdbc.sql(
            "INSERT INTO engine.workflow_run (id, tenant_id, created_at, updated_at, incident_id,"
                + " mode, episode, workflow_version_id, content, state, wait_reason, wake_pending,"
                + " wake_reason, current_step_execution_id, next_step_id, started_at,"
                + " finished_at, end_reason, end_detail) VALUES (:id, :tenant_id, :updated_at,"
                + " :updated_at, :incident_id, :mode, :episode, :workflow_version_id,"
                + " CAST(:content AS jsonb), :state, :wait_reason, :wake_pending, :wake_reason,"
                + " :current_step_execution_id, :next_step_id, :started_at, :finished_at,"
                + " :end_reason, :end_detail)")
        .params(params)
        .update();
  }

  /** Writes the run's changeable fields and {@code updated_at}. */
  void updateRun(RunRow row, Instant now) {
    jdbc.sql(
            "UPDATE engine.workflow_run SET updated_at = :updated_at, state = :state,"
                + " wait_reason = :wait_reason, wake_pending = :wake_pending,"
                + " wake_reason = :wake_reason,"
                + " current_step_execution_id = :current_step_execution_id,"
                + " next_step_id = :next_step_id, finished_at = :finished_at,"
                + " end_reason = :end_reason, end_detail = :end_detail"
                + " WHERE tenant_id = :tenant_id AND id = :id")
        .params(runParams(row, now))
        .update();
  }

  private static Map<String, Object> runParams(RunRow row, Instant now) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", row.id);
    params.put("tenant_id", row.tenantId.value());
    params.put("updated_at", Timestamp.from(now));
    params.put("state", row.state.key());
    params.put("wait_reason", row.waitReason == null ? null : row.waitReason.key());
    params.put("wake_pending", row.wakePending);
    params.put("wake_reason", row.wakeReason);
    params.put("current_step_execution_id", row.currentStepExecutionId);
    params.put("next_step_id", row.nextStepId);
    params.put("finished_at", timestamp(row.finishedAt));
    params.put("end_reason", row.endReason == null ? null : row.endReason.key());
    params.put("end_detail", row.endDetail);
    return params;
  }

  private static RunRow run(TenantId tenantId, ResultSet rs) throws SQLException {
    RunRow row = new RunRow();
    row.tenantId = tenantId;
    row.id = rs.getObject("id", UUID.class);
    row.incidentId = rs.getObject("incident_id", UUID.class);
    row.mode = RunMode.fromKey(rs.getString("mode")).orElseThrow();
    row.episode = rs.getObject("episode", Long.class);
    row.workflowVersionId = rs.getObject("workflow_version_id", UUID.class);
    row.draftId = rs.getObject("draft_id", UUID.class);
    row.draftRevision = rs.getObject("draft_revision", Long.class);
    row.content = rs.getString("content");
    row.testReplayId = rs.getObject("test_replay_id", UUID.class);
    row.state = RunState.fromKey(rs.getString("state")).orElseThrow();
    row.waitReason = WaitReason.fromKey(rs.getString("wait_reason")).orElse(null);
    row.wakePending = rs.getBoolean("wake_pending");
    row.wakeReason = rs.getString("wake_reason");
    row.currentStepExecutionId = rs.getObject("current_step_execution_id", UUID.class);
    row.nextStepId = rs.getString("next_step_id");
    row.simulatedNow = instant(rs, "simulated_now");
    row.startedAt = instant(rs, "started_at");
    row.finishedAt = instant(rs, "finished_at");
    row.endReason = EndReason.fromKey(rs.getString("end_reason")).orElse(null);
    row.endDetail = rs.getString("end_detail");
    return row;
  }

  // Wakes (AR-R-20): one UPDATE marks the runs the wake is for.

  private static final String MARK =
      "UPDATE engine.workflow_run r SET wake_pending = true, wake_reason = :reason,"
          + " updated_at = :now WHERE r.tenant_id = :tenantId AND r.state IN ('running', 'waiting')"
          + " AND (%s) AND (r.state = 'running' OR (r.wait_reason = 'step_waiting' AND EXISTS"
          + " (SELECT 1 FROM engine.step_execution s WHERE s.tenant_id = r.tenant_id"
          + " AND s.id = r.current_step_execution_id AND %s))"
          + " OR (r.wait_reason = 'incident_forming' AND :event = 'incident_state_changed'))"
          + " RETURNING r.id";
  private static final String LISTED = "s.wake_events @> jsonb_build_array(CAST(:event AS text))";

  /** Marks the tenant's unfinished live runs whose current step is a Recovery check step. */
  List<UUID> markRecoveryChecks(TenantId tenantId, WakeEvent event, Instant now) {
    return mark(
        tenantId,
        "r.mode = 'live' AND EXISTS (SELECT 1 FROM engine.step_execution c"
            + " WHERE c.tenant_id = r.tenant_id AND c.id = r.current_step_execution_id"
            + " AND c.step_type = 'recovery_check')",
        LISTED,
        Map.of(),
        "event:" + event.key(),
        event.key(),
        now);
  }

  /** Marks the unfinished live runs of the incident. */
  List<UUID> markIncidentRuns(TenantId tenantId, UUID incidentId, WakeEvent event, Instant now) {
    return mark(
        tenantId,
        "r.mode = 'live' AND r.incident_id = :incidentId",
        LISTED,
        Map.of("incidentId", incidentId),
        "event:" + event.key(),
        event.key(),
        now);
  }

  /** Marks the run for an approval decision of its current step execution (DM-R-67). */
  List<UUID> markApproval(TenantId tenantId, UUID runId, UUID stepExecutionId, Instant now) {
    String event = WakeEvent.APPROVAL_DECIDED.key();
    return mark(
        tenantId,
        "r.id = :runId",
        LISTED + " AND s.id = :stepExecutionId",
        Map.of("runId", runId, "stepExecutionId", stepExecutionId),
        "event:" + event,
        event,
        now);
  }

  /** Marks the run of a fired timer that belongs to its current step execution. */
  List<UUID> markTimer(TenantId tenantId, FiredTimer timer, Instant now) {
    return mark(
        tenantId,
        "r.id = :runId",
        "s.timer_id = :timerId",
        Map.of("runId", timer.runId(), "timerId", timer.id()),
        "timer",
        "timer",
        now);
  }

  private List<UUID> mark(
      TenantId tenantId,
      String runs,
      String step,
      Map<String, Object> params,
      String reason,
      String event,
      Instant now) {
    return jdbc.sql(String.format(MARK, runs, step))
        .param("tenantId", tenantId.value())
        .param("reason", reason)
        .param("event", event)
        .param("now", Timestamp.from(now))
        .params(params)
        .query(UUID.class)
        .list();
  }

  /** Restart step (4) for a tenant: every waiting run gets a wake with reason {@code restart}. */
  void wakeWaitingRuns(TenantId tenantId, Instant now) {
    jdbc.sql(
            "UPDATE engine.workflow_run SET wake_pending = true, wake_reason = 'restart',"
                + " updated_at = :now WHERE tenant_id = :tenantId AND state = 'waiting'")
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .update();
  }

  /** Restart step (4) for a tenant: retries of the running runs count from zero. */
  void resetRetries(TenantId tenantId, Instant now) {
    jdbc.sql(
            "UPDATE engine.step_execution s SET retry_due_at = NULL, failed_attempts = 0,"
                + " updated_at = :now WHERE s.tenant_id = :tenantId AND (s.retry_due_at IS NOT NULL"
                + " OR s.failed_attempts <> 0) AND s.id IN (SELECT r.current_step_execution_id"
                + " FROM engine.workflow_run r WHERE r.tenant_id = :tenantId"
                + " AND r.state = 'running')")
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .update();
  }

  // Step executions.

  Optional<StepRow> step(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT "
                + STEP_COLUMNS
                + " FROM engine.step_execution WHERE tenant_id = :tenantId AND id = :id")
        .param("tenantId", tenantId.value())
        .param("id", id)
        .query(EngineRecords::step)
        .optional();
  }

  /** The run's step executions, by {@code started_at}, then id. */
  List<StepRow> steps(TenantId tenantId, UUID runId) {
    return jdbc.sql(
            "SELECT "
                + STEP_COLUMNS
                + " FROM engine.step_execution WHERE tenant_id = :tenantId AND run_id = :runId"
                + " ORDER BY started_at, id")
        .param("tenantId", tenantId.value())
        .param("runId", runId)
        .query(EngineRecords::step)
        .list();
  }

  long visits(TenantId tenantId, UUID runId, String stepId) {
    return jdbc.sql(
            "SELECT count(*) FROM engine.step_execution WHERE tenant_id = :tenantId"
                + " AND run_id = :runId AND step_id = :stepId")
        .param("tenantId", tenantId.value())
        .param("runId", runId)
        .param("stepId", stepId)
        .query(Long.class)
        .single();
  }

  void insertStep(TenantId tenantId, StepRow row, Instant now) {
    Map<String, Object> params = stepParams(tenantId, row, now);
    params.put("run_id", row.runId);
    params.put("step_id", row.stepId);
    params.put("step_type", row.stepType.key());
    params.put("visit", row.visit);
    params.put("started_at", Timestamp.from(row.startedAt));
    jdbc.sql(
            "INSERT INTO engine.step_execution (id, tenant_id, created_at, updated_at, run_id,"
                + " step_id, step_type, visit, state, started_at, finished_at, named_output,"
                + " result_ref, wake_events, timer_id, step_state, evaluation_count,"
                + " refusal_reason, failed_attempts, retry_due_at) VALUES (:id, :tenant_id,"
                + " :updated_at, :updated_at, :run_id, :step_id, :step_type, :visit, :state,"
                + " :started_at, :finished_at, :named_output, CAST(:result_ref AS jsonb),"
                + " CAST(:wake_events AS jsonb), :timer_id, CAST(:step_state AS jsonb),"
                + " :evaluation_count, :refusal_reason, :failed_attempts, :retry_due_at)")
        .params(params)
        .update();
  }

  /** Writes the step execution's changeable fields and {@code updated_at}. */
  void updateStep(TenantId tenantId, StepRow row, Instant now) {
    jdbc.sql(
            "UPDATE engine.step_execution SET updated_at = :updated_at, state = :state,"
                + " finished_at = :finished_at, named_output = :named_output,"
                + " result_ref = CAST(:result_ref AS jsonb),"
                + " wake_events = CAST(:wake_events AS jsonb), timer_id = :timer_id,"
                + " step_state = CAST(:step_state AS jsonb), evaluation_count = :evaluation_count,"
                + " refusal_reason = :refusal_reason, failed_attempts = :failed_attempts,"
                + " retry_due_at = :retry_due_at WHERE tenant_id = :tenant_id AND id = :id")
        .params(stepParams(tenantId, row, now))
        .update();
  }

  private static Map<String, Object> stepParams(TenantId tenantId, StepRow row, Instant now) {
    Map<String, Object> params = new HashMap<>();
    params.put("id", row.id);
    params.put("tenant_id", tenantId.value());
    params.put("updated_at", Timestamp.from(now));
    params.put("state", row.state.key());
    params.put("finished_at", timestamp(row.finishedAt));
    params.put("named_output", row.namedOutput);
    params.put("result_ref", resultRef(row.resultRef));
    params.put("wake_events", wakeEvents(row.wakeEvents));
    params.put("timer_id", row.timerId);
    params.put("step_state", row.stepState);
    params.put("evaluation_count", row.evaluationCount);
    params.put("refusal_reason", row.refusalReason);
    params.put("failed_attempts", row.failedAttempts);
    params.put("retry_due_at", timestamp(row.retryDueAt));
    return params;
  }

  private static String resultRef(ResultRef ref) {
    if (ref == null) {
      return null;
    }
    return RunContent.JSON.writeValueAsString(
        RunContent.JSON
            .createObjectNode()
            .put("module", ref.module())
            .put("entity", ref.entity())
            .put("id", ref.id().toString()));
  }

  /** The keys sorted in code point order, as a JSON array (AR-P-01). */
  private static String wakeEvents(Set<WakeEvent> events) {
    if (events == null) {
      return null;
    }
    ArrayNode array = RunContent.JSON.createArrayNode();
    new TreeSet<>(events.stream().map(WakeEvent::key).toList()).forEach(array::add);
    return RunContent.JSON.writeValueAsString(array);
  }

  private static StepRow step(ResultSet rs, int rowNum) throws SQLException {
    StepRow row = new StepRow();
    row.id = rs.getObject("id", UUID.class);
    row.runId = rs.getObject("run_id", UUID.class);
    row.stepId = rs.getString("step_id");
    row.stepType = RunContent.type(rs.getString("step_type"));
    row.visit = rs.getLong("visit");
    row.state = StepExecutionState.fromKey(rs.getString("state")).orElseThrow();
    row.startedAt = instant(rs, "started_at");
    row.finishedAt = instant(rs, "finished_at");
    row.namedOutput = rs.getString("named_output");
    String ref = rs.getString("result_ref");
    if (ref != null) {
      JsonNode node = RunContent.JSON.readTree(ref);
      row.resultRef =
          new ResultRef(
              node.get("module").stringValue(),
              node.get("entity").stringValue(),
              UUID.fromString(node.get("id").stringValue()));
    }
    String events = rs.getString("wake_events");
    if (events != null) {
      Set<WakeEvent> set = new TreeSet<>();
      for (JsonNode event : RunContent.JSON.readTree(events)) {
        for (WakeEvent value : WakeEvent.values()) {
          if (value.key().equals(event.stringValue())) {
            set.add(value);
          }
        }
      }
      row.wakeEvents = Set.copyOf(set);
    }
    row.timerId = rs.getObject("timer_id", UUID.class);
    row.stepState = rs.getString("step_state");
    row.evaluationCount = rs.getLong("evaluation_count");
    row.refusalReason = rs.getString("refusal_reason");
    row.testNote = rs.getString("test_note");
    row.failedAttempts = rs.getLong("failed_attempts");
    row.retryDueAt = instant(rs, "retry_due_at");
    return row;
  }

  // Durable timers.

  /** A new {@code pending} timer of the step execution; returns its id. */
  UUID insertTimer(
      TenantId tenantId,
      UUID id,
      UUID runId,
      UUID stepExecutionId,
      Instant dueAt,
      String purpose,
      Instant now) {
    jdbc.sql(
            "INSERT INTO engine.durable_timer (id, tenant_id, created_at, updated_at, run_id,"
                + " step_execution_id, due_at, state, purpose) VALUES (:id, :tenantId, :now,"
                + " :now, :runId, :stepExecutionId, :dueAt, 'pending', :purpose)")
        .param("id", id)
        .param("tenantId", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("runId", runId)
        .param("stepExecutionId", stepExecutionId)
        .param("dueAt", Timestamp.from(dueAt))
        .param("purpose", purpose)
        .update();
    return id;
  }

  /** DM-SM09-T3: the step execution's {@code pending} timer, if any, becomes {@code cancelled}. */
  void cancelTimer(TenantId tenantId, UUID stepExecutionId, Instant now) {
    jdbc.sql(
            "UPDATE engine.durable_timer SET state = 'cancelled', updated_at = :now"
                + " WHERE tenant_id = :tenantId AND step_execution_id = :stepExecutionId"
                + " AND state = 'pending'")
        .param("tenantId", tenantId.value())
        .param("stepExecutionId", stepExecutionId)
        .param("now", Timestamp.from(now))
        .update();
  }

  /** DM-SM09-T2: the tenant's {@code pending} timers due at or before now fire, in due order. */
  List<FiredTimer> fireDueTimers(TenantId tenantId, Instant now) {
    List<FiredTimer> due =
        jdbc.sql(
                "SELECT id, run_id FROM engine.durable_timer WHERE tenant_id = :tenantId"
                    + " AND state = 'pending' AND due_at <= :now ORDER BY due_at, id FOR UPDATE")
            .param("tenantId", tenantId.value())
            .param("now", Timestamp.from(now))
            .query(
                (rs, n) ->
                    new FiredTimer(
                        rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class)))
            .list();
    for (FiredTimer timer : due) {
      jdbc.sql(
              "UPDATE engine.durable_timer SET state = 'fired', updated_at = :now"
                  + " WHERE tenant_id = :tenantId AND id = :id")
          .param("tenantId", tenantId.value())
          .param("id", timer.id())
          .param("now", Timestamp.from(now))
          .update();
    }
    return due;
  }

  private static Timestamp timestamp(Instant instant) {
    return instant == null ? null : Timestamp.from(instant);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    Timestamp value = rs.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
