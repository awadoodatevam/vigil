package vigil.identity.api;

import java.util.UUID;
import vigil.shared.Key;

/** A team (DM-E-04). */
public record TeamView(UUID id, Key teamKey, String name, boolean active) {}
