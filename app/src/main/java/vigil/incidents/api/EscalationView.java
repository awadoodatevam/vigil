package vigil.incidents.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** An incident escalation (DM-E-36); {@code timer} is {@code null} for {@code escalate_now}. */
public record EscalationView(
    UUID id,
    UUID incidentId,
    long level,
    List<String> teamKeys,
    EscalationCause cause,
    Duration timer,
    long policyRevision,
    Instant at) {}
