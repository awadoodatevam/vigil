package vigil.connectors.api;

import java.util.List;
import java.util.UUID;
import vigil.shared.TenantId;

/**
 * The notification channel connector type (AR-K-00d): one bean per channel kind in
 * vigil-connectors; vigil-notifications calls it.
 */
public interface NotificationChannelConnector {

  /** The kind this connector serves; its type is {@link ConnectorType#NOTIFICATION_CHANNEL}. */
  ConnectorKind kind();

  /**
   * Sends one message of at least one line to {@code address}: {@code credentialRefId} is the
   * delivery address's credential reference ({@code null} for email), {@code senderName} the
   * product display name (used by email only), line 1 the subject of an email.
   *
   * <p>external call: not inside a transaction
   */
  Delivery send(
      TenantId tenantId,
      ConnectorInstanceView instance,
      String address,
      UUID credentialRefId,
      String senderName,
      List<MessageLine> lines);
}
