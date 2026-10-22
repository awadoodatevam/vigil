package vigil.incidents.api;

import java.time.Duration;

/** The incident settings of a tenant (DM-E-40). */
public record IncidentSettingsView(Duration reopenWindow, Duration closureCheckInterval) {}
