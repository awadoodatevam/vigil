package vigil.connectors.api;

import java.time.Duration;
import java.util.UUID;
import vigil.shared.TenantId;

/**
 * The target connector type (AR-K-00b): runs one command on a target server. A kind is registered
 * by one bean of this type; vigil-runner implements it (PM-M04-02), vigil-connectors does not call
 * it.
 */
public interface TargetConnector {

  /** The kind this connector serves; its type is {@link ConnectorType#TARGET}. */
  ConnectorKind kind();

  /**
   * Runs {@code command} on the instance's server with the secret of {@code credentialRefId};
   * {@code timeout} bounds the whole call.
   *
   * <p>external call: not inside a transaction
   */
  TargetResult run(
      TenantId tenantId,
      ConnectorInstanceView instance,
      UUID credentialRefId,
      String command,
      Duration timeout);
}
