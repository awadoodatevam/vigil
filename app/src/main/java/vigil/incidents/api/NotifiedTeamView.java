package vigil.incidents.api;

import java.time.Instant;
import java.util.UUID;

/** A notified team (DM-E-37). */
public record NotifiedTeamView(
    UUID id, UUID incidentId, String teamKey, Instant firstNotifiedAt, NotifiedPurpose purpose) {}
