package vigil.workflows;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import vigil.shared.Actor;
import vigil.shared.TenantId;
import vigil.workflows.api.VersionOrigin;
import vigil.workflows.api.WorkflowVersionView;
import vigil.workflows.api.WorkflowView;

/** The tables of {@code workflows}; every statement filters by {@code tenant_id}. */
@Component
class WorkflowRecords {

  private static final String VERSION_COLUMNS =
      "v.id, v.workflow_id, v.number, v.content::text AS content, v.origin, v.origin_ref,"
          + " v.published_by_kind, v.published_by_user_id, v.published_by_module,"
          + " v.published_at, v.change_list::text AS change_list";

  private final JdbcClient jdbc;

  WorkflowRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  void insertWorkflow(TenantId tenantId, UUID id, String name, UUID activeVersionId, Instant now) {
    jdbc.sql(
            "INSERT INTO workflows.workflow (id, tenant_id, created_at, updated_at, name,"
                + " active_version_id) VALUES (:id, :t, :now, :now, :name, :active)")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("name", name)
        .param("active", activeVersionId)
        .update();
  }

  void insertVersion(
      TenantId tenantId,
      UUID id,
      UUID workflowId,
      long number,
      String contentJson,
      VersionOrigin origin,
      UUID originRef,
      Actor publishedBy,
      String changeListJson,
      Instant now) {
    jdbc.sql(
            "INSERT INTO workflows.workflow_version (id, tenant_id, created_at, workflow_id,"
                + " number, content, origin, origin_ref, published_by_kind, published_by_user_id,"
                + " published_by_module, published_at, change_list) VALUES (:id, :t, :now,"
                + " :workflow, :number, CAST(:content AS jsonb), :origin, :originRef, :kind,"
                + " :userId, :module, :now, CAST(:changeList AS jsonb))")
        .param("id", id)
        .param("t", tenantId.value())
        .param("now", Timestamp.from(now))
        .param("workflow", workflowId)
        .param("number", number)
        .param("content", contentJson)
        .param("origin", origin.key())
        .param("originRef", originRef)
        .param("kind", publishedBy.kind() == Actor.Kind.USER ? "user" : "module")
        .param("userId", publishedBy.userId())
        .param("module", publishedBy.module())
        .param("changeList", changeListJson)
        .update();
  }

  Optional<WorkflowView> workflow(TenantId tenantId) {
    return jdbc.sql(
            "SELECT id, name, active_version_id FROM workflows.workflow WHERE tenant_id = :t")
        .param("t", tenantId.value())
        .query(
            (rs, row) ->
                new WorkflowView(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getObject("active_version_id", UUID.class)))
        .optional();
  }

  Optional<WorkflowVersionView> version(TenantId tenantId, UUID versionId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM workflows.workflow_version v WHERE v.tenant_id = :t AND v.id = :id")
        .param("t", tenantId.value())
        .param("id", versionId)
        .query(WorkflowRecords::version)
        .optional();
  }

  Optional<WorkflowVersionView> activeVersion(TenantId tenantId) {
    return jdbc.sql(
            "SELECT "
                + VERSION_COLUMNS
                + " FROM workflows.workflow w JOIN workflows.workflow_version v"
                + " ON v.tenant_id = w.tenant_id AND v.id = w.active_version_id"
                + " WHERE w.tenant_id = :t")
        .param("t", tenantId.value())
        .query(WorkflowRecords::version)
        .optional();
  }

  private static WorkflowVersionView version(ResultSet rs, int row) throws SQLException {
    return new WorkflowVersionView(
        rs.getObject("id", UUID.class),
        rs.getObject("workflow_id", UUID.class),
        rs.getLong("number"),
        rs.getString("content"),
        VersionOrigin.fromKey(rs.getString("origin")).orElseThrow(),
        rs.getObject("origin_ref", UUID.class),
        rs.getString("published_by_kind").equals("user")
            ? Actor.user(rs.getObject("published_by_user_id", UUID.class))
            : Actor.module(rs.getString("published_by_module")),
        rs.getTimestamp("published_at").toInstant(),
        rs.getString("change_list"));
  }
}
