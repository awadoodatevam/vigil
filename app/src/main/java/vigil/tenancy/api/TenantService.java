package vigil.tenancy.api;

import java.util.Optional;
import java.util.Set;
import vigil.shared.Actor;
import vigil.shared.TenantId;

/** Tenants, their settings and the tenant aliases (PM-M01). */
public interface TenantService {

  /** DM-SM01-T1: a new tenant in state {@code provisioning} (PM-M01-03). */
  TenantId createTenant(String name, Actor createdBy);

  /** DM-SM01-T2: {@code provisioning} to {@code ready}; the tenant MUST have settings. */
  void markReady(TenantId tenantId);

  /** DM-SM01-T3: {@code provisioning} to {@code failed}. */
  void markFailed(TenantId tenantId);

  Optional<TenantView> tenant(TenantId tenantId);

  /** The tenant's settings (PM-M01-02); {@code NOT_FOUND} when it has none. */
  TenantSettingsView settings(TenantId tenantId);

  Optional<TenantLogo> logo(TenantId tenantId);

  /**
   * Sets a host name to a {@code ready} tenant: the next revision of its alias (DM-E83-R1).
   * Publishes {@link TenantAliasChanged}.
   */
  AliasRevision setAlias(String hostName, TenantId tenantId, String setBy);

  /** The tenant of the host name's highest alias revision, or empty. */
  Optional<TenantId> resolveHost(String hostName);

  /** The tenants that the highest revision of some host name points to (AR-R-19). */
  Set<TenantId> activeTenants();

  boolean isActive(TenantId tenantId);

  /** The host name of the tenant's latest alias revision, or empty (PM-M01-04). */
  Optional<String> publicHostName(TenantId tenantId);
}
