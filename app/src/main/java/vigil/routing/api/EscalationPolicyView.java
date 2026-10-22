package vigil.routing.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import vigil.shared.Actor;

/** An escalation policy revision (DM-E-42). */
public record EscalationPolicyView(
    UUID id, long revision, List<EscalationLevel> levels, Actor savedBy, Instant savedAt) {}
