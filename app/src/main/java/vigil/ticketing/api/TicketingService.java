package vigil.ticketing.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vigil.incidents.api.EscalationCause;
import vigil.shared.TenantId;

/** Ticket links, ticket sync records and the escalation comment (PM-M19). */
public interface TicketingService {

  /** The incident's ticket link, or empty. */
  Optional<TicketLinkView> ticketLink(TenantId tenantId, UUID incidentId);

  /** The incident's sync records, by {@code at}, then id. */
  List<SyncRecordView> syncRecords(TenantId tenantId, UUID incidentId);

  /**
   * PM-M19-01, DC-OUT-02, from vigil-routing: the escalation comment on the incident's ticket, one
   * record per escalation; {@code timer} is required for {@code acknowledgement_timeout} and {@code
   * null} for {@code escalate_now}. Returns the record's outcome.
   *
   * <p>external call: not inside a transaction
   */
  SyncOutcome recordEscalation(
      TenantId tenantId,
      UUID incidentId,
      UUID escalationId,
      long level,
      List<String> teamNames,
      EscalationCause cause,
      Duration timer);
}
