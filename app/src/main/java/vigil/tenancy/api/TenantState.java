package vigil.tenancy.api;

import java.util.Locale;

/** The states of a tenant (DM-SM-01). */
public enum TenantState {
  PROVISIONING,
  READY,
  FAILED;

  /** The stored key (Example: {@code ready}). */
  public String key() {
    return name().toLowerCase(Locale.ROOT);
  }
}
