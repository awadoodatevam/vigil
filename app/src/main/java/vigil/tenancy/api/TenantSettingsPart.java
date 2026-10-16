package vigil.tenancy.api;

/**
 * The content of seed part 1 (DM-R-56 (1)); {@code environmentLabel} and {@code logo} may be {@code
 * null}.
 */
public record TenantSettingsPart(
    String customerName, String environmentLabel, String productDisplayName, TenantLogo logo) {}
