package vigil.incidents.api;

import java.time.Instant;
import java.util.UUID;
import vigil.shared.Actor;

/** A timeline entry (DM-E-39); {@code refType} and {@code refId} may be {@code null}. */
public record TimelineView(
    UUID id,
    UUID incidentId,
    Instant at,
    Actor actor,
    TimelineEntryType entryType,
    String text,
    String refType,
    UUID refId) {}
