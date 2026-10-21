package vigil.servicemap.api;

import java.time.Instant;
import vigil.shared.Actor;

/** A service map generation (DM-E-24); {@code importedAt} is its creation time (DC-S07-03). */
public record GenerationView(
    long generation,
    String fileName,
    String fileHash,
    Actor importedBy,
    Instant importedAt,
    Counts counts) {}
