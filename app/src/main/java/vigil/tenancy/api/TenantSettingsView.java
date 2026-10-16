package vigil.tenancy.api;

/** A tenant's settings (DM-E-02); {@code environmentLabel} is {@code null} when none. */
public record TenantSettingsView(
    String customerName, boolean hasLogo, String environmentLabel, String productDisplayName) {}
