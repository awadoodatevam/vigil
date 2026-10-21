package vigil.setup;

import org.springframework.stereotype.Service;
import vigil.setup.api.Installation;
import vigil.shared.TenantId;
import vigil.tenancy.api.AliasRevision;
import vigil.tenancy.api.TenantService;

/** The tenant and alias operations of the installation (AR-I-95, AR-I-98, AR-S-07). */
@Service
class InstallationService implements Installation {

  static final String SET_BY = "installation token";

  private final TenantService tenants;

  InstallationService(TenantService tenants) {
    this.tenants = tenants;
  }

  @Override
  public TenantId createTenant(String name) {
    return tenants.createTenant(name, SeedImporter.SETUP);
  }

  @Override
  public AliasRevision setAlias(String hostName, TenantId tenantId) {
    return tenants.setAlias(hostName, tenantId, SET_BY);
  }
}
