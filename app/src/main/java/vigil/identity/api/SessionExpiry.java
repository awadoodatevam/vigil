package vigil.identity.api;

/** The session expiry sweep of the active tenants (AR-R-23, PM-R-11). */
public interface SessionExpiry {

  /**
   * Registers the sweep of every active tenant that has none and cancels the sweep of every tenant
   * no longer active. Called by restart step (6) and on {@code TenantAliasChanged}.
   */
  void matchActiveTenants();
}
