package vigil.api;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vigil.shared.TenantId;
import vigil.tenancy.api.TenantService;

/** The tenant settings for the banner and header (AR-I-15). */
@RestController
@Profile("!migrate")
class TenantController {

  private final TenantService tenants;

  TenantController(TenantService tenants) {
    this.tenants = tenants;
  }

  @GetMapping("/api/v1/tenant")
  @LoggedIn
  TenantSettings tenant(TenantId tenant) {
    return TenantSettings.of(tenants.settings(tenant));
  }
}
