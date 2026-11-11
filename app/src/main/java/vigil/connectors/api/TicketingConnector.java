package vigil.connectors.api;

import java.util.List;
import vigil.shared.TenantId;

/**
 * The ticketing connector type (AR-K-00c): one bean per ticketing kind in vigil-connectors;
 * vigil-ticketing calls it. Every method except {@link #kind()} is an external call, not inside a
 * transaction.
 */
public interface TicketingConnector {

  /** The kind this connector serves; its type is {@link ConnectorType#TICKETING}. */
  ConnectorKind kind();

  /**
   * Creates an issue with the title, the description lines and the label.
   *
   * <p>external call: not inside a transaction
   */
  TicketOutcome create(
      TenantId tenantId,
      ConnectorInstanceView instance,
      String title,
      List<TicketLine> description,
      String label);

  /**
   * Adds a comment of the lines to the issue.
   *
   * <p>external call: not inside a transaction
   */
  TicketOutcome addComment(
      TenantId tenantId, ConnectorInstanceView instance, String key, List<TicketLine> lines);

  /**
   * Moves the issue to the status named {@code statusName}.
   *
   * <p>external call: not inside a transaction
   */
  TicketOutcome transition(
      TenantId tenantId, ConnectorInstanceView instance, String key, String statusName);

  /**
   * Reads the issue's status name.
   *
   * <p>external call: not inside a transaction
   */
  TicketOutcome status(TenantId tenantId, ConnectorInstanceView instance, String key);

  /**
   * Searches once for an issue with the label; {@code Done} with no key when there is none.
   *
   * <p>external call: not inside a transaction
   */
  TicketOutcome findByLabel(TenantId tenantId, ConnectorInstanceView instance, String label);
}
