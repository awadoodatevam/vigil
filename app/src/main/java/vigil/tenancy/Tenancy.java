package vigil.tenancy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vigil.shared.Actor;
import vigil.shared.Ids;
import vigil.shared.TenantId;
import vigil.shared.Timestamps;
import vigil.shared.VigilError;
import vigil.tenancy.api.AliasRevision;
import vigil.tenancy.api.TenantAliasChanged;
import vigil.tenancy.api.TenantImport;
import vigil.tenancy.api.TenantLogo;
import vigil.tenancy.api.TenantService;
import vigil.tenancy.api.TenantSettingsPart;
import vigil.tenancy.api.TenantSettingsView;
import vigil.tenancy.api.TenantState;
import vigil.tenancy.api.TenantView;

/** Tenants, tenant settings and tenant aliases (PM-M01; DM-E-01, DM-E-02, DM-E-83). */
@Service
class Tenancy implements TenantService, TenantImport {

  private static final int NAME_MAX = 100;
  private static final int SET_BY_MAX = 40;
  private static final int CUSTOMER_NAME_MAX = 100;
  private static final int ENVIRONMENT_LABEL_MAX = 100;
  private static final int PRODUCT_DISPLAY_NAME_MAX = 60;
  private static final int LOGO_MAX_BYTES = 512 * 1024;
  private static final Set<String> LOGO_MEDIA_TYPES = Set.of("image/png", "image/svg+xml");
  private static final Pattern HOST_NAME = Pattern.compile("^[a-z0-9.-]{1,253}$");
  private static final Actor SETUP = Actor.module("vigil-setup");
  private static final String HIGHEST_REVISIONS =
      "SELECT a.host_name, a.revision, a.tenant_id FROM tenancy.tenant_alias a"
          + " WHERE a.revision = (SELECT max(b.revision) FROM tenancy.tenant_alias b"
          + " WHERE b.host_name = a.host_name)";

  private final JdbcClient jdbc;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  Tenancy(JdbcClient jdbc, Clock clock, ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.clock = clock;
    this.events = events;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public TenantId createTenant(String name, Actor createdBy) {
    if (name == null || name.isEmpty() || characters(name) > NAME_MAX) {
      throw new VigilError(VigilError.Code.BAD_REQUEST, "name: 1 to 100 characters");
    }
    required(createdBy, "createdBy");
    TenantId id = new TenantId(Ids.newId());
    jdbc.sql(
            "INSERT INTO tenancy.tenant (id, name, state, created_at, created_by_kind,"
                + " created_by_user_id, created_by_module) VALUES (:id, :name, 'provisioning',"
                + " :createdAt, :kind, :userId, :module)")
        .param("id", id.value())
        .param("name", name)
        .param("createdAt", Timestamp.from(Timestamps.now(clock)))
        .param("kind", actorKind(createdBy))
        .param("userId", createdBy.userId())
        .param("module", createdBy.module())
        .update();
    return id;
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void markReady(TenantId tenantId) {
    lockProvisioning(tenantId);
    if (!hasSettings(tenantId)) {
      throw new VigilError(
          VigilError.Code.CONFLICT, "Tenant " + tenantId.value() + " has no settings.");
    }
    jdbc.sql("UPDATE tenancy.tenant SET state = 'ready', ready_at = :now WHERE id = :id")
        .param("now", Timestamp.from(Timestamps.now(clock)))
        .param("id", tenantId.value())
        .update();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public void markFailed(TenantId tenantId) {
    lockProvisioning(tenantId);
    jdbc.sql("UPDATE tenancy.tenant SET state = 'failed' WHERE id = :id")
        .param("id", tenantId.value())
        .update();
  }

  /** Locks the tenant row (AR-R-12) and refuses unless the tenant is {@code provisioning}. */
  private void lockProvisioning(TenantId tenantId) {
    required(tenantId, "tenantId");
    Optional<String> state =
        jdbc.sql("SELECT state FROM tenancy.tenant WHERE id = :id FOR UPDATE")
            .param("id", tenantId.value())
            .query(String.class)
            .optional();
    if (state.isEmpty()) {
      throw new VigilError(VigilError.Code.NOT_FOUND);
    }
    if (!state.get().equals(TenantState.PROVISIONING.key())) {
      throw new VigilError(
          VigilError.Code.CONFLICT, "Tenant " + tenantId.value() + " is " + state.get() + ".");
    }
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantView> tenant(TenantId tenantId) {
    required(tenantId, "tenantId");
    return jdbc.sql(
            "SELECT id, name, state, created_at, created_by_kind, created_by_user_id,"
                + " created_by_module, ready_at FROM tenancy.tenant WHERE id = :id")
        .param("id", tenantId.value())
        .query(Tenancy::tenantView)
        .optional();
  }

  @Override
  @Transactional(readOnly = true)
  public TenantSettingsView settings(TenantId tenantId) {
    required(tenantId, "tenantId");
    return jdbc.sql(
            "SELECT customer_name, customer_logo IS NOT NULL AS has_logo, environment_label,"
                + " product_display_name FROM tenancy.tenant_settings WHERE tenant_id = :tenantId")
        .param("tenantId", tenantId.value())
        .query(
            (rs, row) ->
                new TenantSettingsView(
                    rs.getString("customer_name"),
                    rs.getBoolean("has_logo"),
                    rs.getString("environment_label"),
                    rs.getString("product_display_name")))
        .optional()
        .orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantLogo> logo(TenantId tenantId) {
    required(tenantId, "tenantId");
    return jdbc.sql(
            "SELECT customer_logo, customer_logo_media_type FROM tenancy.tenant_settings"
                + " WHERE tenant_id = :tenantId AND customer_logo IS NOT NULL")
        .param("tenantId", tenantId.value())
        .query(
            (rs, row) ->
                new TenantLogo(
                    rs.getBytes("customer_logo"), rs.getString("customer_logo_media_type")))
        .optional();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public AliasRevision setAlias(String hostName, TenantId tenantId, String setBy) {
    String host = hostName == null ? null : hostName.toLowerCase(Locale.ROOT);
    if (host == null || !HOST_NAME.matcher(host).matches()) {
      throw new VigilError(
          VigilError.Code.BAD_REQUEST, "host name: 1 to 253 characters of a-z, 0-9, . and -");
    }
    if (setBy == null || setBy.isEmpty() || characters(setBy) > SET_BY_MAX) {
      throw new IllegalArgumentException("setBy: 1 to 40 characters");
    }
    required(tenantId, "tenantId");
    TenantView tenant =
        tenant(tenantId).orElseThrow(() -> new VigilError(VigilError.Code.NOT_FOUND));
    if (tenant.state() != TenantState.READY) {
      throw new VigilError(
          VigilError.Code.REFUSED,
          "The tenant is not ready.",
          Map.of("refusal", "tenant_not_ready"));
    }
    jdbc.sql(
            "SELECT pg_advisory_xact_lock(hashtext('tenancy.tenant_alias'), hashtext(:host))::text")
        .param("host", host)
        .query(String.class)
        .single();
    Optional<Map<String, Object>> previous =
        jdbc
            .sql(
                "SELECT revision, tenant_id FROM tenancy.tenant_alias WHERE host_name = :host"
                    + " ORDER BY revision DESC LIMIT 1")
            .param("host", host)
            .query()
            .listOfRows()
            .stream()
            .findFirst();
    long revision = previous.map(row -> ((Number) row.get("revision")).longValue() + 1).orElse(1L);
    TenantId previousTenant =
        previous.map(row -> new TenantId((UUID) row.get("tenant_id"))).orElse(null);
    Instant setAt = Timestamps.now(clock);
    jdbc.sql(
            "INSERT INTO tenancy.tenant_alias (host_name, revision, tenant_id, set_at, set_by)"
                + " VALUES (:host, :revision, :tenantId, :setAt, :setBy)")
        .param("host", host)
        .param("revision", revision)
        .param("tenantId", tenantId.value())
        .param("setAt", Timestamp.from(setAt))
        .param("setBy", setBy)
        .update();
    events.publishEvent(new TenantAliasChanged(host, tenantId, previousTenant));
    return new AliasRevision(host, revision, tenantId, setAt, setBy);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<TenantId> resolveHost(String hostName) {
    if (hostName == null) {
      return Optional.empty();
    }
    String host = hostName.toLowerCase(Locale.ROOT);
    if (!HOST_NAME.matcher(host).matches()) {
      return Optional.empty();
    }
    return jdbc.sql(
            "SELECT tenant_id FROM tenancy.tenant_alias WHERE host_name = :host"
                + " ORDER BY revision DESC LIMIT 1")
        .param("host", host)
        .query(UUID.class)
        .optional()
        .map(TenantId::new);
  }

  @Override
  @Transactional(readOnly = true)
  public Set<TenantId> activeTenants() {
    List<UUID> ids =
        jdbc.sql("SELECT DISTINCT tenant_id FROM (" + HIGHEST_REVISIONS + ") current_alias")
            .query(UUID.class)
            .list();
    Set<TenantId> active = new HashSet<>();
    ids.forEach(id -> active.add(new TenantId(id)));
    return Set.copyOf(active);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean isActive(TenantId tenantId) {
    required(tenantId, "tenantId");
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM ("
                + HIGHEST_REVISIONS
                + ") current_alias WHERE tenant_id = :tenantId)")
        .param("tenantId", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<String> publicHostName(TenantId tenantId) {
    required(tenantId, "tenantId");
    return jdbc.sql(
            "SELECT host_name FROM tenancy.tenant_alias WHERE tenant_id = :tenantId"
                + " ORDER BY set_at DESC, revision DESC, host_name COLLATE \"C\" ASC LIMIT 1")
        .param("tenantId", tenantId.value())
        .query(String.class)
        .optional();
  }

  @Override
  @Transactional(propagation = Propagation.REQUIRED)
  public Map<String, UUID> importPart(
      TenantId tenantId,
      TenantSettingsPart part,
      Instant importStartedAt,
      Map<String, UUID> handles) {
    required(tenantId, "tenantId");
    required(part, "part");
    List<String> problems = new ArrayList<>();
    Optional<TenantView> tenant = tenant(tenantId);
    if (tenant.isEmpty() || tenant.get().state() != TenantState.PROVISIONING) {
      problems.add("tenant is not provisioning");
    }
    if (hasSettings(tenantId)) {
      problems.add("tenant settings already exist");
    }
    if (!lengthBetween(part.customerName(), 1, CUSTOMER_NAME_MAX)) {
      problems.add("customer_name: 1 to 100 characters");
    }
    if (part.environmentLabel() != null
        && characters(part.environmentLabel()) > ENVIRONMENT_LABEL_MAX) {
      problems.add("environment_label: at most 100 characters");
    }
    if (!lengthBetween(part.productDisplayName(), 1, PRODUCT_DISPLAY_NAME_MAX)) {
      problems.add("product_display_name: 1 to 60 characters");
    }
    TenantLogo logo = part.logo();
    if (logo != null) {
      if (!LOGO_MEDIA_TYPES.contains(logo.mediaType())) {
        problems.add("customer_logo: media type image/png or image/svg+xml");
      }
      if (logo.bytes() == null || logo.bytes().length < 1 || logo.bytes().length > LOGO_MAX_BYTES) {
        problems.add("customer_logo: 1 byte to 512 KB");
      }
    }
    if (!problems.isEmpty()) {
      throw new VigilError(
          VigilError.Code.INVALID, "Part tenant settings refused.", Map.of("problems", problems));
    }
    Timestamp now = Timestamp.from(Timestamps.now(clock));
    String label =
        part.environmentLabel() == null || part.environmentLabel().isEmpty()
            ? null
            : part.environmentLabel();
    jdbc.sql(
            "INSERT INTO tenancy.tenant_settings (id, tenant_id, created_at, updated_at,"
                + " customer_name, customer_logo, customer_logo_media_type, environment_label,"
                + " product_display_name, updated_by_kind, updated_by_user_id, updated_by_module)"
                + " VALUES (:id, :tenantId, :now, :now, :customerName, :logo, :mediaType, :label,"
                + " :productDisplayName, :kind, :userId, :module)")
        .param("id", Ids.newId())
        .param("tenantId", tenantId.value())
        .param("now", now)
        .param("customerName", part.customerName())
        .param("logo", logo == null ? null : logo.bytes())
        .param("mediaType", logo == null ? null : logo.mediaType())
        .param("label", label)
        .param("productDisplayName", part.productDisplayName())
        .param("kind", actorKind(SETUP))
        .param("userId", SETUP.userId())
        .param("module", SETUP.module())
        .update();
    return Map.of();
  }

  private boolean hasSettings(TenantId tenantId) {
    return jdbc.sql(
            "SELECT EXISTS (SELECT 1 FROM tenancy.tenant_settings WHERE tenant_id = :tenantId)")
        .param("tenantId", tenantId.value())
        .query(Boolean.class)
        .single();
  }

  private static TenantView tenantView(ResultSet rs, int row) throws SQLException {
    Actor createdBy =
        "user".equals(rs.getString("created_by_kind"))
            ? Actor.user(rs.getObject("created_by_user_id", UUID.class))
            : Actor.module(rs.getString("created_by_module"));
    Timestamp readyAt = rs.getTimestamp("ready_at");
    return new TenantView(
        new TenantId(rs.getObject("id", UUID.class)),
        rs.getString("name"),
        TenantState.valueOf(rs.getString("state").toUpperCase(Locale.ROOT)),
        rs.getTimestamp("created_at").toInstant(),
        createdBy,
        readyAt == null ? null : readyAt.toInstant());
  }

  private static String actorKind(Actor actor) {
    return actor.kind().name().toLowerCase(Locale.ROOT);
  }

  private static boolean lengthBetween(String value, int min, int max) {
    return value != null && characters(value) >= min && characters(value) <= max;
  }

  private static int characters(String value) {
    return value.codePointCount(0, value.length());
  }

  private static void required(Object value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + ": required");
    }
  }
}
