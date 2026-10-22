package vigil.incidents.api;

/**
 * Seed part 9, the section {@code incident_settings} of {@code 07-settings.yaml} (DD-F-07), each
 * value's text as the seed import reads it ({@code null} for a missing value).
 */
public record IncidentSettingsPart(String reopenWindowS, String closureCheckIntervalS) {}
