package vigil.connectors;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.CredentialPurpose;
import vigil.shared.TenantId;

/** Reads connector instances and credential references of one tenant (DM-C-10). */
@Component
class ConnectorRecords {

  /** A stored credential reference with the secret name of its locator. */
  record StoredCredential(UUID id, CredentialPurpose purpose, String secretName) {}

  private static final String SECRET_PREFIX = "secret:";
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcClient jdbc;

  ConnectorRecords(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  Optional<ConnectorInstanceView> instance(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT id, tenant_id, name, kind, address, settings::text AS settings,"
                + " credential_ref_id, active FROM connectors.connector_instance"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("tenantId", tenantId.value())
        .param("id", id)
        .query(ConnectorRecords::instanceRow)
        .optional();
  }

  Optional<StoredCredential> credential(TenantId tenantId, UUID id) {
    return jdbc.sql(
            "SELECT id, purpose, locator FROM connectors.credential_reference"
                + " WHERE tenant_id = :tenantId AND id = :id")
        .param("tenantId", tenantId.value())
        .param("id", id)
        .query(
            (rs, row) ->
                new StoredCredential(
                    rs.getObject("id", UUID.class),
                    CredentialPurpose.fromKey(rs.getString("purpose"))
                        .orElseThrow(() -> new IllegalStateException("unknown stored purpose")),
                    rs.getString("locator").substring(SECRET_PREFIX.length())))
        .optional();
  }

  private static ConnectorInstanceView instanceRow(ResultSet rs, int row) throws SQLException {
    String kind = rs.getString("kind");
    return new ConnectorInstanceView(
        new TenantId(rs.getObject("tenant_id", UUID.class)),
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        ConnectorKind.fromKey(kind)
            .orElseThrow(() -> new IllegalStateException("unknown stored kind " + kind)),
        rs.getString("address"),
        JSON.readTree(rs.getString("settings")),
        rs.getObject("credential_ref_id", UUID.class),
        rs.getBoolean("active"));
  }
}
