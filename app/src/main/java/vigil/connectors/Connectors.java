package vigil.connectors;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import vigil.connectors.api.ConnectionTestResult;
import vigil.connectors.api.ConnectorInstanceView;
import vigil.connectors.api.ConnectorKind;
import vigil.connectors.api.ConnectorService;
import vigil.connectors.api.CredentialReferenceView;
import vigil.connectors.api.MonitoringSourceConnector;
import vigil.connectors.api.ParseResult;
import vigil.shared.TenantId;
import vigil.shared.TransactionGuard;
import vigil.shared.VigilError;

/** The requests of other modules to vigil-connectors (PM-M04, SL-B-07). */
@Service
class Connectors implements ConnectorService {

  private final ConnectorRecords records;
  private final MonitoringConnectors monitoring;

  Connectors(ConnectorRecords records, MonitoringConnectors monitoring) {
    this.records = records;
    this.monitoring = monitoring;
  }

  @Override
  public Optional<ConnectorInstanceView> instance(TenantId tenantId, UUID instanceId) {
    required(tenantId, "tenantId");
    required(instanceId, "instanceId");
    return records.instance(tenantId, instanceId);
  }

  @Override
  public Optional<CredentialReferenceView> credentialReference(
      TenantId tenantId, UUID credentialRefId) {
    required(tenantId, "tenantId");
    required(credentialRefId, "credentialRefId");
    return records
        .credential(tenantId, credentialRefId)
        .map(stored -> new CredentialReferenceView(stored.id(), stored.purpose()));
  }

  @Override
  public ParseResult parse(ConnectorKind kind, byte[] body, String contentType) {
    required(kind, "kind");
    required(body, "body");
    return monitoring
        .find(kind)
        .orElseThrow(
            () -> new IllegalArgumentException("no monitoring connector for " + kind.key()))
        .parse(body, contentType);
  }

  @Override
  public ConnectionTestResult testConnection(TenantId tenantId, UUID instanceId) {
    TransactionGuard.assertNoTransaction();
    required(tenantId, "tenantId");
    required(instanceId, "instanceId");
    ConnectorInstanceView instance =
        records
            .instance(tenantId, instanceId)
            .orElseThrow(
                () ->
                    new VigilError(
                        VigilError.Code.NOT_FOUND,
                        "Connector instance " + instanceId + " not found."));
    MonitoringSourceConnector connector =
        monitoring
            .find(instance.kind())
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        "connector instance " + instanceId + " is not a monitoring source"));
    return connector.test(instance);
  }

  private static void required(Object value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + ": required");
    }
  }
}
