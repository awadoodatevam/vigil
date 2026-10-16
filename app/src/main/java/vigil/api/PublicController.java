package vigil.api;

import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vigil.shared.TenantId;
import vigil.shared.VigilError;
import vigil.shared.VigilError.Code;
import vigil.tenancy.api.TenantLogo;
import vigil.tenancy.api.TenantService;

/** The operations before login: branding and logo of the host's tenant (AR-I-10, AR-I-11). */
@RestController
@Profile("!migrate")
class PublicController {

  private final TenantService tenants;

  PublicController(TenantService tenants) {
    this.tenants = tenants;
  }

  /** AR-I-10, AR-B-10. */
  @GetMapping("/api/v1/public/branding")
  @Public
  TenantSettings branding(TenantId tenant) {
    return TenantSettings.of(tenants.settings(tenant));
  }

  /** AR-I-11: the address is the same for every tenant, so the answer is not cached. */
  @GetMapping("/api/v1/public/logo")
  @Public
  ResponseEntity<byte[]> logo(TenantId tenant) {
    TenantLogo logo = tenants.logo(tenant).orElseThrow(() -> new VigilError(Code.NOT_FOUND));
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(logo.mediaType()))
        .cacheControl(CacheControl.noCache())
        .body(logo.bytes());
  }
}
