package vigil.incidents.api;

import java.time.Instant;
import java.util.UUID;
import vigil.shared.Actor;

/** An incident state transition (DM-E-33); {@code fromState} is {@code null} for creation. */
public record TransitionView(
    UUID id,
    UUID incidentId,
    String transitionId,
    IncidentState fromState,
    IncidentState toState,
    Instant at,
    Actor actor,
    String reason) {}
