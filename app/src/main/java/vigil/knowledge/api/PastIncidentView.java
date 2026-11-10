package vigil.knowledge.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A past incident (DM-E-64); both lists sorted; {@code acknowledgedAt}, {@code recoveredAt} and
 * {@code fixActionKey} {@code null} when empty.
 */
public record PastIncidentView(
    UUID id,
    String pastIncidentKey,
    String title,
    String recordLabel,
    Instant openedAt,
    Instant acknowledgedAt,
    Instant recoveredAt,
    long alertCount,
    List<String> alertTypes,
    List<String> affectedServiceKeys,
    String hypothesisComponentKey,
    String summary,
    String fixDescription,
    String fixActionKey,
    long fixSuccessCount,
    boolean active) {}
