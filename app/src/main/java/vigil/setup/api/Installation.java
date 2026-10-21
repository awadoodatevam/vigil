package vigil.setup.api;

import vigil.shared.TenantId;
import vigil.tenancy.api.AliasRevision;

/** The installation operations of AR-I-95 and AR-I-98; requests of vigil-api. */
public interface Installation {

  /** A new tenant in state {@code provisioning}, created by the module actor vigil-setup. */
  TenantId createTenant(String name);

  /** Points {@code hostName} at a {@code ready} tenant (AR-B-01); set by "installation token". */
  AliasRevision setAlias(String hostName, TenantId tenantId);
}
