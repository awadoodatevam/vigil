package vigil.ticketing.api;

import java.time.Instant;
import java.util.UUID;

/** A ticket link (DM-E-75): the Jira issue of an incident. */
public record TicketLinkView(
    UUID id,
    UUID incidentId,
    UUID connectorInstanceId,
    String externalKey,
    String url,
    TicketLinkState state,
    Instant createdAt,
    Instant updatedAt) {}
